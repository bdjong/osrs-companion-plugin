package com.osrscompanion;

import com.osrscompanion.model.BossGear;
import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.model.GearPlan;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapGoal;
import com.osrscompanion.model.SlayerGuide;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.SpriteID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;

/**
 * The OSRS Companion side panel: a menu of five tool tiles (like the website's tool banners).
 * Each opens its own page with a Back tile, what is active, its on/off settings (e.g. whether the
 * bank lays out the boss setup or farm route) and a tile to open its window over the game.
 */
public class RoadmapPanel extends PluginPanel {
	private static final Color ROADMAP = new Color(0x60A5FA);
	private static final Color GEAR = new Color(0xFB923C);
	private static final Color FARM = new Color(0x4ADE80);
	private static final Color BOSS = new Color(0xA78BFA);
	private static final Color DAILIES = new Color(0xF472B6);
	private static final Color SLAYER = new Color(0xF87171);

	private final ItemManager itemManager;
	private final SpriteManager spriteManager;
	private final GoalImageCache images;
	private final OSRSCompanionConfig config;
	private final ConfigManager configManager;
	private final RoadmapManager roadmapManager;
	private final FarmRunManager farmRun;
	private final BossGearManager bossGear;
	private final GearProgressionManager gearProgression;
	private final SlayerHelperManager slayer;
	private final ClientThread clientThread;
	private final DailiesManager dailies;

	private final CardLayout cards = new CardLayout();
	/** The pages; only as tall as the page on show (a card layout is as tall as its tallest page). */
	private final JPanel deck = new JPanel(cards) {
		@Override
		public Dimension getPreferredSize() {
			for (Component c : getComponents()) {
				if (c.isVisible()) {
					Dimension d = c.getPreferredSize();
					Insets in = getInsets();
					return new Dimension(d.width + in.left + in.right, d.height + in.top + in.bottom);
				}
			}
			return super.getPreferredSize();
		}
	};
	/** The boss page's gear display and the gap under the tile above it, shown while its setting is on. */
	private BossGearPanelView gearView;
	private Component gearViewGap;
	/** The slayer page's session numbers and the gap under them, shown while a session is going. */
	private JComponent slayerStats;
	private Component slayerStatsGap;
	/** The Slayer skill icon, loaded in the background (the task card's picture). */
	private volatile java.awt.image.BufferedImage slayerIcon;
	/** Keeps the live cards (current roadmap, route, setup) and switches up to date while the panel is open. */
	private final Timer refresh = new Timer(1000, e -> tick());

