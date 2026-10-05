package com.osrscompanion.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

public class GearPlanTest {
	// Shape of GET /api/runelite/gear/ (trimmed).
	private static final String JSON = "{\"plan\":{\"id\":1,\"template\":\"ironman\",\"title\":\"Ironman gear progression\","
			+ "\"done\":1,\"total\":3,\"next_tier\":1,\"next_step\":2,\"tiers\":[{\"tier\":1,\"name\":\"Early game\",\"done\":1,\"total\":3,"
			+ "\"steps\":[{\"step\":1,\"items\":[{\"id\":10,\"key\":\"rune-pouch\",\"name\":\"Rune pouch\",\"kind\":\"item\",\"item_id\":12791,"
			+ "\"image_url\":\"https://oldschool.runescape.wiki/images/Rune_pouch.png\",\"done\":true,\"auto\":true,\"manual\":false,\"next\":false,"
			+ "\"how\":\"Ticks itself off once it is in your bank.\"}]},"
			+ "{\"step\":2,\"items\":[{\"id\":11,\"key\":\"piety\",\"name\":\"Piety\",\"kind\":\"prayer\",\"item_id\":null,"
			+ "\"image_url\":\"https://oldschool.runescape.wiki/images/Piety.png\",\"done\":false,\"auto\":false,\"manual\":false,\"next\":true,\"how\":\"x\"},"
			+ "{\"id\":12,\"key\":\"spirit-tree\",\"name\":\"Spirit tree\",\"kind\":\"poh\",\"item_id\":null,\"image_url\":null,"
			+ "\"done\":false,\"auto\":false,\"manual\":false,\"next\":true,\"how\":\"Mark it done yourself.\"}]}]}]}}";

	@Test
	public void parsesTheServerShape() {
		GearPlan plan = new Gson().fromJson(JSON, GearPlan.Response.class).getPlan();
		assertEquals("Ironman gear progression", plan.getTitle());
		assertEquals(1, plan.getDone());
		assertEquals(3, plan.getTotal());
		assertEquals(Integer.valueOf(1), plan.getNextTier());

		GearPlan.Tier tier = plan.getTiers().get(0);
		assertEquals("Early game", tier.getName());
		assertEquals(2, tier.getSteps().size());

		GearPlan.Step first = tier.getSteps().get(0);
		assertTrue(first.isDone());
		assertFalse(first.isNext());
		GearPlan.Item pouch = first.getItems().get(0);
		assertEquals(Integer.valueOf(12791), pouch.getItemId());
		assertTrue(pouch.isAuto());

		GearPlan.Step second = tier.getSteps().get(1);
		assertEquals("two items on the same level", 2, second.getItems().size());
		assertTrue(second.isNext());
		assertFalse(second.isDone());
		assertNull(second.getItems().get(0).getItemId());
		assertEquals("prayer", second.getItems().get(0).getKind());
	}

	@Test
	public void findsItemsById() {
		GearPlan plan = new Gson().fromJson(JSON, GearPlan.Response.class).getPlan();
		assertEquals("Spirit tree", plan.findItem(12).getName());
		assertNull(plan.findItem(99));
	}

	@Test
	public void noPlanYet() {
		assertNull(new Gson().fromJson("{\"plan\":null}", GearPlan.Response.class).getPlan());
	}
}
