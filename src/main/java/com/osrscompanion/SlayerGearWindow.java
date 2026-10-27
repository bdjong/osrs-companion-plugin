package com.osrscompanion;

import static com.osrscompanion.GameUi.*;

import com.osrscompanion.model.SlayerGear;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;

/**
 * The Slayer gear window, drawn over the game like the boss gear window: the website's Gear &amp; DPS
 * calculator's setups against the task's monster as tabs (one per style, the best marked), the picked
 * one's worn equipment in the game's layout (carried green, in the bank orange "B") and what else it
 * counts on (potion, prayer, style or spell, darts). The picked setup is what the side panel shows and
 * what the bank lays out with the Slayer gear bank layout on.
 */
@Singleton
public class SlayerGearWindow extends GameWindow {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "slayer-gear-window";

	private static final int MAX_WIDTH = 520;
	private static final int MAX_HEIGHT = 400;
	private static final int MIN_WIDTH = 400;
	private static final int MIN_HEIGHT = 320;
	private static final int SUBTITLE_HEIGHT = 22;
	private static final int TAB_HEIGHT = 32;
	private static final int SLOT_GAP = 3;
	private static final int BUTTON_WIDTH = 110;
	private static final int BUTTON_HEIGHT = 20;
	private static final Color STORED = new Color(0xFF981F);

	private final SlayerHelperManager manager;
	private final ItemManager itemManager;
	private final OSRSCompanionConfig config;

	@Inject
	public SlayerGearWindow(Client client, SlayerHelperManager manager, ItemManager itemManager, OSRSCompanionConfig config) {
		super(client, GUARD_FEATURE);
		this.manager = manager;
		this.itemManager = itemManager;
		this.config = config;
	}

	// ------------------------------------------------------------------ render

	@Override
	public Dimension render(Graphics2D g) {
		return Guard.render(GUARD_FEATURE, () -> {
			List<Hit> newHits = new ArrayList<>();
			Rectangle win = frame(g, client.getWidget(InterfaceID.Bankmain.UNIVERSE) == null && manager.isGearOpen(), MAX_WIDTH, MAX_HEIGHT, MIN_WIDTH, MIN_HEIGHT, "Slayer Gear", newHits);
			if (win == null) {
				return null;
			}
			int x = win.x;
			int y = win.y;
			int w = win.width;
			int h = win.height;

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
		String hint = !config.slayerGearBankLayout() ? "Turn on Bank layout on the Slayer helper page to have the bank show this setup."
			: config.slayerGuide() ? "Open your bank: it shows only this setup, laid out like your gear."
			: "Turn the Slayer guide on to have the bank show this setup.";
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

	// ------------------------------------------------------------------ input

	@Override
	boolean isShown() {
		return manager.isGearOpen();
	}

	@Override
	void closeWindow() {
		manager.closeGearWindow();
	}
}
