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

	// ------------------------------------------------------------------ the whole client thread

	/** Records what the stall listener heard. */
	static final class RecordingStalls implements FreezeWatchdog.StallListener {
		final java.util.List<FreezeWatchdog.Stall> stalls = new java.util.ArrayList<>();
		final java.util.List<Long> resumed = new java.util.ArrayList<>();

		@Override
		public void onStall(FreezeWatchdog.Stall stall) {
			stalls.add(stall);
		}

		@Override
		public void onResume(FreezeWatchdog.Stall stall, long stalledMs) {
			resumed.add(stalledMs);
		}
	}

	/** A stand-in client thread, parked until released (stuck "in another plugin's code"). */
	private static Thread fakeClientThread(java.util.concurrent.CountDownLatch release) {
		Thread t = new Thread(() -> {
			try {
				release.await();
			} catch (InterruptedException e) {
				// test over
			}
		}, "Client");
		t.setDaemon(true);
		t.start();
		// Wait until it is parked in await(), so a sampled stack always shows it (no race with the start).
		long until = System.currentTimeMillis() + 5_000;
		while (t.getState() != Thread.State.WAITING && System.currentTimeMillis() < until) {
			Thread.onSpinWait();
		}
		return t;
	}

	private static boolean mentions(StackTraceElement[] stack, String text) {
		for (StackTraceElement f : stack) {
			if (f.toString().contains(text)) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void aClientThreadThatStopsTickingIsAStallReportedOnceThenItsLengthWhenItResumes() throws Exception {
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		Thread client = fakeClientThread(release);
		try {
			guard.watchThread(client);
			RecordingStalls stalls = new RecordingStalls();
			watchdog.setStallListener(stalls);
			watchdog.setInGame(() -> true);

			// Ticking: never a stall, however long it runs.
			for (int i = 0; i < 20; i++) {
				watchdog.beat();
				nanos.addAndGet(500 * MS);
				assertFalse(watchdog.checkClient());
			}
			watchdog.beat();
			assertFalse(watchdog.checkClient());
			nanos.addAndGet(4_900 * MS);
			assertFalse(watchdog.checkClient());
			nanos.addAndGet(200 * MS);
			assertTrue(watchdog.checkClient());
			nanos.addAndGet(3_000 * MS);
			assertFalse("one incident, reported once", watchdog.checkClient());

			assertEquals(1, stalls.stalls.size());
			FreezeWatchdog.Stall stall = stalls.stalls.get(0);
			assertEquals(FreezeWatchdog.CLIENT_FEATURE, stall.feature);
			assertEquals(5_100, stall.elapsedMs);
			assertFalse(stall.terminated);
			// The stuck thread's own frames, whoever's code they are.
			assertTrue(mentions(stall.stack, "CountDownLatch.await"));
			// Not a feature of ours: no breaker.
			assertFalse(guard.isDisabled(FreezeWatchdog.CLIENT_FEATURE));

			watchdog.beat();
			assertFalse(watchdog.checkClient());
			assertEquals(java.util.Collections.singletonList(8_100L), stalls.resumed);
		} finally {
			release.countDown();
		}
	}

	@Test
	public void noHeartbeatOutOfGameIsNoStall() throws Exception {
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		Thread client = fakeClientThread(release);
		try {
			guard.watchThread(client);
			RecordingStalls stalls = new RecordingStalls();
			watchdog.setStallListener(stalls);
			AtomicBoolean inGame = new AtomicBoolean(false);
			watchdog.setInGame(inGame::get);
			assertFalse(watchdog.checkClient());
			nanos.addAndGet(60_000 * MS);
			assertFalse(watchdog.checkClient());
			// Logging in: the silence before does not count.
			inGame.set(true);
			nanos.addAndGet(1_000 * MS);
			assertFalse(watchdog.checkClient());
			assertTrue(stalls.stalls.isEmpty());
		} finally {
			release.countDown();
		}
	}

	@Test
	public void aClientThreadThatDiesIsReportedOnceWithTheLastStackSeen() throws Exception {
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		Thread client = fakeClientThread(release);
		guard.watchThread(client);
		RecordingStalls stalls = new RecordingStalls();
		watchdog.setStallListener(stalls);
		watchdog.setInGame(() -> true);
		watchdog.beat();
		assertFalse(watchdog.checkClient());
		// Silent for a while: its stack is sampled.
		nanos.addAndGet(1_500 * MS);
		assertFalse(watchdog.checkClient());
		// An Error escapes the client loop: the thread ends.
		release.countDown();
		client.join(5_000);
		assertFalse(client.isAlive());
		nanos.addAndGet(500 * MS);
		assertTrue(watchdog.checkClient());
		assertFalse(watchdog.checkClient());

		assertEquals(1, stalls.stalls.size());
		FreezeWatchdog.Stall dead = stalls.stalls.get(0);
		assertTrue(dead.terminated);
		assertEquals(2_000, dead.elapsedMs);
		assertTrue(mentions(dead.stack, "CountDownLatch.await"));
		assertFalse(guard.isDisabled(FreezeWatchdog.CLIENT_FEATURE));
	}

	@Test
	public void aClientThreadThatDiesWithoutAStallIsStillReported() throws Exception {
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		Thread client = fakeClientThread(release);
		guard.watchThread(client);
		RecordingStalls stalls = new RecordingStalls();
		watchdog.setStallListener(stalls);
		watchdog.setInGame(() -> false);
		assertFalse(watchdog.checkClient());
		release.countDown();
		client.join(5_000);
		assertTrue(watchdog.checkClient());
		assertTrue(stalls.stalls.get(0).terminated);
		assertEquals(0, stalls.stalls.get(0).stack.length);
	}

	@Test
	public void aStallInsideOurSectionIsNamedAfterItsFeature() {
		watchdog = new FreezeWatchdog(guard, 60_000, FreezeWatchdog.STALL_MS);
		RecordingStalls stalls = new RecordingStalls();
		watchdog.setStallListener(stalls);
		watchdog.setInGame(() -> true);
		assertFalse(watchdog.checkClient());
		guard.runIn("dailies-markers", () -> {
			nanos.addAndGet(6_000 * MS);
			assertTrue(watchdog.checkClient());
		});
		assertEquals("dailies-markers", stalls.stalls.get(0).feature);
		assertTrue(mentions(stalls.stalls.get(0).stack, FreezeWatchdogTest.class.getName()));
	}

	@Test
	public void aStallOurSectionCheckAlreadyReportedIsNotReportedTwice() {
		RecordingStalls stalls = new RecordingStalls();
		watchdog.setStallListener(stalls);
		watchdog.setInGame(() -> true);
		assertFalse(watchdog.checkClient());
		guard.runIn("slow-feature", () -> {
			nanos.addAndGet(6_000 * MS);
			assertTrue(watchdog.check());
			assertFalse(watchdog.checkClient());
		});
		watchdog.beat();
		assertFalse(watchdog.checkClient());
		assertEquals(1, listener.freezes.size());
		assertTrue(stalls.stalls.isEmpty());
		assertTrue(stalls.resumed.isEmpty());
	}
}
