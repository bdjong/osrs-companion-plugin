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

/** Side-panel pieces in the tool tiles' style: an on/off tile, info cards and a stats card. */
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
		/** Replaces the on/off line while it says something (e.g. "Paused while Inactive"); null: none. */
		private Supplier<String> override = () -> null;
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
					Guard.run(RoadmapPanel.GUARD_FEATURE, () -> {
						hover = true;
						repaint();
					});
				}

				@Override
				public void mouseExited(MouseEvent e) {
					Guard.run(RoadmapPanel.GUARD_FEATURE, () -> {
						hover = false;
						repaint();
					});
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					Guard.run(RoadmapPanel.GUARD_FEATURE, () -> {
						if (contains(e.getPoint())) {
							flip.run();
							repaint();
						}
					});
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
				String why = override.get();
				shadow(g, fit(why != null ? why : on ? onText : offText, g.getFontMetrics(), sx - 20), 12, 35,
					on && why == null ? brighten(accent) : new Color(0xAEB6C4));
			} finally {
				g.dispose();
			}
		}

		/** Shows this line instead of the on/off text while it returns something. */
		Toggle withNote(Supplier<String> note) {
			override = note;
			return this;
		}
	}

	/**
	 * A rounded tile with a title, a row of chips of which one is picked (clicking another picks it), and
	 * a line saying what the picked one does: the boss page's gear mode.
	 */
	static final class Choice extends JComponent {
		private static final int CHIP_Y = 25;
		private static final int CHIP_H = 18;
		private final String title;
		private final String[] values;
		private final java.util.function.Function<String, String> label;
		private final java.util.function.Function<String, String> shortLabel;
		private final java.util.function.Function<String, String> describe;
		private final Color accent;
		private final Supplier<String> state;
		private final java.util.function.Consumer<String> pick;
		/** Replaces the description while it says something; null: none. */
		private Supplier<String> override = () -> null;
		private int hover = -1;

		Choice(String title, String[] values, java.util.function.Function<String, String> label, java.util.function.Function<String, String> shortLabel,
				java.util.function.Function<String, String> describe, Color accent, Supplier<String> state, java.util.function.Consumer<String> pick) {
			this.title = title;
			this.values = values.clone();
			this.label = label;
			this.shortLabel = shortLabel;
			this.describe = describe;
			this.accent = accent;
			this.state = state;
			this.pick = pick;
			setPreferredSize(new Dimension(200, 64));
			setMinimumSize(new Dimension(120, 64));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter mouse = new MouseAdapter() {
				@Override
				public void mouseMoved(MouseEvent e) {
					Guard.run(RoadmapPanel.GUARD_FEATURE, () -> {
						int at = chipAt(e.getX(), e.getY());
						if (at != hover) {
							hover = at;
							repaint();
						}
					});
				}

				@Override
				public void mouseExited(MouseEvent e) {
					Guard.run(RoadmapPanel.GUARD_FEATURE, () -> {
						hover = -1;
						repaint();
					});
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					Guard.run(RoadmapPanel.GUARD_FEATURE, () -> {
						int at = chipAt(e.getX(), e.getY());
						if (at >= 0) {
							pick.accept(Choice.this.values[at]);
							repaint();
						}
					});
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
		}

		private int chipWidth() {
			return (getWidth() - 24 - 2 * 4) / values.length;
		}

		private int chipAt(int x, int y) {
			if (y < CHIP_Y || y > CHIP_Y + CHIP_H || x < 12) {
				return -1;
			}
			int i = (x - 12) / (chipWidth() + 4);
			return i < values.length ? i : -1;
		}

		/** Each chip says what it does. */
		@Override
		public String getToolTipText(MouseEvent e) {
			int at = chipAt(e.getX(), e.getY());
			return at < 0 ? super.getToolTipText(e) : label.apply(values[at]) + ": " + describe.apply(values[at]);
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				smooth(g);
				int w = getWidth();
				int h = getHeight();
				String on = state.get();
				RoundRectangle2D card = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, ARC, ARC);
				g.setPaint(new GradientPaint(0, 0, FROM, w, 0, mix(TO, accent, 0.2f)));
				g.fill(card);
				g.setColor(alpha(accent, 150));
				g.draw(card);

				g.setFont(FontManager.getRunescapeBoldFont());
				shadow(g, fit(title, g.getFontMetrics(), w - 24), 12, 18, Color.WHITE);

				g.setFont(FontManager.getRunescapeSmallFont());
				FontMetrics fm = g.getFontMetrics();
				int cw = chipWidth();
				boolean full = true;
				for (String v : values) {
					full &= fm.stringWidth(label.apply(v)) + 8 <= cw;
				}
				for (int i = 0; i < values.length; i++) {
					boolean picked = values[i].equals(on);
					int x = 12 + i * (cw + 4);
					RoundRectangle2D chip = new RoundRectangle2D.Float(x, CHIP_Y, cw, CHIP_H, CHIP_H, CHIP_H);
					g.setColor(picked ? mix(TO, accent, 0.55f) : i == hover ? new Color(0x2C3548) : new Color(0x1B2130));
					g.fill(chip);
					g.setColor(picked ? accent : new Color(0x4A5670));
					g.draw(chip);
					String text = fit(full ? label.apply(values[i]) : shortLabel.apply(values[i]), fm, cw - 6);
					shadow(g, text, x + (cw - fm.stringWidth(text)) / 2, CHIP_Y + 13, picked ? Color.WHITE : new Color(0xAEB6C4));
				}
				String why = override.get();
				shadow(g, fit(why != null ? why : on == null ? "" : describe.apply(on), fm, w - 24), 12, CHIP_Y + CHIP_H + 15, new Color(0xAEB6C4));
			} finally {
				g.dispose();
			}
		}

		/** Shows this line instead of the description while it returns something. */
		Choice withNote(Supplier<String> note) {
			override = note;
			return this;
		}
	}

	/**
	 * A card like {@link Info} with a picture on the right (same size and place, never dimmed), plus an
	 * Active / Inactive switch on its bottom row: the boss page's setup, the farm page's route.
	 */
	static final class SetupCard extends JComponent {
		private static final java.awt.Rectangle SWITCH_AREA = new java.awt.Rectangle(6, 46, 100, 24);
		private final String activeHeading;
		private final String selectedHeading;
		private final String activeTip;
		private final String inactiveTip;
		private final Color accent;
		private final Supplier<String[]> lines;
		private final Supplier<java.awt.image.BufferedImage> picture;
		private final BooleanSupplier hasSet;
		private final BooleanSupplier active;
		private final Runnable flip;
		/** Said before Active / Inactive ("Guide: Active"); null: nothing. */
		private String switchLabel;

		SetupCard(String activeHeading, String selectedHeading, String activeTip, String inactiveTip, Color accent, Supplier<String[]> lines,
				Supplier<java.awt.image.BufferedImage> picture, BooleanSupplier hasSet, BooleanSupplier active, Runnable flip) {
			this.activeHeading = activeHeading;
			this.selectedHeading = selectedHeading;
			this.activeTip = activeTip;
			this.inactiveTip = inactiveTip;
			this.accent = accent;
			this.lines = lines;
			this.picture = picture;
			this.hasSet = hasSet;
			this.active = active;
			this.flip = flip;
			setPreferredSize(new Dimension(200, 72));
			setMinimumSize(new Dimension(120, 72));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));
			// Registers the card with the tooltip manager; the text comes from getToolTipText(MouseEvent).
			setToolTipText("");
			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseReleased(MouseEvent e) {
					Guard.run(RoadmapPanel.GUARD_FEATURE, () -> {
						if (hasSet.getAsBoolean() && SWITCH_AREA.contains(e.getPoint())) {
							flip.run();
							repaint();
						}
					});
				}
			});
			addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
				@Override
				public void mouseMoved(MouseEvent e) {
					Guard.run(RoadmapPanel.GUARD_FEATURE, () -> {
						boolean over = hasSet.getAsBoolean() && SWITCH_AREA.contains(e.getPoint());
						setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
					});
				}
			});
		}

		/** Says what the switch is before Active / Inactive ("Guide: Active"). */
		SetupCard withSwitchLabel(String label) {
			switchLabel = label;
			return this;
		}

		@Override
		public String getToolTipText(MouseEvent e) {
			if (!hasSet.getAsBoolean() || !SWITCH_AREA.contains(e.getPoint())) {
				return null;
			}
			return active.getAsBoolean() ? activeTip : inactiveTip;
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
				boolean set = hasSet.getAsBoolean();
				boolean on = set && active.getAsBoolean();
				java.awt.image.BufferedImage img = picture.get();
				int textW = w - 24;
				if (img != null && img.getWidth() > 0) {
					// Same box and place as the other cards' pictures, at full quality whether Active or not.
					int box = 40;
					float scale = Math.min(box / (float) img.getWidth(), box / (float) img.getHeight());
					int iw = Math.round(img.getWidth() * scale);
					int ih = Math.round(img.getHeight() * scale);
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
					g.drawImage(img, w - 10 - box + (box - iw) / 2, (h - ih) / 2, iw, ih, null);
					textW = w - 24 - box - 6;
				}
				g.setFont(FontManager.getRunescapeSmallFont());
				shadow(g, on ? activeHeading : selectedHeading, 12, 16, new Color(0x8C96A8));
				String[] text = lines.get();
				if (text.length > 0) {
					g.setFont(FontManager.getRunescapeFont());
					shadow(g, fit(text[0], g.getFontMetrics(), textW), 12, 32, set && !on ? new Color(0x8C96A8) : Color.WHITE);
				}
				if (text.length > 1) {
					g.setFont(FontManager.getRunescapeSmallFont());
					shadow(g, fit(text[1], g.getFontMetrics(), textW), 12, 45, set && !on ? new Color(0x6B7385) : new Color(0xC9D1DE));
				}
				if (set) {
					int sx = 12;
					int sy = 50;
					g.setColor(on ? accent : new Color(0x3A4355));
					g.fillRoundRect(sx, sy, 30, 16, 16, 16);
					g.setColor(Color.WHITE);
					g.fillOval(on ? sx + 16 : sx + 2, sy + 2, 12, 12);
					g.setFont(FontManager.getRunescapeSmallFont());
					shadow(g, (switchLabel == null ? "" : switchLabel + ": ") + (on ? "Active" : "Inactive"), 48, 62,
						on ? brighten(accent) : new Color(0xAEB6C4));
				}
			} finally {
				g.dispose();
			}
		}
	}

	/** A card with a heading and up to a set number of lines: the first in white, the rest smaller (the slayer location). */
	static final class Lines extends JComponent {
		private final String heading;
		private final int count;
		private final Supplier<String[]> lines;
		private Supplier<String> tip = () -> null;

		Lines(String heading, int count, Supplier<String[]> lines) {
			this.heading = heading;
			this.count = count;
			this.lines = lines;
			int h = 38 + (count - 1) * 13 + 4;
			setPreferredSize(new Dimension(200, h));
			setMinimumSize(new Dimension(120, h));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		}

		/** A live tooltip (null: none). */
		Lines withTip(Supplier<String> tip) {
			this.tip = tip;
			// Registers the card with the tooltip manager; the text comes from getToolTipText(MouseEvent).
			setToolTipText("");
			return this;
		}

		@Override
		public String getToolTipText(MouseEvent e) {
			return tip.get();
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
				int y = 32;
				for (int i = 0; i < text.length && i < count; i++) {
					g.setFont(i == 0 ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont());
					shadow(g, fit(text[i], g.getFontMetrics(), w - 24), 12, y, i == 0 ? Color.WHITE : new Color(0xC9D1DE));
					y += i == 0 ? 15 : 13;
				}
			} finally {
				g.dispose();
			}
		}
	}

	/** A card with a heading and rows of a label and its value on the right (the slayer session's numbers). */
	static final class Stats extends JComponent {
		private static final int ROW = 14;
		private final String heading;
		private final Color accent;
		private final int count;
		private final Supplier<String[][]> rows;

		Stats(String heading, Color accent, int count, Supplier<String[][]> rows) {
			this.heading = heading;
			this.accent = accent;
			this.count = count;
			this.rows = rows;
			int h = 24 + count * ROW + 6;
			setPreferredSize(new Dimension(200, h));
			setMinimumSize(new Dimension(120, h));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
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
				g.setColor(alpha(accent, 120));
				g.draw(card);
				g.setFont(FontManager.getRunescapeSmallFont());
				shadow(g, heading, 12, 16, new Color(0x8C96A8));
				FontMetrics fm = g.getFontMetrics();
				String[][] all = rows.get();
				int y = 32;
				for (int i = 0; i < all.length && i < count; i++) {
					String label = all[i].length > 0 ? all[i][0] : "";
					String value = all[i].length > 1 && all[i][1] != null ? all[i][1] : "-";
					int vw = fm.stringWidth(value);
					shadow(g, fit(label, fm, w - 36 - vw), 12, y, new Color(0xAEB6C4));
					shadow(g, value, w - 12 - vw, y, i == 0 ? brighten(accent) : Color.WHITE);
					y += ROW;
				}
			} finally {
				g.dispose();
			}
		}
	}

	/** A quiet card with a heading and one or two lines of live text (current roadmap, route, setup). */
	static final class Info extends JComponent {
		private final String heading;
		private final Supplier<String[]> lines;
		private final Supplier<java.awt.image.BufferedImage> picture;

		Info(String heading, Supplier<String[]> lines) {
			this(heading, lines, () -> null);
		}

		/** With a picture on the right, like the tool tiles (the active boss, the next goal). */
		Info(String heading, Supplier<String[]> lines, Supplier<java.awt.image.BufferedImage> picture) {
			this.heading = heading;
			this.lines = lines;
			this.picture = picture;
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
				java.awt.image.BufferedImage img = picture.get();
				int textW = w - 24;
				if (img != null && img.getWidth() > 0) {
					int box = 40;
					float scale = Math.min(box / (float) img.getWidth(), box / (float) img.getHeight());
					int iw = Math.round(img.getWidth() * scale);
					int ih = Math.round(img.getHeight() * scale);
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
					g.drawImage(img, w - 10 - box + (box - iw) / 2, (h - ih) / 2, iw, ih, null);
					textW = w - 24 - box - 6;
				}
				g.setFont(FontManager.getRunescapeSmallFont());
				shadow(g, heading, 12, 16, new Color(0x8C96A8));
				String[] text = lines.get();
				g.setFont(FontManager.getRunescapeFont());
				FontMetrics fm = g.getFontMetrics();
				int y = 32;
				for (int i = 0; i < text.length && i < 2; i++) {
					g.setFont(i == 0 ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont());
					shadow(g, fit(text[i], g.getFontMetrics(), textW), 12, y, i == 0 ? Color.WHITE : new Color(0xC9D1DE));
					y += i == 0 ? 15 : 13;
				}
			} finally {
				g.dispose();
			}
		}
	}

	/** What a {@link Progress} card shows; null lines mean "nothing loaded" (then only the first two lines draw). */
	static final class ProgressState {
		final String[] lines;
		/** Big text inside the ring (the tier number), the ring's share (whole progression) and the bar's (this tier); -1: none. */
		final String badge;
		final float ring;
		final float bar;

		ProgressState(String[] lines, String badge, float ring, float bar) {
			this.lines = lines;
			this.badge = badge;
			this.ring = ring;
			this.bar = bar;
		}
	}

	/**
	 * A card like {@link Info} with a progress ring on the right (the whole progression, the current
	 * tier's number inside) and a bar under the text (the current tier).
	 */
	static final class Progress extends JComponent {
		private final String heading;
		private final Color accent;
		private final Supplier<ProgressState> state;

		Progress(String heading, Color accent, Supplier<ProgressState> state) {
			this.heading = heading;
			this.accent = accent;
			this.state = state;
			setPreferredSize(new Dimension(200, 64));
			setMinimumSize(new Dimension(120, 64));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
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
				ProgressState s = state.get();
				int textW = w - 24;
				if (s.ring >= 0) {
					// The ring: a dim track, the done share in the accent from the top, the tier number inside.
					int d = 42;
					int rx = w - 10 - d;
					int ry = (h - d) / 2;
					g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					g.setColor(new Color(0x2C3446));
					g.drawOval(rx + 3, ry + 3, d - 6, d - 6);
					g.setColor(accent);
					int arc = Math.round(360 * Math.min(1f, s.ring));
					if (arc > 0) {
						g.drawArc(rx + 3, ry + 3, d - 6, d - 6, 90, -arc);
					}
					g.setStroke(new BasicStroke(1f));
					if (s.badge != null) {
						g.setFont(FontManager.getRunescapeBoldFont());
						FontMetrics fm = g.getFontMetrics();
						shadow(g, s.badge, rx + (d - fm.stringWidth(s.badge)) / 2, ry + (d + fm.getAscent()) / 2 - 2, Color.WHITE);
					}
					textW = w - 24 - d - 6;
				}
				g.setFont(FontManager.getRunescapeSmallFont());
				shadow(g, heading, 12, 15, new Color(0x8C96A8));
				String[] text = s.lines;
				int y = 30;
				for (int i = 0; i < text.length && i < 2; i++) {
					g.setFont(i == 0 ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont());
					shadow(g, fit(text[i], g.getFontMetrics(), textW), 12, y, i == 0 ? Color.WHITE : new Color(0xC9D1DE));
					y += i == 0 ? 14 : 12;
				}
				if (s.bar >= 0) {
					int bw = textW;
					g.setColor(new Color(0x2C3446));
					g.fillRoundRect(12, h - 12, bw, 5, 5, 5);
					g.setColor(accent);
					int fill = Math.round(bw * Math.min(1f, s.bar));
					if (fill > 0) {
						g.fillRoundRect(12, h - 12, Math.max(5, fill), 5, 5, 5);
					}
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
