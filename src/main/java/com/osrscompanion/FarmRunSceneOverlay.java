package com.osrscompanion;

import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.model.FarmRoute;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

/**
 * Points the way during a farm run, like Quest Helper: the yellow hint arrow and a
 * world map marker on the current stop, and the stop's patches outlined in the scene
 * (yellow to do, green planted, grey nothing to do, orange still without compost: the
 * stop just left counts too while you are still there).
 */
public class FarmRunSceneOverlay extends Overlay {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "farm-run-markers";

	/** Patch objects further than this from the stop belong to another stop (varbits repeat between regions). */
	static final int STOP_RADIUS = 32;
	private static final int SCAN_EVERY_TICKS = 2;

	private final Client client;
	private final FarmRunManager manager;
	private final ModelOutlineRenderer outlines;
	private final WorldMapPointManager worldMap;
	private final ItemManager itemManager;

	private volatile List<Found> found = Collections.emptyList();
	private WorldMapPoint mapPoint;
	private WorldPoint arrowAt;
	private int ticks;
	/** In your house on the way to the stop: the portal, nexus, fairy ring... the travel uses, and its name. */
	private volatile GameObject houseObject;
	private volatile String houseObjectName;
	static final Color HOUSE_COLOR = new Color(0x22, 0xD3, 0xEE);
	private static final Pattern PORTAL = Pattern.compile("the ([a-z' ()-]+?) portal");

	private static final class Found {
		final GameObject object;
		final FarmRoute.Patch patch;

		Found(GameObject object, FarmRoute.Patch patch) {
			this.object = object;
			this.patch = patch;
		}
	}

	@Inject
	public FarmRunSceneOverlay(Client client, FarmRunManager manager, ModelOutlineRenderer outlines, WorldMapPointManager worldMap,
		ItemManager itemManager) {
		this.client = client;
		this.manager = manager;
		this.outlines = outlines;
		this.worldMap = worldMap;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		Guard.run(GUARD_FEATURE, () -> {
			// A finished run keeps pointing at the last patches while one of them still has no compost.
			boolean active = manager.isRunning() && (!manager.isComplete() || manager.hasCompostToDo());
			FarmRoute.Stop stop = active ? manager.getCurrentStop() : null;
			if (stop == null || client.getGameState() != GameState.LOGGED_IN) {
				found = Collections.emptyList();
				houseObject = null;
				clearPointers();
				return;
			}
			if (++ticks % SCAN_EVERY_TICKS == 0 || found.isEmpty()) {
				List<Found> all = new ArrayList<>(scan(stop));
				FarmRoute.Stop behind = manager.compostStopBehind();
				if (behind != null && behind != stop) {
					all.addAll(scan(behind));
				}
				found = all;
				findHouseObject(manager.travelOf(stop));
			}
			updatePointers(stop);
		});
	}

	/**
	 * What to use in your house for this way to the stop, as names to look for: the named portal
	 * ("then the Troll Stronghold portal"), the Portal Nexus, a fairy ring, spirit tree or jewellery box.
	 * Shared with the Slayer guide ({@link SlayerGuideHouseOverlay}).
	 */
	static List<String> houseObjectNames(FarmRoute.Travel travel) {
		if (travel == null) {
			return Collections.emptyList();
		}
		String name = travel.getName() == null ? "" : travel.getName().toLowerCase();
		String text = (name + " " + (travel.getHow() == null ? "" : travel.getHow())).toLowerCase();
		if (!text.contains("poh") && !text.contains("house")) {
			return Collections.emptyList();
		}
		if (name.contains("jewellery box")) {
			// "House teleport tab, then the jewellery box (Amulet of glory)": the box only, whatever the run after mentions.
			return Collections.singletonList("jewellery box");
		}
		List<String> out = new ArrayList<>();
		if (text.contains("nexus")) {
			out.add("portal nexus");
		}
		Matcher m = PORTAL.matcher(text);
		while (m.find()) {
			String place = m.group(1).replaceAll("\\(.*?\\)", "").trim();
			if (!place.isEmpty() && !place.equals("house")) {
				out.add(place);
			}
		}
		for (String other : new String[]{"fairy ring", "spirit tree", "jewellery box"}) {
			if (text.contains(other)) {
				out.add(other);
			}
		}
		return out;
	}

