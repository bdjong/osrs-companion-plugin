package com.osrscompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CircuitBreakerTest {
	@Test
	public void opensOnTheFifthFailureWithinTheWindow() {
		CircuitBreaker b = new CircuitBreaker(5, 60_000);
		for (int i = 0; i < 4; i++) {
			assertFalse(b.recordFailure(1_000 + i * 10_000L));
			assertFalse(b.isOpen());
		}
		assertTrue(b.recordFailure(41_000));
		assertTrue(b.isOpen());
	}

	@Test
	public void failuresSpreadOverMoreThanTheWindowKeepItClosed() {
		CircuitBreaker b = new CircuitBreaker(5, 60_000);
		// One failure every 20 s: never 5 within a minute.
		for (int i = 0; i < 20; i++) {
			assertFalse(b.recordFailure(i * 20_000L));
		}
		assertFalse(b.isOpen());
		assertEquals(20, b.totalFailures());
	}

	@Test
	public void aBurstAfterOldFailuresOpensIt() {
		CircuitBreaker b = new CircuitBreaker(5, 60_000);
		b.recordFailure(0);
		b.recordFailure(1);
		// Long quiet, then 5 quick ones.
		long t = 10 * 60_000L;
		for (int i = 0; i < 4; i++) {
			assertFalse(b.recordFailure(t + i));
		}
		assertTrue(b.recordFailure(t + 4));
	}

	@Test
	public void staysOpenAndReportsOpeningOnce() {
		CircuitBreaker b = new CircuitBreaker(2, 60_000);
		b.recordFailure(0);
		assertTrue(b.recordFailure(1));
		assertFalse(b.recordFailure(2));
		assertFalse(b.trip());
		assertTrue(b.isOpen());
	}

	@Test
	public void tripOpensAtOnce() {
		CircuitBreaker b = new CircuitBreaker(5, 60_000);
		assertTrue(b.trip());
		assertTrue(b.isOpen());
		assertFalse(b.recordFailure(0));
	}

	@Test
	public void anExemptBreakerCountsButNeverOpens() {
		CircuitBreaker b = new CircuitBreaker(5, 60_000, true);
		for (int i = 0; i < 50; i++) {
			assertFalse(b.recordFailure(i));
		}
		assertFalse(b.trip());
		assertFalse(b.isOpen());
		assertTrue(b.isExempt());
		assertEquals(50, b.totalFailures());
	}
}
