package com.osrscompanion;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Times each task monster's kill on its own: from the moment you started attacking it (or your
 * first hitsplat on it, whichever came first) until it dies. Barraging or cannoning several at once
 * gives every one of them its own time, so the average kill time stays a per-monster number.
 * Monsters you never hit don't count. Keyed by the NPC's index in the scene; no game access, so
 * the caller passes the time.
 */
final class SlayerKillTimer {
	/** A fight with no death after this long is dropped (walked away, or the index was reused). */
	static final long STALE_MS = 10 * 60_000L;

	private static final class Fight {
		final long startMs;
		boolean hit;

		Fight(long startMs) {
			this.startMs = startMs;
		}
	}

	private final Map<Integer, Fight> fights = new HashMap<>();

	/** You started attacking this NPC: its timer starts unless it is already running. */
	void attacked(int npcIndex, long nowMs) {
		fights.putIfAbsent(npcIndex, new Fight(nowMs));
	}

	/** One of your hitsplats landed on this NPC (a block counts): starts its timer if needed and marks it hit. */
	void hit(int npcIndex, long nowMs) {
		fights.computeIfAbsent(npcIndex, i -> new Fight(nowMs)).hit = true;
	}

	/**
	 * The NPC died: its kill time in ms, or null when you never hit it (someone else's kill, or a
	 * second death event for the same NPC).
	 */
	Long died(int npcIndex, long nowMs) {
		Fight f = fights.remove(npcIndex);
		if (f == null || !f.hit) {
			return null;
		}
		return Math.max(0, nowMs - f.startMs);
	}

	/** The NPC left the scene alive (you ran off, it walked away): its fight is forgotten. */
	void forget(int npcIndex) {
		fights.remove(npcIndex);
	}

	/** Drops fights that never ended. */
	void prune(long nowMs) {
		for (Iterator<Fight> it = fights.values().iterator(); it.hasNext(); ) {
			if (nowMs - it.next().startMs > STALE_MS) {
				it.remove();
			}
		}
	}

	void clear() {
		fights.clear();
	}

	/** How many NPCs are being timed right now. */
	int fighting() {
		return fights.size();
	}
}
