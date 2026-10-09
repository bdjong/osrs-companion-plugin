package com.osrscompanion;

/**
 * One feature's circuit breaker (see {@link Guard}): {@link #limit} failures within {@link #windowMs}
 * open it, and an open breaker stays open for the rest of the session. A fatal error or a freeze
 * opens it at once ({@link #trip()}). Thread-safe; {@link #isOpen()} is a plain volatile read, so it
 * is cheap enough to check on every frame.
 *
 * An exempt breaker ({@link Guard#NEVER_DISABLED}) counts failures but never opens.
 */
final class CircuitBreaker {
	private final int limit;
	private final long windowMs;
	/** The last {@link #limit} failure times, a ring: {@code next} is where the next one goes (and the oldest). */
	private final long[] times;
	private final boolean exempt;
	private int next;
	private int count;
	private int total;
	private volatile boolean open;

	CircuitBreaker(int limit, long windowMs) {
		this(limit, windowMs, false);
	}

	/** @param exempt never opens: failures are counted, {@link #trip()} does nothing */
	CircuitBreaker(int limit, long windowMs, boolean exempt) {
		this.limit = Math.max(1, limit);
		this.windowMs = windowMs;
		this.times = new long[this.limit];
		this.exempt = exempt;
	}

	boolean isExempt() {
		return exempt;
	}

	boolean isOpen() {
		return open;
	}

	/**
	 * Counts one failure at {@code nowMs}.
	 *
	 * @return true when this failure opened the breaker (only ever once)
	 */
	synchronized boolean recordFailure(long nowMs) {
		total++;
		if (open || exempt) {
			return false;
		}
		times[next] = nowMs;
		next = (next + 1) % limit;
		if (count < limit) {
			count++;
		}
		// Full ring: the oldest of the last `limit` failures is the one `next` points at now.
		if (count == limit && nowMs - times[next] <= windowMs) {
			open = true;
			return true;
		}
		return false;
	}

	/** Opens the breaker now (a fatal error or a freeze); true when it was still closed. Never for an exempt one. */
	synchronized boolean trip() {
		if (open || exempt) {
			return false;
		}
		open = true;
		return true;
	}

	/** Every failure counted, before and after the breaker opened. */
	synchronized int totalFailures() {
		return total;
	}
}
