package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.model.FarmRoute;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FarmRunRecordTest {
	private static final long START = 1_760_000_000_000L;

	/** Two stops: 2 ranarr herb patches and an allotment (3 potato seeds), then a compost bin and a magic sapling. */
	private static FarmRoute route() {
		return new Gson().fromJson("{\"id\":7,\"name\":\"Herb run\",\"stops\":["
			+ "{\"patches\":[{\"key\":\"herb:a\",\"impl\":\"HERB\",\"seed\":5295,\"seed_qty\":1,\"gp\":40000},"
			+ "{\"key\":\"herb:b\",\"impl\":\"HERB\",\"seed\":5295,\"seed_qty\":1,\"gp\":40000},"
			+ "{\"key\":\"allot:a\",\"impl\":\"ALLOTMENT\",\"seed\":5318,\"seed_qty\":3,\"gp\":-500}]},"
			+ "{\"patches\":[{\"key\":\"compost:a\",\"impl\":\"COMPOST\"},"
			+ "{\"key\":\"tree:a\",\"impl\":\"TREE\",\"seed\":5374,\"seed_qty\":1,\"gp\":-90000}]}]}", FarmRoute.class);
	}

	@Test
	public void completedRunCountsDonePatchesAndSeeds() {
		Map<String, Status> s = new HashMap<>();
		s.put("herb:a", Status.DONE);
		s.put("herb:b", Status.DONE);
		s.put("allot:a", Status.DONE);
		s.put("compost:a", Status.DONE);
		s.put("tree:a", Status.NOT_READY);
		Map<String, Object> body = FarmRunRecord.build("Zezima", route(), s, START, START + 15 * 60_000, 1000, 2500, true);
		assertEquals("Zezima", body.get("player_username"));
		assertEquals(7, body.get("route_id"));
		assertEquals("Herb run", body.get("route_name"));
		assertEquals("2025-10-09T08:53:20Z", body.get("started_at"));
		assertEquals("2025-10-09T09:08:20Z", body.get("ended_at"));
		assertEquals(600L, body.get("active_seconds"));
		assertEquals(4, body.get("patches_done"));
		assertEquals(5, body.get("patches_total"));
		assertEquals(0, body.get("patches_skipped"));
		assertEquals(true, body.get("completed"));
		assertEquals(2500, body.get("xp_gained"));
		assertEquals(79500, body.get("est_gp"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> planted = (List<Map<String, Object>>) body.get("planted");
		// 2 ranarr seeds and 3 potato seeds; the compost bin plants nothing, the tree was still growing.
		assertEquals(2, planted.size());
		assertEquals(5295, planted.get(0).get("item"));
		assertEquals(2, planted.get(0).get("qty"));
		assertEquals(5318, planted.get(1).get("item"));
		assertEquals(3, planted.get(1).get("qty"));
	}

	@Test
	public void stoppedRunCountsSkippedPatches() {
		Map<String, Status> s = new HashMap<>();
		s.put("herb:a", Status.DONE);
		s.put("herb:b", Status.SKIPPED);
		Map<String, Object> body = FarmRunRecord.build("Zezima", route(), s, START, START + 60_000, 50, 300, false);
		assertEquals(1, body.get("patches_done"));
		assertEquals(1, body.get("patches_skipped"));
		assertEquals(false, body.get("completed"));
	}

	@Test
	public void noPatchDoneIsNotARun() {
		Map<String, Status> s = new HashMap<>();
		s.put("herb:a", Status.NOT_READY);
		s.put("herb:b", Status.SKIPPED);
		assertNull(FarmRunRecord.build("Zezima", route(), s, START, START + 60_000, 100, 0, true));
		assertNull(FarmRunRecord.build("Zezima", null, s, START, START + 60_000, 100, 0, true));
	}

	@Test
	public void activeTimeNeverExceedsTheWallClockAndXpNeverNegative() {
		Map<String, Status> s = new HashMap<>();
		s.put("herb:a", Status.DONE);
		// 10,000 ticks (100 minutes) in a 5 minute run: capped at 5 minutes.
		Map<String, Object> body = FarmRunRecord.build("Zezima", route(), s, START, START + 5 * 60_000, 10_000, -40, true);
		assertEquals(300L, body.get("active_seconds"));
		assertEquals(0, body.get("xp_gained"));
		assertTrue(new Gson().toJson(body).contains("\"route_id\":7"));
	}
}
