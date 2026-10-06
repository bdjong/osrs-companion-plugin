package com.osrscompanion;

import com.osrscompanion.model.BossGear;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;

/**
 * The active boss setup in the side panel (Boss page, "Gear display" on): the worn gear in the
 * game's layout with the special attack weapon beside it, the 28 inventory slots and the rune
 * pouch, each slot coloured like the boss gear window (carried, in the bank, missing).
 */
class BossGearPanelView extends JComponent {
	private static final int SLOT = 36;
	private static final int CELL = 40;
	private static final int GRID = 4 * CELL - 4;
	private static final Color CARD = new Color(0x1A1F2B);
	private static final Color EDGE = new Color(0x313A4E);
	private static final Color HEADING = new Color(0x8C96A8);
	private static final Color SLOT_FILL = new Color(0x11151E);
	private static final Color SLOT_EDGE = new Color(0x2A3242);
	private static final Color GREEN = new Color(0x0DC10D);
	private static final Color STORED = new Color(0xFF981F);
	private static final Color RED = new Color(0xFF3D3D);
	private static final Color BEIGE = new Color(0xC9B98F);
	private static final Color YELLOW = new Color(0xFFFF00);
	private static final Color GREY = new Color(0x9A9080);

	private final BossGearManager manager;
	private final ItemManager itemManager;
	/** Each drawn slot's area and tooltip, from the last paint. */
	private volatile List<Tip> tips = Collections.emptyList();

	private static final class Tip {
		final Rectangle area;
		final String text;

		Tip(Rectangle area, String text) {
			this.area = area;
			this.text = text;
		}
	}

	BossGearPanelView(BossGearManager manager, ItemManager itemManager) {
		this.manager = manager;
		this.itemManager = itemManager;
		setMinimumSize(new Dimension(120, 54));
		setToolTipText("");
		fitHeight(54);
	}

	@Override
	public String getToolTipText(MouseEvent e) {
		for (Tip t : tips) {
			if (t.area.contains(e.getPoint())) {
				return t.text;
			}
		}
		return null;
	}

	/** The card's height: two lines of text without a set, else the three grids (no pouch: 60 less). */
	private int wantedHeight() {
		BossGear set = manager.getSet();
		if (set == null || !manager.isSetActive()) {
			return 54;
		}
		return set.getPouch().isEmpty() ? 578 : 638;
	}

	private void fitHeight(int h) {
		setPreferredSize(new Dimension(200, h));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		int wanted = wantedHeight();
		if (getPreferredSize().height != wanted) {
			// The set loaded, changed or went Inactive: take the new height on the next layout.
			fitHeight(wanted);
			SwingUtilities.invokeLater(() -> {
				revalidate();
				repaint();
			});
		}
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			RoundRectangle2D card = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, 14, 14);
			g.setColor(CARD);
			g.fill(card);
			g.setColor(EDGE);
			g.draw(card);
			BossGear set = manager.getSet();
			List<Tip> newTips = new ArrayList<>();
			if (set == null || !manager.isSetActive() || h < 500) {
				String[] lines = set == null
					? new String[]{manager.isLoading() ? "Loading..." : "No setup picked yet", "Load one in Gear overview"}
					: !manager.isSetActive() ? new String[]{"Gear hidden while Inactive", "Set the setup Active above to show it"}
					: new String[]{"Loading...", ""};
				g.setFont(FontManager.getRunescapeSmallFont());
				shadow(g, "ACTIVE GEAR", 12, 16, HEADING);
				g.setFont(FontManager.getRunescapeFont());
				shadow(g, fit(lines[0], g.getFontMetrics(), w - 24), 12, 32, Color.WHITE);
				g.setFont(FontManager.getRunescapeSmallFont());
				shadow(g, fit(lines[1], g.getFontMetrics(), w - 24), 12, 45, new Color(0xC9D1DE));
				tips = newTips;
				return;
			}

			int x0 = (w - GRID) / 2;
			g.setFont(FontManager.getRunescapeSmallFont());
			shadow(g, "ACTIVE GEAR", 12, 16, HEADING);
			int[] c = manager.carriedCount(set);
			String count = c[0] + " / " + c[1] + " carried";
			shadow(g, count, w - 12 - g.getFontMetrics().stringWidth(count), 16, c[0] == c[1] ? GREEN : new Color(0xC9D1DE));

