package com.osrscompanion;

import com.osrscompanion.FarmPatchProgress.Status;
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
 * The farm run step box (like Quest Helper's): which stop you are on, how to get there,
 * and what to do at each patch, ticking off as you plant; it points out a patch that has
 * no compost yet and a minigame teleport that is still on its cooldown.
 */
public class FarmRunOverlay extends OverlayPanel {
	static final Color TITLE_COLOR = new Color(0xF2, 0x8C, 0x28);
	static final Color MUTED_COLOR = new Color(0xA5, 0xA5, 0xA5);
	static final Color DONE_COLOR = new Color(0x0D, 0xC1, 0x0D);
	static final Color TODO_COLOR = new Color(0xFF, 0xE0, 0x4A);
	static final Color COMPOST_COLOR = new Color(0xF9, 0x73, 0x16);
	private static final int WIDTH = 220;
	private static final int ICON_GAP = 6;
	private static final BufferedImage SPACER = new BufferedImage(1, 4, BufferedImage.TYPE_INT_ARGB);

	private final OSRSCompanionConfig config;
	private final FarmRunManager manager;
	private final ItemManager itemManager;
	private final Map<Integer, BufferedImage> icons = new HashMap<>();

	@Inject
	public FarmRunOverlay(OSRSCompanionConfig config, FarmRunManager manager, ItemManager itemManager) {
		this.config = config;
		this.manager = manager;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (!config.showFarmRun() || !manager.isRunning()) {
			return null;
		}
		FarmRoute route = manager.getRoute();
		FarmRoute.Stop stop = manager.getCurrentStop();
		if (route == null || stop == null) {
			return null;
		}
		FontMetrics fm = graphics.getFontMetrics();
		int textWidth = WIDTH - 14;
		panelComponent.getChildren().clear();

		int[] progress = manager.progress();
		String title = manager.isComplete() ? "Farm run complete!"
			: "Farm run - stop " + (manager.getStopIndex() + 1) + "/" + route.getStops().size();
		panelComponent.getChildren().add(LineComponent.builder().left(title).leftColor(manager.isComplete() ? DONE_COLOR : TITLE_COLOR)
			.right(progress[0] + "/" + progress[1] + " done").rightColor(MUTED_COLOR).build());

		if (manager.isComplete()) {
			for (String line : wrap("Every patch is planted, skipped or still growing. The run stops when you leave this stop.", fm, textWidth)) {
				panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(MUTED_COLOR).build());
			}
			for (FarmRoute.Patch p : stop.getPatches()) {
				if (manager.needsCompost(p)) {
					for (String line : wrap(p.getType() + ": no compost yet: use your " + compostName() + " on the patch.", fm, textWidth)) {
						panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(COMPOST_COLOR).build());
					}
				}
			}
			panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
			return super.render(graphics);
		}

		for (String line : wrap(stop.getName(), fm, textWidth)) {
			panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(Color.WHITE).build());
		}
		FarmRoute.Travel travel = stop.getTravel();
		String via = (travel.isWalk() ? "" : travel.isSpell() ? "Cast " : "Use ") + travel.getName() + (travel.isSpell() ? " (normal spellbook)" : "");
		Integer travelItem = travel.getItems().isEmpty() ? null : travel.getItems().get(0);
		addIconLine(travelItem, via, Color.CYAN, fm, textWidth);
		for (String line : wrap(travel.getHow(), fm, textWidth)) {
			panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(MUTED_COLOR).build());
		}
		int cooldown = manager.minigameCooldownMinutes();
		if (cooldown > 0) {
			String wait = "Minigame teleports are on cooldown for " + cooldown + " more min" + (cooldown == 1 ? "" : "s") + ": use another way there.";
			for (String line : wrap(wait, fm, textWidth)) {
				panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(Color.YELLOW).build());
			}
		}
		panelComponent.getChildren().add(new ImageComponent(SPACER));

		if (manager.needsFarmingCape()) {
			for (String line : wrap("Put on your Farming cape before picking the herbs.", fm, textWidth)) {
				panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(Color.YELLOW).build());
			}
		}
		String compost = compostName();
		int behind = manager.compostBehind();
		if (behind > 0) {
			String step = behind + (behind == 1 ? " patch" : " patches") + " at the last stop still " + (behind == 1 ? "has" : "have") + " no compost: use your " + compost + " on " + (behind == 1 ? "it." : "them.");
			for (String line : wrap(step, fm, textWidth)) {
				panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(COMPOST_COLOR).build());
			}
		}
		for (FarmRoute.Patch p : stop.getPatches()) {
			Status s = manager.statusOf(p);
			String label = p.getType() + (p.getCrop() != null ? ": " + p.getCrop() : "");
			Color color = s == Status.DONE ? DONE_COLOR : s == Status.TODO ? TODO_COLOR : MUTED_COLOR;
			String state = s == Status.DONE ? "done" : s == Status.NOT_READY ? "not ready" : s == Status.SKIPPED ? "skipped" : "";
			panelComponent.getChildren().add(LineComponent.builder().left(label).leftColor(color).right(state).rightColor(color).build());
			if (s.isOpen()) {
				for (String step : stepsFor(p)) {
					for (String line : wrap(step, fm, textWidth - 8)) {
						panelComponent.getChildren().add(LineComponent.builder().left("  " + line).leftColor(MUTED_COLOR).build());
					}
				}
			}
			if (manager.needsCompost(p)) {
				String step = (s == Status.DONE ? "No compost yet: use your " : "Compost it: use your ") + compost + " on the patch.";
				for (String line : wrap(step, fm, textWidth - 8)) {
					panelComponent.getChildren().add(LineComponent.builder().left("  " + line).leftColor(COMPOST_COLOR).build());
				}
			}
		}
		if (stop.getNote() != null) {
			for (String line : wrap(stop.getNote(), fm, textWidth)) {
				panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(TITLE_COLOR).build());
			}
		}
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
		return super.render(graphics);
	}

	/** What to do at an open patch, in order. */
	static java.util.List<String> stepsFor(FarmRoute.Patch p) {
		java.util.List<String> steps = new java.util.ArrayList<>();
		String impl = p.getImpl() == null ? "" : p.getImpl();
		boolean tree = impl.contains("TREE") || impl.equals("REDWOOD") || impl.equals("CALQUAT") || impl.equals("CELASTRUS");
		if (impl.contains("COMPOST")) {
			steps.add("Take out the compost, then fill and close the bin.");
			return steps;
		}
		steps.add(tree ? "Check health; pay the gardener 200 coins to clear it." : "Harvest, then rake and compost.");
		if (p.getSeed() != null && p.getSeed() > 0) {
			steps.add("Plant " + (p.getSeedQty() > 1 ? p.getSeedQty() + " x " : "") + (p.getSeedName() == null ? p.getCrop() : p.getSeedName()) + ".");
		}
		List<FarmRoute.Stack> pay = p.getProtect();
		if (!pay.isEmpty()) {
			StringBuilder sb = new StringBuilder("Pay ");
			for (int i = 0; i < pay.size(); i++) {
				sb.append(i == 0 ? "" : ", ").append(pay.get(i).getQty()).append(" x ").append(pay.get(i).getName());
			}
			steps.add(sb.append(" to protect it.").toString());
		}
		return steps;
	}

	/** The compost the route brings ("Ultracompost", "Bottomless compost bucket"), or plain "compost". */
	private String compostName() {
		FarmRoute.Stack s = manager.compostStack();
		return s == null || s.getName() == null || "Item".equals(s.getName()) ? "compost" : s.getName();
	}

	private void addIconLine(Integer itemId, String text, Color color, FontMetrics fm, int width) {
		BufferedImage icon = itemId == null ? null : icons.computeIfAbsent(itemId, itemManager::getImage);
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

	static java.util.List<String> wrap(String text, FontMetrics fm, int width) {
		return text == null || text.isEmpty() ? java.util.Collections.emptyList() : GameUi.wrap(text, fm, width);
	}
}