	/** In your house (an instance): finds the object the way there uses, for the outline and the arrow. Client thread. */
	private void findHouseObject(FarmRoute.Travel travel) {
		GameObject object = findHouseObject(client, travel);
		houseObject = object;
		houseObjectName = object == null ? null : objectName(client, object);
	}

	/** The object's name, through its impostor (a built hotspot's furniture). Client thread. */
	static String objectName(Client client, GameObject object) {
		ObjectComposition comp = client.getObjectDefinition(object.getId());
		if (comp != null && comp.getImpostorIds() != null && comp.getImpostor() != null) {
			comp = comp.getImpostor();
		}
		return comp == null ? null : comp.getName();
	}

	/**
	 * In your house (an instance): the object the way there uses (see {@link #houseObjectNames}), null when
	 * you are not in a house or it has none. Client thread.
	 */
	static GameObject findHouseObject(Client client, FarmRoute.Travel travel) {
		WorldView view = client.getTopLevelWorldView();
		List<String> names = houseObjectNames(travel);
		if (view == null || !view.isInstance() || names.isEmpty()) {
			return null;
		}
		Tile[][] tiles = view.getScene().getTiles()[view.getPlane()];
		for (Tile[] column : tiles) {
			for (Tile tile : column) {
				if (tile == null) {
					continue;
				}
				for (GameObject object : tile.getGameObjects()) {
					if (object == null) {
						continue;
					}
					String objectName = objectName(client, object);
					String name = objectName == null ? "" : objectName.toLowerCase();
					for (String wanted : names) {
						// A destination portal is named after the place ("Troll Stronghold Portal"); the nexus,
						// fairy ring and others by what they are (a spiritual fairy tree is a fairy ring too).
						boolean match = wanted.equals("fairy ring") ? name.contains("fairy ring") || name.contains("spiritual fairy tree")
							: name.contains(wanted);
						if (match && !name.equals("portal")) {
							return object;
						}
					}
				}
			}
		}
		return null;
	}

	/** Finds the stop's patch objects in the loaded scene (the patch object's varbit is the patch's). */
	private List<Found> scan(FarmRoute.Stop stop) {
		Map<Integer, FarmRoute.Patch> byVarbit = new HashMap<>();
		for (FarmRoute.Patch p : stop.getPatches()) {
			byVarbit.putIfAbsent(p.getVarbit(), p);
		}
		WorldView view = client.getTopLevelWorldView();
		if (view == null) {
			return Collections.emptyList();
		}
		Scene scene = view.getScene();
		int plane = view.getPlane();
		Tile[][] tiles = scene.getTiles()[plane];
		WorldPoint centre = new WorldPoint(stop.getX(), stop.getY(), stop.getPlane());
		List<Found> out = new ArrayList<>();
		for (Tile[] column : tiles) {
			for (Tile tile : column) {
				if (tile == null) {
					continue;
				}
				for (GameObject object : tile.getGameObjects()) {
					if (object == null || object.getWorldLocation().distanceTo2D(centre) > STOP_RADIUS) {
						continue;
					}
					ObjectComposition comp = client.getObjectDefinition(object.getId());
					if (comp == null || comp.getImpostorIds() == null) {
						continue;
					}
					FarmRoute.Patch patch = byVarbit.get(comp.getVarbitId());
					if (patch != null && out.stream().noneMatch(f -> f.object == object)) {
						out.add(new Found(object, patch));
					}
				}
			}
		}
		return out;
	}

