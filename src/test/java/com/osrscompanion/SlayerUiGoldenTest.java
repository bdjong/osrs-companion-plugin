package com.osrscompanion;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.osrscompanion.model.SlayerGuide;
import org.junit.Test;

/** Golden renders of the Slayer helper's guide and stats box (see {@link UiKit}). */
public class SlayerUiGoldenTest {
	private final UiKit kit = new UiKit();
	private final SlayerHelperManager manager = mock(SlayerHelperManager.class);

	static final String GUIDE_JSON = "{\"task\":{\"task_id\":42,\"task_name\":\"Abyssal demons\",\"amount_remaining\":143,\"amount_original\":180,\"master\":\"Duradel\"},"
		+ "\"selected\":\"catacombs\",\"locations\":["
		+ "{\"key\":\"catacombs\",\"name\":\"Catacombs of Kourend\",\"tile\":[1675,10085,0],\"radius\":12,\"region_ids\":[6557],\"multi\":true,\"recommended\":true,"
		+ " \"why\":\"Multi-combat, totem pieces and prayer restore from bones.\",\"requirements\":[],"
		+ " \"travel\":{\"name\":\"Kharedst's memoirs\",\"how\":\"Lunch by the Lancalliums, then walk to the statue.\",\"items\":[21760]},"
		+ " \"steps\":[\"Climb down the statue in the middle of Kourend Castle's courtyard.\",\"Go north-west through the dark beasts' corridor.\"],"
		+ " \"monsters\":[\"Abyssal demon\"]},"
		+ "{\"key\":\"slayer-tower\",\"name\":\"Slayer Tower top floor\",\"tile\":[3417,3570,2],\"radius\":10,\"region_ids\":[13623],\"multi\":false,"
		+ " \"travel\":{\"name\":\"Slayer ring\",\"how\":\"Rub it and choose Morytania Slayer Tower.\",\"items\":[11866]},\"steps\":[],\"monsters\":[\"Abyssal demon\"]}"
		+ "]}";

	static final SlayerGuide GUIDE = UiSamples.GSON.fromJson(GUIDE_JSON, SlayerGuide.class);

	private final SlayerHelperManager.Task task = new SlayerHelperManager.Task(42, 0, 0, 7, "Abyssal demons", 143, 180);

	private SlayerHelperOverlay overlay() {
		return new SlayerHelperOverlay(manager, kit.itemManager());
	}

	/** A session of 20 minutes inside with 37 kills, just walked out (so the time no longer runs). */
	static SlayerHelperManager.Stats stats() {
		long t0 = 1_700_000_000_000L;
		SlayerSession s = SlayerSession.start("42:0:0:7", 42, "Abyssal demons", null, "Duradel", "catacombs", "Catacombs of Kourend", 180, 180, t0);
		long t = t0;
		for (int i = 0; i < 2000; i++) {
			s.tick(true, t);
			if (i % 54 == 53) {
				s.addKills(1, t);
				s.setRemaining(180 - s.kills, t);
			}
			t += 600;
		}
		s.tick(false, t);
		return new SlayerHelperManager.Stats(s, t);
	}

	@Test
	public void guidingToTheLocation() {
		when(manager.getTask()).thenReturn(task);
		when(manager.isGuiding()).thenReturn(true);
		when(manager.getLocation()).thenReturn(GUIDE.getLocations().get(0));
		when(manager.getDirection()).thenReturn("north-west");
		when(manager.getDistance()).thenReturn(87);
		UiKit.golden("slayer-overlay-guiding", kit.render(overlay(), 400, 400));
	}

	@Test
	public void guidingWithoutStepsOneTile() {
		when(manager.getTask()).thenReturn(task);
		when(manager.isGuiding()).thenReturn(true);
		when(manager.getLocation()).thenReturn(GUIDE.getLocations().get(1));
		when(manager.getDirection()).thenReturn("east");
		when(manager.getDistance()).thenReturn(1);
		UiKit.golden("slayer-overlay-guiding-short", kit.render(overlay(), 400, 300));
	}

	@Test
	public void fetchingTheNewTask() {
		when(manager.getTask()).thenReturn(task);
		when(manager.isGuideOn()).thenReturn(true);
		when(manager.isFetchingForTask()).thenReturn(true);
		UiKit.golden("slayer-overlay-fetching", kit.render(overlay(), 400, 200));
	}

	@Test
	public void noLocationForTheTask() {
		when(manager.getTask()).thenReturn(task);
		when(manager.isGuideOn()).thenReturn(true);
		when(manager.getGuide()).thenReturn(GUIDE);
		UiKit.golden("slayer-overlay-no-location", kit.render(overlay(), 400, 200));
	}

	@Test
	public void statsAtTheLocation() {
		when(manager.getTask()).thenReturn(task);
		when(manager.isAtLocation()).thenReturn(true);
		when(manager.getStats()).thenReturn(stats());
		UiKit.golden("slayer-overlay-stats", kit.render(overlay(), 300, 200));
	}

	@Test
	public void atTheLocationBeforeTheFirstTick() {
		when(manager.getTask()).thenReturn(new SlayerHelperManager.Task(42, 0, 0, 7, null, 12, 0));
		when(manager.isAtLocation()).thenReturn(true);
		UiKit.golden("slayer-overlay-stats-empty", kit.render(overlay(), 300, 200));
	}

	@Test
	public void nothingWithoutATask() {
		UiKit.golden("slayer-overlay-none", kit.render(overlay(), 100, 100));
	}
}
