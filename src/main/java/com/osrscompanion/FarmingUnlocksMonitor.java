package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Syncs your house for the farm route: its fairy ring (then a Construction/Max cape beats the
 * Ardougne cloak for reaching one), portal chamber and Portal Nexus destinations (teleports for
 * the route) and the furniture you can use (the website's House page). The house is looked around
 * a couple of ticks after its scene loads; the Nexus when its menu opens. The other farming
 * unlocks (auto-weed, Barbarian Farming, the Tithe Farm, the minigame teleport, the scroll
 * prayers) go through the data pump ({@link VarPump}).
 */
@Singleton
public class FarmingUnlocksMonitor {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "farming-unlocks-sync";

	/** Building a fairy ring takes 85 Construction (boostable); below this it is someone else's. */
	private static final int MIN_CONSTRUCTION_FOR_RING = 80;
	private static final int HOUSE_CHECK_DELAY_TICKS = 2;
	/** "Varrock Portal", "Troll Stronghold Portal": portal chamber destinations. */
	private static final Pattern PORTAL = Pattern.compile("(?i)(.+) portal");
	/** Objects every house has, or building-mode hotspots: not worth listing. */
	private static final Pattern IGNORED = Pattern.compile("(?i)portal|exit portal|door|large door|.* space|.* hotspot");
	/** Lines of the Portal Nexus menu that are not destinations. */
	private static final Pattern NEXUS_NOISE = Pattern.compile("(?i).*(nexus|teleport|scry|config|select|close|menu|search).*");
	/** The key shown before a Nexus destination ("1 : Varrock"). */
	private static final Pattern NEXUS_KEY = Pattern.compile("^\\s*[0-9A-Za-z]\\s*:\\s*");

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final Gson gson;

	private String lastSyncJson;
	/** Null until the plugin has been inside your house this session. */
	private Boolean pohFairyRing;
	/** Portal chamber destinations and usable furniture; null until seen this session. */
	private List<String> pohPortals;
	private List<String> pohFeatures;
	/** Portal Nexus destinations; null until its menu was opened this session. */
	private List<String> pohNexus;
	/** Entered through a friend's-house option, so the house being loaded is not yours. */
	private boolean visitingFriend;
	private int houseCheckTick = -1;
	private int nexusReadTick = -1;

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
		lastSyncJson = null;
		pohFairyRing = null;
		pohPortals = null;
		pohFeatures = null;
		pohNexus = null;
		visitingFriend = false;
		houseCheckTick = -1;
		nexusReadTick = -1;
	}

	/** Re-pushes the house as last seen (the panel's "Sync &amp; refresh"). */
	public void forceSync() {
		Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
			lastSyncJson = null;
			sync();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			GameState newState = event.getGameState();
			if (newState == GameState.LOGGED_IN) {
				// A scene finished loading: if it is a house, look around once its objects are in.
				houseCheckTick = client.getTickCount() + HOUSE_CHECK_DELAY_TICKS;
			}
		});
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		Guard.run(GUARD_FEATURE, () -> {
			String option = Text.removeTags(event.getMenuOption()).toLowerCase();
			String target = Text.removeTags(event.getMenuTarget()).toLowerCase();
			if (option.contains("friend") || option.startsWith("visit") || option.equals("enter house")) {
				visitingFriend = true;
			} else if (option.equals("home") || option.contains("tele to poh") || option.contains("teleport to house")
					|| target.contains("teleport to house") || target.contains("house tab")) {
				visitingFriend = false;
			}
		});
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == InterfaceID.TELENEXUS_TELEPORT) {
				// Its rows are filled in by a script after it loads.
				nexusReadTick = client.getTickCount() + 1;
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (houseCheckTick >= 0 && client.getTickCount() >= houseCheckTick) {
				houseCheckTick = -1;
				checkHouse();
			}
			if (nexusReadTick >= 0 && client.getTickCount() >= nexusReadTick) {
				nexusReadTick = -1;
				readNexus();
			}
		});
	}

	/** True inside your own house (an instance with the exit portal, not entered as a guest). */
	private boolean inOwnHouse(WorldView view) {
		if (view == null || !view.isInstance() || visitingFriend) {
			return false;
		}
		for (Tile[][] plane : view.getScene().getTiles()) {
			for (Tile[] column : plane) {
				for (Tile tile : column) {
					if (tile == null) {
						continue;
					}
					for (GameObject object : tile.getGameObjects()) {
						if (object != null && object.getId() == ObjectID.POH_EXIT_PORTAL) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	/** In your own house: notes the fairy ring, portals and furniture, and syncs a change. Client thread. */
	private void checkHouse() {
		WorldView view = client.getTopLevelWorldView();
		if (view == null || !view.isInstance()) {
			visitingFriend = false;
			return;
		}
		if (!inOwnHouse(view)) {
			return;
		}
		boolean ring = false;
		Set<String> portals = new TreeSet<>();
		Set<String> features = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (Tile[][] plane : view.getScene().getTiles()) {
			for (Tile[] column : plane) {
				for (Tile tile : column) {
					if (tile == null) {
						continue;
					}
					for (GameObject object : tile.getGameObjects()) {
						if (object != null) {
							ring |= object.getId() == ObjectID.POH_FAIRY_RING || object.getId() == ObjectID.POH_SPIRIT_RING;
							note(object, portals, features);
						}
					}
					note(tile.getWallObject(), portals, features);
					note(tile.getDecorativeObject(), portals, features);
					note(tile.getGroundObject(), portals, features);
				}
			}
		}
		Boolean hasRing = ring && client.getRealSkillLevel(Skill.CONSTRUCTION) >= MIN_CONSTRUCTION_FOR_RING;
		List<String> portalList = new ArrayList<>(portals);
		List<String> featureList = new ArrayList<>(features);
		if (!hasRing.equals(pohFairyRing) || !portalList.equals(pohPortals) || !featureList.equals(pohFeatures)) {
			pohFairyRing = hasRing;
			pohPortals = portalList;
			pohFeatures = featureList;
			sync();
		}
	}

	/** Adds a usable object's name to the furniture, and its destination when it is a portal. */
	private void note(TileObject object, Set<String> portals, Set<String> features) {
		if (object == null) {
			return;
		}
		ObjectComposition comp = client.getObjectDefinition(object.getId());
		if (comp != null && comp.getImpostorIds() != null) {
			comp = comp.getImpostor();
		}
		if (comp == null || comp.getName() == null) {
			return;
		}
		String name = Text.removeTags(comp.getName()).trim();
		if (name.isEmpty() || name.equalsIgnoreCase("null") || IGNORED.matcher(name).matches() || !hasAction(comp)) {
			return;
		}
		Matcher portal = PORTAL.matcher(name);
		if (portal.matches()) {
			portals.add(portal.group(1).trim().toLowerCase());
		}
		features.add(name);
	}

	private static boolean hasAction(ObjectComposition comp) {
		String[] actions = comp.getActions();
		if (actions != null) {
			for (String a : actions) {
				if (a != null && !a.isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	/** Reads the destinations off the Portal Nexus teleport menu (your own house only). Client thread. */
	private void readNexus() {
		if (!inOwnHouse(client.getTopLevelWorldView())) {
			return;
		}
		Set<String> found = new TreeSet<>();
		collectNexus(client.getWidget(InterfaceID.TelenexusTeleport.UNIVERSE), found, 0);
		List<String> list = new ArrayList<>(found);
		if (!list.isEmpty() && !list.equals(pohNexus)) {
			pohNexus = list;
			sync();
		}
	}

	private void collectNexus(Widget w, Set<String> found, int depth) {
		if (w == null || depth > 6) {
			return;
		}
		String text = w.getText();
		if (text != null && !text.isEmpty()) {
			String clean = NEXUS_KEY.matcher(Text.removeTags(text).trim()).replaceFirst("").trim();
			if (clean.length() >= 3 && clean.length() <= 40 && clean.matches(".*[A-Za-z]{3}.*") && !NEXUS_NOISE.matcher(clean).matches()) {
				found.add(clean.toLowerCase());
			}
		}
		for (Widget[] kids : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()}) {
			if (kids != null) {
				for (Widget kid : kids) {
					collectNexus(kid, found, depth + 1);
				}
			}
		}
	}

	/** Syncs what was seen of the house (deduped). Client thread only. */
	private void sync() {
		if (pohFairyRing == null && pohNexus == null || client.getGameState() != GameState.LOGGED_IN
				|| !config.syncTimeTracking() || !SyncGuard.hasAppKey(config) || GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String player = SyncGuard.getPlayerUsername(client);
		if (player == null) {
			return;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("player_username", player);
		if (pohFairyRing != null) {
			payload.put("poh_fairy_ring", pohFairyRing);
		}
		if (pohPortals != null) {
			payload.put("poh_portals", pohPortals);
			payload.put("poh_features", pohFeatures);
		}
		if (pohNexus != null) {
			payload.put("poh_nexus", pohNexus);
		}
		String json = gson.toJson(payload);
		if (json.equals(lastSyncJson)) {
			return;
		}
		lastSyncJson = json;
		apiClient.sendFarmingUnlocks(json);
	}
}
