package com.osrscompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SlayerBossesTest {
	private static final long START = 1_791_374_400_000L; // 2026-10-07T12:00:00Z

	@Test
	public void bossVariantsCountForTheirTaskOnly() {
		assertTrue(SlayerBosses.isBoss("Hydras", "Alchemical Hydra"));
		assertTrue(SlayerBosses.isBoss("Hellhounds", "<col=ffff00>Cerberus</col>"));
		assertTrue(SlayerBosses.isBoss("Cave krakens", "Kraken"));
		assertTrue(SlayerBosses.isBoss("Gargoyles", "Dusk"));
		assertTrue(SlayerBosses.isBoss("Dagannoth", "dagannoth prime"));
		assertTrue(SlayerBosses.isBoss("Araxytes", "Araxxor"));
		// Both phases of the Kalphite Queen have the same name, on a "Kalphite" (or "Kalphites") task.
		assertTrue(SlayerBosses.isBoss("Kalphite", "Kalphite Queen"));
		assertTrue(SlayerBosses.isBoss("Kalphites", "Kalphite Queen"));
		assertFalse(SlayerBosses.isBoss("Kalphite", "Kalphite Soldier"));
		// The regular monster, another task's boss, a variant that is no boss.
		assertFalse(SlayerBosses.isBoss("Hydras", "Hydra"));
		assertFalse(SlayerBosses.isBoss("Hydras", "Cerberus"));
		assertFalse(SlayerBosses.isBoss("Basilisks", "Basilisk Knight"));
		assertFalse(SlayerBosses.isBoss(null, "Kraken"));
		assertTrue(SlayerBosses.forTask("Nechryael").isEmpty());
	}

	@Test
	public void bossAreaKeysAreSlugs() {
		assertEquals("boss-alchemical-hydra", SlayerBosses.areaKey("Alchemical Hydra"));
		assertEquals("boss-k-ril-tsutsaroth", SlayerBosses.areaKey("K'ril Tsutsaroth"));
	}

}
