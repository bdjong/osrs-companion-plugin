package com.osrscompanion;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebVersionWatcherTest {
	private static final long NOW = 1_000_000L;

	@Test
	public void checksOnlyWhenLoggedInWithAKeyAndNoRecentBatch() {
		assertTrue(WebVersionWatcher.shouldCheck(true, true, "Zezima", NOW, Long.MIN_VALUE));
		assertTrue(WebVersionWatcher.shouldCheck(true, true, "Zezima", NOW, NOW - WebVersionWatcher.RECENT_BATCH_MS));
		assertFalse(WebVersionWatcher.shouldCheck(true, true, "Zezima", NOW, NOW - 5_000));
		assertFalse(WebVersionWatcher.shouldCheck(false, true, "Zezima", NOW, Long.MIN_VALUE));
		assertFalse(WebVersionWatcher.shouldCheck(true, false, "Zezima", NOW, Long.MIN_VALUE));
		assertFalse(WebVersionWatcher.shouldCheck(true, true, null, NOW, Long.MIN_VALUE));
	}

	@Test
	public void heartbeatWhenNothingReachedTheWebsiteFor15Seconds() {
		long beat = LiveActivityMonitor.HEARTBEAT_MS;
		assertTrue(WebVersionWatcher.shouldHeartbeat(NOW, Long.MIN_VALUE, Long.MIN_VALUE));
		assertFalse(WebVersionWatcher.shouldHeartbeat(NOW, NOW - beat + 1, Long.MIN_VALUE));
		assertTrue(WebVersionWatcher.shouldHeartbeat(NOW, NOW - beat, Long.MIN_VALUE));
		// A heartbeat that got no answer (server down) still waits 15 s before the next one.
		assertFalse(WebVersionWatcher.shouldHeartbeat(NOW, NOW - 60_000, NOW - 5_000));
		assertTrue(WebVersionWatcher.shouldHeartbeat(NOW, NOW - 60_000, NOW - beat));
	}
}
