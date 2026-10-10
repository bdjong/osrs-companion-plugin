package com.osrscompanion;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pairs a boss kill count message with the death of the boss NPC you fought, for the boss history: the two
 * arrive a few ticks apart, in either order. The message is the kill; the death gives the fight time (first
 * hit to death). A death no message explains within {@link #WINDOW_TICKS} counts as a kill only for a boss
 * whose death is always one kill ({@link BossAreas.Boss#deathIsKill}); the caller passes that. The boss's own
 * drop ({@link #loot}) is the other proof of a kill: one that no kill count message or counted death explains
 * within {@link #LOOT_WINDOW_TICKS} is a kill, so a kill still counts when its message never arrives (a boss
 * with phases, whose death alone proves nothing). The game's own "Fight duration" (or raid "Duration")
 * message beats the measured fight time. No game access: the caller passes the tick.
 */
final class BossKillMatcher {
	/** A message and a death (or a duration) further apart than this many ticks don't belong together. */
	static final int WINDOW_TICKS = 10;
	/** The game's duration message comes this close to the kill count message. */
	static final int DURATION_TICKS = 3;
	static final long TICK_MS = 600L;
	/**
	 * A drop this close to a kill (its message or counted death) is that kill's; a drop nothing explains
	 * within it is a kill of its own. Longer than {@link #WINDOW_TICKS}: the loot appears a few ticks after
	 * the death animation.
	 */
	static final int LOOT_WINDOW_TICKS = 16;

	/** What {@link #tick} found: kills to add (deaths and drops without a message) and fight times to record. */
	static final class Result {
		int deathKills;
		/** Drops of the boss that no kill count message or counted death explained. */
		int lootKills;
		/** Fight times (ms) of kills settled this tick, in order. */
		final java.util.List<Long> fightMs = new java.util.ArrayList<>();

		boolean any() {
			return deathKills > 0 || lootKills > 0 || !fightMs.isEmpty();
		}

		int kills() {
			return deathKills + lootKills;
		}
	}

	private static final class Death {
		final int tick;
		final long fightMs;
		boolean countsAsKill;

		Death(int tick, long fightMs, boolean countsAsKill) {
			this.tick = tick;
			this.fightMs = fightMs;
			this.countsAsKill = countsAsKill;
		}
	}

	private static final class Kill {
		final int tick;
		long fightMs = -1;
		boolean paired;

		Kill(int tick) {
			this.tick = tick;
		}
	}

	private final Deque<Death> deaths = new ArrayDeque<>();
	private final Deque<Kill> kills = new ArrayDeque<>();
	/** Ticks of settled kill messages no death paired with yet (a late death is theirs). */
	private final Deque<Integer> settled = new ArrayDeque<>();
	/** Ticks of the kills counted lately (messages and deaths): a drop next to one is that kill's. */
	private final Deque<Integer> counted = new ArrayDeque<>();
	/** Ticks of the boss's drops no kill explained yet. */
	private final Deque<Integer> loots = new ArrayDeque<>();
	/** The last duration message and its tick (-1: none). */
	private long durationMs = -1;
	private int durationTick;
	private boolean durationTotal;

	/**
	 * A boss NPC you fought died, {@code fightMs} after your first hit on it (-1: not timed). With
	 * {@code countsAsKill} it is a kill when no kill count message pairs with it.
	 */
	void died(int tick, long fightMs, boolean countsAsKill) {
		Kill k = waitingKill(tick);
		if (k != null) {
			k.fightMs = fightMs;
			k.paired = true;
			return;
		}
		// The message came first and its kill is settled already: this death was that kill.
		while (!settled.isEmpty() && tick - settled.peekFirst() > WINDOW_TICKS) {
			settled.pollFirst();
		}
		if (!settled.isEmpty()) {
			settled.pollFirst();
			return;
		}
		// Its drop came first (and no message): the death is the kill the drop proves.
		boolean dropped = pollLoot(tick);
		deaths.addLast(new Death(tick, fightMs, countsAsKill || dropped));
	}

	/** A kill count message: a kill now (the caller counts it); its fight time settles within the window. */
	void killMessage(int tick) {
		Kill k = new Kill(tick);
		counted.addLast(tick);
		// A drop that came before the message is this kill's.
		pollLoot(tick);
		Death d = deaths.peekFirst();
		if (d != null && tick - d.tick <= WINDOW_TICKS) {
			deaths.pollFirst();
			k.fightMs = d.fightMs;
			k.paired = true;
		}
		kills.addLast(k);
	}

	/**
	 * A drop of the boss itself (only for a boss whose drop is one kill, {@link BossAreas#dropIsKill}). Next to
	 * a counted kill it is that kill's; a death of the boss waiting for its message becomes a kill; else it
	 * waits for a message or a death, and is a kill of its own after {@link #LOOT_WINDOW_TICKS}.
	 */
	void loot(int tick) {
		prune(counted, tick);
		if (!counted.isEmpty()) {
			return;
		}
		for (Death d : deaths) {
			if (tick - d.tick <= LOOT_WINDOW_TICKS) {
				if (d.countsAsKill) {
					// Already a kill when it settles: the drop is its.
					return;
				}
				d.countsAsKill = true;
				return;
			}
		}
		loots.addLast(tick);
	}

	/**
	 * The game's own fight duration ("Fight duration: 1:23.40"), kept for the kill count message next to it.
	 * A whole raid's time ({@code total}) is not replaced by a room's that comes next to it.
	 */
	void duration(int tick, long ms, boolean total) {
		if (ms <= 0 || !total && durationTotal && durationMs > 0 && tick - durationTick <= DURATION_TICKS * 2) {
			return;
		}
		durationMs = ms;
		durationTick = tick;
		durationTotal = total;
	}

	/** Settles what waited long enough: kills get their time, deaths without a message their kill. */
	Result tick(int tick) {
		Result out = new Result();
		while (!kills.isEmpty() && tick - kills.peekFirst().tick > DURATION_TICKS) {
			Kill k = kills.pollFirst();
			if (!k.paired) {
				settled.addLast(k.tick);
			}
			long ms = k.fightMs;
			if (durationMs > 0 && Math.abs(durationTick - k.tick) <= DURATION_TICKS) {
				ms = durationMs;
				durationMs = -1;
			}
			if (ms > 0) {
				out.fightMs.add(ms);
			}
		}
		while (!deaths.isEmpty() && tick - deaths.peekFirst().tick > WINDOW_TICKS) {
			Death d = deaths.pollFirst();
			if (d.countsAsKill) {
				out.deathKills++;
				counted.addLast(d.tick);
				if (d.fightMs > 0) {
					out.fightMs.add(d.fightMs);
				}
			}
		}
		while (!loots.isEmpty() && tick - loots.peekFirst() > LOOT_WINDOW_TICKS) {
			loots.pollFirst();
			out.lootKills++;
		}
		prune(counted, tick);
		if (durationMs > 0 && tick - durationTick > WINDOW_TICKS) {
			durationMs = -1;
		}
		return out;
	}

	/** Everything waiting is settled now (the session closes): as {@link #tick} far in the future. */
	Result flush(int tick) {
		return tick(tick + LOOT_WINDOW_TICKS + DURATION_TICKS + 2);
	}

	void clear() {
		deaths.clear();
		kills.clear();
		settled.clear();
		counted.clear();
		loots.clear();
		durationMs = -1;
	}

	/** A waiting drop within the window of this kill: taken (it was this kill's). */
	private boolean pollLoot(int tick) {
		Integer t = loots.peekFirst();
		if (t != null && tick - t <= LOOT_WINDOW_TICKS) {
			loots.pollFirst();
			return true;
		}
		return false;
	}

	private static void prune(Deque<Integer> ticks, int tick) {
		while (!ticks.isEmpty() && tick - ticks.peekFirst() > LOOT_WINDOW_TICKS) {
			ticks.pollFirst();
		}
	}

	/** A kill count message waiting for its death (the message came first). */
	private Kill waitingKill(int tick) {
		for (Kill k : kills) {
			if (!k.paired && tick - k.tick <= WINDOW_TICKS) {
				return k;
			}
		}
		return null;
	}

	/**
	 * "1:23.40", "1:23", "1:02:03.6" -> ms; -1 when unreadable. Hours only with three parts.
	 */
	static long parseDuration(String s) {
		if (s == null) {
			return -1;
		}
		String t = s.trim();
		if (!t.matches("[0-9]+(:[0-9]{1,2}){1,2}(\\.[0-9]+)?")) {
			return -1;
		}
		String frac = "";
		int dot = t.indexOf('.');
		if (dot >= 0) {
			frac = t.substring(dot + 1);
			t = t.substring(0, dot);
		}
		String[] parts = t.split(":");
		long sec = 0;
		for (String p : parts) {
			sec = sec * 60 + Long.parseLong(p);
		}
		long ms = sec * 1000;
		if (!frac.isEmpty()) {
			String f = (frac + "00").substring(0, 3);
			ms += Long.parseLong(f);
		}
		return ms;
	}
}
