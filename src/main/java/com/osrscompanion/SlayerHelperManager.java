package com.osrscompanion;

import lombok.RequiredArgsConstructor;
import lombok.Getter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.SlayerGear;
import com.osrscompanion.model.SlayerGuide;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

/**
 * The Slayer helper: your current task (read from the slayer varps), where to do it (GET
 * /api/runelite/slayer/guide/), the guide there while the "Slayer guide" switch is on, and the
 * session at the location: time spent inside the area, trips, task kills, each monster's kill time
 * and the loot's value, sent to the website (POST /api/runelite/slayer/sessions/) so it can chart
 * them per task.
 *
 * <p>A session starts the first time you are in one of the task's locations (within its radius of
 * its tile on the same plane, or in one of its map regions) and lasts until the task is done or
 * changes, or you start killing at another location. Once going, it stays in the area anywhere in the
 * map regions the location spans, so crossing between them never restarts or pauses it; only outside
 * all of them does the time there pause. It is kept in the RuneScape profile's config,
 * so a client restart picks it up again.
 *
 * <p>Game reads and every event run on the client thread; the panel and overlays only read the
 * volatile fields and {@link Stats}. The guide arrives on an OkHttp thread.
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
@Singleton
public class SlayerHelperManager {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "slayer-helper";

	/**
	 * Re-read the guide at most this often (the panel opening, the guide running): a backstop, as a
	 * change on the website is picked up by the web version check ({@link WebVersionWatcher}).
	 */
	static final long REFRESH_INTERVAL_MS = 10 * 60_000L;
	/**
	 * While in the area, the session is sent at most this often when it changed (and when it ends, and
	 * on logout); the outbox batches it with the other uploads.
	 */
	static final long SYNC_INTERVAL_MS = 10_000L;
	/** The open session is written to the config at most this often while only its timers change. */
	private static final long PERSIST_INTERVAL_MS = 10_000L;
	/** Ticks after logging in before the slayer varps are trusted (they arrive a moment after login). */
	private static final int LOGIN_GRACE_TICKS = 8;
	/** A task that ended from this many kills left or fewer counts as completed (a cancel also zeroes the count). */
	private static final int COMPLETE_FROM_LEFT = 10;
	/** A finished task's session stays open this many ticks, for the last monster's death and loot. */
	private static final int CLOSE_DELAY_TICKS = 4;
	/** Kills a few ticks after stepping out of the area still count (the last monster dies as you leave). */
	private static final int KILL_GRACE_TICKS = 10;
	/** After a task change the website needs a moment to hear about the new task before the guide is read. */
	/**
	 * After a new task the guide is read once the website has the task: the task change goes up at once,
	 * so a short wait, then a quick retry while the website still answers for the old task (~25 s at most).
	 */
	private static final long TASK_CHANGE_FETCH_DELAY_MS = 2_000L;
	private static final long GUIDE_RETRY_MS = 3_000L;
	private static final int GUIDE_RETRIES = 8;
	/**
	 * Tiles round where you first fought a boss variant of the task (Cerberus on a hellhound task) that
	 * count as its area when no location of the guide is there: boss lairs aren't in the guide.
	 */
	static final int BOSS_AREA_RADIUS = 30;
	static final String SESSION_KEY = "slayerHelperSession";
	static final String UNSENT_KEY = "slayerHelperUnsent";
	static final String TITLE_COLOUR = "f28c28";
	/** The "Focus on" option RuneLite adds for a clickable world map point. */

	/** The slayer task as the varps have it (and its name from the game's tables). */
	static final class Task {
		final int taskId;
		final int bossId;
		final int areaId;
		final int masterId;
		final String name;
		final int remaining;
		final int original;

		Task(int taskId, int bossId, int areaId, int masterId, String name, int remaining, int original) {
			this.taskId = taskId;
			this.bossId = bossId;
			this.areaId = areaId;
			this.masterId = masterId;
			this.name = name;
			this.remaining = remaining;
			this.original = original;
		}

		/** The assignment: same monster, boss, area and master (a new assignment of the same monster has another master). */
		String key() {
			return taskId + ":" + bossId + ":" + areaId + ":" + masterId;
		}

		String displayName() {
			return name == null ? "Slayer task" : name;
		}
	}

	/** The session's numbers at the last tick, for the panel and the overlay (they run on other threads). */
	static final class Stats {
		final boolean inside;
		final long snapshotMs;
		final long startedMs;
		final long endedMs;
		final long timeInAreaMs;
		final int trips;
		final int kills;
		final long avgKillMs;
		final double killsPerHour;
		final long lootGp;
		final long avgLootGp;
		final long estimatedLeftMs;
		final String locationName;
		/** The trip timer's parts: time in the area plus the counted times away, and when you left (0: inside). */
		final long tripBaseMs;
		final long leftMs;

		Stats(SlayerSession s, long nowMs) {
			inside = s.inside;
			tripBaseMs = s.timeInAreaMs + s.awayCountedMs;
			leftMs = s.leftMs;
			snapshotMs = nowMs;
			startedMs = s.startedMs;
			endedMs = s.endedMs;
			timeInAreaMs = s.timeInAreaMs;
			trips = s.tripCount;
			kills = s.kills;
			avgKillMs = s.avgKillMs();
			killsPerHour = s.killsPerHour();
			lootGp = s.lootGp;
			avgLootGp = s.avgLootGp();
			estimatedLeftMs = s.estimatedLeftMs();
			locationName = s.locationName;
		}

		/** Time at the location, running on between ticks while inside. */
		long timeInArea(long nowMs) {
			return timeInAreaMs + (inside && endedMs == 0 ? Math.max(0, Math.min(nowMs - snapshotMs, SlayerSession.MAX_TICK_GAP_MS)) : 0);
		}

		/**
		 * The trip timer (see {@link SlayerSession#tripMs}): the time in the area, running on between ticks
		 * while inside, plus the times away back within 15 minutes; while away it runs on for up to 15
		 * minutes, then those come off again and it pauses until you are back.
		 */
		long total(long nowMs) {
			long ms = tripBaseMs + (timeInArea(nowMs) - timeInAreaMs);
			if (endedMs == 0 && !inside && leftMs > 0) {
				long away = nowMs - leftMs;
				if (away > 0 && away <= SlayerSession.TRIP_GRACE_MS) {
					ms += away;
				}
			}
			return Math.max(0, ms);
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final ConfigManager configManager;
	private final OSRSCompanionApiClient apiClient;
	private final SlayerMonitor slayerMonitor;
	private final ItemManager itemManager;
	private final Gson gson;
	private final GoalCompletionNotifier popups;
	private final TeleportLeftClick teleports;
	private final FarmRunManager farmRun;

	private final SlayerKillTimer timer = new SlayerKillTimer();
	private final Map<String, String> taskNames = new ConcurrentHashMap<>();

	// Read by the panel and overlays.
	@Getter
	private volatile Task task;
	@Getter
	private volatile SlayerGuide guide;
	@Getter
	private volatile String error;
	@Getter
	private volatile boolean loading;
	private volatile long fetchedAtMs;
	private volatile String player;
	/** The location the guide leads to (the selected or recommended one), or null. */
	@Getter
	private volatile SlayerGuide.Location location;
	/** The task location you are in, or null. */
	@Getter
	private volatile SlayerGuide.Location here;
	/** The open session's numbers at the last tick, or null when there is none. */
	@Getter
	private volatile Stats stats;
	/** Distance (tiles) and compass direction from you to the location; -1 / null when not comparable. */
	@Getter
	private volatile int distance = -1;
	@Getter
	private volatile String direction;

	// Client thread only.
	private SlayerSession session;
	/**
	 * The area round the task's boss you are fighting, or round an unknown spot where you fight the task's
	 * monster outside the guide's locations (see {@link #BOSS_AREA_RADIUS}); null: none this task.
	 */
	private SlayerGuide.Location adhocArea;
	/** Pairs the task count going down with the monster that died, for the session's kill log. */
	private final SlayerKillLog.Matcher killMatcher = new SlayerKillLog.Matcher();
	/** Finished sessions the website has not acknowledged yet (stored in the config too). */
	private List<SlayerSession> unsent = new ArrayList<>();
	private boolean loggedIn;
	private int ticksLoggedIn;
	private boolean taskRead;
	private int ticksSinceInside = Integer.MAX_VALUE;
	private long lastSyncMs;
	private long lastSentUpdate = -1;
	private long lastPersistMs;
	private long lastPersistedUpdate = -1;
	private int completionChatTick = -1;
	/** The task ended: the session closes at this tick (-1: not closing), completed or not. */
	private int closeAtTick = -1;
	private boolean closeCompleted;
	private long fetchAtMs;
	private int retriesLeft;

	// ---- The guide from the website ------------------------------------------------------

	/** Reads the guide again when the last read is a few minutes old (the panel opening). Any thread. */
	public void refreshIfStale() {
		if (!loading && System.currentTimeMillis() - fetchedAtMs > REFRESH_INTERVAL_MS) {
			refresh();
		}
	}

	/** Reads the guide for the current task. Any thread (the player name is read on the client thread). */
	public void refresh() {
		String name = player;
		if (name == null) {
			error = "Log in to the game to load your slayer task.";
			return;
		}
		if (!SyncGuard.hasAppKey(config)) {
			error = "Add your App Key in the OSRS Companion settings first.";
			return;
		}
		loading = true;
		fetchedAtMs = System.currentTimeMillis();
		apiClient.getSlayerGuide(name, new OSRSCompanionApiClient.RoadmapCallback<SlayerGuide>() {
			@Override
			public void onSuccess(SlayerGuide result) {
				loading = false;
				error = null;
				guide = result;
				loadGear(name);
				Guard.invokeLater(clientThread, GUARD_FEATURE, () -> onGuide(result));
			}

			@Override
			public void onError(String message) {
				loading = false;
				error = "Could not load the slayer guide (" + message + ").";
			}
		});
	}

	// ---- Slayer gear (the website's DPS calculator) ---------------------------------------------

	@Getter
	private volatile SlayerGear gear;
	/** The setup picked in the Slayer gear window (its key), stored per task in the RS profile; null: the best. */
	private volatile String gearKey;
	/** Whether the Slayer gear window is open. */
	@Getter
	private volatile boolean gearOpen;
	private volatile boolean gearLoading;
	/** RS profile config key prefix of the picked setup, per task ("slayerGearSet." + task). */
	static final String GEAR_SET_KEY = "slayerGearSet.";

	/** Reads the best setups for the task's monster, with the guide. */
	private void loadGear(String name) {
		gearLoading = true;
		apiClient.getSlayerGear(name, new OSRSCompanionApiClient.RoadmapCallback<SlayerGear>() {
			@Override
			public void onSuccess(SlayerGear result) {
				gearLoading = false;
				SlayerGear before = gear;
				// A new task (or monster) starts on the setup picked for it before, else its best.
				if (before == null || !java.util.Objects.equals(gearConfigKey(before), gearConfigKey(result))) {
					String key = gearConfigKey(result);
					gearKey = key == null ? null : configManager.getRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, key);
				}
				gear = result;
			}

			@Override
			public void onError(String message) {
				gearLoading = false;
			}
		});
	}

	/** The RS profile config key the picked setup of these setups' task is stored under, or null without a task. */
	static String gearConfigKey(SlayerGear g) {
		if (g == null) {
			return null;
		}
		String task = g.getTask() != null && !g.getTask().trim().isEmpty() ? g.getTask() : g.getMonster();
		if (task == null || task.trim().isEmpty()) {
			return null;
		}
		return GEAR_SET_KEY + task.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
	}

	/** The index of the setup with this key, else 0 (the best, as the website sends them best first). */
	static int gearIndex(List<SlayerGear.Set> sets, String key) {
		if (key != null) {
			for (int i = 0; i < sets.size(); i++) {
				if (key.equals(sets.get(i).getKey())) {
					return i;
				}
			}
		}
		return 0;
	}

	/** The picked setup (the best when none is picked or it is gone), or null when there is none. */
	public SlayerGear.Set getGearSet() {
		SlayerGear g = gear;
		if (g == null || g.getSets().isEmpty()) {
			return null;
		}
		return g.getSets().get(gearIndex(g.getSets(), gearKey));
	}

	/** The picked setup's place in the list (0: the best). */
	public int getGearIndex() {
		SlayerGear g = gear;
		return g == null ? 0 : gearIndex(g.getSets(), gearKey);
	}

	/** Picks a setup (from the Slayer gear window) and stores it for this task in the RS profile. */
	public void selectGearSet(SlayerGear.Set set) {
		SlayerGear g = gear;
		if (set == null || set.getKey() == null || g == null) {
			return;
		}
		gearKey = set.getKey();
		String key = gearConfigKey(g);
		if (key != null) {
			configManager.setRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, key, set.getKey());
		}
	}

	public boolean isGearLoading() {
		return gearLoading || loading;
	}

	/** Reads the guide and the setups again (the window's Refresh). */
	public void refreshGear() {
		if (!loading) {
			refresh();
		}
	}

	// ---- The Slayer gear window ---------------------------------------------------------------

	public void toggleGearWindow() {
		if (gearOpen) {
			closeGearWindow();
		} else {
			gearOpen = true;
			refreshIfStale();
		}
	}

	public void closeGearWindow() {
		gearOpen = false;
	}

	// ---- The bank layout ----------------------------------------------------------------------

	/** Item names (lower case) read on the client thread. */
	private final Map<Integer, String> itemNames = new ConcurrentHashMap<>();

	/**
	 * Whether the picked setup uses this item: worn (the item, another version of it (charges) or the same
	 * name), or brought along (its ammo / spell options, boost and charge materials). Bank Tags, client thread.
	 */
	public boolean needs(int itemId) {
		SlayerGear.Set set = getGearSet();
		if (set == null || itemId <= 0) {
			return false;
		}
		int base = net.runelite.client.game.ItemVariationMapping.map(itemId);
		for (SlayerGear.Piece p : set.getEquipment()) {
			if (p.getItem() == itemId || p.getItem() > 0 && net.runelite.client.game.ItemVariationMapping.map(p.getItem()) == base) {
				return true;
			}
		}
		for (int id : set.supplyItems(config.slayerGearSupplyOptions())) {
			if (id == itemId || net.runelite.client.game.ItemVariationMapping.map(id) == base) {
				return true;
			}
		}
		for (SlayerGear.Bring b : set.getBring()) {
			for (int id : b.getItems()) {
				if (id == itemId || net.runelite.client.game.ItemVariationMapping.map(id) == base) {
					return true;
				}
			}
		}
		String name = itemName(itemId);
		if (name == null) {
			return false;
		}
		for (SlayerGear.Piece p : set.getEquipment()) {
			if (p.getItem() > 0 && name.equals(itemName(p.getItem()))) {
				return true;
			}
		}
		return false;
	}

	/** The item's name in lower case; off the client thread only when it was read there before, else null. */
	private String itemName(int itemId) {
		String cached = itemNames.get(itemId);
		if (cached != null || !client.isClientThread()) {
			return cached;
		}
		net.runelite.api.ItemComposition comp = itemManager.getItemComposition(itemId);
		String name = comp == null || comp.getName() == null ? null : comp.getName().toLowerCase(java.util.Locale.ROOT);
		if (name != null) {
			itemNames.put(itemId, name);
		}
		return name;
	}

	/** How many of the item you carry (inventory, worn, rune pouch), for the slot colours. */
	public int carried(int itemId) {
		return farmRun.carried(itemId);
	}

	/** A guide arrived: pick the location; a guide for an older task is read again shortly. Client thread. */
	private void onGuide(SlayerGuide g) {
		Task t = task;
		if (t != null && !matches(g, t) && retriesLeft > 0) {
			// The website has not heard about the new task yet (it is synced a moment after it changes).
			retriesLeft--;
			fetchAtMs = System.currentTimeMillis() + GUIDE_RETRY_MS;
		}
		updateLocation();
	}

	/** Whether the guide is about this task (same task id); a guide without a task never is. */
	static boolean matches(SlayerGuide g, Task t) {
		return g != null && g.getTask() != null && t != null && g.getTask().getTaskId() != null && g.getTask().getTaskId() == t.taskId;
	}

	/** The guide's locations when the guide is about the current task, else none. */
	private List<SlayerGuide.Location> locations() {
		SlayerGuide g = guide;
		return matches(g, task) ? g.getLocations() : Collections.emptyList();
	}

	private void updateLocation() {
		SlayerGuide g = guide;
		location = matches(g, task) ? g.selectedLocation() : null;
	}

	// ---- Every tick ------------------------------------------------------------------------

	/** A bank opened: if the player is away from the task's area, the time away counts as a bank trip once back. */
	@Subscribe
	public void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.BANKMAIN && session != null && session.isOpen()) {
				session.bankOpened();
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e) {
		Guard.run(GUARD_FEATURE, () -> {
			GameState s = e.getGameState();
			if (s == GameState.LOGGED_IN && !loggedIn) {
				loggedIn = true;
				ticksLoggedIn = 0;
				lastSlayerXp = 0;
				taskRead = false;
			} else if (loggedIn && (s == GameState.LOGIN_SCREEN || s == GameState.HOPPING || s == GameState.CONNECTION_LOST)) {
				loggedOut();
			}
		});
	}

	/** Sends and stores the open session, then forgets everything about this login. Client thread. */
	private void loggedOut() {
		loggedIn = false;
		if (closeAtTick >= 0) {
			closeSession(closeCompleted, System.currentTimeMillis());
		}
		if (session != null) {
			// Out of the area from the logout on: the trip timer pauses after 15 minutes, as after leaving.
			session.loggedOut(System.currentTimeMillis());
			persist(true);
			sync(true);
		}
		session = null;
		adhocArea = null;
		stats = null;
		task = null;
		here = null;
		location = null;
		guide = null;
		distance = -1;
		direction = null;
		timer.clear();
		killMatcher.clear();
		taskRead = false;
	}

	@Subscribe
	public void onGameTick(GameTick e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (client.getGameState() != GameState.LOGGED_IN) {
				return;
			}
			if (!loggedIn) {
				// The plugin was turned on while logged in: no login event came.
				loggedIn = true;
				ticksLoggedIn = 0;
				taskRead = false;
			}
			String name = SyncGuard.getPlayerUsername(client);
			if (name != null) {
				player = name;
			}
			if (++ticksLoggedIn < LOGIN_GRACE_TICKS) {
				return;
			}
			long now = System.currentTimeMillis();
			Task before = task;
			Task now0 = readTask();
			if (!taskRead) {
				// First read of this login: pick the stored session back up and load the guide.
				taskRead = true;
				task = now0;
				if (now0 != null) {
					rememberStartXp(now0);
				}
				restore(now0, now);
				fetchAtMs = now;
				retriesLeft = GUIDE_RETRIES;
			} else {
				task = now0;
				taskChanges(before, now0, now);
			}
			logKills(killMatcher.expire(client.getTickCount(), false), now);
			if (closeAtTick >= 0 && client.getTickCount() >= closeAtTick) {
				closeSession(closeCompleted, now);
			}
			showFinishedPopup();
			updateLocation();
			if (fetchAtMs > 0 && now >= fetchAtMs && !loading) {
				fetchAtMs = 0;
				refresh();
			} else if (config.slayerGuide() && now0 != null) {
				refreshIfStale();
			}
			updateArea(now);
			if (session != null) {
				if (ticksLoggedIn % 100 == 0) {
					timer.prune(now);
				}
				persist(false);
				if (session.inside && now - lastSyncMs >= SYNC_INTERVAL_MS) {
					sync(false);
				}
				stats = new Stats(session, now);
			} else {
				stats = null;
			}
		});
	}

	/** The task from the varps; null without one. Client thread. */
	private Task readTask() {
		int id = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		int left = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		if (id == 0 || left <= 0) {
			return null;
		}
		int boss = client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID);
		String cacheKey = id + ":" + boss;
		String name = taskNames.get(cacheKey);
		if (name == null) {
			name = slayerMonitor.taskName(id, boss == 0 ? null : boss);
			if (name != null) {
				taskNames.put(cacheKey, name);
			}
		}
		int area = client.getVarpValue(VarPlayerID.SLAYER_AREA);
		int master = client.getVarbitValue(VarbitID.SLAYER_MASTER);
		// The size never below the count seen on this assignment (SlayerMonitor#originalAmount).
		int original = slayerMonitor.originalAmount(id, boss, area, master,
			client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL), left);
		return new Task(id, boss, area, master, name, left, original);
	}

	/** Kills counted down, a task finished or swapped for another. Client thread. */
	private void taskChanges(Task before, Task now, long nowMs) {
		if (before == null) {
			if (now != null) {
				rememberStartXp(now);
				// A new task: load its guide once the website has it.
				fetchAtMs = nowMs + TASK_CHANGE_FETCH_DELAY_MS;
				retriesLeft = GUIDE_RETRIES;
			}
			return;
		}
		boolean sameTask = now != null && now.key().equals(before.key());
		if (sameTask) {
			if (now.remaining < before.remaining) {
				countKills(before.remaining - now.remaining, nowMs);
			}
			if (session != null) {
				session.setRemaining(now.remaining, nowMs);
			}
			return;
		}
		// The task ended: counted down to 0 (or the completion message came), or it was swapped.
		boolean recentChat = completionChatTick >= 0 && Math.abs(client.getTickCount() - completionChatTick) <= 10;
		boolean completed = now == null && (before.remaining <= COMPLETE_FROM_LEFT || recentChat);
		if (completed) {
			countKills(before.remaining, nowMs);
			// The popup a couple of ticks later, once the last kill's XP is in.
			finished = before;
			popupAtTick = client.getTickCount() + 2;
		}
		if (session != null && closeAtTick < 0) {
			// Closed in a few ticks: the last monster's death and loot come just after the count.
			session.close(completed, nowMs);
			closeAtTick = client.getTickCount() + CLOSE_DELAY_TICKS;
			closeCompleted = completed;
		}
		here = null;
		location = null;
		guide = null;
		adhocArea = null;
		if (now != null) {
			rememberStartXp(now);
			fetchAtMs = nowMs + TASK_CHANGE_FETCH_DELAY_MS;
			retriesLeft = GUIDE_RETRIES;
		}
	}

	// ---- Task complete popup ---------------------------------------------------------------

	/** "Task key|amount|Slayer XP" when the task was given, in the RuneScape profile (survives a restart). */
	private static final String START_XP_KEY = "slayerTaskStartXp";
	/** The task that was just completed, and the tick its popup shows (-1: none). */
	private Task finished;
	private int popupAtTick = -1;

	/** Notes the Slayer XP at the start of a task (once per assignment). Client thread. */
	private void rememberStartXp(Task t) {
		String prefix = t.key() + "|" + t.original + "|";
		String stored = configManager.getRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, START_XP_KEY);
		if (stored == null || !stored.startsWith(prefix)) {
			configManager.setRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, START_XP_KEY,
				prefix + client.getSkillExperience(Skill.SLAYER));
		}
	}

	/** The Slayer XP gained on the task, or -1 when its start wasn't seen. Client thread. */
	private long xpGained(Task t) {
		String stored = configManager.getRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, START_XP_KEY);
		String prefix = t.key() + "|" + t.original + "|";
		if (stored == null || !stored.startsWith(prefix)) {
			return -1;
		}
		try {
			return Math.max(0, client.getSkillExperience(Skill.SLAYER) - Long.parseLong(stored.substring(prefix.length())));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Shows the "Slayer task complete" popup when its tick comes. Client thread. */
	private void showFinishedPopup() {
		if (popupAtTick < 0 || client.getTickCount() < popupAtTick) {
			return;
		}
		Task t = finished;
		popupAtTick = -1;
		finished = null;
		if (t == null || !config.slayerTaskPopup()) {
			return;
		}
		long xp = xpGained(t);
		popups.showMessage("<col=" + TITLE_COLOUR + ">Slayer task complete!</col>", finishedBody(t.displayName(), t.original, xp));
	}

	/** "Abyssal demons: 190 kills" and "32,456 Slayer XP" (left out when the task's start wasn't seen). */
	static String finishedBody(String task, int kills, long xp) {
		String body = "<col=ffffff>" + Text.removeTags(task) + ": " + kills + (kills == 1 ? " kill" : " kills") + "</col>";
		return xp >= 0 ? body + "<br><col=ffffff>" + QuantityFormatter.formatNumber(xp) + " Slayer XP</col>" : body;
	}

	private void countKills(int n, long nowMs) {
		SlayerKillLog.Npc recent = killMatcher.recent();
		boolean away = session == null || !session.isOpen() || ticksSinceInside > KILL_GRACE_TICKS;
		if (away && closeAtTick < 0 && recent != null) {
			// A task kill outside any known location: its spot (or the boss's lair) becomes the session's area.
			noteArea(recent.name, nowMs);
		}
		boolean counts = session != null && session.isOpen() && ticksSinceInside <= KILL_GRACE_TICKS;
		List<SlayerKillLog.Npc> killed = killMatcher.counted(n, client.getTickCount(), counts);
		if (counts) {
			session.addKills(n, nowMs);
			logKills(killed, nowMs);
			// A kill goes to the website at once (with the task count), not with the next timed sync.
			sync(true, true);
		}
	}

	/** Task kills of these monsters go in the session's kill log. */
	private void logKills(List<SlayerKillLog.Npc> killed, long nowMs) {
		SlayerSession s = session;
		if (s == null || killed.isEmpty()) {
			return;
		}
		for (SlayerKillLog.Npc npc : killed) {
			s.logKills(npc, 1, nowMs);
		}
	}

	/**
	 * Where you are: in one of the task's locations or not, starting, continuing or switching the
	 * session; and the distance and direction to the guide's location. Client thread.
	 */
	private void updateArea(long nowMs) {
		Task t = task;
		WorldPoint at = playerPoint();
		SlayerGuide.Location in = null;
		// Until the guide for this task is loaded the locations are unknown: the session waits (a restored
		// session would otherwise see you leave and come back, a trip that never happened).
		boolean known = matches(guide, t);
		if (known && at != null) {
			SlayerSession s = session;
			in = locationHere(locations(), s == null ? null : s.locationKey,
				at.getX(), at.getY(), at.getPlane(), at.getRegionID());
		}
		// Not in a location of the guide: the area round the task's boss or the unknown spot, once you fought
		// there (known without the guide).
		SlayerGuide.Location adhoc = adhocArea;
		if (in == null && adhoc != null && at != null && t != null && adhoc.contains(at.getX(), at.getY(), at.getPlane(), at.getRegionID())) {
			in = adhoc;
		}
		here = in;
		ticksSinceInside = in != null ? 0 : ticksSinceInside == Integer.MAX_VALUE ? ticksSinceInside : ticksSinceInside + 1;

		if (closeAtTick >= 0) {
			// The task just ended: only its last death and loot are still waited for.
			here = null;
			return;
		}
		if (in != null && t != null) {
			if (session != null && !Objects.equals(session.locationKey, in.getKey())) {
				// Killing somewhere else now: that is another session.
				closeSession(false, nowMs);
			}
			if (session == null) {
				startSession(t, in, nowMs);
			}
		}
		// An area round a boss or a spot is known without the guide; the guide's locations only once it is loaded.
		boolean adhocSession = session != null && isAdhocKey(session.locationKey);
		if (session != null && (known || adhocSession)) {
			boolean wasInside = session.inside;
			if (session.tick(in != null, nowMs)) {
				persist(true);
			} else if (wasInside && !session.inside) {
				// Left the area: the website's trip timer runs on from here for 15 minutes at most.
				persist(true);
				sync(true);
			}
		}

		SlayerGuide.Location target = location;
		if (target == null || at == null || !target.hasTile()) {
			distance = -1;
			direction = null;
		} else {
			boolean underground = at.getY() >= 6400;
			boolean targetUnderground = target.getY() >= 6400;
			if (underground != targetUnderground) {
				distance = -1;
				direction = null;
			} else {
				int dx = target.getX() - at.getX();
				int dy = target.getY() - at.getY();
				distance = Math.max(Math.abs(dx), Math.abs(dy));
				direction = SlayerFormat.compass(dx, dy);
			}
		}
	}

	/**
	 * The task's location you are in: one whose area holds you (the session's own first, so standing where
	 * two overlap keeps it going); else the session's location while you are anywhere in one of the regions
	 * it spans ({@link SlayerGuide.Location#coveredRegions}), so walking between the regions of one location
	 * never pauses, splits or restarts its session. Null outside them all (the time there pauses).
	 */
	static SlayerGuide.Location locationHere(List<SlayerGuide.Location> locations, String sessionKey, int x, int y, int plane, int regionId) {
		SlayerGuide.Location in = null;
		SlayerGuide.Location own = null;
		for (SlayerGuide.Location l : locations) {
			boolean mine = sessionKey != null && Objects.equals(sessionKey, l.getKey());
			if (mine) {
				own = l;
			}
			if (l.contains(x, y, plane, regionId) && (in == null || mine)) {
				in = l;
			}
		}
		if (in == null && own != null && !isAdhocKey(sessionKey) && own.covers(x, y, plane, regionId)) {
			in = own;
		}
		return in;
	}

	/** Your tile, as the real world tile in an instance. Client thread. */
	private WorldPoint playerPoint() {
		Player local = client.getLocalPlayer();
		if (local == null) {
			return null;
		}
		return local.getLocalLocation() == null ? local.getWorldLocation() : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
	}

	// ---- Sessions --------------------------------------------------------------------------

	/** A new session for the task at this location (of the guide, or round a boss or a spot). Client thread. */
	private void startSession(Task t, SlayerGuide.Location in, long nowMs) {
		String master = guide != null && guide.getTask() != null ? guide.getTask().getMaster() : null;
		session = SlayerSession.start(t.key(), t.taskId, t.name, t.areaId == 0 ? null : t.areaId, master,
			in.getKey(), in.getName(), t.original, t.remaining, nowMs);
		session.monsters = new ArrayList<>(in.getMonsters());
		if (isAdhocKey(in.getKey())) {
			session.bossAreaTile = Arrays.asList(in.getX(), in.getY(), in.getPlane());
		}
		session.player = player;
		lastSentUpdate = -1;
	}

	/** Whether a location key is an area made in the game (round a boss, or an unknown spot), not the guide's. */
	static boolean isAdhocKey(String key) {
		return key != null && (key.startsWith("boss-") || key.startsWith("spot-"));
	}

	private void closeSession(boolean completed, long nowMs) {
		SlayerSession s = session;
		closeAtTick = -1;
		if (s == null) {
			return;
		}
		// Counted kills still waiting for their monster's death go to the last one killed.
		logKills(killMatcher.expire(client.getTickCount(), true), nowMs);
		// A session closed when its task ended keeps that end time; its last kill and loot still count.
		s.close(completed, nowMs);
		queue(s);
		session = null;
		stats = null;
		timer.clear();
		configManager.unsetRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, SESSION_KEY);
		saveUnsent();
		sendUnsent();
	}

	/** Sends the open session when it changed since the last send (every 20 s in the area, or now). Client thread. */
	private void sync(boolean now) {
		sync(now, false);
	}

	/** @param urgent send at once (a kill) instead of with the outbox's next timed flush */
	private void sync(boolean now, boolean urgent) {
		SlayerSession s = session;
		String name = s == null || s.player == null ? player : s.player;
		if (s == null || name == null || !canSync() || s.updatedMs == lastSentUpdate) {
			return;
		}
		if (!now && System.currentTimeMillis() - lastSyncMs < SYNC_INTERVAL_MS) {
			return;
		}
		lastSyncMs = System.currentTimeMillis();
		lastSentUpdate = s.updatedMs;
		apiClient.sendSlayerSession(s.payload(name), null, urgent);
	}

	/** A finished session waits in the unsent list until the website has it. */
	private void queue(SlayerSession s) {
		if (s.player == null) {
			s.player = player;
		}
		if (s.player != null && canSync()) {
			unsent.add(s);
		}
	}

	/** Sends the finished sessions not yet acknowledged by the website; each is dropped once it is. Client thread. */
	private void sendUnsent() {
		for (SlayerSession s : new ArrayList<>(unsent)) {
			apiClient.sendSlayerSession(s.payload(s.player), () -> Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
				unsent.remove(s);
				saveUnsent();
			}));
		}
	}

	private boolean canSync() {
		return config.syncSlayer() && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client);
	}

	/** Writes the open session to the profile's config: straight away, or at most every few seconds. Client thread. */
	private void persist(boolean now) {
		SlayerSession s = session;
		if (s == null || s.updatedMs == lastPersistedUpdate) {
			return;
		}
		long t = System.currentTimeMillis();
		if (!now && t - lastPersistMs < PERSIST_INTERVAL_MS) {
			return;
		}
		lastPersistMs = t;
		lastPersistedUpdate = s.updatedMs;
		configManager.setRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, SESSION_KEY, gson.toJson(s));
	}

	private void saveUnsent() {
		if (unsent.isEmpty()) {
			configManager.unsetRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, UNSENT_KEY);
		} else {
			configManager.setRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, UNSENT_KEY, gson.toJson(unsent));
		}
	}

	/**
	 * After logging in: the stored session goes on when it is for the task you still have; one for
	 * another task is closed (it ended while the plugin wasn't running) and sent. Client thread.
	 */
	private void restore(Task now, long nowMs) {
		unsent = new ArrayList<>();
		try {
			String stored = configManager.getRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, UNSENT_KEY);
			List<SlayerSession> list = stored == null ? null : gson.fromJson(stored, new TypeToken<List<SlayerSession>>() { }.getType());
			if (list != null) {
				for (SlayerSession s : list) {
					if (s != null && s.uuid != null && s.player != null) {
						unsent.add(s);
					}
				}
			}
		} catch (RuntimeException ex) {
			configManager.unsetRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, UNSENT_KEY);
		}
		SlayerSession stored = null;
		try {
			String json = configManager.getRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, SESSION_KEY);
			stored = json == null ? null : gson.fromJson(json, SlayerSession.class);
		} catch (RuntimeException ex) {
		}
		if (stored != null && stored.uuid != null && stored.isOpen()) {
			if (now != null && now.key().equals(stored.taskKey)) {
				stored.resumed();
				if (stored.killTimesMs == null) {
					stored.killTimesMs = new ArrayList<>();
				}
				if (stored.killLog == null) {
					stored.killLog = new ArrayList<>();
				}
				session = stored;
				if (session.player == null) {
					session.player = player;
				}
				adhocArea = bossAreaOf(stored);
				lastSentUpdate = -1;
				lastPersistedUpdate = stored.updatedMs;
			} else {
				// The task ended while the plugin was off: close it when it was last seen.
				stored.close(now == null && stored.amountRemaining <= COMPLETE_FROM_LEFT, Math.max(stored.updatedMs, stored.startedMs));
				queue(stored);
				configManager.unsetRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, SESSION_KEY);
				saveUnsent();
			}
		}
		sendUnsent();
	}

	// ---- Kill timing and loot ---------------------------------------------------------------

	/**
	 * Whether this NPC is the session's task monster: one of its location's monsters, else by the task's
	 * name; a boss variant of the task counts too.
	 */
	private boolean isTaskNpc(NPC npc) {
		SlayerSession s = session;
		return npc != null && s != null && (SlayerMonsters.matches(npc.getName(), s.monsters, s.taskName) || isTaskBoss(npc, s)
			|| s.locationKey != null && s.locationKey.startsWith("spot-") && SlayerMonsters.matches(npc.getName(), null, s.taskName));
	}

	/**
	 * Whether this NPC is the task's monster without a session: one of the guide's locations' monsters, a
	 * boss variant of the task, or by the task's name.
	 */
	private boolean isTaskMonster(Task t, String npcName) {
		if (t == null || npcName == null) {
			return false;
		}
		if (t.bossId == 0 && SlayerBosses.isBoss(t.name, npcName)) {
			return true;
		}
		for (SlayerGuide.Location l : locations()) {
			List<String> m = l.getMonsters();
			if (m != null && !m.isEmpty() && SlayerMonsters.matches(npcName, m, t.name)) {
				return true;
			}
		}
		return SlayerMonsters.matches(npcName, null, t.name);
	}

	/** The monsters of a session at an unknown spot: the guide's for the task and this one (the task's name also counts). */
	private List<String> spotMonsters(Task t, String npcName) {
		List<String> out = new ArrayList<>();
		for (SlayerGuide.Location l : locations()) {
			for (String m : l.getMonsters()) {
				if (m != null && !out.contains(m)) {
					out.add(m);
				}
			}
		}
		String name = Text.removeTags(npcName);
		if (!out.isEmpty() && !out.contains(name)) {
			out.add(name);
		}
		return out;
	}

	/**
	 * Whether a session at a location of the guide is going and you are just outside it (within the radius
	 * of a spot): fighting at its edge is no other spot, so that session stays.
	 */
	private boolean nearGuideSession(WorldPoint at) {
		SlayerSession s = session;
		if (s == null || !s.isOpen() || isAdhocKey(s.locationKey)) {
			return false;
		}
		if (ticksSinceInside <= KILL_GRACE_TICKS) {
			return true;
		}
		for (SlayerGuide.Location l : locations()) {
			if (Objects.equals(l.getKey(), s.locationKey)) {
				if (l.coveredRegions().contains(at.getRegionID())) {
					return true;
				}
				return l.hasTile() && l.getPlane() == at.getPlane()
					&& Math.max(Math.abs(at.getX() - l.getX()), Math.abs(at.getY() - l.getY())) <= Math.max(0, l.getRadius()) + BOSS_AREA_RADIUS;
			}
		}
		return false;
	}

	/** Whether the guide for the task is in (or the website had none): only then is a spot "unknown". */
	private boolean guideSettled(Task t) {
		return matches(guide, t) || !loading && fetchAtMs == 0;
	}

	/** Whether this NPC is a boss variant of the session's task (never on the "Boss" task itself). */
	private static boolean isTaskBoss(NPC npc, SlayerSession s) {
		return npc != null && s != null && !isBossTask(s.taskKey) && SlayerBosses.isBoss(s.taskName, npc.getName());
	}

	/** Whether a task key ({@link Task#key()}) is the slayer "Boss" task: its boss id isn't 0. */
	static boolean isBossTask(String taskKey) {
		String[] parts = taskKey == null ? new String[0] : taskKey.split(":");
		return parts.length > 1 && !"0".equals(parts[1]);
	}

	/** You attacked or hit an NPC: see {@link #noteArea(String, long)}. Client thread. */
	private void noteArea(NPC npc) {
		if (npc != null) {
			noteArea(npc.getName(), System.currentTimeMillis());
		}
	}

	/**
	 * You fought the task's monster outside the guide's locations: the tiles round you become the task's
	 * area there ("boss-..." round a boss variant of the task, in its lair; "spot-<region>" round any other
	 * spot), and its session starts at once so this fight counts. Returns whether you are in such an area
	 * now. Client thread.
	 */
	private boolean noteArea(String npcName, long nowMs) {
		Task t = task;
		if (t == null || here != null || closeAtTick >= 0 || npcName == null) {
			return false;
		}
		boolean boss = t.bossId == 0 && SlayerBosses.isBoss(t.name, npcName);
		// An unknown spot only once the guide's locations are known (else it may be one of them).
		if (!boss && (!guideSettled(t) || !isTaskMonster(t, npcName))) {
			return false;
		}
		WorldPoint at = playerPoint();
		if (at == null || !boss && nearGuideSession(at)) {
			return false;
		}
		SlayerGuide.Location area = adhocArea;
		boolean inArea = area != null && area.contains(at.getX(), at.getY(), at.getPlane(), at.getRegionID());
		// A boss fought at a spot gets its own area (its lair); regular monsters near a boss stay in the boss's.
		if (!inArea || boss && !area.getKey().startsWith("boss-")) {
			String name = Text.removeTags(npcName);
			int region = at.getRegionID();
			area = boss
				? SlayerGuide.Location.area(SlayerBosses.areaKey(name), name, at.getX(), at.getY(), at.getPlane(), BOSS_AREA_RADIUS,
					SlayerBosses.forTask(t.name))
				: SlayerGuide.Location.area(SlayerKillLog.spotKey(region), SlayerKillLog.spotName(region), at.getX(), at.getY(), at.getPlane(),
					BOSS_AREA_RADIUS, spotMonsters(t, npcName));
			adhocArea = area;
		}
		if (session != null && Objects.equals(session.locationKey, area.getKey())) {
			// The same boss or region again: the session goes on round the new centre.
			session.bossAreaTile = Arrays.asList(area.getX(), area.getY(), area.getPlane());
		} else {
			if (session != null) {
				closeSession(false, nowMs);
			}
			startSession(t, area, nowMs);
		}
		here = area;
		ticksSinceInside = 0;
		return true;
	}

	/** A restored session at a boss's area or an unknown spot: that area again (null for a location of the guide). */
	static SlayerGuide.Location bossAreaOf(SlayerSession s) {
		List<Integer> tile = s.bossAreaTile;
		if (!isAdhocKey(s.locationKey) || tile == null || tile.size() < 3 || tile.contains(null)) {
			return null;
		}
		return SlayerGuide.Location.area(s.locationKey, s.locationName, tile.get(0), tile.get(1), tile.get(2), BOSS_AREA_RADIUS, s.monsters);
	}

	/** Kills are timed while a session is going (and for a few ticks after its task ended). */
	private boolean timing() {
		return session != null && (session.isOpen() || closeAtTick >= 0);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged e) {
		Guard.run(GUARD_FEATURE, () -> {
			Actor target = e.getTarget();
			if (e.getSource() == client.getLocalPlayer() && target instanceof NPC) {
				noteArea((NPC) target);
			}
			if (timing() && e.getSource() == client.getLocalPlayer() && target instanceof NPC && isTaskNpc((NPC) target)) {
				timer.attacked(((NPC) target).getIndex(), System.currentTimeMillis());
			}
		});
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e) {
		Guard.run(GUARD_FEATURE, () -> {
			Actor actor = e.getActor();
			if (actor instanceof NPC && e.getHitsplat() != null && e.getHitsplat().isMine()) {
				noteArea((NPC) actor);
			}
			if (timing() && actor instanceof NPC && e.getHitsplat() != null && e.getHitsplat().isMine() && isTaskNpc((NPC) actor)) {
				timer.hit(((NPC) actor).getIndex(), System.currentTimeMillis());
			}
		});
	}

	@Subscribe
	public void onActorDeath(ActorDeath e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (e.getActor() instanceof NPC) {
				died((NPC) e.getActor());
			}
		});
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e) {
		Guard.run(GUARD_FEATURE, () -> {
			NPC npc = e.getNpc();
			if (npc.isDead()) {
				// Also for deaths without an ActorDeath; the timer only counts each NPC once.
				died(npc);
			} else {
				timer.forget(npc.getIndex());
			}
		});
	}

	private void died(NPC npc) {
		long now = System.currentTimeMillis();
		Long ms = timer.died(npc.getIndex(), now);
		if (ms != null && timing()) {
			session.addKillTime(ms, isTaskBoss(npc, session), now);
		}
		// The kill log: the task monster that died (the count going down pairs with it, see SlayerKillLog.Matcher),
		// also without a session (a kill at an unknown spot opens one).
		boolean taskNpc = timing() ? isTaskNpc(npc) : isTaskMonster(task, npc.getName());
		SlayerKillLog.Npc key = taskNpc ? killLogNpc(npc) : null;
		if (key == null) {
			return;
		}
		SlayerKillLog.Npc counted = killMatcher.died(key, ms != null, client.getTickCount());
		if (timing()) {
			if (ms != null) {
				session.logFightTime(key, ms, now);
			}
			if (counted != null) {
				session.logKills(counted, 1, now);
			}
		}
	}

	/** The kill log's line for this NPC dying now: its id, name and level, your region and multi-combat. Client thread. */
	private SlayerKillLog.Npc killLogNpc(NPC npc) {
		WorldPoint at = playerPoint();
		if (npc == null || at == null) {
			return null;
		}
		boolean multi = client.getVarbitValue(VarbitID.MULTIWAY_INDICATOR) != 0;
		return new SlayerKillLog.Npc(npc.getId(), Text.removeTags(npc.getName()), npc.getCombatLevel(), at.getRegionID(), multi);
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (!timing() || !isTaskNpc(e.getNpc())) {
				return;
			}
			long gp = 0;
			for (ItemStack item : e.getItems()) {
				gp += itemManager.getItemPrice(item.getId()) * (long) item.getQuantity();
			}
			session.addLoot(gp, System.currentTimeMillis());
		});
	}

	/** Slayer XP at the last stat change (0: not seen yet this login). */
	private int lastSlayerXp;

	/** Slayer XP gained while a session is open counts for it (the website shows it per task, and per hour). */
	@Subscribe
	public void onStatChanged(StatChanged e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (e.getSkill() != Skill.SLAYER) {
				return;
			}
			int xp = e.getXp();
			SlayerSession s = session;
			if (lastSlayerXp > 0 && xp > lastSlayerXp && s != null && s.isOpen()) {
				s.addSlayerXp(xp - lastSlayerXp, System.currentTimeMillis());
			}
			lastSlayerXp = xp;
		});
	}

	@Subscribe
	public void onChatMessage(ChatMessage e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (e.getType() == ChatMessageType.GAMEMESSAGE && SlayerMonitor.COMPLETION_CHAT_PATTERN.matcher(Text.removeTags(e.getMessage())).find()) {
				completionChatTick = client.getTickCount();
			}
		});
	}

	// ---- The guide: map point name, teleport left click ------------------------------------------

	static String mapPointName(SlayerGuide.Location l) {
		return "Slayer: " + l.getName();
	}

	/**
	 * The guide was switched on: load its location if it isn't yet. No popup: the guide's box in the game
	 * shows the way there (popups are for accomplishments, like a completed task).
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (OSRSCompanionConfig.CONFIG_GROUP.equals(e.getGroup()) && "slayerGuide".equals(e.getKey())
				&& "true".equals(e.getNewValue()) && location == null) {
				refreshIfStale();
			}
		});
	}

	/** "Auto set teleport left click" for the way to the location, while the guide leads you there (a farm run goes first). */
	@Subscribe(priority = -100)
	public void onPostMenuSort(PostMenuSort e) {
		Guard.run(GUARD_FEATURE, () -> {
			SlayerGuide.Location l = location;
			if (!config.autoCapeTeleport() || !isGuiding() || l == null || l.getTravel() == null || farmRun.isRunning() || client.isMenuOpen()) {
				return;
			}
			SlayerGear.Set set = getGearSet();
			teleports.apply(l.getTravel(), "slayer " + l.getName(),
				m -> keepsWornLeftClick(m.getItemId(), m.getTarget(), set));
		});
	}

	/**
	 * Whether a menu entry's item is worn by the suggested setup (the item, another version of it, or the
	 * same name): its left click stays "Wear" instead of the teleport. False without a setup (as before).
	 */
	static boolean keepsWornLeftClick(int menuItemId, String menuTarget, SlayerGear.Set set) {
		if (set == null) {
			return false;
		}
		int base = menuItemId > 0 ? net.runelite.client.game.ItemVariationMapping.map(menuItemId) : -1;
		String target = TeleportLeftClick.baseName(menuTarget);
		for (SlayerGear.Piece p : set.getEquipment()) {
			if (p.getItem() <= 0) {
				continue;
			}
			if (menuItemId > 0 && (p.getItem() == menuItemId || net.runelite.client.game.ItemVariationMapping.map(p.getItem()) == base)) {
				return true;
			}
			if (!target.isEmpty() && target.equals(TeleportLeftClick.baseName(p.getName()))) {
				return true;
			}
		}
		return false;
	}

	// ---- Read by the panel and overlays -------------------------------------------------------

	/** The guide is on, there is a task and a location, and you are not there yet. */
	public boolean isGuiding() {
		return config.slayerGuide() && task != null && location != null && here == null;
	}

	/** The guide is on for a task and you aren't at one of its locations (the location may still be on its way). */
	public boolean isGuideOn() {
		return config.slayerGuide() && task != null && here == null;
	}

	/** The guide's locations for the current task (empty without a guide for it). */
	List<SlayerGuide.Location> guideLocations() {
		return locations();
	}

	/** Whether this NPC counts for the current task (its monster or a boss variant). Client thread. */
	boolean isTaskTarget(String npcName) {
		return isTaskMonster(task, npcName);
	}

	/** The guide is on and you are at one of the task's locations. */
	public boolean isAtLocation() {
		return config.slayerGuide() && task != null && here != null;
	}

	/** The master's name as the website has it, or null. */
	public String getMaster() {
		SlayerGuide g = guide;
		return matches(g, task) ? g.getTask().getMaster() : null;
	}

	/**
	 * Still getting the location and gear for the task you have: a guide for it is loading, due or being
	 * retried while the website catches up with a new task. False once it arrived, or the retries ran out.
	 */
	public boolean isFetchingForTask() {
		return task != null && !isGuideForTask() && error == null && (loading || fetchAtMs > 0);
	}

	/** True when the loaded guide is about the task you have (false while it is loading or the website lags behind). */
	public boolean isGuideForTask() {
		return matches(guide, task);
	}

	/** Plugin shutdown: the open session is stored (and sent) first. Client thread. */
	public void shutDown() {
		if (loggedIn) {
			loggedOut();
		}
		unsent = new ArrayList<>();
		gearOpen = false;
		fetchAtMs = 0;
		error = null;
		player = null;
	}
}
