package com.osrscompanion;

import com.osrscompanion.FarmPatchProgress.Snapshot;
import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.FarmRoute;
import com.google.common.collect.ImmutableSet;
import com.osrscompanion.runelite.farming.FarmingPatch;
import com.osrscompanion.runelite.farming.FarmingTracker;
import com.osrscompanion.runelite.farming.FarmingWorld;
import com.osrscompanion.runelite.farming.PatchPrediction;
import com.osrscompanion.runelite.farming.Produce;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.Notifier;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemVariationMapping;

/**
 * The guided farm run: the active route from the website, which stop you are on and,
 * per patch, whether it still needs a visit. Patch progress comes from RuneLite's
 * farming tracker (the Time Tracking plugin keeps it current while you farm), so
 * planting a patch ticks it off and the run moves to the next stop by itself.
 *
 * <p>Network work runs on OkHttp threads, game reads on the client thread; fields the
 * panel and overlays read are volatile or concurrent.
 */
@Slf4j
@Singleton
public class FarmRunManager {
	/** Re-read the route at most this often while the panel or a run uses it. */
	static final long REFRESH_INTERVAL_MS = 5 * 60_000;
	/** Farming cape and Farming cape (t): extra herbs when worn. */
	static final int FARMING_CAPE = 9810;
	static final int FARMING_CAPE_T = 9811;
	/** Rune pouches (regular and divine, and their locked versions): the runes inside count as carried. */
	static final Set<Integer> RUNE_POUCHES = ImmutableSet.of(
		ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER, ItemID.DIVINE_RUNE_POUCH, ItemID.DIVINE_RUNE_POUCH_TROUVER);
	/** How close to the stop the "wear your Farming cape" reminder fires. */
	private static final int CAPE_REMINDER_DISTANCE = 20;

	private final Client client;
	private final OSRSCompanionApiClient apiClient;
	private final RoadmapManager roadmapManager;

	private volatile FarmingWorld farmingWorld;
	private volatile FarmingTracker farmingTracker;

	private volatile FarmRoute route;
	private volatile List<FarmRoute.RouteSummary> routes = Collections.emptyList();
	private volatile String error;
	private volatile boolean loading;
	private volatile boolean loaded;
	private volatile long fetchedAtMs;

	private volatile boolean running;
	private volatile int stopIndex;
	private final Map<String, Snapshot> startState = new ConcurrentHashMap<>();
	private final Map<String, Status> status = new ConcurrentHashMap<>();
	private final Set<String> skipped = ConcurrentHashMap.newKeySet();
	private final Map<String, FarmingPatch> patchCache = new ConcurrentHashMap<>();

	private volatile Map<Integer, Integer> carried = Collections.emptyMap();
	/** The same counts by item variation (charges, colours) and by name (graceful's worn ids). */
	private volatile Map<Integer, Integer> carriedBase = Collections.emptyMap();
	private volatile Map<String, Integer> carriedNames = Collections.emptyMap();
	private volatile boolean capeWorn;
	/** The rune pouch changed (runes added, taken or cast): count again on the next tick. */
	private boolean pouchChanged;
	private int capeRemindedStop = -1;
	private volatile Map<Integer, Integer> leprechaun = Collections.emptyMap();

	/** The in-game farm run window, and the stop shown in its details (-1: the current one). */
	private volatile boolean open;
	private volatile int selectedStop = -1;

	private final OSRSCompanionConfig config;
	private final Notifier notifier;

	@Inject
	public FarmRunManager(Client client, OSRSCompanionApiClient apiClient, RoadmapManager roadmapManager, OSRSCompanionConfig config, Notifier notifier) {
		this.client = client;
		this.apiClient = apiClient;
		this.roadmapManager = roadmapManager;
		this.config = config;
		this.notifier = notifier;
	}

	/** The farming data the plugin builds at start-up. */
	public void setFarming(FarmingWorld world, FarmingTracker tracker) {
		this.farmingWorld = world;
		this.farmingTracker = tracker;
		patchCache.clear();
	}

	// ---- Loading -----------------------------------------------------------------

	public void refreshIfStale() {
		if (!loading && System.currentTimeMillis() - fetchedAtMs > REFRESH_INTERVAL_MS) {
			refresh();
		}
	}

	public void refresh() {
		String player = roadmapManager.getPlayerUsername();
		if (player == null) {
			error = "Log in to the game to load your farm route.";
			return;
		}
		if (!roadmapManager.hasAppKey()) {
			error = "Add your App Key in the OSRS Companion settings first.";
			return;
		}
		loading = true;
		fetchedAtMs = System.currentTimeMillis();
		apiClient.getFarmRoute(player, callback(null));
	}

