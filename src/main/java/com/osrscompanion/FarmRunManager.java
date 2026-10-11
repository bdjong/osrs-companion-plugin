package com.osrscompanion;

import lombok.RequiredArgsConstructor;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.FarmRoute;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
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
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import net.runelite.client.util.QuantityFormatter;

/**
 * The guided farm run: the active route from the website, which stop you are on and, per patch, whether it still
 * needs a visit. The website works the run out (POST /api/runelite/farm-run): every action here (start, next, skip,
 * compost used...) and every change RuneLite's Time Tracking stores while you farm goes there with that data, and
 * the run's state comes back: planting a patch ticks it off and the run moves to the next stop by itself.
 *
 * <p>Kept here, as they need the game at once: what you carry, the Farming cape, the left-click swaps, the minigame
 * teleport's cooldown, where you are (finishing, compost at the stop just left). Once every patch is handled the run
 * pops up a summary and stops by itself when you leave the last stop.
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
public class FarmRunManager {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "farm-run";

	/** A backstop: website changes are picked up by the web version check ({@link WebVersionWatcher}). */
	static final long REFRESH_INTERVAL_MS = 10 * 60_000;
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
	static final java.util.Set<Integer> RUNE_POUCHES = ImmutableSet.of(
		ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER, ItemID.DIVINE_RUNE_POUCH, ItemID.DIVINE_RUNE_POUCH_TROUVER);
	/** How close to the stop the "wear your Farming cape" reminder fires (also "still at the stop" for compost and finishing). */
	private static final int CAPE_REMINDER_DISTANCE = 20;
	/** A finished run stops once you leave the last stop, or after this many ticks (a minute). */
	private static final int FINISH_DELAY_TICKS = 100;
	/** While a run goes (or the window is open), the state is asked for again this often even when nothing changed. */
	private static final int SYNC_EVERY_TICKS = 50;
	/** The last minigame (Grouping) teleport, in minutes since the epoch. */
	static final int LAST_MINIGAME_TELEPORT_VARP = 888;
	/** Every minigame teleport waits this long after the last one (OSRS Wiki: 2,000 ticks). */
	static final int MINIGAME_COOLDOWN_MINUTES = 20;
	/** Compost items, best first: what to point at when the route's own one is not carried. */
	private static final int[] COMPOST_ITEMS = {
		ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED, ItemID.BUCKET_ULTRACOMPOST, ItemID.BUCKET_SUPERCOMPOST, ItemID.BUCKET_COMPOST};
	/** The game's messages when compost goes on a patch (or it already has some). */
	private static final Pattern COMPOST_MESSAGE = Pattern.compile(
		"You treat the .+ with (ultra|super|)compost\\.|^The .+ has been treated with (ultra|super|)compost"
			+ "|This .+ has already been (treated|fertilised) with (ultra|super|)compost");
	static final String FINISHED_TITLE = "<col=f28c28>Farm run complete</col>";
	static final String READY_TITLE = "<col=f28c28>Farm route ready</col>";
	/** Where the last run is kept (per account), for the route-ready notification. */
	static final String READY_KEY = "farmRouteReady";

	/** The run's state, as the website answers it. */
	static final class State {
		int route_id;
		boolean running;
		int stop_index;
		int previous_stop = -1;
		boolean complete;
		Map<String, PatchState> patches = Collections.emptyMap();
		int compost_to_bring = -1;
		long next_run_at = -1;
		int planted;
		int gp;
		FarmRouteReady last_run;
	}

	static final class PatchState {
		Status status = Status.TODO;
		boolean harvestable;
		String step;
		boolean compost;
	}

	private final Client client;
	private final OSRSCompanionApiClient apiClient;
	private final RoadmapManager roadmapManager;

	@Getter
	private volatile FarmRoute route;
	@Getter
	private volatile List<FarmRoute.RouteSummary> routes = Collections.emptyList();
	@Getter
	private volatile String error;
	@Getter
	private volatile boolean loading;
	@Getter
	private volatile boolean loaded;
	private volatile long fetchedAtMs;

	@Getter
	private volatile boolean running;
	@Getter
	private volatile int stopIndex;
	/** The website's last answer for the route (null: none yet). */
	private volatile State state;

	// Requests to the website, one at a time and in order (client thread and OkHttp threads).
	private final Deque<Map<String, Object>> queue = new ArrayDeque<>();
	private boolean inFlight;
	private boolean syncPending;
	private int lastSyncTick;

	private volatile Map<Integer, Integer> carried = Collections.emptyMap();
	/** The same counts by item variation (charges, colours) and by name (graceful's worn ids). */
	private volatile Map<Integer, Integer> carriedBase = Collections.emptyMap();
	private volatile Map<String, Integer> carriedNames = Collections.emptyMap();
	private volatile boolean capeWorn;
	/** Count the carried items again on the next tick (the rune pouch changed, or the window opened). */
	private volatile boolean recountPending;
	private int capeRemindedStop = -1;
	private volatile Map<Integer, Integer> leprechaun = Collections.emptyMap();

	/** The route patch compost was last used on, until the game's message confirms it. */
	private String compostTarget;
	/** The stop just left that the website still points compost out at, until you walk away from it. */
	private int awayFrom = -1;
	/** The last minigame teleport (minutes since the epoch, 0: never), read on the client thread during a run. */
	private volatile long lastMinigameTeleport;
	/** Minutes left on the minigame teleport cooldown when the current stop uses one (0: ready). */
	private volatile int minigameCooldown;
	/** Farming XP when the run started, the tick it was first complete (-1: not yet), ticks it was going. */
	private int startXp;
	private int lastXp;
	private int completedTick = -1;
	private volatile int runTicks;
	/** The run started on a special world (Leagues, DMM...): not recorded. */
	private volatile boolean specialMode;
	/** Who started the run (a run stopped after logging out is still theirs). */
	private volatile String runPlayer;

	/** The in-game farm run window, and the stop shown in its details (-1: the current one). */
	@Getter
	private volatile boolean open;
	private volatile int selectedStop = -1;

	private final OSRSCompanionConfig config;
	private final Notifier notifier;
	private final GoalCompletionNotifier popups;
	private final TeleportLeftClick teleports;
	private final ConfigManager configManager;
	private final Gson gson;

	/** The last run's patches, until the route is ready again (null: none). */
	private volatile FarmRouteReady lastRun;
	private volatile boolean loadLastRun;
	/** The route got ready while logged out: the in-game popup waits for the next login. */
	private volatile String readyPopupPending;

	// ---- Loading -----------------------------------------------------------------

	/** A load without an answer after this long is tried again (opening the window, Refresh). */
	private static final long STUCK_LOAD_MS = 20_000;

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
				// A different route restarts the progress; a run going on the old route ends here (stopped part way).
				if (old == null || route == null || old.getId() != route.getId()) {
					if (running && old != null) {
						running = false;
						send("stop", old);
					}
					running = false;
					stopIndex = 0;
					state = null;
				} else if (stopIndex >= route.getStops().size()) {
					stopIndex = Math.max(0, route.getStops().size() - 1);
				}
				syncPending = true;
			}

			@Override
			public void onError(String message) {
				error = (switchedTo == null ? "Could not load your farm route" : "Could not switch to " + switchedTo) + " (" + message + ").";
				loading = false;
			}
		};
	}

	// ---- The website ------------------------------------------------------------------

	/** Asks the website to do this to the run (with what Time Tracking stored now). Client thread. */
	private void send(String action, FarmRoute r) {
		send(action, r, null);
	}

	private void send(String action, FarmRoute r, Map<String, Object> extra) {
		String player = runPlayer != null ? runPlayer : roadmapManager.getPlayerUsername();
		if (r == null || player == null) {
			return;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", player);
		body.put("route_id", r.getId());
		body.put("action", action);
		body.put("profile", TimerMonitor.timeTracking(configManager));
		if (extra != null) {
			body.putAll(extra);
		}
		synchronized (queue) {
			queue.add(body);
		}
		pump();
	}

	private void pump() {
		Map<String, Object> next;
		synchronized (queue) {
			if (inFlight || queue.isEmpty()) {
				return;
			}
			inFlight = true;
			next = queue.poll();
		}
		apiClient.postFarmRun(gson.toJson(next), State.class, new OSRSCompanionApiClient.RoadmapCallback<State>() {
			@Override
			public void onSuccess(State answer) {
				apply(answer);
				done();
			}

			@Override
			public void onError(String message) {
				done();
			}
		});
	}

	private void done() {
		synchronized (queue) {
			inFlight = false;
		}
		pump();
	}

	/** The website's answer (OkHttp thread): the newest one for the current route wins. */
	private void apply(State answer) {
		FarmRoute r = route;
		boolean newest;
		synchronized (queue) {
			newest = queue.isEmpty();
		}
		if (r == null || answer.route_id != r.getId() || !newest) {
			return;
		}
		if (answer.patches == null) {
			answer.patches = Collections.emptyMap();
		}
		state = answer;
		if (running && answer.running) {
			stopIndex = answer.stop_index;
		}
		FarmRouteReady last = answer.last_run;
		FarmRouteReady known = lastRun;
		if (last != null && (known == null || known.getReadyAt() != last.getReadyAt() || known.getEndedAt() != last.getEndedAt())) {
			// The website's newest time for the last run; already told stays told for the same run.
			if (known != null && known.isNotified() && known.getEndedAt() == last.getEndedAt()) {
				last.markNotified();
			}
			lastRun = last;
			saveLastRun(last);
		}
	}

	private PatchState stateOf(FarmRoute.Patch p) {
		State s = state;
		PatchState ps = s == null ? null : s.patches.get(p.getKey());
		return ps == null ? new PatchState() : ps;
	}

	// ---- The run -------------------------------------------------------------------

	/** Starts the guide: the website notes every patch's state now and goes to the first stop with work. */
	public void start() {
		FarmRoute r = route;
		if (r == null || r.getStops().isEmpty()) {
			return;
		}
		compostTarget = null;
		awayFrom = -1;
		completedTick = -1;
		startXp = client.getSkillExperience(Skill.FARMING);
		lastXp = startXp;
		runTicks = 0;
		specialMode = GameModeUtil.isSpecialGameMode(client);
		runPlayer = roadmapManager.getPlayerUsername();
		state = null;
		running = true;
		// Only one guide is active at a time: a farm run turns the boss setup and the slayer guide off.
		turnOff(BOSS_ACTIVE_KEY);
		turnOff(SLAYER_GUIDE_KEY);
		stopIndex = 0;
		capeRemindedStop = -1;
		recountPending = true;
		send("start", r);
	}

	public void stop() {
		FarmRoute r = route;
		boolean was;
		synchronized (this) {
			was = running;
			running = false;
		}
		if (was) {
			Map<String, Object> extra = new LinkedHashMap<>();
			extra.put("xp_gained", lastXp - startXp);
			extra.put("active_ticks", runTicks);
			// For the website's Run history (when that setting is on).
			extra.put("record", config.syncFarmRuns() && !specialMode && SyncGuard.hasAppKey(config));
			send("stop", r, extra);
		}
		completedTick = -1;
		runPlayer = null;
	}

	public void next() {
		FarmRoute r = route;
		if (r != null && stopIndex < r.getStops().size() - 1) {
			stopIndex++;
			send("next", r);
		}
	}

	public void previous() {
		if (stopIndex > 0) {
			stopIndex--;
			send("previous", route);
		}
	}

	public void goTo(int index) {
		FarmRoute r = route;
		if (r != null && index >= 0 && index < r.getStops().size()) {
			stopIndex = index;
			send("goto", r, Collections.singletonMap("index", index));
		}
	}

	/** Skips the current stop's open patches and moves on. */
	public void skipStop() {
		if (getCurrentStop() != null) {
			send("skip", route);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		Guard.run(GUARD_FEATURE, () -> {
			if (loadLastRun) {
				loadLastRun = false;
				loadLastRun();
			}
			String popup = readyPopupPending;
			if (popup != null) {
				readyPopupPending = null;
				popups.showMessage(READY_TITLE, "<col=ffffff>" + popup + "</col>");
			}
			if (recountPending) {
				recountPending = false;
				recount();
			}
			if (open || running) {
				leprechaun = ToolLeprechaun.read(client);
				if (syncPending || client.getTickCount() - lastSyncTick >= SYNC_EVERY_TICKS) {
					syncPending = false;
					lastSyncTick = client.getTickCount();
					if (client.getGameState() == GameState.LOGGED_IN) {
						send("sync", route);
					}
				}
			}
			if (!running || route == null) {
				return;
			}
			runTicks++;
			int xp = client.getSkillExperience(Skill.FARMING);
			if (xp >= startXp) {
				lastXp = xp;
			}
			remindCape();
			checkAway();
			updateMinigameCooldown();
			checkFinished();
		});
	}

	/** RuneLite's Time Tracking stored a patch change (you farmed): the website works the run out again. */
	@Subscribe
	public void onConfigChanged(ConfigChanged e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (TimeTrackingConfig.CONFIG_GROUP.equals(e.getGroup())) {
				syncPending = true;
				return;
			}
			if (!OSRSCompanionConfig.CONFIG_GROUP.equals(e.getGroup()) || !"true".equals(e.getNewValue())) {
				return;
			}
			// The farm run, the boss setup and the slayer guide are never active together.
			if (BOSS_ACTIVE_KEY.equals(e.getKey())) {
				turnOff(SLAYER_GUIDE_KEY);
			} else if (SLAYER_GUIDE_KEY.equals(e.getKey())) {
				turnOff(BOSS_ACTIVE_KEY);
			} else {
				return;
			}
			if (running) {
				stop();
			}
		});
	}

	// ---- Finishing -----------------------------------------------------------------------

	/** A finished run: the summary popup straight away, then it stops once you leave the last stop, or after a minute. */
	private void checkFinished() {
		State s = state;
		if (!isComplete()) {
			completedTick = -1;
			return;
		}
		if (completedTick < 0) {
			completedTick = client.getTickCount();
			if (s.planted == 0) {
				// Nothing was planted: every patch is still growing or skipped.
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Farm run: nothing to do yet - every patch is still growing or skipped.", null);
				stop();
				return;
			}
			showFinished(s.planted);
			return;
		}
		WorldPoint at = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getWorldLocation();
		WorldPoint last = getCurrentTarget();
		boolean left = at != null && last != null && (at.getPlane() != last.getPlane() || at.distanceTo2D(last) > CAPE_REMINDER_DISTANCE);
		// The minute's grace waits while a patch here still has no compost (you are still at it).
		boolean timeUp = client.getTickCount() - completedTick >= FINISH_DELAY_TICKS && !hasCompostToDo();
		if (left || timeUp) {
			stop();
		}
	}

	/** The summary popup (when that setting is on) and a notification: patches done and Farming XP gained. */
	private void showFinished(int planted) {
		int xp = Math.max(0, client.getSkillExperience(Skill.FARMING) - startXp);
		if (config.farmRunPopup()) {
			popups.showMessage(FINISHED_TITLE, finishedBody(planted, xp));
		}
		notifier.notify("Farm run complete: " + planted + (planted == 1 ? " patch" : " patches")
			+ (xp > 0 ? ", " + QuantityFormatter.formatNumber(xp) + " Farming XP" : "") + ".");
	}

	/** The popup body: "8 patches, 1,234 Farming XP" (the XP left out when none was gained). */
	static String finishedBody(int patches, int xp) {
		return "<col=ffffff>" + patches + (patches == 1 ? " patch" : " patches")
			+ (xp > 0 ? ", " + QuantityFormatter.formatNumber(xp) + " Farming XP" : "") + "</col>";
	}

	// ---- One guide at a time -------------------------------------------------------------

	/** The boss setup's Active switch and the slayer guide's switch (this plugin's settings). */
	static final String BOSS_ACTIVE_KEY = "bossGearActive";
	static final String SLAYER_GUIDE_KEY = "slayerGuide";

	private void turnOff(String key) {
		if (Boolean.parseBoolean(configManager.getConfiguration(OSRSCompanionConfig.CONFIG_GROUP, key))) {
			configManager.setConfiguration(OSRSCompanionConfig.CONFIG_GROUP, key, false);
		}
	}

	// ---- Route ready again ----------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (e.getGameState() == GameState.LOGGED_IN) {
				loadLastRun = true;
			}
		});
	}

	/** The account's last run, as saved (client thread, logged in). */
	private void loadLastRun() {
		try {
			String json = configManager.getRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, READY_KEY);
			lastRun = json == null ? null : gson.fromJson(json, FarmRouteReady.class);
		} catch (RuntimeException ex) {
			lastRun = null;
		}
	}

	private void saveLastRun(FarmRouteReady run) {
		try {
			configManager.setRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, READY_KEY, gson.toJson(run));
		} catch (RuntimeException ex) {
		}
	}

	/**
	 * Every half minute (the plugin's executor), logged in or not: once the last run's patches are grown (as the
	 * website last worked it out), a notification and an in-game popup (at once or at the next login). Once a run.
	 */
	public void checkRouteReady() {
		FarmRouteReady run = lastRun;
		if (run == null || run.isNotified() || running || !config.farmRouteReadyNotify() || !run.isReady(System.currentTimeMillis() / 1000)) {
			return;
		}
		run.markNotified();
		saveLastRun(run);
		String message = FarmRouteReady.message(run.getRoute(), run.getPatches());
		notifier.notify(message);
		if (client.getGameState() == GameState.LOGGED_IN) {
			popups.showMessage(READY_TITLE, "<col=ffffff>" + message + "</col>");
		} else {
			readyPopupPending = message;
		}
	}

	// ---- Compost and the minigame teleport ---------------------------------------------

	/** Walked away from the stop just left: the website stops pointing compost out there. Client thread. */
	private void checkAway() {
		State s = state;
		FarmRoute r = route;
		int prev = s == null ? -1 : s.previous_stop;
		if (r == null || prev < 0 || prev >= r.getStops().size() || prev == awayFrom || client.getLocalPlayer() == null) {
			return;
		}
		FarmRoute.Stop stop = r.getStops().get(prev);
		if (client.getLocalPlayer().getWorldLocation().distanceTo2D(new WorldPoint(stop.getX(), stop.getY(), stop.getPlane())) > CAPE_REMINDER_DISTANCE) {
			awayFrom = prev;
			send("away", r);
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

	/** The way to a stop: during a run, a minigame teleport still on its cooldown gives way to the route's fallback. */
	public FarmRoute.Travel travelOf(FarmRoute.Stop stop) {
		FarmRoute.Travel t = stop.getTravel();
		return running && t.isMinigame() && t.getFallback() != null && minigameCooldownLeft() > 0 ? t.getFallback() : t;
	}

	/** Compost (or Fertile Soil) used on a route patch: remember which, until the game's message says it worked. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e) {
		Guard.run(GUARD_FEATURE, () -> {
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
			State s = state;
			int prev = s == null ? -1 : s.previous_stop;
			FarmRoute.Stop[] near = {getCurrentStop(), r != null && prev >= 0 && prev < r.getStops().size() ? r.getStops().get(prev) : null};
			for (FarmRoute.Stop stop : near) {
				for (FarmRoute.Patch p : stop == null ? Collections.<FarmRoute.Patch>emptyList() : stop.getPatches()) {
					if (p.getVarbit() == varbit && compostTarget == null) {
						compostTarget = p.getKey();
					}
				}
			}
		});
	}

	@Subscribe
	public void onChatMessage(ChatMessage e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (compostTarget != null && (e.getType() == ChatMessageType.GAMEMESSAGE || e.getType() == ChatMessageType.SPAM)
				&& COMPOST_MESSAGE.matcher(e.getMessage()).find()) {
				send("composted", route, Collections.singletonMap("key", compostTarget));
				compostTarget = null;
			}
		});
	}

	private static boolean isCompostItem(int itemId) {
		for (int id : COMPOST_ITEMS) {
			if (id == itemId) {
				return true;
			}
		}
		return false;
	}

	/** True while this route patch still needs compost (at the current stop, or the one just left while you are there). */
	public boolean needsCompost(FarmRoute.Patch p) {
		return running && stateOf(p).compost;
	}

	/** True while a patch at (or just behind) the current stop still needs compost. */
	public boolean hasCompostToDo() {
		State s = state;
		return running && s != null && s.patches.values().stream().anyMatch(p -> p.compost);
	}

	/** The stop just left while one of its patches (you are still there) has no compost yet, else null. */
	public FarmRoute.Stop compostStopBehind() {
		FarmRoute r = route;
		State s = state;
		int prev = s == null ? -1 : s.previous_stop;
		if (!running || r == null || prev < 0 || prev >= r.getStops().size() || prev == stopIndex) {
			return null;
		}
		FarmRoute.Stop stop = r.getStops().get(prev);
		return stop.getPatches().stream().anyMatch(this::needsCompost) ? stop : null;
	}

	/** Patches of the stop just left (you are still there) that have no compost yet. */
	public int compostBehind() {
		FarmRoute.Stop behind = compostStopBehind();
		return behind == null ? 0 : (int) behind.getPatches().stream().filter(this::needsCompost).count();
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

	/** True while the current stop still has herbs to pick and the cape is not worn (the setting on, 99 Farming). */
	public boolean needsFarmingCape() {
		if (config.farmingCapeMode() == FarmingCapeMode.OFF || !running || isComplete() || capeWorn || client.getRealSkillLevel(Skill.FARMING) < 99) {
			return false;
		}
		FarmRoute.Stop stop = getCurrentStop();
		return stop != null && stop.getPatches().stream().anyMatch(this::herbToPick);
	}

	/** A herb patch of the run still to pick (grown, not dead or diseased). */
	private boolean herbToPick(FarmRoute.Patch p) {
		PatchState s = stateOf(p);
		return isHerb(p) && s.status == Status.TODO && s.harvestable;
	}

	/** What to do at a patch still to do, as the website works it out (harvest, rake, compost, plant...). */
	public String nextStep(FarmRoute.Patch p) {
		String step = stateOf(p).step;
		return step != null ? step : p.getType() + (p.getCrop() != null ? ": " + p.getCrop() : "");
	}

	/** With "Farming cape for herbs" on Force: a herb patch of the run still to pick while the cape is not worn (99 Farming). */
	public boolean capeBlocks(FarmRoute.Patch p) {
		return config.farmingCapeMode() == FarmingCapeMode.FORCE && running && !capeWorn && herbToPick(p)
			&& client.getRealSkillLevel(Skill.FARMING) >= 99;
	}

	/** With "Farming cape for herbs" on Force: the cape is not worn while herbs of the run are still to pick. */
	public boolean capeMissing() {
		FarmRoute r = route;
		if (config.farmingCapeMode() != FarmingCapeMode.FORCE || !running || capeWorn || r == null || client.getRealSkillLevel(Skill.FARMING) < 99) {
			return false;
		}
		return r.getStops().stream().anyMatch(s -> s.getPatches().stream().anyMatch(this::herbToPick));
	}

	/** "Auto set cape teleport": the teleport to the current stop becomes its item's left click. Runs after every other plugin. */
	@Subscribe(priority = -100)
	public void onPostMenuSort(PostMenuSort e) {
		Guard.run(GUARD_FEATURE, () -> {
			FarmRoute.Stop stop = getCurrentStop();
			if (!config.autoCapeTeleport() || !running || stop == null || client.isMenuOpen()) {
				return;
			}
			teleports.apply(travelOf(stop), "farm step " + (stopIndex + 1));
		});
	}

	/** "Pick" on a herb patch the cape blocks moves below "Walk here", so a left click walks instead. */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (!running || capeWorn || config.farmingCapeMode() != FarmingCapeMode.FORCE || !"Pick".equals(e.getOption())) {
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
		});
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
		Guard.run(GUARD_FEATURE, () -> {
			int id = event.getContainerId();
			if (id == InventoryID.INV || id == InventoryID.WORN) {
				recount();
			}
		});
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (RunePouchMonitor.isPouchVarbit(event.getVarbitId())) {
				recountPending = true;
			}
		});
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

	/** When the route is ready for its next run (epoch seconds): 0 nothing growing, -1 not known (from the website). */
	public long nextRunAt() {
		State s = state;
		return s == null ? -1 : s.next_run_at;
	}

	// ---- The window --------------------------------------------------------------------

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
		// Always fetch on open, so a route just made or edited on the website shows up straight away (the same
		// route keeps its progress; see callback()). A load that never answered doesn't block it.
		if (!loading || System.currentTimeMillis() - fetchedAtMs > STUCK_LOAD_MS) {
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

	public FarmRoute.Stop getCurrentStop() {
		FarmRoute r = route;
		if (r == null || r.getStops().isEmpty()) {
			return null;
		}
		return r.getStops().get(Math.min(stopIndex, r.getStops().size() - 1));
	}

	/** The run is finished: every patch from the current stop to the end of the route is handled (the website's answer). */
	public boolean isComplete() {
		State s = state;
		return running && s != null && s.running && s.complete;
	}

	public Status statusOf(FarmRoute.Patch p) {
		return running ? stateOf(p).status : Status.TODO;
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

	/** Item counts in the inventory plus worn equipment; variants count too (worn graceful, charges, trimmed cape). */
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

	/** The loadout entry this item satisfies: the same item, a variant (charges, colours) or one of the same name. */
	public FarmRoute.Stack neededStack(int itemId) {
		return neededStack(itemId, loadout());
	}

	/** {@link #neededStack(int)} against a loadout the caller already has (the item overlay keeps one per frame). */
	FarmRoute.Stack neededStack(int itemId, List<FarmRoute.Stack> loadout) {
		int base = ItemVariationMapping.map(itemId);
		String name = null;
		for (FarmRoute.Stack s : loadout) {
			if (s.getItem() == itemId || ItemVariationMapping.map(s.getItem()) == base) {
				return s;
			}
		}
		for (FarmRoute.Stack s : loadout) {
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
		if (hasCompostToDo()) {
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
	 * The route's inventory (its compost counted by the website from the patches you will actually plant), plus the
	 * Farming cape when that setting is on and the route picks herbs.
	 */
	public List<FarmRoute.Stack> inventory() {
		FarmRoute r = route;
		if (r == null) {
			return Collections.emptyList();
		}
		State s = state;
		List<FarmRoute.Stack> inv = s != null && s.compost_to_bring >= 0 && client.getGameState() == GameState.LOGGED_IN
			? withCompost(r.getInventory(), s.compost_to_bring) : r.getInventory();
		boolean herbs = r.getStops().stream().anyMatch(st -> st.getPatches().stream().anyMatch(FarmRunManager::isHerb));
		boolean listed = inv.stream().anyMatch(st -> st.getItem() == FARMING_CAPE || st.getItem() == FARMING_CAPE_T)
			|| r.getEquipment().stream().anyMatch(st -> st.getItem() == FARMING_CAPE || st.getItem() == FARMING_CAPE_T);
		if (config.farmingCapeMode() == FarmingCapeMode.OFF || !herbs || listed || client.getRealSkillLevel(Skill.FARMING) < 99) {
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
		state = null;
		synchronized (queue) {
			queue.clear();
		}
		awayFrom = -1;
		completedTick = -1;
		carried = Collections.emptyMap();
	}
}
