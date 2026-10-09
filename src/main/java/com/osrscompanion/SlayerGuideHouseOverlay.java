package com.osrscompanion;

import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.model.SlayerGuide;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * In your house on the Slayer guide's way to the location, as the farm run does: the object the way
 * there uses (the jewellery box for "then the jewellery box (Amulet of glory)", a portal, the Portal
 * Nexus, the fairy ring) outlined with "Use: ..." and the hint arrow on it. A running farm run goes
 * first (its own scene overlay does the same for its stop). Client thread for the scan.
 */
public class SlayerGuideHouseOverlay extends Overlay {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "slayer-guide-house";

	private static final int SCAN_EVERY_TICKS = 2;

	private final Client client;
	private final SlayerHelperManager manager;
	private final FarmRunManager farmRun;
	private final ModelOutlineRenderer outlines;

	private volatile GameObject houseObject;
	private volatile String houseObjectName;
	private WorldPoint arrowAt;
	private int ticks;

	@Inject
	public SlayerGuideHouseOverlay(Client client, SlayerHelperManager manager, FarmRunManager farmRun, ModelOutlineRenderer outlines) {
		this.client = client;
		this.manager = manager;
		this.farmRun = farmRun;
		this.outlines = outlines;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		Guard.run(GUARD_FEATURE, () -> {
			SlayerGuide.Location l = manager.getLocation();
			FarmRoute.Travel travel = l == null ? null : l.getTravel();
			if (!manager.isGuiding() || travel == null || farmRun.isRunning() || client.getGameState() != GameState.LOGGED_IN
				|| FarmRunSceneOverlay.houseObjectNames(travel).isEmpty()) {
				clear();
				return;
			}
			if (++ticks % SCAN_EVERY_TICKS == 0 || houseObject == null) {
				GameObject object = FarmRunSceneOverlay.findHouseObject(client, travel);
				houseObject = object;
				houseObjectName = object == null ? null : FarmRunSceneOverlay.objectName(client, object);
			}
			GameObject object = houseObject;
			if (object == null) {
				clearArrow();
				return;
			}
			WorldPoint target = object.getWorldLocation();
			if (!target.equals(arrowAt)) {
				client.setHintArrow(target);
				arrowAt = target;
			}
		});
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		return Guard.render(GUARD_FEATURE, () -> {
			GameObject object = houseObject;
			if (object == null || !manager.isGuiding() || farmRun.isRunning()) {
				return null;
			}
			outlines.drawOutline(object, 3, FarmRunSceneOverlay.HOUSE_COLOR, 4);
			String label = "Use: " + houseObjectName;
			Point at = object.getCanvasTextLocation(graphics, label, 200);
			if (at != null) {
				OverlayUtil.renderTextLocation(graphics, at, label, FarmRunSceneOverlay.HOUSE_COLOR);
			}
			return null;
		});
	}

	private void clearArrow() {
		if (arrowAt != null) {
			if (arrowAt.equals(client.getHintArrowPoint())) {
				client.clearHintArrow();
			}
			arrowAt = null;
		}
	}

	/** Forgets the object and removes the arrow (only if it is ours). Client thread. */
	void clear() {
		houseObject = null;
		houseObjectName = null;
		clearArrow();
	}
}
