package com.osrscompanion;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A session's kill log: the task kills per exact NPC (its id, so each version of a monster apart), the
 * map region you stood in and whether that was a multi-combat area, with the summed fight time of the
 * timed ones. Kept aggregated (one entry per NPC id, region and multi) so it stays small, and sent whole
 * with the session as {@code kill_log}. No game access.
 */
final class SlayerKillLog {
	private SlayerKillLog() {
	}

	/** A session keeps at most this many entries: the most killed. */
	static final int MAX_ENTRIES = 50;

	/** What a kill is logged under: the NPC (id, name, level) and where you were (region, multi). */
	static final class Npc {
		final int id;
		final String name;
		final int combatLevel;
		final int regionId;
		final boolean multi;

		Npc(int id, String name, int combatLevel, int regionId, boolean multi) {
			this.id = id;
			this.name = clean(name);
			this.combatLevel = combatLevel;
			this.regionId = regionId;
			this.multi = multi;
		}

		/** The same entry: the name and level follow from the id. */
		boolean sameKey(Entry e) {
			return e != null && e.npcId == id && e.regionId == regionId && e.multi == multi;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Npc)) {
				return false;
			}
			Npc n = (Npc) o;
			return n.id == id && n.regionId == regionId && n.multi == multi;
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, regionId, multi);
		}

		@Override
		public String toString() {
			return name + " (" + id + ") in " + regionId + (multi ? " multi" : "");
		}
	}

	/** One line of the log, stored with the session (gson) under the contract's keys. */
	static final class Entry {
		@SerializedName("npc_id")
		int npcId;
		@SerializedName("npc_name")
		String npcName;
		@SerializedName("combat_level")
		int combatLevel;
		@SerializedName("region_id")
		int regionId;
		@SerializedName("multi")
		boolean multi;
		@SerializedName("kills")
		int kills;
		@SerializedName("fight_ms_total")
		long fightMsTotal;
		@SerializedName("fight_ms_count")
		int fightMsCount;

		Map<String, Object> payload() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("npc_id", npcId);
			m.put("npc_name", npcName == null ? "" : npcName);
			m.put("combat_level", combatLevel);
			m.put("region_id", regionId);
			m.put("multi", multi);
			m.put("kills", kills);
			m.put("fight_ms_total", fightMsTotal);
			m.put("fight_ms_count", fightMsCount);
			return m;
		}
	}

	/** The entry for this NPC in the log, added when new (the log then capped, never dropping this one). */
	static Entry entry(List<Entry> log, Npc npc) {
		for (Entry e : log) {
			if (npc.sameKey(e)) {
				if (npc.name != null && !npc.name.isEmpty()) {
					e.npcName = npc.name;
				}
				if (npc.combatLevel > 0) {
					e.combatLevel = npc.combatLevel;
				}
				return e;
			}
		}
		Entry e = new Entry();
		e.npcId = npc.id;
		e.npcName = npc.name;
		e.combatLevel = npc.combatLevel;
		e.regionId = npc.regionId;
		e.multi = npc.multi;
		log.add(e);
		cap(log, e);
		return e;
	}

	/** n task kills of this NPC. */
	static void addKills(List<Entry> log, Npc npc, int n) {
		if (npc != null && n > 0) {
			entry(log, npc).kills += n;
		}
	}

	/** One timed kill (a fight you hit) of this NPC. */
	static void addFightTime(List<Entry> log, Npc npc, long ms) {
		if (npc != null && ms >= 0) {
			Entry e = entry(log, npc);
			e.fightMsTotal += ms;
			e.fightMsCount++;
		}
	}

	/** Over {@link #MAX_ENTRIES}: the least killed go (fewest kills, then fewest timed; never {@code keep}). */
	static void cap(List<Entry> log, Entry keep) {
		while (log.size() > MAX_ENTRIES) {
			Entry least = null;
			for (Entry e : log) {
				if (e != keep && (least == null || e.kills < least.kills || e.kills == least.kills && e.fightMsCount < least.fightMsCount)) {
					least = e;
				}
			}
			if (least == null) {
				return;
			}
			log.remove(least);
		}
	}

	/** The {@code kill_log} array: every entry (at most {@link #MAX_ENTRIES}), most killed first. */
	static List<Map<String, Object>> payload(List<Entry> log) {
		List<Entry> sorted = new ArrayList<>();
		if (log != null) {
			for (Entry e : log) {
				if (e != null) {
					sorted.add(e);
				}
			}
		}
		sorted.sort((a, b) -> Integer.compare(b.kills, a.kills));
		List<Map<String, Object>> out = new ArrayList<>();
		for (Entry e : sorted) {
			if (out.size() >= MAX_ENTRIES) {
				break;
			}
			out.add(e.payload());
		}
		return out;
	}

	/** "Unknown spot (region 12345)"'s key: a session at a task spot that no location of the guide covers. */
	static String spotKey(int regionId) {
		return "spot-" + regionId;
	}

	static String spotName(int regionId) {
		return "Unknown spot (region " + regionId + ")";
	}

	static String clean(String s) {
		return s == null ? "" : s.replaceAll("<[^>]*>", "").replace(' ', ' ').trim();
	}

	/**
	 * Pairs the task count going down with the task monster that died: the count and the death arrive in
	 * the same tick or a tick apart, in either order. A death you hit is preferred; a counted kill no death
	 * explains within a few ticks goes to the last monster that did. No game access: the caller passes
	 * the game tick.
	 */
	static final class Matcher {
		/** A death and a count further apart than this many ticks don't belong together. */
		static final int WINDOW_TICKS = 3;

		private static final class Death {
			final Npc npc;
			final int tick;
			final boolean mine;

			Death(Npc npc, int tick, boolean mine) {
				this.npc = npc;
				this.tick = tick;
				this.mine = mine;
			}
		}

		private final Deque<Death> deaths = new ArrayDeque<>();
		private final Deque<Integer> waiting = new ArrayDeque<>();
		private Npc last;

		/**
		 * A task monster died (mine: you hit it). Returns it when it explains a kill counted just before,
		 * which is then logged; else it waits for the count.
		 */
		Npc died(Npc npc, boolean mine, int tick) {
			if (npc == null) {
				return null;
			}
			drop(tick);
			if (mine || last == null) {
				last = npc;
			}
			if (!waiting.isEmpty()) {
				waiting.removeFirst();
				last = npc;
				return npc;
			}
			deaths.addLast(new Death(npc, tick, mine));
			return null;
		}

		/**
		 * The task count went down by n: the monsters these kills were (yours first, the latest first). Kills
		 * no death explains yet wait a few ticks for one ({@link #expire}) when {@code wait} (the session
		 * counted them); else they only use up the deaths.
		 */
		List<Npc> counted(int n, int tick, boolean wait) {
			drop(tick);
			List<Npc> out = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				Death d = take();
				if (d == null) {
					if (wait) {
						waiting.addLast(tick);
					}
				} else {
					last = d.npc;
					out.add(d.npc);
				}
			}
			return out;
		}

		/** Counted kills that waited too long (or all of them, when {@code all}) go to the last monster killed. */
		List<Npc> expire(int tick, boolean all) {
			List<Npc> out = new ArrayList<>();
			while (!waiting.isEmpty() && (all || tick - waiting.peekFirst() > WINDOW_TICKS)) {
				waiting.removeFirst();
				if (last != null) {
					out.add(last);
				}
			}
			drop(tick);
			return out;
		}

		/** A recent task monster's death not yet paired with a count, or the last one; null when none. */
		Npc recent() {
			Death d = deaths.peekLast();
			return d != null ? d.npc : last;
		}

		void clear() {
			deaths.clear();
			waiting.clear();
			last = null;
		}

		private Death take() {
			Death pick = null;
			for (Iterator<Death> it = deaths.descendingIterator(); it.hasNext(); ) {
				Death d = it.next();
				if (d.mine) {
					pick = d;
					break;
				}
				if (pick == null) {
					pick = d;
				}
			}
			if (pick != null) {
				deaths.remove(pick);
			}
			return pick;
		}

		private void drop(int tick) {
			while (!deaths.isEmpty() && tick - deaths.peekFirst().tick > WINDOW_TICKS) {
				deaths.removeFirst();
			}
		}
	}
}
