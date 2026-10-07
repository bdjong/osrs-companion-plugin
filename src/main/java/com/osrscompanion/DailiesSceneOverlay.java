package com.osrscompanion;

import com.osrscompanion.model.Dailies;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
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
 * Points the way during a dailies run, like the farm run: the hint arrow on the daily's NPC once
 * it is in view (its tile until then), the NPC outlined with the option to pick, and a world map
 * mark with the reward's picture. Leaves the arrow to the farm run while one is going.
 */
public class DailiesSceneOverlay extends Overlay {
	/** An NPC of the right name further than this from the daily's tile is someone else. */
	static final int NPC_RADIUS = 20;
	private static final Color NPC_COLOR = DailiesOverlay.TITLE_COLOR;

	private final Client client;
	private final DailiesManager manager;
	private final FarmRunManager farmRun;
	private final OSRSCompanionConfig config;
	private final ModelOutlineRenderer outlines;
	private final WorldMapPointManager worldMap;
	private final ItemManager itemManager;

	private volatile NPC npc;
	private volatile String option;
	private NPC arrowNpc;
	private WorldPoint arrowPoint;
	private WorldMapPoint mapPoint;
	private String mapFor;

	@Inject
	public DailiesSceneOverlay(Client client, DailiesManager manager, FarmRunManager farmRun, OSRSCompanionConfig config,
		ModelOutlineRenderer outlines, WorldMapPointManager worldMap, ItemManager itemManager) {
		this.client = client;
		this.manager = manager;
		this.farmRun = farmRun;
		this.config = config;
		this.outlines = outlines;
		this.worldMap = worldMap;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		DailiesManager.Entry e = config.showDailiesGuide() && !farmRun.isRunning() ? manager.current() : null;
		Dailies.Daily g = e == null ? null : e.guide;
		if (g == null || client.getGameState() != GameState.LOGGED_IN || (g.getX() == 0 && g.getY() == 0)) {
			npc = null;
			clearPointers();
			return;
		}
		WorldPoint tile = new WorldPoint(g.getX(), g.getY(), g.getPlane());
		NPC found = findNpc(g.getNpc(), tile);
		npc = found;
		option = g.getOption();
		if (found != null) {
			if (found != arrowNpc) {
				client.setHintArrow(found);
				arrowNpc = found;
				arrowPoint = null;
			}
		} else if (!tile.equals(arrowPoint)) {
			client.setHintArrow(tile);
			arrowPoint = tile;
			arrowNpc = null;
		}
		if (mapPoint == null || !g.getId().equals(mapFor)) {
			if (mapPoint != null) {
				worldMap.remove(mapPoint);
			}
			mapPoint = new WorldMapPoint(tile, pin(g.getIcon()));
			mapPoint.setTooltip("Daily: " + g.getName());
			// Click to centre the map on it; RuneLite wants a name for clickable points.
			mapPoint.setName("Daily: " + g.getName());
			mapPoint.setTarget(tile);
			mapPoint.setSnapToEdge(true);
			mapPoint.setJumpOnClick(true);
			worldMap.add(mapPoint);
			mapFor = g.getId();
		}
	}

	/** The daily's NPC in the loaded scene, near its tile. Client thread. */
	private NPC findNpc(String name, WorldPoint tile) {
		WorldView view = client.getTopLevelWorldView();
		if (name == null || view == null) {
			return null;
		}
		for (NPC n : view.npcs()) {
			if (n != null && name.equalsIgnoreCase(n.getName()) && n.getWorldLocation() != null
				&& n.getWorldLocation().distanceTo2D(tile) <= NPC_RADIUS) {
				return n;
			}
		}
		return null;
	}

	/** Removes the arrow (only if it is still ours) and the map mark. Client thread. */
	void clearPointers() {
		if (arrowNpc != null && arrowNpc == client.getHintArrowNpc()) {
			client.clearHintArrow();
		} else if (arrowPoint != null && arrowPoint.equals(client.getHintArrowPoint())) {
			client.clearHintArrow();
		}
		arrowNpc = null;
		arrowPoint = null;
		if (mapPoint != null) {
			worldMap.remove(mapPoint);
			mapPoint = null;
			mapFor = null;
		}
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		NPC n = npc;
		if (n == null || !manager.isRunning()) {
			return null;
		}
		outlines.drawOutline(n, 3, NPC_COLOR, 4);
		String label = option == null || option.isEmpty() ? n.getName() : option;
		Point at = n.getCanvasTextLocation(graphics, label, n.getLogicalHeight() + 40);
		if (at != null) {
			OverlayUtil.renderTextLocation(graphics, at, label, NPC_COLOR);
		}
		return null;
	}

	/** The reward's picture as the map mark (it fills in once loaded). */
	private BufferedImage pin(int itemId) {
		return itemManager.getImage(itemId > 0 ? itemId : net.runelite.api.gameval.ItemID.BATTLESTAFF);
	}
}
