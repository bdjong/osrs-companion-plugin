package com.osrscompanion;

import com.osrscompanion.DailyChecks.State;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * The side panel's list of today's dailies, in the tool cards' style: each row with the reward's
 * picture, the name, what it gives or why it is locked, its value and a tick. Click a row to tick
 * it off by hand (for dailies the game does not track, or when it missed one), click again to undo.
 */
class DailiesListView extends JComponent {
	private static final int ARC = 14;
	private static final int ROW = 34;
	private static final int PAD = 6;
	private static final Color OPEN = new Color(0xF4, 0x72, 0xB6);
	private static final Color DONE = new Color(0x4A, 0xDE, 0x80);
	private static final Color LOCKED = new Color(0x6B, 0x73, 0x85);

	private final DailiesManager manager;
	private final ItemManager itemManager;
	private final Map<Integer, BufferedImage> icons = new HashMap<>();
	private List<DailiesManager.Entry> shown = java.util.Collections.emptyList();
	private int hoverRow = -1;

	DailiesListView(DailiesManager manager, ItemManager itemManager) {
		this.manager = manager;
		this.itemManager = itemManager;
		// Registers with the tooltip manager; the text comes from getToolTipText(MouseEvent).
		setToolTipText("");
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				Guard.run(DailiesManager.GUARD_FEATURE, () -> {
					int row = rowAt(e.getY());
					if (row != hoverRow) {
						hoverRow = row;
						DailiesManager.Entry en = row >= 0 && row < shown.size() ? shown.get(row) : null;
						setCursor(Cursor.getPredefinedCursor(en != null && en.state != State.LOCKED ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
						repaint();
					}
				});
			}

			@Override
			public void mouseExited(MouseEvent e) {
				Guard.run(DailiesManager.GUARD_FEATURE, () -> {
					hoverRow = -1;
					repaint();
				});
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				Guard.run(DailiesManager.GUARD_FEATURE, () -> {
					int row = rowAt(e.getY());
					if (row >= 0 && row < shown.size()) {
						manager.toggleTick(shown.get(row).id);
						repaint();
					}
				});
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	private int rowAt(int y) {
		return y < PAD ? -1 : (y - PAD) / ROW;
	}

	private int heightFor(int rows) {
		return PAD * 2 + Math.max(1, rows) * ROW;
	}

	@Override
	public Dimension getPreferredSize() {
		return new Dimension(200, heightFor(manager.entries().size()));
	}

	@Override
	public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	@Override
	public String getToolTipText(MouseEvent e) {
		int row = rowAt(e.getY());
		if (row < 0 || row >= shown.size()) {
			return null;
		}
		DailiesManager.Entry en = shown.get(row);
		if (en.state == State.LOCKED) {
			return en.note == null ? "Locked" : en.note;
		}
		String reward = en.guide == null ? en.name : en.guide.getName() + ": " + en.guide.getReward();
		if (en.ticked) {
			return reward + " (ticked by hand: click to undo)";
		}
		return reward + (en.state == State.DONE ? " (done)" : en.tracked ? " (ticks off by itself; click if it missed it)" : " (click when done)");
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		List<DailiesManager.Entry> list = manager.entries();
		shown = list;
		if (getHeight() != heightFor(list.size())) {
			// The list grew or shrank (the guide loaded): lay the page out again.
			SwingUtilities.invokeLater(() -> {
				revalidate();
				repaint();
			});
		}
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			int w = getWidth();
			int h = getHeight();
			RoundRectangle2D card = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, ARC, ARC);
			g.setColor(new Color(0x1A1F2B));
			g.fill(card);
			g.setColor(new Color(0x313A4E));
			g.draw(card);
			DailiesManager.Entry current = manager.current();
			for (int i = 0; i < list.size(); i++) {
				paintRow(g, list.get(i), PAD + i * ROW, w, i == hoverRow, current != null && current.id.equals(list.get(i).id));
			}
		} finally {
			g.dispose();
		}
	}

	private void paintRow(Graphics2D g, DailiesManager.Entry e, int y, int w, boolean hover, boolean current) {
		Color tone = e.state == State.DONE ? DONE : e.state == State.LOCKED ? LOCKED : OPEN;
		if (hover && e.state != State.LOCKED) {
			g.setColor(new Color(255, 255, 255, 14));
			g.fillRoundRect(4, y, w - 8, ROW - 2, 10, 10);
		}
		if (current) {
			g.setColor(OPEN);
			g.fillRoundRect(4, y + 6, 3, ROW - 14, 3, 3);
		}
		// The reward's picture (dimmed when locked).
		int icon = e.guide == null ? 0 : e.guide.getIcon();
		BufferedImage img = icon <= 0 ? null : icons.computeIfAbsent(icon, itemManager::getImage);
		if (img != null && img.getWidth() > 0) {
			int box = 24;
			float scale = Math.min(box / (float) img.getWidth(), box / (float) img.getHeight());
			int iw = Math.round(img.getWidth() * scale);
			int ih = Math.round(img.getHeight() * scale);
			java.awt.Composite old = g.getComposite();
			if (e.state == State.LOCKED) {
				g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.35f));
			}
			g.drawImage(img, 12 + (box - iw) / 2, y + (ROW - 2 - ih) / 2, iw, ih, null);
			g.setComposite(old);
		}
		// The tick on the right.
		int tx = w - 26;
		int ty = y + (ROW - 2 - 16) / 2;
		if (e.state == State.DONE) {
			g.setColor(DONE);
			g.fillOval(tx, ty, 16, 16);
			g.setColor(new Color(0x05, 0x2E, 0x14));
			g.setStroke(new java.awt.BasicStroke(2f));
			g.drawLine(tx + 4, ty + 8, tx + 7, ty + 11);
			g.drawLine(tx + 7, ty + 11, tx + 12, ty + 5);
			g.setStroke(new java.awt.BasicStroke(1f));
		} else if (e.state == State.OPEN) {
			g.setColor(hover ? OPEN : new Color(0x4A, 0x56, 0x70));
			g.setStroke(new java.awt.BasicStroke(1.5f));
			g.drawOval(tx, ty, 16, 16);
			g.setStroke(new java.awt.BasicStroke(1f));
		}
		// Name, then what it gives or why it is locked, and the value on the right.
		int textX = 44;
		String value = e.state == State.OPEN && e.guide != null && e.guide.getValue() != null && e.guide.getValue() > 0
			? QuantityFormatter.quantityToStackSize(e.guide.getValue()) : null;
		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics small = g.getFontMetrics();
		int valueW = value == null ? 0 : small.stringWidth(value) + 6;
		int textW = tx - textX - 6;
		g.setFont(FontManager.getRunescapeFont());
		shadow(g, fit(e.name, g.getFontMetrics(), textW - valueW), textX, y + 14, e.state == State.LOCKED ? LOCKED : Color.WHITE);
		if (value != null) {
			g.setFont(FontManager.getRunescapeSmallFont());
			shadow(g, value, tx - 6 - small.stringWidth(value), y + 14, new Color(0xFA, 0xCC, 0x15));
		}
		g.setFont(FontManager.getRunescapeSmallFont());
		String sub = e.state == State.LOCKED ? e.note
			: e.state == State.DONE ? (e.ticked ? "Ticked by hand" : "Done today")
			: e.note != null ? e.note : e.amount > 0 ? e.amount + " today" : e.tracked ? "Open" : "Open: tick when done";
		shadow(g, fit(sub, small, textW), textX, y + 27, e.state == State.OPEN ? tone : new Color(0x8C, 0x96, 0xA8));
	}

	private static void shadow(Graphics2D g, String text, int x, int y, Color color) {
		g.setColor(new Color(0, 0, 0, 170));
		g.drawString(text, x + 1, y + 1);
		g.setColor(color);
		g.drawString(text, x, y);
	}

	private static String fit(String s, FontMetrics fm, int width) {
		if (s == null) {
			return "";
		}
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
