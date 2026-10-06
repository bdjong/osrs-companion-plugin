package com.osrscompanion;

import com.osrscompanion.model.BossGear;
import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapGoal;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The OSRS Companion side panel: a menu of four tool tiles (like the website's tool banners).
 * Each opens its own page with a Back tile, what is active, its on/off settings (e.g. whether the
 * bank lays out the boss setup or farm route) and a tile to open its window over the game.
 */
public class RoadmapPanel extends PluginPanel {
	private static final Color ROADMAP = new Color(0x60A5FA);
	private static final Color GEAR = new Color(0xFB923C);
	private static final Color FARM = new Color(0x4ADE80);
	private static final Color BOSS = new Color(0xA78BFA);

	private final ItemManager itemManager;
	private final OSRSCompanionConfig config;
	private final ConfigManager configManager;
	private final RoadmapManager roadmapManager;
	private final FarmRunManager farmRun;
	private final BossGearManager bossGear;

	private final CardLayout cards = new CardLayout();
	private final JPanel deck = new JPanel(cards);
	/** Keeps the live cards (current roadmap, route, setup) and switches up to date while the panel is open. */
	private final Timer refresh = new Timer(1000, e -> deck.repaint());

	public RoadmapPanel(Runnable openRoadmap, Runnable openGearProgression, Runnable openFarmRun, Runnable openBossGear,
			ItemManager itemManager, OSRSCompanionConfig config, ConfigManager configManager,
			RoadmapManager roadmapManager, FarmRunManager farmRun, BossGearManager bossGear) {
		super(false);
		this.itemManager = itemManager;
		this.config = config;
		this.configManager = configManager;
		this.roadmapManager = roadmapManager;
		this.farmRun = farmRun;
		this.bossGear = bossGear;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		deck.add(mainMenu(), "main");
		deck.add(roadmapPage(openRoadmap), "roadmap");
		deck.add(gearPage(openGearProgression), "gear");
		deck.add(farmPage(openFarmRun), "farm");
		deck.add(bossPage(openBossGear), "boss");
		add(deck, BorderLayout.NORTH);

		JLabel note = new JLabel("<html><body style='width:170px'><i>Progress updates as you play and "
			+ "syncs with the website in the background.</i></body></html>");
		note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		note.setFont(FontManager.getRunescapeSmallFont());
		note.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		add(note, BorderLayout.SOUTH);
	}

	@Override
	public void onActivate() {
		refresh.start();
	}

	@Override
	public void onDeactivate() {
		refresh.stop();
	}

	// ---- Pages --------------------------------------------------------------------------

	private JPanel mainMenu() {
		JPanel p = page();
		JLabel title = new JLabel("OSRS Companion");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(title);
		p.add(Box.createVerticalStrut(10));
		add(p, roadmapTile(() -> show("roadmap")), "Roadmap: your goals, settings and the roadmap window.");
		add(p, gearTile(() -> show("gear")), "Gear progression: settings and the gear progression window.");
		add(p, farmTile(() -> show("farm")), "Farm run: your route, bank layout and the farm run window.");
		add(p, bossTile(() -> show("boss")), "Boss gear: your setup, bank layout and the boss gear window.");
		return p;
	}

	private JPanel roadmapPage(Runnable openWindow) {
		JPanel p = page();
		add(p, back(), "Back to all tools");
		add(p, roadmapTile(openWindow), "Open or close the roadmap window (Escape closes it).");
		add(p, new PanelTiles.Info("SELECTED ROADMAP", () -> {
			Roadmap r = roadmapManager.getCurrentRoadmap();
			if (r == null) {
				return new String[]{"No roadmap loaded yet", "Open the window to load your roadmaps"};
			}
			RoadmapGoal next = r.firstIncompleteGoal();
			return new String[]{r.getTitle(), next == null ? "Every goal is done!" : "Next: " + next.getName()};
		}), null);
		add(p, toggle("Roadmap box", "Next goal in the game", "Hidden", ROADMAP, "showNextGoal", config::showNextGoal), null);
		add(p, toggle("Goal popup", "Popup on completion", "No popup", ROADMAP, "showGoalPopup", config::showGoalPopup), null);
		add(p, FeatureTile.slim("Open roadmap window", ROADMAP, openWindow), "Goals, progress, complete or delete, switch roadmap.");
		return p;
	}

	private JPanel gearPage(Runnable openWindow) {
		JPanel p = page();
		add(p, back(), "Back to all tools");
		add(p, gearTile(openWindow), "Open or close your gear progression (Escape closes it).");
		add(p, toggle("Progression box", "Next gear step in game", "Hidden", GEAR, "showGearProgress", config::showGearProgress), null);
		add(p, FeatureTile.slim("Open gear progression", GEAR, openWindow), "Your gear order in tiers; it ticks itself off.");
		return p;
	}

	private JPanel farmPage(Runnable openWindow) {
		JPanel p = page();
		add(p, back(), "Back to all tools");
		add(p, farmTile(openWindow), "Open or close the farm run window (Escape closes it).");
		add(p, new PanelTiles.Info("ACTIVE ROUTE", () -> {
			FarmRoute r = farmRun.getRoute();
			if (r == null) {
				return new String[]{"No route loaded yet", "Make one on the website, then open the window"};
			}
			int[] done = farmRun.progress();
			String state = farmRun.isRunning() ? (farmRun.isComplete() ? "Run complete" : "Running: " + done[0] + " / " + done[1] + " patches") : "Not running";
			return new String[]{r.getName(), state};
		}), null);
		add(p, toggle("Active in the bank", "Bank shows route items", "Bank left alone", FARM, "farmRunBankFilter", config::farmRunBankFilter), null);
		add(p, toggle("Run guide", "Arrow + patch outlines", "Guide hidden", FARM, "showFarmRun", config::showFarmRun), null);
		add(p, FeatureTile.slim("Open farm run window", FARM, openWindow), "Stops, what to bring, start or stop the run, switch route.");
		return p;
	}

	private JPanel bossPage(Runnable openWindow) {
		JPanel p = page();
		add(p, back(), "Back to all tools");
		add(p, bossTile(openWindow), "Open or close the boss gear window (Escape closes it).");
		add(p, new PanelTiles.Info("ACTIVE SETUP", () -> {
			BossGear s = bossGear.getSet();
			if (s == null) {
				return new String[]{bossGear.isLoading() ? "Loading..." : "No setup picked yet", "Pick one in the window or on the website"};
			}
			return new String[]{s.getBossName(), s.getStyle()};
		}), null);
		add(p, toggle("Active in the bank", "Bank shows this setup", "Bank left alone", BOSS, "bossGearBankLayout", config::bossGearBankLayout), null);
		add(p, FeatureTile.slim("Open boss gear window", BOSS, openWindow), "See the setup and switch boss or setup.");
		return p;
	}

	// ---- Pieces -----------------------------------------------------------------------------

	private void show(String page) {
		// The pages show what is active: load it when it isn't yet.
		if ("boss".equals(page) && bossGear.getSet() == null && !bossGear.isLoading()) {
			bossGear.refresh();
		} else if ("farm".equals(page)) {
			farmRun.refreshIfStale();
		}
		cards.show(deck, page);
		deck.repaint();
	}

	private FeatureTile back() {
		return FeatureTile.banner("< BACK", "", "All tools", new Color(0x94A3B8), new Color(0x10141C), new Color(0x334155), null, () -> show("main"));
	}

	private FeatureTile roadmapTile(Runnable action) {
		return FeatureTile.banner("ROAD", "MAP", "Your goals, tracked as you play",
			ROADMAP, new Color(0x0D1530), new Color(0x1E40AF), itemManager.getImage(ItemID.SKILLCAPE_QP), action);
	}

	private FeatureTile gearTile(Runnable action) {
		return FeatureTile.banner("GEAR", "PROGRESSION", "Gear order, auto-ticked",
			GEAR, new Color(0x1C0D06), new Color(0x9A3412), itemManager.getImage(ItemID.HUNDRED_GAUNTLETS_LEVEL_10), action);
	}

	private FeatureTile farmTile(Runnable action) {
		return FeatureTile.banner("FARM", "RUN", "Route & guided run",
			FARM, new Color(0x08130C), new Color(0x166534), itemManager.getImage(ItemID.WATERING_CAN_8), action);
	}

	private FeatureTile bossTile(Runnable action) {
		return FeatureTile.banner("BOSS", "GEAR", "Setups + bank layout",
			BOSS, new Color(0x120A24), new Color(0x5B21B6), itemManager.getImage(ItemID.VORKATH_HEAD), action);
	}

	/** An on/off tile for a boolean setting of this plugin. */
	private PanelTiles.Toggle toggle(String title, String on, String off, Color accent, String key, java.util.function.BooleanSupplier value) {
		return new PanelTiles.Toggle(title, on, off, accent, value,
			() -> configManager.setConfiguration(OSRSCompanionConfig.CONFIG_GROUP, key, !value.getAsBoolean()));
	}

	private static JPanel page() {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		return p;
	}

	private static void add(JPanel page, JComponent tile, String tooltip) {
		tile.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (tooltip != null) {
			tile.setToolTipText(tooltip);
		}
		page.add(tile);
		page.add(Box.createVerticalStrut(8));
	}
}
