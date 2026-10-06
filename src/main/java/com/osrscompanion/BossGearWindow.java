package com.osrscompanion;

import static com.osrscompanion.GameUi.*;

import com.osrscompanion.model.BossGear;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The boss gear window, drawn over the game like the farm run window: the active set's boss and
 * style (with a switcher), the worn equipment in the game's layout, the 28 inventory slots and the
 * rune pouch, each slot coloured by whether you carry it, have it stored, or miss it.
 */
@Singleton
public class BossGearWindow extends Overlay implements MouseListener, MouseWheelListener, KeyListener {
	private static final int MAX_WIDTH = 640;
	private static final int MAX_HEIGHT = 440;
	private static final int MIN_WIDTH = 480;
	private static final int MIN_HEIGHT = 320;
	private static final int SCREEN_MARGIN = 10;
	private static final int TITLE_HEIGHT = 28;
	private static final int SUBTITLE_HEIGHT = 22;
	private static final int SLOT_GAP = 3;
	private static final int BUTTON_WIDTH = 110;
	private static final int BUTTON_HEIGHT = 20;
	private static final Color STORED = new Color(0xFF981F);

	/** Worn-equipment layout as in the game: 3 columns, null = no slot there. */
	static final String[][] EQUIPMENT = {
		{null, "head", null}, {"cape", "neck", "ammo"}, {"weapon", "body", "shield"}, {null, "legs", null}, {"hands", "feet", "ring"},
	};

	private final Client client;
	private final BossGearManager manager;
	private final ItemManager itemManager;

	private volatile List<Hit> hits = Collections.emptyList();
	private volatile Rectangle window;
	private volatile Point mouse;
	private volatile boolean switcherOpen;
	/** In the switcher: first row shown (mouse wheel scrolls) and the boss whose setups are unfolded. */
	private volatile int switcherScroll;
	private volatile String expandedBoss;
	private volatile Rectangle switcherArea;
	/** The switcher's tab: {@link #TAB_RECENT}, {@link #TAB_WIKI} or {@link #TAB_CUSTOM}. */
	private volatile int tab;
	private static final int TAB_RECENT = 0;
	private static final int TAB_WIKI = 1;
	private static final int TAB_CUSTOM = 2;
	/** How many builds the Recently used tab lists. */
	private static final int RECENT_MAX = 10;

	private static final class Hit {
		final Rectangle area;
		final Runnable action;
		final String tooltip;
		/** Clicking it leaves the switcher list open (tabs, unfolding a boss, the list itself). */
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