	/**
	 * The arrow goes to the first patch still to do, else to one still without compost (the stop's
	 * tile until it is in view); the map marker to the stop.
	 */
	private void updatePointers(FarmRoute.Stop stop) {
		WorldPoint target = null;
		GameObject house = houseObject;
		if (house != null) {
			// In your house: the arrow points at the portal (or nexus...) to take.
			target = house.getWorldLocation();
		}
		for (Found f : found) {
			if (target == null && manager.statusOf(f.patch).isOpen()) {
				target = f.object.getWorldLocation();
				break;
			}
		}
		if (target == null) {
			for (Found f : found) {
				if (target == null && manager.needsCompost(f.patch)) {
					target = f.object.getWorldLocation();
					break;
				}
			}
		}
		if (target == null) {
			target = new WorldPoint(stop.getX(), stop.getY(), stop.getPlane());
		}
		if (!target.equals(arrowAt)) {
			client.setHintArrow(target);
			arrowAt = target;
		}
		WorldPoint stopPoint = new WorldPoint(stop.getX(), stop.getY(), stop.getPlane());
		if (mapPoint == null || !stopPoint.equals(mapPoint.getWorldPoint())) {
			if (mapPoint != null) {
				worldMap.remove(mapPoint);
			}
			mapPoint = new WorldMapPoint(stopPoint, pin());
			mapPoint.setTooltip("Farm run: " + stop.getName());
			// Click to centre the world map on the stop ("Focus on <name>"). RuneLite
			// requires a name for clickable points (it asserts on it in developer mode).
			mapPoint.setName("Farm run: " + stop.getName());
			mapPoint.setTarget(stopPoint);
			mapPoint.setSnapToEdge(true);
			mapPoint.setJumpOnClick(true);
			worldMap.add(mapPoint);
		}
	}

	/** Removes the arrow (only if it is ours) and the map marker. Client thread. */
	void clearPointers() {
		if (arrowAt != null) {
			if (arrowAt.equals(client.getHintArrowPoint())) {
				client.clearHintArrow();
			}
			arrowAt = null;
		}
		if (mapPoint != null) {
			worldMap.remove(mapPoint);
			mapPoint = null;
		}
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		return Guard.render(GUARD_FEATURE, () -> {
			GameObject house = houseObject;
			if (house != null && manager.isRunning()) {
				outlines.drawOutline(house, 3, HOUSE_COLOR, 4);
				String label = "Use: " + houseObjectName;
				Point at = house.getCanvasTextLocation(graphics, label, 200);
				if (at != null) {
					OverlayUtil.renderTextLocation(graphics, at, label, HOUSE_COLOR);
				}
			}
			List<Found> list = found;
			if (list.isEmpty() || !manager.isRunning()) {
				return null;
			}
			for (Found f : list) {
				Status s = manager.statusOf(f.patch);
				// Still without compost (the compost is highlighted in the inventory too): a patch to do says so
				// in its label, a planted one turns orange.
				boolean compost = manager.needsCompost(f.patch);
				Color color = compost && s != Status.TODO ? FarmRunOverlay.COMPOST_COLOR : s == Status.DONE ? FarmRunOverlay.DONE_COLOR
					: s == Status.TODO ? FarmRunOverlay.TODO_COLOR : FarmRunOverlay.MUTED_COLOR;
				outlines.drawOutline(f.object, 2, color, 4);
				Polygon tile = f.object.getCanvasTilePoly();
				if (tile != null && (s == Status.TODO || compost)) {
					graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
					graphics.fill(tile);
				}
				String label = f.patch.getType();
				if (manager.capeBlocks(f.patch)) {
					// Farming cape for herbs on Force: no picking (left click walks) until the cape is on.
					label = "WEAR Farming cape";
					color = FarmRunOverlay.COMPOST_COLOR;
				} else if (s == Status.TODO) {
					label = manager.nextStep(f.patch);
				} else if (compost) {
					label += " - compost it";
				} else {
					label += s == Status.DONE ? " - done" : " - not ready";
				}
				Point at = f.object.getCanvasTextLocation(graphics, label, 160);
				if (at != null) {
					OverlayUtil.renderTextLocation(graphics, at, label, color);
				}
			}
			return null;
		});
	}

	private BufferedImage pinImage;

	/** The stop's mark on the world map: a watering can (the item picture, a bit bigger than a map icon; it fills in once loaded). */
	private BufferedImage pin() {
		if (pinImage == null) {
			pinImage = itemManager.getImage(ItemID.WATERING_CAN_8);
		}
		return pinImage;
	}
}
