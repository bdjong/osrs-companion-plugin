package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Syncs farming unlocks the farm route's loadout depends on: auto-weed (no rake needed),
 * Barbarian Farming (planting seeds without a seed dibber) and whether your house has a
 * fairy ring (then a Construction/Max cape beats the Ardougne cloak for reaching one).
 * Read timing reuses {@link DiarySyncTrigger} like the house location: after the post-login
 * replay settles, a few ticks after either varbit changes, and on logout. The house is
 * checked a couple of ticks after its scene loads.
 */
@Slf4j
@Singleton
public class FarmingUnlocksMonitor {
	/** Building a fairy ring takes 85 Construction (boostable); below this it is someone else's. */
	private static final int MIN_CONSTRUCTION_FOR_RING = 80;
	private static final int HOUSE_CHECK_DELAY_TICKS = 2;

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final Gson gson;
	private final DiarySyncTrigger trigger = new DiarySyncTrigger();

	private GameState previousGameState = GameState.UNKNOWN;
	private String lastSyncJson;
	/** Null until the plugin has been inside your house this session. */
	private Boolean pohFairyRing;
	/** Entered through a friend's-house option, so the house being loaded is not yours. */
	private boolean visitingFriend;
	private int houseCheckTick = -1;

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
		pohFairyRing = null;
		visitingFriend = false;
		houseCheckTick = -1;
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
		if (newState == GameState.LOGGED_IN) {
			// A scene finished loading: if it is a house, look for the fairy ring once its objects are in.
			houseCheckTick = client.getTickCount() + HOUSE_CHECK_DELAY_TICKS;
		}
		previousGameState = newState;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		String option = Text.removeTags(event.getMenuOption()).toLowerCase();
		String target = Text.removeTags(event.getMenuTarget()).toLowerCase();
		if (option.contains("friend") || option.startsWith("visit") || option.equals("enter house")) {
			visitingFriend = true;
		} else if (option.equals("home") || option.contains("tele to poh") || option.contains("teleport to house")
				|| target.contains("teleport to house") || target.contains("house tab")) {
			visitingFriend = false;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (event.getVarbitId() == VarbitID.FARMING_BLOCKWEEDS || event.getVarbitId() == VarbitID.BRUT_FARMING_PLANTING) {
			trigger.varbitChanged();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (houseCheckTick >= 0 && client.getTickCount() >= houseCheckTick) {
			houseCheckTick = -1;
			checkHouse();
		}
		if (trigger.tick(client.getTickCount(), client.getGameState() == GameState.LOGGED_IN) == DiarySyncTrigger.Decision.SYNC) {
			sync();
		}
	}

	/** In your own house: is there a fairy ring or spiritual fairy tree? Syncs a change. Client thread. */
	private void checkHouse() {
		WorldView view = client.getTopLevelWorldView();
		if (view == null || !view.isInstance()) {
			visitingFriend = false;
			return;
		}
		boolean portal = false;
		boolean ring = false;
		for (Tile[][] plane : view.getScene().getTiles()) {
			for (Tile[] column : plane) {
				for (Tile tile : column) {
					if (tile == null) {
						continue;
					}
					for (GameObject object : tile.getGameObjects()) {
						if (object == null) {
							continue;
						}
						int id = object.getId();
						portal |= id == ObjectID.POH_EXIT_PORTAL;
						ring |= id == ObjectID.POH_FAIRY_RING || id == ObjectID.POH_SPIRIT_RING;
					}
				}
			}
		}
		if (!portal || visitingFriend) {
			return;
		}
		boolean has = ring && client.getRealSkillLevel(Skill.CONSTRUCTION) >= MIN_CONSTRUCTION_FOR_RING;
		if (!Boolean.valueOf(has).equals(pohFairyRing)) {
			pohFairyRing = has;
			log.debug("House fairy ring: {}", has);
			if (trigger.isBaselineSynced()) {
				sync();
			}
		}
	}

	/** Reads both varbits (and the house fairy ring once seen) and syncs them (deduped). Client thread only. */
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
		if (pohFairyRing != null) {
			payload.put("poh_fairy_ring", pohFairyRing);
		}
		String json = gson.toJson(payload);
		if (json.equals(lastSyncJson)) {
			return;
		}
		lastSyncJson = json;
		log.debug("Syncing farming unlocks for {}: {}", player, json);
		apiClient.sendFarmingUnlocks(json);
	}
}
