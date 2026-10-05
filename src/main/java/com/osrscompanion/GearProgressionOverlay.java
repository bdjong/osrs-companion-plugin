package com.osrscompanion;

import com.osrscompanion.model.GearPlan;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The gear progression window, drawn over the game in the style of the game's own
 * interfaces (like the Leagues menus): tier tabs on the left, the selected tier's
 * steps in the middle (items stacked in a column are on the same level) and the
 * selected item's details with its actions at the bottom.
 *
 * <p>Plugins cannot add real game interfaces, so this is an overlay that also
 * listens to the mouse: clicks inside the window are handled here and consumed so
 * they never reach the game; clicks outside pass through untouched.
 */
@Singleton
public class GearProgressionOverlay extends Overlay implements MouseListener, MouseWheelListener, KeyListener {
	// Game interface palette.
	private static final Color BG = new Color(0x3E3529);
	private static final Color FRAME_DARK = new Color(0x0E0C08);
	private static final Color FRAME_LIGHT = new Color(0x5D5240);
	private static final Color PANEL = new Color(0x2E2820);
	private static final Color PANEL_EDGE = new Color(0x14110C);
	private static final Color PANEL_INNER = new Color(0x4A4134);
	private static final Color TAB_ON = new Color(0x5A4D3A);
	private static final Color SLOT = new Color(0x2B251C);
	private static final Color SLOT_EDGE = new Color(0x13100B);
	private static final Color SLOT_INNER = new Color(0x3F3629);
	private static final Color BUTTON_INNER = new Color(0x6A5C48);
	private static final Color BAR_BG = new Color(0x5C0B0B);
	private static final Color ORANGE = new Color(0xFF981F);
	private static final Color YELLOW = new Color(0xFFFF00);
	private static final Color GREEN = new Color(0x0DC10D);
	private static final Color RED = new Color(0xFF3D3D);
	private static final Color GREY = new Color(0x9A9080);
	private static final Color BEIGE = new Color(0xC9B98F);

	private static final int MAX_WIDTH = 900;
	private static final int MAX_HEIGHT = 600;
	private static final int MIN_WIDTH = 420;
	private static final int MIN_HEIGHT = 300;
	private static final int SCREEN_MARGIN = 10;
	private static final int TITLE_HEIGHT = 28;
	private static final int SUBTITLE_HEIGHT = 22;
	private static final int TAB_WIDTH = 150;
	private static final int TAB_HEIGHT = 50;
	private static final int SLOT_WIDTH = 42;
	private static final int SLOT_HEIGHT = 38;
	private static final int SLOT_GAP = 3;
	private static final int ARROW_WIDTH = 18;
	private static final int ROW_GAP = 10;
	private static final int DETAIL_HEIGHT = 92;
	private static final int BUTTON_WIDTH = 132;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SCROLL_STEP = 24;

	private final Client client;
	private final GearProgressionManager manager;
	private final ItemManager itemManager;
	private final GoalImageCache imageCache;

	/** Clickable areas of the last frame, replaced as a whole each frame. */
	private volatile List<Hit> hits = Collections.emptyList();
	private volatile Rectangle window;
	private volatile Rectangle stepsArea;
	private volatile Point mouse;
	private volatile int scroll;
	private volatile int maxScroll;
	private int scrolledTier = -1;

	private static final class Hit {
		final Rectangle area;
		final Runnable action;
		final String tooltip;

		Hit(Rectangle area, Runnable action, String tooltip) {
			this.area = area;
			this.action = action;
			this.tooltip = tooltip;
		}
	}

