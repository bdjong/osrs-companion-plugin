package com.osrscompanion;

import ch.qos.logback.classic.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Guard's reports: caught errors, freezes and an earlier session's fatal incident. */
public class ErrorReporterIncidentTest {
	private final List<Map<String, Object>> sent = new ArrayList<>();
	private long now = 1_700_000_000_000L;
	private ErrorReporter reporter;

	@Before
	public void setUp() {
		reporter = new ErrorReporter((p, r) -> sent.addAll(r), () -> now, () -> true, () -> "Zezima", "v", "rl");
	}

	@Test
	public void caughtErrorsAreCountedUnderTheGuardFeature() {
		RuntimeException e = new IllegalStateException("bad");
		reporter.reportCaught("farm-run-overlay", e);
		reporter.reportCaught("farm-run-overlay", e);
		reporter.flush(true);
		assertEquals(1, sent.size());
		assertEquals("error", sent.get(0).get("level"));
		assertEquals("farm-run-overlay", sent.get(0).get("feature"));
		assertEquals(2, sent.get(0).get("count"));
	}

	@Test
	public void freezeCarriesTheClientThreadStack() {
		StackTraceElement[] stack = {
			new StackTraceElement("com.osrscompanion.FarmRunSceneOverlay", "scan", "FarmRunSceneOverlay.java", 10),
			new StackTraceElement("net.runelite.client.callback.ClientThread", "invoke", "ClientThread.java", 1),
		};
		reporter.reportFreeze("farm-run-markers", 2500, stack);
		reporter.flush(true);
		Map<String, Object> r = sent.get(0);
		assertEquals(ErrorReporter.LEVEL_FREEZE, r.get("level"));
		assertTrue(String.valueOf(r.get("stack")).contains("FarmRunSceneOverlay.scan"));
		assertTrue(String.valueOf(r.get("message")).contains("2500 ms"));
	}

	@Test
	public void previousSessionIsFatalWithItsOwnTime() {
		reporter.reportPreviousSession("freeze", "slayer-helper", "froze", "Client thread\n\tat x", 1_600_000_000_000L);
		reporter.flush(true);
		Map<String, Object> r = sent.get(0);
		assertEquals(ErrorReporter.LEVEL_FATAL, r.get("level"));
		assertEquals(java.time.Instant.ofEpochMilli(1_600_000_000_000L).toString(), r.get("occurred_at"));
	}

	@Test
	public void theAppenderSkipsWhatTheGuardReportsItself() {
		Throwable t = new IllegalStateException("x");
		assertFalse(ErrorLogAppender.accepts(Guard.class.getName(), Level.WARN, "failed", t));
		assertFalse(ErrorLogAppender.accepts(FreezeWatchdog.class.getName(), Level.WARN, "froze", null));
		assertTrue(ErrorLogAppender.accepts(OSRSCompanionPlugin.class.getName(), Level.ERROR, "x", t));
	}
}
