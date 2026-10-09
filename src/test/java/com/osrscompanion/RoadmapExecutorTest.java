package com.osrscompanion;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/** The roadmap's background work runs on its own daemon thread, never RuneLite's shared executor. */
public class RoadmapExecutorTest {
	@Test
	public void runsOnItsOwnDaemonThreadThatEndsWhenIdle() throws Exception {
		ScheduledThreadPoolExecutor ex = RoadmapManager.newOwnExecutor();
		try {
			AtomicReference<Thread> ran = new AtomicReference<>();
			CountDownLatch done = new CountDownLatch(1);
			ex.schedule(() -> {
				ran.set(Thread.currentThread());
				done.countDown();
			}, 10, TimeUnit.MILLISECONDS);
			assertTrue(done.await(5, TimeUnit.SECONDS));
			assertEquals(RoadmapManager.THREAD_NAME, ran.get().getName());
			assertTrue(ran.get().isDaemon());
			assertNotSame(Thread.currentThread(), ran.get());
			assertTrue("the thread is not kept forever", ex.allowsCoreThreadTimeOut());
			assertEquals(RoadmapManager.IDLE_SECONDS, ex.getKeepAliveTime(TimeUnit.SECONDS));
		} finally {
			ex.shutdown();
		}
	}
}
