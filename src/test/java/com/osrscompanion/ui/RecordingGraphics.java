package com.osrscompanion.ui;

import java.awt.Color;
import java.awt.Composite;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws onto a real image and writes every drawing call to a log: the text with its position, font and
 * colour, every shape and every image with where it went. Two renders with the same log are pixel-identical
 * on any machine, which is what the UI golden tests compare (the PNG is only for looking at).
 */
public final class RecordingGraphics extends Graphics2D {
	private final Graphics2D g;
	private final List<String> log;
	private final ImageNames images;

	public RecordingGraphics(Graphics2D delegate, List<String> log, ImageNames images) {
		this.g = delegate;
		this.log = log;
		this.images = images;
	}

	/** Gives drawn images a stable name for the log (fake item icons are named by the test kit). */
	public interface ImageNames {
		String name(Image image);
	}

	// ---- logging ---------------------------------------------------------------------------------

	private void rec(String what) {
		AffineTransform t = g.getTransform();
		StringBuilder sb = new StringBuilder(what);
		if (t.getTranslateX() != 0 || t.getTranslateY() != 0 || !t.isIdentity()) {
			sb.append(" @t(").append(num(t.getTranslateX())).append(',').append(num(t.getTranslateY()));
			if (t.getScaleX() != 1 || t.getScaleY() != 1 || t.getShearX() != 0 || t.getShearY() != 0) {
				sb.append(",m=").append(num(t.getScaleX())).append('/').append(num(t.getShearY())).append('/')
					.append(num(t.getShearX())).append('/').append(num(t.getScaleY()));
			}
			sb.append(')');
		}
		Shape clip = g.getClip();
		if (clip != null) {
			Rectangle r = clip.getBounds();
			sb.append(" clip(").append(r.x).append(',').append(r.y).append(',').append(r.width).append(',').append(r.height).append(')');
		}
		log.add(sb.toString());
	}

	private String paint() {
		Paint p = g.getPaint();
		String s = p instanceof Color ? color((Color) p) : p == null ? "null" : p.getClass().getSimpleName();
		Composite c = g.getComposite();
		if (c instanceof AlphaComposite && (((AlphaComposite) c).getAlpha() != 1f || ((AlphaComposite) c).getRule() != AlphaComposite.SRC_OVER)) {
			s += " a=" + num(((AlphaComposite) c).getAlpha()) + "/" + ((AlphaComposite) c).getRule();
		}
		return s;
	}

	private String stroke() {
		Stroke st = g.getStroke();
		if (st instanceof BasicStroke) {
			BasicStroke b = (BasicStroke) st;
			return "w=" + num(b.getLineWidth()) + (b.getDashArray() != null ? " dash" : "");
		}
		return st.getClass().getSimpleName();
	}

	private static String color(Color c) {
		return String.format(Locale.ROOT, "#%02x%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
	}

	private static String font(Font f) {
		return f == null ? "null" : f.getFontName(Locale.ROOT) + "/" + f.getStyle() + "/" + num(f.getSize2D());
	}

	private static String num(double d) {
		if (d == Math.rint(d)) {
			return String.valueOf((long) d);
		}
		return String.format(Locale.ROOT, "%.3f", d);
	}

	private static String shape(Shape s) {
		if (s instanceof Rectangle) {
			Rectangle r = (Rectangle) s;
			return "rect(" + r.x + "," + r.y + "," + r.width + "," + r.height + ")";
		}
		StringBuilder sb = new StringBuilder(s.getClass().getSimpleName()).append('[');
		double[] c = new double[6];
		int n = 0;
		for (PathIterator it = s.getPathIterator(null); !it.isDone() && n < 64; it.next(), n++) {
			int type = it.currentSegment(c);
			sb.append(type).append(':').append(num(c[0])).append(',').append(num(c[1])).append(' ');
		}
		return sb.append(']').toString();
	}

	private String img(Image image) {
		return image == null ? "null" : images.name(image);
	}

	// ---- shapes and text ---------------------------------------------------------------------------

	@Override
	public void draw(Shape s) {
		rec("draw " + shape(s) + " " + paint() + " " + stroke());
		g.draw(s);
	}

	@Override
	public void fill(Shape s) {
		rec("fill " + shape(s) + " " + paint());
		g.fill(s);
	}

	@Override
	public void drawString(String str, int x, int y) {
		rec("text '" + str + "' " + x + "," + y + " " + font(g.getFont()) + " " + paint());
		g.drawString(str, x, y);
	}

	@Override
	public void drawString(String str, float x, float y) {
		rec("text '" + str + "' " + num(x) + "," + num(y) + " " + font(g.getFont()) + " " + paint());
		g.drawString(str, x, y);
	}

	@Override
	public void drawString(AttributedCharacterIterator iterator, int x, int y) {
		rec("text-attr " + x + "," + y + " " + paint());
		g.drawString(iterator, x, y);
	}

	@Override
	public void drawString(AttributedCharacterIterator iterator, float x, float y) {
		rec("text-attr " + num(x) + "," + num(y) + " " + paint());
		g.drawString(iterator, x, y);
	}

	@Override
	public void drawGlyphVector(GlyphVector gv, float x, float y) {
		rec("glyphs " + gv.getNumGlyphs() + " " + num(x) + "," + num(y) + " " + paint());
		g.drawGlyphVector(gv, x, y);
	}

	@Override
	public void drawLine(int x1, int y1, int x2, int y2) {
		rec("line " + x1 + "," + y1 + "-" + x2 + "," + y2 + " " + paint() + " " + stroke());
		g.drawLine(x1, y1, x2, y2);
	}

	@Override
	public void drawRect(int x, int y, int width, int height) {
		rec("drawRect " + x + "," + y + "," + width + "," + height + " " + paint() + " " + stroke());
		g.drawRect(x, y, width, height);
	}

	@Override
	public void fillRect(int x, int y, int width, int height) {
		rec("fillRect " + x + "," + y + "," + width + "," + height + " " + paint());
		g.fillRect(x, y, width, height);
	}

	@Override
	public void clearRect(int x, int y, int width, int height) {
		rec("clearRect " + x + "," + y + "," + width + "," + height + " " + color(g.getBackground()));
		g.clearRect(x, y, width, height);
	}

	@Override
	public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
		rec("drawRound " + x + "," + y + "," + width + "," + height + "," + arcWidth + "," + arcHeight + " " + paint() + " " + stroke());
		g.drawRoundRect(x, y, width, height, arcWidth, arcHeight);
	}

