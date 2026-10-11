package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * The data pump: sends the raw values of the varbits and varplayers the website asks for (GET /api/runelite/vars,
 * once per login), and the website works out what they mean. Read and sent like the diary sync: never during the
 * post-login replay ({@link DiarySyncTrigger}), a few ticks after a watched value changes, and once at logout.
 *
 * <p>First data type: the achievement diaries, checked side by side; the payload also carries what
 * {@link AchievementDiariesReader} made ({@code compare}), and the website only compares until the switch.
 */
@Singleton
public class VarPump {
	static final String GUARD_FEATURE = "var-pump";

	/** What GET /api/runelite/vars answers. */
	static final class WatchList {
		int[] varbits = new int[0];
		int[] varps = new int[0];
	}

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final Gson gson;
	private final AchievementDiariesReader diaries;
	private final DiarySyncTrigger trigger = new DiarySyncTrigger();

	private volatile Set<Integer> varbits;
	private volatile Set<Integer> varps;
	private volatile boolean fetching;
	private GameState previousGameState = GameState.UNKNOWN;
	private String lastSentJson;

	@Inject
	public VarPump(Client client, ClientThread clientThread, OSRSCompanionConfig config, OSRSCompanionApiClient apiClient, Gson gson,
		AchievementDiariesReader diaries) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.gson = gson;
		this.diaries = diaries;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			boolean wasLoggedIn = previousGameState == GameState.LOGGED_IN;
			if (wasLoggedIn && event.getGameState() != GameState.LOGGED_IN && trigger.leaveAndShouldFlush()) {
				// Logging out: the values are still this player's fully loaded ones.
				send();
			}
			if (event.getGameState() == GameState.LOGIN_SCREEN) {
				// The next login asks for the watch list again (the website may watch more by then).
				varbits = null;
				varps = null;
			}
			previousGameState = event.getGameState();
		});
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			Set<Integer> bits = varbits;
			Set<Integer> players = varps;
			if (bits == null || bits.contains(event.getVarbitId()) || players.contains(event.getVarpId())) {
				trigger.varbitChanged();
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		Guard.run(GUARD_FEATURE, () -> {
			boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
			if (trigger.tick(client.getTickCount(), loggedIn) == DiarySyncTrigger.Decision.SYNC) {
				send();
			}
		});
	}

	/** Sends again (App Key entered while logged in), once the session has settled. */
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
		varbits = null;
		varps = null;
	}

	/** Reads the watched values and sends them (deduped); asks for the watch list first when there is none. Client thread. */
	private void send() {
		if (!config.syncAchievementDiaries() || !SyncGuard.hasAppKey(config) || GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String player = SyncGuard.getPlayerUsername(client);
		if (player == null) {
			return;
		}
		Set<Integer> bits = varbits;
		Set<Integer> players = varps;
		if (bits == null) {
			fetchWatchList(player);
			return;
		}
		Map<Integer, Integer> bitValues = new TreeMap<>();
		for (int id : bits) {
			bitValues.put(id, client.getVarbitValue(id));
		}
		Map<Integer, Integer> playerValues = new TreeMap<>();
		for (int id : players) {
			playerValues.put(id, client.getVarpValue(id));
		}
		Map<String, Object> compare = Collections.singletonMap("achievement_diaries", diaries.read(client));
		String json = json(player, bitValues, playerValues, compare);
		if (json.equals(lastSentJson)) {
			return;
		}
		lastSentJson = json;
		apiClient.sendVars(json, player);
	}

	String json(String player, Map<Integer, Integer> bitValues, Map<Integer, Integer> playerValues, Map<String, Object> compare) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", player);
		body.put("varbits", bitValues);
		body.put("varps", playerValues);
		if (compare != null) {
			body.put("compare", compare);
		}
		return gson.toJson(body);
	}

	private void fetchWatchList(String player) {
		if (fetching) {
			return;
		}
		fetching = true;
		apiClient.getVarWatchList(player, WatchList.class, new OSRSCompanionApiClient.RoadmapCallback<WatchList>() {
			@Override
			public void onSuccess(WatchList result) {
				Set<Integer> bits = new TreeSet<>();
				Set<Integer> players = new TreeSet<>();
				for (int id : result.varbits) {
					bits.add(id);
				}
				for (int id : result.varps) {
					players.add(id);
				}
				varps = players;
				varbits = bits;
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