	public RoadmapPanel(Runnable openRoadmap, Runnable openGearProgression, Runnable openFarmRun, Runnable openBossGear, Runnable openSlayerGear,
			ItemManager itemManager, SpriteManager spriteManager, GoalImageCache images, OSRSCompanionConfig config, ConfigManager configManager,
			RoadmapManager roadmapManager, FarmRunManager farmRun, BossGearManager bossGear, GearProgressionManager gearProgression,
			SlayerHelperManager slayer, ClientThread clientThread, DailiesManager dailies) {
		super(false);
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.images = images;
		this.config = config;
		this.configManager = configManager;
		this.roadmapManager = roadmapManager;
		this.farmRun = farmRun;
		this.bossGear = bossGear;
		this.gearProgression = gearProgression;
		this.slayer = slayer;
		this.clientThread = clientThread;
		this.dailies = dailies;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		deck.add(mainMenu(), "main");
		deck.add(roadmapPage(openRoadmap), "roadmap");
		deck.add(gearPage(openGearProgression), "gear");
		deck.add(farmPage(openFarmRun), "farm");
		deck.add(bossPage(openBossGear), "boss");
		deck.add(dailiesPage(), "dailies");
		deck.add(slayerPage(openSlayerGear), "slayer");
		// The boss page's gear display is tall: the pages scroll.
		JPanel top = new JPanel(new BorderLayout());
		top.add(deck, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(top, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		// Paint everything again on scroll: the default (copying the pixels already drawn) leaves the
		// hand-painted tiles grey until they are hovered.
		scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
		scroll.getVerticalScrollBar().addAdjustmentListener(e -> scroll.getViewport().repaint());
		add(scroll, BorderLayout.CENTER);
		tick();

		JLabel note = new JLabel("<html><body style='width:170px'><i>Progress updates as you play and "
			+ "syncs with the website in the background.</i></body></html>");
		note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		note.setFont(FontManager.getRunescapeSmallFont());
		note.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		add(note, BorderLayout.SOUTH);
	}

	/**
	 * Repaints the live cards, shows or hides the boss gear display with its setting, and lays the
	 * page out again when the display's height changed (a set loaded, switched or went Inactive).
	 */
	/** The Slayer helper page's gear (the website's DPS setups); laid out again when its height changes. */
	private SlayerGearPanelView slayerGear;

	private void tick() {
		if (gearView != null) {
			boolean showGear = config.bossGearPanelDisplay();
			if (gearView.isVisible() != showGear) {
				gearView.setVisible(showGear);
				gearViewGap.setVisible(showGear);
				deck.revalidate();
			} else if (showGear && gearView.getHeight() != gearView.getPreferredSize().height) {
				gearView.revalidate();
			}
			// The display needs the setup loaded, like opening the Boss page does.
			if (showGear && bossGear.getSet() == null && !bossGear.isLoading() && !bossGear.isLoaded() && bossGear.getError() == null) {
				bossGear.refresh();
			}
		}
		if (slayerGear != null && slayerGear.getHeight() != slayerGear.getPreferredSize().height) {
			// The setups loaded or another one is on show: its height changes.
			slayerGear.revalidate();
		}
		if (slayerStats != null) {
			boolean showStats = slayer.getStats() != null;
			if (slayerStats.isVisible() != showStats) {
				slayerStats.setVisible(showStats);
				slayerStatsGap.setVisible(showStats);
				deck.revalidate();
			}
		}
		deck.repaint();
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
		add(p, bossTile(() -> show("boss")), "Boss gear: your build, bank layout, gear display and the boss gear window.");
		add(p, dailiesTile(() -> show("dailies")), "Dailies: today's open dailies and the guided run.");
		add(p, slayerTile(() -> show("slayer")), "Slayer helper: your task, the best place to do it, a guide there and your kill stats.");
		return p;
	}

	private JPanel roadmapPage(Runnable openWindow) {
		JPanel p = page();
		add(p, back(), "Back to all tools");
		add(p, new PanelTiles.Info("SELECTED ROADMAP", () -> {
			Roadmap r = roadmapManager.getCurrentRoadmap();
			if (r == null) {
				return new String[]{"No roadmap loaded yet", "Open Goal overview to load it"};
			}
			RoadmapGoal next = r.firstIncompleteGoal();
			return new String[]{r.getTitle(), next == null ? "Every goal is done!" : "Next: " + next.getName()};
		}, () -> {
			Roadmap r = roadmapManager.getCurrentRoadmap();
			RoadmapGoal next = r == null ? null : r.firstIncompleteGoal();
			return next == null ? null : images.get(next.getImageUrl());
		}), null);
		add(p, toggle("Roadmap box", "Next goal in the game", "Hidden", ROADMAP, "showNextGoal", config::showNextGoal), null);
		add(p, toggle("Goal popup", "Popup on completion", "No popup", ROADMAP, "showGoalPopup", config::showGoalPopup), null);
		add(p, FeatureTile.banner("GOAL", "OVERVIEW", "Goals and progress", ROADMAP, new Color(0x0D1530), new Color(0x1E40AF),
				itemManager.getImage(ItemID.SKILLCAPE_QP), openWindow),
			"Open or close the roadmap window: goals, progress, complete or delete, switch roadmap (Escape closes it).");
		return p;
	}

	private JPanel gearPage(Runnable openWindow) {
		JPanel p = page();
		add(p, back(), "Back to all tools");
		add(p, new PanelTiles.Progress("CURRENT TIER", GEAR, this::gearProgress),
			"The ring is your whole gear progression (the current tier's number inside); the bar is the current tier.");
		add(p, toggle("Progression box", "Next gear step in game", "Hidden", GEAR, "showGearProgress", config::showGearProgress), null);
		add(p, overview("TIER", "OVERVIEW", "Gear order by tier", GEAR, new Color(0x1C0D06), new Color(0x9A3412), SpriteID.TAB_STATS, openWindow),
			"Open or close your gear progression: the gear order in tiers, ticking itself off (Escape closes it).");
		return p;
	}

	private JPanel farmPage(Runnable openWindow) {
		JPanel p = page();
		add(p, back(), "Back to all tools");
		// The route, with a switch that starts (Active) or stops (Inactive) the run.
		add(p, new PanelTiles.SetupCard("ACTIVE ROUTE", "SELECTED ROUTE",
			"Active: the run is going (guide, bank layout). Click to stop it.",
			"Inactive: no run going. Click to start the run at its first stop.", FARM, () -> {
			FarmRoute r = farmRun.getRoute();
			if (r == null) {
				return new String[]{"No route loaded yet", "Make one on the website, then open the window"};
			}
			int[] done = farmRun.progress();
			String state = farmRun.isRunning() ? (farmRun.isComplete() ? "Run complete" : "Running: " + done[0] + " / " + done[1] + " patches")
				: r.isReady() ? "READY - every crop ready" : "Not running";
			return new String[]{r.getName(), state};
		}, () -> {
			FarmRoute r = farmRun.getRoute();
			Integer item = r == null ? null : r.pictureItem();
			return item == null ? null : itemManager.getImage(item);
		}, () -> farmRun.getRoute() != null, farmRun::isRunning, () -> {
			// Starting reads the patches' state from the game: on the client thread.
			clientThread.invoke(() -> {
				if (farmRun.isRunning()) {
					farmRun.stop();
				} else {
					farmRun.start();
				}
			});
			deck.repaint();
		}), null);
		add(p, toggle("Active in the bank", "Bank shows items during a run", "Bank left alone", FARM, "farmRunBankFilter", config::farmRunBankFilter),
			"Only one bank layout is active at a time: turning this on turns the other bank layouts off.");
		add(p, toggle("Run guide", "Arrow + patch outlines", "Guide hidden", FARM, "showFarmRun", config::showFarmRun), null);
		add(p, overview("ROUTE", "OVERVIEW", "Stops and what to bring", FARM, new Color(0x08130C), new Color(0x166534), SpriteID.MINIMAP_ORB_WORLD_MAP_PLANET, openWindow).iconSize(28),
			"Open or close the farm run window: stops, what to bring, start or stop the run, switch route (Escape closes it).");
		return p;
	}

	private JPanel bossPage(Runnable openWindow) {
		JPanel p = page();
		add(p, back(), "Back to all tools");
		add(p, new PanelTiles.SetupCard("ACTIVE SETUP", "SELECTED SETUP",
			"Active: the bank lays out this setup and the gear display shows it. Click to set Inactive (the setup stays selected).",
			"Inactive: the setup stays selected, but the bank is left alone and no gear is shown. Click to set Active.", BOSS, () -> {
			BossGear s = bossGear.getSet();
			if (s == null) {
				return new String[]{bossGear.isLoading() ? "Loading..." : "No setup picked yet", "Load one in Gear overview"};
			}
			return new String[]{s.getBossName(), buildLabel(s)};
		}, () -> {
			BossGear s = bossGear.getSet();
			return s == null ? null : images.get(s.getImage());
		}, () -> bossGear.getSet() != null, bossGear::isSetActive, () -> {
			bossGear.setSetActive(!bossGear.isSetActive());
			deck.repaint();
		}), null);
		add(p, toggle("Bank layout", "Bank shows this setup", "Bank left alone", BOSS, "bossGearBankLayout", config::bossGearBankLayout)
			.withNote(() -> bossGear.getSet() != null && !bossGear.isSetActive() && config.bossGearBankLayout() ? "Paused while Inactive" : null),
			"Only one bank layout is active at a time: turning this on turns the other bank layouts off.");
		add(p, toggle("Gear display", "Shown below", "Hidden", BOSS, "bossGearPanelDisplay", config::bossGearPanelDisplay)
			.withNote(() -> bossGear.getSet() != null && !bossGear.isSetActive() && config.bossGearPanelDisplay() ? "Hidden while Inactive" : null),
			"Show the active setup's worn gear, inventory and rune pouch in this panel.");
		add(p, new PanelTiles.Toggle("Owned alternatives", "Missing items: best owned instead", "Wiki gear only", BOSS, bossGear::isAlternatives, () -> {
			bossGear.setAlternatives(!bossGear.isAlternatives());
			deck.repaint();
		}).withNote(() -> bossGear.isAlternatives() && isCustomBuild(bossGear.getSet()) ? "Custom builds unchanged" : null),
			"Wiki presets: where you own none of the items the wiki lists for a slot, use the best item you own of the setup's style "
				+ "(melee / ranged / magic, checked with the DPS calculator), marked A. Synced with the website's Boss Gear page.");
		add(p, overview("GEAR", "OVERVIEW", "Load a preset or build", BOSS, new Color(0x120A24), new Color(0x5B21B6), SpriteID.TAB_EQUIPMENT, openWindow),
			"Open or close the boss gear window: the setup's gear and inventory; load a wiki preset or one of your custom builds there (Escape closes it).");
		gearView = new BossGearPanelView(bossGear, itemManager);
		gearView.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(gearView);
		gearViewGap = Box.createVerticalStrut(8);
		p.add(gearViewGap);
		return p;
	}

	private JPanel dailiesPage() {
		JPanel p = page();
		add(p, back(), "Back to all tools");
		// The guided run, with a switch that starts it at the first open daily or stops it.
		add(p, new PanelTiles.SetupCard("GUIDED RUN", "TODAY'S DAILIES",
			"Active: the step box and arrow lead you to each open daily in turn. Click to stop.",
			"Inactive: click to start the guided run at the first open daily.", DAILIES, () -> {
			long[] open = dailies.openCountAndValue();
			String reset = "resets in " + DailyReset.until(System.currentTimeMillis(), DailyReset.nextReset(System.currentTimeMillis()));
			String worth = open[1] > 0 ? QuantityFormatter.quantityToStackSize(open[1]) + " GP · " : "";
			DailiesManager.Entry cur = dailies.current();
			if (cur != null) {
				return new String[]{"Now: " + cur.name, open[0] + " open · " + worth + reset};
			}
			return new String[]{open[0] > 0 ? open[0] + " open today" : "All done for today", worth + reset};
		}, () -> {
			DailiesManager.Entry cur = dailies.current();
			if (cur == null) {
				for (DailiesManager.Entry e : dailies.entries()) {
					if (e.state == DailyChecks.State.OPEN) {
						cur = e;
						break;
					}
				}
			}
			return cur == null || cur.guide == null || cur.guide.getIcon() <= 0 ? null : itemManager.getImage(cur.guide.getIcon());
		}, () -> dailies.isRunning() || dailies.openCountAndValue()[0] > 0, dailies::isRunning, () -> {
			if (dailies.isRunning()) {
				dailies.stop();
			} else {
				dailies.start();
			}
			deck.repaint();
		}), null);
		add(p, toggle("Dailies guide", "Step box + arrow to the NPC", "Guide hidden", DAILIES, "showDailiesGuide", config::showDailiesGuide), null);
		add(p, FeatureTile.slim("SKIP THIS DAILY", DAILIES, () -> {
			dailies.skip();
			deck.repaint();
		}), "During the run: leave the current daily for later and go to the next open one.");
		DailiesListView list = new DailiesListView(dailies, itemManager);
		add(p, list, null);
		return p;
	}

	private JPanel slayerPage(Runnable openGearWindow) {
		JPanel p = page();
		add(p, back(), "Back to all tools");
		// The task, with the switch that turns the guide to its location on (Active) or off.
		add(p, new PanelTiles.SetupCard("TASK - GUIDE ON", "SLAYER TASK",
			"Active: the guide leads you to the location (step box, arrow, Slayer icon on the world map). Click to turn it off.",
			"Inactive: no guide. Click to be guided to the task's location.", SLAYER, this::slayerTaskLines,
			() -> slayerIcon, () -> true, config::slayerGuide, () -> {
				configManager.setConfiguration(OSRSCompanionConfig.CONFIG_GROUP, "slayerGuide", !config.slayerGuide());
				deck.repaint();
			}).withSwitchLabel("Guide"), null);
		slayerStats = new PanelTiles.Stats("AT THE LOCATION", SLAYER, 9, this::slayerStatRows);
		add(p, slayerStats, "This visit to the task's location: it is sent to the website, which charts it per task.");
		slayerStatsGap = p.getComponent(p.getComponentCount() - 1);
		slayerStats.setVisible(false);
		slayerStatsGap.setVisible(false);
		add(p, new PanelTiles.Lines("LOCATION", 3, this::slayerLocationLines).withTip(() -> {
			SlayerGuide.Location l = slayer.getLocation();
			return l == null || l.getRequirements().isEmpty() ? null : "Needs: " + String.join(", ", l.getRequirements());
		}), null);
		add(p, toggle("Bank layout", "Bank shows this setup", "Bank left alone", SLAYER, "slayerGearBankLayout", config::slayerGearBankLayout),
			"Only one bank layout is active at a time: turning this on turns the other bank layouts off.");
		add(p, overview("GEAR", "OVERVIEW", "Pick a DPS setup", SLAYER, new Color(0x1F0A0A), new Color(0x991B1B), SpriteID.TAB_EQUIPMENT, openGearWindow),
			"Open or close the slayer gear window: the website's DPS setups against the task's monster; pick the one to use (Escape closes it).");
		// The setup picked in the window (the best until one is picked), against the task's monster.
		slayerGear = new SlayerGearPanelView(slayer, itemManager);
		add(p, slayerGear, null);
		spriteManager.getSpriteAsync(SpriteID.SKILL_SLAYER, 0, img -> {
			slayerIcon = img;
			SwingUtilities.invokeLater(deck::repaint);
		});
		return p;
	}

	private String[] slayerTaskLines() {
		SlayerHelperManager.Task t = slayer.getTask();
		if (t == null) {
			return new String[]{"No slayer task", "Get one from a slayer master"};
		}
		String master = slayer.getMaster();
		return new String[]{t.displayName(), t.remaining + " left" + (t.original > 0 ? " of " + t.original : "") + (master != null ? " - " + master : "")};
	}

	private String[] slayerLocationLines() {
		SlayerGuide.Location l = slayer.getLocation();
		if (l != null) {
			SlayerGuide.Location here = slayer.getHere();
			String name = (here != null && here.getKey() != null && here.getKey().equals(l.getKey()) ? "Here: " : "") + l.getName()
				+ (l.isMulti() ? " (multi)" : "");
			return new String[]{name, l.getWhy(), l.getTravel() == null ? "" : "Way there: " + l.getTravel().getName()};
		}
		if (slayer.getTask() == null) {
			return new String[]{"No task, no location", "", ""};
		}
		if (slayer.isLoading()) {
			return new String[]{"Loading...", "", ""};
		}
		if (slayer.getError() != null) {
			return new String[]{"No location loaded", slayer.getError(), ""};
		}
		if (!slayer.isGuideForTask()) {
			return new String[]{"No location yet", "The website doesn't have this task yet", "(it needs Slayer sync on)"};
		}
		return new String[]{"No location known", "The website has no locations", "for this task yet"};
	}

	private String[][] slayerStatRows() {
		SlayerHelperManager.Stats s = slayer.getStats();
		SlayerHelperManager.Task t = slayer.getTask();
		if (s == null) {
			return new String[0][];
		}
		long now = System.currentTimeMillis();
		long time = s.timeInArea(now);
		return new String[][]{
			{"In the area", slayer.getHere() != null ? "Yes" : "No"},
			{"Time at location", SlayerFormat.clock(time)},
			{"Trip timer", SlayerFormat.clock(s.total(now))},
			{"Kills", String.valueOf(s.kills)},
			{"Avg kill time", s.avgKillMs > 0 ? SlayerFormat.seconds(s.avgKillMs) : "-"},
			{"Kills / hour", s.killsPerHour > 0 ? String.valueOf(Math.round(s.killsPerHour)) : "-"},
			{"Est. time left", s.estimatedLeftMs > 0 && t != null ? SlayerFormat.clock(s.estimatedLeftMs) : "-"},
			{"Loot", SlayerFormat.gp(s.lootGp)},
			{"Avg loot / kill", s.kills > 0 ? SlayerFormat.gp(s.avgLootGp) : "-"}};
	}

	/** Whether the loaded build is one of your custom builds (owned alternatives apply to wiki presets only). */
	private boolean isCustomBuild(BossGear s) {
		if (s == null) {
			return false;
		}
		for (BossGear.SetSummary sum : bossGear.getSets()) {
			if (sum.getId() == s.getId()) {
				return sum.isCustom();
			}
		}
		return false;
	}

	/** The build's name under the boss: your own name for a custom build, else the wiki setup's style. */
	private String buildLabel(BossGear s) {
		for (BossGear.SetSummary sum : bossGear.getSets()) {
			if (sum.getId() == s.getId() && sum.isCustom()) {
				return sum.getLabel() + " (custom build)";
			}
		}
		return s.getStyle() + " (wiki preset)";
	}

	/** The gear page's progress card: the tier with the next open step, its share done and the whole plan's. */
	private PanelTiles.ProgressState gearProgress() {
		GearPlan plan = gearProgression.getPlan();
		if (plan == null) {
			String why = gearProgression.isLoading() ? "Loading..." : gearProgression.getError() != null ? "Could not load it" : "No gear progression loaded";
			return new PanelTiles.ProgressState(new String[]{why, "Load one on the website: Tools > Gear progression"}, null, -1, -1);
		}
		float overall = plan.getTotal() == 0 ? 0 : plan.getDone() / (float) plan.getTotal();
		String percent = Math.round(overall * 100) + "% of all gear";
		GearPlan.Tier tier = null;
		for (GearPlan.Tier t : plan.getTiers()) {
			if (plan.getNextTier() != null && t.getTier() == plan.getNextTier()) {
				tier = t;
			}
		}
		if (tier == null) {
			return new PanelTiles.ProgressState(new String[]{"Every tier done!", plan.getDone() + " / " + plan.getTotal() + " - " + percent}, "\u2713", 1f, 1f);
		}
		String name = tier.getName().isEmpty() ? "Tier " + tier.getTier() : "Tier " + tier.getTier() + ": " + tier.getName();
		return new PanelTiles.ProgressState(new String[]{name, tier.getDone() + " / " + tier.getTotal() + " in this tier - " + percent},
			String.valueOf(tier.getTier()), overall, tier.getTotal() == 0 ? 0 : tier.getDone() / (float) tier.getTotal());
	}

	// ---- Pieces -----------------------------------------------------------------------------

	private void show(String page) {
		// The pages show what is active: load it when it isn't yet.
		if ("boss".equals(page) && bossGear.getSet() == null && !bossGear.isLoading()) {
			bossGear.refresh();
		} else if ("farm".equals(page)) {
			farmRun.refreshIfStale();
		} else if ("gear".equals(page) && gearProgression.getPlan() == null && !gearProgression.isLoading()) {
			gearProgression.refresh();
		} else if ("dailies".equals(page)) {
			dailies.refreshIfStale();
		} else if ("slayer".equals(page)) {
			slayer.refreshIfStale();
		}
		cards.show(deck, page);
		deck.revalidate();
		deck.repaint();
	}

	/** A full-size tile that opens the tool's window, with the game's own interface icon (loaded in the background). */
	private FeatureTile overview(String white, String accentWord, String subtitle, Color accent, Color from, Color to, int spriteId, Runnable action) {
		FeatureTile tile = FeatureTile.banner(white, accentWord, subtitle, accent, from, to, null, action);
		spriteManager.getSpriteAsync(spriteId, 0, img -> SwingUtilities.invokeLater(() -> tile.setIcon(img)));
		return tile;
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
		return FeatureTile.banner("BOSS", "GEAR", "Builds + bank layout",
			BOSS, new Color(0x120A24), new Color(0x5B21B6), itemManager.getImage(ItemID.VORKATH_HEAD), action);
	}

	private FeatureTile dailiesTile(Runnable action) {
		return FeatureTile.banner("DAILIES", "", "Today's dailies, guided",
			DAILIES, new Color(0x14060D), new Color(0x831843), itemManager.getImage(ItemID.BATTLESTAFF), action);
	}

	private FeatureTile slayerTile(Runnable action) {
		return FeatureTile.banner("SLAYER", "HELPER", "Task, best spot & kill stats",
			SLAYER, new Color(0x1F0A0A), new Color(0x991B1B), itemManager.getImage(ItemID.SLAYER_HELM), action);
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
