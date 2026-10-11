package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.DailyChecks.Result;
import com.osrscompanion.DailyChecks.State;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
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
import net.runelite.client.eventbus.Subscribe;

/**
 * The dailies sync: reads which are done, open or locked from the game ({@link DailyChecks}) after login settles
 * and a few ticks after a claim varbit changes, and sends them to the website's Dailies page (which has the
 * instructions and the reset timers).
 */
@Singleton
public class DailiesManager {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "dailies";

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final Gson gson;
	private final DiarySyncTrigger trigger = new DiarySyncTrigger();

	/** What the game said at the last read (client thread). */
	private Map<String, Result> local = Collections.emptyMap();
	private boolean ironman;
	private boolean ultimate;
	private String player;

	/** UTC day of the login (or hop): a claimed flag read on a later day may be yesterday's; -1 logged out. */
	private long loginDay = -1;
	private long kingdomVisitDay = -1;
	private long readDay = -1;
	private String lastSyncJson;

	@Inject
	public DailiesManager(Client client, ClientThread clientThread, OSRSCompanionConfig config, OSRSCompanionApiClient apiClient, Gson gson) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.gson = gson;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			GameState s = event.getGameState();
			// Logging in again or hopping refreshes the claimed flags: today's are the real ones from then on.
			if (s == GameState.LOGIN_SCREEN || s == GameState.HOPPING) {
				loginDay = -1;
			}
		});
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (DailyChecks.WATCHED.contains(event.getVarbitId()) || event.getVarpId() == DailyChecks.NZONE_POINTS_VARP) {
				trigger.varbitChanged();
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		Guard.run(GUARD_FEATURE, () -> {
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
				// After 00:00 UTC: read again.
				if (readDay >= 0 && readDay != today) {
					trigger.varbitChanged();
				}
			}
			if (trigger.tick(client.getTickCount(), loggedIn) == DiarySyncTrigger.Decision.SYNC) {
				read(today);
			}
		});
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
		sync();
	}

	/** Sends the game's states (deduped: only what changed goes out). */
	private void sync() {
		String name = player;
		if (name == null || !config.syncDailies() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		List<Map<String, Object>> list = new ArrayList<>();
		for (Result r : local.values()) {
			if (r.state == State.UNKNOWN) {
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
		Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
			lastSyncJson = null;
			trigger.varbitChanged();
		});
	}

	/** Forgets the session (plugin stopping). */
	public void clear() {
		trigger.reset();
		local = Collections.emptyMap();
		loginDay = -1;
		readDay = -1;
		lastSyncJson = null;
	}
}
