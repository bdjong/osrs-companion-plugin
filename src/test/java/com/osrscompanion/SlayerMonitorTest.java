package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for SlayerMonitor's outcome claim table and config toggle.
 *
 * <p>The claim mirrors the backend decision table: streak advance (either
 * counter) or completion chat = completed; block-list membership = blocked;
 * a points drop with neither = skipped. Point AMOUNTS never classify, because
 * Mortimer's skip costs 100, the classic block price.
 */
public class SlayerMonitorTest {
	private TestOSRSCompanionConfig config;

	@Before
	public void setUp() {
		config = new TestOSRSCompanionConfig();
	}

	@Test
	public void testConfigSyncSlayerDefaultIsTrue() {
		assertTrue(config.syncSlayer());
	}

	@Test
	public void testConfigSyncSlayerCanBeDisabled() {
		config.setSyncSlayer(false);
		assertFalse(config.syncSlayer());
	}














	@Test
	public void testHasStoredTaskRegularTask() {
		assertTrue(SlayerMonitor.hasStoredTask(51, 77));
		assertFalse(SlayerMonitor.hasStoredTask(0, 77));
		assertFalse(SlayerMonitor.hasStoredTask(51, 0));
		assertFalse(SlayerMonitor.hasStoredTask(0, 0));
		// Negative amounts only render for the boss sentinel in game.
		assertFalse(SlayerMonitor.hasStoredTask(51, -1));
	}

	@Test
	public void testHasStoredTaskBossSentinel() {
		assertTrue(SlayerMonitor.hasStoredTask(98, 5));
		// The rewards script draws a name-only slot for a boss with amount -1.
		assertTrue(SlayerMonitor.hasStoredTask(98, -1));
		assertFalse(SlayerMonitor.hasStoredTask(98, 0));
	}

	@Test
	public void testStoredTaskExtraEmptyUntilCaptured() {
		assertTrue(SlayerMonitor.storedTaskExtra(null).isEmpty());
	}

	@Test
	public void testStoredTaskExtraCarriesCapture() {
		SlayerMonitor.StoredTaskCapture capture = new SlayerMonitor.StoredTaskCapture();
		capture.taskId = 98;
		capture.amount = 35;
		capture.bossTaskId = 3;
		capture.taskName = "Kalphite Queen";
		capture.capturedAt = "2026-07-28T00:00:00Z";
		java.util.Map<String, Object> extra = SlayerMonitor.storedTaskExtra(capture);
		assertEquals(98, extra.get("stored_task_id"));
		assertEquals(35, extra.get("stored_task_amount"));
		assertEquals(3, extra.get("stored_boss_task_id"));
		assertEquals("Kalphite Queen", extra.get("stored_task_name"));
		assertEquals("2026-07-28T00:00:00Z", extra.get("stored_task_captured_at"));
	}

	@Test
	public void testStoredTaskExtraOmitsUnresolvedName() {
		// A capture that confirmed the storage is EMPTY still syncs zeros, so
		// the backend can distinguish "empty" from "never captured".
		SlayerMonitor.StoredTaskCapture capture = new SlayerMonitor.StoredTaskCapture();
		capture.capturedAt = "2026-07-28T00:00:00Z";
		java.util.Map<String, Object> extra = SlayerMonitor.storedTaskExtra(capture);
		assertEquals(0, extra.get("stored_task_id"));
		assertEquals(0, extra.get("stored_task_amount"));
		assertEquals(0, extra.get("stored_boss_task_id"));
		assertFalse(extra.containsKey("stored_task_name"));
	}

