package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public class VarPumpTest {
	private final OSRSCompanionConfig config = mock(OSRSCompanionConfig.class);
	private final VarPump pump = new VarPump(null, null, config, null, new Gson());

	@Test
	public void sendsTheRawValuesOfTheSourcesThatAreOn() {
		Map<Integer, Integer> varbits = new TreeMap<>();
		varbits.put(4499, 1);
		varbits.put(3566, 5);
		Map<Integer, Integer> varps = new TreeMap<>();
		varps.put(1196, 3);
		assertEquals("{\"player_username\":\"Zezima\",\"sources\":[\"achievement_diaries\",\"kingdom\"],"
				+ "\"varbits\":{\"3566\":5,\"4499\":1},\"varps\":{\"1196\":3},\"quests\":{\"THRONE_OF_MISCELLANIA\":\"FINISHED\"},"
				+ "\"region\":10044,\"session_start\":1760000000000}",
			pump.json("Zezima", Arrays.asList("achievement_diaries", "kingdom"), varbits, varps,
				Collections.singletonMap("THRONE_OF_MISCELLANIA", "FINISHED"), 10044, 1760000000000L));
	}

	@Test
	public void leavesOutWhatIsNotKnown() {
		assertEquals("{\"player_username\":\"Zezima\",\"sources\":[],\"varbits\":{},\"varps\":{},\"quests\":{}}",
			pump.json("Zezima", Collections.emptyList(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), null, 0));
	}

	@Test
	public void theWatchListReadsTheWebsitesAnswer() {
		VarPump.WatchList w = new Gson().fromJson("{\"sources\":{\"kingdom\":{\"varbits\":[72,74],\"varps\":[],"
			+ "\"quests\":[\"THRONE_OF_MISCELLANIA\"],\"regions\":[10044,10300]},\"combat_achievements\":{\"varbits\":[],"
			+ "\"varps\":[3116],\"quests\":[],\"regions\":[]}}}", VarPump.WatchList.class);
		assertEquals(2, w.sources.size());
		assertEquals(74, w.sources.get("kingdom").varbits[1]);
		assertEquals("THRONE_OF_MISCELLANIA", w.sources.get("kingdom").quests[0]);
		assertEquals(3116, w.sources.get("combat_achievements").varps[0]);
	}

	@Test
	public void eachSourceFollowsItsSyncSwitch() {
		when(config.syncAchievementDiaries()).thenReturn(true);
		when(config.syncTimeTracking()).thenReturn(true);
		assertTrue(pump.enabled("achievement_diaries"));
		assertTrue(pump.enabled("farming_unlocks"));
		assertFalse(pump.enabled("kingdom"));
		assertFalse(pump.enabled("dailies"));
		assertFalse(pump.enabled("something_newer"));
	}
}
