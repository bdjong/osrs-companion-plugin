package com.osrscompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The next start: the persisted incident is read, reported once logged in, and cleared once sent. */
public class PreviousIncidentTest {
	private final IncidentStoreTest.MemoryStorage storage = new IncidentStoreTest.MemoryStorage();
	private final List<Map<String, Object>> sent = new ArrayList<>();
	private final List<Runnable> acks = new ArrayList<>();
	private boolean reportsOn = true;

	private ErrorReporter reporter() {
		return new ErrorReporter(new ErrorReporter.Sender() {
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
		}, () -> 1_700_000_000_000L, () -> reportsOn, () -> "Zezima", "v", "rl");
	}

	/** The session that froze and was killed: it saved the incident and never stopped cleanly. */
	private IncidentStore.Incident frozeLastSession() {
		IncidentStore.Incident i = new IncidentStore.Incident("freeze", "client", "The client thread froze for 5100 ms",
			"Client thread (RUNNABLE)\n\tat net.runelite.client.plugins.menuentryswapper.MenuEntrySwapperPlugin.swapMenuEntry(MenuEntrySwapperPlugin.java:1556)",
			1_600_000_000_000L);
		new IncidentStore(storage).save(i);
		return i;
	}

	@Test
	public void readOnStartReportedOnceLoggedInAndClearedOnlyOnceSent() {
		frozeLastSession();
		// startUp
		IncidentStore store = new IncidentStore(storage);
		PreviousIncident previous = new PreviousIncident(store);
		assertTrue(previous.isWaiting());
		assertNotNull("read, not taken, at start", storage.value);

		// LOGGED_IN
		ErrorReporter reporter = reporter();
		previous.report(reporter);
		assertFalse(previous.isWaiting());
		// LOGGED_IN again (a region load): not reported twice.
		previous.report(reporter);
		reporter.flush(true);
		assertEquals(1, sent.size());
		Map<String, Object> r = sent.get(0);
		assertEquals(ErrorReporter.LEVEL_FATAL, r.get("level"));
		assertEquals("client", r.get("feature"));
		assertTrue(String.valueOf(r.get("stack")).contains("MenuEntrySwapperPlugin"));
		assertEquals(java.time.Instant.ofEpochMilli(1_600_000_000_000L).toString(), r.get("occurred_at"));
		assertTrue("not sent yet: kept", previous.isStillStored());

		acks.forEach(Runnable::run);
		assertNull(storage.value);
		assertFalse(previous.isStillStored());
	}

	@Test
	public void aClientThatDiesBeforeSendingReportsItOnTheStartAfter() {
		IncidentStore.Incident i = frozeLastSession();
		PreviousIncident first = new PreviousIncident(new IncidentStore(storage));
		first.report(reporter());
		// Killed again before the flush: nothing was sent, nothing cleared.
		PreviousIncident second = new PreviousIncident(new IncidentStore(storage));
		assertTrue(second.isWaiting());
		assertEquals(IncidentStore.encode(i), storage.value);
	}

	@Test
	public void notLoggedInThisSessionKeepsItForTheNextStart() {
		frozeLastSession();
		PreviousIncident previous = new PreviousIncident(new IncidentStore(storage));
		// A clean stop keeps what is still stored.
		assertTrue(previous.isStillStored());
	}

	@Test
	public void withReportsOffItIsClearedWithoutSending() {
		frozeLastSession();
		reportsOn = false;
		PreviousIncident previous = new PreviousIncident(new IncidentStore(storage));
		previous.report(reporter());
		assertNull(storage.value);
		assertTrue(sent.isEmpty());
	}

	@Test
	public void noReporterYetLeavesItWaiting() {
		frozeLastSession();
		PreviousIncident previous = new PreviousIncident(new IncidentStore(storage));
		previous.report(null);
		assertTrue(previous.isWaiting());
	}

	@Test
	public void anUnreadableIncidentIsDropped() {
		storage.value = "v0\ngarbage";
		PreviousIncident previous = new PreviousIncident(new IncidentStore(storage));
		assertFalse(previous.isWaiting());
		assertNull(storage.value);
	}

	@Test
	public void nothingStoredIsNothingToDo() {
		PreviousIncident previous = new PreviousIncident(new IncidentStore(storage));
		assertFalse(previous.isWaiting());
		assertFalse(previous.isStillStored());
		previous.report(reporter());
		assertTrue(sent.isEmpty());
	}
}