	@Override
	public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
		rec("fillRound " + x + "," + y + "," + width + "," + height + "," + arcWidth + "," + arcHeight + " " + paint());
		g.fillRoundRect(x, y, width, height, arcWidth, arcHeight);
	}

	@Override
	public void drawOval(int x, int y, int width, int height) {
		rec("drawOval " + x + "," + y + "," + width + "," + height + " " + paint() + " " + stroke());
		g.drawOval(x, y, width, height);
	}

	@Override
	public void fillOval(int x, int y, int width, int height) {
		rec("fillOval " + x + "," + y + "," + width + "," + height + " " + paint());
		g.fillOval(x, y, width, height);
	}

	@Override
	public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
		rec("drawArc " + x + "," + y + "," + width + "," + height + "," + startAngle + "," + arcAngle + " " + paint() + " " + stroke());
		g.drawArc(x, y, width, height, startAngle, arcAngle);
	}

	@Override
	public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
		rec("fillArc " + x + "," + y + "," + width + "," + height + "," + startAngle + "," + arcAngle + " " + paint());
		g.fillArc(x, y, width, height, startAngle, arcAngle);
	}

	private static String points(int[] xs, int[] ys, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			sb.append(xs[i]).append(',').append(ys[i]).append(' ');
		}
		return sb.toString().trim();
	}

	@Override
	public void drawPolyline(int[] xPoints, int[] yPoints, int nPoints) {
		rec("polyline " + points(xPoints, yPoints, nPoints) + " " + paint() + " " + stroke());
		g.drawPolyline(xPoints, yPoints, nPoints);
	}

	@Override
	public void drawPolygon(int[] xPoints, int[] yPoints, int nPoints) {
		rec("drawPoly " + points(xPoints, yPoints, nPoints) + " " + paint() + " " + stroke());
		g.drawPolygon(xPoints, yPoints, nPoints);
	}

	@Override
	public void fillPolygon(int[] xPoints, int[] yPoints, int nPoints) {
		rec("fillPoly " + points(xPoints, yPoints, nPoints) + " " + paint());
		g.fillPolygon(xPoints, yPoints, nPoints);
	}

	// ---- images ---------------------------------------------------------------------------------------

	@Override
	public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) {
		rec("image " + img(img) + " xform=" + xform + " " + paint());
		return g.drawImage(img, xform, obs);
	}

	@Override
	public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) {
		rec("image " + img(img) + " op=" + (op == null ? "-" : op.getClass().getSimpleName()) + " " + x + "," + y + " " + paint());
		g.drawImage(img, op, x, y);
	}

	@Override
	public void drawRenderedImage(RenderedImage img, AffineTransform xform) {
		rec("rendered " + img.getWidth() + "x" + img.getHeight() + " xform=" + xform);
		g.drawRenderedImage(img, xform);
	}

	@Override
	public void drawRenderableImage(RenderableImage img, AffineTransform xform) {
		rec("renderable xform=" + xform);
		g.drawRenderableImage(img, xform);
	}

	@Override
	public boolean drawImage(Image img, int x, int y, ImageObserver observer) {
		rec("image " + img(img) + " " + x + "," + y + " " + paint());
		return g.drawImage(img, x, y, observer);
	}

	@Override
	public boolean drawImage(Image img, int x, int y, int width, int height, ImageObserver observer) {
		rec("image " + img(img) + " " + x + "," + y + " " + width + "x" + height + " " + paint());
		return g.drawImage(img, x, y, width, height, observer);
	}

	@Override
	public boolean drawImage(Image img, int x, int y, Color bgcolor, ImageObserver observer) {
		rec("image " + img(img) + " " + x + "," + y + " bg=" + color(bgcolor) + " " + paint());
		return g.drawImage(img, x, y, bgcolor, observer);
	}

	@Override
	public boolean drawImage(Image img, int x, int y, int width, int height, Color bgcolor, ImageObserver observer) {
		rec("image " + img(img) + " " + x + "," + y + " " + width + "x" + height + " bg=" + color(bgcolor) + " " + paint());
		return g.drawImage(img, x, y, width, height, bgcolor, observer);
	}

	@Override
	public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver observer) {
		rec("image " + img(img) + " d" + dx1 + "," + dy1 + "," + dx2 + "," + dy2 + " s" + sx1 + "," + sy1 + "," + sx2 + "," + sy2 + " " + paint());
		return g.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, observer);
	}

	@Override
	public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, Color bgcolor,
			ImageObserver observer) {
		rec("image " + img(img) + " d" + dx1 + "," + dy1 + "," + dx2 + "," + dy2 + " s" + sx1 + "," + sy1 + "," + sx2 + "," + sy2
			+ " bg=" + color(bgcolor) + " " + paint());
		return g.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, bgcolor, observer);
	}

	@Override
	public void copyArea(int x, int y, int width, int height, int dx, int dy) {
		rec("copyArea " + x + "," + y + "," + width + "," + height + " by " + dx + "," + dy);
		g.copyArea(x, y, width, height, dx, dy);
	}

	// ---- state (passed through; drawing calls above log what is current) ----------------------------

	@Override
	public Graphics create() {
		return new RecordingGraphics((Graphics2D) g.create(), log, images);
	}

	@Override
	public void dispose() {
		g.dispose();
	}

	@Override
	public boolean hit(Rectangle rect, Shape s, boolean onStroke) {
		return g.hit(rect, s, onStroke);
	}

	@Override
	public GraphicsConfiguration getDeviceConfiguration() {
		return g.getDeviceConfiguration();
	}

	@Override
	public void setComposite(Composite comp) {
		g.setComposite(comp);
	}

	@Override
	public void setPaint(Paint paint) {
		g.setPaint(paint);
	}

	@Override
	public void setStroke(Stroke s) {
		g.setStroke(s);
	}

	@Override
	public void setRenderingHint(RenderingHints.Key hintKey, Object hintValue) {
		g.setRenderingHint(hintKey, hintValue);
	}

	@Override
	public Object getRenderingHint(RenderingHints.Key hintKey) {
		return g.getRenderingHint(hintKey);
	}

	@Override
	public void setRenderingHints(Map<?, ?> hints) {
		g.setRenderingHints(hints);
	}

	@Override
	public void addRenderingHints(Map<?, ?> hints) {
		g.addRenderingHints(hints);
	}

	@Override
	public RenderingHints getRenderingHints() {
		return g.getRenderingHints();
	}

	@Override
	public void translate(int x, int y) {
		g.translate(x, y);
	}

	@Override
	public void translate(double tx, double ty) {
		g.translate(tx, ty);
	}

	@Override
	public void rotate(double theta) {
		g.rotate(theta);
	}

	@Override
	public void rotate(double theta, double x, double y) {
		g.rotate(theta, x, y);
	}

	@Override
	public void scale(double sx, double sy) {
		g.scale(sx, sy);
	}

	@Override
	public void shear(double shx, double shy) {
		g.shear(shx, shy);
	}

	@Override
	public void transform(AffineTransform tx) {
		g.transform(tx);
	}

	@Override
	public void setTransform(AffineTransform tx) {
		g.setTransform(tx);
	}

	@Override
	public AffineTransform getTransform() {
		return g.getTransform();
	}

	@Override
	public Paint getPaint() {
		return g.getPaint();
	}

	@Override
	public Composite getComposite() {
		return g.getComposite();
	}

	@Override
	public void setBackground(Color color) {
		g.setBackground(color);
	}

	@Override
	public Color getBackground() {
		return g.getBackground();
	}

	@Override
	public Stroke getStroke() {
		return g.getStroke();
	}

	@Override
	public void clip(Shape s) {
		g.clip(s);
	}

	@Override
	public FontRenderContext getFontRenderContext() {
		return g.getFontRenderContext();
	}

	@Override
	public Color getColor() {
		return g.getColor();
	}

	@Override
	public void setColor(Color c) {
		g.setColor(c);
	}

	@Override
	public void setPaintMode() {
		g.setPaintMode();
	}

	@Override
	public void setXORMode(Color c1) {
		g.setXORMode(c1);
	}

	@Override
	public Font getFont() {
		return g.getFont();
	}

	@Override
	public void setFont(Font font) {
		g.setFont(font);
	}

	@Override
	public FontMetrics getFontMetrics(Font f) {
		return g.getFontMetrics(f);
	}

	@Override
	public Rectangle getClipBounds() {
		return g.getClipBounds();
	}

	@Override
	public void clipRect(int x, int y, int width, int height) {
		g.clipRect(x, y, width, height);
	}

	@Override
	public void setClip(int x, int y, int width, int height) {
		g.setClip(x, y, width, height);
	}

	@Override
	public Shape getClip() {
		return g.getClip();
	}

	@Override
	public void setClip(Shape clip) {
		g.setClip(clip);
	}
}
