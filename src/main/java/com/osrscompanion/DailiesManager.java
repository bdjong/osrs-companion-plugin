package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.DailyChecks.Result;
import com.osrscompanion.DailyChecks.State;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.Dailies;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * The dailies: reads which are done, open or locked from the game ({@link DailyChecks}) after
 * login settles and a few ticks after a claim varbit changes, syncs them to the website, loads the
 * website's guide (the way there with your unlocks, the steps, timer and kingdom states) and runs
 * the guided run: one daily at a time, ticked off when the game says it is collected (or by hand
 * in the side panel for the ones the game does not track), then on to the next open one.
 */
@Slf4j
@Singleton
public class DailiesManager {
	/** Re-read the guide at most this often while the panel or a run uses it. */
	/** A backstop: website changes are picked up by the web version check ({@link WebVersionWatcher}). */
	static final long REFRESH_INTERVAL_MS = 10 * 60_000;
	private static final String CHAT_PREFIX = "<col=f472b6>Dailies:</col> ";

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final ConfigManager configManager;
	private final OSRSCompanionApiClient apiClient;
	private final Gson gson;
	private final DiarySyncTrigger trigger = new DiarySyncTrigger();

	/** What the game said at the last read (client thread writes, everyone reads the snapshot). */
	private volatile Map<String, Result> local = Collections.emptyMap();
	private volatile boolean ironman;
	private volatile boolean ultimate;
	private volatile String player;
	private volatile Dailies guide;
	private volatile boolean loading;
	private volatile String error;
	private volatile long fetchedAtMs;
	/** Dailies ticked by hand: id to when the tick stops counting (the reset). */
	private final Map<String, Long> ticked = new ConcurrentHashMap<>();

	/** UTC day of the login (or hop): a claimed flag read on a later day may be yesterday's; -1 logged out. */
	private long loginDay = -1;
	private long kingdomVisitDay = -1;
	private long readDay = -1;
	private String lastSyncJson;

	private volatile boolean running;
	private volatile String currentId;
	private final Set<String> skipped = ConcurrentHashMap.newKeySet();

