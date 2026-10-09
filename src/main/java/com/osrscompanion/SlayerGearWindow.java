package com.osrscompanion;

import static com.osrscompanion.GameUi.*;

import com.osrscompanion.model.SlayerGear;
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
import java.util.Locale;
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
 * The Slayer gear window, drawn over the game like the boss gear window: the website's Gear &amp; DPS
 * calculator's setups against the task's monster as tabs (one per style, the best marked), the picked
 * one's worn equipment in the game's layout (carried green, in the bank orange "B") and what else it
 * counts on (potion, prayer, style or spell, darts). The picked setup is what the side panel shows and
 * what the bank lays out with the Slayer gear bank layout on.
 */
@Singleton
public class SlayerGearWindow extends Overlay implements MouseListener, MouseWheelListener, KeyListener {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "slayer-gear-window";

	private static final int MAX_WIDTH = 520;
	private static final int MAX_HEIGHT = 400;
	private static final int MIN_WIDTH = 400;
	private static final int MIN_HEIGHT = 320;
	private static final int SCREEN_MARGIN = 10;
	private static final int TITLE_HEIGHT = 28;
	private static final int SUBTITLE_HEIGHT = 22;
	private static final int TAB_HEIGHT = 32;
	private static final int SLOT_GAP = 3;
	private static final int BUTTON_WIDTH = 110;
	private static final int BUTTON_HEIGHT = 20;
	private static final Color STORED = new Color(0xFF981F);

	private final Client client;
	private final SlayerHelperManager manager;
	private final ItemManager itemManager;
	private final OSRSCompanionConfig config;

