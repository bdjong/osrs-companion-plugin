package com.osrscompanion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SlayerSessionTest {
	private static final long START = 1_791_374_400_000L; // 2026-10-07T12:00:00Z

	private static SlayerSession session() {
		return SlayerSession.start("42:0:0:6", 42, "Abyssal demons", null, "Duradel", "abyssal-demons-catacombs",
			"Catacombs of Kourend", 190, 190, START);
	}

	@Test
	public void timeAwayWithABankVisitIsABankTrip() {
		SlayerSession s = session();
		s.tick(true, START);
		s.tick(true, START + 600);
		// Leave, open a bank, come back 3 minutes after leaving: one bank trip of 3 minutes.
		s.tick(false, START + 1_200);
		s.bankOpened();
		s.tick(false, START + 60_000);
		s.tick(true, START + 1_200 + 180_000);
		assertEquals(1, s.bankTrips);
		assertEquals(180_000, s.bankTripMs);
		// Leaving and coming back without a bank (a walk to a safespot) is no bank trip.
		s.tick(false, START + 400_000);
		s.tick(true, START + 430_000);
		assertEquals(1, s.bankTrips);
		// A bank visit before leaving doesn't count either.
		s.bankOpened();
		s.tick(false, START + 500_000);
		s.tick(true, START + 520_000);
		assertEquals(1, s.bankTrips);
		// A long bank trip counts at most 8 minutes.
		s.tick(false, START + 540_000);
		s.bankOpened();
		s.tick(true, START + 540_000 + 12 * 60_000);
		assertEquals(2, s.bankTrips);
		assertEquals(180_000 + SlayerSession.BANK_TRIP_CAP_MS, s.bankTripMs);
		// Away for longer than the limit (a break): not a bank trip.
		s.tick(false, START + 2_000_000);
		s.bankOpened();
		s.tick(true, START + 2_000_000 + SlayerSession.MAX_BANK_TRIP_MS + 1);
		assertEquals(2, s.bankTrips);
		Map<String, Object> payload = s.payload("Zezima");
		assertEquals(2, payload.get("bank_trips"));
		assertEquals(180L + 480L, payload.get("bank_trip_sec"));
	}

	@Test
	public void aRestartNeverTurnsTheTimeOfflineIntoABankTrip() {
		SlayerSession s = session();
		s.tick(true, START);
		s.tick(false, START + 1_000);
		s.bankOpened();
		s.resumed();
		s.tick(true, START + 120_000);
		assertEquals(0, s.bankTrips);
	}

	@Test
	public void timeInAreaOnlyCountsTicksInside() {
		SlayerSession s = session();
		assertTrue(s.tick(true, START));
		assertEquals(1, s.tripCount);
		s.tick(true, START + 600);
		s.tick(true, START + 1_200);
		assertEquals(1_200, s.timeInAreaMs);
		// Out to bank: the time away is not counted...
		assertFalse(s.tick(false, START + 1_800));
		s.tick(false, START + 60_000);
		assertEquals(1_200, s.timeInAreaMs);
		// ...and coming back is the second trip.
		assertTrue(s.tick(true, START + 120_000));
		assertEquals(2, s.tripCount);
		assertEquals(1_200, s.timeInAreaMs);
		s.tick(true, START + 120_600);
		assertEquals(1_800, s.timeInAreaMs);
		// The trip timer runs from the start, inside or not.
		assertEquals(120_600, s.totalMs(START + 120_600));
	}

	@Test
	public void longGapsAndRestartsAreNotCounted() {
		SlayerSession s = session();
		s.tick(true, START);
		s.tick(true, START + 600);
		// The client froze for a minute.
		s.tick(true, START + 60_600);
		assertEquals(600, s.timeInAreaMs);
		// Restarted while standing there: same trip, and the time offline is not counted.
		s.resumed();
		assertFalse(s.tick(true, START + 600_000));
		s.tick(true, START + 600_600);
		assertEquals(1, s.tripCount);
		assertEquals(1_200, s.timeInAreaMs);
	}

	@Test
	public void averagesAndEstimates() {
		SlayerSession s = session();
		s.tick(true, START);
		for (int i = 1; i <= 100; i++) {
			s.tick(true, START + i * 600L);
		}
		// 60 s inside, 4 kills, three of them timed (barraged together), 100k loot.
		s.addKills(4, START + 60_000);
		s.addKillTime(18_000, START);
		s.addKillTime(21_000, START);
		s.addKillTime(17_500, START);
		s.addLoot(100_000, START);
		s.setRemaining(186, START);
		assertEquals(18_833, s.avgKillMs());
		assertEquals(240.0, s.killsPerHour(), 0.001);
		assertEquals(25_000, s.avgLootGp());
		// Time in the area per kill (60 s / 4 kills), not the fight time.
		assertEquals(186 * 15_000L, s.estimatedLeftMs());
	}

	@Test
	public void keepsTheLastKillTimesOnly() {
		SlayerSession s = session();
		for (int i = 0; i < SlayerSession.MAX_KILL_TIMES + 20; i++) {
			s.addKillTime(i, START);
		}
		assertEquals(SlayerSession.MAX_KILL_TIMES, s.killTimesMs.size());
		assertEquals(Long.valueOf(20), s.killTimesMs.get(0));
		// The average still covers every kill.
		assertEquals(SlayerSession.MAX_KILL_TIMES + 20, s.killTimeCount);
	}

	@Test
	public void payloadHasTheContractsKeys() {
		SlayerSession s = session();
		s.tick(true, START);
		s.tick(true, START + 600);
		s.addKills(2, START);
		s.addKillTime(18_000, START);
		s.addLoot(70_000, START);
		s.setRemaining(188, START);
		Map<String, Object> body = s.payload("Zezima");
		List<String> keys = Arrays.asList("player", "session_uuid", "task_id", "task_name", "area_id", "master", "location_key",
			"amount_original", "amount_at_start", "amount_remaining", "started_at", "ended_at", "completed", "time_in_area_sec",
			"trip_count", "bank_trips", "bank_trip_sec", "kills", "avg_kill_ms", "kills_per_hour", "loot_gp", "avg_loot_gp", "slayer_xp", "kill_times_ms",
			"boss_kills", "boss_kill_time_ms_total", "regular_kill_time_ms_total");
		assertEquals(keys, new ArrayList<>(body.keySet()));

		JsonObject json = new Gson().newBuilder().serializeNulls().create().toJsonTree(body).getAsJsonObject();
		assertEquals("Zezima", json.get("player").getAsString());
		assertEquals(42, json.get("task_id").getAsInt());
		assertTrue(json.get("area_id").isJsonNull());
		assertEquals("abyssal-demons-catacombs", json.get("location_key").getAsString());
		assertEquals("2026-10-07T12:00:00Z", json.get("started_at").getAsString());
		assertTrue(json.get("ended_at").isJsonNull());
		assertFalse(json.get("completed").getAsBoolean());
		assertEquals(190, json.get("amount_at_start").getAsInt());
		assertEquals(188, json.get("amount_remaining").getAsInt());
		assertEquals(1, json.get("trip_count").getAsInt());
		assertEquals(18_000, json.get("avg_kill_ms").getAsLong());
		assertEquals(35_000, json.get("avg_loot_gp").getAsLong());
		assertEquals(1, json.get("kill_times_ms").getAsJsonArray().size());
	}

	@Test
	public void completingClosesTheSession() {
		SlayerSession s = session();
		s.tick(true, START);
		s.close(true, START + 2_400_000);
		assertFalse(s.isOpen());
		assertFalse(s.inside);
		Map<String, Object> body = s.payload("Zezima");
		assertEquals("2026-10-07T12:40:00Z", body.get("ended_at"));
		assertEquals(true, body.get("completed"));
		assertEquals(0, body.get("amount_remaining"));
		// Closing twice keeps the first end.
		s.close(false, START + 3_000_000);
		assertEquals("2026-10-07T12:40:00Z", s.payload("Zezima").get("ended_at"));
		assertEquals(2_400_000, s.totalMs(START + 9_999_999));
	}

	@Test
	public void survivesARestartAsJson() {
		Gson gson = new Gson();
		SlayerSession s = session();
		s.tick(true, START);
		s.tick(true, START + 600);
		s.addKills(3, START);
		s.addKillTime(15_000, START);
		s.addLoot(12_345, START);
		SlayerSession back = gson.fromJson(gson.toJson(s), SlayerSession.class);
		assertEquals(s.uuid, back.uuid);
		assertEquals("42:0:0:6", back.taskKey);
		assertEquals(600, back.timeInAreaMs);
		assertEquals(3, back.kills);
		assertEquals(15_000, back.avgKillMs());
		assertEquals(12_345, back.lootGp);
		assertTrue(back.inside);
		assertTrue(back.isOpen());
		assertEquals(s.payload("Zezima"), back.payload("Zezima"));
	}
}
