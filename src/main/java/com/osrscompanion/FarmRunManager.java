package com.osrscompanion;

import com.google.common.collect.ImmutableSet;
import com.osrscompanion.FarmPatchProgress.Snapshot;
import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.runelite.farming.CompostState;
import com.osrscompanion.runelite.farming.CompostTracker;
import com.osrscompanion.runelite.farming.CropState;
import com.osrscompanion.runelite.farming.FarmingPatch;
import com.osrscompanion.runelite.farming.FarmingTracker;
import com.osrscompanion.runelite.farming.FarmingWorld;
import com.osrscompanion.runelite.farming.PatchPrediction;
import com.osrscompanion.runelite.farming.Produce;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

/**
 * The guided farm run: the active route from the website, which stop you are on and,
 * per patch, whether it still needs a visit. Patch progress comes from RuneLite's
 * farming tracker (the Time Tracking plugin keeps it current while you farm), so
 * planting a patch ticks it off and the run moves to the next stop by itself.
 *
 * <p>Once every patch is handled the run pops up a summary (like a finished roadmap goal)
 * and stops by itself when you leave the last stop. While a patch at the stop has no
 * compost yet, the compost is highlighted.
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
	/** Per elemental rune (air 556, water 555, earth 557, fire 554): the combination runes that also count as it. */
	static final Map<Integer, int[]> COMBO_RUNES = new HashMap<>();

	static {
		int mist = 4695;
		int dust = 4696;
		int smoke = 4697;
		int steam = 4694;
		int mud = 4698;
		int lava = 4699;
		COMBO_RUNES.put(556, new int[]{mist, dust, smoke});
		COMBO_RUNES.put(555, new int[]{mist, steam, mud});
		COMBO_RUNES.put(557, new int[]{dust, mud, lava});
		COMBO_RUNES.put(554, new int[]{smoke, steam, lava});
	}
	/** Rune pouches (regular and divine, and their locked versions): the runes inside count as carried. */
	static final Set<Integer> RUNE_POUCHES = ImmutableSet.of(
		ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER, ItemID.DIVINE_RUNE_POUCH, ItemID.DIVINE_RUNE_POUCH_TROUVER);
	/** How close to the stop the "wear your Farming cape" reminder fires (also "still at the stop" for compost and finishing). */
	private static final int CAPE_REMINDER_DISTANCE = 20;
	/** A finished run stops once you leave the last stop, or after this many ticks (a minute). */
	private static final int FINISH_DELAY_TICKS = 100;
	/**
	 * The last minigame (Grouping) teleport, in minutes since the epoch: RuneLite's deprecated
	 * {@code VarPlayer.LAST_MINIGAME_TELEPORT} (the gameval {@code VarPlayerID} lists it under a cache name).
	 */
	static final int LAST_MINIGAME_TELEPORT_VARP = 888;
	/** Every minigame teleport waits this long after the last one (OSRS Wiki: 2,000 ticks). */
	static final int MINIGAME_COOLDOWN_MINUTES = 20;
	/** Compost items, best first: what to point at when the route's own one is not carried. */
	private static final int[] COMPOST_ITEMS = {
		ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED, ItemID.BUCKET_ULTRACOMPOST, ItemID.BUCKET_SUPERCOMPOST, ItemID.BUCKET_COMPOST};
	/** The game's messages when compost goes on a patch (or it already has some); RuneLite's CompostTracker reads the same. */
	private static final Pattern COMPOST_MESSAGE = Pattern.compile(
		"You treat the .+ with (ultra|super|)compost\\.|^The .+ has been treated with (ultra|super|)compost"
			+ "|This .+ has already been (treated|fertilised) with (ultra|super|)compost");
	static final String FINISHED_TITLE = "<col=f28c28>Farm Run Complete!</col>";

	private final Client client;
	private final OSRSCompanionApiClient apiClient;
	private final RoadmapManager roadmapManager;

	private volatile FarmingWorld farmingWorld;
	private volatile FarmingTracker farmingTracker;
	private volatile CompostTracker compostTracker;

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
	/**
	 * Count the carried items again on the next tick: the rune pouch changed (runes added, taken or
	 * cast), or the window opened or a run started before any inventory change was seen (the plugin
	 * started while logged in), so the pouch's runes show as carried straight away.
	 */
	private volatile boolean recountPending;
	private int capeRemindedStop = -1;
	private volatile Map<Integer, Integer> leprechaun = Collections.emptyMap();

	/** Route patches (keys) at the current stop, or the one just left while you are still there, without compost. */
	private volatile Set<String> needCompost = Collections.emptySet();
	/**
	 * How many buckets of compost the run still needs, counted live from the patches you will actually
	 * plant (not still growing, not diseased, taking compost, without compost yet); -1: not known, use
	 * the website's count. The route's inventory with that count, for the route it was made for.
	 */
	private volatile int compostToBring = -1;
	private volatile List<FarmRoute.Stack> compostAdjusted;
	private volatile FarmRoute compostAdjustedFor;
	/** Composted this run, seen from the game's message (covers the Time Tracking plugin being off). */
	private final Set<String> composted = ConcurrentHashMap.newKeySet();
	/** The route patch compost was last used on, until the game's message confirms it. */
	private String compostTarget;
	/** The stop the run just moved on from: its patches can still take compost while you are there. */
	private volatile int previousStop = -1;
	/** Minutes left on the minigame teleport cooldown when the current stop uses one (0: ready). */
	private volatile int minigameCooldown;
	/** The last minigame teleport (minutes since the epoch, 0: never), read on the client thread during a run. */
	private volatile long lastMinigameTeleport;
	/** Farming XP when the run started, and the tick it was first complete (-1: not yet). */
	private int startXp;
	private int completedTick = -1;

	/** The in-game farm run window, and the stop shown in its details (-1: the current one). */
	private volatile boolean open;
	private volatile int selectedStop = -1;

	private final OSRSCompanionConfig config;
	private final Notifier notifier;
	private final GoalCompletionNotifier popups;

	@Inject
	public FarmRunManager(Client client, OSRSCompanionApiClient apiClient, RoadmapManager roadmapManager, OSRSCompanionConfig config, Notifier notifier,
		GoalCompletionNotifier popups) {
		this.client = client;
		this.apiClient = apiClient;
		this.roadmapManager = roadmapManager;
		this.config = config;
		this.notifier = notifier;
		this.popups = popups;
	}

	/** The farming data the plugin builds at start-up. */
	public void setFarming(FarmingWorld world, FarmingTracker tracker, CompostTracker compost) {
		this.farmingWorld = world;
		this.farmingTracker = tracker;
		this.compostTracker = compost;
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
		composted.clear();
		compostTarget = null;
		previousStop = -1;
		completedTick = -1;
		startXp = client.getSkillExperience(Skill.FARMING);
		for (FarmRoute.Stop stop : r.getStops()) {
			for (FarmRoute.Patch p : stop.getPatches()) {
				Snapshot s = snapshot(p);
				if (s != null) {
					startState.put(p.getKey(), s);
				}
			}
		}
		running = true;
		// Always step 1: going to another stop is done from the window after starting.
		stopIndex = 0;
		capeRemindedStop = -1;
		recountPending = true;
		updateStatuses();
		if (log.isDebugEnabled()) {
			Map<Status, Integer> counts = new java.util.EnumMap<>(Status.class);
			for (FarmRoute.Stop stop : r.getStops()) {
				for (FarmRoute.Patch p : stop.getPatches()) {
					counts.merge(statusOf(p), 1, Integer::sum);
				}
			}
			log.debug("Farm run: started '{}' at step 1: {} (complete: {})", r.getName(), counts, isComplete());
		}
	}

	public void stop() {
		log.debug("Farm run: stopped");
		running = false;
		needCompost = Collections.emptySet();
		completedTick = -1;
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
		if (recountPending) {
			recountPending = false;
			recount();
		}
		if (open || running) {
			leprechaun = ToolLeprechaun.read(client);
			updateCompostToBring();
		}
		if (!running || route == null) {
			return;
		}
		if (updateStatuses()) {
			advance();
		}
		remindCape();
		updateCompost();
		updateMinigameCooldown();
		checkFinished();
	}

	// ---- Finishing -----------------------------------------------------------------------

	/**
	 * A finished run: the summary popup straight away, then it stops by itself once you leave the
	 * last stop (time to pay the gardener or compost), or after a minute. Client thread.
	 */
	private void checkFinished() {
		if (!isComplete()) {
			completedTick = -1;
			return;
		}
		if (completedTick < 0) {
			completedTick = client.getTickCount();
			log.debug("Farm run: complete at step {} ({} / {} patches handled)", stopIndex + 1, progress()[0], progress()[1]);
			int[] done = finishedCounts();
			if (done[0] == 0) {
				// Nothing was planted: every patch is still growing or skipped.
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Farm run: nothing to do yet - every patch is still growing or skipped.", null);
				stop();
				return;
			}
			showFinished(done);
			return;
		}
		WorldPoint at = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getWorldLocation();
		WorldPoint last = getCurrentTarget();
		boolean left = at != null && last != null && (at.getPlane() != last.getPlane() || at.distanceTo2D(last) > CAPE_REMINDER_DISTANCE);
		// The minute's grace waits while a patch here still has no compost (you are still at it).
		boolean timeUp = client.getTickCount() - completedTick >= FINISH_DELAY_TICKS && needCompost.isEmpty();
		if (left || timeUp) {
			stop();
		}
	}

	/** Patches planted this run, and their estimated profit. */
	private int[] finishedCounts() {
		int planted = 0;
		long gp = 0;
		FarmRoute r = route;
		if (r != null) {
			for (FarmRoute.Stop s : r.getStops()) {
				for (FarmRoute.Patch p : s.getPatches()) {
					if (statusOf(p) == Status.DONE) {
						planted++;
						gp += p.getGp();
					}
				}
			}
		}
		return new int[]{planted, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, gp))};
	}

	/** The summary popup (when that setting is on) and a notification: patches planted, Farming XP gained, estimated profit. */
	private void showFinished(int[] done) {
		FarmRoute r = route;
		int xp = Math.max(0, client.getSkillExperience(Skill.FARMING) - startXp);
		if (config.farmRunPopup()) {
			popups.showMessage(FINISHED_TITLE, finishedBody(r == null ? null : r.getName(), done[0], xp, done[1]));
		}
		notifier.notify("Farm run complete: " + done[0] + (done[0] == 1 ? " patch" : " patches")
			+ (xp > 0 ? ", " + QuantityFormatter.formatNumber(xp) + " Farming XP" : "") + ".");
	}

	/** The popup body: the patches in white, then the XP gained and estimated profit when known, then the route name. */
	static String finishedBody(String routeName, int patches, int xp, int gp) {
		StringBuilder sb = new StringBuilder("<col=ffffff>").append(patches).append(patches == 1 ? " patch" : " patches").append(" planted</col>");
		StringBuilder numbers = new StringBuilder();
		if (xp > 0) {
			numbers.append(QuantityFormatter.formatNumber(xp)).append(" Farming XP");
		}
		if (gp != 0) {
			numbers.append(numbers.length() > 0 ? " - " : "").append("~").append(QuantityFormatter.quantityToStackSize(gp)).append(" gp");
		}
		if (numbers.length() > 0) {
			sb.append("<br><col=ffffff>").append(numbers).append("</col>");
		}
		if (routeName != null && !routeName.trim().isEmpty()) {
			sb.append("<br><col=c6c6c6>").append(Text.removeTags(routeName)).append("</col>");
		}
		return sb.toString();
	}

	// ---- Compost and the minigame teleport ---------------------------------------------

	/**
	 * Which patches of the current stop (and of the stop just finished, while you are still there)
	 * have no compost: still to plant, or planted this run without any. Compost state comes from
	 * RuneLite's Time Tracking (CompostTracker) and from the game's messages this run. Client thread.
	 */
	private void updateCompost() {
		Set<String> out = new HashSet<>();
		FarmRoute r = route;
		if (r != null) {
			addNeedingCompost(getCurrentStop(), out);
			int prev = previousStop;
			if (prev >= 0 && prev < r.getStops().size() && prev != stopIndex && client.getLocalPlayer() != null) {
				FarmRoute.Stop stop = r.getStops().get(prev);
				WorldPoint at = client.getLocalPlayer().getWorldLocation();
				if (at.distanceTo2D(new WorldPoint(stop.getX(), stop.getY(), stop.getPlane())) <= CAPE_REMINDER_DISTANCE) {
					addNeedingCompost(stop, out);
				} else {
					previousStop = -1;
				}
			}
		}
		needCompost = out;
	}

	private void addNeedingCompost(FarmRoute.Stop stop, Set<String> out) {
		if (stop == null) {
			return;
		}
		for (FarmRoute.Patch p : stop.getPatches()) {
			Status s = statusOf(p);
			if ((s == Status.TODO || s == Status.DONE) && p.getSeed() != null && p.getSeed() > 0 && p.takesCompost() && !hasCompost(p)) {
				out.add(p.getKey());
			}
		}
	}

	private boolean hasCompost(FarmRoute.Patch p) {
		if (composted.contains(p.getKey())) {
			return true;
		}
		CompostTracker tracker = compostTracker;
		FarmingPatch patch = findPatch(p);
		if (tracker == null || patch == null) {
			return false;
		}
		try {
			CompostState state = tracker.getCompostState(patch);
			return state != null;
		} catch (RuntimeException e) {
			log.debug("Could not read the compost on {}", p.getKey(), e);
			return false;
		}
	}

	/**
	 * Counts the compost the run needs from the patches you will actually plant, so the bring list asks
	 * for one bucket per such patch rather than trusting the website's count from the last sync. Before
	 * a run: every route patch that takes compost and is not still growing (or diseased: that is cured,
	 * not replanted). During a run: the patches still to do, plus those planted without compost yet.
	 * Compost bins and patches that take no compost never count. Client thread.
	 */
	private void updateCompostToBring() {
		FarmRoute r = route;
		if (r == null || farmingTracker == null || client.getGameState() != GameState.LOGGED_IN) {
			compostToBring = -1;
			compostAdjustedFor = null;
			return;
		}
		int n = 0;
		for (FarmRoute.Stop stop : r.getStops()) {
			for (FarmRoute.Patch p : stop.getPatches()) {
				if (p.getSeed() == null || p.getSeed() <= 0 || !p.takesCompost()) {
					continue;
				}
				if (running) {
					Snapshot start = startState.get(p.getKey());
					Status s = statusOf(p);
					if (start != null && start.state == CropState.DISEASED) {
						continue;
					}
					if (s == Status.TODO) {
						// Composted before planting (seen this run) needs no more; RuneLite's stored compost is
						// not trusted here, as it is only cleared once the patch is seen harvested.
						Snapshot now = snapshot(p);
						if (!composted.contains(p.getKey()) && (now == null || now.state != CropState.DISEASED)) {
							n++;
						}
					} else if (s == Status.DONE && !hasCompost(p)) {
						n++;
					}
				} else {
					Snapshot now = snapshot(p);
					if (now == null || (!now.growingCrop() && now.state != CropState.DISEASED)) {
						n++;
					}
				}
			}
		}
		if (n != compostToBring || compostAdjustedFor != r) {
			compostAdjusted = withCompost(r.getInventory(), n);
			compostToBring = n;
			compostAdjustedFor = r;
		}
	}

	/** The inventory with the compost buckets set to this many (left out at 0). A bottomless bucket stays as it is. */
	static List<FarmRoute.Stack> withCompost(List<FarmRoute.Stack> inventory, int buckets) {
		List<FarmRoute.Stack> out = new ArrayList<>(inventory.size());
		for (FarmRoute.Stack s : inventory) {
			if (!"Compost".equals(s.getWhy()) || !isCompostBucket(s.getItem())) {
				out.add(s);
			} else if (buckets > 0) {
				out.add(s.withQty(buckets));
			}
		}
		return Collections.unmodifiableList(out);
	}

	private static boolean isCompostBucket(int itemId) {
		return itemId == ItemID.BUCKET_ULTRACOMPOST || itemId == ItemID.BUCKET_SUPERCOMPOST || itemId == ItemID.BUCKET_COMPOST;
	}

	/** Minutes left before the next minigame teleport, from the game's last-use time. Client thread. */
	private void updateMinigameCooldown() {
		lastMinigameTeleport = client.getVarpValue(LAST_MINIGAME_TELEPORT_VARP);
		FarmRoute.Stop stop = getCurrentStop();
		minigameCooldown = stop == null || !stop.getTravel().isMinigame() ? 0 : minigameCooldownLeft();
	}

	/** Minutes before any minigame teleport can be used again (0: ready, or never used). */
	private int minigameCooldownLeft() {
		long last = lastMinigameTeleport;
		long now = System.currentTimeMillis() / 60_000L;
		return last <= 0 ? 0 : (int) Math.max(0, last + MINIGAME_COOLDOWN_MINUTES - now);
	}

	/**
	 * The way to a stop: during a run, a minigame teleport still on its cooldown gives way to the
	 * route's fallback (the next fastest way there), when the website sent one.
	 */
	public FarmRoute.Travel travelOf(FarmRoute.Stop stop) {
		FarmRoute.Travel t = stop.getTravel();
		return running && t.isMinigame() && t.getFallback() != null && minigameCooldownLeft() > 0 ? t.getFallback() : t;
	}

	/** Compost (or Fertile Soil) used on a route patch: remember which, until the game's message says it worked. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e) {
		if (!running || e.getMenuAction() != MenuAction.WIDGET_TARGET_ON_GAME_OBJECT) {
			return;
		}
		Widget selected = client.getSelectedWidget();
		if (selected == null || (selected.getId() != InterfaceID.MagicSpellbook.FERTILE_SOIL && !isCompostItem(selected.getItemId()))) {
			return;
		}
		ObjectComposition def = client.getObjectDefinition(e.getId());
		int varbit = def == null ? -1 : def.getVarbitId();
		compostTarget = null;
		FarmRoute r = route;
		int prev = previousStop;
		FarmRoute.Stop[] near = {getCurrentStop(), r != null && prev >= 0 && prev < r.getStops().size() ? r.getStops().get(prev) : null};
		for (FarmRoute.Stop s : near) {
			if (s == null) {
				continue;
			}
			for (FarmRoute.Patch p : s.getPatches()) {
				if (p.getVarbit() == varbit && compostTarget == null) {
					compostTarget = p.getKey();
				}
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage e) {
		if (compostTarget == null || (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM)) {
			return;
		}
		if (COMPOST_MESSAGE.matcher(e.getMessage()).find()) {
			composted.add(compostTarget);
			compostTarget = null;
		}
	}

	private static boolean isCompostItem(int itemId) {
		for (int id : COMPOST_ITEMS) {
			if (id == itemId) {
				return true;
			}
		}
		return false;
	}

	/** True while this route patch still needs compost (see {@link #updateCompost()}). */
	public boolean needsCompost(FarmRoute.Patch p) {
		return running && needCompost.contains(p.getKey());
	}

	/** True while a patch at (or just behind) the current stop still needs compost. */
	public boolean hasCompostToDo() {
		return running && !needCompost.isEmpty();
	}

	/** The stop just left while one of its patches (you are still there) has no compost yet, else null. */
	public FarmRoute.Stop compostStopBehind() {
		FarmRoute r = route;
		int prev = previousStop;
		if (!running || r == null || prev < 0 || prev >= r.getStops().size() || prev == stopIndex) {
			return null;
		}
		Set<String> keys = needCompost;
		FarmRoute.Stop stop = r.getStops().get(prev);
		for (FarmRoute.Patch p : stop.getPatches()) {
			if (keys.contains(p.getKey())) {
				return stop;
			}
		}
		return null;
	}

	/** Patches of the stop just left (you are still there) that have no compost yet. */
	public int compostBehind() {
		Set<String> keys = needCompost;
		FarmRoute.Stop stop = getCurrentStop();
		if (!running || keys.isEmpty() || stop == null) {
			return 0;
		}
		int here = 0;
		for (FarmRoute.Patch p : stop.getPatches()) {
			if (keys.contains(p.getKey())) {
				here++;
			}
		}
		return keys.size() - here;
	}

	/** Minutes before the minigame teleport to the current stop can be used again (0: ready). */
	public int minigameCooldownMinutes() {
		return running ? minigameCooldown : 0;
	}

	/** The route's compost (bottomless bucket, ultracompost, supercompost), or null when it brings none. */
	public FarmRoute.Stack compostStack() {
		FarmRoute r = route;
		if (r == null) {
			return null;
		}
		for (FarmRoute.Stack s : r.getInventory()) {
			if ("Compost".equals(s.getWhy())) {
				return s;
			}
		}
		return null;
	}

	/** The compost to point at: the route's when carried (not an empty bucket), else the best compost carried, else the route's. */
	int compostItem() {
		FarmRoute.Stack s = compostStack();
		Map<Integer, Integer> have = carried;
		if (s != null && s.getItem() != ItemID.BOTTOMLESS_COMPOST_BUCKET && have.getOrDefault(s.getItem(), 0) > 0) {
			return s.getItem();
		}
		for (int id : COMPOST_ITEMS) {
			if (have.getOrDefault(id, 0) > 0) {
				return id;
			}
		}
		return s == null ? ItemID.BUCKET_ULTRACOMPOST : s.getItem();
	}

	/**
	 * True while the current stop still has herbs to pick and the cape is not worn: the setting is on and
	 * you have 99 Farming (so can have the cape).
	 */
	public boolean needsFarmingCape() {
		if (!config.farmingCapeForHerbs() || !running || isComplete() || capeWorn || client.getRealSkillLevel(Skill.FARMING) < 99) {
			return false;
		}
		FarmRoute.Stop stop = getCurrentStop();
		return stop != null && stop.getPatches().stream().anyMatch(p -> isHerb(p) && statusOf(p) == Status.TODO);
	}

	/**
	 * With "Force Farming cape" on: a herb patch of the run still to pick while the cape is not worn (and you
	 * have 99 Farming). Its label then says to wear the cape and picking it is not the left click.
	 */
	public boolean capeBlocks(FarmRoute.Patch p) {
		return config.forceFarmingCape() && running && !capeWorn && isHerb(p) && statusOf(p) == Status.TODO
			&& client.getRealSkillLevel(Skill.FARMING) >= 99;
	}

	/** With "Force Farming cape" on: the cape is not worn while herbs of the run are still to pick (the inventory highlights it). */
	public boolean capeMissing() {
		FarmRoute r = route;
		if (!config.forceFarmingCape() || !running || capeWorn || r == null || client.getRealSkillLevel(Skill.FARMING) < 99) {
			return false;
		}
		return r.getStops().stream().anyMatch(s -> s.getPatches().stream().anyMatch(p -> isHerb(p) && statusOf(p) == Status.TODO));
	}

	/** "Pick" on a herb patch the cape blocks moves below "Walk here", so a left click walks instead. */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded e) {
		if (!running || capeWorn || !config.forceFarmingCape() || !"Pick".equals(e.getOption())) {
			return;
		}
		ObjectComposition def = client.getObjectDefinition(e.getIdentifier());
		int varbit = def == null ? -1 : def.getVarbitId();
		FarmRoute r = route;
		if (varbit < 0 || r == null) {
			return;
		}
		for (FarmRoute.Stop s : r.getStops()) {
			for (FarmRoute.Patch p : s.getPatches()) {
				if (p.getVarbit() == varbit && capeBlocks(p)) {
					e.getMenuEntry().setDeprioritized(true);
					return;
				}
			}
		}
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
			String text = "Farm run: put on your Farming cape before you pick the herbs (5% more herbs).";
			// In the chat too: RuneLite only shows notifications while the game is not focused, by default.
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff9040>" + text + "</col>", null);
			notifier.notify(text);
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
			recountPending = true;
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

	/**
	 * Moves past a stop once none of its patches needs a visit. When no later stop has work the run
	 * is finished and stays on this stop (where you are), so it can still point out the compost here
	 * and stops by itself once you walk away.
	 */
	private void advance() {
		FarmRoute.Stop stop = getCurrentStop();
		if (stop != null && !hasOpenPatch(stop)) {
			int from = stopIndex;
			int next = nextOpenFrom(stopIndex);
			if (next >= 0 && next != from) {
				stopIndex = next;
				previousStop = from;
			}
		}
	}

	/** The first stop from this one on with a patch still to do, or -1 when there is none. */
	private int nextOpenFrom(int from) {
		FarmRoute r = route;
		if (r == null) {
			return -1;
		}
		List<FarmRoute.Stop> stops = r.getStops();
		for (int i = Math.max(0, from); i < stops.size(); i++) {
			if (hasOpenPatch(stops.get(i))) {
				return i;
			}
		}
		return -1;
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
			boolean grown = prediction.getStages() > 1 && prediction.getStage() >= prediction.getStages() - 1;
			return new Snapshot(prediction.getCropState(), weeds, prediction.getDoneEstimate(), grown);
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
		recountPending = true;
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

	/**
	 * The run is finished: every patch from the current stop to the end of the route is handled
	 * (harvested and planted, not ready, or skipped). Patches at earlier stops you moved on from
	 * with "next" count as passed over, so they no longer keep the run from finishing.
	 */
	public boolean isComplete() {
		FarmRoute r = route;
		if (!running || r == null || r.getStops().isEmpty()) {
			return false;
		}
		return nextOpenFrom(Math.min(stopIndex, r.getStops().size() - 1)) < 0;
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
		int[] combos = COMBO_RUNES.get(itemId);
		if (combos != null) {
			// An elemental rune: the combination runes that count as it are carried too (dust: air and earth).
			int n = carried.getOrDefault(itemId, 0);
			for (int combo : combos) {
				n += carried.getOrDefault(combo, 0);
			}
			return n;
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

	/** Item names (lower case) read on the client thread, for the side panel which paints on Swing's thread. */
	private final Map<Integer, String> names = new java.util.concurrent.ConcurrentHashMap<>();

	/** The item's name in lower case; off the client thread only when it was read there before, else null. */
	private String nameOf(int itemId) {
		String cached = names.get(itemId);
		if (cached != null || !client.isClientThread()) {
			return cached;
		}
		ItemComposition comp = client.getItemDefinition(itemId);
		String name = comp == null || comp.getName() == null ? null : comp.getName().toLowerCase();
		if (name != null) {
			names.put(itemId, name);
		}
		return name;
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

	/** Items worth highlighting right now: the teleport there, seeds/payments for open patches, compost while a patch has none. */
	public Map<Integer, Highlight> currentItems() {
		Map<Integer, Highlight> out = new HashMap<>();
		FarmRoute.Stop s = getCurrentStop();
		if (!running || s == null) {
			return out;
		}
		if (!needCompost.isEmpty()) {
			out.put(compostItem(), Highlight.COMPOST);
		}
		for (Integer id : travelOf(s).getItems()) {
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

	/**
	 * The route's inventory (its compost counted from the patches you will actually plant), plus the
	 * Farming cape when that setting is on and the route picks herbs.
	 */
	public List<FarmRoute.Stack> inventory() {
		FarmRoute r = route;
		if (r == null) {
			return Collections.emptyList();
		}
		List<FarmRoute.Stack> inv = compostAdjustedFor == r && compostAdjusted != null ? compostAdjusted : r.getInventory();
		boolean herbs = r.getStops().stream().anyMatch(s -> s.getPatches().stream().anyMatch(FarmRunManager::isHerb));
		boolean listed = inv.stream().anyMatch(s -> s.getItem() == FARMING_CAPE || s.getItem() == FARMING_CAPE_T)
			|| r.getEquipment().stream().anyMatch(s -> s.getItem() == FARMING_CAPE || s.getItem() == FARMING_CAPE_T);
		if (!config.farmingCapeForHerbs() || !herbs || listed || client.getRealSkillLevel(Skill.FARMING) < 99) {
			return inv;
		}
		List<FarmRoute.Stack> all = new ArrayList<>(inv);
		all.add(FarmRoute.Stack.extra(FARMING_CAPE, "Farming cape", "Wear before picking herbs"));
		return all;
	}

	enum Highlight {
		TELEPORT, SEED, PAYMENT, COMPOST
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
		composted.clear();
		needCompost = Collections.emptySet();
		previousStop = -1;
		completedTick = -1;
		carried = Collections.emptyMap();
		compostToBring = -1;
		compostAdjusted = null;
		compostAdjustedFor = null;
	}
}
