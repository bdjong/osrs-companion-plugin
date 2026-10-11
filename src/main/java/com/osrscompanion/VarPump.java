package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * The data pump: sends the raw game values the website asks for (GET /api/runelite/vars, once per login: per
 * source, its varbits, varplayers, quests and regions), and the website works out what they mean: the
 * achievement diaries, combat achievements, Managing Miscellania, the house location, the farming unlocks and the
 * dailies. Only the sources whose sync is on are sent. Read and sent like the diary sync: never during the
 * post-login replay ({@link DiarySyncTrigger}), a few ticks after a watched value changes, on walking into a
 * watched region (a visit), after 00:00 UTC (the dailies), and once at logout.
 */
@Singleton
public class VarPump {
	static final String GUARD_FEATURE = "var-pump";
	private static final long DAY_MS = 86_400_000L;

	/** What one source needs, as GET /api/runelite/vars answers it. */
	static final class Source {
		int[] varbits = new int[0];
		int[] varps = new int[0];
		String[] quests = new String[0];
		int[] regions = new int[0];
	}

	static final class WatchList {
		Map<String, Source> sources = Collections.emptyMap();
	}

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final Gson gson;
	private final DiarySyncTrigger trigger = new DiarySyncTrigger();

	private volatile WatchList watchList;
	private volatile Set<Integer> varbits;
	private volatile Set<Integer> varps;
	private volatile Set<Integer> regions;
	private volatile boolean fetching;
	private GameState previousGameState = GameState.UNKNOWN;
	private boolean wasInRegion;
	/** When this login (or world hop) began; 0 logged out. */
	private long sessionStart;
	private long sentDay = -1;
	private String lastSentJson;