	@Test
	public void testParseTaskOffersAgainstCapturedDialogRows() {
		// Verbatim rows captured from the live Slayer Task Choice dialog
		// (interface 236.3), including its underline name tags.
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(java.util.Arrays.asList(
						"Slayer Task Choice",
						"<u=ff981f>Turoth", "Amount: 80 to 120", "+15 Slayer points",
						"<u=ff981f>Gryphons", "Amount: 80 to 120", "+20 Slayer points",
						"<col=b2b2b2>Complete 50 tasks with Mortimer to unlock a third choice."));
		assertEquals(2, offers.size());
		assertEquals("Turoth", offers.get(0).get("name"));
		assertEquals(80, offers.get(0).get("amount_min"));
		assertEquals(120, offers.get(0).get("amount_max"));
		assertEquals("+15 Slayer points", offers.get(0).get("modifier_text"));
		assertEquals(15, offers.get(0).get("modifier_value"));
		assertEquals("Slayer points", offers.get(0).get("modifier_label"));
		assertEquals("Gryphons", offers.get(1).get("name"));
		assertEquals(20, offers.get(1).get("modifier_value"));
	}

	@Test
	public void testParseTaskOffersHandlesEveryObservedModifierForm() {
		// value, isPercent, label, and the row as displayed in game.
		Object[][] cases = {
				{30, true, "Slayer XP", "+30% Slayer XP"},
				{300, true, "Superior Chance", "+300% Superior Chance"},
				{100, false, "Assigned", "+100 Assigned"},
				{2, false, "clue scrolls", "x2 clue scrolls"},
		};
		for (Object[] c : cases) {
			java.util.List<java.util.Map<String, Object>> offers =
					SlayerMonitor.parseTaskOffers(java.util.Arrays.asList(
							"<u=ff981f>Turoth", "Amount: 80 to 120", (String) c[3]));
			assertEquals(1, offers.size());
			assertEquals(c[3], offers.get(0).get("modifier_text"));
			assertEquals(c[0], offers.get(0).get("modifier_value"));
			assertEquals(c[1], offers.get(0).get("modifier_is_percent"));
			assertEquals(c[2], offers.get(0).get("modifier_label"));
		}
	}

	@Test
	public void testUnparseableModifierWordingIsStillSent() {
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(java.util.Arrays.asList(
						"Dust devils", "Amount: 145 to 237", "Guaranteed superior spawn"));
		assertEquals(1, offers.size());
		assertEquals("Guaranteed superior spawn", offers.get(0).get("modifier_text"));
		assertFalse(offers.get(0).containsKey("modifier_value"));
	}

	@Test
	public void testParseTaskOffersSingleAmountAndNoModifier() {
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(java.util.Arrays.asList(
						"Basilisks", "Amount: 48", ""));
		assertEquals(1, offers.size());
		assertEquals(48, offers.get(0).get("amount_min"));
		assertEquals(48, offers.get(0).get("amount_max"));
		assertFalse(offers.get(0).containsKey("modifier_text"));
	}

	@Test
	public void testNegativeModifierKeepsItsSign() {
		// Mortimer's "-20 Assigned" asks for 20 FEWER kills than the amount
		// rolled; dropping the sign would have it ask for 20 more.
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(java.util.Arrays.asList(
						"<u=ff981f>Banshees", "Amount: 35 to 50", "-20 Assigned"));
		assertEquals(1, offers.size());
		assertEquals("-20 Assigned", offers.get(0).get("modifier_text"));
		assertEquals(-20, offers.get(0).get("modifier_value"));
		assertEquals("Assigned", offers.get(0).get("modifier_label"));
		assertFalse((Boolean) offers.get(0).get("modifier_multiplies"));
	}

	@Test
	public void testModifierIsFoundAcrossUntextedRows() {
		// The dialog's rows are only consecutive once its untexted widgets are
		// stepped over; indexing straight to the next row misses the modifier.
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(java.util.Arrays.asList(
						"<u=ff981f>Turoth", "Amount: 80 to 120", "", "+15 Slayer points",
						"<u=ff981f>Kurask", "Amount: 40 to 60", "", "+20 Slayer points"));
		assertEquals(2, offers.size());
		assertEquals("+15 Slayer points", offers.get(0).get("modifier_text"));
		assertEquals("Kurask", offers.get(1).get("name"));
		assertEquals("+20 Slayer points", offers.get(1).get("modifier_text"));
	}

