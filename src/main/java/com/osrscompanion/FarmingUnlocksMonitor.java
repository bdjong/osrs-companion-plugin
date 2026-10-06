package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Syncs farming unlocks the farm route's loadout depends on: auto-weed (no rake needed)
 * and Barbarian Farming (planting seeds without a seed dibber). Read timing reuses
 * {@link DiarySyncTrigger} like the house location: after the post-login replay settles,
 * a few ticks after either varbit changes, and on logout.
 */
@Slf4j
@Singleton
public class FarmingUnlocksMonitor {
	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final Gson gson;
	private final DiarySyncTrigger trigger = new DiarySyncTrigger();

	private GameState previousGameState = GameState.UNKNOWN;
	private String lastSyncJson;

	@Inject
	public FarmingUnlocksMonitor(Client client, ClientThread clientThread, OSRSCompanionConfig config,
			OSRSCompanionApiClient apiClient, Gson gson) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.gson = gson;
	}

	public void stop() {
		previousGameState = GameState.UNKNOWN;
		trigger.reset();
		lastSyncJson = null;
	}

	/** Re-pushes the unlocks (the panel's "Sync &amp; refresh"). No-op until the session has settled. */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			if (client.getGameState() != GameState.LOGGED_IN || !trigger.isBaselineSynced()) {
				return;
			}
			lastSyncJson = null;
			sync();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN && trigger.leaveAndShouldFlush()) {
			sync();
		}
		previousGameState = newState;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (event.getVarbitId() == VarbitID.FARMING_BLOCKWEEDS || event.getVarbitId() == VarbitID.BRUT_FARMING_PLANTING) {
			trigger.varbitChanged();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (trigger.tick(client.getTickCount(), client.getGameState() == GameState.LOGGED_IN) == DiarySyncTrigger.Decision.SYNC) {
			sync();
		}
	}

	/** Reads both varbits and syncs them (deduped). Client thread only. */
	private void sync() {
		if (!config.syncTimeTracking() || !SyncGuard.hasAppKey(config) || GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String player = SyncGuard.getPlayerUsername(client);
		if (player == null) {
			return;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("player_username", player);
		// 0 = auto-weed not unlocked, 1 = unlocked but off, 2 = on.
		payload.put("autoweed", client.getVarbitValue(VarbitID.FARMING_BLOCKWEEDS));
		payload.put("barbarian_planting", client.getVarbitValue(VarbitID.BRUT_FARMING_PLANTING));
		String json = gson.toJson(payload);
		if (json.equals(lastSyncJson)) {
			return;
		}
		lastSyncJson = json;
		log.debug("Syncing farming unlocks for {}: {}", player, json);
		apiClient.sendFarmingUnlocks(json);
	}
}
