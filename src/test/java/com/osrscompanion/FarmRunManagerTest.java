package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.FarmRoute;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
