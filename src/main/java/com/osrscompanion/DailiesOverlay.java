package com.osrscompanion;

import com.osrscompanion.DailyChecks.State;
import com.osrscompanion.model.Dailies;
import com.osrscompanion.model.FarmRoute;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;

/**
 * The dailies step box (like the farm run's): the daily you are on, the teleport to use from your
 * unlocks, where to go, who to talk to and the option, what to bring, and how it gets ticked off.
 */
public class DailiesOverlay extends OverlayPanel {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "dailies-overlay";

	static final Color TITLE_COLOR = new Color(0xF4, 0x72, 0xB6);
	private static final int WIDTH = 220;
	private static final int ICON_GAP = 6;
	private static final BufferedImage SPACER = new BufferedImage(1, 4, BufferedImage.TYPE_INT_ARGB);

	private final OSRSCompanionConfig config;
	private final DailiesManager manager;
	private final ItemManager itemManager;
	private final Map<Integer, BufferedImage> icons = new HashMap<>();

	@Inject
	public DailiesOverlay(OSRSCompanionConfig config, DailiesManager manager, ItemManager itemManager) {
		this.config = config;
		this.manager = manager;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		return Guard.render(GUARD_FEATURE, () -> {
			DailiesManager.Entry e = config.showDailiesGuide() ? manager.current() : null;
			if (e == null) {
				return null;
			}
			FontMetrics fm = graphics.getFontMetrics();
			int textWidth = WIDTH - 14;
			panelComponent.getChildren().clear();

			List<DailiesManager.Entry> all = manager.entries();
			int open = 0;
			int done = 0;
			for (DailiesManager.Entry x : all) {
				open += x.state == State.OPEN ? 1 : 0;
				done += x.state == State.DONE ? 1 : 0;
			}
			panelComponent.getChildren().add(LineComponent.builder().left("Dailies").leftColor(TITLE_COLOR)
				.right(done + "/" + (done + open) + " done").rightColor(FarmRunOverlay.MUTED_COLOR).build());
			Dailies.Daily g = e.guide;
			addIconLine(g == null ? null : g.getIcon(), e.name + (e.amount > 0 ? " (" + e.amount + ")" : ""), Color.WHITE, fm, textWidth);
			if (g == null) {
				line(manager.isLoading() ? "Loading the guide..." : manager.getError() != null ? manager.getError() : "Open the side panel to load the guide.",
					FarmRunOverlay.MUTED_COLOR, fm, textWidth);
				panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
				return super.render(graphics);
			}

			FarmRoute.Travel travel = g.getTravel();
			if (travel != null) {
				String via = (travel.isWalk() ? "" : travel.isSpell() ? "Cast " : "Use ") + travel.getName() + (travel.isSpell() ? " (normal spellbook)" : "");
				addIconLine(travel.getItems().isEmpty() ? null : travel.getItems().get(0), via, Color.CYAN, fm, textWidth);
				line(travel.getHow(), FarmRunOverlay.MUTED_COLOR, fm, textWidth);
			}
			panelComponent.getChildren().add(new ImageComponent(SPACER));
			line(g.getArea(), Color.WHITE, fm, textWidth);
			if (g.getNpc() != null) {
				line(g.getNpc() + ": " + g.getOption(), FarmRunOverlay.TODO_COLOR, fm, textWidth);
			} else if (!g.getOption().isEmpty()) {
				line(g.getOption(), FarmRunOverlay.TODO_COLOR, fm, textWidth);
			}
			for (String step : g.getSteps()) {
				line(step, FarmRunOverlay.MUTED_COLOR, fm, textWidth);
			}
			for (Dailies.Bring b : g.getBring()) {
				addIconLine(b.getItem(), "Bring: " + b.getText(), FarmRunOverlay.COMPOST_COLOR, fm, textWidth);
			}
			if (e.note != null) {
				line(e.note, Color.YELLOW, fm, textWidth);
			}
			panelComponent.getChildren().add(new ImageComponent(SPACER));
			line(e.tracked ? "Ticks off by itself once you have it." : "Tick it off in the side panel when done.", FarmRunOverlay.DONE_COLOR, fm, textWidth);
			panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
			return super.render(graphics);
		});
	}

	private void line(String text, Color color, FontMetrics fm, int width) {
		for (String l : FarmRunOverlay.wrap(text, fm, width)) {
			panelComponent.getChildren().add(LineComponent.builder().left(l).leftColor(color).build());
		}
	}

	/** A line with the item's picture in front (long text wraps under it without one). */
	private void addIconLine(Integer itemId, String text, Color color, FontMetrics fm, int width) {
		BufferedImage icon = itemId == null || itemId <= 0 ? null : icons.computeIfAbsent(itemId, itemManager::getImage);
		if (icon == null) {
			line(text, color, fm, width);
			return;
		}
		List<String> lines = FarmRunOverlay.wrap(text, fm, width - icon.getWidth() - ICON_GAP);
		panelComponent.getChildren().add(SplitComponent.builder()
			.first(new ImageComponent(icon))
			.second(LineComponent.builder().left(lines.isEmpty() ? "" : lines.get(0)).leftColor(color).build())
			.orientation(ComponentOrientation.HORIZONTAL)
			.gap(new Point(ICON_GAP, 0))
			.build());
		for (int i = 1; i < lines.size(); i++) {
			panelComponent.getChildren().add(LineComponent.builder().left(lines.get(i)).leftColor(color).build());
		}
	}
}
