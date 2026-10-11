package com.osrscompanion;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.ui.FontManager;

/**
 * Palette and drawing helpers for the in-game windows (gear progression, farm run),
 * styled like the game's own interfaces.
 */
final class GameUi {
	private GameUi() {
	}

	// Game interface palette.
	static final Color BG = new Color(0x3E3529);
	static final Color FRAME_DARK = new Color(0x0E0C08);
	static final Color FRAME_LIGHT = new Color(0x5D5240);
	static final Color PANEL = new Color(0x2E2820);
	static final Color PANEL_EDGE = new Color(0x14110C);
	static final Color PANEL_INNER = new Color(0x4A4134);
	static final Color TAB_ON = new Color(0x5A4D3A);
	static final Color SLOT = new Color(0x2B251C);
	static final Color SLOT_EDGE = new Color(0x13100B);
	static final Color SLOT_INNER = new Color(0x3F3629);
	static final Color BUTTON_INNER = new Color(0x6A5C48);
	static final Color BAR_BG = new Color(0x5C0B0B);
	static final Color ORANGE = new Color(0xFF981F);
	static final Color YELLOW = new Color(0xFFFF00);
	static final Color GREEN = new Color(0x0DC10D);
	static final Color RED = new Color(0xFF3D3D);
	static final Color GREY = new Color(0x9A9080);
	static final Color BEIGE = new Color(0xC9B98F);


	static void panel(Graphics2D g, Rectangle r) {
		box(g, r, PANEL, PANEL_EDGE, PANEL_INNER);
	}

	static void box(Graphics2D g, Rectangle r, Color fill, Color edge, Color inner) {
		g.setColor(fill);
		g.fillRect(r.x, r.y, r.width, r.height);
		g.setColor(edge);
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		g.setColor(inner);
		g.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
	}

	static void button(Graphics2D g, Rectangle r, String label, boolean disabled) {
		box(g, r, BG, PANEL_EDGE, BUTTON_INNER);
		g.setFont(FontManager.getRunescapeSmallFont());
		centred(g, label, r.x + r.width / 2, r.y + 14, disabled ? GREY : ORANGE);
	}

	static void bar(Graphics2D g, int x, int y, int w, int h, int done, int total) {
		if (w <= 4) {
			return;
		}
		g.setColor(Color.BLACK);
		g.fillRect(x, y, w, h);
		g.setColor(BAR_BG);
		g.fillRect(x + 1, y + 1, w - 2, h - 2);
		int fill = total <= 0 ? 0 : (int) ((long) (w - 2) * done / total);
		g.setColor(GREEN);
		g.fillRect(x + 1, y + 1, fill, h - 2);
	}

	static void arrow(Graphics2D g, int x, int y, int length) {
		Stroke old = g.getStroke();
		for (int pass = 0; pass < 2; pass++) {
			g.setStroke(new BasicStroke(pass == 0 ? 4 : 2));
			g.setColor(pass == 0 ? Color.BLACK : ORANGE);
			g.drawLine(x, y, x + length, y);
			g.drawLine(x + length - 4, y - 4, x + length, y);
			g.drawLine(x + length - 4, y + 4, x + length, y);
		}
		g.setStroke(old);
	}

	static void tick(Graphics2D g, int x, int y) {
		Stroke old = g.getStroke();
		for (int pass = 0; pass < 2; pass++) {
			g.setStroke(new BasicStroke(pass == 0 ? 4 : 2));
			g.setColor(pass == 0 ? Color.BLACK : GREEN);
			g.drawLine(x + 1, y + 6, x + 4, y + 9);
			g.drawLine(x + 4, y + 9, x + 11, y + 1);
		}
		g.setStroke(old);
	}

	static void tooltip(Graphics2D g, String label, Point m, Rectangle win) {
		g.setFont(FontManager.getRunescapeFont());
		FontMetrics fm = g.getFontMetrics();
		int w = fm.stringWidth(label) + 10;
		int h = 20;
		int tx = Math.min(m.x + 12, win.x + win.width - w - 4);
		int ty = Math.max(win.y + 4, m.y - h - 4);
		g.setColor(new Color(0xFFFFA0));
		g.fillRect(tx, ty, w, h);
		g.setColor(Color.BLACK);
		g.drawRect(tx, ty, w - 1, h - 1);
		g.drawString(label, tx + 5, ty + 15);
	}

	/** Draws text with the game's 1px black shadow; returns the x after the text. */
	static int text(Graphics2D g, String s, int x, int y, Color color) {
		g.setColor(Color.BLACK);
		g.drawString(s, x + 1, y + 1);
		g.setColor(color);
		g.drawString(s, x, y);
		return x + g.getFontMetrics().stringWidth(s);
	}

	static void centred(Graphics2D g, String s, int centreX, int y, Color color) {
		text(g, s, centreX - g.getFontMetrics().stringWidth(s) / 2, y, color);
	}

	static String truncate(String s, FontMetrics fm, int maxWidth) {
		if (s == null || fm.stringWidth(s) <= maxWidth) {
			return s == null ? "" : s;
		}
		String dots = "...";
		int end = s.length();
		while (end > 0 && fm.stringWidth(s.substring(0, end) + dots) > maxWidth) {
			end--;
		}
		return s.substring(0, end) + dots;
	}

	static List<String> wrap(String s, FontMetrics fm, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) > maxWidth && line.length() > 0) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0) {
			lines.add(line.toString());
		}
		return lines;
	}
}
