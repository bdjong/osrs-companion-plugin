package com.osrscompanion;

import com.osrscompanion.model.SlayerGuide;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Slayer debug mode (developer's characters only, see {@link SlayerHelperManager#debugAllowed}): a box
 * with everything the plugin knows about where you are and what the website sent for the task, to map
 * task areas: your tile and region, the task as the game has it, the guide's location (tile, radius,
 * regions) and whether you count as inside it, and the open session.
 */
public class SlayerDebugOverlay extends OverlayPanel {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "slayer-debug-overlay";

	private static final Color KEY = new Color(0x9CA3AF);
	private static final Color YES = new Color(0x4ADE80);
	private static final Color NO = new Color(0xF87171);

	private final Client client;
	private final SlayerHelperManager manager;
	private final OSRSCompanionConfig config;

	@Inject
	public SlayerDebugOverlay(Client client, SlayerHelperManager manager, OSRSCompanionConfig config) {
		this.client = client;
		this.manager = manager;
		this.config = config;
		setPosition(OverlayPosition.TOP_RIGHT);
		panelComponent.setPreferredSize(new Dimension(260, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		return Guard.render(GUARD_FEATURE, () -> {
			Player me = client.getLocalPlayer();
			if (!config.slayerDebug() || me == null || !SlayerHelperManager.debugAllowed(me.getName())) {
				return null;
			}
			WorldPoint at = WorldPoint.fromLocalInstance(client, me.getLocalLocation());
			title("Slayer debug");
			line("Tile", at.getX() + ", " + at.getY() + ", " + at.getPlane());
			line("Region", String.valueOf(at.getRegionID()) + (client.isInInstancedRegion() ? " (instance)" : ""));

			SlayerHelperManager.Task t = manager.getTask();
			title("Task (game)");
			if (t == null) {
				line("Task", "none");
			} else {
				line("Name", t.displayName());
				line("Ids", "task " + t.taskId + " · boss " + t.bossId + " · area " + t.areaId + " · master " + t.masterId);
				line("Left", t.remaining + " / " + t.original);
			}

			SlayerGuide guide = manager.getGuide();
			title("Guide (website)");
			line("State", manager.isLoading() ? "loading" : manager.getError() != null ? manager.getError()
				: guide == null ? "not loaded" : manager.isGuideForTask() ? "loaded for this task" : "for ANOTHER task");
			if (guide != null && guide.getTask() != null) {
				SlayerGuide.Task gt = guide.getTask();
				line("For", gt.getTaskName() + " · area " + gt.getAreaId() + (gt.getAreaName() != null ? " " + gt.getAreaName() : ""));
				line("Locations", String.valueOf(guide.getLocations().size()) + (guide.getSelected() != null ? " · picked " + guide.getSelected() : ""));
			}
			location("Target", manager.getLocation(), at);
			location("Here", manager.getHere(), at);
			line("Guiding", yesNo(manager.isGuiding()) + " · at location " + yesNo(manager.isAtLocation()));
			if (manager.getDistance() >= 0) {
				line("Way", manager.getDistance() + " tiles " + (manager.getDirection() == null ? "" : manager.getDirection()));
			}

			SlayerHelperManager.Stats s = manager.getStats();
			title("Session");
			if (s == null) {
				line("Session", "none");
			} else {
				line("Location", s.locationName == null ? "-" : s.locationName);
				line("Inside", s.inside ? "yes" : "no", s.inside ? YES : NO);
				line("Kills", s.kills + " · " + SlayerFormat.clock(s.timeInAreaMs) + " in area");
			}
			return super.render(graphics);
		});
	}

	private void location(String label, SlayerGuide.Location l, WorldPoint at) {
		if (l == null) {
			line(label, "-");
			return;
		}
		line(label, l.getKey() + " (" + l.getName() + ")");
		if (l.hasTile()) {
			line("  tile", l.getX() + ", " + l.getY() + ", " + l.getPlane() + " · r " + l.getRadius());
		}
		List<Integer> regions = l.getRegionIds();
		if (!regions.isEmpty()) {
			line("  regions", regions.stream().map(String::valueOf).collect(Collectors.joining(", ")));
		}
		boolean in = l.contains(at.getX(), at.getY(), at.getPlane(), at.getRegionID());
		line("  you inside", in ? "yes" : "no", in ? YES : NO);
	}

	private static String yesNo(boolean b) {
		return b ? "yes" : "no";
	}

	private void title(String text) {
		panelComponent.getChildren().add(TitleComponent.builder().text(text).color(new Color(0xFBBF24)).build());
	}

	private void line(String left, String right) {
		line(left, right, Color.WHITE);
	}

	private void line(String left, String right, Color color) {
		panelComponent.getChildren().add(LineComponent.builder().left(left).leftColor(KEY).right(right).rightColor(color).build());
	}
}
