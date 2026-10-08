package com.osrscompanion;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One slayer session: one task assignment at one location, from the first time you walk into the
 * area until the task is done (or you move to another location / get another task). It counts
 * the time actually spent inside the area (tick by tick, only while inside), the separate visits
 * (trips), the task kills, each monster's kill time and the loot's value.
 *
 * <p>Stored as JSON in the RuneScape profile's config so a client restart keeps it, and sent to the
 * website as the POST /api/runelite/slayer/sessions/ body ({@link #payload}). No game access: the
 * caller passes the time.
 */
final class SlayerSession {
	/** The website charts the distribution from at most this many recent kill times. */
	static final int MAX_KILL_TIMES = 500;
	/**
	 * Longer gaps between two ticks inside the area are not counted (the client froze, or this is the
	 * first tick after a restart): a tick is 600 ms, so this only drops real gaps.
	 */
	static final long MAX_TICK_GAP_MS = 3_000L;
	/** A time away longer than this is not a bank trip (a break, another activity). */
	static final long MAX_BANK_TRIP_MS = 20 * 60_000L;
	/** A bank trip counts at most this long (lingering at the bank is not part of the task). */
	static final long BANK_TRIP_CAP_MS = 8 * 60_000L;

	@SerializedName("session_uuid")
	String uuid;
	/** The character it belongs to (the website's player). */
	@SerializedName("player")
	String player;
	/** Which assignment this is (see {@link SlayerHelperManager}); a different task starts a new session. */
	@SerializedName("task_key")
	String taskKey;
	@SerializedName("task_id")
	Integer taskId;
	@SerializedName("task_name")
	String taskName;
	@SerializedName("area_id")
	Integer areaId;
	@SerializedName("master")
	String master;
	@SerializedName("location_key")
	String locationKey;
	@SerializedName("location_name")
	String locationName;
	/** NPC names that count as the task monster at this location (empty: by the task's name). */
	@SerializedName("monsters")
	List<String> monsters = new ArrayList<>();
	@SerializedName("amount_original")
	int amountOriginal;
	@SerializedName("amount_at_start")
	int amountAtStart;
	@SerializedName("amount_remaining")
	int amountRemaining;
	@SerializedName("started_ms")
	long startedMs;
	/** 0 while the session is open. */
	@SerializedName("ended_ms")
	long endedMs;
	@SerializedName("completed")
	boolean completed;
	@SerializedName("time_in_area_ms")
	long timeInAreaMs;
	@SerializedName("trip_count")
	int tripCount;
	/** Times away from the area that included a bank visit and ended back here, and their total length. */
	@SerializedName("bank_trips")
	int bankTrips;
	@SerializedName("bank_trip_ms")
	long bankTripMs;
	/** When the player last left the area (0: inside, or not counting), and whether a bank was opened since. */
	@SerializedName("left_ms")
	long leftMs;
	@SerializedName("bank_seen_away")
	boolean bankSeenAway;
	@SerializedName("kills")
	int kills;
	@SerializedName("kill_time_total_ms")
	long killTimeTotalMs;
	@SerializedName("kill_time_count")
	int killTimeCount;
	@SerializedName("kill_times_ms")
	List<Long> killTimesMs = new ArrayList<>();
	@SerializedName("loot_gp")
	long lootGp;

	/** Slayer XP gained while the session was open (the task's kills). */
	@SerializedName("slayer_xp")
	long slayerXp;
	/** Inside the area on the last tick, and when that tick was (0: none yet since loading). */
	@SerializedName("inside")
	boolean inside;
	@SerializedName("last_tick_ms")
	long lastTickMs;
	/** Last time anything in the session changed (for the sync and the restore after a restart). */
	@SerializedName("updated_ms")
	long updatedMs;

	/** Starts a session on the first tick inside the area: trip 1. */
	static SlayerSession start(String taskKey, Integer taskId, String taskName, Integer areaId, String master,
			String locationKey, String locationName, int amountOriginal, int amountRemaining, long nowMs) {
		SlayerSession s = new SlayerSession();
		s.uuid = UUID.randomUUID().toString();
		s.taskKey = taskKey;
		s.taskId = taskId;
		s.taskName = taskName;
		s.areaId = areaId;
		s.master = master;
		s.locationKey = locationKey;
		s.locationName = locationName;
		s.amountOriginal = amountOriginal;
		s.amountAtStart = amountRemaining;
		s.amountRemaining = amountRemaining;
		s.startedMs = nowMs;
		s.updatedMs = nowMs;
		return s;
	}

	/**
	 * One game tick: while inside, the time since the last tick inside is added to the time at the
	 * location; walking (or teleporting) back in starts a new trip. Leaving notes the time; coming back
	 * after a bank visit (see {@link #bankOpened}) counts the time away as a bank trip. Returns true on entering.
	 */
	boolean tick(boolean nowInside, long nowMs) {
		boolean entered = false;
		if (nowInside) {
			if (!inside) {
				tripCount++;
				entered = true;
				updatedMs = nowMs;
				if (leftMs > 0 && bankSeenAway) {
					long away = nowMs - leftMs;
					if (away > 0 && away <= MAX_BANK_TRIP_MS) {
						bankTrips++;
						bankTripMs += Math.min(away, BANK_TRIP_CAP_MS);
					}
				}
				leftMs = 0;
				bankSeenAway = false;
			} else if (lastTickMs > 0) {
				long gap = nowMs - lastTickMs;
				if (gap > 0 && gap <= MAX_TICK_GAP_MS) {
					timeInAreaMs += gap;
					updatedMs = nowMs;
				}
			}
			lastTickMs = nowMs;
		} else if (inside) {
			updatedMs = nowMs;
			leftMs = nowMs;
			bankSeenAway = false;
		}
		inside = nowInside;
		return entered;
	}

	/** A bank was opened: while away from the area, the time away becomes a bank trip once back. */
	void bankOpened() {
		if (!inside && leftMs > 0) {
			bankSeenAway = true;
		}
	}

	/** After loading from the config: the time offline is never counted (nor as a bank trip). */
	void resumed() {
		lastTickMs = 0;
		leftMs = 0;
		bankSeenAway = false;
	}

	void addKills(int n, long nowMs) {
		if (n > 0) {
			kills += n;
			updatedMs = nowMs;
		}
	}

	void addKillTime(long ms, long nowMs) {
		killTimeTotalMs += ms;
		killTimeCount++;
		killTimesMs.add(ms);
		while (killTimesMs.size() > MAX_KILL_TIMES) {
			killTimesMs.remove(0);
		}
		updatedMs = nowMs;
	}

	void addLoot(long gp, long nowMs) {
		if (gp > 0) {
			lootGp += gp;
			updatedMs = nowMs;
		}
	}

	void addSlayerXp(long xp, long nowMs) {
		if (xp > 0) {
			slayerXp += xp;
			updatedMs = nowMs;
		}
	}

	void setRemaining(int remaining, long nowMs) {
		if (remaining != amountRemaining) {
			amountRemaining = remaining;
			updatedMs = nowMs;
		}
	}

	/** The task is done (completed) or this session ends another way (new task, other location). */
	void close(boolean done, long nowMs) {
		if (endedMs == 0) {
			endedMs = nowMs;
			completed = done;
			if (done) {
				amountRemaining = 0;
			}
			inside = false;
			updatedMs = nowMs;
		}
	}

	boolean isOpen() {
		return endedMs == 0;
	}

	/** The per-monster average kill time; 0 before the first timed kill. */
	long avgKillMs() {
		return killTimeCount == 0 ? 0 : Math.round(killTimeTotalMs / (double) killTimeCount);
	}

	/** Task kills per hour spent in the area; 0 until there is time and kills. */
	double killsPerHour() {
		return timeInAreaMs < 1_000 || kills == 0 ? 0 : Math.round(kills * 3_600_000.0 / timeInAreaMs * 10) / 10.0;
	}

	long avgLootGp() {
		return kills == 0 ? 0 : Math.round(lootGp / (double) kills);
	}

	/** From the start to the end (or now while open): the trip timer. */
	long totalMs(long nowMs) {
		return Math.max(0, (endedMs > 0 ? endedMs : nowMs) - startedMs);
	}

	/** Remaining kills times the average kill time; 0 when not known yet. */
	long estimatedLeftMs() {
		return Math.max(0, amountRemaining) * avgKillMs();
	}

	/** The POST /api/runelite/slayer/sessions/ body, keys as the contract has them. */
	Map<String, Object> payload(String player) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("player", player);
		m.put("session_uuid", uuid);
		m.put("task_id", taskId);
		m.put("task_name", taskName);
		m.put("area_id", areaId);
		m.put("master", master);
		m.put("location_key", locationKey);
		m.put("amount_original", amountOriginal);
		m.put("amount_at_start", amountAtStart);
		m.put("amount_remaining", amountRemaining);
		m.put("started_at", iso(startedMs));
		m.put("ended_at", endedMs == 0 ? null : iso(endedMs));
		m.put("completed", completed);
		m.put("time_in_area_sec", timeInAreaMs / 1000);
		m.put("trip_count", tripCount);
		m.put("bank_trips", bankTrips);
		m.put("bank_trip_sec", bankTripMs / 1000);
		m.put("kills", kills);
		m.put("avg_kill_ms", avgKillMs());
		m.put("kills_per_hour", killsPerHour());
		m.put("loot_gp", lootGp);
		m.put("avg_loot_gp", avgLootGp());
		m.put("slayer_xp", slayerXp);
		m.put("kill_times_ms", new ArrayList<>(killTimesMs));
		return m;
	}

	/** "2026-10-07T12:00:00Z": whole seconds. */
	static String iso(long epochMs) {
		return Instant.ofEpochSecond(Math.floorDiv(epochMs, 1000L)).toString();
	}
}
