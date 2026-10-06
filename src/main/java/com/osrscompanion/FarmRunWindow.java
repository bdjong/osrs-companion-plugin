package com.osrscompanion;

import static com.osrscompanion.GameUi.*;

import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.model.FarmRoute;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The farm run window, drawn over the game like the gear progression window: the
 * route's stops on the left, what to bring (worn gear and inventory, with where
 * each missing item is: bank, seed vault, seed box or tool leprechaun) on the right,
 * and the selected stop's details with the run's buttons below it.
 */
@Singleton
public class FarmRunWindow extends Overlay implements MouseListener, MouseWheelListener, KeyListener {
	private static final int MAX_WIDTH = 900;
	private static final int MAX_HEIGHT = 600;
	private static final int MIN_WIDTH = 560;
	private static final int MIN_HEIGHT = 340;
	private static final int SCREEN_MARGIN = 10;
	private static final int TITLE_HEIGHT = 28;
	private static final int SUBTITLE_HEIGHT = 22;
	private static final int ROW_HEIGHT = 46;
	private static final int SLOT_GAP = 3;
	private static final int BUTTON_WIDTH = 120;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SCROLL_STEP = 24;
	private static final Color STORED = new Color(0xFF981F);
	private static final Color CYAN = new Color(0x22D3EE);

	/** Worn-equipment layout as in the game: 3 columns, null = no slot there. */
	private static final String[][] EQUIPMENT = {
		{null, "head", null}, {"cape", "neck", "ammo"}, {"weapon", "body", "shield"}, {null, "legs", null}, {"hands", "feet", "ring"},
	};
	/** Storage names as the website sends them, with their one-letter badge. */
	private static final Map<String, String[]> STORAGE = new LinkedHashMap<>();

	static {
		STORAGE.put("leprechaun", new String[]{"L", "tool leprechaun"});
		STORAGE.put("seed_vault", new String[]{"V", "seed vault"});
		STORAGE.put("seed_box", new String[]{"S", "seed box"});
		STORAGE.put("bank", new String[]{"B", "bank"});
	}

	private final Client client;
	private final FarmRunManager manager;
	private final ItemManager itemManager;

