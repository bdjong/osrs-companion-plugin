package com.osrscompanion;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.runelite.client.callback.ClientThread;
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

	// ------------------------------------------------------------------ never-disabled features

	@Test
	public void syncIsNeverTurnedOffButEveryFailureIsReported() {
		for (int i = 0; i < Guard.FAILURE_LIMIT * 3; i++) {
			failOnce(Guard.SYNC_FEATURE);
		}
		assertFalse(guard.isDisabled(Guard.SYNC_FEATURE));
		assertEquals(Guard.FAILURE_LIMIT * 3, listener.failures.size());
		assertTrue(listener.disabled.isEmpty());
		AtomicInteger runs = new AtomicInteger();
		guard.runIn(Guard.SYNC_FEATURE, runs::incrementAndGet);
		assertEquals("still runs", 1, runs.get());
	}

	@Test
	public void aFatalErrorInSyncIsReportedButDoesNotTurnItOff() {
		guard.runIn(Guard.SYNC_FEATURE, () -> {
			throw new OutOfMemoryError("test");
		});
		assertEquals(1, listener.fatal.size());
		assertEquals(1, listener.failures.size());
		assertFalse(guard.isDisabled(Guard.SYNC_FEATURE));
		assertTrue(listener.disabled.isEmpty());
	}

	@Test
	public void aFreezeInSyncIsReportedButDoesNotTurnItOff() {
		guard.frozen(Guard.SYNC_FEATURE, 3000, new StackTraceElement[0]);
		assertEquals(Guard.SYNC_FEATURE + ":3000", listener.freezes.get(0));
		assertFalse(guard.isDisabled(Guard.SYNC_FEATURE));
		assertTrue(listener.disabled.isEmpty());
	}

	@Test
	public void theWebVersionWatcherIsExemptToo() {
		assertTrue(Guard.isExempt(WebVersionWatcher.GUARD_FEATURE));
		for (int i = 0; i < Guard.FAILURE_LIMIT; i++) {
			failOnce(WebVersionWatcher.GUARD_FEATURE);
		}
		guard.frozen(WebVersionWatcher.GUARD_FEATURE, 2500, new StackTraceElement[0]);
		assertFalse(guard.isDisabled(WebVersionWatcher.GUARD_FEATURE));
		assertFalse("ordinary features are not exempt", Guard.isExempt("farm-run"));
	}

	@Test
	public void exemptFeaturesKeepTheirOwnBreakerWhenTheMapIsFull() {
		for (int i = 0; i < Guard.MAX_FEATURES + 5; i++) {
			guard.runIn("f" + i, () -> { });
		}
		for (int i = 0; i < Guard.FAILURE_LIMIT; i++) {
			failOnce(Guard.SYNC_FEATURE);
		}
		assertFalse(guard.isDisabled(Guard.SYNC_FEATURE));
		assertFalse("sync's failures do not land on the shared breaker", guard.isDisabled(Guard.OTHER_FEATURE));
	}

	// ------------------------------------------------------------------ client thread helpers

	@Test
	public void wrapBoolPassesTheAnswerThrough() {
		Guard.setShared(guard);
		try {
			assertFalse(Guard.wrapBool("poll", () -> false).getAsBoolean());
			assertTrue(Guard.wrapBool("poll", () -> true).getAsBoolean());
		} finally {
			Guard.setShared(new Guard(System::currentTimeMillis, System::nanoTime));
		}
	}

	@Test
	public void wrapBoolIsDoneWhenTheBodyThrowsOrTheFeatureIsOff() {
		Guard.setShared(guard);
		try {
			BooleanSupplier broken = Guard.wrapBool("poll", () -> {
				throw new IllegalStateException("boom");
			});
			assertTrue("a broken poll stops instead of retrying forever", broken.getAsBoolean());
			assertEquals(1, listener.failures.size());

			for (int i = 0; i < Guard.FAILURE_LIMIT; i++) {
				failOnce("poll");
			}
			AtomicInteger runs = new AtomicInteger();
			BooleanSupplier off = Guard.wrapBool("poll", () -> {
				runs.incrementAndGet();
				return false;
			});
			assertTrue(off.getAsBoolean());
			assertEquals(0, runs.get());
		} finally {
			Guard.setShared(new Guard(System::currentTimeMillis, System::nanoTime));
		}
	}

	@Test
	public void cleanUpsRunEvenWhenTheFeatureIsOff() {
		Guard.setShared(guard);
		try {
			for (int i = 0; i < Guard.FAILURE_LIMIT; i++) {
				failOnce("bank-layout");
			}
			assertTrue(guard.isDisabled("bank-layout"));
			AtomicInteger runs = new AtomicInteger();
			Guard.wrapCleanup("bank-layout", runs::incrementAndGet).run();
			assertEquals(1, runs.get());
			// And a failing clean-up is still caught and reported.
			Guard.wrapCleanup("bank-layout", () -> {
				throw new IllegalStateException("boom");
			}).run();
			assertEquals(Guard.FAILURE_LIMIT + 1, listener.failures.size());
		} finally {
			Guard.setShared(new Guard(System::currentTimeMillis, System::nanoTime));
		}
	}

	/** Records what reaches the client thread's queue instead of running it on a game tick. */
	static final class RecordingClientThread extends ClientThread {
		final List<Runnable> runnables = new ArrayList<>();
		final List<BooleanSupplier> polls = new ArrayList<>();

		@Override
		public void invoke(Runnable r) {
			runnables.add(r);
		}

		@Override
		public void invoke(BooleanSupplier r) {
			polls.add(r);
		}

		@Override
		public void invokeLater(Runnable r) {
			runnables.add(r);
		}

		@Override
		public void invokeLater(BooleanSupplier r) {
			polls.add(r);
		}
	}

	private boolean stillWaiting() {
		return false;
	}

	private void boom() {
		throw new IllegalStateException("boom");
	}

	@Test
	public void theClientThreadHelpersKeepTheOverloadAndGuardTheTask() {
		Guard.setShared(guard);
		try {
			RecordingClientThread ct = new RecordingClientThread();
			// A method returning boolean stays a poll (false: again next tick), as with ClientThread itself.
			Guard.invokeLater(ct, "poll", this::stillWaiting);
			// A void body stays a plain task. (A lambda that only throws fits both, and Java picks the
			// poll, here as with ClientThread itself.)
			Guard.invokeLater(ct, "task", this::boom);
			Guard.invoke(ct, "poll", () -> {
				throw new IllegalStateException("boom");
			});
			assertEquals(2, ct.polls.size());
			assertEquals(1, ct.runnables.size());
			assertFalse(ct.polls.get(0).getAsBoolean());
			ct.runnables.get(0).run();
			assertTrue(ct.polls.get(1).getAsBoolean());
			assertEquals(2, listener.failures.size());
		} finally {
			Guard.setShared(new Guard(System::currentTimeMillis, System::nanoTime));
		}
	}

	// ------------------------------------------------------------------ nesting and the watchdog

	@Test
	public void nestedClientThreadTasksKeepTheOutermostSection() {
		Guard.setShared(guard);
		try {
			guard.watchThread(Thread.currentThread());
			nanos.set(100);
			guard.runIn("outer", () -> {
				nanos.set(900);
				// invoke() runs at once when already on the client thread: a nested section.
				assertTrue(Guard.wrapBool("inner", () -> {
					assertEquals("outer", guard.currentSection().feature);
					assertEquals(100, guard.currentSection().startNanos);
					throw new IllegalStateException("inner failed");
				}).getAsBoolean());
				Guard.wrap("inner", () -> assertEquals("outer", guard.currentSection().feature)).run();
				assertEquals("outer", guard.currentSection().feature);
			});
			assertNull(guard.currentSection());
			// A task straight off the queue is its own outermost section.
			Guard.wrapBool("poll", () -> {
				assertEquals("poll", guard.currentSection().feature);
				return true;
			}).getAsBoolean();
			assertNull(guard.currentSection());
		} finally {
			Guard.setShared(new Guard(System::currentTimeMillis, System::nanoTime));
		}
	}

	@Test
	public void clearingTheWatchedThreadMidSectionDoesNotStickTheDepth() {
		guard.watchThread(Thread.currentThread());
		guard.runIn("outer", () -> guard.watchThread(null));
		guard.watchThread(Thread.currentThread());
		guard.runIn("next", () -> assertEquals("next", guard.currentSection().feature));
		assertNull(guard.currentSection());
	}

	// ------------------------------------------------------------------ test failures

	@Test
	public void theTestTaskTurnsTheRethrowOn() {
		assertTrue("build.gradle's test task sets " + Guard.RETHROW_TEST_FAILURES_PROPERTY,
			Guard.rethrowsTestFailures());
	}

	@Test
	public void aFailedAssertionInsideGuardedCodeFailsTheTest() {
		try {
			guard.runIn("x", () -> assertEquals(1, 2));
			fail("the AssertionError must reach JUnit");
		} catch (AssertionError expected) {
			assertTrue(listener.failures.isEmpty());
			assertFalse(guard.isDisabled("x"));
		}
	}

	@Test
	public void inProductionAnAssertionErrorIsCaughtLikeAnyOther() {
		boolean before = Guard.rethrowsTestFailures();
		Guard.setRethrowTestFailures(false);
		try {
			guard.runIn("x", () -> {
				throw new AssertionError("an assert in the plugin");
			});
			assertEquals("x:AssertionError", listener.failures.get(0));
		} finally {
			Guard.setRethrowTestFailures(before);
		}
	}
}
