package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.FarmRoute;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FarmRunManagerTest {
	@Test
	public void finishedBodyListsPatchesXpProfitAndRoute() {
		assertEquals("<col=ffffff>8 patches planted</col><br><col=ffffff>1,234 Farming XP - ~56K gp</col><br><col=c6c6c6>Herb run</col>",
				FarmRunManager.finishedBody("Herb run", 8, 1234, 56_000));
	}

	@Test
	public void finishedBodyLeavesOutUnknownNumbers() {
		assertEquals("<col=ffffff>1 patch planted</col>", FarmRunManager.finishedBody(null, 1, 0, 0));
	}

	@Test
	public void compostFlagFallsBackToThePatchType() {
		Gson gson = new Gson();
		assertTrue(gson.fromJson("{\"impl\":\"HERB\"}", FarmRoute.Patch.class).takesCompost());
		assertFalse(gson.fromJson("{\"impl\":\"COMPOST\"}", FarmRoute.Patch.class).takesCompost());
		assertFalse(gson.fromJson("{\"impl\":\"HERB\",\"compost\":false}", FarmRoute.Patch.class).takesCompost());
	}

	@Test
	public void routePictureIsTheMostVisitedPatchTypesMostPlantedCrop() {
		Gson gson = new Gson();
		// 3 tree patches (2 magic, 1 yew), 2 herbs, 4 compost bins: a magic sapling.
		String trees = "{\"stops\":[{\"patches\":["
			+ "{\"impl\":\"TREE\",\"seed\":5374},{\"impl\":\"TREE\",\"seed\":5374},{\"impl\":\"TREE\",\"seed\":5373},"
			+ "{\"impl\":\"HERB\",\"seed\":5295,\"produce\":207},{\"impl\":\"HERB\",\"seed\":5295,\"produce\":207},"
			+ "{\"impl\":\"COMPOST\",\"seed\":6032},{\"impl\":\"COMPOST\",\"seed\":6032},{\"impl\":\"COMPOST\",\"seed\":6032},{\"impl\":\"COMPOST\",\"seed\":6032}]}]}";
		assertEquals(Integer.valueOf(5374), gson.fromJson(trees, FarmRoute.class).pictureItem());
		// Mostly herbs: the herb they give, not the seed.
		String herbs = "{\"stops\":[{\"patches\":[{\"impl\":\"FRUIT_TREE\",\"seed\":5501},"
			+ "{\"impl\":\"HERB\",\"seed\":5295,\"produce\":207},{\"impl\":\"HERB\",\"seed\":5295,\"produce\":207}]}]}";
		assertEquals(Integer.valueOf(207), gson.fromJson(herbs, FarmRoute.class).pictureItem());
		assertNull(gson.fromJson("{\"stops\":[]}", FarmRoute.class).pictureItem());
	}

	@Test
	public void compostCountFollowsThePatchesNotTheRoute() {
		Gson gson = new Gson();
		List<FarmRoute.Stack> inv = Arrays.asList(
			gson.fromJson("{\"item\":5295,\"qty\":9,\"why\":\"Seed\"}", FarmRoute.Stack.class),
			gson.fromJson("{\"item\":21483,\"qty\":9,\"why\":\"Compost\",\"name\":\"Ultracompost\"}", FarmRoute.Stack.class));
		List<FarmRoute.Stack> four = FarmRunManager.withCompost(inv, 4);
		assertEquals(2, four.size());
		assertEquals(9, four.get(0).getQty());
		assertEquals(4, four.get(1).getQty());
		assertEquals("Ultracompost", four.get(1).getName());
		// No patch to compost: the compost is left out.
		assertEquals(1, FarmRunManager.withCompost(inv, 0).size());
		// A bottomless bucket is one item whatever the count.
		List<FarmRoute.Stack> bucket = Arrays.asList(gson.fromJson("{\"item\":22997,\"qty\":1,\"why\":\"Compost\"}", FarmRoute.Stack.class));
		assertEquals(1, FarmRunManager.withCompost(bucket, 5).get(0).getQty());
	}
}
