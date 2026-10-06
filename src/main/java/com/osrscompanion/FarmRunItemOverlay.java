package com.osrscompanion;

import com.osrscompanion.model.FarmRoute;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Highlights items during a farm run, like Quest Helper: the teleport to the current
 * stop (cyan), seeds for its open patches (green) and protection payments (yellow);
 * in the bank also every loadout item you are not carrying enough of (white).
 */
public class FarmRunItemOverlay extends WidgetItemOverlay {
	private static final Color TELEPORT = new Color(0x22, 0xD3, 0xEE);
	private static final Color SEED = new Color(0x22, 0xC5, 0x5E);
	private static final Color PAYMENT = new Color(0xFA, 0xCC, 0x15);
	private static final Color BRING = new Color(255, 255, 255, 200);

	private final OSRSCompanionConfig config;
	private final FarmRunManager manager;

	@Inject
	public FarmRunItemOverlay(OSRSCompanionConfig config, FarmRunManager manager) {
		this.config = config;
		this.manager = manager;
		showOnInventory();
		showOnEquipment();
		showOnBank();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem) {
		if (!config.showFarmRun() || !manager.isRunning() || manager.isComplete()) {
			return;
		}
		Map<Integer, FarmRunManager.Highlight> now = manager.currentItems();
		FarmRunManager.Highlight h = now.get(itemId);
		Color color = h == FarmRunManager.Highlight.TELEPORT ? TELEPORT : h == FarmRunManager.Highlight.SEED ? SEED : h == FarmRunManager.Highlight.PAYMENT ? PAYMENT : null;
		if (color == null && isBankItem(widgetItem)) {
			// Variants count: the filled and the empty compost bucket, glory charges, graceful colours.
			FarmRoute.Stack s = manager.neededStack(itemId);
			if (s != null && manager.carried(s.getItem()) < s.getQty()) {
				color = BRING;
			}
		}
		if (color == null) {
			return;
		}
		Rectangle r = widgetItem.getCanvasBounds();
		graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 45));
		graphics.fill(r);
		graphics.setColor(color);
		graphics.setStroke(new BasicStroke(1.5f));
		graphics.draw(new Rectangle(r.x, r.y, r.width - 1, r.height - 1));
	}

	/** Bank items sit in the bank interface (group 12). */
	private static boolean isBankItem(WidgetItem item) {
		return item.getWidget() != null && (item.getWidget().getId() >>> 16) == 12;
	}
}
