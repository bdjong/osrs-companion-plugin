package com.osrscompanion.model;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
	public void parsesOwnedAlternatives() {
		String json = "{\"mode\":\"alternatives\",\"alternatives\":true,\"sets\":[],\"set\":{\"id\":4,\"equipment\":["
			+ "{\"slot\":\"neck\",\"item\":6585,\"name\":\"Amulet of fury\",\"ids\":[6585],\"owned\":true,\"status\":\"alt\",\"rank\":null,\"bis\":false,"
			+ "\"alt_for\":\"Salve amulet(ei)\"},"
			+ "{\"slot\":\"head\",\"item\":3749,\"name\":\"Archer helm\",\"owned\":true,\"status\":\"partial\",\"rank\":5}]}}";
		BossGear.Response r = new Gson().fromJson(json, BossGear.Response.class);
		assertEquals(BossGear.MODE_ALTERNATIVES, r.getMode());
		BossGear.Slot neck = r.getSet().slot("neck");
		assertTrue(neck.isAlternative());
		assertEquals("Salve amulet(ei)", neck.getAltFor());
		assertNull(neck.getRank());
		assertFalse(r.getSet().slot("head").isAlternative());
		assertEquals(BossGear.MODE_OWNED, new Gson().fromJson("{\"set\":null}", BossGear.Response.class).getMode());
	}

	@Test
	public void parsesGearModesSwitchWeaponAndWikiLink() {
		String json = "{\"mode\":\"bis\",\"set\":{\"id\":5,\"url\":\"https://oldschool.runescape.wiki/w/The_Leviathan/Strategies\",\"two_handed\":true,"
			+ "\"equipment\":[{\"slot\":\"weapon\",\"item\":20997,\"name\":\"Twisted bow\",\"owned\":true,\"status\":\"missing\",\"rank\":1},"
			+ "{\"slot\":\"switch\",\"item\":12926,\"name\":\"Toxic blowpipe\",\"owned\":true,\"status\":\"bis\",\"rank\":1}]}}";
		BossGear.Response r = new Gson().fromJson(json, BossGear.Response.class);
		assertEquals(BossGear.MODE_BIS, r.getMode());
		assertEquals("https://oldschool.runescape.wiki/w/The_Leviathan/Strategies", r.getSet().getUrl());
		assertTrue(r.getSet().isTwoHanded());
		assertNull(r.getSet().slot("shield"));
		assertEquals("Toxic blowpipe", r.getSet().slot("switch").getName());
		// Older websites: the alternatives switch, else the default mode.
		assertEquals(BossGear.MODE_ALTERNATIVES, new Gson().fromJson("{\"alternatives\":true}", BossGear.Response.class).getMode());
		assertEquals(BossGear.MODE_OWNED, new Gson().fromJson("{\"mode\":\"what\"}", BossGear.Response.class).getMode());
		assertEquals(BossGear.MODE_OWNED, BossGear.nextMode(BossGear.MODE_BIS));
		assertEquals(BossGear.MODE_BIS, BossGear.nextMode(BossGear.MODE_ALTERNATIVES));
		assertEquals("Best owned", BossGear.modeLabel(BossGear.MODE_OWNED));
	}

	@Test
	public void parsesInventoryStandIns() {
		String json = "{\"mode\":\"owned\",\"set\":{\"id\":6,\"equipment\":[],"
			+ "\"inventory\":[{\"item\":12695,\"name\":\"Super combat potion(4)\",\"ids\":[12699,12695],\"owned\":true,\"status\":\"alt\","
			+ "\"alt_for\":\"Divine super combat potion(4)\"},{\"item\":385,\"name\":\"Shark\",\"owned\":true},null],"
			+ "\"pouch\":[{\"item\":560,\"name\":\"Death rune\",\"owned\":true,\"status\":\"alt\"}]}}";
		BossGear s = new Gson().fromJson(json, BossGear.Response.class).getSet();
		BossGear.Item potion = s.getInventory().get(0);
		assertTrue(potion.isAlternative());
		assertEquals("Divine super combat potion(4)", potion.getAltFor());
		assertEquals(Arrays.asList(12699, 12695), potion.getIds());
		assertFalse(s.getInventory().get(1).isAlternative());
		assertEquals("unknown", s.getInventory().get(1).getStatus());
		assertTrue(s.getPouch().get(0).isAlternative());
		assertEquals("the wiki's item", s.getPouch().get(0).getAltFor());
		BossGear.Slot slot = new Gson().fromJson("{\"slot\":\"neck\",\"status\":\"alt\"}", BossGear.Slot.class);
		assertEquals("the best in slot", slot.getAltFor());
	}

	@Test
	public void parsesMechanicNeeds() {
		BossGear.Item antifire = new Gson().fromJson("{\"item\":22209,\"name\":\"Extended super antifire(4)\",\"owned\":false,"
			+ "\"need\":\"Needed against dragonfire\"}", BossGear.Item.class);
		assertEquals("Needed against dragonfire", antifire.getNeed());
		assertNull(new Gson().fromJson("{\"item\":385,\"need\":\"\"}", BossGear.Item.class).getNeed());
		BossGear.Slot body = new Gson().fromJson("{\"slot\":\"body\",\"item\":23975,\"status\":\"missing\","
			+ "\"need\":\"Worn with the Bow of Faerdhinen\"}", BossGear.Slot.class);
		assertEquals("Worn with the Bow of Faerdhinen", body.getNeed());
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