	private volatile List<Hit> hits = Collections.emptyList();
	private volatile Rectangle window;
	private volatile Rectangle stopsArea;
	private volatile Point mouse;
	private volatile int scroll;
	private volatile int maxScroll;
	private volatile boolean switcherOpen;

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
	public FarmRunWindow(Client client, FarmRunManager manager, ItemManager itemManager) {
		this.client = client;
		this.manager = manager;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	// ------------------------------------------------------------------ render

	@Override
	public Dimension render(Graphics2D g) {
		if (!manager.isOpen()) {
			window = null;
			stopsArea = null;
			hits = Collections.emptyList();
			return null;
		}
		int w = Math.min(MAX_WIDTH, client.getCanvasWidth() - 2 * SCREEN_MARGIN);
		int h = Math.min(MAX_HEIGHT, client.getCanvasHeight() - 2 * SCREEN_MARGIN);
		if (w < MIN_WIDTH || h < MIN_HEIGHT) {
			window = null;
			hits = Collections.emptyList();
			return null;
		}
		int x = (client.getCanvasWidth() - w) / 2;
		int y = (client.getCanvasHeight() - h) / 2;
		Rectangle win = new Rectangle(x, y, w, h);
		List<Hit> newHits = new ArrayList<>();

		g.setColor(BG);
		g.fillRect(x, y, w, h);
		g.setColor(FRAME_DARK);
		g.setStroke(new BasicStroke(3));
		g.drawRect(x + 1, y + 1, w - 3, h - 3);
		g.setStroke(new BasicStroke(1));
		g.setColor(FRAME_LIGHT);
		g.drawRect(x + 3, y + 3, w - 7, h - 7);
		g.setFont(FontManager.getRunescapeBoldFont());
		centred(g, "Farm Run", x + w / 2, y + 20, ORANGE);
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

		FarmRoute route = manager.getRoute();
		if (route == null || route.getStops().isEmpty()) {
			message(g, win);
			finish(g, win, newHits, null);
			return new Dimension(w, h);
		}

		Rectangle switchButton = subtitle(g, route, win, newHits);
		int bodyY = y + TITLE_HEIGHT + SUBTITLE_HEIGHT + 6;
		int bodyBottom = y + h - 10;
		int leftW = Math.max(250, (int) (w * 0.4));
		Rectangle stops = new Rectangle(x + 8, bodyY, leftW, bodyBottom - bodyY);
		renderStops(g, route, stops, newHits);

		int rightX = stops.x + stops.width + 8;
		int rightW = x + w - 8 - rightX;
		int bodyH = bodyBottom - bodyY;
		// Slots shrink on small screens so the inventory's 7 rows and the details both fit.
		int slot = Math.max(26, Math.min(36, (bodyH * 62 / 100 - 44) / 7 - SLOT_GAP));
		int loadoutH = 26 + 7 * (slot + SLOT_GAP) + 18;
		Rectangle loadout = new Rectangle(rightX, bodyY, rightW, loadoutH);
		renderLoadout(g, route, loadout, slot, newHits);
		Rectangle detail = new Rectangle(rightX, loadout.y + loadout.height + 6, rightW, bodyBottom - loadout.y - loadout.height - 6);
		renderDetail(g, route, detail, newHits);

		if (switcherOpen && switchButton != null) {
			renderSwitcher(g, switchButton, newHits);
		}
		finish(g, win, newHits, stops);
		return new Dimension(w, h);
	}

	/** Route name (with a switcher), the run's numbers and the Start / Stop button. Returns the switch button. */
	private Rectangle subtitle(Graphics2D g, FarmRoute route, Rectangle win, List<Hit> newHits) {
		int subY = win.y + TITLE_HEIGHT + 16;
		g.setFont(FontManager.getRunescapeSmallFont());
		int after = text(g, "Route: ", win.x + 12, subY, BEIGE);
		g.setFont(FontManager.getRunescapeFont());
		after = text(g, truncate(route.getName(), g.getFontMetrics(), win.width / 4), after, subY, Color.WHITE);
		Rectangle switchButton = null;
		if (manager.getRoutes().size() > 1) {
			switchButton = new Rectangle(after + 8, subY - 13, 62, 17);
			button(g, switchButton, switcherOpen ? "Close" : "Switch", false);
			newHits.add(new Hit(switchButton, () -> switcherOpen = !switcherOpen, "Switch to another route"));
		} else {
			switcherOpen = false;
		}

		Rectangle start = new Rectangle(win.x + win.width - 12 - BUTTON_WIDTH, subY - 14, BUTTON_WIDTH, BUTTON_HEIGHT);
		boolean running = manager.isRunning();
		button(g, start, running ? "Stop run" : "Start run", false);
		newHits.add(new Hit(start, running ? manager::stop : manager::start,
			running ? "Stop guiding this run" : "Guide this run: arrow, patch outlines and the step box"));

		int[] p = manager.progress();
		String info;
		if (running) {
			info = manager.isComplete() ? "Run complete!" : p[0] + " / " + p[1] + " patches done";
		} else {
			long ready = route.getStops().stream().flatMap(s -> s.getPatches().stream()).filter(pt -> "ready".equals(pt.getReady())).count();
			FarmRoute.Summary sum = route.getSummary();
			info = p[1] + " patches - " + ready + " ready" + (sum == null ? "" : " - ~" + Math.max(1, Math.round(sum.getActiveSec() / 60.0)) + " min");
		}
		g.setFont(FontManager.getRunescapeFont());
		text(g, info, start.x - 10 - g.getFontMetrics().stringWidth(info), subY, running && !manager.isComplete() ? YELLOW : Color.WHITE);
		return switchButton;
	}

	private void renderSwitcher(Graphics2D g, Rectangle anchor, List<Hit> newHits) {
		List<FarmRoute.RouteSummary> routes = manager.getRoutes();
		g.setFont(FontManager.getRunescapeFont());
		int rowH = 18;
		Rectangle list = new Rectangle(anchor.x, anchor.y + anchor.height + 2, 220, routes.size() * rowH + 6);
		box(g, list, BG, FRAME_DARK, BUTTON_INNER);
		List<Hit> optionHits = new ArrayList<>();
		int rowY = list.y + 3;
		for (FarmRoute.RouteSummary r : routes) {
			Rectangle row = new Rectangle(list.x + 3, rowY, list.width - 6, rowH);
			Point m = mouse;
			if (m != null && row.contains(m)) {
				g.setColor(TAB_ON);
				g.fillRect(row.x, row.y, row.width, row.height);
			}
			text(g, truncate(r.getName(), g.getFontMetrics(), row.width - 50), row.x + 6, row.y + 13, r.isActive() ? ORANGE : Color.WHITE);
			if (r.isActive()) {
				g.setFont(FontManager.getRunescapeSmallFont());
				text(g, "active", row.x + row.width - 38, row.y + 13, GREEN);
				g.setFont(FontManager.getRunescapeFont());
			} else {
				optionHits.add(new Hit(row, () -> {
					switcherOpen = false;
					manager.activate(r);
				}, null));
			}
			rowY += rowH;
		}
		optionHits.add(new Hit(list, () -> { }, null));
		newHits.addAll(0, optionHits);
	}

	private void message(Graphics2D g, Rectangle win) {
		String message = manager.isLoading() ? "Loading your farm route..."
			: manager.getError() != null ? manager.getError()
			: !manager.isLoaded() ? "Loading your farm route..."
			: "No farm route yet. On the OSRS Companion website, open Tools > Farm Runs and build one for this character.";
		g.setFont(FontManager.getRunescapeFont());
		int lineY = win.y + win.height / 2 - 10;
		for (String line : wrap(message, g.getFontMetrics(), win.width - 80)) {
			centred(g, line, win.x + win.width / 2, lineY, Color.WHITE);
			lineY += 16;
		}
	}

	// ------------------------------------------------------------------ stops

	private void renderStops(Graphics2D g, FarmRoute route, Rectangle area, List<Hit> newHits) {
		panel(g, area);
		g.setFont(FontManager.getRunescapeBoldFont());
		text(g, "Stops", area.x + 10, area.y + 18, ORANGE);
		g.setFont(FontManager.getRunescapeSmallFont());
		String legend = manager.isRunning() ? "to do  done  nothing to do" : "ready  growing  not checked";
		int lx = area.x + area.width - 12 - g.getFontMetrics().stringWidth(legend);
		if (manager.isRunning()) {
			lx = text(g, "to do  ", lx, area.y + 17, YELLOW);
			lx = text(g, "done  ", lx, area.y + 17, GREEN);
			text(g, "nothing to do", lx, area.y + 17, GREY);
		} else {
			lx = text(g, "ready  ", lx, area.y + 17, GREEN);
			lx = text(g, "growing  ", lx, area.y + 17, GREY);
			text(g, "not checked", lx, area.y + 17, BEIGE);
		}

		Rectangle view = new Rectangle(area.x + 4, area.y + 26, area.width - 8, area.height - 30);
		List<FarmRoute.Stop> stops = route.getStops();
		int content = stops.size() * (ROW_HEIGHT + 2);
		maxScroll = Math.max(0, content - view.height);
		int offset = Math.min(scroll, maxScroll);
		int selected = manager.getSelectedStop();
		Shape oldClip = g.getClip();
		g.clip(view);
		for (int i = 0; i < stops.size(); i++) {
			FarmRoute.Stop stop = stops.get(i);
			Rectangle row = new Rectangle(view.x, view.y + i * (ROW_HEIGHT + 2) - offset, view.width - (maxScroll > 0 ? 6 : 0), ROW_HEIGHT);
			if (row.y + row.height < view.y || row.y > view.y + view.height) {
				continue;
			}
			boolean current = manager.isRunning() && i == manager.getStopIndex() && !manager.isComplete();
			boolean done = manager.isRunning() && stop.getPatches().stream().noneMatch(pt -> manager.statusOf(pt).isOpen());
			if (i == selected) {
				g.setColor(TAB_ON);
				g.fillRect(row.x, row.y, row.width, row.height);
			}
			if (current || i == selected) {
				g.setColor(current ? YELLOW : ORANGE);
				g.drawRect(row.x, row.y, row.width - 1, row.height - 1);
			}
			g.setFont(FontManager.getRunescapeFont());
			Color nameColor = current ? YELLOW : done ? GREEN : Color.WHITE;
			int nx = text(g, (i + 1) + ".", row.x + 6, row.y + 16, nameColor) + 5;
			// Patch icons on the right; the name takes what is left.
			int iconsW = Math.min(stop.getPatches().size(), 4) * 22;
			text(g, truncate(stop.getName(), g.getFontMetrics(), row.x + row.width - iconsW - 10 - nx), nx, row.y + 16, nameColor);
			int ix = row.x + row.width - 6 - iconsW;
			for (int p = 0; p < stop.getPatches().size() && p < 4; p++) {
				FarmRoute.Patch patch = stop.getPatches().get(p);
				patchIcon(g, patch, ix + p * 22, row.y + 4);
			}
			FarmRoute.Travel travel = stop.getTravel();
			g.setFont(FontManager.getRunescapeSmallFont());
			int tx = row.x + 22;
			if (!travel.getItems().isEmpty()) {
				BufferedImage icon = icon(travel.getItems().get(0), 1, false);
				if (icon != null) {
					g.drawImage(icon, tx - 2, row.y + 22, 20, 18, null);
					tx += 20;
				}
			}
			text(g, truncate((travel.isWalk() ? "" : travel.isSpell() ? "Cast " : "") + travel.getName(), g.getFontMetrics(), row.width - (tx - row.x) - 8), tx, row.y + 37, BEIGE);
			if (view.contains(row.x, Math.max(view.y, Math.min(row.y + row.height / 2, view.y + view.height - 1)))) {
				final int index = i;
				newHits.add(new Hit(row.intersection(view), () -> manager.selectStop(index), null));
			}
		}
		g.setClip(oldClip);
		if (maxScroll > 0) {
			int trackX = area.x + area.width - 7;
			g.setColor(PANEL_EDGE);
			g.fillRect(trackX, view.y, 3, view.height);
			int thumbH = Math.max(20, view.height * view.height / Math.max(1, content));
			int thumbY = view.y + (view.height - thumbH) * offset / Math.max(1, maxScroll);
			g.setColor(FRAME_LIGHT);
			g.fillRect(trackX, thumbY, 3, thumbH);
		}
	}

	/** A patch's seed with a coloured bar under it: its state in the run, or its ready state before. */
	private void patchIcon(Graphics2D g, FarmRoute.Patch patch, int x, int y) {
		if (patch.getSeed() != null && patch.getSeed() > 0) {
			BufferedImage icon = icon(patch.getSeed(), 1, false);
			if (icon != null) {
				g.drawImage(icon, x, y, 20, 18, null);
			}
		}
		Color c;
		if (manager.isRunning()) {
			Status s = manager.statusOf(patch);
			c = s == Status.DONE ? GREEN : s == Status.TODO ? YELLOW : GREY;
		} else {
			String r = patch.getReady();
			c = "ready".equals(r) ? GREEN : "diseased".equals(r) || "dead".equals(r) ? RED : "growing".equals(r) ? GREY : BEIGE;
		}
		g.setColor(Color.BLACK);
		g.fillRect(x + 1, y + 20, 19, 4);
		g.setColor(c);
		g.fillRect(x + 2, y + 21, 17, 2);
	}

	// ------------------------------------------------------------------ loadout

	private void renderLoadout(Graphics2D g, FarmRoute route, Rectangle area, int slot, List<Hit> newHits) {
		panel(g, area);
		int carried = 0;
		int total = 0;
		for (FarmRoute.Stack s : manager.loadout()) {
			total++;
			if (manager.carried(s.getItem()) >= s.getQty()) {
				carried++;
			}
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		text(g, "Bring", area.x + 10, area.y + 18, ORANGE);
		g.setFont(FontManager.getRunescapeSmallFont());
		String count = carried + " / " + total + " carried";
		text(g, count, area.x + area.width - 10 - g.getFontMetrics().stringWidth(count), area.y + 17, carried == total ? GREEN : BEIGE);

		int top = area.y + 26;
		int cell = slot + SLOT_GAP;
		// Worn equipment in the game's layout.
		Map<String, FarmRoute.Stack> worn = new LinkedHashMap<>();
		for (FarmRoute.Stack s : route.getEquipment()) {
			worn.putIfAbsent(s.getSlot(), s);
		}
		int ex = area.x + 10;
		int ey = top + cell; // a row lower so it sits beside the inventory's middle
		for (int r = 0; r < EQUIPMENT.length; r++) {
			for (int c = 0; c < 3; c++) {
				String name = EQUIPMENT[r][c];
				if (name == null) {
					continue;
				}
				Rectangle cellRect = new Rectangle(ex + c * cell, ey + r * cell, slot, slot);
				FarmRoute.Stack s = worn.get(name);
				drawItemSlot(g, cellRect, s, newHits);
			}
		}
		// Inventory: 4 x 7.
		int ix = ex + 3 * cell + 14;
		List<FarmRoute.Stack> inv = route.getInventory();
		for (int i = 0; i < 28; i++) {
			Rectangle cellRect = new Rectangle(ix + (i % 4) * cell, top + (i / 4) * cell, slot, slot);
			drawItemSlot(g, cellRect, i < inv.size() ? inv.get(i) : null, newHits);
		}
		if (inv.size() > 28) {
			g.setFont(FontManager.getRunescapeSmallFont());
			text(g, "+" + (inv.size() - 28) + " more", ix + 4 * cell + 4, top + 12, RED);
		}
		// Legend.
		g.setFont(FontManager.getRunescapeSmallFont());
		int ly = area.y + area.height - 6;
		int lx = area.x + 10;
		lx = text(g, "Carried  ", lx, ly, GREEN);
		lx = text(g, "Stored: ", lx, ly, STORED);
		lx = text(g, "B bank  V seed vault  S seed box  L tool leprechaun  ", lx, ly, BEIGE);
		text(g, "Missing", lx, ly, RED);
		int sideX = ix + 4 * cell + 6;
		int sideW = area.width - (sideX - area.x) - 8;
		if (route.getSpellbook() != null) {
			// Routes cast from the normal spellbook; red while you are on another one.
			boolean onNormal = client.getVarbitValue(VarbitID.SPELLBOOK) == 0;
			text(g, truncate("Spellbook: " + route.getSpellbook() + (onNormal ? "" : " (switch!)"), g.getFontMetrics(), sideW),
				sideX, top + 26, onNormal ? GREEN : RED);
		}
		if (!route.getRunes().isEmpty()) {
			text(g, truncate("Runes for: " + String.join(", ", route.getRunes()), g.getFontMetrics(), sideW), sideX, top + 40, BEIGE);
		}
	}

	/** One loadout slot: the item (with its quantity) and a border/badge saying whether you carry it or where it is. */
	private void drawItemSlot(Graphics2D g, Rectangle r, FarmRoute.Stack s, List<Hit> newHits) {
		box(g, r, SLOT, SLOT_EDGE, SLOT_INNER);
		if (s == null) {
			return;
		}
		BufferedImage icon = icon(s.getItem(), s.getQty(), s.getQty() > 1);
		if (icon != null) {
			int iw = Math.min(icon.getWidth(), r.width - 2);
			int ih = Math.min(icon.getHeight(), r.height - 2);
			g.drawImage(icon, r.x + (r.width - iw) / 2, r.y + (r.height - ih) / 2, iw, ih, null);
		}
		int carried = manager.carried(s.getItem());
		Map<String, Integer> where = whereStored(s);
		Color edge;
		String badge = null;
		if (carried >= s.getQty()) {
			edge = GREEN;
		} else if (!where.isEmpty()) {
			edge = STORED;
			String[] label = STORAGE.get(where.keySet().iterator().next());
			badge = label != null ? label[0] : "+";
		} else {
			edge = RED;
		}
		g.setColor(edge);
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		if (badge != null) {
			g.setFont(FontManager.getRunescapeSmallFont());
			text(g, badge, r.x + r.width - 8, r.y + r.height - 3, STORED);
		}
		newHits.add(new Hit(r, () -> { }, tooltipFor(s, carried, where)));
	}

	/** Where the item is besides your inventory: tool leprechaun (live), the badged storages, then any other storage. */
	private Map<String, Integer> whereStored(FarmRoute.Stack s) {
		Map<String, Integer> out = new LinkedHashMap<>();
		int lep = manager.inLeprechaun(s.getItem());
		if (lep > 0) {
			out.put("leprechaun", lep);
		}
		for (String key : STORAGE.keySet()) {
			Integer n = s.getWhere().get(key);
			if (n != null && n > 0) {
				out.put(key, n);
			}
		}
		for (Map.Entry<String, Integer> e : s.getWhere().entrySet()) {
			if (e.getValue() != null && e.getValue() > 0) {
				out.putIfAbsent(e.getKey(), e.getValue());
			}
		}
		return out;
	}

	private static String tooltipFor(FarmRoute.Stack s, int carried, Map<String, Integer> where) {
		StringBuilder sb = new StringBuilder(s.getName()).append(s.getQty() > 1 ? " x" + s.getQty() : "");
		sb.append(" - carrying ").append(carried);
		for (Map.Entry<String, Integer> e : where.entrySet()) {
			String[] label = STORAGE.get(e.getKey());
			sb.append(", ").append(label != null ? label[1] : e.getKey().replace('_', ' ')).append(' ').append(e.getValue());
		}
		if (carried < s.getQty() && where.isEmpty()) {
			sb.append(", not in your bank or storage");
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ details

	private void renderDetail(Graphics2D g, FarmRoute route, Rectangle area, List<Hit> newHits) {
		panel(g, area);
		int index = manager.getSelectedStop();
		if (index < 0 || area.height < 40) {
			return;
		}
		FarmRoute.Stop stop = route.getStops().get(index);
		int textW = area.width - BUTTON_WIDTH - 30;
		g.setFont(FontManager.getRunescapeBoldFont());
		text(g, truncate("Stop " + (index + 1) + " - " + stop.getName(), g.getFontMetrics(), textW), area.x + 10, area.y + 18, ORANGE);
		g.setFont(FontManager.getRunescapeFont());
		int lineY = area.y + 34;
		FarmRoute.Travel t = stop.getTravel();
		String via = (t.isWalk() ? "" : t.isSpell() ? "Cast " : "Use ") + t.getName() + (t.isSpell() ? " (normal spellbook)" : "")
			+ (t.getHow().isEmpty() ? "" : ": " + t.getHow());
		for (String line : wrap(via, g.getFontMetrics(), textW)) {
			if (lineY > area.y + area.height - 6) {
				break;
			}
			text(g, line, area.x + 10, lineY, CYAN);
			lineY += 15;
		}
		for (FarmRoute.Patch p : stop.getPatches()) {
			if (lineY > area.y + area.height - 6) {
				break;
			}
			Status s = manager.isRunning() ? manager.statusOf(p) : null;
			String state = s == null ? p.getReady().replace("unknown", "not checked") : s == Status.DONE ? "done" : s == Status.NOT_READY ? "nothing to do" : s == Status.SKIPPED ? "skipped" : "to do";
			Color c = s == Status.DONE || (s == null && "ready".equals(p.getReady())) ? GREEN : s == Status.TODO ? YELLOW : GREY;
			g.setFont(FontManager.getRunescapeFont());
			text(g, truncate(p.getType() + (p.getCrop() != null ? ": " + p.getCrop() : "") + " - " + state, g.getFontMetrics(), textW), area.x + 10, lineY, c);
			lineY += 15;
			if (s == null || s == Status.TODO) {
				g.setFont(FontManager.getRunescapeSmallFont());
				for (String step : FarmRunOverlay.stepsFor(p)) {
					if (lineY > area.y + area.height - 6) {
						break;
					}
					text(g, truncate("  " + step, g.getFontMetrics(), textW), area.x + 14, lineY, GREY);
					lineY += 13;
				}
			}
		}
		if (stop.getNote() != null && lineY <= area.y + area.height - 6) {
			g.setFont(FontManager.getRunescapeSmallFont());
			text(g, truncate(stop.getNote(), g.getFontMetrics(), textW), area.x + 10, lineY, ORANGE);
		}

		// Buttons on the right.
		int bx = area.x + area.width - BUTTON_WIDTH - 10;
		int by = area.y + 10;
		boolean running = manager.isRunning();
		boolean current = running && index == manager.getStopIndex();
		if (!running) {
			Rectangle start = new Rectangle(bx, by, BUTTON_WIDTH, BUTTON_HEIGHT);
			button(g, start, "Start run here", false);
			newHits.add(new Hit(start, () -> {
				manager.start();
				manager.goTo(index);
			}, "Start the run at this stop"));
		} else if (!current) {
			Rectangle go = new Rectangle(bx, by, BUTTON_WIDTH, BUTTON_HEIGHT);
			button(g, go, "Go to this stop", false);
			newHits.add(new Hit(go, () -> manager.goTo(index), "Make this the current stop"));
		} else if (!manager.isComplete()) {
			Rectangle skip = new Rectangle(bx, by, BUTTON_WIDTH, BUTTON_HEIGHT);
			button(g, skip, "Skip this stop", false);
			newHits.add(new Hit(skip, manager::skipStop, "Leave these patches for next time"));
			by += BUTTON_HEIGHT + 6;
			if (index > 0) {
				Rectangle back = new Rectangle(bx, by, BUTTON_WIDTH, BUTTON_HEIGHT);
				button(g, back, "Back a stop", false);
				newHits.add(new Hit(back, manager::previous, null));
			}
		}
		Rectangle refresh = new Rectangle(bx, area.y + area.height - BUTTON_HEIGHT - 10, BUTTON_WIDTH, BUTTON_HEIGHT);
		button(g, refresh, manager.isLoading() ? "Loading..." : "Refresh", manager.isLoading());
		newHits.add(new Hit(refresh, manager::refresh, "Reload the route and ready times from the website"));
	}

	private void finish(Graphics2D g, Rectangle win, List<Hit> newHits, Rectangle stops) {
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
		stopsArea = stops;
		hits = Collections.unmodifiableList(newHits);
	}

	/** Package-private so a preview harness can supply images without a game client. */
	BufferedImage icon(int itemId, int quantity, boolean stackable) {
		return itemManager.getImage(itemId, quantity, stackable);
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
			if (listWasOpen && switcherOpen && (clicked == null || clicked.tooltip == null || !clicked.tooltip.startsWith("Switch"))) {
				switcherOpen = false;
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
		if (!inside(e)) {
			return e;
		}
		Rectangle area = stopsArea;
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
			if (switcherOpen) {
				switcherOpen = false;
			} else {
				manager.close();
			}
			e.consume();
		}
	}

	@Override
	public void keyReleased(KeyEvent e) {
	}
}