	@Inject
	public BossGearWindow(Client client, BossGearManager manager, ItemManager itemManager) {
		this.client = client;
		this.manager = manager;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	// ------------------------------------------------------------------ render

	@Override
	public Dimension render(Graphics2D g) {
		// Out of the way while the bank is open (it is laid out underneath).
		if (client.getWidget(InterfaceID.Bankmain.UNIVERSE) != null || !manager.isOpen()) {
			window = null;
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
		centred(g, "Boss Gear", x + w / 2, y + 20, ORANGE);
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

		BossGear set = manager.getSet();
		if (set == null) {
			message(g, win);
			Rectangle pick = null;
			if (!manager.getCatalog().isEmpty()) {
				pick = new Rectangle(x + (w - 120) / 2, y + h / 2 + 30, 120, BUTTON_HEIGHT);
				button(g, pick, switcherOpen ? "Close" : "Pick a build", false);
				newHits.add(new Hit(pick, this::toggleSwitcher, "Load a wiki preset or one of your custom builds", true));
			}
			if (switcherOpen && pick != null) {
				renderSwitcher(g, new Rectangle(x + (w - 300) / 2, y + TITLE_HEIGHT + 8, 300, 0), win, newHits);
			}
			finish(g, win, newHits);
			return new Dimension(w, h);
		}

		Rectangle switchButton = subtitle(g, set, win, newHits);
		int bodyY = y + TITLE_HEIGHT + SUBTITLE_HEIGHT + 6;
		int bodyBottom = y + h - 10;
		int bodyH = bodyBottom - bodyY;
		int slot = Math.max(26, Math.min(40, (bodyH - 52) / 7 - SLOT_GAP));
		int cell = slot + SLOT_GAP;

		// Worn equipment (and the special attack weapon) on the left.
		Rectangle worn = new Rectangle(x + 8, bodyY, 3 * cell + 20, bodyH);
		panel(g, worn);
		g.setFont(FontManager.getRunescapeBoldFont());
		text(g, "Worn", worn.x + 10, worn.y + 18, ORANGE);
		int ex = worn.x + 10;
		int ey = worn.y + 26;
		for (int r = 0; r < EQUIPMENT.length; r++) {
			for (int c = 0; c < 3; c++) {
				String name = EQUIPMENT[r][c];
				if (name != null) {
					drawSlot(g, new Rectangle(ex + c * cell, ey + r * cell, slot, slot), set.slot(name), 0, set.isSynced(), newHits);
				}
			}
		}
		BossGear.Slot special = set.slot("special");
		if (special != null && ey + 5 * cell + 18 + slot <= worn.y + worn.height) {
			g.setFont(FontManager.getRunescapeSmallFont());
			text(g, "Special attack", ex, ey + 5 * cell + 12, BEIGE);
			drawSlot(g, new Rectangle(ex + cell, ey + 5 * cell + 16, slot, slot), special, 0, set.isSynced(), newHits);
		}

		// Inventory: 4 x 7.
		Rectangle invArea = new Rectangle(worn.x + worn.width + 8, bodyY, 4 * cell + 20, bodyH);
		panel(g, invArea);
		g.setFont(FontManager.getRunescapeBoldFont());
		text(g, "Inventory", invArea.x + 10, invArea.y + 18, ORANGE);
		List<BossGear.Item> inv = set.getInventory();
		Map<Integer, Integer> needs = manager.inventoryNeeds();
		for (int i = 0; i < 28; i++) {
			Rectangle r = new Rectangle(invArea.x + 10 + (i % 4) * cell, invArea.y + 26 + (i / 4) * cell, slot, slot);
			BossGear.Item it = i < inv.size() ? inv.get(i) : null;
			drawSlot(g, r, it, it == null ? 0 : needs.getOrDefault(it.getItem(), it.getQty()), set.isSynced(), newHits);
		}
		if (inv.isEmpty()) {
			g.setFont(FontManager.getRunescapeSmallFont());
			for (String line : wrap("The wiki has no inventory for this setup.", g.getFontMetrics(), invArea.width - 20)) {
				text(g, line, invArea.x + 10, invArea.y + bodyH / 2, BEIGE);
			}
		}

		// Rune pouch, legend and buttons on the right.
		Rectangle side = new Rectangle(invArea.x + invArea.width + 8, bodyY, x + w - 8 - (invArea.x + invArea.width + 8), bodyH);
		renderSide(g, set, side, slot, needs, newHits);

		if (switcherOpen && switchButton != null) {
			renderSwitcher(g, switchButton, win, newHits);
		}
		finish(g, win, newHits);
		return new Dimension(w, h);
	}

	/**
	 * Boss, build and where it comes from (wiki preset / custom build), the button to change it, and
	 * how much of it you carry (or that it is Inactive, with a button to set it active). Returns the button.
	 */
	private Rectangle subtitle(Graphics2D g, BossGear set, Rectangle win, List<Hit> newHits) {
		int subY = win.y + TITLE_HEIGHT + 16;
		BossGear.SetSummary inUse = inUse(set);
		boolean custom = inUse != null && inUse.isCustom();
		g.setFont(FontManager.getRunescapeFont());
		int after = text(g, truncate(set.getBossName(), g.getFontMetrics(), Math.min(170, win.width / 4)), win.x + 12, subY, Color.WHITE);
		g.setFont(FontManager.getRunescapeSmallFont());
		String label = custom && inUse.getLabel() != null ? inUse.getLabel() : set.getStyle();
		after = text(g, truncate("  " + label, g.getFontMetrics(), 120), after, subY, BEIGE);
		badge(g, custom ? "Custom build" : "Wiki preset", after + 8, subY, custom ? YELLOW : BEIGE);
		// Right side: Change build, with Inactive and Set active before it while the setup is Inactive.
		int right = win.x + win.width - 12;
		Rectangle switchButton = new Rectangle(right - 84, subY - 13, 84, 17);
		button(g, switchButton, switcherOpen ? "Close" : "Change build", false);
		newHits.add(new Hit(switchButton, this::toggleSwitcher, "Load a wiki preset or one of your custom builds", true));
		if (!manager.isSetActive()) {
			Rectangle activate = new Rectangle(switchButton.x - 8 - 76, subY - 13, 76, 17);
			button(g, activate, "Set active", false);
			newHits.add(new Hit(activate, () -> manager.setSetActive(true), "Lay out the bank for this setup again"));
			badge(g, "Inactive", activate.x - 6 - 54, subY, ORANGE);
		}
		return switchButton;
	}

	/** A small labelled box on the subtitle line (Wiki preset, Custom build, Inactive). Returns its right edge. */
	private static int badge(Graphics2D g, String label, int x, int baseline, Color color) {
		g.setFont(FontManager.getRunescapeSmallFont());
		int bw = Math.max(54, g.getFontMetrics().stringWidth(label) + 10);
		box(g, new Rectangle(x, baseline - 12, bw, 15), PANEL, PANEL_EDGE, PANEL_INNER);
		centred(g, label, x + bw / 2, baseline - 1, color);
		return x + bw;
	}

	/** The saved set the loaded build is (for its name and whether it is a custom build), or null. */
	private BossGear.SetSummary inUse(BossGear set) {
		if (set == null) {
			return null;
		}
		for (BossGear.SetSummary s : manager.getSets()) {
			if (s.getId() == set.getId()) {
				return s;
			}
		}
		return null;
	}

	/** Opens the switcher on Recently used (the wiki presets when you used none yet), or closes it. */
	private void toggleSwitcher() {
		if (switcherOpen) {
			switcherOpen = false;
			return;
		}
		tab = manager.getSets().isEmpty() ? TAB_WIKI : TAB_RECENT;
		switcherScroll = 0;
		switcherOpen = true;
	}

	private void renderSide(Graphics2D g, BossGear set, Rectangle area, int slot, Map<Integer, Integer> needs, List<Hit> newHits) {
		panel(g, area);
		int lx = area.x + 10;
		int ly = area.y + 18;
		if (!set.getPouch().isEmpty()) {
			g.setFont(FontManager.getRunescapeBoldFont());
			text(g, "Rune pouch", lx, ly, ORANGE);
			int px = lx;
			for (BossGear.Item it : set.getPouch()) {
				if (px + slot > area.x + area.width - 6) {
					break;
				}
				drawSlot(g, new Rectangle(px, ly + 8, slot, slot), it, needs.getOrDefault(it.getItem(), it.getQty()), set.isSynced(), newHits);
				px += slot + SLOT_GAP;
			}
			ly += slot + 26;
		}
		g.setFont(FontManager.getRunescapeSmallFont());
		int textW = area.width - 20;
		if (set.getInventoryLabel() != null) {
			for (String line : wrap("Inventory: " + set.getInventoryLabel(), g.getFontMetrics(), textW)) {
				text(g, line, lx, ly, BEIGE);
				ly += 13;
			}
			ly += 4;
		}
		text(g, "Carried", lx, ly, GREEN);
		ly += 13;
		text(g, set.isSynced() ? "In your bank or storage (B)" : "Not synced: sync your bank", lx, ly, set.isSynced() ? STORED : BEIGE);
		ly += 13;
		text(g, "Missing", lx, ly, RED);
		ly += 18;
		String hint = manager.isSetActive() ? "Open your bank with this window open: it shows only these items, laid out like your gear and inventory."
			: "Inactive: the bank is left alone. Press Set active to lay it out.";
		for (String line : wrap(hint, g.getFontMetrics(), textW)) {
			if (ly > area.y + area.height - BUTTON_HEIGHT - 16) {
				break;
			}
			text(g, line, lx, ly, GREY);
			ly += 13;
		}
		Rectangle refresh = new Rectangle(area.x + (area.width - BUTTON_WIDTH) / 2, area.y + area.height - BUTTON_HEIGHT - 10, BUTTON_WIDTH, BUTTON_HEIGHT);
		button(g, refresh, manager.isLoading() ? "Loading..." : "Refresh", manager.isLoading());
		newHits.add(new Hit(refresh, manager::refresh, "Reload your builds from the website"));
	}

	/** One line of the switcher: a heading, a build, a boss (unfolds) or one of its setups. */
	private static final class Row {
		final String text;
		final Color color;
		final int indent;
		final Runnable action;
		final boolean keepOpen;
		final String note;
		final Color noteColor;

		Row(String text, Color color, int indent, Runnable action, boolean keepOpen, String note, Color noteColor) {
			this.text = text;
			this.color = color;
			this.indent = indent;
			this.action = action;
			this.keepOpen = keepOpen;
			this.note = note;
			this.noteColor = noteColor;
		}
	}

	private static final int TAB_BAR = 25;
	private static final int SWITCHER_WIDTH = 300;
	private static final String CUSTOM_FOOTER = "Custom builds are edited on the website (Tools > Boss Gear > My sets). Press Refresh to load changes.";
	private static final String CUSTOM_EMPTY = "On the website, open Tools > Boss Gear and pick a boss. Under Wiki sets, choose a setup and press "
		+ "Copy to my sets. Edit your copy there, then press Refresh here to load it.";

	/** A build's row: "In use" when it is the loaded one (Inactive: clicking sets it active), else clicking loads it (and makes it Active). */
	private Row buildRow(String text, int indent, Color color, boolean loaded, Runnable load) {
		if (loaded && manager.isSetActive()) {
			return new Row(text, ORANGE, indent, null, false, "In use", GREEN);
		}
		if (loaded) {
			return new Row(text, ORANGE, indent, () -> manager.setSetActive(true), false, "Inactive", ORANGE);
		}
		return new Row(text, color, indent, () -> {
			manager.setSetActive(true);
			load.run();
		}, false, null, null);
	}

	/** Recently used: the last builds you loaded (wiki presets and custom builds), newest first. */
	private List<Row> recentRows() {
		List<Row> rows = new ArrayList<>();
		BossGear set = manager.getSet();
		for (BossGear.SetSummary s : manager.getSets()) {
			if (rows.size() >= RECENT_MAX) {
				break;
			}
			Row row = buildRow(s.getName(), 8, Color.WHITE, set != null && set.getId() == s.getId(), () -> manager.activate(s));
			// Says which kind it is, unless the row already says In use / Inactive.
			rows.add(row.note != null ? row
				: new Row(row.text, row.color, row.indent, row.action, row.keepOpen, s.isCustom() ? "Custom" : "Wiki", s.isCustom() ? YELLOW : GREY));
		}
		return rows;
	}

	/** Wiki presets: every boss (click one to unfold its setups). */
	private List<Row> presetRows() {
		List<Row> rows = new ArrayList<>();
		BossGear set = manager.getSet();
		List<BossGear.CatalogBoss> catalog = manager.getCatalog();
		if (!catalog.isEmpty()) {
			BossGear.SetSummary inUse = inUse(set);
			boolean presetInUse = set != null && (inUse == null || !inUse.isCustom());
			for (BossGear.CatalogBoss b : catalog) {
				boolean open = b.getKey().equals(expandedBoss);
				rows.add(new Row((open ? "- " : "+ ") + b.getName(), Color.WHITE, 8, () -> expandedBoss = open ? null : b.getKey(), true,
					b.getSetups().size() + (b.getSetups().size() == 1 ? " setup" : " setups"), GREY));
				if (open) {
					for (int i = 0; i < b.getSetups().size(); i++) {
						final int setup = i;
						String style = b.getSetups().get(i);
						boolean loaded = presetInUse && b.getKey().equals(set.getBoss()) && style.equals(set.getStyle());
						rows.add(buildRow(style, 22, BEIGE, loaded, () -> {
							expandedBoss = null;
							manager.activatePreset(b, setup);
						}));
					}
				}
			}
		}
		return rows;
	}

	/** Your own builds (made and edited on the website). */
	private List<Row> customRows() {
		List<Row> rows = new ArrayList<>();
		BossGear set = manager.getSet();
		for (BossGear.SetSummary s : manager.getSets()) {
			if (s.isCustom()) {
				rows.add(buildRow(s.getName(), 8, Color.WHITE, set != null && set.getId() == s.getId(), () -> manager.activate(s)));
			}
		}
		return rows;
	}

	private void renderSwitcher(Graphics2D g, Rectangle anchor, Rectangle win, List<Hit> newHits) {
		int shownTab = tab;
		boolean custom = shownTab == TAB_CUSTOM;
		List<Row> rows = custom ? customRows() : shownTab == TAB_RECENT ? recentRows() : presetRows();
		int customCount = 0;
		for (BossGear.SetSummary s : manager.getSets()) {
			customCount += s.isCustom() ? 1 : 0;
		}
		int rowH = 18;
		int x = Math.min(anchor.x, win.x + win.width - 10 - SWITCHER_WIDTH);
		int top = anchor.y + anchor.height + 2;
		g.setFont(FontManager.getRunescapeSmallFont());
		List<String> footer = custom ? wrap(rows.isEmpty() ? CUSTOM_EMPTY : CUSTOM_FOOTER, g.getFontMetrics(), rows.isEmpty() ? 270 : 288)
			: Collections.emptyList();
		int footerH = rows.isEmpty() ? 0 : footer.isEmpty() ? 0 : footer.size() * 13 + 8;
		int maxRows = Math.max(3, (win.y + win.height - 12 - top - 6 - TAB_BAR - footerH) / rowH);
		int shown = Math.min(rows.size(), maxRows);
		int maxScroll = Math.max(0, rows.size() - shown);
		int scroll = Math.max(0, Math.min(switcherScroll, maxScroll));
		switcherScroll = scroll;
		int bodyH = rows.isEmpty() ? Math.max(90, 34 + footer.size() * 13) : shown * rowH;
		Rectangle list = new Rectangle(x, top, SWITCHER_WIDTH, TAB_BAR + bodyH + footerH + 6);
		switcherArea = list;
		box(g, list, BG, FRAME_DARK, BUTTON_INNER);
		List<Hit> optionHits = new ArrayList<>();

		// The tabs: recently used | wiki presets | your custom builds.
		Point m = mouse;
		String[] tabs = {"Recently used", "Wiki presets", "Custom builds (" + customCount + ")"};
		for (int t = 0; t < tabs.length; t++) {
			boolean on = t == shownTab;
			Rectangle tabArea = new Rectangle(list.x + 3 + t * 98, list.y + 3, 98, 22);
			g.setColor(on ? TAB_ON : PANEL);
			g.fillRect(tabArea.x, tabArea.y, tabArea.width, tabArea.height);
			g.setFont(FontManager.getRunescapeSmallFont());
			centred(g, tabs[t], tabArea.x + tabArea.width / 2, tabArea.y + 15, on ? ORANGE : GREY);
			final int to = t;
			optionHits.add(new Hit(tabArea, () -> {
				tab = to;
				switcherScroll = 0;
			}, null, true));
		}
		g.setColor(PANEL_EDGE);
		g.drawLine(list.x + 3, list.y + TAB_BAR, list.x + list.width - 4, list.y + TAB_BAR);

		int rowY = list.y + TAB_BAR + 3;
		if (rows.isEmpty()) {
			if (custom) {
				g.setFont(FontManager.getRunescapeBoldFont());
				centred(g, "No custom builds yet", list.x + list.width / 2, rowY + 17, Color.WHITE);
				g.setFont(FontManager.getRunescapeSmallFont());
				int ly = rowY + 34;
				for (String line : footer) {
					centred(g, line, list.x + list.width / 2, ly, BEIGE);
					ly += 13;
				}
			} else {
				g.setFont(FontManager.getRunescapeFont());
				String empty = manager.isLoading() ? "Loading..." : shownTab == TAB_RECENT ? "No builds used yet: pick one under Wiki presets"
					: "No bosses loaded: press Refresh";
				centred(g, empty, list.x + list.width / 2, rowY + 40, BEIGE);
			}
		}
		for (int i = scroll; i < scroll + shown; i++) {
			Row r = rows.get(i);
			Rectangle row = new Rectangle(list.x + 3, rowY, list.width - 6 - (maxScroll > 0 ? 6 : 0), rowH);
			if (r.action != null && m != null && row.contains(m)) {
				g.setColor(TAB_ON);
				g.fillRect(row.x, row.y, row.width, row.height);
			}
			g.setFont(r.action == null && r.indent == 0 ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
			text(g, truncate(r.text, g.getFontMetrics(), row.width - r.indent - 70), row.x + 6 + r.indent, row.y + 13, r.color);
			if (r.note != null) {
				g.setFont(FontManager.getRunescapeSmallFont());
				text(g, r.note, row.x + row.width - 6 - g.getFontMetrics().stringWidth(r.note), row.y + 13, r.noteColor);
			}
			if (r.action != null) {
				optionHits.add(new Hit(row, r.action, null, r.keepOpen));
			}
			rowY += rowH;
		}
		if (maxScroll > 0) {
			// A thin scroll bar: the mouse wheel moves the list.
			int barH = Math.max(12, bodyH * shown / rows.size());
			int barY = list.y + TAB_BAR + 3 + (bodyH - barH) * scroll / maxScroll;
			g.setColor(PANEL_EDGE);
			g.fillRect(list.x + list.width - 6, barY, 3, barH);
		}
		if (!rows.isEmpty() && !footer.isEmpty()) {
			int fy = list.y + TAB_BAR + 3 + bodyH + 4;
			g.setColor(PANEL_EDGE);
			g.drawLine(list.x + 3, fy, list.x + list.width - 4, fy);
			g.setFont(FontManager.getRunescapeSmallFont());
			fy += 14;
			for (String line : footer) {
				text(g, line, list.x + 8, fy, GREY);
				fy += 13;
			}
		}
		g.setFont(FontManager.getRunescapeFont());
		optionHits.add(new Hit(list, () -> { }, null, true));
		newHits.addAll(0, optionHits);
	}

	private void message(Graphics2D g, Rectangle win) {
		String message = manager.isLoading() || !manager.isLoaded() && manager.getError() == null ? "Loading your boss gear..."
			: manager.getError() != null ? manager.getError()
			: "No build loaded yet. Press Pick a build to load a wiki preset, or make a custom build on the website (Tools > Boss Gear > My sets).";
		g.setFont(FontManager.getRunescapeFont());
		int lineY = win.y + win.height / 2 - 10;
		for (String line : wrap(message, g.getFontMetrics(), win.width - 80)) {
			centred(g, line, win.x + win.width / 2, lineY, Color.WHITE);
			lineY += 16;
		}
	}

	/**
	 * One slot: the item (with its stack size) and a border saying whether you carry it (green), have it in
	 * your bank or storage at the last sync (orange, "B"), or miss it (red). {@code need}: how many the
	 * whole set needs of it (0: one).
	 */
	private void drawSlot(Graphics2D g, Rectangle r, BossGear.Item it, int need, boolean synced, List<Hit> newHits) {
		box(g, r, SLOT, SLOT_EDGE, SLOT_INNER);
		if (it == null) {
			return;
		}
		BufferedImage icon = icon(it.getItem(), it.getQty(), it.getQty() > 1);
		if (icon != null) {
			int iw = Math.min(icon.getWidth(), r.width - 2);
			int ih = Math.min(icon.getHeight(), r.height - 2);
			g.drawImage(icon, r.x + (r.width - iw) / 2, r.y + (r.height - ih) / 2, iw, ih, null);
		}
		int carried = manager.carried(it);
		int want = Math.max(1, need);
		Color edge;
		String badge = null;
		if (carried >= want) {
			edge = GREEN;
		} else if (it.isOwned()) {
			edge = STORED;
			badge = "B";
		} else {
			edge = synced ? RED : BEIGE;
		}
		g.setColor(edge);
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		g.setFont(FontManager.getRunescapeSmallFont());
		if (badge != null) {
			text(g, badge, r.x + r.width - 8, r.y + r.height - 3, STORED);
		}
		if (it.isNoted()) {
			text(g, "N", r.x + 2, r.y + r.height - 3, YELLOW);
		}
		newHits.add(new Hit(r, () -> { }, manager.slotTooltip(it, need, synced)));
	}

	private void finish(Graphics2D g, Rectangle win, List<Hit> newHits) {
		Point m = mouse;
		Rectangle list = switcherArea;
		// No tooltips over the open switcher list (its rows say what they do).
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
			if (listWasOpen && switcherOpen && (clicked == null || !clicked.keepOpen)) {
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
		if (inside(e)) {
			Rectangle list = switcherArea;
			if (switcherOpen && list != null && list.contains(e.getPoint())) {
				switcherScroll = Math.max(0, switcherScroll + e.getWheelRotation() * 3);
			}
			e.consume();
		}
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
