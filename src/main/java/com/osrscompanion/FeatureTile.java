package com.osrscompanion;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * A side-panel button in the style of the website's tool banners: a rounded card with the
 * feature's colour gradient, a glow behind an in-game item picture on the right, a two-tone
 * title with a small accent bar, a one-line subtitle and a hover/press effect.
 */
class FeatureTile extends JComponent {
	private static final int ARC = 14;

	private final String titleWhite;
	private final String titleAccent;
	private final String subtitle;
	private final Color accent;
	private final Color from;
	private final Color to;
	private final Runnable action;
	private final boolean compact;
	private volatile BufferedImage icon;
	/** The picture's square on the right, in pixels. */
	private int iconBox = 40;
	/** The picture's drop shadow, made once the picture has loaded. */
	private BufferedImage shadow;
	private boolean hover;
	private boolean pressed;

	/** A banner tile: "WHITE ACCENT" title, subtitle and an item picture. */
	static FeatureTile banner(String titleWhite, String titleAccent, String subtitle, Color accent, Color from, Color to,
			BufferedImage icon, Runnable action) {
		return new FeatureTile(titleWhite, titleAccent, subtitle, accent, from, to, icon, action, false);
	}

	/** A slim tile for small actions (Refresh). */
	static FeatureTile slim(String title, Color accent, Runnable action) {
		return new FeatureTile(title, "", null, accent, new Color(0x1B2130), new Color(0x263049), null, action, true);
	}

	private FeatureTile(String titleWhite, String titleAccent, String subtitle, Color accent, Color from, Color to,
			BufferedImage icon, Runnable action, boolean compact) {
		this.titleWhite = titleWhite;
		this.titleAccent = titleAccent;
		this.subtitle = subtitle;
		this.accent = accent;
		this.from = from;
		this.to = to;
		this.action = action;
		this.compact = compact;
		this.icon = icon;
		if (icon instanceof AsyncBufferedImage) {
			// Item pictures load in the background; draw again once they are in.
			((AsyncBufferedImage) icon).onLoaded(() -> {
				shadow = null;
				repaint();
			});
		}
		int h = compact ? 30 : 58;
		setPreferredSize(new Dimension(200, h));
		setMinimumSize(new Dimension(120, h));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		setFocusable(false);
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				hover = true;
				repaint();
			}

			@Override
			public void mouseExited(MouseEvent e) {
				hover = false;
				pressed = false;
				repaint();
			}

			@Override
			public void mousePressed(MouseEvent e) {
				pressed = true;
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				boolean click = pressed && contains(e.getPoint());
				pressed = false;
				repaint();
				if (click && action != null) {
					action.run();
				}
			}
		};
		addMouseListener(mouse);
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			int w = getWidth();
			int h = getHeight();
			int dy = pressed ? 1 : 0;
			RoundRectangle2D card = new RoundRectangle2D.Float(0.5f, 0.5f + dy, w - 1.5f, h - 1.5f - dy, ARC, ARC);

			// Dark on the left, the feature's colour on the right, a little brighter on hover.
			Color right = hover ? brighter(to, 1.18f) : to;
			g.setPaint(new GradientPaint(0, 0, from, w, 0, right));
			g.fill(card);
			// The glow behind the picture.
			if (!compact) {
				float r = h * 1.15f;
				g.setPaint(new RadialGradientPaint(new Point2D.Float(w - 30, h / 2f), r, new float[]{0f, 1f},
					new Color[]{withAlpha(accent, hover ? 120 : 80), withAlpha(accent, 0)}, MultipleGradientPaint.CycleMethod.NO_CYCLE));
				g.fill(card);
			}
			// Top sheen.
			g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, hover ? 30 : 18), 0, h / 2f, new Color(255, 255, 255, 0)));
			g.fill(card);
			// Border: the accent, stronger on hover.
			g.setStroke(new BasicStroke(hover ? 1.6f : 1f));
			g.setColor(withAlpha(accent, hover ? 230 : 140));
			g.draw(card);

			if (compact) {
				g.setFont(FontManager.getRunescapeBoldFont());
				FontMetrics fm = g.getFontMetrics();
				String t = titleWhite;
				int tx = (w - fm.stringWidth(t)) / 2;
				int ty = (h - fm.getHeight()) / 2 + fm.getAscent() + dy;
				shadowText(g, t, tx, ty, hover ? Color.WHITE : new Color(0xD7DEEA));
				return;
			}

			// The item picture, a little larger, with a soft shadow.
			int ix = w - iconBox - 10;
			int iy = (h - iconBox) / 2 + dy;
			BufferedImage img = icon;
			if (img != null && img.getWidth() > 0) {
				float scale = Math.min(iconBox / (float) img.getWidth(), iconBox / (float) img.getHeight());
				int iw = Math.round(img.getWidth() * scale);
				int ih = Math.round(img.getHeight() * scale);
				int px = ix + (iconBox - iw) / 2;
				int py = iy + (iconBox - ih) / 2 - (hover ? 1 : 0);
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
				if (shadow == null) {
					shadow = silhouette(img);
				}
				g.drawImage(shadow, px + 2, py + 3, iw, ih, null);
				g.setComposite(AlphaComposite.SrcOver);
				g.drawImage(img, px, py, iw, ih, null);
			}

			// Title: white then the accent colour, the accent bar, the subtitle.
			int tx = 12;
			g.setFont(FontManager.getRunescapeBoldFont());
			FontMetrics fm = g.getFontMetrics();
			int ty = 20 + dy;
			shadowText(g, titleWhite, tx, ty, Color.WHITE);
			if (!titleAccent.isEmpty()) {
				shadowText(g, titleAccent, tx + fm.stringWidth(titleWhite + " "), ty, accent);
			}
			g.setColor(accent);
			g.fillRoundRect(tx, ty + 5, 26, 3, 3, 3);
			g.setColor(withAlpha(accent, 90));
			g.fillRoundRect(tx + 28, ty + 5, 12, 3, 3, 3);
			if (subtitle != null) {
				g.setFont(FontManager.getRunescapeSmallFont());
				String sub = fit(subtitle, g.getFontMetrics(), ix - tx - 6);
				shadowText(g, sub, tx, ty + 22, new Color(0xC9D1DE));
			}
		} finally {
			g.dispose();
		}
	}

	private static void shadowText(Graphics2D g, String text, int x, int y, Color color) {
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

	/** A bigger (or smaller) picture square than the usual 40 pixels; it should fit the tile's 58 pixel height. */
	FeatureTile iconSize(int px) {
		iconBox = px;
		repaint();
		return this;
	}

	/** Sets (or replaces) the picture, e.g. an interface sprite once it has loaded. */
	void setIcon(BufferedImage image) {
		icon = image;
		shadow = null;
		repaint();
	}

	/** The picture's shape in black, for the drop shadow. */
	private static BufferedImage silhouette(BufferedImage img) {
		BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				int a = (img.getRGB(x, y) >>> 24) & 0xFF;
				out.setRGB(x, y, a << 24);
			}
		}
		return out;
	}

	private static Color withAlpha(Color c, int alpha) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
	}

	private static Color brighter(Color c, float f) {
		return new Color(Math.min(255, Math.round(c.getRed() * f)), Math.min(255, Math.round(c.getGreen() * f)), Math.min(255, Math.round(c.getBlue() * f)));
	}
}