			// Worn: the game's 3-column doll, the special attack weapon in the 4th column on the weapon row.
			g.setFont(FontManager.getRunescapeBoldFont());
			shadow(g, "Worn", x0, 36, Color.WHITE);
			for (int r = 0; r < BossGearWindow.EQUIPMENT.length; r++) {
				for (int col = 0; col < 3; col++) {
					String name = BossGearWindow.EQUIPMENT[r][col];
					if (name != null) {
						slot(g, new Rectangle(x0 + col * CELL, 44 + r * CELL, SLOT, SLOT), set.slot(name), 0, set.isSynced(), newTips);
					}
				}
			}
			BossGear.Slot special = set.slot("special");
			if (special != null) {
				g.setFont(FontManager.getRunescapeSmallFont());
				int sx = x0 + 3 * CELL;
				shadow(g, "Spec", sx + (SLOT - g.getFontMetrics().stringWidth("Spec")) / 2, 118, GREY);
				slot(g, new Rectangle(sx, 124, SLOT, SLOT), special, 0, set.isSynced(), newTips);
			}

			// Inventory: 4 x 7.
			g.setFont(FontManager.getRunescapeBoldFont());
			shadow(g, "Inventory", x0, 260, Color.WHITE);
			List<BossGear.Item> inv = set.getInventory();
			Map<Integer, Integer> needs = manager.inventoryNeeds();
			for (int i = 0; i < 28; i++) {
				BossGear.Item it = i < inv.size() ? inv.get(i) : null;
				slot(g, new Rectangle(x0 + (i % 4) * CELL, 268 + (i / 4) * CELL, SLOT, SLOT), it,
					it == null ? 0 : needs.getOrDefault(it.getItem(), it.getQty()), set.isSynced(), newTips);
			}
			if (inv.isEmpty()) {
				g.setFont(FontManager.getRunescapeSmallFont());
				shadow(g, fit("The wiki has no inventory for this setup.", g.getFontMetrics(), GRID), x0, 268 + 3 * CELL + 22, new Color(0xAEB6C4));
			}
			int y = 268 + 7 * CELL - 4;

			// Rune pouch: one row.
			if (!set.getPouch().isEmpty()) {
				g.setFont(FontManager.getRunescapeBoldFont());
				shadow(g, "Rune pouch", x0, 564, Color.WHITE);
				int px = x0;
				for (BossGear.Item it : set.getPouch()) {
					if (px + SLOT > x0 + GRID + 1) {
						break;
					}
					slot(g, new Rectangle(px, 572, SLOT, SLOT), it, needs.getOrDefault(it.getItem(), it.getQty()), set.isSynced(), newTips);
					px += CELL;
				}
				y = 572 + SLOT;
			}

			// Legend.
			g.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics fm = g.getFontMetrics();
			int ly = y + 24;
			int lx = x0;
			shadow(g, "Carried", lx, ly, GREEN);
			lx += fm.stringWidth("Carried") + 10;
			String stored = set.isSynced() ? "In bank (B)" : "Not synced";
			shadow(g, stored, lx, ly, set.isSynced() ? STORED : BEIGE);
			lx += fm.stringWidth(stored) + 10;
			shadow(g, "Missing", lx, ly, RED);
			tips = newTips;
		} finally {
			g.dispose();
		}
	}

	/** One slot: the item at its own size and a border saying carried (green), in the bank (orange, B) or missing (red). */
	private void slot(Graphics2D g, Rectangle r, BossGear.Item it, int need, boolean synced, List<Tip> newTips) {
		g.setColor(SLOT_FILL);
		g.fillRect(r.x, r.y, r.width, r.height);
		if (it == null) {
			g.setColor(SLOT_EDGE);
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
			return;
		}
		BufferedImage icon = itemManager.getImage(it.getItem(), it.getQty(), it.getQty() > 1);
		if (icon != null && icon.getWidth() > 0) {
			g.drawImage(icon, r.x + (r.width - icon.getWidth()) / 2, r.y + (r.height - icon.getHeight()) / 2, null);
		}
		int carried = manager.carried(it);
		Color edge;
		boolean inBank = false;
		if (carried >= Math.max(1, need)) {
			edge = GREEN;
		} else if (it.isOwned()) {
			edge = STORED;
			inBank = true;
		} else {
			edge = synced ? RED : BEIGE;
		}
		g.setColor(edge);
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		g.setFont(FontManager.getRunescapeSmallFont());
		if (inBank) {
			shadow(g, "B", r.x + r.width - 8, r.y + r.height - 3, STORED);
		}
		if (it.isNoted()) {
			shadow(g, "N", r.x + 2, r.y + r.height - 3, YELLOW);
		}
		newTips.add(new Tip(r, manager.slotTooltip(it, need, synced)));
	}

	private static void shadow(Graphics2D g, String text, int x, int y, Color color) {
		g.setColor(new Color(0, 0, 0, 170));
		g.drawString(text, x + 1, y + 1);
		g.setColor(color);
		g.drawString(text, x, y);
	}

	private static String fit(String s, FontMetrics fm, int width) {
		if (fm.stringWidth(s) <= width) {
			return s;
		}
		String t = s;
		while (t.length() > 1 && fm.stringWidth(t + "...") > width) {
			t = t.substring(0, t.length() - 1);
		}
		return t + "...";
	}
}
