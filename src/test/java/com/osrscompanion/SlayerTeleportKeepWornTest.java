package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.SlayerGear;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Slayer helper's teleport left click leaves an item the suggested setup wears on "Wear". */
public class SlayerTeleportKeepWornTest {
	private static final int MAX_CAPE = 13280;
	private static final int RING_OF_DUELING_8 = 2552;
	private static final int RING_OF_DUELING_3 = 2562;
	private static final int ARDOUGNE_CLOAK_4 = 13124;

	private static SlayerGear.Set set(String equipment) {
		return new Gson().fromJson("{\"key\":\"melee\",\"equipment\":[" + equipment + "]}", SlayerGear.Set.class);
	}

	private final SlayerGear.Set withCape = set("{\"slot\":\"cape\",\"item\":" + MAX_CAPE + ",\"name\":\"Max cape\"},"
		+ "{\"slot\":\"ring\",\"item\":" + RING_OF_DUELING_8 + ",\"name\":\"Ring of dueling(8)\"},"
		+ "{\"slot\":\"weapon\",\"item\":4151,\"name\":\"Abyssal whip\"},{\"slot\":\"ammo\",\"item\":0,\"name\":\"None\"}");

	@Test
	public void keepsTheLeftClickOfAWornSetupItem() {
		assertTrue(SlayerHelperManager.keepsWornLeftClick(MAX_CAPE, "<col=ff9040>Max cape</col>", withCape));
	}

	@Test
	public void matchesAnotherVersionOfTheItem() {
		// Charges: the setup names one dose, the inventory holds another.
		assertTrue(SlayerHelperManager.keepsWornLeftClick(RING_OF_DUELING_3, "<col=ff9040>Ring of dueling(3)</col>", withCape));
	}

	@Test
	public void matchesByNameWhenTheEntryHasNoItemId() {
		assertTrue(SlayerHelperManager.keepsWornLeftClick(-1, "<col=ff9040>Max cape</col>", withCape));
	}

	@Test
	public void swapsATeleportItemTheSetupDoesNotWear() {
		assertFalse(SlayerHelperManager.keepsWornLeftClick(ARDOUGNE_CLOAK_4, "<col=ff9040>Ardougne cloak 4</col>", withCape));
		// An empty slot ("None", item 0) matches nothing.
		assertFalse(SlayerHelperManager.keepsWornLeftClick(0, "None", withCape));
	}

	@Test
	public void withoutASetupNothingIsKept() {
		assertFalse(SlayerHelperManager.keepsWornLeftClick(MAX_CAPE, "Max cape", null));
		assertFalse(SlayerHelperManager.keepsWornLeftClick(MAX_CAPE, "Max cape", set("")));
	}
}
