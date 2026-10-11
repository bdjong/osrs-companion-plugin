package com.osrscompanion;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.osrscompanion.model.DiaryTierResult;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public class VarPumpTest {
	private final VarPump pump = new VarPump(null, null, null, null, new Gson(), null);

	@Test
	public void sendsTheRawValuesWithWhatTheReaderMadeForComparison() {
		Map<Integer, Integer> varbits = new TreeMap<>();
		varbits.put(4499, 1);
		varbits.put(3566, 5);
		Map<Integer, Integer> varps = new TreeMap<>();
		varps.put(1196, 3);
		Map<String, Object> compare = Collections.singletonMap("achievement_diaries",
			Collections.singletonMap("Ardougne", Collections.singletonMap("Easy", new DiaryTierResult(true, Arrays.asList(true, true, false)))));
		assertEquals("{\"player_username\":\"Zezima\",\"varbits\":{\"3566\":5,\"4499\":1},\"varps\":{\"1196\":3},"
				+ "\"compare\":{\"achievement_diaries\":{\"Ardougne\":{\"Easy\":{\"complete\":true,\"tasks\":[true,true,false]}}}}}",
			pump.json("Zezima", varbits, varps, compare));
	}

	@Test
	public void withoutComparison() {
		assertEquals("{\"player_username\":\"Zezima\",\"varbits\":{},\"varps\":{}}",
			pump.json("Zezima", new TreeMap<>(), new TreeMap<>(), null));
	}

	@Test
	public void theWatchListReadsTheWebsitesAnswer() {
		VarPump.WatchList w = new Gson().fromJson("{\"varbits\":[4499,3566],\"varps\":[1196,1198,1199]}", VarPump.WatchList.class);
		assertEquals(2, w.varbits.length);
		assertEquals(1199, w.varps[2]);
	}
}
