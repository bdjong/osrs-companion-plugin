package com.osrscompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The boss history's lairs, its session and trip state machine, the kill pairing and the loot attribution. */
public class BossHistoryTest {
	private static final long MIN = 60_000L;
	private static final long T0 = 1_760_000_000_000L;

	// ---- Lairs ----------------------------------------------------------------------------------

	@Test
	public void lairsByRegionAndName() {
		assertEquals("vorkath", BossAreas.lairAt(9023, null, null).key);
		assertEquals("general-graardor", BossAreas.lairAt(11347, null, null).key);
		assertEquals("cerberus", BossAreas.lairAt(5140, null, null).key);
		assertEquals("tombs-of-amascut", BossAreas.lairAt(15700, null, null).key);
		assertEquals(BossAreas.Kind.RAID, BossAreas.byKey("theatre-of-blood").kind);
		assertNull(BossAreas.lairAt(12850, null, null));

		assertEquals("dagannoth-kings", BossAreas.byName("Dagannoth Rex").key);
		assertEquals("grotesque-guardians", BossAreas.byName("Dusk").key);
		assertEquals("grotesque-guardians", BossAreas.byName("Grotesque Guardians").key);
		assertEquals("moons-of-peril", BossAreas.byName("Blue Moon").key);
		assertEquals("vetion", BossAreas.byName("Vet'ion").key);
		assertEquals("tombs-of-amascut", BossAreas.byName("Tombs of Amascut: Expert Mode").key);
		assertEquals("the-gauntlet", BossAreas.byName("Gauntlet").key);
		assertEquals("corrupted-gauntlet", BossAreas.byName("Corrupted Gauntlet").key);
		assertEquals("the-nightmare", BossAreas.byName("The Nightmare").key);
		assertEquals("phosanis-nightmare", BossAreas.byName("Phosani's Nightmare").key);
		assertEquals("tzhaar-fight-cave", BossAreas.byName("TzTok-Jad").key);
		assertNull(BossAreas.byName("Goblin"));
		assertNull(BossAreas.byName("Wintertodt"));
	}

	@Test
	public void sharedLairPrefersTheSessionsBoss() {
		BossAreas.Boss phosani = BossAreas.byKey("phosanis-nightmare");
		assertEquals("the-nightmare", BossAreas.lairAt(15515, null, null).key);
		assertEquals("phosanis-nightmare", BossAreas.lairAt(15515, phosani, null).key);
	}

	@Test
	public void learnedLairsCount() {
		Map<String, List<Integer>> learned = new HashMap<>();
		learned.put("spindel", Collections.singletonList(12345 + 1));
		assertEquals("spindel", BossAreas.lairAt(12346, null, learned).key);
		assertTrue(BossAreas.isLair(BossAreas.byKey("spindel"), 12346, learned));
		assertFalse(BossAreas.isLair(BossAreas.byKey("spindel"), 12347, learned));
	}

	@Test
	public void everyBossHasAUniqueKeyAndCommonBossesAreCovered() {
		List<String> keys = BossAreas.keys();
		assertEquals(keys.size(), new java.util.HashSet<>(keys).size());
		for (String k : Arrays.asList("vorkath", "zulrah", "dagannoth-kings", "kalphite-queen", "cerberus", "alchemical-hydra",
			"abyssal-sire", "kraken", "thermonuclear-smoke-devil", "grotesque-guardians", "corporeal-beast", "the-nightmare",
			"phosanis-nightmare", "nex", "phantom-muspah", "duke-sucellus", "the-leviathan", "the-whisperer", "vardorvis",
			"callisto", "venenatis", "vetion", "moons-of-peril", "the-hueycoatl", "araxxor", "scurrius", "sarachnis",
			"the-gauntlet", "corrupted-gauntlet", "zalcano", "chambers-of-xeric", "theatre-of-blood", "tombs-of-amascut")) {
			assertNotNull(k, BossAreas.byKey(k));
		}
	}

	// ---- Session, trips and banks -----------------------------------------------------------------

	private static BossSession vorkath() {
		return BossSession.start(BossAreas.byKey("vorkath"), T0);
	}

