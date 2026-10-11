package com.osrscompanion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.reflect.TypeToken;
import com.osrscompanion.model.BossGear;
import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.model.GearPlan;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.SlayerGear;
import com.osrscompanion.FarmPatchProgress.Status;
import java.lang.reflect.Method;
import java.util.List;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.SpriteManager;
import org.junit.Before;
import org.junit.Test;

/** Golden renders of every side panel page (see {@link UiKit}). */
public class SidePanelGoldenTest {
	/** The side panel's width in the client. */
	private static final int W = 242;

	private final UiKit kit = new UiKit();
	private boolean bossDisplay;
	private final TestOSRSCompanionConfig config = new TestOSRSCompanionConfig() {
		@Override
		public boolean bossGearPanelDisplay() {
			return bossDisplay;
		}
	};
	private final RoadmapManager roadmaps = mock(RoadmapManager.class);
	private final FarmRunManager farmRun = mock(FarmRunManager.class);
	private final BossGearManager bossGear = mock(BossGearManager.class);
	private final GearProgressionManager gear = mock(GearProgressionManager.class);
	private final SlayerHelperManager slayer = mock(SlayerHelperManager.class);
	private final GoalImageCache images = mock(GoalImageCache.class);
	private RoadmapPanel panel;

	@Before
	public void setUp() {
		UiKit.runeLiteLookAndFeel();
		when(roadmaps.hasAppKey()).thenReturn(true);
		when(roadmaps.getPlayerUsername()).thenReturn("Iron Bob");
		Roadmap roadmap = UiSamples.GSON.fromJson(RoadmapWindowGoldenTest.ROADMAP_JSON, Roadmap.class);
		when(roadmaps.getCurrentRoadmap()).thenReturn(roadmap);

		FarmRoute route = UiSamples.route();
		when(farmRun.getRoute()).thenReturn(route);
		when(farmRun.isLoaded()).thenReturn(true);
		when(farmRun.progress()).thenReturn(new int[]{2, 6});
		when(farmRun.statusOf(any())).thenReturn(Status.TODO);
		when(farmRun.nextRunAt()).thenReturn(-1L);
		List<FarmRoute.RouteSummary> routes = UiSamples.GSON.fromJson("[{\"id\":7,\"name\":\"Herbs + trees\",\"active\":true}]",
			new TypeToken<List<FarmRoute.RouteSummary>>() { }.getType());
		when(farmRun.getRoutes()).thenReturn(routes);

		BossGear set = BossGearWindowGoldenTest.set();
		when(bossGear.getSet()).thenReturn(set);
		when(bossGear.isLoaded()).thenReturn(true);
		when(bossGear.isSetActive()).thenReturn(true);
		when(bossGear.getMode()).thenReturn(BossGear.MODE_OWNED);
		when(bossGear.carried(any(BossGear.Item.class))).thenReturn(1);

		when(gear.getPlan()).thenReturn(UiSamples.GSON.fromJson(GoalsUiGoldenTest.PLAN_JSON, GearPlan.class));

		SlayerGear slayerGear = UiSamples.GSON.fromJson(GearWindowsGoldenTest.SLAYER_GEAR_JSON, SlayerGear.class);
		when(slayer.getTask()).thenReturn(new SlayerHelperManager.Task(42, 0, 0, 7, "Abyssal demons", 143, 180));
		when(slayer.getGuide()).thenReturn(SlayerUiGoldenTest.GUIDE);
		when(slayer.getLocation()).thenReturn(SlayerUiGoldenTest.GUIDE.getLocations().get(0));
		when(slayer.getGear()).thenReturn(slayerGear);
		when(slayer.getGearSet()).thenReturn(slayerGear.getSets().get(0));
		when(slayer.getStats()).thenReturn(SlayerUiGoldenTest.stats());
		when(slayer.carried(anyInt())).thenReturn(1);

		Runnable none = () -> { };
		panel = new RoadmapPanel(none, none, none, none, none, kit.itemManager(), mock(SpriteManager.class), images, config,
			mock(ConfigManager.class), roadmaps, farmRun, bossGear, gear, slayer, new ClientThread());
	}

	private void call(String method, Class<?> type, Object arg) {
		try {
			Method m = type == null ? RoadmapPanel.class.getDeclaredMethod(method) : RoadmapPanel.class.getDeclaredMethod(method, type);
			m.setAccessible(true);
			if (type == null) {
				m.invoke(panel);
			} else {
				m.invoke(panel, arg);
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Shows a page, lets the panel's refresh tick run once (as its timer does), and renders it. */
	private UiKit.Rendered page(String name) {
		call("show", String.class, name);
		call("tick", null, null);
		return kit.render(panel, W, 0);
	}

	@Test
	public void mainMenu() {
		UiKit.golden("panel-main", page("main"));
	}

	@Test
	public void roadmapPage() {
		UiKit.golden("panel-roadmap", page("roadmap"));
	}

	@Test
	public void gearPage() {
		UiKit.golden("panel-gear", page("gear"));
	}

	@Test
	public void farmPage() {
		UiKit.golden("panel-farm", page("farm"));
	}

	@Test
	public void farmPageDuringARun() {
		when(farmRun.isRunning()).thenReturn(true);
		when(farmRun.getStopIndex()).thenReturn(1);
		UiKit.golden("panel-farm-running", page("farm"));
	}

	@Test
	public void bossPage() {
		UiKit.golden("panel-boss", page("boss"));
	}

	@Test
	public void bossPageWithGearDisplay() {
		bossDisplay = true;
		UiKit.golden("panel-boss-display", page("boss"));
	}

	@Test
	public void slayerPage() {
		UiKit.golden("panel-slayer", page("slayer"));
	}

	@Test
	public void tripsPage() {
		UiKit.golden("panel-trips", page("trips"));
	}
}