	/** Switches the active route (on the website too) and loads it. A running guide stops. */
	public void activate(FarmRoute.RouteSummary summary) {
		String player = roadmapManager.getPlayerUsername();
		if (summary == null || player == null || (route != null && route.getId() == summary.getId())) {
			return;
		}
		stop();
		loading = true;
		apiClient.activateFarmRoute(summary.getId(), player, callback(summary.getName()));
	}

	private OSRSCompanionApiClient.RoadmapCallback<FarmRoute.Response> callback(String switchedTo) {
		return new OSRSCompanionApiClient.RoadmapCallback<FarmRoute.Response>() {
			@Override
			public void onSuccess(FarmRoute.Response result) {
				FarmRoute old = route;
				route = result.getRoute();
				routes = result.getRoutes();
				error = null;
				loaded = true;
				loading = false;
				// A different route (or one edited on the website) restarts the progress.
				if (old == null || route == null || old.getId() != route.getId()) {
					running = false;
					stopIndex = 0;
					status.clear();
					startState.clear();
					skipped.clear();
				} else if (stopIndex >= route.getStops().size()) {
					stopIndex = Math.max(0, route.getStops().size() - 1);
				}
			}

			@Override
			public void onError(String message) {
				error = (switchedTo == null ? "Could not load your farm route" : "Could not switch to " + switchedTo) + " (" + message + ").";
				loading = false;
			}
		};
	}

	// ---- The run -------------------------------------------------------------------

	/** Starts the guide: notes every patch's state now, then goes to the first stop with work. */
	public void start() {
		FarmRoute r = route;
		if (r == null || r.getStops().isEmpty()) {
			return;
		}
		startState.clear();
		status.clear();
		skipped.clear();
		for (FarmRoute.Stop stop : r.getStops()) {
			for (FarmRoute.Patch p : stop.getPatches()) {
				Snapshot s = snapshot(p);
				if (s != null) {
					startState.put(p.getKey(), s);
				}
			}
		}
		running = true;
		stopIndex = 0;
		capeRemindedStop = -1;
		updateStatuses();
		stopIndex = firstOpenFrom(0);
	}

	public void stop() {
		running = false;
	}

	public void next() {
		FarmRoute r = route;
		if (r != null && stopIndex < r.getStops().size() - 1) {
			stopIndex++;
		}
	}

	public void previous() {
		if (stopIndex > 0) {
			stopIndex--;
		}
	}

	public void goTo(int index) {
		FarmRoute r = route;
		if (r != null && index >= 0 && index < r.getStops().size()) {
			stopIndex = index;
		}
	}

	/** Skips the current stop's open patches and moves on. */
	public void skipStop() {
		FarmRoute.Stop stop = getCurrentStop();
		if (stop == null) {
			return;
		}
		for (FarmRoute.Patch p : stop.getPatches()) {
			if (statusOf(p).isOpen()) {
				skipped.add(p.getKey());
				status.put(p.getKey(), Status.SKIPPED);
			}
		}
		advance();
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		if (pouchChanged) {
			pouchChanged = false;
			recount();
		}
		if (open || running) {
			leprechaun = ToolLeprechaun.read(client);
		}
		if (!running || route == null) {
			return;
		}
		if (updateStatuses()) {
			advance();
		}
		remindCape();
	}

	/** True while the current stop still has herbs to pick and the Farming cape setting is on but the cape is not worn. */
	public boolean needsFarmingCape() {
		if (!config.farmingCapeForHerbs() || !running || isComplete() || capeWorn) {
			return false;
		}
		FarmRoute.Stop stop = getCurrentStop();
		return stop != null && stop.getPatches().stream().anyMatch(p -> isHerb(p) && statusOf(p) == Status.TODO);
	}

	private static boolean isHerb(FarmRoute.Patch p) {
		return p.getType() != null && p.getType().toLowerCase().contains("herb");
	}

