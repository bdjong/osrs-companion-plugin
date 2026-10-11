package com.osrscompanion;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.reflect.TypeToken;
import com.osrscompanion.model.GearPlan;
import com.osrscompanion.model.SlayerGear;
import java.awt.Color;
import java.util.List;
import net.runelite.api.Client;
import org.junit.Before;
import org.junit.Test;

/** Golden renders of the slayer gear window and the gear progression window (see {@link UiKit}, {@link WindowDriver}). */
public class GearWindowsGoldenTest {
	private static final int W = 1000;
	private static final int H = 700;

	private final UiKit kit = new UiKit();
	private final Client client = mock(Client.class);

	@Before
	public void setUp() {
		when(client.getCanvasWidth()).thenReturn(W);
		when(client.getCanvasHeight()).thenReturn(H);
	}

	// ---- slayer gear ---------------------------------------------------------------------------------

	static final String SLAYER_GEAR_JSON = "{\"task\":\"Abyssal demons\",\"monster\":\"Abyssal demon\",\"sets\":["
		+ "{\"key\":\"melee\",\"label\":\"Melee\",\"max_hit\":44,\"dps\":6.81,\"kills_per_hour\":121.4,"
		+ " \"equipment\":[{\"slot\":\"head\",\"item\":11865,\"name\":\"Slayer helmet (i)\"},{\"slot\":\"weapon\",\"item\":12006,\"name\":\"Abyssal tentacle\"},"
		+ "  {\"slot\":\"body\",\"item\":10551,\"name\":\"Fighter torso\"},{\"slot\":\"shield\",\"item\":12954,\"name\":\"Dragon defender\"},{\"slot\":\"neck\",\"item\":6585,\"name\":\"Amulet of fury\"},"
		+ "  {\"slot\":\"legs\",\"item\":4087,\"name\":\"Dragon platelegs\"},{\"slot\":\"hands\",\"item\":7462,\"name\":\"Barrows gloves\"},{\"slot\":\"feet\",\"item\":11840,\"name\":\"Dragon boots\"},"
		+ "  {\"slot\":\"ring\",\"item\":11773,\"name\":\"Berserker ring (i)\"},{\"slot\":\"cape\",\"item\":6570,\"name\":\"Fire cape\"}],"
		+ " \"extras\":[{\"label\":\"Prayer\",\"name\":\"Piety\"},{\"label\":\"Boost\",\"name\":\"Super combat potion\"}],"
		+ " \"bring\":[{\"kind\":\"food\",\"name\":\"Shark\",\"items\":[385]}]},"
		+ "{\"key\":\"burst\",\"label\":\"Ice Burst (3 targets)\",\"max_hit\":22,\"dps\":9.4,\"kills_per_hour\":168.0,\"spell\":\"Ice Burst\","
		+ " \"equipment\":[{\"slot\":\"head\",\"item\":11865,\"name\":\"Slayer helmet (i)\"},{\"slot\":\"weapon\",\"item\":12899,\"name\":\"Trident of the swamp\"}],"
		+ " \"extras\":[],\"supplies\":{\"kind\":\"runes\",\"options\":[{\"name\":\"Ice Burst\",\"items\":[560,565]},{\"name\":\"Ice Blitz\",\"items\":[565,555]}]},\"bring\":[]}"
		+ "]}";

	private final SlayerHelperManager slayer = mock(SlayerHelperManager.class);

	private SlayerGearWindow slayerWindow() {
		SlayerGear gear = UiSamples.GSON.fromJson(SLAYER_GEAR_JSON, SlayerGear.class);
		when(slayer.isGearOpen()).thenReturn(true);
		when(slayer.getGear()).thenReturn(gear);
		when(slayer.getGearSet()).thenReturn(gear.getSets().get(0));
		when(slayer.getTask()).thenReturn(new SlayerHelperManager.Task(42, 0, 0, 7, "Abyssal demons", 143, 180));
		when(slayer.carried(anyInt())).thenAnswer(a -> ((Integer) a.getArgument(0)) % 2);
		return new SlayerGearWindow(client, slayer, kit.itemManager(), new TestOSRSCompanionConfig());
	}

	private UiKit.Rendered twice(Object window) {
		kit.render((net.runelite.client.ui.overlay.Overlay) window, W, H);
		return kit.render((net.runelite.client.ui.overlay.Overlay) window, W, H);
	}

