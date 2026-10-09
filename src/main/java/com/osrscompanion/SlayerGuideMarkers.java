package com.osrscompanion;

import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.model.SlayerGuide;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SpriteID;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

/**
 * Points the way to the slayer location while the guide is on and you are not there yet: the
 * yellow hint arrow on the location's tile once it is in the loaded scene, and the Slayer skill
 * icon on the world map (hover: the location and the teleport; click: the map centres on it; the
 * way there is in the guide's box in the game). A running farm run
 * keeps the arrow. Client thread.
 */
@Singleton
public class SlayerGuideMarkers {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "slayer-guide-markers";

	static final Color PIN_COLOR = new Color(0xF87171);

	private final Client client;
	private final SlayerHelperManager manager;
	private final FarmRunManager farmRun;
	private final WorldMapPointManager worldMap;
	private final SpriteManager spriteManager;

	private WorldMapPoint mapPoint;
	private String mapPointFor;
	private WorldPoint arrowAt;
	private BufferedImage icon;

	@Inject
	public SlayerGuideMarkers(Client client, SlayerHelperManager manager, FarmRunManager farmRun, WorldMapPointManager worldMap,
		SpriteManager spriteManager) {
		this.client = client;
		this.manager = manager;
		this.farmRun = farmRun;
		this.worldMap = worldMap;
		this.spriteManager = spriteManager;
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		Guard.run(GUARD_FEATURE, () -> {
			SlayerGuide.Location l = manager.getLocation();
			if (!manager.isGuiding() || l == null || !l.hasTile() || client.getGameState() != GameState.LOGGED_IN) {
				clear();
				return;
			}
			WorldPoint target = new WorldPoint(l.getX(), l.getY(), l.getPlane());
			updateArrow(target);
			updateMapPoint(l, target);
		});
	}

	/** The hint arrow on the location's tile while it is in the loaded scene (a farm run's arrow goes first). */
	private void updateArrow(WorldPoint target) {
		WorldView view = client.getTopLevelWorldView();
		boolean inScene = view != null && view.getPlane() == target.getPlane() && LocalPoint.fromWorld(view, target) != null;
		if (!inScene || farmRun.isRunning()) {
			clearArrow();
			return;
		}
		if (!target.equals(arrowAt)) {
			client.setHintArrow(target);
			arrowAt = target;
		}
	}

	private void updateMapPoint(SlayerGuide.Location l, WorldPoint target) {
		String key = SlayerHelperManager.mapPointName(l) + "@" + target;
		if (mapPoint != null && key.equals(mapPointFor)) {
			return;
		}
		BufferedImage img = pin();
		if (img == null) {
			// The skill icon is not loaded yet: next tick.
			return;
		}
		clearMapPoint();
		mapPoint = new WorldMapPoint(target, img);
		FarmRoute.Travel travel = l.getTravel();
		mapPoint.setTooltip(SlayerHelperManager.mapPointName(l) + (travel == null ? "" : " - " + travel.getName()));
		// Click: RuneLite centres the map on it ("Focus on <name>"; clickable points need a name) and the
		// manager sees that click to show the way there.
		mapPoint.setName(SlayerHelperManager.mapPointName(l));
		mapPoint.setTarget(target);
		mapPoint.setSnapToEdge(true);
		mapPoint.setJumpOnClick(true);
		worldMap.add(mapPoint);
		mapPointFor = key;
	}

	/** The Slayer skill icon on a dark round badge with a red ring, so it stands out on the map. */
	private BufferedImage pin() {
		if (icon != null) {
			return icon;
		}
		BufferedImage skill = spriteManager.getSprite(SpriteID.SKILL_SLAYER, 0);
		if (skill == null) {
			return null;
		}
		int size = 24;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setColor(new Color(0x1A, 0x10, 0x10, 230));
		g.fillOval(1, 1, size - 2, size - 2);
		g.setColor(PIN_COLOR);
		g.setStroke(new BasicStroke(1.6f));
		g.drawOval(1, 1, size - 3, size - 3);
		int box = 16;
		float scale = Math.min(box / (float) skill.getWidth(), box / (float) skill.getHeight());
		int w = Math.round(skill.getWidth() * scale);
		int h = Math.round(skill.getHeight() * scale);
		g.drawImage(skill, (size - w) / 2, (size - h) / 2, w, h, null);
		g.dispose();
		icon = img;
		return icon;
	}

	private void clearArrow() {
		if (arrowAt != null) {
			if (arrowAt.equals(client.getHintArrowPoint())) {
				client.clearHintArrow();
			}
			arrowAt = null;
		}
	}

	private void clearMapPoint() {
		if (mapPoint != null) {
			worldMap.remove(mapPoint);
			mapPoint = null;
			mapPointFor = null;
		}
	}

	/** Removes the arrow (only if it is ours) and the map icon. Client thread. */
	void clear() {
		clearArrow();
		clearMapPoint();
	}
}