	@Inject
	public VarPump(Client client, ClientThread clientThread, OSRSCompanionConfig config, OSRSCompanionApiClient apiClient, Gson gson) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.gson = gson;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			GameState state = event.getGameState();
			if (previousGameState == GameState.LOGGED_IN && state != GameState.LOGGED_IN && trigger.leaveAndShouldFlush()) {
				// Logging out: the values are still this player's fully loaded ones.
				send();
			}
			if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING) {
				// A new session: the claimed flags are today's again, and the next login sends once even unchanged.
				sessionStart = 0;
				lastSentJson = null;
				wasInRegion = false;
			}
			if (state == GameState.LOGIN_SCREEN) {
				// The next login asks for the watch list again (the website may watch more by then).
				setWatchList(null);
			}
			previousGameState = state;
		});
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			Set<Integer> bits = varbits;
			if (bits == null || bits.contains(event.getVarbitId()) || varps.contains(event.getVarpId())) {
				trigger.varbitChanged();
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		Guard.run(GUARD_FEATURE, () -> {
			boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
			if (loggedIn) {
				if (sessionStart == 0) {
					sessionStart = System.currentTimeMillis();
				}
				Set<Integer> watched = regions;
				Integer region = region();
				boolean inRegion = watched != null && region != null && watched.contains(region);
				if (inRegion && !wasInRegion || sentDay >= 0 && sentDay != today()) {
					trigger.varbitChanged();
				}
				wasInRegion = inRegion;
			}
			if (trigger.tick(client.getTickCount(), loggedIn) == DiarySyncTrigger.Decision.SYNC) {
				send();
			}
		});
	}

	/** Sends again (the panel's "Sync & refresh", an App Key entered), once the session has settled. */
	public void forceSync() {
		Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
			if (client.getGameState() == GameState.LOGGED_IN && trigger.isBaselineSynced()) {
				lastSentJson = null;
				send();
			}
		});
	}

	public void stop() {
		previousGameState = GameState.UNKNOWN;
		trigger.reset();
		lastSentJson = null;
		sessionStart = 0;
		sentDay = -1;
		wasInRegion = false;
		setWatchList(null);
	}

	private static long today() {
		return Math.floorDiv(System.currentTimeMillis(), DAY_MS);
	}

	private Integer region() {
		Player me = client.getLocalPlayer();
		return me == null || me.getWorldLocation() == null ? null : me.getWorldLocation().getRegionID();
	}

	/** Whether the sync this source belongs to is on. */
	boolean enabled(String source) {
		switch (source) {
			case "achievement_diaries":
				return config.syncAchievementDiaries();
			case "combat_achievements":
				return config.syncCombatAchievements();
			case "kingdom":
				return config.syncKingdom();
			case "house_location":
				return config.syncHouseLocation();
			case "farming_unlocks":
				return config.syncTimeTracking();
			case "dailies":
				return config.syncDailies();
			default:
				return false;
		}
	}

	/** Reads the enabled sources' values and sends them (deduped per day); asks for the watch list first. Client thread. */
	private void send() {
		if (!SyncGuard.hasAppKey(config) || GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String player = SyncGuard.getPlayerUsername(client);
		if (player == null) {
			return;
		}
		WatchList list = watchList;
		if (list == null) {
			fetchWatchList(player);
			return;
		}
		List<String> sources = new ArrayList<>();
		Map<Integer, Integer> bitValues = new TreeMap<>();
		Map<Integer, Integer> playerValues = new TreeMap<>();
		Map<String, String> questStates = new TreeMap<>();
		for (Map.Entry<String, Source> e : list.sources.entrySet()) {
			if (!enabled(e.getKey())) {
				continue;
			}
			sources.add(e.getKey());
			Source s = e.getValue();
			for (int id : s.varbits) {
				bitValues.put(id, client.getVarbitValue(id));
			}
			for (int id : s.varps) {
				playerValues.put(id, client.getVarpValue(id));
			}
			for (String quest : s.quests) {
				try {
					questStates.put(quest, Quest.valueOf(quest).getState(client).name());
				} catch (IllegalArgumentException unknownToThisRuneLite) {
					// The website leaves a source alone without all its values.
				}
			}
		}
		if (sources.isEmpty()) {
			return;
		}
		String json = json(player, sources, bitValues, playerValues, questStates, region(), sessionStart);
		long day = today();
		// The same values on a new day still go out: the dailies read them differently after 00:00 UTC.
		if (json.equals(lastSentJson) && day == sentDay) {
			return;
		}
		lastSentJson = json;
		sentDay = day;
		apiClient.sendVars(json, player);
	}

	String json(String player, List<String> sources, Map<Integer, Integer> bitValues, Map<Integer, Integer> playerValues,
		Map<String, String> quests, Integer region, long sessionStart) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", player);
		body.put("sources", sources);
		body.put("varbits", bitValues);
		body.put("varps", playerValues);
		body.put("quests", quests);
		body.put("region", region);
		body.put("session_start", sessionStart > 0 ? sessionStart : null);
		return gson.toJson(body);
	}

	void setWatchList(WatchList list) {
		Set<Integer> bits = null;
		Set<Integer> players = null;
		Set<Integer> places = null;
		if (list != null) {
			bits = new HashSet<>();
			players = new HashSet<>();
			places = new HashSet<>();
			for (Source s : list.sources.values()) {
				for (int id : s.varbits) {
					bits.add(id);
				}
				for (int id : s.varps) {
					players.add(id);
				}
				for (int id : s.regions) {
					places.add(id);
				}
			}
		}
		varps = players;
		regions = places;
		varbits = bits;
		watchList = list;
	}

	private void fetchWatchList(String player) {
		if (fetching) {
			return;
		}
		fetching = true;
		apiClient.getVarWatchList(player, WatchList.class, new OSRSCompanionApiClient.RoadmapCallback<WatchList>() {
			@Override
			public void onSuccess(WatchList result) {
				setWatchList(result);
				fetching = false;
				// Sent on the client thread as soon as the list is in.
				Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
					if (client.getGameState() == GameState.LOGGED_IN && trigger.isBaselineSynced()) {
						send();
					}
				});
			}

			@Override
			public void onError(String message) {
				fetching = false;
			}
		});
	}
}
