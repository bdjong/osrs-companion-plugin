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
		if (!manager.isOpen()) {
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
			renderSwitcher(g, switchButton, newHits);
		}
		finish(g, win, newHits);
		return new Dimension(w, h);
	}

	/** Boss and style (with a switcher), and how much of it you carry. Returns the switch button. */
	private Rectangle subtitle(Graphics2D g, BossGear set, Rectangle win, List<Hit> newHits) {
		int subY = win.y + TITLE_HEIGHT + 16;
		g.setFont(FontManager.getRunescapeFont());
		int after = text(g, truncate(set.getBossName(), g.getFontMetrics(), win.width / 3), win.x + 12, subY, Color.WHITE);
		g.setFont(FontManager.getRunescapeSmallFont());
		after = text(g, truncate("  " + set.getStyle(), g.getFontMetrics(), win.width / 4), after, subY, BEIGE);
		Rectangle switchButton = null;
		if (manager.getSets().size() > 1) {
			switchButton = new Rectangle(after + 8, subY - 13, 62, 17);
			button(g, switchButton, switcherOpen ? "Close" : "Switch", false);
			newHits.add(new Hit(switchButton, () -> switcherOpen = !switcherOpen, "Switch to another saved setup"));
		} else {
			switcherOpen = false;
		}
		int[] c = carriedCount(set);
		String info = c[0] + " / " + c[1] + " carried";
		g.setFont(FontManager.getRunescapeFont());
		text(g, info, win.x + win.width - 12 - g.getFontMetrics().stringWidth(info), subY, c[0] == c[1] ? GREEN : Color.WHITE);
		return switchButton;
	}

	/** Items carried in full / all different items of the set. */
	private int[] carriedCount(BossGear set) {
		int carried = 0;
		int total = 0;
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		Map<Integer, Integer> needs = manager.inventoryNeeds();
		for (BossGear.Item it : BossGearManager.items(set)) {
			if (!seen.add(it.getItem())) {
				continue;
			}
			total++;
			int need = it instanceof BossGear.Slot ? 1 : needs.getOrDefault(it.getItem(), it.getQty());
			if (manager.carried(it) >= need) {
				carried++;
			}
		}
		return new int[]{carried, total};
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
		for (String line : wrap("Open your bank with this window open: it shows only these items, laid out like your gear and inventory.",
			g.getFontMetrics(), textW)) {
			if (ly > area.y + area.height - BUTTON_HEIGHT - 16) {
				break;
			}
			text(g, line, lx, ly, GREY);
			ly += 13;
		}
		Rectangle refresh = new Rectangle(area.x + (area.width - BUTTON_WIDTH) / 2, area.y + area.height - BUTTON_HEIGHT - 10, BUTTON_WIDTH, BUTTON_HEIGHT);
		button(g, refresh, manager.isLoading() ? "Loading..." : "Refresh", manager.isLoading());
		newHits.add(new Hit(refresh, manager::refresh, "Reload the setup from the website"));
	}

	private void renderSwitcher(Graphics2D g, Rectangle anchor, List<Hit> newHits) {
		List<BossGear.SetSummary> sets = manager.getSets();
		g.setFont(FontManager.getRunescapeFont());
		int rowH = 18;
		Rectangle list = new Rectangle(anchor.x, anchor.y + anchor.height + 2, 260, sets.size() * rowH + 6);
		box(g, list, BG, FRAME_DARK, BUTTON_INNER);
		List<Hit> optionHits = new ArrayList<>();
		int rowY = list.y + 3;
		for (BossGear.SetSummary s : sets) {
			Rectangle row = new Rectangle(list.x + 3, rowY, list.width - 6, rowH);
			Point m = mouse;
			if (m != null && row.contains(m)) {
				g.setColor(TAB_ON);
				g.fillRect(row.x, row.y, row.width, row.height);
			}
			text(g, truncate(s.getName(), g.getFontMetrics(), row.width - 50), row.x + 6, row.y + 13, s.isActive() ? ORANGE : Color.WHITE);
			if (s.isActive()) {
				g.setFont(FontManager.getRunescapeSmallFont());
				text(g, "active", row.x + row.width - 38, row.y + 13, GREEN);
				g.setFont(FontManager.getRunescapeFont());
			} else {
				optionHits.add(new Hit(row, () -> {
					switcherOpen = false;
					manager.activate(s);
				}, null));
			}
			rowY += rowH;
		}
		optionHits.add(new Hit(list, () -> { }, null));
		newHits.addAll(0, optionHits);
	}

	private void message(Graphics2D g, Rectangle win) {
		String message = manager.isLoading() || !manager.isLoaded() && manager.getError() == null ? "Loading your boss gear..."
			: manager.getError() != null ? manager.getError()
			: "No boss gear picked yet. On the OSRS Companion website, open Tools > Boss Gear, pick a boss and setup and press Use in RuneLite.";
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
		StringBuilder tip = new StringBuilder(it.getName());
		if (it.getQty() > 1) {
			tip.append(" x").append(it.getQty());
		}
		if (it.isNoted()) {
			tip.append(" (noted)");
		}
		if (it instanceof BossGear.Slot) {
			BossGear.Slot s = (BossGear.Slot) it;
			tip.append("bis".equals(s.getStatus()) ? " - best in slot" : "partial".equals(s.getStatus()) ? " - best you own (rank " + s.getRank() + ")"
				: "missing".equals(s.getStatus()) ? " - best in slot, not owned" : "");
		}
		tip.append(" - carrying ").append(carried);
		if (carried < want) {
			tip.append(it.isOwned() ? ", in your bank or storage" : synced ? ", not in your bank" : "");
		}
		newHits.add(new Hit(r, () -> { }, tip.toString()));
	}

	private void finish(Graphics2D g, Rectangle win, List<Hit> newHits) {
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
		if (inside(e)) {
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
