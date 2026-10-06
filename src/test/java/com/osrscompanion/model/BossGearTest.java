package com.osrscompanion.model;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BossGearTest {
	@Test
	public void parsesTheActiveSetAndTheSavedSets() {
		String json = "{\"sets\":[{\"id\":3,\"boss\":\"vorkath\",\"boss_name\":\"Vorkath\",\"setup\":0,\"style\":\"Ranged\",\"inventory\":null,\"active\":true}],"
			+ "\"set\":{\"id\":3,\"boss\":\"vorkath\",\"boss_name\":\"Vorkath\",\"style\":\"Ranged\",\"inventory_label\":\"Ranged\",\"synced\":true,"
			+ "\"equipment\":[{\"slot\":\"weapon\",\"item\":12926,\"name\":\"Toxic blowpipe\",\"ids\":[12924,12926],\"owned\":true,\"status\":\"bis\",\"rank\":1,\"bis\":true}],"
			+ "\"inventory\":[{\"item\":385,\"name\":\"Shark\",\"ids\":[385],\"owned\":false},null,{\"item\":2434,\"name\":\"Prayer potion(4)\",\"ids\":[2434],\"owned\":true,\"qty\":4,\"noted\":true}],"
			+ "\"pouch\":[{\"item\":560,\"name\":\"Death rune\",\"ids\":[560],\"owned\":true,\"qty\":1000}]}}";
		BossGear.Response r = new Gson().fromJson(json, BossGear.Response.class);
		assertEquals(1, r.getSets().size());
		assertEquals("Vorkath - Ranged", r.getSets().get(0).getName());
		assertTrue(r.getSets().get(0).isActive());
		BossGear s = r.getSet();
		assertEquals(Arrays.asList(12924, 12926), s.slot("weapon").getIds());
		assertEquals("bis", s.slot("weapon").getStatus());
		assertNull(s.slot("head"));
		assertEquals(3, s.getInventory().size());
		assertNull(s.getInventory().get(1));
		assertEquals(1, s.getInventory().get(0).getQty());
		assertEquals(4, s.getInventory().get(2).getQty());
		assertTrue(s.getInventory().get(2).isNoted());
		assertEquals(1000, s.getPouch().get(0).getQty());
	}

	@Test
	public void noActiveSet() {
		BossGear.Response r = new Gson().fromJson("{\"set\":null,\"sets\":[]}", BossGear.Response.class);
		assertNull(r.getSet());
		assertEquals(Collections.emptyList(), r.getSets());
	}

	@Test
	public void idsFallBackToTheItem() {
		BossGear.Item it = new Gson().fromJson("{\"item\":385}", BossGear.Item.class);
		assertEquals(Collections.singletonList(385), it.getIds());
	}
}
