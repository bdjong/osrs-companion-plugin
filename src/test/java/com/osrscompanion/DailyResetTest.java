package com.osrscompanion;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import org.junit.Test;

/** The 00:00 UTC reset and the hand ticks' expiry. */
public class DailyResetTest {
	private static long at(String iso) {
		return Instant.parse(iso).toEpochMilli();
	}

	@Test
	public void nextResetIsMidnightUtc() {
		assertEquals(at("2026-10-08T00:00:00Z"), DailyReset.nextReset(at("2026-10-07T13:30:00Z")));
		assertEquals(at("2026-10-08T00:00:00Z"), DailyReset.nextReset(at("2026-10-07T23:59:59Z")));
		// Exactly at the reset: the next one is a day on.
		assertEquals(at("2026-10-09T00:00:00Z"), DailyReset.nextReset(at("2026-10-08T00:00:00Z")));
	}

	@Test
	public void dayChangesAtMidnightUtc() {
		assertEquals(DailyReset.day(at("2026-10-07T00:00:00Z")), DailyReset.day(at("2026-10-07T23:59:59Z")));
		assertEquals(DailyReset.day(at("2026-10-07T23:59:59Z")) + 1, DailyReset.day(at("2026-10-08T00:00:01Z")));
	}

	@Test
	public void untilText() {
		long now = at("2026-10-07T18:47:30Z");
		assertEquals("5h 13m", DailyReset.until(now, DailyReset.nextReset(now)));
		assertEquals("45m", DailyReset.until(now, now + 45 * 60_000));
		assertEquals("2d 3h", DailyReset.until(now, now + (51 * 60) * 60_000L));
		assertEquals("now", DailyReset.until(now, now - 1));
	}

	@Test
	public void handTicksLastUntilTheirReset() {
		long now = at("2026-10-07T13:30:00Z");
		assertEquals(at("2026-10-08T00:00:00Z"), DailiesManager.tickExpiry("daily", now));
		assertEquals(at("2026-10-14T00:00:00Z"), DailiesManager.tickExpiry("weekly", now));
		assertEquals(now + 50 * 60_000L, DailiesManager.tickExpiry("timer", now));
	}
}