	@Inject
	public DailiesManager(Client client, ClientThread clientThread, OSRSCompanionConfig config, ConfigManager configManager,
		OSRSCompanionApiClient apiClient, Gson gson) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.configManager = configManager;
		this.apiClient = apiClient;
		this.gson = gson;
		loadTicks();
	}

	// ---- Reading the game ---------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState s = event.getGameState();
		// Logging in again or hopping refreshes the claimed flags: today's are the real ones from then on.
		if (s == GameState.LOGIN_SCREEN || s == GameState.HOPPING) {
			loginDay = -1;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (DailyChecks.WATCHED.contains(event.getVarbitId()) || event.getVarpId() == DailyChecks.NZONE_POINTS_VARP) {
			trigger.varbitChanged();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		long today = DailyReset.day(System.currentTimeMillis());
		if (loggedIn) {
			if (loginDay < 0) {
				loginDay = today;
			}
			Player me = client.getLocalPlayer();
			if (me != null && me.getWorldLocation() != null && KingdomMonitor.isKingdomRegion(me.getWorldLocation().getRegionID())
				&& kingdomVisitDay != today) {
				kingdomVisitDay = today;
				trigger.varbitChanged();
			}
			// After 00:00 UTC: read again (the guide and the hand ticks of yesterday are stale too).
			if (readDay >= 0 && readDay != today) {
				trigger.varbitChanged();
				fetchedAtMs = 0;
			}
		}
		if (trigger.tick(client.getTickCount(), loggedIn) == DiarySyncTrigger.Decision.SYNC) {
			boolean first = guide == null || fetchedAtMs == 0;
			read(today);
			if (first) {
				refresh();
			}
		}
		if (running) {
			followRun();
		}
	}

	/** Reads every daily from the game and syncs it. Client thread. */
	private void read(long today) {
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		player = SyncGuard.getPlayerUsername(client);
		DailyChecks.Vars vars = new DailyChecks.Vars() {
			@Override
			public int varbit(int id) {
				return client.getVarbitValue(id);
			}

			@Override
			public int varp(int id) {
				return client.getVarpValue(id);
			}

			@Override
			public boolean questDone(Quest quest) {
				return quest.getState(client) == QuestState.FINISHED;
			}
		};
		int mode = client.getVarbitValue(VarbitID.IRONMAN);
		ironman = mode != 0;
		ultimate = mode == DailyChecks.ULTIMATE;
		local = DailyChecks.read(vars, loginDay >= 0 && loginDay != today, kingdomVisitDay == today);
		readDay = today;
		dropExpiredTicks();
		sync();
	}

	/** Sends the game's states and the hand ticks (deduped: only what changed goes out). */
	private synchronized void sync() {
		String name = player;
		if (name == null || !config.syncDailies() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		List<Map<String, Object>> list = new ArrayList<>();
		for (Result r : local.values()) {
			if (r.state == State.UNKNOWN || ticked.containsKey(r.id)) {
				continue;
			}
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("id", r.id);
			e.put("state", r.state.wire());
			if (r.amount > 0) {
				e.put("amount", r.amount);
			}
			if (r.note != null) {
				e.put("note", r.note);
			}
			list.add(e);
		}
		for (Map.Entry<String, Long> t : ticked.entrySet()) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("id", t.getKey());
			e.put("state", "done");
			e.put("manual", true);
			e.put("expires_at", java.time.Instant.ofEpochMilli(t.getValue()).toString());
			list.add(e);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", name);
		Map<String, Object> account = new LinkedHashMap<>();
		account.put("ironman", ironman);
		account.put("ultimate", ultimate);
		body.put("account", account);
		body.put("dailies", list);
		String json = gson.toJson(body);
		if (json.equals(lastSyncJson)) {
			return;
		}
		lastSyncJson = json;
		apiClient.sendDailies(json);
	}

	/** Reads and sends again (App Key entered while logged in), even when nothing changed. */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			lastSyncJson = null;
			trigger.varbitChanged();
			fetchedAtMs = 0;
		});
	}

	// ---- The website's guide ---------------------------------------------------------------

	public void refreshIfStale() {
		if (!loading && System.currentTimeMillis() - fetchedAtMs > REFRESH_INTERVAL_MS) {
			refresh();
		}
	}

	public void refresh() {
		String name = player != null ? player : SyncGuard.getPlayerUsername(client);
		if (name == null) {
			error = "Log in to the game to load your dailies.";
			return;
		}
		if (!SyncGuard.hasAppKey(config)) {
			error = "Add your App Key in the OSRS Companion settings for the guide.";
			return;
		}
		loading = true;
		fetchedAtMs = System.currentTimeMillis();
		apiClient.getDailies(name, new OSRSCompanionApiClient.RoadmapCallback<Dailies>() {
			@Override
			public void onSuccess(Dailies result) {
				guide = result;
				error = null;
				loading = false;
			}

			@Override
			public void onError(String message) {
				error = "Could not load the dailies guide (" + message + ").";
				loading = false;
			}
		});
	}

	// ---- What the panel and the guide show ---------------------------------------------------

	/** One daily as shown: its state merged from the hand tick, the game and the website. */
	public static final class Entry {
		public final String id;
		public final String name;
		public final State state;
		public final String note;
		public final boolean ticked;
		/** The website's details (way there, steps); null until the guide has loaded. */
		public final Dailies.Daily guide;
		/** True when the game tracks it (it ticks off by itself). */
		public final boolean tracked;
		/** How many today (or still to claim): the game's count when it has one, else the website's; 0 unknown. */
		public final int amount;

		Entry(String id, String name, State state, String note, boolean ticked, Dailies.Daily guide, boolean tracked, int amount) {
			this.id = id;
			this.name = name;
			this.state = state;
			this.note = note;
			this.ticked = ticked;
			this.guide = guide;
			this.tracked = tracked;
			this.amount = amount;
		}
	}

	/** Every daily in the website's order (the built-in list until the guide has loaded). */
	public List<Entry> entries() {
		Dailies g = guide;
		Map<String, Result> game = local;
		List<String> ids = new ArrayList<>();
		Map<String, String> names = new HashMap<>();
		if (g != null && !g.getDailies().isEmpty()) {
			for (Dailies.Daily d : g.getDailies()) {
				ids.add(d.getId());
				names.put(d.getId(), d.getShortName());
			}
		} else {
			for (String[] d : DailyChecks.DAILIES) {
				ids.add(d[0]);
				names.put(d[0], d[1]);
			}
		}
		long now = System.currentTimeMillis();
		List<Entry> out = new ArrayList<>();
		for (String id : ids) {
			Dailies.Daily d = g == null ? null : g.find(id);
			Result r = game.get(id);
			Long until = ticked.get(id);
			boolean tick = until != null && until > now;
			State s = DailyChecks.merged(tick, r, d == null ? null : d.getState(), d == null ? null : d.getSource());
			String note = s == State.DONE ? null : r != null && r.state != State.UNKNOWN ? r.note : d == null ? null : d.getNote();
			int amount = r != null && r.amount > 0 ? r.amount : d != null && d.getAmount() != null ? d.getAmount() : 0;
			out.add(new Entry(id, names.get(id), s, note, tick, d, r != null && r.state != State.UNKNOWN, amount));
		}
		return out;
	}

	public Entry entry(String id) {
		if (id == null) {
			return null;
		}
		for (Entry e : entries()) {
			if (e.id.equals(id)) {
				return e;
			}
		}
		return null;
	}

	/** Ticks a daily off by hand, or takes the tick away. Any thread. */
	public void toggleTick(String id) {
		Entry e = entry(id);
		if (e == null || e.state == State.LOCKED) {
			return;
		}
		if (e.ticked) {
			ticked.remove(id);
			sendUntick(id);
		} else if (e.state != State.DONE) {
			ticked.put(id, tickExpiry(e.guide == null ? "daily" : e.guide.getReset(), System.currentTimeMillis()));
		}
		saveTicks();
		clientThread.invokeLater(this::sync);
	}

	/** A hand tick taken away: the website forgets its done too. */
	private void sendUntick(String id) {
		String name = player;
		if (name == null || !config.syncDailies() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("id", id);
		e.put("state", "open");
		e.put("manual", true);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", name);
		body.put("dailies", Collections.singletonList(e));
		apiClient.sendDailies(gson.toJson(body));
	}

	/** When a hand tick stops counting: the next reset, a week for Tears of Guthix, 50 minutes for bird houses. */
	static long tickExpiry(String reset, long now) {
		if ("weekly".equals(reset)) {
			return DailyReset.day(now) * DailyReset.DAY_MS + 7 * DailyReset.DAY_MS;
		}
		if ("timer".equals(reset)) {
			return now + 50 * 60_000L;
		}
		return DailyReset.nextReset(now);
	}

	private void dropExpiredTicks() {
		long now = System.currentTimeMillis();
		if (ticked.values().removeIf(t -> t <= now)) {
			saveTicks();
		}
	}

	private void loadTicks() {
		ticked.clear();
		String raw = config.dailiesTicked();
		if (raw == null || raw.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		for (String part : raw.split(",")) {
			String[] kv = part.split("=");
			try {
				if (kv.length == 2 && Long.parseLong(kv[1]) > now) {
					ticked.put(kv[0], Long.parseLong(kv[1]));
				}
			} catch (NumberFormatException ignored) {
				// An old or edited value: that tick is dropped.
			}
		}
	}

	private void saveTicks() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Long> t : ticked.entrySet()) {
			sb.append(sb.length() == 0 ? "" : ",").append(t.getKey()).append('=').append(t.getValue());
		}
		configManager.setConfiguration(OSRSCompanionConfig.CONFIG_GROUP, "dailiesTicked", sb.toString());
	}

	// ---- The guided run ------------------------------------------------------------------------

	/** Starts the run at the first open daily; nothing happens when none is open. */
	public void start() {
		skipped.clear();
		Map<String, State> states = states();
		currentId = DailyChecks.nextOpen(new ArrayList<>(states.keySet()), states, null, skipped);
		running = currentId != null;
		refreshIfStale();
	}

	public void stop() {
		running = false;
		currentId = null;
	}

	/** Leaves the current daily for later (this run) and goes to the next open one. */
	public void skip() {
		String cur = currentId;
		if (cur == null) {
			return;
		}
		skipped.add(cur);
		Map<String, State> states = states();
		currentId = DailyChecks.nextOpen(new ArrayList<>(states.keySet()), states, cur, skipped);
		if (currentId == null) {
			running = false;
		}
	}

	/** On each tick of a run: once the current daily is no longer open, says so and moves on. Client thread. */
	private void followRun() {
		String cur = currentId;
		Map<String, State> states = states();
		if (cur != null && states.get(cur) == State.OPEN) {
			return;
		}
		String next = DailyChecks.nextOpen(new ArrayList<>(states.keySet()), states, cur, skipped);
		Entry done = entry(cur);
		Entry nextEntry = entry(next);
		currentId = next;
		if (next == null) {
			running = false;
			chat("every daily is done" + (done != null ? " (last: " + done.name + ")" : "") + ".");
		} else if (nextEntry != null) {
			chat((done != null ? done.name + " done. " : "") + "Next: " + nextEntry.name + ".");
		}
	}

	private Map<String, State> states() {
		Map<String, State> out = new LinkedHashMap<>();
		for (Entry e : entries()) {
			out.put(e.id, e.state);
		}
		return out;
	}

	private void chat(String text) {
		if (client.getGameState() == GameState.LOGGED_IN) {
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", CHAT_PREFIX + text, null);
		}
	}

	// ---- Getters --------------------------------------------------------------------------------

	public boolean isRunning() {
		return running;
	}

	public Entry current() {
		return running ? entry(currentId) : null;
	}

	public Dailies getGuide() {
		return guide;
	}

	public boolean isLoading() {
		return loading;
	}

	public String getError() {
		return error;
	}

	/** Open dailies and the GP they are worth (website values), for the panel. */
	public long[] openCountAndValue() {
		long count = 0;
		long value = 0;
		for (Entry e : entries()) {
			if (e.state == State.OPEN) {
				count++;
				if (e.guide != null && e.guide.getValue() != null) {
					value += e.guide.getValue();
				}
			}
		}
		return new long[]{count, value};
	}

	/** Forgets the session (plugin stopping). */
	public void clear() {
		stop();
		trigger.reset();
		local = Collections.emptyMap();
		guide = null;
		loginDay = -1;
		readDay = -1;
		lastSyncJson = null;
	}
}
