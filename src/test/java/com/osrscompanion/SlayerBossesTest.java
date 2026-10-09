package com.osrscompanion;

import com.osrscompanion.model.SlayerGuide;
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

	@Test
	public void theBossTaskHasNoBossVariant() {
		assertTrue(SlayerHelperManager.isBossTask("98:12:0:5"));
		assertFalse(SlayerHelperManager.isBossTask("42:0:0:5"));
		assertFalse(SlayerHelperManager.isBossTask(null));
	}

	@Test
	public void bossAndRegularKillsAreCountedApart() {
		SlayerSession s = SlayerSession.start("113:0:0:8", 113, "Hydras", null, "Konar", "hydras-karuulm", "Karuulm Slayer Dungeon", 150, 150, START);
		s.addKills(3, START + 1_000);
		s.addKillTime(20_000, false, START + 1_000);
		s.addKillTime(22_000, false, START + 2_000);
		s.addKillTime(180_000, true, START + 3_000);
		assertEquals(1, s.bossKills);
		assertEquals(180_000, s.bossKillTimeMs);
		assertEquals(42_000, s.regularKillTimeMs);
		assertEquals(3, s.killTimeCount);
		Map<String, Object> p = s.payload("Zezima");
		assertEquals(1, p.get("boss_kills"));
		assertEquals(180_000L, p.get("boss_kill_time_ms_total"));
		assertEquals(42_000L, p.get("regular_kill_time_ms_total"));
		// The old call is a regular kill.
		s.addKillTime(10_000, START + 4_000);
		assertEquals(1, s.bossKills);
		assertEquals(52_000, s.regularKillTimeMs);
	}

	@Test
	public void bossKillsNeverExceedTheTaskKillsInThePayload() {
		SlayerSession s = SlayerSession.start("31:0:0:5", 31, "Hellhounds", null, "Duradel", "boss-cerberus", "Cerberus", 100, 100, START);
		s.addKillTime(90_000, true, START + 1_000);
		assertEquals(0, s.payload("Zezima").get("boss_kills"));
		s.addKills(1, START + 2_000);
		assertEquals(1, s.payload("Zezima").get("boss_kills"));
	}

	@Test
	public void aRestoredBossSessionGetsItsAreaBack() {
		SlayerSession s = SlayerSession.start("31:0:0:5", 31, "Hellhounds", null, "Duradel", "boss-cerberus", "Cerberus", 100, 100, START);
		s.monsters = Collections.singletonList("Cerberus");
		assertNull(SlayerHelperManager.bossAreaOf(s));
		s.bossAreaTile = Arrays.asList(1240, 1250, 0);
		SlayerGuide.Location area = SlayerHelperManager.bossAreaOf(s);
		assertNotNull(area);
		assertEquals("boss-cerberus", area.getKey());
		assertTrue(area.contains(1240 + SlayerHelperManager.BOSS_AREA_RADIUS, 1250, 0, 0));
		assertFalse(area.contains(1240 + SlayerHelperManager.BOSS_AREA_RADIUS + 1, 1250, 0, 0));
		assertFalse(area.contains(1240, 1250, 1, 0));
		assertEquals(Collections.singletonList("Cerberus"), area.getMonsters());
		// A location of the guide is never a boss area.
		s.locationKey = "hellhounds-taverley";
		assertNull(SlayerHelperManager.bossAreaOf(s));
	}
}