	@Test
	public void testNextOffersNameIsNotReadAsAModifier() {
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(java.util.Arrays.asList(
						"<u=ff981f>Turoth", "Amount: 80 to 120",
						"<u=ff981f>Kurask", "Amount: 40 to 60"));
		assertEquals(2, offers.size());
		assertFalse(offers.get(0).containsKey("modifier_text"));
		assertEquals("Kurask", offers.get(1).get("name"));
	}

	@Test
	public void testReadingOrderSortsRowsDownTheDialog() {
		// Walking the static, dynamic and nested child lists in turn groups
		// the rows by list; only where they drew says which offer owns which.
		java.util.List<String> texts = SlayerMonitor.readingOrder(java.util.Arrays.asList(
				new SlayerMonitor.OfferRow("<u=ff981f>Turoth", 20, 40),
				new SlayerMonitor.OfferRow("<u=ff981f>Kurask", 20, 80),
				new SlayerMonitor.OfferRow("Amount: 80 to 120", 20, 55),
				new SlayerMonitor.OfferRow("Amount: 40 to 60", 20, 95),
				new SlayerMonitor.OfferRow("+15 Slayer points", 20, 70),
				new SlayerMonitor.OfferRow("+20 Slayer points", 20, 110)));
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(texts);
		assertEquals(2, offers.size());
		assertEquals("Turoth", offers.get(0).get("name"));
		assertEquals(15, offers.get(0).get("modifier_value"));
		assertEquals("Kurask", offers.get(1).get("name"));
		assertEquals(20, offers.get(1).get("modifier_value"));
	}

	@Test
	public void testReadingOrderKeepsWalkOrderWhenAnyRowIsUnplaced() {
		java.util.List<String> texts = SlayerMonitor.readingOrder(java.util.Arrays.asList(
				new SlayerMonitor.OfferRow("second", 20, 80),
				new SlayerMonitor.OfferRow("first", null, null)));
		assertEquals(java.util.Arrays.asList("second", "first"), texts);
	}

	@Test
	public void testParseTaskOffersRejectsNonOfferDialogs() {
		java.util.List<String> texts = java.util.Arrays.asList(
				"Select an option", "Yes", "No", "Cancel");
		org.junit.Assert.assertTrue(SlayerMonitor.parseTaskOffers(texts).isEmpty());
	}

	@Test
	public void taskSizeIsNeverBelowTheCountOfTheAssignment() {
		// The report: Mortimer's hydras, SLAYER_COUNT_ORIGINAL 181 while the count was 263 (a quantity
		// modifier on top of the roll): 222 left showed -41 killed. The size is the highest count seen.
		SlayerMonitor.TaskSize size = new SlayerMonitor.TaskSize();
		assertEquals(263, size.update("a", 181, 263));
		assertEquals(263, size.update("a", 181, 222));
		// A right varp size stays as it is.
		assertEquals(300, size.update("b", 300, 290));
		assertEquals(300, size.update("b", 300, 120));
		// Another assignment starts over; no task: the varp's.
		assertEquals(181, size.update("c", 181, 150));
		assertEquals(181, size.update("c", 181, 0));
	}

	@Test
	public void taskSizeSurvivesARestart() {
		SlayerMonitor.TaskSize size = new SlayerMonitor.TaskSize();
		size.update("42:0:0:10:181", 181, 263);
		size.update("42:0:0:10:181", 181, 240);
		SlayerMonitor.TaskSize back = new SlayerMonitor.TaskSize();
		back.restore(size.stored());
		assertEquals("42:0:0:10:181|263", back.stored());
		// Started mid-task: the stored highest count, not what is left now.
		assertEquals(263, back.update("42:0:0:10:181", 181, 222));
		// Junk in the config is ignored.
		SlayerMonitor.TaskSize junk = new SlayerMonitor.TaskSize();
		junk.restore("nonsense|x");
		assertEquals(null, junk.stored());
	}
}