	/** Once per stop, near it: a notification to put the Farming cape on before picking the herbs. */
	private void remindCape() {
		if (!needsFarmingCape() || capeRemindedStop == stopIndex || client.getLocalPlayer() == null) {
			return;
		}
		FarmRoute.Stop stop = getCurrentStop();
		WorldPoint at = client.getLocalPlayer().getWorldLocation();
		if (at.distanceTo2D(new WorldPoint(stop.getX(), stop.getY(), stop.getPlane())) <= CAPE_REMINDER_DISTANCE) {
			capeRemindedStop = stopIndex;
			notifier.notify("Farm run: put on your Farming cape before you pick the herbs.");
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		int id = event.getContainerId();
		if (id == InventoryID.INV || id == InventoryID.WORN) {
			recount();
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (RunePouchMonitor.isPouchVarbit(event.getVarbitId())) {
			pouchChanged = true;
		}
	}

	/** Counts the inventory and worn items, plus the runes in a carried rune pouch. Client thread. */
	private void recount() {
		Map<Integer, Integer> counts = new HashMap<>();
		Map<Integer, Integer> byBase = new HashMap<>();
		Map<String, Integer> byName = new HashMap<>();
		boolean cape = false;
		boolean pouch = false;
		for (int container : new int[]{InventoryID.INV, InventoryID.WORN}) {
			ItemContainer c = client.getItemContainer(container);
			if (c == null) {
				continue;
			}
			for (Item item : c.getItems()) {
				if (item.getId() > 0) {
					count(counts, byBase, byName, item.getId(), item.getQuantity());
					cape |= container == InventoryID.WORN && (item.getId() == FARMING_CAPE || item.getId() == FARMING_CAPE_T);
					pouch |= container == InventoryID.INV && RUNE_POUCHES.contains(item.getId());
				}
			}
		}
		if (pouch) {
			// The pouch's runes (from its varbits) are carried too, so the window marks them green.
			for (Map.Entry<Integer, Integer> rune : RunePouchMonitor.read(client).entrySet()) {
				count(counts, byBase, byName, rune.getKey(), rune.getValue());
			}
		}
		carried = counts;
		carriedBase = byBase;
		carriedNames = byName;
		capeWorn = cape;
	}

	/** Moves past a stop once none of its patches needs a visit. */
	private void advance() {
		FarmRoute.Stop stop = getCurrentStop();
		if (stop != null && !hasOpenPatch(stop)) {
			stopIndex = firstOpenFrom(stopIndex);
		}
	}

	private int firstOpenFrom(int from) {
		FarmRoute r = route;
		if (r == null) {
			return 0;
		}
		List<FarmRoute.Stop> stops = r.getStops();
		for (int i = from; i < stops.size(); i++) {
			if (hasOpenPatch(stops.get(i))) {
				return i;
			}
		}
		// Nothing left: stay on the last stop, which shows the run as complete.
		return Math.max(0, stops.size() - 1);
	}

	private boolean hasOpenPatch(FarmRoute.Stop stop) {
		for (FarmRoute.Patch p : stop.getPatches()) {
			if (statusOf(p).isOpen()) {
				return true;
			}
		}
		return false;
	}

	/** Re-reads every patch of the route; true when any status changed. Client thread. */
	private boolean updateStatuses() {
		FarmRoute r = route;
		if (r == null || client.getGameState() != GameState.LOGGED_IN) {
			return false;
		}
		boolean any = false;
		for (FarmRoute.Stop stop : r.getStops()) {
			for (FarmRoute.Patch p : stop.getPatches()) {
				Status next = skipped.contains(p.getKey()) ? Status.SKIPPED
					: FarmPatchProgress.classify(startState.get(p.getKey()), snapshot(p));
				if (status.put(p.getKey(), next) != next) {
					any = true;
				}
			}
		}
		return any;
	}

	private Snapshot snapshot(FarmRoute.Patch p) {
		FarmingTracker tracker = farmingTracker;
		FarmingPatch patch = findPatch(p);
		if (tracker == null || patch == null) {
			return null;
		}
		try {
			PatchPrediction prediction = tracker.predictPatch(patch);
			if (prediction == null || prediction.getCropState() == null) {
				return null;
			}
			boolean weeds = prediction.getProduce() == Produce.WEEDS || prediction.getProduce() == Produce.SCARECROW;
			return new Snapshot(prediction.getCropState(), weeds, prediction.getDoneEstimate());
		} catch (RuntimeException e) {
			log.debug("Could not read patch {}", p.getKey(), e);
			return null;
		}
	}

	/** The RuneLite patch for a route patch: same region name, varbit and patch type. */
	FarmingPatch findPatch(FarmRoute.Patch p) {
		FarmingWorld world = farmingWorld;
		if (world == null || p.getKey() == null) {
			return null;
		}
		FarmingPatch cached = patchCache.get(p.getKey());
		if (cached != null) {
			return cached;
		}
		for (Set<FarmingPatch> tab : world.getTabs().values()) {
			for (FarmingPatch fp : tab) {
				if (fp.getVarbit() == p.getVarbit() && fp.getRegion() != null && fp.getRegion().getName().equals(p.getRegion())
					&& fp.getImplementation().name().equals(p.getImpl())) {
					patchCache.put(p.getKey(), fp);
					return fp;
				}
			}
		}
		return null;
	}

	// ---- The window --------------------------------------------------------------------

	public boolean isOpen() {
		return open;
	}

	public void toggle() {
		if (open) {
			close();
		} else {
			openWindow();
		}
	}

	public void openWindow() {
		open = true;
		selectedStop = -1;
		// Always fetch on open, so a route just made or edited on the website shows up straight
		// away (the same route keeps its progress; see callback()).
		if (!loading) {
			refresh();
		}
	}

	public void close() {
		open = false;
	}

	/** The stop shown in the window's details: the one clicked, else the current one. */
	public int getSelectedStop() {
		FarmRoute r = route;
		int size = r == null ? 0 : r.getStops().size();
		int s = selectedStop >= 0 ? selectedStop : stopIndex;
		return size == 0 ? -1 : Math.min(s, size - 1);
	}

	public void selectStop(int index) {
		selectedStop = index;
	}

	/** How many of an item the tool leprechaun holds (tools: 1 when stored). */
	public int inLeprechaun(int itemId) {
		Integer n = leprechaun.get(itemId);
		return n == null ? 0 : n;
	}

	// ---- Read by the panel and overlays -------------------------------------------

	public FarmRoute getRoute() {
		return route;
	}

	public List<FarmRoute.RouteSummary> getRoutes() {
		return routes;
	}

	public String getError() {
		return error;
	}

	public boolean isLoading() {
		return loading;
	}

	public boolean isLoaded() {
		return loaded;
	}

	public boolean isRunning() {
		return running;
	}

	public int getStopIndex() {
		return stopIndex;
	}

	public FarmRoute.Stop getCurrentStop() {
		FarmRoute r = route;
		if (r == null || r.getStops().isEmpty()) {
			return null;
		}
		return r.getStops().get(Math.min(stopIndex, r.getStops().size() - 1));
	}

	/** Every patch handled: harvested and planted, not ready, or skipped. */
	public boolean isComplete() {
		FarmRoute r = route;
		if (!running || r == null) {
			return false;
		}
		for (FarmRoute.Stop s : r.getStops()) {
			if (hasOpenPatch(s)) {
				return false;
			}
		}
		return true;
	}

	public Status statusOf(FarmRoute.Patch p) {
		Status s = status.get(p.getKey());
		return s == null ? Status.TODO : s;
	}

	/** How many patches are handled out of all patches. */
	public int[] progress() {
		FarmRoute r = route;
		int done = 0;
		int total = 0;
		if (r != null) {
			for (FarmRoute.Stop s : r.getStops()) {
				for (FarmRoute.Patch p : s.getPatches()) {
					total++;
					if (!statusOf(p).isOpen()) {
						done++;
					}
				}
			}
		}
		return new int[]{done, total};
	}

	/**
	 * Item counts in the inventory plus worn equipment. Other variants count too: worn graceful has
	 * its own item id, jewellery its charges, the Farming cape a trimmed one.
	 */
	public int carried(int itemId) {
		if (itemId == FARMING_CAPE || itemId == FARMING_CAPE_T) {
			return carried.getOrDefault(FARMING_CAPE, 0) + carried.getOrDefault(FARMING_CAPE_T, 0);
		}
		Integer n = carried.get(itemId);
		if (n == null) {
			n = carriedBase.get(ItemVariationMapping.map(itemId));
		}
		if (n == null) {
			String name = nameOf(itemId);
			n = name == null ? null : carriedNames.get(name);
		}
		return n == null ? 0 : n;
	}

	private void count(Map<Integer, Integer> counts, Map<Integer, Integer> byBase, Map<String, Integer> byName, int itemId, int qty) {
		counts.merge(itemId, qty, Integer::sum);
		byBase.merge(ItemVariationMapping.map(itemId), qty, Integer::sum);
		String name = nameOf(itemId);
		if (name != null) {
			byName.merge(name, qty, Integer::sum);
		}
	}

	/** Whether a Farming cape (or the trimmed one) is worn. */
	public boolean isFarmingCapeWorn() {
		return capeWorn;
	}

	/** The route patch for a RuneLite patch (same region, varbit and type), or null when the route does not have it. */
	public FarmRoute.Patch routePatch(FarmingPatch fp) {
		FarmRoute r = route;
		if (r == null || fp == null || fp.getRegion() == null) {
			return null;
		}
		for (FarmRoute.Stop stop : r.getStops()) {
			for (FarmRoute.Patch p : stop.getPatches()) {
				if (p.getVarbit() == fp.getVarbit() && fp.getRegion().getName().equals(p.getRegion())
					&& fp.getImplementation().name().equals(p.getImpl())) {
					return p;
				}
			}
		}
		return null;
	}

	private String nameOf(int itemId) {
		ItemComposition comp = client.getItemDefinition(itemId);
		return comp == null || comp.getName() == null ? null : comp.getName().toLowerCase();
	}

	/** Whether the route (or the Farming cape setting) needs this item; variants count. For the bank filter. */
	public boolean needs(int itemId) {
		return neededStack(itemId) != null;
	}

	/** The loadout entry this item satisfies: the same item, a variant (charges, colours) or one of the same name (filled/empty). */
	public FarmRoute.Stack neededStack(int itemId) {
		int base = ItemVariationMapping.map(itemId);
		String name = null;
		for (FarmRoute.Stack s : loadout()) {
			if (s.getItem() == itemId || ItemVariationMapping.map(s.getItem()) == base) {
				return s;
			}
		}
		for (FarmRoute.Stack s : loadout()) {
			if (name == null) {
				name = nameOf(itemId);
				if (name == null) {
					return null;
				}
			}
			if (name.equals(nameOf(s.getItem()))) {
				return s;
			}
		}
		return null;
	}

	/** Where the guide points: the current stop's tile. */
	public WorldPoint getCurrentTarget() {
		FarmRoute.Stop s = getCurrentStop();
		return s == null ? null : new WorldPoint(s.getX(), s.getY(), s.getPlane());
	}

	/** Items worth highlighting right now: the teleport there, and seeds/payments for open patches. */
	public Map<Integer, Highlight> currentItems() {
		Map<Integer, Highlight> out = new HashMap<>();
		FarmRoute.Stop s = getCurrentStop();
		if (!running || s == null) {
			return out;
		}
		for (Integer id : s.getTravel().getItems()) {
			out.put(id, Highlight.TELEPORT);
		}
		for (FarmRoute.Patch p : s.getPatches()) {
			if (!statusOf(p).isOpen()) {
				continue;
			}
			if (p.getSeed() != null && p.getSeed() > 0) {
				out.putIfAbsent(p.getSeed(), Highlight.SEED);
			}
			for (FarmRoute.Stack pay : p.getProtect()) {
				out.putIfAbsent(pay.getItem(), Highlight.PAYMENT);
			}
		}
		return out;
	}

	/** The whole loadout (inventory and equipment) with how many of each the run needs. */
	public List<FarmRoute.Stack> loadout() {
		FarmRoute r = route;
		if (r == null) {
			return Collections.emptyList();
		}
		List<FarmRoute.Stack> all = new ArrayList<>(r.getEquipment());
		all.addAll(inventory());
		return all;
	}

	/** The route's inventory, plus the Farming cape when that setting is on and the route picks herbs. */
	public List<FarmRoute.Stack> inventory() {
		FarmRoute r = route;
		if (r == null) {
			return Collections.emptyList();
		}
		boolean herbs = r.getStops().stream().anyMatch(s -> s.getPatches().stream().anyMatch(FarmRunManager::isHerb));
		if (!config.farmingCapeForHerbs() || !herbs || r.getInventory().stream().anyMatch(s -> s.getItem() == FARMING_CAPE || s.getItem() == FARMING_CAPE_T)) {
			return r.getInventory();
		}
		List<FarmRoute.Stack> all = new ArrayList<>(r.getInventory());
		all.add(FarmRoute.Stack.extra(FARMING_CAPE, "Farming cape", "Wear before picking herbs"));
		return all;
	}

	enum Highlight {
		TELEPORT, SEED, PAYMENT
	}

	public void clear() {
		running = false;
		open = false;
		selectedStop = -1;
		leprechaun = Collections.emptyMap();
		route = null;
		routes = Collections.emptyList();
		loaded = false;
		error = null;
		status.clear();
		startState.clear();
		skipped.clear();
		carried = Collections.emptyMap();
	}
}
