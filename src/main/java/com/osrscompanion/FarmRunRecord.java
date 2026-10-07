package com.osrscompanion;

import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.model.FarmRoute;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The record of one guided farm run for the website's Run history, sent when the run ends:
 * completed, or stopped after at least one patch was done. Its harvests are not part of it:
 * they were sent one by one with {@code on_run} and the website links them by time.
 * Pure logic so it can be tested without a game client.
 */
final class FarmRunRecord {
	/** One game tick, for the run's logged-in time. */
	static final double TICK_SECONDS = 0.6;

	private FarmRunRecord() {
	}

	/**
	 * The JSON body (snake_case) for POST /api/runelite/farm-runs, or null when no patch was done
	 * (nothing was planted or refilled: not a run worth keeping).
	 *
	 * @param statuses    per route patch key, its status at the end (missing: still to do)
	 * @param activeTicks game ticks the run was going while logged in
	 */
	static Map<String, Object> build(String player, FarmRoute route, Map<String, Status> statuses, long startedAtMs, long endedAtMs,
		int activeTicks, int xpGained, boolean completed) {
		if (route == null) {
			return null;
		}
		int done = 0;
		int skipped = 0;
		int total = 0;
		long gp = 0;
		// Seeds and saplings planted, by item, in route order.
		Map<Integer, Integer> planted = new LinkedHashMap<>();
		for (FarmRoute.Stop stop : route.getStops()) {
			for (FarmRoute.Patch p : stop.getPatches()) {
				total++;
				Status s = statuses.get(p.getKey());
				if (s == Status.SKIPPED) {
					skipped++;
				} else if (s == Status.DONE) {
					done++;
					gp += p.getGp();
					Integer seed = p.getSeed();
					if (seed != null && seed > 0) {
						planted.merge(seed, Math.max(1, p.getSeedQty()), Integer::sum);
					}
				}
			}
		}
		if (done == 0) {
			return null;
		}
		List<Map<String, Object>> plantedList = new ArrayList<>();
		for (Map.Entry<Integer, Integer> e : planted.entrySet()) {
			Map<String, Object> stack = new LinkedHashMap<>();
			stack.put("item", e.getKey());
			stack.put("qty", e.getValue());
			plantedList.add(stack);
		}
		long span = Math.max(0, endedAtMs - startedAtMs) / 1000;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", player);
		body.put("route_id", route.getId());
		body.put("route_name", route.getName());
		body.put("started_at", Instant.ofEpochMilli(startedAtMs).toString());
		body.put("ended_at", Instant.ofEpochMilli(endedAtMs).toString());
		// Logged-in time (ticks), never more than the wall-clock time.
		body.put("active_seconds", Math.min(span, Math.round(activeTicks * TICK_SECONDS)));
		body.put("patches_done", done);
		body.put("patches_total", total);
		body.put("patches_skipped", skipped);
		body.put("completed", completed);
		body.put("xp_gained", Math.max(0, xpGained));
		body.put("est_gp", (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, gp)));
		body.put("planted", plantedList);
		return body;
	}
}
