package com.osrscompanion;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseWheelListener;

/**
 * Plays the mouse and keyboard on the in-game windows for the UI golden tests: finds their clickable areas
 * (the windows keep them in a {@code hits} list after each render), hovers, clicks, scrolls, presses keys.
 */
final class WindowDriver {
	private static final Canvas SOURCE = new Canvas();

	private WindowDriver() {
	}

	/** A clickable area of the last render, with its tooltip (null when it has none). */
	static final class Area {
		final Rectangle bounds;
		final String tooltip;

		Area(Rectangle bounds, String tooltip) {
			this.bounds = bounds;
			this.tooltip = tooltip;
		}

		Point centre() {
			return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
		}

		@Override
		public String toString() {
			return bounds + " " + tooltip;
		}
	}

	static List<Area> areas(Object window) {
		List<Area> out = new ArrayList<>();
		for (Object hit : (List<?>) get(window, "hits")) {
			Rectangle r = null;
			String tip = null;
			for (Field f : hit.getClass().getDeclaredFields()) {
				f.setAccessible(true);
				try {
					Object v = f.get(hit);
					if (v instanceof Rectangle && r == null) {
						r = (Rectangle) v;
					} else if (v instanceof String && tip == null) {
						tip = (String) v;
					}
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				}
			}
			out.add(new Area(r, tip));
		}
		return out;
	}

	/** The first area whose tooltip starts with the text. */
	static Area area(Object window, String tooltipStart) {
		for (Area a : areas(window)) {
			if (a.tooltip != null && a.tooltip.startsWith(tooltipStart)) {
				return a;
			}
		}
		throw new AssertionError("No area with tooltip '" + tooltipStart + "' in " + areas(window));
	}

	static void move(Object window, Point p) {
		((MouseListener) window).mouseMoved(new MouseEvent(SOURCE, MouseEvent.MOUSE_MOVED, 0, 0, p.x, p.y, 0, false));
	}

	static void click(Object window, Point p) {
		MouseListener l = (MouseListener) window;
		l.mousePressed(new MouseEvent(SOURCE, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, p.x, p.y, 1, false, MouseEvent.BUTTON1));
		l.mouseReleased(new MouseEvent(SOURCE, MouseEvent.MOUSE_RELEASED, 0, 0, p.x, p.y, 1, false, MouseEvent.BUTTON1));
	}

	static void scroll(Object window, Point p, int notches) {
		((MouseWheelListener) window).mouseWheelMoved(new MouseWheelEvent(SOURCE, MouseEvent.MOUSE_WHEEL, 0, 0, p.x, p.y, 0, false,
			MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, notches));
	}

	static void key(Object window, int keyCode) {
		((KeyListener) window).keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, 0, 0, keyCode, KeyEvent.CHAR_UNDEFINED));
	}

	/** The field on the object's class or one it extends (the windows keep theirs in {@link GameWindow}). */
	private static Field field(Object target, String name) throws NoSuchFieldException {
		for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException e) {
				// look in the class it extends
			}
		}
		throw new NoSuchFieldException(name);
	}

	static Object get(Object target, String field) {
		try {
			return field(target, field).get(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	static void set(Object target, String field, Object value) {
		try {
			field(target, field).set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
