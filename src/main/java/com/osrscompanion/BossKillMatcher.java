package com.osrscompanion;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pairs a boss kill count message with the death of the boss NPC you fought, for the boss history: the two
 * arrive a few ticks apart, in either order. The message is the kill; the death gives the fight time (first
 * hit to death). A death no message explains within {@link #WINDOW_TICKS} counts as a kill only for a boss
 * whose death is always one kill ({@link BossAreas.Boss#deathIsKill}); the caller passes that. The game's own
 * "Fight duration" (or raid "Duration") message beats the measured fight time. No game access: the caller
 * passes the tick.
 */
final class BossKillMatcher {
	/** A message and a death (or a duration) further apart than this many ticks don't belong together. */
	static final int WINDOW_TICKS = 10;
	/** The game's duration message comes this close to the kill count message. */
	static final int DURATION_TICKS = 3;
	static final long TICK_MS = 600L;

	/** What {@link #tick} found: kills to add (deaths without a message) and fight times to record. */
	static final class Result {
		int deathKills;
		/** Fight times (ms) of kills settled this tick, in order. */
		final java.util.List<Long> fightMs = new java.util.ArrayList<>();

		boolean any() {
			return deathKills > 0 || !fightMs.isEmpty();
		}
	}

	private static final class Death {
		final int tick;
		final long fightMs;
		final boolean countsAsKill;

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
		deaths.addLast(new Death(tick, fightMs, countsAsKill));
	}

	/** A kill count message: a kill now (the caller counts it); its fight time settles within the window. */
	void killMessage(int tick) {
		Kill k = new Kill(tick);
		Death d = deaths.peekFirst();
		if (d != null && tick - d.tick <= WINDOW_TICKS) {
			deaths.pollFirst();
			k.fightMs = d.fightMs;
			k.paired = true;
		}
		kills.addLast(k);
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
				if (d.fightMs > 0) {
					out.fightMs.add(d.fightMs);
				}
			}
		}
		if (durationMs > 0 && tick - durationTick > WINDOW_TICKS) {
			durationMs = -1;
		}
		return out;
	}

	/** Everything waiting is settled now (the session closes): as {@link #tick} far in the future. */
	Result flush(int tick) {
		return tick(tick + WINDOW_TICKS + DURATION_TICKS + 2);
	}

	void clear() {
		deaths.clear();
		kills.clear();
		settled.clear();
		durationMs = -1;
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
