package com.osrscompanion;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.osrscompanion.DailyChecks.State;
import com.osrscompanion.model.Dailies;
import java.util.Arrays;
import org.junit.Test;

/** Golden renders of the dailies guide's step box (see {@link UiKit}). */
public class DailiesUiGoldenTest {
	private final UiKit kit = new UiKit();
	private final TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
	private final DailiesManager manager = mock(DailiesManager.class);

	static Dailies.Daily daily(String json) {
		return new Gson().fromJson(json, Dailies.Daily.class);
	}

	static final Dailies.Daily BATTLESTAVES = daily("{\"id\":\"battlestaves\",\"name\":\"Battlestaves from Zaff\",\"reset\":\"daily\","
		+ "\"icon\":1391,\"npc\":\"Zaff\",\"option\":\"Buy-battlestaves\",\"area\":\"Zaff's Superior Staffs, Varrock\","
		+ "\"travel\":{\"name\":\"Varrock teleport\",\"how\":\"Teleport to Varrock and walk north-west past the fountain to the shop.\",\"items\":[8007]},"
		+ "\"bring\":[{\"item\":995,\"qty\":105000,\"text\":\"105k coins (up to 120 staves at 7k each with the Varrock hard diary)\"}],"
		+ "\"steps\":[\"Talk to Zaff and choose Buy-battlestaves.\",\"Bank or note them at the bank behind the shop.\"]}");

	static final Dailies.Daily SAND = daily("{\"id\":\"sand\",\"name\":\"Buckets of sand from Bert\",\"reset\":\"daily\","
		+ "\"icon\":1783,\"option\":\"\",\"area\":\"Yanille\",\"travel\":{\"name\":\"Watchtower Teleport\",\"how\":\"Needs Watchtower; lands in the middle of Yanille.\",\"items\":[],\"spell\":true},"
		+ "\"bring\":[],\"steps\":[\"Talk to Bert in the house south of the bank.\"]}");

	private DailiesManager.Entry entry(String id, String name, State state, String note, Dailies.Daily guide, boolean tracked, int amount) {
		return new DailiesManager.Entry(id, name, state, note, false, guide, tracked, amount);
	}

	private DailiesOverlay overlay() {
		return new DailiesOverlay(config, manager, kit.itemManager());
	}

	private void show(DailiesManager.Entry current, DailiesManager.Entry... all) {
		when(manager.current()).thenReturn(current);
		when(manager.entries()).thenReturn(Arrays.asList(all));
	}

	@Test
	public void guideStepWithTravelBringAndNote() {
		DailiesManager.Entry e = entry("battlestaves", "Battlestaves from Zaff", State.OPEN, "120 left to buy today", BATTLESTAVES, true, 120);
		show(e, e, entry("sand", "Buckets of sand", State.DONE, null, SAND, true, 0), entry("flax", "Flax", State.OPEN, null, null, false, 0));
		UiKit.golden("dailies-overlay-step", kit.render(overlay(), 400, 400));
	}

	@Test
	public void spellTravelWithoutNpcNotTracked() {
		DailiesManager.Entry e = entry("sand", "Buckets of sand", State.OPEN, null, SAND, false, 0);
		show(e, e);
		UiKit.golden("dailies-overlay-spell", kit.render(overlay(), 400, 400));
	}

	@Test
	public void guideNotLoadedYet() {
		DailiesManager.Entry e = entry("flax", "Flax", State.OPEN, null, null, false, 0);
		show(e, e);
		when(manager.isLoading()).thenReturn(true);
		UiKit.golden("dailies-overlay-loading", kit.render(overlay(), 400, 200));
	}

	@Test
	public void guideFailedToLoad() {
		DailiesManager.Entry e = entry("flax", "Flax", State.OPEN, null, null, false, 0);
		show(e, e);
		when(manager.getError()).thenReturn("Could not reach OSRS Companion (timeout)");
		UiKit.golden("dailies-overlay-error", kit.render(overlay(), 400, 200));
	}

	@Test
	public void nothingWithoutARun() {
		show(null);
		UiKit.golden("dailies-overlay-none", kit.render(overlay(), 100, 100));
	}
}
