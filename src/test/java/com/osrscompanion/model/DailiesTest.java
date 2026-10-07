package com.osrscompanion.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

/** The dailies guide as the website sends it (GET /api/runelite/dailies/) reads into the model. */
public class DailiesTest {
	private static final String JSON = "{\"reset_at\":\"2026-10-08T00:00:00.000Z\",\"dailies\":[{\"id\":\"zaff\",\"name\":\"Battlestaves from Zaff\","
		+ "\"short\":\"Zaff's battlestaves\",\"reset\":\"daily\",\"tracking\":\"varbit\",\"icon\":1391,\"reward\":\"30 noted battlestaves at 7,000 gp each\","
		+ "\"amount\":30,\"value\":26910,\"state\":\"done\",\"note\":null,\"source\":\"plugin\",\"until\":\"2026-10-08T00:00:00.000Z\",\"npc\":\"Zaff\","
		+ "\"option\":\"Buy from the barrel in the shop's corner\",\"area\":\"Zaff's Superior Staffs, Varrock\",\"x\":3203,\"y\":3434,\"plane\":0,"
		+ "\"travel\":{\"name\":\"Varrock Teleport\",\"how\":\"Lands in Varrock Square.\",\"items\":[],\"spell\":true,\"walk\":false,\"minigame\":false},"
		+ "\"bring\":[{\"item\":995,\"qty\":210000,\"name\":\"Coins\",\"text\":\"210,000 coins\"}],\"steps\":[\"Buy them.\"]},"
		+ "{\"id\":\"tog\",\"name\":\"Tears of Guthix\",\"reset\":\"weekly\",\"value\":null,\"amount\":null,\"npc\":null}]}";

	@Test
	public void readsTheGuide() {
		Dailies d = new Gson().fromJson(JSON, Dailies.class);
		assertEquals("2026-10-08T00:00:00.000Z", d.getResetAt());
		assertEquals(2, d.getDailies().size());
		Dailies.Daily zaff = d.find("zaff");
		assertEquals("Zaff's battlestaves", zaff.getShortName());
		assertEquals(3203, zaff.getX());
		assertEquals(Long.valueOf(26910), zaff.getValue());
		assertEquals(Integer.valueOf(30), zaff.getAmount());
		assertTrue(zaff.getTravel().isSpell());
		assertEquals("Varrock Teleport", zaff.getTravel().getName());
		assertEquals("210,000 coins", zaff.getBring().get(0).getText());
		assertEquals("plugin", zaff.getSource());
	}

	@Test
	public void missingPartsHaveSafeDefaults() {
		Dailies.Daily tog = new Gson().fromJson(JSON, Dailies.class).find("tog");
		assertEquals("Tears of Guthix", tog.getShortName());
		assertNull(tog.getValue());
		assertNull(tog.getNpc());
		assertTrue(tog.getSteps().isEmpty());
		assertTrue(tog.getBring().isEmpty());
		assertEquals("", tog.getOption());
		assertNull(new Gson().fromJson(JSON, Dailies.class).find("nope"));
	}
}
