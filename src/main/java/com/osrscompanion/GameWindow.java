package com.osrscompanion;

import static com.osrscompanion.GameUi.*;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Collections;
import java.util.List;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * An in-game window in the middle of the screen (farm run, boss gear, roadmap, slayer gear, gear progression):
 * its frame with title and close button, clickable areas with tooltips, an optional switcher list, and the mouse
 * and keyboard (Escape closes the list, then the window; clicks and the wheel inside never reach the game).
 */
abstract class GameWindow extends Overlay implements MouseListener, MouseWheelListener, KeyListener {
	static final int SCREEN_MARGIN = 10;
	static final int TITLE_HEIGHT = 28;

	/** A clickable area of the last render: what a click does, its tooltip, and whether it leaves the switcher list open. */
	static final class Hit {
		final Rectangle area;
		final Runnable action;
		final String tooltip;
		final boolean keepOpen;

		Hit(Rectangle area, Runnable action, String tooltip) {
			this(area, action, tooltip, false);
		}

		Hit(Rectangle area, Runnable action, String tooltip, boolean keepOpen) {
			this.area = area;
			this.action = action;
			this.tooltip = tooltip;
			this.keepOpen = keepOpen;
		}
	}

	final Client client;
	private final String feature;
	volatile List<Hit> hits = Collections.emptyList();
	volatile Rectangle window;
	volatile Point mouse;
	volatile boolean switcherOpen;
	/** The open switcher list: no tooltips over it, and the wheel scrolls it. */
	volatile Rectangle switcherArea;

	GameWindow(Client client, String feature) {
		this.client = client;
		this.feature = feature;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Whether the window is open (its tool says so). */
	abstract boolean isShown();

	/** Escape with no switcher list open, and the close button. */
	abstract void closeWindow();

	/** The wheel turned over the window (outside nothing; default: nothing scrolls). */
	void scrolled(Point p, int rotation) {
	}

	/** Whether clicking this area leaves an open switcher list open. */
	boolean keepsSwitcherOpen(Hit clicked) {
		return clicked != null && clicked.keepOpen;
	}

	/**
	 * The window's frame: background, border, title, close button and the title bar's line. Null (and no clickable
	 * areas) when it is not shown or the screen is smaller than its minimum size.
	 */
	Rectangle frame(Graphics2D g, boolean shown, int maxW, int maxH, int minW, int minH, String title, List<Hit> newHits) {
		if (!shown) {
			window = null;
			hits = Collections.emptyList();
			return null;
		}
		int w = Math.min(maxW, client.getCanvasWidth() - 2 * SCREEN_MARGIN);
		int h = Math.min(maxH, client.getCanvasHeight() - 2 * SCREEN_MARGIN);
		if (w < minW || h < minH) {
			window = null;
			hits = Collections.emptyList();
			return null;
		}
		int x = (client.getCanvasWidth() - w) / 2;
		int y = (client.getCanvasHeight() - h) / 2;
		g.setColor(BG);
		g.fillRect(x, y, w, h);
		g.setColor(FRAME_DARK);
		g.setStroke(new BasicStroke(3));
		g.drawRect(x + 1, y + 1, w - 3, h - 3);
		g.setStroke(new BasicStroke(1));
		g.setColor(FRAME_LIGHT);
		g.drawRect(x + 3, y + 3, w - 7, h - 7);
		g.setFont(FontManager.getRunescapeBoldFont());
		centred(g, title, x + w / 2, y + 20, ORANGE);
		Rectangle close = new Rectangle(x + w - 28, y + 6, 20, 18);
		box(g, close, BG, PANEL_EDGE, BUTTON_INNER);
		g.setColor(new Color(0xC8A24A));
		g.setStroke(new BasicStroke(2));
		g.drawLine(close.x + 6, close.y + 5, close.x + 14, close.y + 13);
		g.drawLine(close.x + 14, close.y + 5, close.x + 6, close.y + 13);
		g.setStroke(new BasicStroke(1));
		newHits.add(new Hit(close, this::closeWindow, null));
		g.setColor(PANEL_EDGE);
		g.drawLine(x + 8, y + TITLE_HEIGHT, x + w - 9, y + TITLE_HEIGHT);
		g.setColor(FRAME_LIGHT);
		g.drawLine(x + 8, y + TITLE_HEIGHT + 1, x + w - 9, y + TITLE_HEIGHT + 1);
		return new Rectangle(x, y, w, h);
	}

	/** The tooltip of the area under the mouse (not over the open switcher list); keeps this render's clickable areas. */
	void finish(Graphics2D g, Rectangle win, List<Hit> newHits) {
		Point m = mouse;
		Rectangle list = switcherArea;
		boolean overList = switcherOpen && list != null && m != null && list.contains(m);
		if (!switcherOpen) {
			switcherArea = null;
		}
		if (m != null && win.contains(m) && !overList) {
			for (Hit hit : newHits) {
				if (hit.tooltip != null && hit.area.contains(m)) {
					tooltip(g, hit.tooltip, m, win);
					break;
				}
			}
		}
		window = win;
		hits = Collections.unmodifiableList(newHits);
	}

	private boolean inside(MouseEvent e) {
		Rectangle win = window;
		return isShown() && win != null && win.contains(e.getPoint());
	}

	private MouseEvent consumeInside(MouseEvent e) {
		return Guard.call(feature, () -> {
			if (inside(e)) {
				e.consume();
			}
			return e;
		}, e);
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		return Guard.call(feature, () -> {
			if (!inside(e)) {
				return e;
			}
			if (SwingUtilities.isLeftMouseButton(e)) {
				Point p = e.getPoint();
				boolean listWasOpen = switcherOpen;
				Hit clicked = null;
				for (Hit hit : hits) {
					if (hit.area.contains(p)) {
						clicked = hit;
						break;
					}
				}
				if (clicked != null) {
					clicked.action.run();
				}
				if (listWasOpen && switcherOpen && !keepsSwitcherOpen(clicked)) {
					switcherOpen = false;
				}
			}
			e.consume();
			return e;
		}, e);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e) {
		return consumeInside(e);
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		return consumeInside(e);
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e) {
		return consumeInside(e);
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e) {
		return Guard.call(feature, () -> {
			mouse = e.getPoint();
			if (inside(e)) {
				e.consume();
			}
			return e;
		}, e);
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e) {
		return Guard.call(feature, () -> {
			mouse = null;
			return e;
		}, e);
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e) {
		return Guard.call(feature, () -> {
			if (inside(e)) {
				scrolled(e.getPoint(), e.getWheelRotation());
				e.consume();
			}
			return e;
		}, e);
	}

	@Override
	public void keyTyped(KeyEvent e) {
	}

	@Override
	public void keyPressed(KeyEvent e) {
		Guard.run(feature, () -> {
			if (isShown() && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
				if (switcherOpen) {
					switcherOpen = false;
				} else {
					closeWindow();
				}
				e.consume();
			}
		});
	}

	@Override
	public void keyReleased(KeyEvent e) {
	}
}
