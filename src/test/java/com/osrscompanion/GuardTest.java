package com.osrscompanion;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GuardTest {
	private final AtomicLong nowMs = new AtomicLong(1_000_000);
	private final AtomicLong nanos = new AtomicLong(0);
	private Guard guard;
	private RecordingListener listener;

	static class RecordingListener implements Guard.Listener {
		final List<String> failures = new ArrayList<>();
		final List<String> fatal = new ArrayList<>();
		final List<String> freezes = new ArrayList<>();
		final List<String> disabled = new ArrayList<>();

		@Override
		public void onFailure(String feature, Throwable error) {
			failures.add(feature + ":" + error.getClass().getSimpleName());
		}

		@Override
		public void onFatal(String feature, Throwable error) {
			fatal.add(feature);
		}

		@Override
		public void onFreeze(String feature, long elapsedMs, StackTraceElement[] stack) {
			freezes.add(feature + ":" + elapsedMs);
		}

		@Override
		public void onDisabled(String feature) {
			disabled.add(feature);
		}
	}

	@Before
	public void setUp() {
		guard = new Guard(nowMs::get, nanos::get);
		listener = new RecordingListener();
		guard.setListener(listener);
	}

	private void failOnce(String feature) {
		guard.runIn(feature, () -> {
			throw new IllegalStateException("boom");
		});
	}

	@Test
	public void catchesAndReportsAFailure() {
		failOnce("farm-run");
		assertEquals(1, listener.failures.size());
		assertEquals("farm-run:IllegalStateException", listener.failures.get(0));
		assertFalse(guard.isDisabled("farm-run"));
	}

	@Test
	public void fiveFailuresInAMinuteTurnTheFeatureOffOnce() {
		for (int i = 0; i < Guard.FAILURE_LIMIT; i++) {
			nowMs.addAndGet(1_000);
			failOnce("slayer-helper");
		}
		assertTrue(guard.isDisabled("slayer-helper"));
		assertEquals(1, listener.disabled.size());
		// Skipped from now on: the body does not run, nothing more is reported.
		AtomicInteger runs = new AtomicInteger();
		guard.runIn("slayer-helper", runs::incrementAndGet);
		failOnce("slayer-helper");
		assertEquals(0, runs.get());
		assertEquals(Guard.FAILURE_LIMIT, listener.failures.size());
		assertEquals(1, listener.disabled.size());
	}

	@Test
	public void otherFeaturesKeepWorking() {
		for (int i = 0; i < Guard.FAILURE_LIMIT; i++) {
			failOnce("bad");
		}
		AtomicInteger runs = new AtomicInteger();
		guard.runIn("good", runs::incrementAndGet);
		assertEquals(1, runs.get());
		assertFalse(guard.isDisabled("good"));
	}

	@Test
	public void outOfMemoryIsFatalForTheFeatureAtOnce() {
		guard.runIn("goal-images", () -> {
			throw new OutOfMemoryError("test");
		});
		assertTrue(guard.isDisabled("goal-images"));
		assertEquals(1, listener.fatal.size());
		assertEquals(1, listener.disabled.size());
	}

	@Test
	public void threadDeathIsRethrown() {
		try {
			guard.runIn("x", () -> {
				throw new ThreadDeath();
			});
			fail("ThreadDeath must not be swallowed");
		} catch (ThreadDeath expected) {
			// ok
		}
	}

	@Test
	public void renderReturnsNullOnFailureAndTheResultOtherwise() {
		Dimension d = new Dimension(3, 4);
		assertSame(d, guard.callIn("overlay", () -> d, null));
		assertNull(guard.callIn("overlay", () -> {
			throw new NullPointerException();
		}, null));
	}

	@Test
	public void aListenerThatThrowsNeverEscapes() {
		guard.setListener(new RecordingListener() {
			@Override
			public void onFailure(String feature, Throwable error) {
				throw new RuntimeException("reporter broken");
			}
		});
		failOnce("x");
		// no exception: done
	}

	@Test
	public void resetTurnsFeaturesBackOn() {
		for (int i = 0; i < Guard.FAILURE_LIMIT; i++) {
			failOnce("x");
		}
		assertTrue(guard.isDisabled("x"));
		guard.resetBreakers();
		assertFalse(guard.isDisabled("x"));
	}

	@Test
	public void publishesTheOutermostSectionOnTheWatchedThreadOnly() {
		assertNull(guard.currentSection());
		guard.runIn("outer", () -> assertNull("not watched yet", guard.currentSection()));
		guard.watchThread(Thread.currentThread());
		nanos.set(500);
		guard.runIn("outer", () -> {
			Guard.Section s = guard.currentSection();
			assertEquals("outer", s.feature);
			assertEquals(500, s.startNanos);
			guard.runIn("inner", () -> assertEquals("outer", guard.currentSection().feature));
			// Still running after the nested one ended.
			assertEquals("outer", guard.currentSection().feature);
		});
		assertNull(guard.currentSection());
	}

	@Test
	public void sectionEndsEvenWhenTheBodyThrows() {
		guard.watchThread(Thread.currentThread());
		failOnce("x");
		assertNull(guard.currentSection());
	}

	@Test
	public void frozenTurnsTheFeatureOffAndReportsIt() {
		guard.frozen("farm-run-markers", 2500, new StackTraceElement[0]);
		assertTrue(guard.isDisabled("farm-run-markers"));
		assertEquals("farm-run-markers:2500", listener.freezes.get(0));
		assertEquals(1, listener.disabled.size());
	}

	@Test
	public void displayNameReadsAsText() {
		assertEquals("Slayer helper overlay", Guard.displayName("slayer-helper-overlay"));
	}

	@Test
	public void featureCountIsBounded() {
		for (int i = 0; i < Guard.MAX_FEATURES + 50; i++) {
			guard.runIn("f" + i, () -> { });
		}
		// The extra features share one breaker: failures there turn "other" off, not the first ones.
		for (int i = 0; i < Guard.FAILURE_LIMIT; i++) {
			failOnce("f" + (Guard.MAX_FEATURES + 10));
		}
		assertTrue(guard.isDisabled(Guard.OTHER_FEATURE));
		assertFalse(guard.isDisabled("f0"));
	}
}
