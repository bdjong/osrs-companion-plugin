package com.osrscompanion;

import com.osrscompanion.model.GearPlan;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
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
 * Game-window box for the active gear roadmap, styled like {@link NextGoalOverlay}
 * and shown next to it: the roadmap name, the items of the next open step (items
 * on the same level are listed together) and the overall progress bar.
 *
 * <p>Gated by {@link OSRSCompanionConfig#showGearProgress()}. Reads the plan cached
 * by {@link GearProgressionManager}, which it asks to refresh every couple of
 * minutes; no network work runs on the render thread.
 */
public class GearNextOverlay extends OverlayPanel {
	private static final Color TITLE_COLOR = new Color(0xF2, 0x8C, 0x28); // OSRS Companion orange
	private static final Color MUTED_COLOR = new Color(0xA5, 0xA5, 0xA5);
	private static final Color DONE_COLOR = new Color(0x0D, 0xC1, 0x0D);
	private static final int WIDTH_PADDING = 14;
	private static final int MIN_WIDTH = 120;
	private static final int MAX_TEXT_WIDTH = 200;
	private static final int ICON_GAP = 6;
	/** At most this many same-level items are listed; the rest are summarised. */
	private static final int MAX_ITEMS = 3;
	private static final BufferedImage SPACER = new BufferedImage(1, 4, BufferedImage.TYPE_INT_ARGB);

	private final OSRSCompanionConfig config;
	private final GearProgressionManager manager;
	private final ItemManager itemManager;
	private final GoalImageCache imageCache;
	private final Map<Integer, BufferedImage> itemIcons = new HashMap<>();

	@Inject
	public GearNextOverlay(OSRSCompanionConfig config, GearProgressionManager manager, ItemManager itemManager, GoalImageCache imageCache) {
		this.config = config;
		this.manager = manager;
		this.itemManager = itemManager;
		this.imageCache = imageCache;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (!config.showGearProgress()) {
			return null;
		}
		manager.refreshForOverlay();
		GearPlan plan = manager.getPlan();
		if (plan == null || plan.getTotal() <= 0) {
			return null;
		}

		FontMetrics metrics = graphics.getFontMetrics();
		panelComponent.getChildren().clear();
		int widest = 0;
		// Title: "[roadmap] - Next gear" on one line when it fits; otherwise the roadmap
		// name (wrapped) with "Next gear" kept together on its own line.
		String oneLine = plan.getTitle() + " - Next gear";
		List<String> titleLines = new ArrayList<>();
		if (metrics.stringWidth(oneLine) <= MAX_TEXT_WIDTH) {
			titleLines.add(oneLine);
		} else {
			titleLines.addAll(GearProgressionOverlay.wrap(plan.getTitle(), metrics, MAX_TEXT_WIDTH));
			titleLines.add("Next gear");
		}
		for (String line : titleLines) {
			panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(TITLE_COLOR).build());
			widest = Math.max(widest, metrics.stringWidth(line));
		}
		panelComponent.getChildren().add(new ImageComponent(SPACER));

		Next next = findNext(plan);
		if (next == null) {
			String done = "Every step is done!";
			panelComponent.getChildren().add(LineComponent.builder().left(done).leftColor(DONE_COLOR).build());
			widest = Math.max(widest, metrics.stringWidth(done));
		} else {
			int shown = Math.min(MAX_ITEMS, next.items.size());
			for (int i = 0; i < shown; i++) {
				GearPlan.Item item = next.items.get(i);
				String name = truncate(item.getName(), metrics, MAX_TEXT_WIDTH - 32 - ICON_GAP);
				BufferedImage icon = iconFor(item);
				LineComponent line = LineComponent.builder().left(name).build();
				if (icon != null) {
					panelComponent.getChildren().add(SplitComponent.builder()
							.first(new ImageComponent(icon))
							.second(line)
							.orientation(ComponentOrientation.HORIZONTAL)
							.gap(new Point(ICON_GAP, 0))
							.build());
					widest = Math.max(widest, icon.getWidth() + ICON_GAP + metrics.stringWidth(name));
				} else {
					panelComponent.getChildren().add(line);
					widest = Math.max(widest, metrics.stringWidth(name));
				}
			}
			if (next.items.size() > shown) {
				String more = "+" + (next.items.size() - shown) + " more on this step";
				panelComponent.getChildren().add(LineComponent.builder().left(more).leftColor(MUTED_COLOR).build());
				widest = Math.max(widest, metrics.stringWidth(more));
			}
			String where = "Tier " + next.tier + " - step " + next.step + (next.items.size() > 1 ? " - any order" : "");
			panelComponent.getChildren().add(LineComponent.builder().left(where).leftColor(MUTED_COLOR).build());
			widest = Math.max(widest, metrics.stringWidth(where));
		}

		if (config.showGoalProgress()) {
			int percent = (int) Math.min(100, (long) plan.getDone() * 100L / plan.getTotal());
			GoalProgressBarComponent bar = new GoalProgressBarComponent(
					percent / 100d, plan.getDone() + " / " + plan.getTotal() + " steps", percent + "%");
			panelComponent.getChildren().add(bar);
			widest = Math.max(widest, bar.minWidth(metrics));
		}

		panelComponent.setPreferredSize(new Dimension(Math.max(MIN_WIDTH, widest + WIDTH_PADDING), 0));
		return super.render(graphics);
	}

	/** The next open step: its tier and step number as the window shows them, and its open items. */
	static Next findNext(GearPlan plan) {
		for (GearPlan.Tier tier : plan.getTiers()) {
			List<GearPlan.Step> steps = tier.getVisibleSteps();
			for (int i = 0; i < steps.size(); i++) {
				List<GearPlan.Item> open = new ArrayList<>();
				for (GearPlan.Item item : steps.get(i).getVisibleItems()) {
					if (item.isNext()) {
						open.add(item);
					}
				}
				if (!open.isEmpty()) {
					return new Next(tier.getTier(), i + 1, open);
				}
			}
		}
		return null;
	}

	static final class Next {
		final int tier;
		final int step;
		final List<GearPlan.Item> items;

		Next(int tier, int step, List<GearPlan.Item> items) {
			this.tier = tier;
			this.step = step;
			this.items = items;
		}
	}

	/** Package-private so a preview harness can supply images without a game client. */
	BufferedImage iconFor(GearPlan.Item item) {
		Integer itemId = item.getItemId();
		if (itemId != null && itemId > 0) {
			return itemIcons.computeIfAbsent(itemId, itemManager::getImage);
		}
		return imageCache.get(item.getImageUrl());
	}

	private static String truncate(String s, FontMetrics fm, int maxWidth) {
		if (fm.stringWidth(s) <= maxWidth) {
			return s;
		}
		int end = s.length();
		while (end > 0 && fm.stringWidth(s.substring(0, end) + "...") > maxWidth) {
			end--;
		}
		return s.substring(0, end) + "...";
	}
}
