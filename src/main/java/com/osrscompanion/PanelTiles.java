package com.osrscompanion;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.JComponent;
import net.runelite.client.ui.FontManager;

/** Side-panel pieces in the tool tiles' style: an on/off tile and an info card. */
final class PanelTiles {
	private static final int ARC = 14;
	private static final Color FROM = new Color(0x161B26);
	private static final Color TO = new Color(0x222A3B);

	private PanelTiles() {
	}

	/** A rounded tile with a title, a line saying what the setting does, and a switch. */
	static final class Toggle extends JComponent {
		private final String title;
		private final String onText;
		private final String offText;
		private final Color accent;
		private final BooleanSupplier state;
		private final Runnable flip;
		private boolean hover;

		Toggle(String title, String onText, String offText, Color accent, BooleanSupplier state, Runnable flip) {
			this.title = title;
			this.onText = onText;
			this.offText = offText;
			this.accent = accent;
			this.state = state;
			this.flip = flip;
			setPreferredSize(new Dimension(200, 46));
			setMinimumSize(new Dimension(120, 46));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseEntered(MouseEvent e) {
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e) {
					hover = false;
					repaint();
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					if (contains(e.getPoint())) {
						flip.run();
						repaint();
					}
				}
			});
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				smooth(g);
				int w = getWidth();
				int h = getHeight();
				boolean on = state.getAsBoolean();
				RoundRectangle2D card = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, ARC, ARC);
				g.setPaint(new GradientPaint(0, 0, FROM, w, 0, on ? mix(TO, accent, 0.35f) : TO));
				g.fill(card);
				g.setStroke(new BasicStroke(hover ? 1.6f : 1f));
				g.setColor(alpha(on ? accent : new Color(0x4A5670), hover ? 230 : 150));
				g.draw(card);

				// The switch: a pill with a knob, the accent colour when on.
				int sw = 36;
				int sh = 18;
				int sx = w - sw - 12;
				int sy = (h - sh) / 2;
				g.setColor(on ? accent : new Color(0x3A4355));
				g.fillRoundRect(sx, sy, sw, sh, sh, sh);
				g.setColor(Color.WHITE);
				int knob = sh - 4;
				g.fillOval(on ? sx + sw - knob - 2 : sx + 2, sy + 2, knob, knob);

				g.setFont(FontManager.getRunescapeBoldFont());
				FontMetrics fm = g.getFontMetrics();
				shadow(g, fit(title, fm, sx - 20), 12, 19, Color.WHITE);
				g.setFont(FontManager.getRunescapeSmallFont());
				shadow(g, fit(on ? onText : offText, g.getFontMetrics(), sx - 20), 12, 35, on ? brighten(accent) : new Color(0xAEB6C4));
			} finally {
				g.dispose();
			}
		}
	}

	/** A quiet card with a heading and one or two lines of live text (current roadmap, route, setup). */
	static final class Info extends JComponent {
		private final String heading;
		private final Supplier<String[]> lines;

		Info(String heading, Supplier<String[]> lines) {
			this.heading = heading;
			this.lines = lines;
			setPreferredSize(new Dimension(200, 54));
			setMinimumSize(new Dimension(120, 54));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				smooth(g);
				int w = getWidth();
				int h = getHeight();
				RoundRectangle2D card = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, ARC, ARC);
				g.setColor(new Color(0x1A1F2B));
				g.fill(card);
				g.setColor(new Color(0x313A4E));
				g.draw(card);
				g.setFont(FontManager.getRunescapeSmallFont());
				shadow(g, heading, 12, 16, new Color(0x8C96A8));
				String[] text = lines.get();
				g.setFont(FontManager.getRunescapeFont());
				FontMetrics fm = g.getFontMetrics();
				int y = 32;
				for (int i = 0; i < text.length && i < 2; i++) {
					g.setFont(i == 0 ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont());
					shadow(g, fit(text[i], g.getFontMetrics(), w - 24), 12, y, i == 0 ? Color.WHITE : new Color(0xC9D1DE));
					y += i == 0 ? 15 : 13;
				}
			} finally {
				g.dispose();
			}
		}
	}

	private static void smooth(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
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

	private static Color alpha(Color c, int a) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}

	private static Color mix(Color a, Color b, float t) {
		return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	private static Color brighten(Color c) {
		return mix(c, Color.WHITE, 0.35f);
	}
}