	@Test
	public void timeInLairCountsOnlyInsideAndStartsATrip() {
		BossSession s = vorkath();
		assertTrue(s.tick(true, T0));
		assertEquals(1, s.tripCount);
		s.tick(true, T0 + 600);
		s.tick(true, T0 + 1200);
		assertEquals(1200, s.timeInLairMs);
		assertEquals(1200, s.openTrip().timeInLairMs);
		// A gap (a frozen client) is not counted.
		s.tick(true, T0 + 20_000);
		assertEquals(1200, s.timeInLairMs);
		s.tick(false, T0 + 20_600);
		s.tick(false, T0 + 21_200);
		assertEquals(1200, s.timeInLairMs);
		// Walking out and back in without a bank or a teleport: the same trip.
		assertTrue(s.tick(true, T0 + 30_000));
		assertEquals(1, s.tripCount);
	}

	@Test
	public void bankEndsTheTripAndTheNextEntryStartsAnother() {
		BossSession s = vorkath();
		s.tick(true, T0);
		s.addKill(5, T0 + MIN);
		s.tick(false, T0 + 2 * MIN);
		s.bankOpened(T0 + 3 * MIN);
		s.bankOpened(T0 + 3 * MIN + 5_000);
		BossSession.Trip first = s.trips.get(0);
		assertEquals("bank", first.endReason);
		// The trip ended when you left the lair.
		assertEquals(T0 + 2 * MIN, first.endedMs);
		assertEquals(1, s.bankVisits);
		assertNull(s.openTrip());

		s.tick(true, T0 + 6 * MIN);
		assertEquals(2, s.tripCount);
		assertEquals(1, s.bankTrips);
		assertEquals(4 * MIN, s.bankTripMs);
		s.addKill(6, T0 + 7 * MIN);
		assertEquals(2, s.kills);
		assertEquals(6, s.killCount);
		assertEquals(1, s.trips.get(1).kills);
	}

	@Test
	public void bankInsideTheLairStartsANewTripNextTick() {
		BossSession s = vorkath();
		s.tick(true, T0);
		s.bankOpened(T0 + MIN);
		assertNull(s.openTrip());
		s.tick(true, T0 + MIN + 600);
		assertEquals(2, s.tripCount);
	}

	@Test
	public void teleportOutAndDeathEndTrips() {
		BossSession s = vorkath();
		s.tick(true, T0);
		// A teleport into the lair (an instance) ends nothing.
		s.teleportedOut(T0 + 600);
		assertNotNull(s.openTrip());
		s.tick(false, T0 + MIN);
		s.teleportedOut(T0 + MIN);
		assertEquals("teleport", s.trips.get(0).endReason);

		s.tick(true, T0 + 3 * MIN);
		s.died(T0 + 4 * MIN);
		assertEquals("death", s.trips.get(1).endReason);
		assertEquals(1, s.deaths);
		assertEquals(1, s.trips.get(1).deaths);
		assertTrue(s.hasData());
	}

	@Test
	public void closesAfterBeingAwayOrIdle() {
		BossSession s = vorkath();
		s.tick(true, T0);
		s.addKill(1, T0 + MIN);
		s.tick(false, T0 + 2 * MIN);
		s.bankOpened(T0 + 3 * MIN);
		assertEquals(0, s.closeAt(T0 + 10 * MIN));
		assertEquals(T0 + 2 * MIN, s.closeAt(T0 + 23 * MIN));
		s.close("left", T0 + 2 * MIN);
		assertFalse(s.isOpen());
		assertEquals(T0 + 2 * MIN, s.endedMs);

		BossSession idle = vorkath();
		idle.tick(true, T0);
		idle.addKill(1, T0 + MIN);
		assertEquals(0, idle.closeAt(T0 + 20 * MIN));
		assertEquals(T0 + MIN, idle.closeAt(T0 + 32 * MIN));
	}

	@Test
	public void aWalkThroughHasNoData() {
		BossSession s = BossSession.start(BossAreas.byKey("callisto"), T0);
		s.tick(true, T0);
		s.tick(true, T0 + 600);
		assertFalse(s.hasData());
	}

	@Test
	public void relabelOnlyBeforeAnythingWasRecorded() {
		BossSession s = BossSession.start(BossAreas.byKey("the-nightmare"), T0);
		assertTrue(s.relabel(BossAreas.byKey("phosanis-nightmare"), T0 + 1));
		assertEquals("phosanis-nightmare", s.bossKey);
		s.addKill(1, T0 + 2);
		assertFalse(s.relabel(BossAreas.byKey("the-nightmare"), T0 + 3));
	}

