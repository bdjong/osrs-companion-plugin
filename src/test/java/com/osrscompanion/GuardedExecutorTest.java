package com.osrscompanion;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GuardedExecutorTest {
	private Guard previous;
	private Guard guard;
	private FakeScheduledExecutorService fake;

	@Before
	public void setUp() {
		previous = Guard.shared();
		guard = new Guard(System::currentTimeMillis);
		Guard.setShared(guard);
		fake = new FakeScheduledExecutorService();
	}

	@After
	public void tearDown() {
		GuardedExecutor.cancelAllPeriodic();
		Guard.setShared(previous);
	}

	@Test
	public void aThrowingPeriodicJobKeepsRunning() {
		ScheduledExecutorService ex = GuardedExecutor.wrap("timers-sync", fake);
		AtomicInteger runs = new AtomicInteger();
		ex.scheduleWithFixedDelay(() -> {
			runs.incrementAndGet();
			throw new IllegalStateException("bad");
		}, 1, 1, TimeUnit.SECONDS);
		fake.runDue(1_000);
		fake.runDue(2_000);
		assertEquals(2, runs.get());
	}

	@Test
	public void aTurnedOffFeaturesJobsDoNothing() {
		ScheduledExecutorService ex = GuardedExecutor.wrap("loot-sync", fake);
		for (int i = 0; i < Guard.FAILURE_LIMIT; i++) {
			guard.runIn("loot-sync", () -> {
				throw new IllegalStateException("boom");
			});
		}
		AtomicInteger runs = new AtomicInteger();
		ex.execute(runs::incrementAndGet);
		assertEquals(0, runs.get());
	}

	@Test
	public void shutdownCancelsOnlyOurPeriodicJobs() {
		ScheduledExecutorService ex = GuardedExecutor.wrap("a", fake);
		ScheduledFuture<?> f = ex.scheduleAtFixedRate(() -> { }, 1, 1, TimeUnit.SECONDS);
		ex.shutdown();
		assertTrue(f.isCancelled());
		assertFalse("RuneLite's executor stays up", fake.isShutdown());
	}

	@Test
	public void cancelAllPeriodicStopsEveryFeaturesJobs() {
		ScheduledFuture<?> a = GuardedExecutor.wrap("a", fake).scheduleWithFixedDelay(() -> { }, 1, 1, TimeUnit.SECONDS);
		ScheduledFuture<?> b = GuardedExecutor.wrap("b", fake).scheduleAtFixedRate(() -> { }, 1, 1, TimeUnit.SECONDS);
		ScheduledFuture<?> once = GuardedExecutor.wrap("b", fake).schedule(() -> { }, 1, TimeUnit.SECONDS);
		GuardedExecutor.cancelAllPeriodic();
		assertTrue(a.isCancelled());
		assertTrue(b.isCancelled());
		assertFalse("one-off tasks still run", once.isCancelled());
	}

	@Test
	public void wrappingTwiceKeepsOneLayer() {
		ScheduledExecutorService ex = GuardedExecutor.wrap("a", fake);
		assertSame(ex, GuardedExecutor.wrap("a", ex));
	}
}
