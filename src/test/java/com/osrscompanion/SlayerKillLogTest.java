package com.osrscompanion;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SlayerKillLogTest {
	private static final long START = 1_791_374_400_000L;

	private static final SlayerKillLog.Npc DEMON = new SlayerKillLog.Npc(415, "Abyssal demon", 124, 6557, true);
	private static final SlayerKillLog.Npc DEMON_SINGLE = new SlayerKillLog.Npc(415, "Abyssal demon", 124, 6557, false);
	private static final SlayerKillLog.Npc DEMON_OTHER_REGION = new SlayerKillLog.Npc(415, "Abyssal demon", 124, 6813, true);
	private static final SlayerKillLog.Npc DEMON_OTHER_ID = new SlayerKillLog.Npc(7241, "<col=ffff00>Abyssal demon</col>", 124, 6557, true);

	private static SlayerSession session() {
		return SlayerSession.start("42:0:0:6", 42, "Abyssal demons", null, "Duradel", "abyssal-demons-catacombs",
			"Catacombs of Kourend", 190, 190, START);
	}

	@Test
	public void theSameNpcRegionAndMultiMerge() {
		SlayerSession s = session();
		s.logKills(DEMON, 1, START);
		s.logFightTime(DEMON, 12_000, START);
		s.logKills(new SlayerKillLog.Npc(415, "Abyssal demon", 124, 6557, true), 2, START);
		s.logFightTime(DEMON, 18_000, START);
		assertEquals(1, s.killLog.size());
		SlayerKillLog.Entry e = s.killLog.get(0);
		assertEquals(3, e.kills);
		assertEquals(30_000, e.fightMsTotal);
		assertEquals(2, e.fightMsCount);
	}

	@Test
	public void anotherIdRegionOrMultiIsAnotherEntry() {
		SlayerSession s = session();
		s.logKills(DEMON, 1, START);
		s.logKills(DEMON_SINGLE, 1, START);
		s.logKills(DEMON_OTHER_REGION, 1, START);
		s.logKills(DEMON_OTHER_ID, 1, START);
		assertEquals(4, s.killLog.size());
		// Tags are stripped from the name.
		assertEquals("Abyssal demon", s.killLog.get(3).npcName);
		assertEquals(7241, s.killLog.get(3).npcId);
	}

	@Test
	public void theLogKeepsTheMostKilled() {
		List<SlayerKillLog.Entry> log = new ArrayList<>();
		for (int i = 0; i < SlayerKillLog.MAX_ENTRIES; i++) {
			SlayerKillLog.addKills(log, new SlayerKillLog.Npc(1000 + i, "Npc " + i, 10, 1, false), 5);
		}
		// One entry with fewer kills: a new NPC pushes it out, not the new one.
		log.get(7).kills = 1;
		SlayerKillLog.addKills(log, new SlayerKillLog.Npc(9999, "New", 10, 1, false), 1);
		assertEquals(SlayerKillLog.MAX_ENTRIES, log.size());
		assertTrue(log.stream().noneMatch(e -> e.npcId == 1007));
		assertTrue(log.stream().anyMatch(e -> e.npcId == 9999));
		// The payload never has more than the cap, most killed first.
		SlayerKillLog.addKills(log, new SlayerKillLog.Npc(9999, "New", 10, 1, false), 10);
		List<Map<String, Object>> out = SlayerKillLog.payload(log);
		assertEquals(SlayerKillLog.MAX_ENTRIES, out.size());
		assertEquals(9999, out.get(0).get("npc_id"));
		assertEquals(11, out.get(0).get("kills"));
	}

	@Test
	public void thePayloadHasTheKillLog() {
		SlayerSession s = session();
		s.addKills(3, START);
		s.logKills(DEMON, 2, START);
		s.logKills(DEMON_SINGLE, 1, START);
		s.logFightTime(DEMON, 9_500, START);
		Map<String, Object> payload = s.payload("Zezima");
		JsonObject json = new Gson().toJsonTree(payload).getAsJsonObject();
		JsonArray log = json.getAsJsonArray("kill_log");
		assertEquals(2, log.size());
		JsonObject first = log.get(0).getAsJsonObject();
		assertEquals(Arrays.asList("npc_id", "npc_name", "combat_level", "region_id", "multi", "kills", "fight_ms_total", "fight_ms_count"),
			new ArrayList<>(first.keySet()));
		assertEquals(415, first.get("npc_id").getAsInt());
		assertEquals("Abyssal demon", first.get("npc_name").getAsString());
		assertEquals(124, first.get("combat_level").getAsInt());
		assertEquals(6557, first.get("region_id").getAsInt());
		assertTrue(first.get("multi").getAsBoolean());
		assertEquals(2, first.get("kills").getAsInt());
		assertEquals(9_500, first.get("fight_ms_total").getAsLong());
		assertEquals(1, first.get("fight_ms_count").getAsInt());
		JsonObject second = log.get(1).getAsJsonObject();
		assertFalse(second.get("multi").getAsBoolean());
		assertEquals(0, second.get("fight_ms_count").getAsInt());
		// The existing keys are still there.
		assertTrue(json.has("boss_kills"));
		assertTrue(json.has("kill_times_ms"));
		// No kills yet: an empty array.
		assertEquals(0, new Gson().toJsonTree(session().payload("Zezima")).getAsJsonObject().getAsJsonArray("kill_log").size());
	}

	@Test
	public void theLogSurvivesARestart() {
		Gson gson = new Gson();
		SlayerSession s = session();
		s.logKills(DEMON, 4, START);
		s.logFightTime(DEMON, 7_000, START);
		SlayerSession back = gson.fromJson(gson.toJson(s), SlayerSession.class);
		assertEquals(1, back.killLog.size());
		SlayerKillLog.Entry e = back.killLog.get(0);
		assertEquals(415, e.npcId);
		assertEquals("Abyssal demon", e.npcName);
		assertEquals(124, e.combatLevel);
		assertEquals(6557, e.regionId);
		assertTrue(e.multi);
		assertEquals(4, e.kills);
		assertEquals(7_000, e.fightMsTotal);
		assertEquals(1, e.fightMsCount);
		// It goes on adding to the same entry after the restart.
		back.logKills(DEMON, 1, START + 1);
		assertEquals(5, back.killLog.get(0).kills);
		// A session stored before the kill log: none, and logging still works.
		SlayerSession old = gson.fromJson("{\"session_uuid\":\"x\",\"kill_log\":null}", SlayerSession.class);
		old.logKills(DEMON, 1, START);
		assertEquals(1, old.killLog.size());
		assertEquals(1, ((List<?>) old.payload("Zezima").get("kill_log")).size());
	}

	@Test
	public void anUnknownSpotHasItsRegionsKeyAndAnAreaAfterARestart() {
		assertEquals("spot-12850", SlayerKillLog.spotKey(12850));
		assertEquals("Unknown spot (region 12850)", SlayerKillLog.spotName(12850));
		assertTrue(SlayerHelperManager.isAdhocKey("spot-12850"));
		assertTrue(SlayerHelperManager.isAdhocKey("boss-cerberus"));
		assertFalse(SlayerHelperManager.isAdhocKey("abyssal-demons-catacombs"));
		SlayerSession s = SlayerSession.start("42:0:0:6", 42, "Abyssal demons", null, "Duradel", SlayerKillLog.spotKey(12850),
			SlayerKillLog.spotName(12850), 190, 190, START);
		s.monsters = Collections.emptyList();
		assertNull(SlayerHelperManager.bossAreaOf(s));
		s.bossAreaTile = Arrays.asList(3200, 3200, 0);
		assertNotNull(SlayerHelperManager.bossAreaOf(s));
		assertEquals("spot-12850", SlayerHelperManager.bossAreaOf(s).getKey());
		assertTrue(SlayerHelperManager.bossAreaOf(s).contains(3200 + SlayerHelperManager.BOSS_AREA_RADIUS, 3200, 0, 0));
	}

	@Test
	public void aCountedKillGoesToTheMonsterThatDied() {
		SlayerKillLog.Matcher m = new SlayerKillLog.Matcher();
		// Death first, then the count.
		assertNull(m.died(DEMON, true, 10));
		assertEquals(Collections.singletonList(DEMON), m.counted(1, 10, true));
		// Count first, then the death a tick later.
		assertTrue(m.counted(1, 20, true).isEmpty());
		assertEquals(DEMON_SINGLE, m.died(DEMON_SINGLE, true, 21));
		// Your kill is preferred over someone else's.
		m.died(DEMON_OTHER_REGION, true, 30);
		m.died(DEMON_OTHER_ID, false, 30);
		assertEquals(Collections.singletonList(DEMON_OTHER_REGION), m.counted(1, 30, true));
		// A count no death explains goes to the last monster after the window.
		assertTrue(m.counted(1, 100, true).isEmpty());
		assertTrue(m.expire(100 + SlayerKillLog.Matcher.WINDOW_TICKS, false).isEmpty());
		assertEquals(Collections.singletonList(DEMON_OTHER_REGION), m.expire(101 + SlayerKillLog.Matcher.WINDOW_TICKS, false));
		// A count the session didn't count doesn't wait.
		assertTrue(m.counted(1, 200, false).isEmpty());
		assertNull(m.died(DEMON, true, 200));
	}
}
