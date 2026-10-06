package com.osrscompanion;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** The Farm run side-panel button: a green sprout, drawn so no image file is needed. */
final class FarmRunIcon {
	private FarmRunIcon() {
	}

	static BufferedImage create() {
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x8B, 0x5A, 0x2B));
		g.fillRoundRect(2, 12, 12, 4, 3, 3);
		g.setColor(new Color(0x4A, 0xDE, 0x80));
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(8, 13, 8, 6);
		Path2D left = new Path2D.Double();
		left.moveTo(8, 8);
		left.curveTo(3, 8, 1, 4, 2, 2);
		left.curveTo(6, 2, 8, 5, 8, 8);
		g.fill(left);
		Path2D right = new Path2D.Double();
		right.moveTo(8, 7);
		right.curveTo(9, 3, 12, 1, 15, 2);
		right.curveTo(15, 6, 11, 8, 8, 7);
		g.fill(right);
		g.dispose();
		return img;
	}
}
