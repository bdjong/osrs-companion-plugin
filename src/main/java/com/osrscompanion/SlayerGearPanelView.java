package com.osrscompanion;

import com.osrscompanion.model.SlayerGear;
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
import java.util.Locale;
import javax.swing.JComponent;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;

/**
 * The Slayer helper page's gear: the website's Gear &amp; DPS calculator's best owned setups against
 * the task's monster, one at a time in the game's equipment layout (carried green, in the bank
 * orange), with what else it counts on (potion, prayer, style or spell, darts). It shows the setup
 * picked in the Slayer gear window (the best until one is picked there).
 */
class SlayerGearPanelView extends JComponent {
	private static final int SLOT = 36;
	private static final int CELL = 40;
	private static final int DOLL_TOP = 50;
	private static final Color CARD = new Color(0x1A1F2B);
	private static final Color EDGE = new Color(0x313A4E);
	private static final Color HEADING = new Color(0x8C96A8);
	private static final Color TEXT = new Color(0xC9D1DE);
	private static final Color SLOT_FILL = new Color(0x11151E);
	private static final Color SLOT_EDGE = new Color(0x2A3242);
	private static final Color GREEN = new Color(0x0DC10D);
	private static final Color STORED = new Color(0xFF981F);
	private static final Color ACCENT = new Color(0xF87171);

	private final SlayerHelperManager manager;
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

	SlayerGearPanelView(SlayerHelperManager manager, ItemManager itemManager) {
		this.manager = manager;
		this.itemManager = itemManager;
		setMinimumSize(new Dimension(120, 54));
		setToolTipText("");
	}

	/** Two lines of text without setups, else the doll and a line per extra. */
	private int wantedHeight() {
		SlayerGear.Set set = manager.getGearSet();
		return set == null ? 54 : DOLL_TOP + 5 * CELL + 8 + set.getExtras().size() * 13 + 10;
	}

	@Override
	public Dimension getPreferredSize() {
		return new Dimension(200, wantedHeight());
	}

	@Override
	public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, wantedHeight());
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

	@Override
	protected void paintComponent(Graphics graphics) {
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
			g.setFont(FontManager.getRunescapeSmallFont());
			shadow(g, "SLAYER GEAR", 12, 16, HEADING);

			SlayerGear gear = manager.getGear();
			SlayerGear.Set set = manager.getGearSet();
			List<Tip> newTips = new ArrayList<>();
			if (set == null) {
				g.setFont(FontManager.getRunescapeFont());
				shadow(g, fit(gear == null ? "Loading..." : "No setups for this task", g.getFontMetrics(), w - 24), 12, 32, Color.WHITE);
				g.setFont(FontManager.getRunescapeSmallFont());
				shadow(g, fit(gear == null ? "" : "Sync your bank and skills", g.getFontMetrics(), w - 24), 12, 45, TEXT);
				tips = newTips;
				return;
			}

			// How many setups there are to pick from in the window (top right).
			int count = gear.getSets().size();
			if (count > 1) {
				String of = (manager.getGearIndex() + 1) + " of " + count;
				shadow(g, of, w - 12 - g.getFontMetrics().stringWidth(of), 16, HEADING);
			}

			// Which setup, best first, and its numbers.
			g.setFont(FontManager.getRunescapeBoldFont());
			String title = set.getLabel() + (manager.getGearIndex() == 0 ? " (best)" : "");
			shadow(g, fit(title, g.getFontMetrics(), w - 24), 12, 32, ACCENT);
			g.setFont(FontManager.getRunescapeSmallFont());
			String numbers = "Max " + set.getMaxHit() + " · " + String.format(Locale.ROOT, "%.2f", set.getDps()) + " DPS · "
				+ Math.round(set.getKillsPerHour()) + " kills/h";
			shadow(g, fit(numbers, g.getFontMetrics(), w - 24), 12, 45, TEXT);

			// The worn gear in the game's 3-column doll.
			int x0 = (w - (3 * CELL - 4)) / 2;
			for (int r = 0; r < BossGearWindow.EQUIPMENT.length; r++) {
				for (int c = 0; c < 3; c++) {
					String slot = BossGearWindow.EQUIPMENT[r][c];
					if (slot != null) {
						slot(g, new Rectangle(x0 + c * CELL, DOLL_TOP + r * CELL, SLOT, SLOT), set.slot(slot), newTips);
					}
				}
			}

			// What else the numbers count on.
			int y = DOLL_TOP + 5 * CELL + 14;
			for (SlayerGear.Extra x : set.getExtras()) {
				shadow(g, x.getLabel() + ":", 12, y, HEADING);
				int lx = 12 + g.getFontMetrics().stringWidth(x.getLabel() + ": ");
				shadow(g, fit(x.getName(), g.getFontMetrics(), w - 12 - lx), lx, y, TEXT);
				y += 13;
			}
			tips = newTips;
		} finally {
			g.dispose();
		}
	}

	/** One slot: the item at its own size, green when carried (worn or in the inventory), else orange (in your storage). */
	private void slot(Graphics2D g, Rectangle r, SlayerGear.Piece piece, List<Tip> newTips) {
		g.setColor(SLOT_FILL);
		g.fillRect(r.x, r.y, r.width, r.height);
		if (piece == null) {
			g.setColor(SLOT_EDGE);
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
			return;
		}
		BufferedImage icon = itemManager.getImage(piece.getItem());
		if (icon != null && icon.getWidth() > 0) {
			g.drawImage(icon, r.x + (r.width - icon.getWidth()) / 2, r.y + (r.height - icon.getHeight()) / 2, null);
		}
		boolean carried = manager.carried(piece.getItem()) > 0;
		g.setColor(carried ? GREEN : STORED);
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		if (!carried) {
			g.setFont(FontManager.getRunescapeSmallFont());
			shadow(g, "B", r.x + r.width - 8, r.y + r.height - 3, STORED);
		}
		newTips.add(new Tip(r, piece.getName() + (carried ? " - carried" : " - in your bank or storage")));
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