	@Test
	public void logoutEndsTheTripButKeepsTheSession() {
		BossSession s = vorkath();
		s.tick(true, T0);
		s.addKill(1, T0 + MIN);
		s.loggedOut(T0 + 2 * MIN);
		assertTrue(s.isOpen());
		assertEquals("logout", s.trips.get(0).endReason);
		assertFalse(s.inside);
		// Back within a minute: a new trip, no time offline counted.
		s.resumed();
		s.tick(true, T0 + 3 * MIN);
		s.tick(true, T0 + 3 * MIN + 600);
		assertEquals(2, s.tripCount);
		assertEquals(600, s.timeInLairMs);
	}

	// ---- Loot ---------------------------------------------------------------------------------

	@Test
	public void dropsGoToTheSessionTripAndTotals() {
		BossSession s = vorkath();
		s.tick(true, T0);
		s.addKill(1, T0 + MIN);
		s.addDrop("Vorkath", Arrays.asList(new BossSession.Stack(536, 2, 4_000, 0), new BossSession.Stack(11286, 1, 5_000_000, 0)), T0 + MIN);
		s.addKill(2, T0 + 2 * MIN);
		s.addDrop("Vorkath", Collections.singletonList(new BossSession.Stack(536, 2, 4_000, 0)), T0 + 2 * MIN);
		assertEquals(5_008_000, s.lootGp);
		assertEquals(2, s.drops.size());
		assertEquals(1, s.drops.get(0).kill);
		assertEquals(2, s.drops.get(1).kill);
		assertEquals(1, s.drops.get(1).trip);
		assertEquals(5_008_000, s.trips.get(0).lootGp);
		assertEquals(2, s.loot.size());
		assertEquals(4, s.loot.get(0).quantity);
		assertEquals(8_000, s.loot.get(0).gp);

		// A chest opened just after the trip ended goes to that trip.
		s.tick(false, T0 + 3 * MIN);
		s.teleportedOut(T0 + 3 * MIN);
		s.addDrop("Vorkath", Collections.singletonList(new BossSession.Stack(995, 100, 100, 0)), T0 + 3 * MIN);
		assertEquals(1, s.drops.get(2).trip);
	}

	@Test
	public void lootAttribution() {
		BossAreas.Boss vork = BossAreas.byKey("vorkath");
		BossAreas.Boss bandos = BossAreas.byKey("general-graardor");
		assertEquals("vorkath", BossHistory.lootBoss("Vorkath", null, false).key);
		// A bodyguard's drop in the general's room is the session's.
		assertEquals(bandos, BossHistory.lootBoss("Sergeant Strongstack", bandos, true));
		assertNull(BossHistory.lootBoss("Sergeant Strongstack", bandos, false));
		assertNull(BossHistory.lootBoss("Goblin", null, false));
		// Another boss's drop is that boss's, whatever the session.
		assertEquals("zulrah", BossHistory.lootBoss("Zulrah", vork, true).key);
		assertEquals("moons-of-peril", BossHistory.lootBoss("Lunar Chest", null, false).key);
		assertEquals("chambers-of-xeric", BossHistory.lootBoss("Chambers of Xeric", null, false).key);
	}

	@Test
	public void suppliesGoToTheSessionAndTheLastTrip() {
		BossSession s = vorkath();
		s.tick(true, T0);
		s.tick(false, T0 + MIN);
		s.bankOpened(T0 + 2 * MIN);
		s.addSupplies(Arrays.asList(new BossSession.Stack(2434, 8, 0, 4), new BossSession.Stack(385, 10, 0, 0)), T0 + 2 * MIN);
		s.addSupplies(Collections.singletonList(new BossSession.Stack(385, 3, 0, 0)), T0 + 2 * MIN);
		assertEquals(2, s.supplies.size());
		assertEquals(13, s.supplies.get(1).quantity);
		assertEquals(2, s.trips.get(0).supplies.size());
	}

