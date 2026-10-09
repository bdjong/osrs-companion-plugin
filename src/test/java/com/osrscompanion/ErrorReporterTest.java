package com.osrscompanion;

import ch.qos.logback.classic.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ErrorReporterTest {
	private static final class Call {
		final String player;
		final List<Map<String, Object>> reports;

		Call(String player, List<Map<String, Object>> reports) {
			this.player = player;
			this.reports = reports;
		}
	}

	private final List<Call> calls = new ArrayList<>();
	private long now;
	private boolean enabled;
	private String player;
	private ErrorReporter reporter;

	@Before
	public void setUp() {
		now = 1_700_000_000_000L;
		enabled = true;
		player = "Zezima";
		reporter = new ErrorReporter((p, r) -> calls.add(new Call(p, r)), () -> now, () -> enabled, () -> player,
			"2026.10.05", "1.13.1");
	}

	private static StackTraceElement frame(String cls, String method, int line) {
		return new StackTraceElement(cls, method, cls.substring(cls.lastIndexOf('.') + 1) + ".java", line);
	}

	private static RuntimeException boom(String message, int line) {
		RuntimeException e = new IllegalStateException(message);
		e.setStackTrace(new StackTraceElement[]{
			frame("java.util.ArrayList", "get", 427),
			frame("com.osrscompanion.SlayerHelperManager", "refresh", line),
			frame("com.osrscompanion.SlayerHelperManager", "onGameTick", 88),
			frame("net.runelite.client.eventbus.EventBus", "post", 200),
			frame("com.osrscompanion.OSRSCompanionPlugin", "startUp", 12),
			frame("net.runelite.client.plugins.PluginManager", "startPlugin", 300),
		});
		return e;
	}

	private void error(Throwable t) {
		reporter.record(ErrorReporter.LEVEL_ERROR, "com.osrscompanion.SlayerHelperManager", "Slayer helper failed", t, null);
	}

	@Test
	public void fingerprintIgnoresNumbersAndLineNumbersButNotTheException() {
		String a = ErrorReporter.fingerprint(boom("index 5 out of 10", 40));
		assertEquals(a, ErrorReporter.fingerprint(boom("index 7 out of 12", 41)));
		assertNotEquals(a, ErrorReporter.fingerprint(boom("other problem", 40)));
		assertNotEquals(a, ErrorReporter.fingerprint(new IllegalArgumentException("index 5 out of 10")));
		assertTrue(a.length() <= ErrorReporter.MAX_FINGERPRINT);
		assertEquals(16, a.length());
	}

	@Test
	public void fingerprintUsesTheFirstThreeOfOurFrames() {
		RuntimeException e1 = boom("x", 1);
		RuntimeException e2 = boom("x", 1);
		StackTraceElement[] s = e2.getStackTrace().clone();
		// A 4th frame of ours, after the first three, does not change it...
		s[5] = frame("com.osrscompanion.Other", "run", 1);
		e2.setStackTrace(s);
		assertEquals(ErrorReporter.fingerprint(e1), ErrorReporter.fingerprint(e2));
		// ...one of the first three does.
		s = s.clone();
		s[2] = frame("com.osrscompanion.SlayerHelperManager", "onStatChanged", 88);
		e2.setStackTrace(s);
		assertNotEquals(ErrorReporter.fingerprint(e1), ErrorReporter.fingerprint(e2));
	}

	@Test
	public void sameErrorIsCountedNotRepeated() {
		error(boom("index 1", 40));
		error(boom("index 2", 40));
		error(boom("index 3", 41));
		now += 5_000;
		reporter.flush();
		assertEquals(1, calls.size());
		assertEquals("Zezima", calls.get(0).player);
		assertEquals(1, calls.get(0).reports.size());
		Map<String, Object> r = calls.get(0).reports.get(0);
		assertEquals(3, r.get("count"));
		assertEquals("error", r.get("level"));
		assertEquals("slayer-helper", r.get("feature"));
		assertEquals("2026.10.05", r.get("plugin_version"));
		assertEquals("1.13.1", r.get("runelite_version"));
		assertEquals("2023-11-14T22:13:20Z", r.get("occurred_at"));
		assertEquals("Slayer helper failed: java.lang.IllegalStateException: index 1", r.get("message"));
	}

	@Test
	public void flushesAtMostOncePerMinuteAndCountsSinceTheLastSend() {
		error(boom("a", 1));
		reporter.flush();
		assertEquals(1, calls.size());
		error(boom("a", 1));
		error(boom("a", 1));
		now += 30_000;
		reporter.flush();
		assertEquals("not within a minute", 1, calls.size());
		now += 30_000;
		reporter.flush();
		assertEquals(2, calls.size());
		assertEquals(2, calls.get(1).reports.get(0).get("count"));
		now += 120_000;
		reporter.flush();
		assertEquals("nothing new, nothing sent", 2, calls.size());
	}

	@Test
	public void forcedFlushIgnoresTheInterval() {
		error(boom("a", 1));
		reporter.flush();
		error(boom("b", 1));
		reporter.flush(true);
		assertEquals(2, calls.size());
	}

	@Test
	public void atMostThirtyDistinctPerSessionAndTwentyPerCall() {
		for (int i = 0; i < 40; i++) {
			reporter.record(ErrorReporter.LEVEL_ERROR, "com.osrscompanion.BankMemoryMonitor", "failure " + (char) ('a' + i % 26) + (char) ('a' + i / 26),
				null, null);
		}
		reporter.flush();
		assertEquals(2, calls.size());
		assertEquals(20, calls.get(0).reports.size());
		assertEquals(10, calls.get(1).reports.size());
		// The cap is per session: a new error after the first 30 is not kept, an old one still counts.
		reporter.record(ErrorReporter.LEVEL_ERROR, "com.osrscompanion.BankMemoryMonitor", "brand new", null, null);
		reporter.record(ErrorReporter.LEVEL_ERROR, "com.osrscompanion.BankMemoryMonitor", "failure aa", null, null);
		now += ErrorReporter.FLUSH_INTERVAL_MS;
		reporter.flush();
		assertEquals(3, calls.size());
		assertEquals(1, calls.get(2).reports.size());
		assertEquals("failure aa", calls.get(2).reports.get(0).get("message"));
	}

	@Test
	public void truncatesToTheContractLimits() {
		StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 3000; i++) {
			longText.append("word ");
		}
		RuntimeException e = new RuntimeException(longText.toString());
		StackTraceElement[] frames = new StackTraceElement[400];
		for (int i = 0; i < frames.length; i++) {
			frames[i] = frame("com.osrscompanion.SlayerKillLog", "deepRecursiveMethodName" + i, i);
		}
		e.setStackTrace(frames);
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("detail", longText.toString());
		reporter.record(ErrorReporter.LEVEL_ERROR, "com.osrscompanion.SlayerKillLog", longText.toString(), e, context);
		reporter.flush();
		Map<String, Object> r = calls.get(0).reports.get(0);
		assertEquals(ErrorReporter.MAX_MESSAGE, ((String) r.get("message")).length());
		assertEquals(ErrorReporter.MAX_STACK, ((String) r.get("stack")).length());
		@SuppressWarnings("unchecked")
		Map<String, Object> ctx = (Map<String, Object>) r.get("context");
		assertTrue(((String) ctx.get("detail")).length() <= 500);

		Map<String, Object> huge = new LinkedHashMap<>();
		for (int i = 0; i < 20; i++) {
			huge.put("k" + i, longText.toString());
		}
		assertNull("over 4 KB even when cut: left out", ErrorReporter.boundedContext(huge));
		assertNull(ErrorReporter.boundedContext(Collections.emptyMap()));
	}

	@Test
	public void stackKeepsOurFramesAndTheFirstOtherOne() {
		RuntimeException cause = new IllegalArgumentException("bad id 12");
		cause.setStackTrace(new StackTraceElement[]{
			frame("com.google.gson.Gson", "fromJson", 1),
			frame("com.google.gson.Gson", "fromJson", 2),
			frame("com.osrscompanion.model.BossGear", "parse", 3),
		});
		RuntimeException e = boom("wrapped", 40);
		e.initCause(cause);
		String stack = ErrorReporter.filterStack(e);
		assertEquals(String.join("\n",
			"java.lang.IllegalStateException: wrapped",
			"\tat java.util.ArrayList.get(ArrayList.java:427)",
			"\tat com.osrscompanion.SlayerHelperManager.refresh(SlayerHelperManager.java:40)",
			"\tat com.osrscompanion.SlayerHelperManager.onGameTick(SlayerHelperManager.java:88)",
			"\tat com.osrscompanion.OSRSCompanionPlugin.startUp(OSRSCompanionPlugin.java:12)",
			"\t... 2 other frames",
			"Caused by: java.lang.IllegalArgumentException: bad id 12",
			"\tat com.google.gson.Gson.fromJson(Gson.java:1)",
			"\tat com.osrscompanion.model.BossGear.parse(BossGear.java:3)",
			"\t... 1 other frames"), stack);
	}

	@Test
	public void causeCyclesDoNotLoop() {
		RuntimeException a = new RuntimeException("a");
		RuntimeException b = new RuntimeException("b", a);
		a.initCause(b);
		assertTrue(ErrorReporter.filterStack(a).contains("Caused by"));
		assertFalse(ErrorReporter.touchesOurCode(new RuntimeException("x") {
			{
				setStackTrace(new StackTraceElement[]{frame("java.lang.Thread", "run", 1)});
			}
		}));
		ErrorReporter.fingerprint(a);
	}

	@Test
	public void featureFromClassName() {
		assertEquals("slayer-helper", ErrorReporter.featureFor("com.osrscompanion.SlayerHelperManager"));
		assertEquals("slayer-helper", ErrorReporter.featureFor("com.osrscompanion.SlayerGearWindow$1"));
		assertEquals("farm-run", ErrorReporter.featureFor("com.osrscompanion.FarmRunManager"));
		assertEquals("boss-gear", ErrorReporter.featureFor("com.osrscompanion.BossGearManager"));
		assertEquals("boss-gear", ErrorReporter.featureFor("com.osrscompanion.model.BossGear"));
		assertEquals("sync", ErrorReporter.featureFor("com.osrscompanion.api.OSRSCompanionApiClient"));
		assertEquals("roadmap", ErrorReporter.featureFor("com.osrscompanion.RoadmapPanel"));
		assertEquals("timers", ErrorReporter.featureFor("com.osrscompanion.runelite.farming.FarmingTracker"));
		assertEquals("collection-log", ErrorReporter.featureFor("com.osrscompanion.CollectionLogMonitor"));
		assertEquals("quest", ErrorReporter.featureFor("com.osrscompanion.QuestMonitor"));
		assertNull(ErrorReporter.featureFor("net.runelite.client.eventbus.EventBus"));
	}

	@Test
	public void eventBusErrorsAreNamedAfterOurFrame() {
		reporter.record(ErrorReporter.LEVEL_WARN, "net.runelite.client.eventbus.EventBus", "Uncaught exception in event subscriber",
			boom("x", 1), null);
		reporter.flush();
		assertEquals("slayer-helper", calls.get(0).reports.get(0).get("feature"));
	}

	@Test
	public void settingOffMeansNothingIsKeptOrSent() {
		enabled = false;
		error(boom("a", 1));
		reporter.reportUploadDropped("bank", "/api/runelite/bank/", 400);
		enabled = true;
		reporter.flush();
		assertTrue(calls.isEmpty());

		// Switched off after the error but before the send: dropped.
		error(boom("a", 1));
		enabled = false;
		reporter.flush(true);
		assertTrue(calls.isEmpty());
		assertEquals(0, reporter.pendingCount());
	}

	@Test
	public void gateThatThrowsCountsAsOff() {
		ErrorReporter r = new ErrorReporter((p, x) -> calls.add(new Call(p, x)), () -> now,
			() -> {
				throw new IllegalStateException("not on the client thread");
			}, () -> player, null, null);
		assertFalse(r.isEnabled());
		r.record(ErrorReporter.LEVEL_ERROR, "com.osrscompanion.X", "m", null, null);
		r.flush(true);
		assertTrue(calls.isEmpty());
	}

	@Test
	public void waitsForAPlayerNameAndKeepsWhoHadTheError() {
		player = null;
		error(boom("a", 1));
		reporter.flush();
		assertTrue("no player known yet", calls.isEmpty());
		player = "Lynx Titan";
		reporter.flush();
		assertEquals("Lynx Titan", calls.get(0).player);

		// An error while logged out after that belongs to the last character seen.
		player = null;
		error(boom("b", 1));
		player = "Other";
		error(boom("b", 1));
		reporter.flush(true);
		assertEquals(Arrays.asList("Lynx Titan", "Other"), Arrays.asList(calls.get(1).player, calls.get(2).player));
	}

	@Test
	public void forcedFlushWithoutAnyPlayerDropsTheReports() {
		player = null;
		error(boom("a", 1));
		reporter.flush(true);
		assertTrue(calls.isEmpty());
		assertEquals(0, reporter.pendingCount());
	}

	@Test
	public void droppedUploadsAreReportedButNever401OrTheErrorReportsThemselves() {
		reporter.reportUploadDropped("bank", "/api/runelite/bank/", 400);
		reporter.reportUploadDropped("bank", "/api/runelite/bank/", 400);
		reporter.reportUploadDropped("bank", "/api/runelite/bank/", 401);
		reporter.reportUploadDropped("errors", "/api/runelite/errors", 500);
		reporter.reportUploadDropped("bank", "/api/runelite/bank/", -1);
		reporter.reportParseFailure("errors", "/api/runelite/errors", "bad");
		reporter.reportParseFailure("slayer-guide", "/api/runelite/slayer/guide/", "Expected BEGIN_OBJECT");
		reporter.flush();
		assertEquals(1, calls.size());
		List<Map<String, Object>> reports = calls.get(0).reports;
		assertEquals(2, reports.size());
		assertEquals("sync", reports.get(0).get("feature"));
		assertEquals(2, reports.get(0).get("count"));
		assertEquals("Upload dropped: POST /api/runelite/bank/ returned 400 (bank)", reports.get(0).get("message"));
		assertNull(reports.get(0).get("stack"));
		@SuppressWarnings("unchecked")
		Map<String, Object> ctx = (Map<String, Object>) reports.get(0).get("context");
		assertEquals(400, ctx.get("status"));
		assertEquals("/api/runelite/bank/", ctx.get("endpoint"));
		assertEquals("Could not read the website's slayer-guide response: Expected BEGIN_OBJECT", reports.get(1).get("message"));
	}

	@Test
	public void senderFailureIsSwallowed() {
		ErrorReporter r = new ErrorReporter((p, x) -> {
			throw new RuntimeException("down");
		}, () -> now, () -> true, () -> "p", null, null);
		r.record(ErrorReporter.LEVEL_ERROR, "com.osrscompanion.X", "m", null, null);
		r.flush();
		assertEquals(0, r.pendingCount());
	}

	@Test
	public void appenderAcceptsOnlyOurErrorsAndOurSubscriberExceptions() {
		RuntimeException ours = boom("x", 1);
		RuntimeException theirs = new RuntimeException("y");
		theirs.setStackTrace(new StackTraceElement[]{frame("net.runelite.client.plugins.Foo", "bar", 1)});
		assertTrue(ErrorLogAppender.accepts("com.osrscompanion.BankLayouts", Level.ERROR, "m", null));
		assertTrue(ErrorLogAppender.accepts("com.osrscompanion.BankLayouts", Level.WARN, "m", theirs));
		assertFalse("warn without exception", ErrorLogAppender.accepts("com.osrscompanion.BankLayouts", Level.WARN, "m", null));
		assertFalse(ErrorLogAppender.accepts("com.osrscompanion.BankLayouts", Level.INFO, "m", ours));
		assertTrue(ErrorLogAppender.accepts(ErrorLogAppender.EVENT_BUS_LOGGER, Level.WARN, "Uncaught exception in event subscriber", ours));
		assertFalse(ErrorLogAppender.accepts(ErrorLogAppender.EVENT_BUS_LOGGER, Level.WARN, "Uncaught exception in event subscriber", theirs));
		assertFalse(ErrorLogAppender.accepts("net.runelite.client.plugins.Foo", Level.ERROR, "m", ours));
		// No loops: the reporter itself and the outbox's send failures are never reported.
		assertFalse(ErrorLogAppender.accepts(ErrorReporter.class.getName(), Level.ERROR, "m", ours));
		assertFalse(ErrorLogAppender.accepts("com.osrscompanion.api.SyncOutbox", Level.WARN,
			"Failed to send queued uploads to the OSRS Companion API", ours));
		assertTrue(ErrorLogAppender.accepts("com.osrscompanion.api.SyncOutbox", Level.WARN, "Upload callback failed", ours));
	}

	@Test
	public void appenderHandsLoggedErrorsToTheReporter() {
		ErrorLogAppender appender = new ErrorLogAppender(reporter);
		ch.qos.logback.classic.LoggerContext ctx = new ch.qos.logback.classic.LoggerContext();
		appender.setContext(ctx);
		appender.start();
		ch.qos.logback.classic.Logger logger = ctx.getLogger("com.osrscompanion.FarmRunManager");
		ctx.getLogger("com.osrscompanion").addAppender(appender);
		logger.error("Farm run step {} failed", 3, boom("x", 1));
		logger.warn("plain warning");
		reporter.flush();
		assertEquals(1, calls.get(0).reports.size());
		Map<String, Object> r = calls.get(0).reports.get(0);
		assertEquals("farm-run", r.get("feature"));
		assertEquals("Farm run step 3 failed: java.lang.IllegalStateException: x", r.get("message"));
		@SuppressWarnings("unchecked")
		Map<String, Object> c = (Map<String, Object>) r.get("context");
		assertEquals("com.osrscompanion.FarmRunManager", c.get("logger"));
	}
}
