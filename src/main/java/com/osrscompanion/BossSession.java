package com.osrscompanion;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One boss session of the boss history: a visit to one boss, from walking (or teleporting) into its lair
 * until you leave it for good. It is made of trips: a trip starts in the lair and ends with a bank, a
 * teleport out, a death or logging out; walking out and back in without one of those is the same trip.
 *
 * <p>The session closes when you were away from the lair for {@link #AWAY_CLOSE_MS} (left and banked, or went
 * elsewhere), when nothing happened in the lair for {@link #IDLE_CLOSE_MS}, or when you start at another boss.
 * It records the time inside the lair (tick by tick, only while inside), kills (and the game's kill count),
 * each kill's fight time, deaths, bank visits, every drop (item ids, quantities and GE value at the time) and
 * the supplies used (from {@link BossTripTracker}'s count), per session and per trip.
 *
 * <p>Stored as JSON in the RuneScape profile's config so a client restart keeps it, and sent whole (re-sent as
 * it grows, upserted by its uuid) as the POST /api/runelite/boss-sessions body ({@link #payload}). No game
 * access: the caller passes the time.
 */
final class BossSession {
	static final long MAX_TICK_GAP_MS = 3_000L;
	/** Away from the lair this long: the session is over (it ends when you left). */
	static final long AWAY_CLOSE_MS = 20 * 60_000L;
	/** In the lair this long without a kill, a drop or a death: the session is over (it ends at the last one). */
	static final long IDLE_CLOSE_MS = 30 * 60_000L;
	/** A bank trip (left, banked, back in the lair) counts at most this long. */
	static final long BANK_TRIP_CAP_MS = 8 * 60_000L;
	static final int MAX_KILL_TIMES = 300;
	static final int MAX_DROPS = 300;
	static final int MAX_DROP_ITEMS = 40;
	static final int MAX_LOOT_ITEMS = 300;
	static final int MAX_TRIPS = 100;
	static final int MAX_SUPPLIES = 100;

	/** One item stack (loot or supplies) as stored and sent. */
	static final class Stack {
		@SerializedName("item_id")
		int itemId;
		@SerializedName("quantity")
		long quantity;
		/** GE value of the stack when it dropped (loot only). */
		@SerializedName("gp")
		long gp;
		/** Supplies: the quantity is doses and one item holds this many (0: not dosed). */
		@SerializedName("dose")
		int dose;

		Stack() {
		}

		Stack(int itemId, long quantity, long gp, int dose) {
			this.itemId = itemId;
			this.quantity = quantity;
			this.gp = gp;
			this.dose = dose;
		}

		Map<String, Object> payload(boolean loot) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("item_id", itemId);
			m.put("quantity", quantity);
			if (loot) {
				m.put("gp", gp);
			} else if (dose > 0) {
				m.put("dose", dose);
			}
			return m;
		}
	}

	/** One drop: what one kill (or chest) gave. */
	static final class Drop {
		@SerializedName("at_ms")
		long atMs;
		/** The session's kill it came with (1-based; 0: before the first kill). */
		@SerializedName("kill")
		int kill;
		@SerializedName("trip")
		int trip;
		@SerializedName("source")
		String source;
		@SerializedName("gp")
		long gp;
		@SerializedName("items")
		List<Stack> items = new ArrayList<>();

		Map<String, Object> payload() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("at", iso(atMs));
			m.put("kill", kill);
			m.put("trip", trip);
			m.put("source", source);
			m.put("gp", gp);
			List<Map<String, Object>> list = new ArrayList<>();
			for (Stack s : items) {
				list.add(s.payload(true));
			}
			m.put("items", list);
			return m;
		}
	}

	/** One trip: from the lair until a bank, a teleport out, a death or logging out. */
	static final class Trip {
		@SerializedName("n")
		int n;
		@SerializedName("started_ms")
		long startedMs;
		/** 0 while it runs. */
		@SerializedName("ended_ms")
		long endedMs;
		/** bank | teleport | death | logout | session end */
		@SerializedName("end_reason")
		String endReason;
		@SerializedName("kills")
		int kills;
		@SerializedName("deaths")
		int deaths;
		@SerializedName("loot_gp")
		long lootGp;
		@SerializedName("time_in_lair_ms")
		long timeInLairMs;
		@SerializedName("supplies")
		List<Stack> supplies = new ArrayList<>();

		boolean isOpen() {
			return endedMs == 0;
		}

		Map<String, Object> payload() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("n", n);
			m.put("started_at", iso(startedMs));
			m.put("ended_at", endedMs == 0 ? null : iso(endedMs));
			m.put("end_reason", endReason);
			m.put("kills", kills);
			m.put("deaths", deaths);
			m.put("loot_gp", lootGp);
			m.put("time_in_lair_sec", timeInLairMs / 1000);
			m.put("supplies", stacks(supplies, false));
			return m;
		}
	}

	@SerializedName("session_uuid")
	String uuid;
	@SerializedName("player")
	String player;
	@SerializedName("boss_key")
	String bossKey;
	@SerializedName("boss_name")
	String bossName;
	/** boss | raid */
	@SerializedName("kind")
	String kind;
	@SerializedName("started_ms")
	long startedMs;
	/** 0 while open. */
	@SerializedName("ended_ms")
	long endedMs;
	/** left | idle | next boss | restart */
	@SerializedName("end_reason")
	String endReason;
	@SerializedName("time_in_lair_ms")
	long timeInLairMs;
	@SerializedName("inside")
	boolean inside;
	@SerializedName("last_tick_ms")
	long lastTickMs;
	/** When you last left the lair (0: inside, or not counting). */
	@SerializedName("left_ms")
	long leftMs;
	/** A bank was opened since the last trip started (one bank visit per break). */
	@SerializedName("bank_seen")
	boolean bankSeen;
	@SerializedName("bank_visits")
	int bankVisits;
	/** Times away with a bank visit that ended back in the lair, and their length (each capped). */
	@SerializedName("bank_trips")
	int bankTrips;
	@SerializedName("bank_trip_ms")
	long bankTripMs;
	@SerializedName("deaths")
	int deaths;
	@SerializedName("kills")
	int kills;
	/** The kill count the game last said (0: not seen). */
	@SerializedName("kill_count")
	int killCount;
	@SerializedName("kill_times_ms")
	List<Long> killTimesMs = new ArrayList<>();
	@SerializedName("kill_time_total_ms")
	long killTimeTotalMs;
	@SerializedName("kill_time_count")
	int killTimeCount;
	@SerializedName("loot_gp")
	long lootGp;
	@SerializedName("loot")
	List<Stack> loot = new ArrayList<>();
	@SerializedName("drops")
	List<Drop> drops = new ArrayList<>();
	@SerializedName("supplies")
	List<Stack> supplies = new ArrayList<>();
	@SerializedName("trips")
	List<Trip> trips = new ArrayList<>();
	@SerializedName("trip_count")
	int tripCount;
	/** Last kill, drop, death or entering the lair (for the idle close). */
	@SerializedName("last_activity_ms")
	long lastActivityMs;
	@SerializedName("updated_ms")
	long updatedMs;

	static BossSession start(BossAreas.Boss boss, long nowMs) {
		BossSession s = new BossSession();
		s.uuid = UUID.randomUUID().toString();
		s.bossKey = boss.key;
		s.bossName = boss.name;
		s.kind = boss.kind.wire;
		s.startedMs = nowMs;
		s.lastActivityMs = nowMs;
		s.updatedMs = nowMs;
		return s;
	}

	/** Another boss of the same lair (Phosani's at the Nightmare's), while nothing was recorded yet. */
	boolean relabel(BossAreas.Boss boss, long nowMs) {
		if (boss == null || boss.key.equals(bossKey) || kills > 0 || !drops.isEmpty()) {
			return false;
		}
		bossKey = boss.key;
		bossName = boss.name;
		kind = boss.kind.wire;
		updatedMs = nowMs;
		return true;
	}

	/**
	 * One game tick, in the lair or not. Inside: the time since the last tick inside counts (for the session
	 * and its trip), and a trip starts when none runs (on entering, or after a bank in the lair). Leaving
	 * notes the time; back after a bank visit, the time away is a bank trip. Returns true on entering.
	 */
	boolean tick(boolean nowInside, long nowMs) {
		boolean entered = false;
		if (nowInside) {
			if (!inside) {
				entered = true;
				lastActivityMs = Math.max(lastActivityMs, nowMs);
				if (leftMs > 0 && bankSeen) {
					long away = nowMs - leftMs;
					if (away > 0 && away <= AWAY_CLOSE_MS) {
						bankTrips++;
						bankTripMs += Math.min(away, BANK_TRIP_CAP_MS);
					}
				}
				leftMs = 0;
				updatedMs = nowMs;
			} else if (lastTickMs > 0) {
				long gap = nowMs - lastTickMs;
				if (gap > 0 && gap <= MAX_TICK_GAP_MS) {
					timeInLairMs += gap;
					Trip t = openTrip();
					if (t != null) {
						t.timeInLairMs += gap;
					}
					updatedMs = nowMs;
				}
			}
			if (openTrip() == null) {
				startTrip(nowMs);
			}
			lastTickMs = nowMs;
		} else if (inside) {
			leftMs = nowMs;
			updatedMs = nowMs;
		}
		inside = nowInside;
		return entered;
	}

	/** A bank was opened: the trip ends (when you left the lair, or now in it), and it is a bank visit. */
	void bankOpened(long nowMs) {
		if (!bankSeen) {
			bankSeen = true;
			bankVisits++;
			updatedMs = nowMs;
		}
		endTrip("bank", inside || leftMs == 0 ? nowMs : leftMs);
	}

	/** A far jump that leaves you outside the lair: the trip ends (a teleport out). */
	void teleportedOut(long nowMs) {
		if (!inside) {
			endTrip("teleport", nowMs);
		}
	}

	void died(long nowMs) {
		deaths++;
		Trip t = openTrip();
		if (t != null) {
			t.deaths++;
		}
		lastActivityMs = nowMs;
		endTrip("death", nowMs);
		updatedMs = nowMs;
	}

	/** Logged out: the trip ends; the session stays open (a quick relog goes on with it). */
	void loggedOut(long nowMs) {
		endTrip("logout", nowMs);
		if (inside) {
			leftMs = nowMs;
		}
		inside = false;
		lastTickMs = 0;
	}

	/** After loading from the config: the time offline is never counted. */
	void resumed() {
		lastTickMs = 0;
		inside = false;
		if (killTimesMs == null) {
			killTimesMs = new ArrayList<>();
		}
		if (loot == null) {
			loot = new ArrayList<>();
		}
		if (drops == null) {
			drops = new ArrayList<>();
		}
		if (supplies == null) {
			supplies = new ArrayList<>();
		}
		if (trips == null) {
			trips = new ArrayList<>();
		}
	}

	/** A kill (a kill count message, or the boss's death); {@code killCount} 0 when the game said none. */
	void addKill(int killCount, long nowMs) {
		kills++;
		if (killCount > 0) {
			this.killCount = killCount;
		}
		Trip t = openTrip();
		if (t == null) {
			// A kill outside the lair (a learned lair, the chest of a raid): it still starts a trip.
			t = startTrip(nowMs);
		}
		t.kills++;
		lastActivityMs = nowMs;
		updatedMs = nowMs;
	}

	void addKillTime(long ms, long nowMs) {
		if (ms <= 0) {
			return;
		}
		killTimeTotalMs += ms;
		killTimeCount++;
		killTimesMs.add(ms);
		while (killTimesMs.size() > MAX_KILL_TIMES) {
			killTimesMs.remove(0);
		}
		updatedMs = nowMs;
	}

	/** A drop: its stacks ({itemId, quantity, gp}) go into the drop list, the loot totals and the trip. */
	void addDrop(String source, List<Stack> items, long nowMs) {
		if (items == null || items.isEmpty()) {
			return;
		}
		Drop d = new Drop();
		d.atMs = nowMs;
		d.kill = kills;
		Trip t = openTrip();
		if (t == null && !trips.isEmpty()) {
			// Loot just after the trip ended (the chest after a teleport): the last trip's.
			t = trips.get(trips.size() - 1);
		}
		d.trip = t == null ? 0 : t.n;
		d.source = source;
		for (Stack s : items) {
			if (s.quantity <= 0) {
				continue;
			}
			d.gp += s.gp;
			if (d.items.size() < MAX_DROP_ITEMS) {
				d.items.add(new Stack(s.itemId, s.quantity, s.gp, 0));
			}
			merge(loot, s, true);
		}
		lootGp += d.gp;
		if (t != null) {
			t.lootGp += d.gp;
		}
		drops.add(d);
		while (drops.size() > MAX_DROPS) {
			drops.remove(0);
		}
		while (loot.size() > MAX_LOOT_ITEMS) {
			loot.remove(cheapest(loot));
		}
		lastActivityMs = nowMs;
		updatedMs = nowMs;
	}

	/** Supplies a finished {@link BossTripTracker} trip used: the session's and the (last) trip's. */
	void addSupplies(List<Stack> used, long nowMs) {
		if (used == null || used.isEmpty()) {
			return;
		}
		Trip t = openTrip();
		if (t == null && !trips.isEmpty()) {
			t = trips.get(trips.size() - 1);
		}
		for (Stack s : used) {
			if (supplies.size() < MAX_SUPPLIES || find(supplies, s) != null) {
				merge(supplies, s, false);
			}
			if (t != null && (t.supplies.size() < MAX_SUPPLIES || find(t.supplies, s) != null)) {
				merge(t.supplies, s, false);
			}
		}
		updatedMs = nowMs;
	}

	/** Whether the session should end now, and when it ended: away too long, or idle in the lair. 0: not. */
	long closeAt(long nowMs) {
		if (!inside && leftMs > 0 && nowMs - leftMs > AWAY_CLOSE_MS) {
			return leftMs;
		}
		if (!inside && leftMs == 0 && lastTickMs == 0 && nowMs - lastActivityMs > AWAY_CLOSE_MS) {
			// Loaded after a restart and never back in the lair.
			return Math.max(startedMs, lastActivityMs);
		}
		if (inside && nowMs - lastActivityMs > IDLE_CLOSE_MS) {
			return Math.max(startedMs, lastActivityMs);
		}
		return 0;
	}

	void close(String reason, long atMs) {
		if (endedMs != 0) {
			return;
		}
		endedMs = Math.max(startedMs, atMs);
		endReason = reason;
		endTrip("session end", endedMs);
		inside = false;
		updatedMs = Math.max(updatedMs, atMs);
	}

	boolean isOpen() {
		return endedMs == 0;
	}

	/** Worth keeping (and sending): something happened, not just a walk through the lair. */
	boolean hasData() {
		return kills > 0 || deaths > 0 || !drops.isEmpty();
	}

	Trip openTrip() {
		if (trips.isEmpty()) {
			return null;
		}
		Trip t = trips.get(trips.size() - 1);
		return t.isOpen() ? t : null;
	}

	private Trip startTrip(long nowMs) {
		Trip t = new Trip();
		t.n = ++tripCount;
		t.startedMs = nowMs;
		trips.add(t);
		while (trips.size() > MAX_TRIPS) {
			trips.remove(0);
		}
		bankSeen = false;
		updatedMs = nowMs;
		return t;
	}

	private void endTrip(String reason, long atMs) {
		Trip t = openTrip();
		if (t == null) {
			return;
		}
		t.endedMs = Math.max(t.startedMs, atMs);
		t.endReason = reason;
		updatedMs = Math.max(updatedMs, atMs);
	}

	long avgKillMs() {
		return killTimeCount == 0 ? 0 : Math.round(killTimeTotalMs / (double) killTimeCount);
	}

	double killsPerHour() {
		return timeInLairMs < 1_000 || kills == 0 ? 0 : Math.round(kills * 3_600_000.0 / timeInLairMs * 10) / 10.0;
	}

	/** The POST /api/runelite/boss-sessions body. */
	Map<String, Object> payload(String player) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("player", player);
		m.put("session_uuid", uuid);
		m.put("boss_key", bossKey);
		m.put("boss_name", bossName);
		m.put("kind", kind);
		m.put("started_at", iso(startedMs));
		m.put("ended_at", endedMs == 0 ? null : iso(endedMs));
		m.put("end_reason", endReason);
		m.put("time_in_lair_sec", timeInLairMs / 1000);
		m.put("trip_count", tripCount);
		m.put("bank_visits", bankVisits);
		m.put("bank_trips", bankTrips);
		m.put("bank_trip_sec", bankTripMs / 1000);
		m.put("deaths", deaths);
		m.put("kills", kills);
		m.put("kill_count", killCount > 0 ? killCount : null);
		m.put("avg_kill_ms", avgKillMs());
		m.put("kills_per_hour", killsPerHour());
		m.put("kill_times_ms", new ArrayList<>(killTimesMs));
		m.put("loot_gp", lootGp);
		m.put("loot", stacks(loot, true));
		List<Map<String, Object>> dl = new ArrayList<>();
		for (Drop d : drops) {
			dl.add(d.payload());
		}
		m.put("drops", dl);
		m.put("supplies", stacks(supplies, false));
		List<Map<String, Object>> tl = new ArrayList<>();
		for (Trip t : trips) {
			tl.add(t.payload());
		}
		m.put("trips", tl);
		return m;
	}

	private static List<Map<String, Object>> stacks(List<Stack> list, boolean loot) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (list != null) {
			for (Stack s : list) {
				out.add(s.payload(loot));
			}
		}
		return out;
	}

	private static Stack find(List<Stack> list, Stack s) {
		for (Stack e : list) {
			if (e.itemId == s.itemId && e.dose == s.dose) {
				return e;
			}
		}
		return null;
	}

	private static void merge(List<Stack> list, Stack s, boolean loot) {
		Stack e = find(list, s);
		if (e == null) {
			list.add(new Stack(s.itemId, s.quantity, loot ? s.gp : 0, s.dose));
		} else {
			e.quantity += s.quantity;
			if (loot) {
				e.gp += s.gp;
			}
		}
	}

	private static int cheapest(List<Stack> list) {
		int at = 0;
		for (int i = 1; i < list.size(); i++) {
			if (list.get(i).gp < list.get(at).gp) {
				at = i;
			}
		}
		return at;
	}

	/** "2026-10-07T12:00:00Z": whole seconds. */
	static String iso(long epochMs) {
		return Instant.ofEpochSecond(Math.floorDiv(epochMs, 1000L)).toString();
	}
}