	@Inject
	public GearProgressionOverlay(Client client, GearProgressionManager manager, ItemManager itemManager, GoalImageCache imageCache) {
		this.client = client;
		this.manager = manager;
		this.itemManager = itemManager;
		this.imageCache = imageCache;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	// ------------------------------------------------------------------ render

	@Override
	public Dimension render(Graphics2D g) {
		if (!manager.isOpen()) {
			window = null;
			stepsArea = null;
			hits = Collections.emptyList();
			return null;
		}
		manager.refreshIfStale();

		int canvasW = client.getCanvasWidth();
		int canvasH = client.getCanvasHeight();
		int w = Math.min(MAX_WIDTH, canvasW - 2 * SCREEN_MARGIN);
		int h = Math.min(MAX_HEIGHT, canvasH - 2 * SCREEN_MARGIN);
		if (w < MIN_WIDTH || h < MIN_HEIGHT) {
			window = null;
			hits = Collections.emptyList();
			return null;
		}
		int x = (canvasW - w) / 2;
		int y = (canvasH - h) / 2;
		Rectangle win = new Rectangle(x, y, w, h);
		List<Hit> newHits = new ArrayList<>();

		// Frame and title bar.
		g.setColor(BG);
		g.fillRect(x, y, w, h);
		g.setColor(FRAME_DARK);
		g.setStroke(new BasicStroke(3));
		g.drawRect(x + 1, y + 1, w - 3, h - 3);
		g.setStroke(new BasicStroke(1));
		g.setColor(FRAME_LIGHT);
		g.drawRect(x + 3, y + 3, w - 7, h - 7);

		g.setFont(FontManager.getRunescapeBoldFont());
		centred(g, "Gear Progression", x + w / 2, y + 20, ORANGE);
		Rectangle close = new Rectangle(x + w - 28, y + 6, 20, 18);
		box(g, close, BG, PANEL_EDGE, BUTTON_INNER);
		g.setColor(new Color(0xC8A24A));
		g.setStroke(new BasicStroke(2));
		g.drawLine(close.x + 6, close.y + 5, close.x + 14, close.y + 13);
		g.drawLine(close.x + 14, close.y + 5, close.x + 6, close.y + 13);
		g.setStroke(new BasicStroke(1));
		newHits.add(new Hit(close, manager::close, "Close"));
		g.setColor(PANEL_EDGE);
		g.drawLine(x + 8, y + TITLE_HEIGHT, x + w - 9, y + TITLE_HEIGHT);
		g.setColor(FRAME_LIGHT);
		g.drawLine(x + 8, y + TITLE_HEIGHT + 1, x + w - 9, y + TITLE_HEIGHT + 1);

		GearPlan plan = manager.getPlan();
		if (plan == null) {
			renderMessage(g, win);
			finish(g, win, newHits, null);
			return new Dimension(w, h);
		}

		// Subtitle: plan name and overall progress.
		int subY = y + TITLE_HEIGHT + 16;
		g.setFont(FontManager.getRunescapeSmallFont());
		text(g, plan.getTitle(), x + 12, subY, BEIGE);
		int barW = 160;
		bar(g, x + w - 12 - barW, subY - 7, barW, 7, plan.getDone(), plan.getTotal());
		String overall = "Overall " + plan.getDone() + " / " + plan.getTotal();
		g.setFont(FontManager.getRunescapeFont());
		text(g, overall, x + w - 20 - barW - g.getFontMetrics().stringWidth(overall), subY, Color.WHITE);

		int bodyY = y + TITLE_HEIGHT + SUBTITLE_HEIGHT + 6;
		int bodyBottom = y + h - 10;

		GearPlan.Tier tier = manager.getSelectedTier();
		renderTabs(g, plan, tier, new Rectangle(x + 8, bodyY, TAB_WIDTH, bodyBottom - bodyY), newHits);

		int mainX = x + 8 + TAB_WIDTH + 8;
		int mainW = x + w - 8 - mainX;
		Rectangle detail = new Rectangle(mainX, bodyBottom - DETAIL_HEIGHT, mainW, DETAIL_HEIGHT);
		Rectangle steps = new Rectangle(mainX, bodyY, mainW, detail.y - 6 - bodyY);
		if (tier != null) {
			renderSteps(g, tier, steps, newHits);
			renderDetail(g, tier, detail, newHits);
		}
		finish(g, win, newHits, steps);
		return new Dimension(w, h);
	}

	private void finish(Graphics2D g, Rectangle win, List<Hit> newHits, Rectangle steps) {
		// Tooltip for whatever the mouse is over.
		Point m = mouse;
		if (m != null && win.contains(m)) {
			for (Hit hit : newHits) {
				if (hit.tooltip != null && hit.area.contains(m)) {
					tooltip(g, hit.tooltip, m, win);
					break;
				}
			}
		}
		window = win;
		stepsArea = steps;
		hits = Collections.unmodifiableList(newHits);
	}

	private void renderMessage(Graphics2D g, Rectangle win) {
		String message;
		if (manager.getError() != null) {
			message = manager.getError();
		} else if (!manager.isLoaded()) {
			message = "Loading your gear progression...";
		} else {
			message = "No gear progression loaded yet. On the OSRS Companion website, open Tools > Gear progression and load it onto this character.";
		}
		g.setFont(FontManager.getRunescapeFont());
		int lineY = win.y + win.height / 2 - 10;
		for (String line : wrap(message, g.getFontMetrics(), win.width - 80)) {
			centred(g, line, win.x + win.width / 2, lineY, Color.WHITE);
			lineY += 16;
		}
	}

	private void renderTabs(Graphics2D g, GearPlan plan, GearPlan.Tier selected, Rectangle area, List<Hit> newHits) {
		panel(g, area);
		int tabY = area.y + 4;
		for (GearPlan.Tier t : plan.getTiers()) {
			if (tabY + TAB_HEIGHT > area.y + area.height - 4) {
				break;
			}
			Rectangle tab = new Rectangle(area.x + 4, tabY, area.width - 8, TAB_HEIGHT);
			boolean on = selected != null && selected.getTier() == t.getTier();
			if (on) {
				g.setColor(TAB_ON);
				g.fillRect(tab.x, tab.y, tab.width, tab.height);
				g.setColor(ORANGE);
				g.drawRect(tab.x, tab.y, tab.width - 1, tab.height - 1);
			}
			g.setFont(FontManager.getRunescapeFont());
			text(g, "Tier " + t.getTier(), tab.x + 7, tab.y + 15, ORANGE);
			text(g, truncate(t.getName(), g.getFontMetrics(), tab.width - 14), tab.x + 7, tab.y + 30, Color.WHITE);
			g.setFont(FontManager.getRunescapeSmallFont());
			String count = t.getDone() + "/" + t.getTotal();
			int countW = g.getFontMetrics().stringWidth(count);
			bar(g, tab.x + 7, tab.y + 37, tab.width - 22 - countW, 6, t.getDone(), t.getTotal());
			text(g, count, tab.x + tab.width - 7 - countW, tab.y + 44, BEIGE);
			final int tierNumber = t.getTier();
			newHits.add(new Hit(tab, () -> manager.selectTier(tierNumber), null));
			tabY += TAB_HEIGHT + 2;
		}
	}

	private void renderSteps(Graphics2D g, GearPlan.Tier tier, Rectangle area, List<Hit> newHits) {
		panel(g, area);
		g.setFont(FontManager.getRunescapeBoldFont());
		text(g, "Tier " + tier.getTier() + " - " + tier.getName(), area.x + 10, area.y + 18, ORANGE);
		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics small = g.getFontMetrics();
		int legendX = area.x + area.width - 10 - small.stringWidth("Owned  Next  Not yet  stacked = same level");
		legendX = text(g, "Owned  ", legendX, area.y + 17, GREEN);
		legendX = text(g, "Next  ", legendX, area.y + 17, YELLOW);
		legendX = text(g, "Not yet  ", legendX, area.y + 17, GREY);
		text(g, "stacked = same level", legendX, area.y + 17, BEIGE);

		Rectangle view = new Rectangle(area.x + 10, area.y + 28, area.width - 20, area.height - 34);
		if (scrolledTier != tier.getTier()) {
			scrolledTier = tier.getTier();
			scroll = 0;
		}

		// Lay the steps out left to right, wrapping into rows.
		List<GearPlan.Step> steps = tier.getSteps();
		int[] colX = new int[steps.size()];
		int[] colY = new int[steps.size()];
		int cx = 0;
		int cy = 0;
		int rowHeight = 0;
		for (int i = 0; i < steps.size(); i++) {
			int colH = 14 + steps.get(i).getItems().size() * (SLOT_HEIGHT + SLOT_GAP);
			if (cx > 0 && cx + SLOT_WIDTH > view.width) {
				cx = 0;
				cy += rowHeight + ROW_GAP;
				rowHeight = 0;
			}
			colX[i] = cx;
			colY[i] = cy;
			rowHeight = Math.max(rowHeight, colH);
			cx += SLOT_WIDTH + ARROW_WIDTH;
		}
		int contentHeight = cy + rowHeight;
		maxScroll = Math.max(0, contentHeight - view.height);
		int offset = Math.min(scroll, maxScroll);

		Shape oldClip = g.getClip();
		g.clip(view);
		GearPlan.Item selected = manager.getSelectedItem();
		for (int i = 0; i < steps.size(); i++) {
			GearPlan.Step step = steps.get(i);
			int sx = view.x + colX[i];
			int sy = view.y + colY[i] - offset;
			g.setFont(FontManager.getRunescapeSmallFont());
			Color labelColor = step.isNext() ? YELLOW : step.isDone() ? GREEN : GREY;
			String label = String.valueOf(i + 1);
			centred(g, label, sx + SLOT_WIDTH / 2, sy + 10, labelColor);
			int slotY = sy + 14;
			for (GearPlan.Item item : step.getItems()) {
				Rectangle slot = new Rectangle(sx, slotY, SLOT_WIDTH, SLOT_HEIGHT);
				drawSlot(g, slot, item, selected != null && selected.getId() == item.getId());
				if (view.contains(slot.x, slot.y + slot.height / 2)) {
					final int id = item.getId();
					newHits.add(new Hit(slot, () -> manager.selectItem(id), item.getName()));
				}
				slotY += SLOT_HEIGHT + SLOT_GAP;
			}
			boolean nextOnSameRow = i + 1 < steps.size() && colY[i + 1] == colY[i];
			if (nextOnSameRow) {
				arrow(g, sx + SLOT_WIDTH + 2, sy + 14 + SLOT_HEIGHT / 2, ARROW_WIDTH - 4);
			}
		}
		g.setClip(oldClip);

		if (maxScroll > 0) {
			// Scroll bar on the right edge of the steps view.
			int trackX = area.x + area.width - 6;
			g.setColor(PANEL_EDGE);
			g.fillRect(trackX, view.y, 3, view.height);
			int thumbH = Math.max(20, view.height * view.height / Math.max(1, contentHeight));
			int thumbY = view.y + (view.height - thumbH) * offset / Math.max(1, maxScroll);
			g.setColor(FRAME_LIGHT);
			g.fillRect(trackX, thumbY, 3, thumbH);
		}
	}

	private void renderDetail(Graphics2D g, GearPlan.Tier tier, Rectangle area, List<Hit> newHits) {
		panel(g, area);
		GearPlan.Item item = manager.getSelectedItem();
		g.setFont(FontManager.getRunescapeFont());
		if (item == null) {
			text(g, "Everything in this tier is done. Pick another tier, or select an item.", area.x + 12, area.y + 26, Color.WHITE);
			statusLine(g, area);
			return;
		}
		Rectangle big = new Rectangle(area.x + 10, area.y + 10, 56, 52);
		drawSlot(g, big, item, true);

		int stepNumber = 0;
		List<String> sameLevel = new ArrayList<>();
		List<GearPlan.Step> steps = tier.getSteps();
		for (int i = 0; i < steps.size(); i++) {
			for (GearPlan.Item other : steps.get(i).getItems()) {
				if (other.getId() == item.getId()) {
					stepNumber = i + 1;
					for (GearPlan.Item sibling : steps.get(i).getItems()) {
						if (sibling.getId() != item.getId()) {
							sameLevel.add(sibling.getName());
						}
					}
				}
			}
		}

		int textX = big.x + big.width + 12;
		int textW = area.x + area.width - BUTTON_WIDTH - 22 - textX;
		g.setFont(FontManager.getRunescapeBoldFont());
		text(g, truncate(item.getName(), g.getFontMetrics(), textW), textX, area.y + 20, ORANGE);
		g.setFont(FontManager.getRunescapeFont());
		String where = "Tier " + tier.getTier() + " - step " + stepNumber
				+ (sameLevel.isEmpty() ? "" : " - same level as " + String.join(", ", sameLevel));
		text(g, truncate(where, g.getFontMetrics(), textW), textX, area.y + 36, Color.WHITE);
		String state = item.isAuto() ? "Done - ticked automatically" : item.isManual() ? "Done - you ticked it" : item.isNext() ? "Next up" : "Not done yet";
		Color stateColor = item.isDone() ? GREEN : item.isNext() ? YELLOW : RED;
		text(g, state, textX, area.y + 52, stateColor);
		g.setFont(FontManager.getRunescapeSmallFont());
		text(g, truncate(item.getHow(), g.getFontMetrics(), textW), textX, area.y + 67, GREY);

		int buttonX = area.x + area.width - BUTTON_WIDTH - 10;
		int buttonY = area.y + 10;
		boolean busy = manager.isBusy();
		if (!item.isAuto()) {
			Rectangle tick = new Rectangle(buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT);
			button(g, tick, item.isManual() ? "Undo my tick" : "Mark as done", busy);
			if (!busy) {
				newHits.add(new Hit(tick, () -> manager.toggleDone(item), null));
			}
			buttonY += BUTTON_HEIGHT + 6;
		}
		if (!item.isDone()) {
			Rectangle goal = new Rectangle(buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT);
			button(g, goal, "Set as current goal", busy);
			if (!busy) {
				newHits.add(new Hit(goal, () -> manager.setAsGoal(item), null));
			}
		}
		statusLine(g, area);
	}

	private void statusLine(Graphics2D g, Rectangle area) {
		String status = manager.getStatus();
		if (status != null) {
			g.setFont(FontManager.getRunescapeSmallFont());
			text(g, truncate(status, g.getFontMetrics(), area.width - 20), area.x + 10, area.y + area.height - 7, BEIGE);
		}
	}

	private void drawSlot(Graphics2D g, Rectangle slot, GearPlan.Item item, boolean selected) {
		box(g, slot, SLOT, SLOT_EDGE, SLOT_INNER);
		BufferedImage image = imageFor(item);
		if (image != null) {
			Composite old = g.getComposite();
			if (!item.isDone() && !item.isNext()) {
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
			}
			g.drawImage(image, slot.x + (slot.width - image.getWidth()) / 2, slot.y + (slot.height - image.getHeight()) / 2, null);
			g.setComposite(old);
		}
		if (selected || item.isNext()) {
			Stroke old = g.getStroke();
			g.setStroke(new BasicStroke(2));
			g.setColor(selected ? ORANGE : YELLOW);
			g.drawRect(slot.x + 1, slot.y + 1, slot.width - 2, slot.height - 2);
			g.setStroke(old);
		}
		if (item.isDone()) {
			tick(g, slot.x + slot.width - 14, slot.y + slot.height - 12);
		}
	}

	/** Package-private so a preview harness can supply images without a game client. */
	BufferedImage imageFor(GearPlan.Item item) {
		if (item.getItemId() != null && item.getItemId() > 0) {
			return itemManager.getImage(item.getItemId());
		}
		return imageCache.get(item.getImageUrl());
	}

	// ------------------------------------------------------------------ drawing helpers

	private static void panel(Graphics2D g, Rectangle r) {
		box(g, r, PANEL, PANEL_EDGE, PANEL_INNER);
	}

	private static void box(Graphics2D g, Rectangle r, Color fill, Color edge, Color inner) {
		g.setColor(fill);
		g.fillRect(r.x, r.y, r.width, r.height);
		g.setColor(edge);
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		g.setColor(inner);
		g.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
	}

	private static void button(Graphics2D g, Rectangle r, String label, boolean disabled) {
		box(g, r, BG, PANEL_EDGE, BUTTON_INNER);
		g.setFont(FontManager.getRunescapeSmallFont());
		centred(g, label, r.x + r.width / 2, r.y + 14, disabled ? GREY : ORANGE);
	}

	private static void bar(Graphics2D g, int x, int y, int w, int h, int done, int total) {
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

	private static void arrow(Graphics2D g, int x, int y, int length) {
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

	private static void tick(Graphics2D g, int x, int y) {
		Stroke old = g.getStroke();
		for (int pass = 0; pass < 2; pass++) {
			g.setStroke(new BasicStroke(pass == 0 ? 4 : 2));
			g.setColor(pass == 0 ? Color.BLACK : GREEN);
			g.drawLine(x + 1, y + 6, x + 4, y + 9);
			g.drawLine(x + 4, y + 9, x + 11, y + 1);
		}
		g.setStroke(old);
	}

	private static void tooltip(Graphics2D g, String label, Point m, Rectangle win) {
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
	private static int text(Graphics2D g, String s, int x, int y, Color color) {
		g.setColor(Color.BLACK);
		g.drawString(s, x + 1, y + 1);
		g.setColor(color);
		g.drawString(s, x, y);
		return x + g.getFontMetrics().stringWidth(s);
	}

	private static void centred(Graphics2D g, String s, int centreX, int y, Color color) {
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

	// ------------------------------------------------------------------ input

	private boolean inside(MouseEvent e) {
		Rectangle win = window;
		return manager.isOpen() && win != null && win.contains(e.getPoint());
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		if (!inside(e)) {
			return e;
		}
		if (SwingUtilities.isLeftMouseButton(e)) {
			Point p = e.getPoint();
			for (Hit hit : hits) {
				if (hit.area.contains(p)) {
					hit.action.run();
					break;
				}
			}
		}
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e) {
		if (inside(e)) {
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		if (inside(e)) {
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e) {
		mouse = e.getPoint();
		if (inside(e)) {
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e) {
		if (inside(e)) {
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e) {
		mouse = null;
		return e;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e) {
		Rectangle area = stepsArea;
		if (!inside(e)) {
			return e;
		}
		if (area != null && area.contains(e.getPoint())) {
			scroll = Math.max(0, Math.min(maxScroll, scroll + e.getWheelRotation() * SCROLL_STEP));
		}
		e.consume();
		return e;
	}

	@Override
	public void keyTyped(KeyEvent e) {
	}

	@Override
	public void keyPressed(KeyEvent e) {
		if (manager.isOpen() && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
			manager.close();
			e.consume();
		}
	}

	@Override
	public void keyReleased(KeyEvent e) {
	}
}