	@Test
	public void slayerGearMeleePicked() {
		UiKit.golden("slayer-gear-window-melee", twice(slayerWindow()));
	}

	@Test
	public void slayerGearBurstPickedHoverPiece() {
		SlayerGearWindow window = slayerWindow();
		SlayerGear gear = slayer.getGear();
		when(slayer.getGearSet()).thenReturn(gear.getSets().get(1));
		twice(window);
		WindowDriver.move(window, WindowDriver.area(window, "Trident of the swamp").centre());
		UiKit.golden("slayer-gear-window-burst-tooltip", kit.render(window, W, H));
	}

	@Test
	public void slayerGearLoading() {
		SlayerGearWindow window = slayerWindow();
		when(slayer.getGear()).thenReturn(null);
		when(slayer.getGearSet()).thenReturn(null);
		when(slayer.isGearLoading()).thenReturn(true);
		UiKit.golden("slayer-gear-window-loading", twice(window));
	}

	@Test
	public void slayerGearNothingForTheTask() {
		SlayerGearWindow window = slayerWindow();
		when(slayer.getGear()).thenReturn(null);
		when(slayer.getGearSet()).thenReturn(null);
		UiKit.golden("slayer-gear-window-empty", twice(window));
	}

	// ---- gear progression ---------------------------------------------------------------------------

	private final GearProgressionManager gear = mock(GearProgressionManager.class);
	private final GoalImageCache images = mock(GoalImageCache.class);

	private GearProgressionOverlay gearWindow() {
		GearPlan plan = UiSamples.GSON.fromJson(GoalsUiGoldenTest.PLAN_JSON, GearPlan.class);
		when(gear.isOpen()).thenReturn(true);
		when(gear.isLoaded()).thenReturn(true);
		when(gear.getPlan()).thenReturn(plan);
		List<GearPlan.Summary> plans = UiSamples.GSON.fromJson("[{\"id\":1,\"name\":\"Ironman gear\",\"active\":true},{\"id\":2,\"name\":\"Main gear\",\"active\":false}]",
			new TypeToken<List<GearPlan.Summary>>() { }.getType());
		when(gear.getPlans()).thenReturn(plans);
		when(gear.getSelectedTier()).thenReturn(plan.getTiers().get(1));
		when(images.get("https://oldschool.runescape.wiki/images/Fire_cape.png")).thenReturn(kit.image("wiki:fire-cape", 30, 32, Color.RED));
		return new GearProgressionOverlay(client, gear, kit.itemManager(), images);
	}

	@Test
	public void gearTierWithNextStep() {
		UiKit.golden("gear-window-tier", twice(gearWindow()));
	}

	@Test
	public void gearItemSelected() {
		GearProgressionOverlay window = gearWindow();
		GearPlan.Item gloves = gear.getPlan().getTiers().get(1).getSteps().get(1).getItems().get(0);
		when(gear.getSelectedItem()).thenReturn(gloves);
		UiKit.golden("gear-window-item-selected", twice(window));
	}

	@Test
	public void gearHoverItemTooltip() {
		GearProgressionOverlay window = gearWindow();
		twice(window);
		WindowDriver.move(window, WindowDriver.area(window, "Fire cape").centre());
		UiKit.golden("gear-window-item-tooltip", kit.render(window, W, H));
	}

	@Test
	public void gearSwitcherOpen() {
		GearProgressionOverlay window = gearWindow();
		twice(window);
		WindowDriver.click(window, WindowDriver.area(window, "Switch to another roadmap").centre());
		UiKit.golden("gear-window-switcher", kit.render(window, W, H));
	}

	@Test
	public void gearBusyWithStatus() {
		GearProgressionOverlay window = gearWindow();
		when(gear.isBusy()).thenReturn(true);
		when(gear.getStatus()).thenReturn("Saving...");
		UiKit.golden("gear-window-busy", twice(window));
	}

	@Test
	public void gearNoPlanError() {
		GearProgressionOverlay window = gearWindow();
		when(gear.getPlan()).thenReturn(null);
		when(gear.getError()).thenReturn("Load the ironman gear progression on the website first.");
		UiKit.golden("gear-window-error", twice(window));
	}
}
