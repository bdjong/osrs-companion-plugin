package com.osrscompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A whole-client-thread stall: persisted at once, reported, and cleared only once its end was sent. */
public class ClientStallReporterTest {
	private final List<Map<String, Object>> sent = new ArrayList<>();
	/** The website's "accepted" for each call, run when the test says so. */
	private final List<Runnable> acks = new ArrayList<>();
	private final IncidentStoreTest.MemoryStorage storage = new IncidentStoreTest.MemoryStorage();
	private IncidentStore store;
	private ErrorReporter reporter;
	private ClientStallReporter stalls;

	@Before
	public void setUp() {
		store = new IncidentStore(storage);
		reporter = new ErrorReporter(new ErrorReporter.Sender() {
			@Override
			public void send(String player, List<Map<String, Object>> reports) {
				sent.addAll(reports);
			}

			@Override
			public void send(String player, List<Map<String, Object>> reports, Runnable onSent) {
				sent.addAll(reports);
				if (onSent != null) {
					acks.add(onSent);
				}
			}
		}, () -> 1_700_000_000_000L, () -> true, () -> "Zezima", "v", "rl");
		stalls = new ClientStallReporter(() -> store, () -> reporter, () -> 1_700_000_000_000L);
	}

	/** The menu entry swapper assertion from the report, under 50 more client frames. */
	private static StackTraceElement[] otherPluginStack() {
		StackTraceElement[] stack = new StackTraceElement[52];
		stack[0] = new StackTraceElement("net.runelite.client.plugins.menuentryswapper.MenuEntrySwapperPlugin",
			"swapMenuEntry", "MenuEntrySwapperPlugin.java", 1556);
		stack[1] = new StackTraceElement("net.runelite.client.eventbus.EventBus", "post", "EventBus.java", 223);
		for (int i = 2; i < stack.length; i++) {
			stack[i] = new StackTraceElement("client", "frame" + i, null, -1);
		}
		return stack;
	}

	@Test
	public void aStallIsPersistedAtOnceWithOtherPluginsFrames() {
		stalls.onStall(new FreezeWatchdog.Stall("client", 5_100, otherPluginStack(), false, "RUNNABLE"));

		IncidentStore.Incident saved = IncidentStore.decode(store.peekRaw());
		assertNotNull(saved);
		assertEquals("freeze", saved.level);
		assertEquals("client", saved.feature);
		assertTrue(saved.stack.contains("MenuEntrySwapperPlugin.swapMenuEntry"));
		assertTrue(saved.stack.contains("frame39"));
		assertFalse("top 40 frames only", saved.stack.contains("frame40"));

		reporter.flush(true);
		Map<String, Object> r = sent.get(0);
		assertEquals(ErrorReporter.LEVEL_FREEZE, r.get("level"));
		assertEquals("client", r.get("feature"));
		assertTrue(String.valueOf(r.get("stack")).contains("MenuEntrySwapperPlugin.swapMenuEntry"));
		assertTrue(String.valueOf(r.get("message")).contains("5100 ms"));
	}

	@Test
	public void aResumedStallCarriesItsLengthAndIsClearedOnlyOnceSent() {
		FreezeWatchdog.Stall stall = new FreezeWatchdog.Stall("client", 5_100, otherPluginStack(), false, "RUNNABLE");
		stalls.onStall(stall);
		stalls.onResume(stall, 7_250);
		assertNotNull("kept until the report is sent", store.peekRaw());
		assertTrue(stalls.holds(store.peekRaw()));

		reporter.flush(true);
		assertEquals(1, sent.size());
		assertEquals(7_250L, ((Map<?, ?>) sent.get(0).get("context")).get("stalled_ms"));
		assertNotNull("not sent yet", store.peekRaw());

		acks.forEach(Runnable::run);
		assertNull(store.peekRaw());
		assertFalse(stalls.holds(store.peekRaw()));
	}

	@Test
	public void aStallSentBeforeItResumedGetsAFollowUp() {
		FreezeWatchdog.Stall stall = new FreezeWatchdog.Stall("client", 5_100, otherPluginStack(), false, "BLOCKED");
		stalls.onStall(stall);
		reporter.flush(true);
		acks.forEach(Runnable::run);
		acks.clear();
		assertNotNull("still stuck: kept for the next start", store.peekRaw());

		stalls.onResume(stall, 30_000);
		reporter.flush(true);
		assertEquals(2, sent.size());
		assertEquals(30_000L, ((Map<?, ?>) sent.get(1).get("context")).get("stalled_ms"));
		acks.forEach(Runnable::run);
		assertNull(store.peekRaw());
	}

	@Test
	public void aNewerIncidentIsNotClearedByAnOldStallsReport() {
		FreezeWatchdog.Stall stall = new FreezeWatchdog.Stall("client", 5_100, otherPluginStack(), false, "RUNNABLE");
		stalls.onStall(stall);
		stalls.onResume(stall, 6_000);
		IncidentStore.Incident newer = new IncidentStore.Incident("fatal", "farm-run", "OutOfMemoryError", null, 1L);
		store.save(newer);
		reporter.flush(true);
		acks.forEach(Runnable::run);
		assertEquals(IncidentStore.encode(newer), store.peekRaw());
	}

	@Test
	public void withReportsOffAResumedStallIsClearedAtOnce() {
		reporter = new ErrorReporter((p, r) -> sent.addAll(r), () -> 0L, () -> false, () -> "Zezima", "v", "rl");
		FreezeWatchdog.Stall stall = new FreezeWatchdog.Stall("client", 5_100, otherPluginStack(), false, "RUNNABLE");
		stalls.onStall(stall);
		assertNotNull(store.peekRaw());
		stalls.onResume(stall, 6_000);
		assertNull(store.peekRaw());
		assertTrue(sent.isEmpty());
	}

	@Test
	public void aTerminatedClientThreadSaysSo() {
		stalls.onStall(new FreezeWatchdog.Stall("client", 2_000, new StackTraceElement[0], true, "TERMINATED"));
		IncidentStore.Incident saved = IncidentStore.decode(store.peekRaw());
		assertTrue(saved.message.contains("terminated"));
		assertTrue(saved.stack.startsWith("Client thread terminated"));
		reporter.flush(true);
		assertEquals(Boolean.TRUE, ((Map<?, ?>) sent.get(0).get("context")).get("terminated"));
	}
}
