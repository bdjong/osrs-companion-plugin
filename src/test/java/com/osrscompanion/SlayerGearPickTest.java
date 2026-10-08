package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.SlayerGear;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The Slayer gear window's picked setup (stored per task) and its bank layout. */
public class SlayerGearPickTest {
	private static final String GEAR = "{\"task\":\"Abyssal demons\",\"monster\":\"Abyssal demon\",\"sets\":["
		+ "{\"key\":\"melee\",\"label\":\"Melee\",\"max_hit\":42,\"dps\":5.31,\"kills_per_hour\":118,"
		+ "\"equipment\":[{\"slot\":\"head\",\"item\":11864,\"name\":\"Slayer helmet\"},{\"slot\":\"weapon\",\"item\":4151,\"name\":\"Abyssal whip\"},"
		+ "{\"slot\":\"weapon\",\"item\":4587,\"name\":\"Dragon scimitar\"},{\"slot\":\"ring\",\"item\":0,\"name\":\"None\"}],"
		+ "\"extras\":[{\"label\":\"Potion\",\"name\":\"Super combat potion\"}]},"
		+ "{\"key\":\"ranged\",\"label\":\"Ranged\",\"max_hit\":30,\"dps\":4.1,\"kills_per_hour\":90},"
		+ "{\"key\":\"barrage\",\"label\":\"Barrage\",\"max_hit\":30,\"dps\":3.2,\"kills_per_hour\":300,\"spell\":\"Ice Barrage\"}]}";

	private final SlayerGear gear = new Gson().fromJson(GEAR, SlayerGear.class);

	@Test
	public void picksTheStoredSetupElseTheBest() {
		assertEquals(0, SlayerHelperManager.gearIndex(gear.getSets(), null));
		assertEquals(2, SlayerHelperManager.gearIndex(gear.getSets(), "barrage"));
		assertEquals(1, SlayerHelperManager.gearIndex(gear.getSets(), "ranged"));
		// A setup that is gone (no longer owned / calculated) falls back to the best.
		assertEquals(0, SlayerHelperManager.gearIndex(gear.getSets(), "magic"));
	}

	@Test
	public void storesThePickPerTask() {
		assertEquals("slayerGearSet.abyssal_demons", SlayerHelperManager.gearConfigKey(gear));
		assertNull(SlayerHelperManager.gearConfigKey(null));
		assertNull(SlayerHelperManager.gearConfigKey(new Gson().fromJson("{\"sets\":[]}", SlayerGear.class)));
		assertEquals("slayerGearSet.kalphite_queen",
			SlayerHelperManager.gearConfigKey(new Gson().fromJson("{\"monster\":\"Kalphite Queen\"}", SlayerGear.class)));
	}

	@Test
	public void laysOutTheWornGearOnePerSlot() {
		Map<String, Integer> worn = BankLayoutPrep.slayerWorn(gear.getSets().get(0), id -> id);
		assertEquals(2, worn.size());
		assertEquals(Integer.valueOf(11864), worn.get("head"));
		assertEquals(Integer.valueOf(4151), worn.get("weapon"));
		int[] positions = BankLayouts.build(worn, java.util.Collections.emptyList(), java.util.Collections.emptyList(), -1);
		assertEquals(11864, positions[BankLayouts.EQUIPMENT_POS.get("head")]);
		assertEquals(4151, positions[BankLayouts.EQUIPMENT_POS.get("weapon")]);
	}

	@Test
	public void anOpenWindowDrivesTheBank() {
		assertNull(BankLayoutPrep.pick(false, false, false, false, false));
		assertEquals(BankLayoutPrep.Driver.SLAYER, BankLayoutPrep.pick(false, false, false, true, false));
		assertEquals(BankLayoutPrep.Driver.SLAYER, BankLayoutPrep.pick(true, false, true, true, true));
		assertEquals(BankLayoutPrep.Driver.BOSS, BankLayoutPrep.pick(true, true, true, true, true));
		assertEquals(BankLayoutPrep.Driver.FARM, BankLayoutPrep.pick(true, false, true, true, false));
		assertEquals(BankLayoutPrep.Driver.BOSS, BankLayoutPrep.pick(true, false, false, true, false));
	}

	@Test
	public void numbersLine() {
		assertEquals("Max 42 · 5.31 DPS · 118 kills/h", SlayerGearWindow.numbers(gear.getSets().get(0)));
	}

	@Test
	public void supplyOptionsBestFirstAsManyAsSet() {
		SlayerGear.Set set = new Gson().fromJson("{\"key\":\"magic\",\"spell\":\"Fire Surge\",\"supplies\":{\"kind\":\"spell\",\"options\":["
			+ "{\"name\":\"Fire Surge\",\"items\":[21880,556,554]},{\"name\":\"Fire Wave\",\"items\":[565,556,554]},"
			+ "{\"name\":\"Fire Blast\",\"items\":[560,556,554]},{\"name\":\"Fire Bolt\",\"items\":[562,556,554]}]}}", SlayerGear.Set.class);
		// Runes once each, in the order of the spells.
		assertEquals(java.util.Arrays.asList(21880, 556, 554, 565, 560), set.supplyItems(3));
		assertEquals("Fire Surge > Fire Wave > Fire Blast", set.supplyText(3));
		assertEquals(java.util.Collections.emptyList(), set.supplyItems(0));
		assertNull(set.supplyText(0));
		assertEquals(java.util.Collections.emptyList(), gear.getSets().get(0).supplyItems(3));
	}
}
