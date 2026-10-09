package com.osrscompanion;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FreezeWatchdogTest {
	private static final long MS = 1_000_000L;
	private final AtomicLong nanos = new AtomicLong(0);
	private Guard guard;
	private GuardTest.RecordingListener listener;
	private FreezeWatchdog watchdog;

	@Before
	public void setUp() {
		guard = new Guard(() -> 0L, nanos::get);
		listener = new GuardTest.RecordingListener();
		guard.setListener(listener);
		guard.watchThread(Thread.currentThread());
		watchdog = new FreezeWatchdog(guard);
	}

	@Test
	public void nothingRunningIsNoFreeze() {
		assertFalse(watchdog.check());
		assertTrue(listener.freezes.isEmpty());
	}

	@Test
	public void aShortSectionIsNoFreeze() {
		guard.runIn("quick", () -> {
			nanos.addAndGet(1_999 * MS);
			assertFalse(watchdog.check());
		});
		assertTrue(listener.freezes.isEmpty());
	}

	@Test
	public void aLongSectionIsReportedOnceAndItsFeatureTurnedOff() {
		guard.runIn("slow-feature", () -> {
			nanos.addAndGet(2_500 * MS);
			assertTrue(watchdog.check());
			// The same incident, checked again: not reported twice.
			nanos.addAndGet(500 * MS);
			assertFalse(watchdog.check());
		});
		assertEquals(1, listener.freezes.size());
		assertEquals("slow-feature:2500", listener.freezes.get(0));
		assertTrue(guard.isDisabled("slow-feature"));
		assertEquals(1, listener.disabled.size());

		// Skipped next time: no section, no freeze.
		AtomicBoolean ran = new AtomicBoolean();
		guard.runIn("slow-feature", () -> ran.set(true));
		assertFalse(ran.get());
	}

	@Test
	public void aFreezeInAnotherFeatureIsANewIncident() {
		guard.runIn("a", () -> {
			nanos.addAndGet(3_000 * MS);
			watchdog.check();
		});
		guard.runIn("b", () -> {
			nanos.addAndGet(3_000 * MS);
			assertTrue(watchdog.check());
		});
		assertEquals(2, listener.freezes.size());
	}

	@Test
	public void capturesTheWatchedThreadsStack() {
		AtomicReference<StackTraceElement[]> seen = new AtomicReference<>();
		guard.setListener(new GuardTest.RecordingListener() {
			@Override
			public void onFreeze(String feature, long elapsedMs, StackTraceElement[] stack) {
				seen.set(stack);
			}
		});
		guard.runIn("x", () -> {
			nanos.addAndGet(5_000 * MS);
			watchdog.check();
		});
		boolean hasThisTest = false;
		for (StackTraceElement f : seen.get()) {
			hasThisTest |= f.getClassName().equals(FreezeWatchdogTest.class.getName());
		}
		assertTrue(hasThisTest);
	}

	@Test
	public void startsAndStopsItsDaemonThread() throws Exception {
		watchdog.start();
		assertTrue(watchdog.isRunning());
		AtomicReference<Thread> chore = new AtomicReference<>();
		java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
		watchdog.execute(() -> {
			chore.set(Thread.currentThread());
			done.countDown();
		});
		assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
		assertEquals(FreezeWatchdog.THREAD_NAME, chore.get().getName());
		assertTrue(chore.get().isDaemon());
		watchdog.stop();
		assertFalse(watchdog.isRunning());
		// Stopped: chores run on the caller.
		watchdog.execute(() -> chore.set(Thread.currentThread()));
		assertEquals(Thread.currentThread(), chore.get());
	}
}
