package com.osrscompanion;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** What makes a live activity status go out at once (the key) and what waits for the next upload. */
public class LiveActivityStatusKeyTest {
	private static LiveActivityMonitor.Status status(String area, int world, int left) {
		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("left", left);
		LiveActivity.Choice choice = new LiveActivity.Choice(LiveActivity.Kind.SLAYER, "Abyssal demons", null, parts);
		return new LiveActivityMonitor.Status("Player", "online", choice, 1_000L, world, area, 12345);
	}

	@Test
	public void areaAndWorldChangesGoOutAtOnce() {
		assertNotEquals(status("Catacombs of Kourend", 302, 40).key(), status("Slayer Tower", 302, 40).key());
		assertNotEquals(status("Slayer Tower", 302, 40).key(), status("Slayer Tower", 330, 40).key());
	}

	@Test
	public void countChangesWaitForTheNextUpload() {
		assertEquals(status("Slayer Tower", 302, 40).key(), status("Slayer Tower", 302, 39).key());
	}

	@Test
	public void theHeartbeatIsFiveMinutes() {
		assertEquals(5 * 60_000L, LiveActivityMonitor.HEARTBEAT_MS);
	}
}
