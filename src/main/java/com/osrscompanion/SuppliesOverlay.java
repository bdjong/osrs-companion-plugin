package com.osrscompanion;

import static com.osrscompanion.GameUi.*;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The supplies check next to the open bank: how many trips (boss setup) and farm runs (route) the bank
 * and what you carry support, and per supply how many a trip takes and how many trips it lasts; red for
 * what you have less than one trip of. Beside the bank when there is room, else a one-line box over its top.
 */
@Singleton
public class SuppliesOverlay extends Overlay {
	private static final int WIDTH = 230;
	private static final int ROW = 22;
	private static final int MAX_ROWS = 8;

	private final Client client;
	private final OSRSCompanionConfig config;
	private final SuppliesCheck check;
	private final BossGearManager bossGear;
	private final FarmRunManager farmRun;
	private final ItemManager itemManager;

	@Inject
	public SuppliesOverlay(Client client, OSRSCompanionConfig config, SuppliesCheck check, BossGearManager bossGear, FarmRunManager farmRun,
		ItemManager itemManager) {
		this.client = client;
		this.config = config;
		this.check = check;
		this.bossGear = bossGear;
		this.farmRun = farmRun;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** The checks to show: the boss setup while it is active, the farm route while a run goes or its window is open (or no boss setup). */
	private List<SuppliesCheck.Result> shown() {
		List<SuppliesCheck.Result> out = new ArrayList<>();
		SuppliesCheck.Result boss = check.getBoss();
		if (boss != null && config.tripPlannerBoss() && bossGear.isSetActive()) {
			out.add(boss);
		}
		SuppliesCheck.Result farm = check.getFarm();
		if (farm != null && config.tripPlannerFarm() && (farmRun.isRunning() || farmRun.isOpen() || out.isEmpty())) {
			out.add(farm);
		}
		return out;
	}

	@Override
	public Dimension render(Graphics2D g) {
		if (!config.tripPlannerBoss() && !config.tripPlannerFarm()) {
			return null;
		}
		Widget bank = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (bank == null || bank.isHidden()) {
			return null;
		}
		List<SuppliesCheck.Result> results = shown();
		if (results.isEmpty()) {
			return null;
		}
		Rectangle b = bank.getBounds();
		boolean left = b.x >= WIDTH + 12;
		boolean right = client.getCanvasWidth() - (b.x + b.width) >= WIDTH + 12;
		boolean full = left || right;
		int h = 8;
		for (SuppliesCheck.Result r : results) {
			h += full ? 46 + Math.min(MAX_ROWS, r.rows.size()) * ROW + 6 : 30;
		}
		int x = left ? b.x - WIDTH - 6 : right ? b.x + b.width + 6 : b.x + b.width - WIDTH - 30;
		int y = full ? b.y + 4 : b.y + 34;
		Rectangle area = new Rectangle(x, y, WIDTH, h);
		box(g, area, BG, FRAME_DARK, FRAME_LIGHT);
		int ly = y + 4;
		for (SuppliesCheck.Result r : results) {
			ly = full ? drawFull(g, r, x, ly) : drawLine(g, r, x, ly);
		}
		return null;
	}

	private static Color tone(int trips) {
		return trips < 1 ? RED : trips < 3 ? ORANGE : GREEN;
	}

	/** Headline and what runs out, in one box line (no room beside the bank). */
	private int drawLine(Graphics2D g, SuppliesCheck.Result r, int x, int y) {
		g.setFont(FontManager.getRunescapeSmallFont());
		text(g, truncate(r.title, g.getFontMetrics(), WIDTH - 16), x + 8, y + 12, BEIGE);
		String line = r.headline() + (r.runsOut == null ? "" : " (runs out of: " + r.runsOut + ")");
		text(g, truncate(line, g.getFontMetrics(), WIDTH - 16), x + 8, y + 25, tone(r.trips));
		return y + 30;
	}

	private int drawFull(Graphics2D g, SuppliesCheck.Result r, int x, int y) {
		g.setFont(FontManager.getRunescapeBoldFont());
		text(g, truncate(r.title, g.getFontMetrics(), WIDTH - 16), x + 8, y + 14, ORANGE);
		g.setFont(FontManager.getRunescapeSmallFont());
		String head = r.headline() + (r.runsOut == null ? "" : " (runs out of: " + r.runsOut + ")");
		text(g, truncate(head, g.getFontMetrics(), WIDTH - 16), x + 8, y + 28, tone(r.trips));
		text(g, truncate(r.source, g.getFontMetrics(), WIDTH - 16), x + 8, y + 41, GREY);
		int ry = y + 46;
		for (int i = 0; i < r.rows.size() && i < MAX_ROWS; i++) {
			SuppliesCheck.Row row = r.rows.get(i);
			Rectangle slot = new Rectangle(x + 8, ry, 20, 20);
			if (row.itemId > 0) {
				BufferedImage icon = itemManager.getImage(row.itemId);
				if (icon != null) {
					g.drawImage(icon, slot.x, slot.y, 20, 18, null);
				}
			}
			Color c = row.isShort() ? RED : Color.WHITE;
			String word = "trip".equals(r.unit) ? "trip" : "run";
			String right = row.trips == Integer.MAX_VALUE ? "" : row.trips + " " + word + (row.trips == 1 ? "" : "s");
			int rw = g.getFontMetrics().stringWidth(right);
			text(g, truncate(row.name + " x" + row.perTrip, g.getFontMetrics(), WIDTH - 44 - rw), x + 32, ry + 14, c);
			text(g, right, x + WIDTH - 8 - rw, ry + 14, row.isShort() ? RED : tone(row.trips));
			ry += ROW;
		}
		return y + 46 + Math.min(MAX_ROWS, r.rows.size()) * ROW + 6;
	}
}
