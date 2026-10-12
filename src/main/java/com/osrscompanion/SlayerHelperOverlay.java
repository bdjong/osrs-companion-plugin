package com.osrscompanion;

import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.model.SlayerGuide;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.*;
import javax.inject.Inject;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;

/**
 * The Slayer guide's box, like the farm run's: on the way, the task and location, which way to go
 * (an arrow and the distance), the teleport and the steps; once you are there, a compact stats box
 * (kills left, time at the location, average kill time, estimated time left).
 */
public class SlayerHelperOverlay extends OverlayPanel {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "slayer-helper-overlay";

	private static final int WIDTH = 220;
	private static final int ICON_GAP = 6;
	private static final BufferedImage SPACER = new BufferedImage(1, 4, BufferedImage.TYPE_INT_ARGB);
	private static final Map<String, BufferedImage> ARROWS = new HashMap<>();

	private final SlayerHelperManager manager;
	private final ItemManager itemManager;
	private final Map<Integer, BufferedImage> icons = new HashMap<>();

	@Inject
	public SlayerHelperOverlay(SlayerHelperManager manager, ItemManager itemManager) {
		this.manager = manager;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		return Guard.render(GUARD_FEATURE, () -> {
			SlayerHelperManager.Task task = manager.getTask();
			if (task == null) {
				return null;
			}
			if (manager.isAtLocation()) {
				return renderStats(graphics, task);
			}
			if (!manager.isGuiding() && !(manager.isGuideOn() && manager.getLocation() == null)) {
				return null;
			}
			SlayerGuide.Location l = manager.getLocation();
			if (l == null) {
				// A new task: say the location is on its way (or that none was found) instead of showing nothing.
				boolean fetching = manager.isFetchingForTask();
				if (!fetching && manager.getGuide() == null) {
					return null;
				}
				panelComponent.getChildren().clear();
				panelComponent.getChildren().add(LineComponent.builder().left("Slayer: " + task.displayName()).leftColor(FarmRunOverlay.TITLE_COLOR)
					.right(task.remaining + " left").rightColor(FarmRunOverlay.MUTED_COLOR).build());
				String text = fetching ? "Retrieving location and gear information for the new task..." : "No location data was found for this task.";
				for (String line : FarmRunOverlay.wrap(text, graphics.getFontMetrics(), WIDTH - 14)) {
					panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(FarmRunOverlay.MUTED_COLOR).build());
				}
				panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
				return super.render(graphics);
			}
			FontMetrics fm = graphics.getFontMetrics();
			int textWidth = WIDTH - 14;
			panelComponent.getChildren().clear();
			panelComponent.getChildren().add(LineComponent.builder().left("Slayer: " + task.displayName()).leftColor(FarmRunOverlay.TITLE_COLOR)
				.right(task.remaining + " left").rightColor(FarmRunOverlay.MUTED_COLOR).build());
			for (String line : FarmRunOverlay.wrap(l.getName() + (l.isMulti() ? " (multi)" : ""), fm, textWidth)) {
				panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(Color.WHITE).build());
			}
			String dir = manager.getDirection();
			int dist = manager.getDistance();
			if (dir != null && dist > 0) {
				addIconLine(arrow(dir), "Go " + dir + ", " + dist + (dist == 1 ? " tile" : " tiles"), FarmRunOverlay.TODO_COLOR);
			}
			FarmRoute.Travel travel = l.getTravel();
			if (travel != null) {
				String via = (travel.isWalk() ? "" : travel.isSpell() ? "Cast " : "Use ") + travel.getName();
				Integer item = travel.getItems().isEmpty() ? null : travel.getItems().get(0);
				addIconLine(item == null ? null : icons.computeIfAbsent(item, itemManager::getImage), via, Color.CYAN);
				for (String line : FarmRunOverlay.wrap(travel.getHow(), fm, textWidth)) {
					panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(FarmRunOverlay.MUTED_COLOR).build());
				}
			}
			List<String> steps = l.getSteps();
			if (!steps.isEmpty()) {
				panelComponent.getChildren().add(new ImageComponent(SPACER));
			}
			for (int i = 0; i < steps.size(); i++) {
				List<String> lines = FarmRunOverlay.wrap((i + 1) + ". " + steps.get(i), fm, textWidth);
				for (int j = 0; j < lines.size(); j++) {
					panelComponent.getChildren().add(LineComponent.builder().left(j == 0 ? lines.get(j) : "   " + lines.get(j))
						.leftColor(FarmRunOverlay.TODO_COLOR).build());
				}
			}
			panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
			return super.render(graphics);
		});
	}

	/**
	 * At the location: task, kills left, time there, the fight time (first hit to kill), the time per kill
	 * (time in the area per kill: walking, looting and AFK included), kills/h and the estimated time left.
	 */
	private Dimension renderStats(Graphics2D graphics, SlayerHelperManager.Task task) {
		SlayerHelperManager.Stats s = manager.getStats();
		long now = System.currentTimeMillis();
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(LineComponent.builder().left("Slayer: " + task.displayName()).leftColor(FarmRunOverlay.TITLE_COLOR).build());
		addRow("Left", task.remaining + (task.original > 0 ? " / " + task.original : ""));
		if (s != null) {
			addRow("Time here", SlayerFormat.clock(s.timeInArea(now)));
			long here = s.timeInArea(now);
			addRow("TTK", s.avgKillMs > 0 ? SlayerFormat.seconds(s.avgKillMs) : "-");
			addRow("Time per kill", s.kills > 0 && here > 0 ? SlayerFormat.seconds(here / s.kills) : "-");
			addRow("Kills/h", s.kills > 0 && here > 0 ? String.valueOf(Math.round(s.kills * 3_600_000.0 / here)) : "-");
			addRow("Est. left", s.estimatedLeftMs > 0 ? SlayerFormat.clock(s.estimatedLeftMs) : "-");
		}
		panelComponent.setPreferredSize(new Dimension(150, 0));
		return super.render(graphics);
	}

	private void addRow(String label, String value) {
		panelComponent.getChildren().add(LineComponent.builder().left(label).leftColor(FarmRunOverlay.MUTED_COLOR)
			.right(value).rightColor(Color.WHITE).build());
	}

	private void addIconLine(BufferedImage icon, String text, Color color) {
		LineComponent line = LineComponent.builder().left(text).leftColor(color).build();
		if (icon == null) {
			panelComponent.getChildren().add(line);
			return;
		}
		panelComponent.getChildren().add(SplitComponent.builder()
			.first(new ImageComponent(icon))
			.second(line)
			.orientation(ComponentOrientation.HORIZONTAL)
			.gap(new Point(ICON_GAP, 0))
			.build());
	}

	/** A small yellow arrow pointing the compass way ("north-east"), drawn once per direction. */
	static BufferedImage arrow(String direction) {
		return ARROWS.computeIfAbsent(direction, d -> {
			String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
			int index = 0;
			for (int i = 0; i < names.length; i++) {
				if (names[i].equals(d)) {
					index = i;
				}
			}
			BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			// Drawn pointing up (north), turned clockwise 45 degrees per step.
			g.setTransform(AffineTransform.getRotateInstance(Math.toRadians(45 * index), 7, 7));
			Polygon head = new Polygon(new int[]{7, 13, 9, 9, 5, 5, 1}, new int[]{0, 7, 7, 13, 13, 7, 7}, 7);
			g.setColor(FarmRunOverlay.TODO_COLOR);
			g.fill(head);
			g.setColor(Color.BLACK);
			g.setStroke(new BasicStroke(1f));
			g.draw(head);
			g.dispose();
			return img;
		});
	}
}