	private volatile List<Hit> hits = Collections.emptyList();
	private volatile Rectangle window;
	private volatile Point mouse;

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
	public SlayerGearWindow(Client client, SlayerHelperManager manager, ItemManager itemManager, OSRSCompanionConfig config) {
		this.client = client;
		this.manager = manager;
		this.itemManager = itemManager;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	// ------------------------------------------------------------------ render

	@Override
	public Dimension render(Graphics2D g) {
		return Guard.render(GUARD_FEATURE, () -> {
			// Out of the way while the bank is open (it is laid out underneath).
			if (client.getWidget(InterfaceID.Bankmain.UNIVERSE) != null || !manager.isGearOpen()) {
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
			centred(g, "Slayer Gear", x + w / 2, y + 20, ORANGE);
			Rectangle close = new Rectangle(x + w - 28, y + 6, 20, 18);
			box(g, close, BG, PANEL_EDGE, BUTTON_INNER);
			g.setColor(new Color(0xC8A24A));
			g.setStroke(new BasicStroke(2));
			g.drawLine(close.x + 6, close.y + 5, close.x + 14, close.y + 13);
			g.drawLine(close.x + 14, close.y + 5, close.x + 6, close.y + 13);
			g.setStroke(new BasicStroke(1));
			newHits.add(new Hit(close, manager::closeGearWindow, null));
			g.setColor(PANEL_EDGE);
			g.drawLine(x + 8, y + TITLE_HEIGHT, x + w - 9, y + TITLE_HEIGHT);
			g.setColor(FRAME_LIGHT);
			g.drawLine(x + 8, y + TITLE_HEIGHT + 1, x + w - 9, y + TITLE_HEIGHT + 1);

			SlayerGear gear = manager.getGear();
			SlayerGear.Set set = manager.getGearSet();
			if (set == null) {
				message(g, win, gear);
				Rectangle refresh = new Rectangle(x + (w - BUTTON_WIDTH) / 2, y + h / 2 + 30, BUTTON_WIDTH, BUTTON_HEIGHT);
				button(g, refresh, manager.isGearLoading() ? "Loading..." : "Refresh", manager.isGearLoading());
				newHits.add(new Hit(refresh, manager::refreshGear, "Load the setups for your task from the website again"));
				finish(g, win, newHits);
				return new Dimension(w, h);
			}

			subtitle(g, gear, set, win);
			int tabsY = y + TITLE_HEIGHT + SUBTITLE_HEIGHT + 4;
			tabs(g, gear, set, new Rectangle(x + 8, tabsY, w - 16, TAB_HEIGHT), newHits);

			int bodyY = tabsY + TAB_HEIGHT + 6;
			int bodyH = y + h - 10 - bodyY;
			int slot = Math.max(26, Math.min(40, (bodyH - 36) / 5 - SLOT_GAP));
			int cell = slot + SLOT_GAP;

			// Worn equipment on the left.
			Rectangle worn = new Rectangle(x + 8, bodyY, 3 * cell + 20, bodyH);
			panel(g, worn);
			g.setFont(FontManager.getRunescapeBoldFont());
			text(g, "Worn", worn.x + 10, worn.y + 18, ORANGE);
			int ex = worn.x + 10;
			int ey = worn.y + 26;
			for (int r = 0; r < BossGearWindow.EQUIPMENT.length; r++) {
				for (int c = 0; c < 3; c++) {
					String name = BossGearWindow.EQUIPMENT[r][c];
					if (name != null) {
						drawSlot(g, new Rectangle(ex + c * cell, ey + r * cell, slot, slot), set.slot(name), newHits);
					}
				}
			}

			// The numbers, what else it counts on, the legend and Refresh on the right.
			Rectangle side = new Rectangle(worn.x + worn.width + 8, bodyY, x + w - 8 - (worn.x + worn.width + 8), bodyH);
			renderSide(g, set, side, newHits);
			finish(g, win, newHits);
			return new Dimension(w, h);
		});
	}

	/** The task's monster, the picked setup and its numbers. */
	private void subtitle(Graphics2D g, SlayerGear gear, SlayerGear.Set set, Rectangle win) {
		int subY = win.y + TITLE_HEIGHT + 16;
		String monster = gear.getMonster() != null ? gear.getMonster() : gear.getTask() != null ? gear.getTask() : "Slayer task";
		g.setFont(FontManager.getRunescapeFont());
		int after = text(g, truncate(monster, g.getFontMetrics(), Math.min(170, win.width / 3)), win.x + 12, subY, Color.WHITE);
		g.setFont(FontManager.getRunescapeSmallFont());
		after = text(g, truncate("  " + set.getLabel(), g.getFontMetrics(), 110), after, subY, BEIGE);
		String numbers = numbers(set);
		int nw = g.getFontMetrics().stringWidth(numbers);
		int nx = Math.max(after + 10, win.x + win.width - 12 - nw);
		text(g, truncate(numbers, g.getFontMetrics(), win.x + win.width - 12 - nx), nx, subY, YELLOW);
	}

	/** "Max 42 · 5.31 DPS · 118 kills/h". */
	static String numbers(SlayerGear.Set set) {
		return "Max " + set.getMaxHit() + " · " + String.format(Locale.ROOT, "%.2f", set.getDps()) + " DPS · "
			+ Math.round(set.getKillsPerHour()) + " kills/h";
	}

	/** One tab per setup (Melee, Ranged, Magic, Barrage...), best first and marked; a click picks it. */
	private void tabs(Graphics2D g, SlayerGear gear, SlayerGear.Set picked, Rectangle area, List<Hit> newHits) {
		List<SlayerGear.Set> sets = gear.getSets();
		int n = sets.size();
		int tw = area.width / Math.max(1, n);
		Point m = mouse;
		for (int i = 0; i < n; i++) {
			SlayerGear.Set s = sets.get(i);
			boolean on = s == picked;
			Rectangle tab = new Rectangle(area.x + i * tw, area.y, i == n - 1 ? area.width - i * tw : tw, area.height);
			boolean hover = !on && m != null && tab.contains(m);
			box(g, tab, on ? TAB_ON : hover ? new Color(0x3A3226) : PANEL, PANEL_EDGE, on ? BUTTON_INNER : PANEL_INNER);
			g.setFont(FontManager.getRunescapeSmallFont());
			String label = s.getLabel() + (i == 0 ? " (best)" : "");
			centred(g, truncate(label, g.getFontMetrics(), tab.width - 8), tab.x + tab.width / 2, tab.y + 13, on ? ORANGE : i == 0 ? GREEN : GREY);
			centred(g, truncate(String.format(Locale.ROOT, "%.2f DPS", s.getDps()), g.getFontMetrics(), tab.width - 8), tab.x + tab.width / 2,
				tab.y + 26, on ? Color.WHITE : BEIGE);
			if (!on) {
				newHits.add(new Hit(tab, () -> manager.selectGearSet(s),
					"Use " + s.getLabel() + ": " + numbers(s) + " (side panel and bank layout follow)"));
			} else {
				newHits.add(new Hit(tab, () -> { }, s.getLabel() + " is picked: " + numbers(s)));
			}
		}
	}

	private void renderSide(Graphics2D g, SlayerGear.Set set, Rectangle area, List<Hit> newHits) {
		panel(g, area);
		int lx = area.x + 10;
		int ly = area.y + 18;
		int textW = area.width - 20;
		int bottom = area.y + area.height - BUTTON_HEIGHT - 16;
		g.setFont(FontManager.getRunescapeBoldFont());
		text(g, truncate(set.getLabel(), g.getFontMetrics(), textW), lx, ly, ORANGE);
		ly += 16;
		g.setFont(FontManager.getRunescapeSmallFont());
		String[][] rows = {
			{"Max hit", String.valueOf(set.getMaxHit())},
			{"DPS", String.format(Locale.ROOT, "%.2f", set.getDps())},
			{"Kills / hour", String.valueOf(Math.round(set.getKillsPerHour()))},
		};
		for (String[] row : rows) {
			text(g, row[0], lx, ly, GREY);
			text(g, row[1], lx + 80, ly, Color.WHITE);
			ly += 13;
		}
		ly += 6;
		// What else the numbers count on: potion, prayer, style or spell, darts.
		List<SlayerGear.Extra> extras = new ArrayList<>(set.getExtras());
		boolean spellListed = false;
		for (SlayerGear.Extra e : extras) {
			spellListed |= set.getSpell() != null && set.getSpell().equalsIgnoreCase(e.getName());
		}
		if (!extras.isEmpty() || set.getSpell() != null && !spellListed) {
			g.setFont(FontManager.getRunescapeBoldFont());
			text(g, "Also uses", lx, ly, ORANGE);
			ly += 15;
			g.setFont(FontManager.getRunescapeSmallFont());
			if (set.getSpell() != null && !spellListed) {
				ly = line(g, "Spell", set.getSpell(), lx, ly, textW);
			}
			for (SlayerGear.Extra e : extras) {
				if (ly > bottom - 60) {
					break;
				}
				ly = line(g, e.getLabel(), e.getName(), lx, ly, textW);
			}
			// The options the bank layout shows (the setting's number), best first.
			String options = set.supplyText(config.slayerGearSupplyOptions());
			if (options != null && ly <= bottom - 60) {
				ly = line(g, "In the bank", options, lx, ly, textW);
			}
			ly += 6;
		}
		text(g, "Carried", lx, ly, GREEN);
		ly += 13;
		text(g, "In your bank or storage (B)", lx, ly, STORED);
		ly += 18;
		String hint = config.slayerGearBankLayout() ? "Open your bank: it shows only this setup, laid out like your gear."
			: "Turn on Bank layout on the Slayer helper page to have the bank show this setup.";
		for (String l : wrap(hint, g.getFontMetrics(), textW)) {
			if (ly > bottom) {
				break;
			}
			text(g, l, lx, ly, GREY);
			ly += 13;
		}
		Rectangle refresh = new Rectangle(area.x + (area.width - BUTTON_WIDTH) / 2, area.y + area.height - BUTTON_HEIGHT - 10, BUTTON_WIDTH, BUTTON_HEIGHT);
		button(g, refresh, manager.isGearLoading() ? "Loading..." : "Refresh", manager.isGearLoading());
		newHits.add(new Hit(refresh, manager::refreshGear, "Load the setups again from the website (after syncing your bank or skills)"));
	}

	/** "Label: value", the value wrapped under the label when it does not fit. Returns the next line's y. */
	private static int line(Graphics2D g, String label, String value, int x, int y, int width) {
		String head = label.isEmpty() ? "" : label + ": ";
		int vx = text(g, head, x, y, GREY);
		List<String> lines = wrap(value, g.getFontMetrics(), x + width - vx);
		for (int i = 0; i < lines.size() && i < 2; i++) {
			text(g, lines.get(i), i == 0 ? vx : x + 8, y, BEIGE);
			y += 13;
		}
		return lines.isEmpty() ? y + 13 : y;
	}

	private void message(Graphics2D g, Rectangle win, SlayerGear gear) {
		String message = manager.getTask() == null && gear == null ? "No slayer task: get one from a slayer master."
			: manager.isGearLoading() || gear == null ? "Loading your slayer setups..."
			: "No setups for this task. Sync your bank and skills, then press Refresh.";
		g.setFont(FontManager.getRunescapeFont());
		int lineY = win.y + win.height / 2 - 10;
		for (String l : wrap(message, g.getFontMetrics(), win.width - 80)) {
			centred(g, l, win.x + win.width / 2, lineY, Color.WHITE);
			lineY += 16;
		}
	}

	/** One worn slot: the item, a border green when carried, else orange with a "B" (in your bank or storage). */
	private void drawSlot(Graphics2D g, Rectangle r, SlayerGear.Piece piece, List<Hit> newHits) {
		box(g, r, SLOT, SLOT_EDGE, SLOT_INNER);
		if (piece == null || piece.getItem() <= 0) {
			return;
		}
		BufferedImage icon = itemManager.getImage(piece.getItem());
		if (icon != null) {
			int iw = Math.min(icon.getWidth(), r.width - 2);
			int ih = Math.min(icon.getHeight(), r.height - 2);
			g.drawImage(icon, r.x + (r.width - iw) / 2, r.y + (r.height - ih) / 2, iw, ih, null);
		}
		boolean carried = manager.carried(piece.getItem()) > 0;
		g.setColor(carried ? GREEN : STORED);
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		if (!carried) {
			g.setFont(FontManager.getRunescapeSmallFont());
			text(g, "B", r.x + r.width - 8, r.y + r.height - 3, STORED);
		}
		newHits.add(new Hit(r, () -> { }, piece.getName() + (carried ? " - carried" : " - in your bank or storage")));
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

	// ------------------------------------------------------------------ input

	private boolean inside(MouseEvent e) {
		Rectangle win = window;
		return manager.isGearOpen() && win != null && win.contains(e.getPoint());
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		return Guard.call(GUARD_FEATURE, () -> {
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
		}, e);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e) {
		return Guard.call(GUARD_FEATURE, () -> {
			if (inside(e)) {
				e.consume();
			}
			return e;
		}, e);
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		return Guard.call(GUARD_FEATURE, () -> {
			if (inside(e)) {
				e.consume();
			}
			return e;
		}, e);
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e) {
		return Guard.call(GUARD_FEATURE, () -> {
			mouse = e.getPoint();
			if (inside(e)) {
				e.consume();
			}
			return e;
		}, e);
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e) {
		return Guard.call(GUARD_FEATURE, () -> {
			if (inside(e)) {
				e.consume();
			}
			return e;
		}, e);
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e) {
		return Guard.call(GUARD_FEATURE, () -> {
			return e;
		}, e);
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e) {
		return Guard.call(GUARD_FEATURE, () -> {
			mouse = null;
			return e;
		}, e);
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e) {
		return Guard.call(GUARD_FEATURE, () -> {
			if (inside(e)) {
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
		Guard.run(GUARD_FEATURE, () -> {
			if (manager.isGearOpen() && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
				manager.closeGearWindow();
				e.consume();
			}
		});
	}

	@Override
	public void keyReleased(KeyEvent e) {
	}
}