	@Test
	public void payloadHasTheContractKeys() {
		BossSession s = vorkath();
		s.tick(true, T0);
		s.tick(true, T0 + 600);
		s.addKill(10, T0 + MIN);
		s.addKillTime(90_000, T0 + MIN);
		s.addDrop("Vorkath", Collections.singletonList(new BossSession.Stack(536, 2, 4_000, 0)), T0 + MIN);
		Map<String, Object> p = s.payload("Zezima");
		assertEquals("Zezima", p.get("player"));
		assertEquals("vorkath", p.get("boss_key"));
		assertEquals("boss", p.get("kind"));
		assertEquals(1, p.get("kills"));
		assertEquals(10, p.get("kill_count"));
		assertEquals(90_000L, p.get("avg_kill_ms"));
		assertEquals(4_000L, p.get("loot_gp"));
		assertNull(p.get("ended_at"));
		assertEquals(1, ((List<?>) p.get("drops")).size());
		assertEquals(1, ((List<?>) p.get("trips")).size());
		assertTrue(p.containsKey("bank_visits"));
		assertTrue(p.containsKey("time_in_lair_sec"));
	}

	// ---- Kills and fight times ------------------------------------------------------------------

	@Test
	public void messageAndDeathPairInEitherOrder() {
		BossKillMatcher m = new BossKillMatcher();
		m.died(100, 60_000, true);
		m.killMessage(101);
		BossKillMatcher.Result r = m.tick(105);
		assertEquals(0, r.deathKills);
		assertEquals(Collections.singletonList(60_000L), r.fightMs);

		m.killMessage(200);
		m.died(201, 30_000, true);
		r = m.tick(205);
		assertEquals(0, r.deathKills);
		assertEquals(Collections.singletonList(30_000L), r.fightMs);
		assertFalse(m.tick(260).any());
	}

	@Test
	public void lateDeathAfterTheKillSettledIsNotASecondKill() {
		BossKillMatcher m = new BossKillMatcher();
		m.killMessage(100);
		assertTrue(m.tick(104).fightMs.isEmpty());
		m.died(107, 50_000, true);
		assertFalse(m.tick(200).any());
	}

	@Test
	public void deathWithoutMessageCountsOnlyForSingleDeathBosses() {
		BossKillMatcher m = new BossKillMatcher();
		m.died(100, 45_000, true);
		BossKillMatcher.Result r = m.tick(111);
		assertEquals(1, r.deathKills);
		assertEquals(Collections.singletonList(45_000L), r.fightMs);

		// A phase "dying" (the Kalphite Queen's first form) is no kill.
		m.died(200, 45_000, false);
		assertFalse(m.tick(211).any());
	}

	@Test
	public void gameDurationBeatsTheMeasuredTime() {
		BossKillMatcher m = new BossKillMatcher();
		m.died(100, 60_000, true);
		m.duration(101, 83_400, false);
		m.killMessage(101);
		assertEquals(Collections.singletonList(83_400L), m.tick(105).fightMs);

		// A raid: the total completion time is not replaced by a room's next to it.
		m.duration(300, 1_800_000, true);
		m.duration(301, 120_000, false);
		m.killMessage(301);
		assertEquals(Collections.singletonList(1_800_000L), m.tick(305).fightMs);
	}

	@Test
	public void durations() {
		assertEquals(83_400, BossKillMatcher.parseDuration("1:23.40"));
		assertEquals(83_000, BossKillMatcher.parseDuration("1:23"));
		assertEquals(3_723_600, BossKillMatcher.parseDuration("1:02:03.6"));
		assertEquals(-1, BossKillMatcher.parseDuration("soon"));
	}

	@Test
	public void killCountMessages() {
		assertArrayEquals(new String[]{"Vorkath", "1,152"}, BossTripTracker.killCount("Your Vorkath kill count is: 1,152."));
		assertArrayEquals(new String[]{"Gauntlet", "5"}, BossTripTracker.killCount("Your Gauntlet completion count is: 5."));
		// Zalcano is no boss trip, but it is in the boss history.
		assertNull(BossTripTracker.killCountBoss("Your Zalcano kill count is: 3."));
		assertArrayEquals(new String[]{"Zalcano", "3"}, BossTripTracker.killCount("Your Zalcano kill count is: 3."));
		assertNull(BossTripTracker.killCount("Your Gnome Stronghold Agility lap count is: 5."));
		assertEquals(1152, BossHistory.parseCount("1,152"));
	}

	private static void assertArrayEquals(String[] want, String[] got) {
		org.junit.Assert.assertArrayEquals(want, got);
	}
}
