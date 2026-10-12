package com.osrscompanion.api;

import com.google.gson.Gson;
import com.osrscompanion.FakeScheduledExecutorService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SyncOutboxTest {
	/** Records what was sent; answers only when the test says so. */
	private static final class FakeSender implements SyncOutbox.Sender {
		final List<List<SyncOutbox.Item>> batches = new ArrayList<>();
		final List<SyncOutbox.BatchCallback> batchCallbacks = new ArrayList<>();
		final List<SyncOutbox.Item> singles = new ArrayList<>();
		final List<SyncOutbox.SingleCallback> singleCallbacks = new ArrayList<>();

		@Override
		public void sendBatch(String baseUrl, String appKey, List<SyncOutbox.Item> items, SyncOutbox.BatchCallback callback) {
			batches.add(new ArrayList<>(items));
			batchCallbacks.add(callback);
		}

		@Override
		public void sendOne(SyncOutbox.Item item, SyncOutbox.SingleCallback callback) {
			singles.add(item);
			singleCallbacks.add(callback);
		}

		SyncOutbox.BatchCallback lastBatch() {
			return batchCallbacks.get(batchCallbacks.size() - 1);
		}

		/** Answers the last batch with 200 for every request. */
		void okLast(Integer webVersion) {
			List<SyncOutbox.Item> items = batches.get(batches.size() - 1);
			lastBatch().onResponse(200, Collections.nCopies(items.size(), 200), webVersion);
		}
	}

	private FakeScheduledExecutorService executor;
	private FakeSender sender;
	private SyncOutbox outbox;
	private List<String> succeeded;

	@Before
	public void setUp() {
		executor = new FakeScheduledExecutorService();
		sender = new FakeSender();
		outbox = new SyncOutbox(sender, executor, () -> executor.nowMs);
		succeeded = new ArrayList<>();
	}

	private SyncOutbox.Item item(String path, String body, String key, boolean priority) {
		String tag = path + body;
		return new SyncOutbox.Item("https://x", "k", path, "POST", body, path, key, priority, false, () -> succeeded.add(tag));
	}

	private void advance(long ms) {
		executor.runDue(executor.nowMs + ms);
	}

	private static List<String> bodies(List<SyncOutbox.Item> items) {
		List<String> out = new ArrayList<>();
		for (SyncOutbox.Item i : items) {
			out.add(i.getBody());
		}
		return out;
	}

	@Test
	public void nothingQueuedSendsNothing() {
		advance(60_000);
		outbox.requestFlush();
		outbox.flushNow();
		assertTrue(sender.batches.isEmpty());
		assertTrue(sender.singles.isEmpty());
	}

	@Test
	public void batchesEverythingQueuedWithinTheIntervalInOrder() {
		outbox.enqueue(item("/a", "{\"n\":1}", null, false));
		advance(3_000);
		outbox.enqueue(item("/b", "{\"n\":2}", null, false));
		advance(6_000);
		assertTrue("not before the interval", sender.batches.isEmpty());
		advance(1_000);
		assertEquals(1, sender.batches.size());
		assertEquals(Arrays.asList("{\"n\":1}", "{\"n\":2}"), bodies(sender.batches.get(0)));
		sender.okLast(null);
		assertEquals(Arrays.asList("/a{\"n\":1}", "/b{\"n\":2}"), succeeded);
		assertEquals(0, outbox.size());
	}

	@Test
	public void priorityFlushesAtOnceWithWhatIsQueued() {
		outbox.enqueue(item("/bank", "{\"b\":1}", "bank", false));
		outbox.enqueue(item("/loot/drop", "{\"d\":1}", null, true));
		advance(0);
		assertEquals(1, sender.batches.size());
		assertEquals(2, sender.batches.get(0).size());
	}

	@Test
	public void fullStateUploadsCoalesceToTheNewestAndRunBothCallbacks() {
		outbox.enqueue(item("/bank", "{\"v\":1}", "bank|p", false));
		outbox.enqueue(item("/drop", "{\"e\":1}", null, false));
		outbox.enqueue(item("/bank", "{\"v\":2}", "bank|p", false));
		outbox.enqueue(item("/bank", "{\"other\":1}", "bank|q", false));
		advance(SyncOutbox.FLUSH_INTERVAL_MS);
		assertEquals(Arrays.asList("{\"e\":1}", "{\"v\":2}", "{\"other\":1}"), bodies(sender.batches.get(0)));
		sender.okLast(null);
		// The replaced copy's caller hears of the upload too, first.
		assertEquals(Arrays.asList("/drop{\"e\":1}", "/bank{\"v\":1}", "/bank{\"v\":2}", "/bank{\"other\":1}"), succeeded);
	}

	@Test
	public void eventsAreNeverDropped() {
		for (int i = 0; i < 5; i++) {
			outbox.enqueue(item("/loot/drop", "{\"d\":" + i + "}", null, false));
		}
		advance(SyncOutbox.FLUSH_INTERVAL_MS);
		assertEquals(5, sender.batches.get(0).size());
	}

	@Test
	public void inFlightCopyIsNotReplacedAndNewerGoesNext() {
		outbox.enqueue(item("/skills", "{\"v\":1}", "skills", true));
		advance(0);
		assertEquals(1, sender.batches.size());
		outbox.enqueue(item("/skills", "{\"v\":2}", "skills", false));
		sender.okLast(null);
		advance(SyncOutbox.FLUSH_INTERVAL_MS);
		assertEquals(2, sender.batches.size());
		assertEquals(Collections.singletonList("{\"v\":2}"), bodies(sender.batches.get(1)));
	}

	@Test
	public void splitsAtFiftyAndSendsTheRestRightAway() {
		for (int i = 0; i < 120; i++) {
			outbox.enqueue(item("/e", "{\"i\":" + i + "}", null, false));
		}
		advance(SyncOutbox.FLUSH_INTERVAL_MS);
		assertEquals(1, sender.batches.size());
		assertEquals(50, sender.batches.get(0).size());
		sender.okLast(null);
		assertEquals(2, sender.batches.size());
		assertEquals(50, sender.batches.get(1).size());
		assertEquals("{\"i\":50}", sender.batches.get(1).get(0).getBody());
		sender.okLast(null);
		assertEquals(20, sender.batches.get(2).size());
		sender.okLast(null);
		assertEquals(120, succeeded.size());
		assertEquals("/e{\"i\":119}", succeeded.get(119));
	}

	@Test
	public void networkFailureRetriesWithBackoffKeepingOrder() {
		outbox.enqueue(item("/a", "{\"n\":1}", null, false));
		outbox.enqueue(item("/b", "{\"n\":2}", null, false));
		advance(SyncOutbox.FLUSH_INTERVAL_MS);
		sender.lastBatch().onNetworkFailure("offline");
		assertEquals(2, outbox.size());

		long[] expectedWaits = {10_000, 20_000, 40_000, 80_000, 120_000, 120_000};
		for (long wait : expectedWaits) {
			int before = sender.batches.size();
			advance(wait - 1);
			assertEquals("no retry before " + wait, before, sender.batches.size());
			advance(1);
			assertEquals("retry after " + wait, before + 1, sender.batches.size());
			sender.lastBatch().onNetworkFailure("offline");
		}
		advance(120_000);
		sender.okLast(null);
		assertEquals(Arrays.asList("/a{\"n\":1}", "/b{\"n\":2}"), succeeded);
		assertEquals(0, outbox.size());
	}

	@Test
	public void serverErrorForTheWholeBatchIsRetried() {
		outbox.enqueue(item("/a", "{}", null, true));
		advance(0);
		sender.lastBatch().onResponse(503, null, null);
		assertEquals(1, outbox.size());
		advance(SyncOutbox.BACKOFF_BASE_MS);
		assertEquals(2, sender.batches.size());
	}

	@Test
	public void unauthorisedBatchIsDroppedNotRetried() {
		outbox.enqueue(item("/a", "{}", null, true));
		advance(0);
		sender.lastBatch().onResponse(401, null, null);
		assertEquals(0, outbox.size());
		assertTrue(succeeded.isEmpty());
		advance(10 * 60_000);
		assertEquals(1, sender.batches.size());
	}

	@Test
	public void perEntryFailureDropsOnlyThatEntry() {
		outbox.enqueue(item("/a", "{\"n\":1}", null, false));
		outbox.enqueue(item("/b", "{\"n\":2}", null, false));
		advance(SyncOutbox.FLUSH_INTERVAL_MS);
		sender.lastBatch().onResponse(200, Arrays.asList(500, 200), 3);
		assertEquals(Collections.singletonList("/b{\"n\":2}"), succeeded);
		assertEquals(0, outbox.size());
	}

	@Test
	public void requestsOlderThanThirtyMinutesAreDropped() {
		outbox.enqueue(item("/a", "{}", null, false));
		advance(SyncOutbox.FLUSH_INTERVAL_MS);
		sender.lastBatch().onNetworkFailure("offline");
		// Keep failing for over 30 minutes.
		for (int i = 0; i < 20 && outbox.size() > 0; i++) {
			advance(SyncOutbox.BACKOFF_MAX_MS);
			if (sender.batchCallbacks.size() > i + 1) {
				sender.lastBatch().onNetworkFailure("offline");
			}
		}
		advance(SyncOutbox.MAX_AGE_MS);
		outbox.flushNow();
		assertEquals(0, outbox.size());
		assertTrue(succeeded.isEmpty());
	}

	@Test
	public void flushNowIgnoresBackoff() {
		outbox.enqueue(item("/a", "{}", null, true));
		advance(0);
		sender.lastBatch().onNetworkFailure("offline");
		outbox.flushNow();
		assertEquals(2, sender.batches.size());
	}

	@Test
	public void priorityWaitsOutBackoff() {
		outbox.enqueue(item("/a", "{}", null, true));
		advance(0);
		sender.lastBatch().onNetworkFailure("offline");
		outbox.enqueue(item("/b", "{}", null, true));
		advance(0);
		assertEquals(1, sender.batches.size());
		advance(SyncOutbox.BACKOFF_BASE_MS);
		assertEquals(2, sender.batches.size());
		assertEquals(2, sender.batches.get(1).size());
	}

	@Test
	public void oldServerWithoutBatchGetsEachRequestInOrder() {
		outbox.enqueue(item("/a", "{\"n\":1}", null, false));
		outbox.enqueue(item("/b", "{\"n\":2}", null, false));
		advance(SyncOutbox.FLUSH_INTERVAL_MS);
		sender.lastBatch().onResponse(404, null, null);
		assertEquals(1, sender.singles.size());
		assertEquals("/a", sender.singles.get(0).getPath());
		sender.singleCallbacks.get(0).onResponse(200);
		assertEquals(2, sender.singles.size());
		assertEquals("/b", sender.singles.get(1).getPath());
		sender.singleCallbacks.get(1).onResponse(200);
		assertEquals(Arrays.asList("/a{\"n\":1}", "/b{\"n\":2}"), succeeded);

		// The next flush goes straight to single requests (no batch attempt) for a while.
		outbox.enqueue(item("/c", "{}", null, true));
		advance(0);
		assertEquals(1, sender.batches.size());
		assertEquals(3, sender.singles.size());
		sender.singleCallbacks.get(2).onResponse(200);

		// ...and tries the batch endpoint again later.
		advance(SyncOutbox.BATCH_RETRY_AFTER_404_MS);
		outbox.enqueue(item("/d", "{}", null, true));
		advance(0);
		assertEquals(2, sender.batches.size());
	}

	@Test
	public void singleRequestNetworkFailureKeepsTheRestForRetry() {
		outbox.enqueue(item("/a", "{}", null, false));
		outbox.enqueue(item("/b", "{}", null, false));
		advance(SyncOutbox.FLUSH_INTERVAL_MS);
		sender.lastBatch().onResponse(404, null, null);
		sender.singleCallbacks.get(0).onResponse(200);
		sender.singleCallbacks.get(1).onNetworkFailure("offline");
		assertEquals(1, outbox.size());
		advance(SyncOutbox.BACKOFF_BASE_MS);
		assertEquals(3, sender.singles.size());
		assertEquals("/b", sender.singles.get(2).getPath());
	}

	@Test
	public void webVersionAndBatchTimeAreReported() {
		List<Integer> versions = new ArrayList<>();
		outbox.setWebVersionListener(versions::add);
		assertEquals(Long.MIN_VALUE, outbox.getLastBatchResponseMs());
		outbox.enqueue(item("/a", "{}", null, true));
		advance(5_000);
		sender.okLast(7);
		assertEquals(Collections.singletonList(7), versions);
		assertEquals(5_000, outbox.getLastBatchResponseMs());
	}

	@Test
	public void stopFlushesAndDropsOnFailureInsteadOfRetrying() {
		outbox.enqueue(item("/a", "{}", null, false));
		outbox.stop();
		assertEquals(1, sender.batches.size());
		sender.lastBatch().onNetworkFailure("offline");
		assertEquals(0, outbox.size());
		advance(10 * 60_000);
		assertEquals(1, sender.batches.size());
	}

	@Test
	public void separateKeysGoInSeparateBatches() {
		outbox.enqueue(new SyncOutbox.Item("https://x", "k1", "/a", "POST", "{}", "a", null, false, false, null));
		outbox.enqueue(new SyncOutbox.Item("https://x", "k2", "/b", "POST", "{}", "b", null, false, false, null));
		advance(SyncOutbox.FLUSH_INTERVAL_MS);
		assertEquals(1, sender.batches.get(0).size());
		sender.okLast(null);
		assertEquals(2, sender.batches.size());
		assertEquals("k2", sender.batches.get(1).get(0).getAppKey());
	}

	@Test
	public void hasPendingSeesQueuedLabels() {
		assertFalse(outbox.hasPending("/a"));
		outbox.enqueue(item("/a", "{}", null, false));
		assertTrue(outbox.hasPending("/a"));
	}

	@Test
	public void batchBodySplicesTheJsonBodiesAndNamesThePlayer() {
		List<SyncOutbox.Item> items = Arrays.asList(
				new SyncOutbox.Item("u", "k", "/api/runelite/session/", "POST", "{\"x\":1}", "s", null, false, false, null),
				new SyncOutbox.Item("u", "k", "/api/runelite/skills/", "POST", "{\"player\":\"Zez \\\"ima\\\"\",\"skills\":{}}", "s", null, false, false, null));
		String body = OSRSCompanionApiClient.batchBody(new Gson(), items);
		assertEquals("{\"player_username\":\"Zez \\\"ima\\\"\",\"requests\":["
				+ "{\"path\":\"/api/runelite/session/\",\"method\":\"POST\",\"body\":{\"x\":1}},"
				+ "{\"path\":\"/api/runelite/skills/\",\"method\":\"POST\",\"body\":{\"player\":\"Zez \\\"ima\\\"\",\"skills\":{}}}]}", body);
	}

	@Test
	public void parsesBatchResponse() {
		OSRSCompanionApiClient.BatchResult r = OSRSCompanionApiClient.parseBatchResponse(
				"{\"results\":[{\"status\":200,\"body\":{}},{\"status\":404,\"body\":null}],\"webVersion\":12}");
		assertEquals(Arrays.asList(200, 404), r.statuses);
		assertEquals(Integer.valueOf(12), r.webVersion);
		OSRSCompanionApiClient.BatchResult empty = OSRSCompanionApiClient.parseBatchResponse("{}");
		assertNull(empty.statuses);
		assertNull(empty.webVersion);
	}

	@Test
	public void playerInBodyFindsEitherField() {
		assertEquals("Zezima", OSRSCompanionApiClient.playerInBody("{\"a\":1,\"player_username\":\"Zezima\"}"));
		assertEquals("Lynx", OSRSCompanionApiClient.playerInBody("{\"player\" : \"Lynx\"}"));
		assertNull(OSRSCompanionApiClient.playerInBody("{\"timers\":[]}"));
	}

	@Test
	public void refusedUploadsAreNotSuccesses() {
		outbox.enqueue(item("/ok", "{}", null, false));
		outbox.enqueue(item("/bad", "{}", null, false));
		advance(SyncOutbox.FLUSH_INTERVAL_MS);
		sender.lastBatch().onResponse(200, Arrays.asList(200, 400), null);
		assertEquals(Collections.singletonList("/ok{}"), succeeded);
	}

	@Test
	public void batchExtraRidesAlongOnceAndIsNeverRetried() {
		List<Integer> asked = new ArrayList<>();
		outbox.setBatchExtra(chunk -> {
			asked.add(chunk.size());
			return new SyncOutbox.Item("https://x", "k", "/activity", "POST", "{\"s\":1}", "activity", null, false, false, null);
		});
		outbox.enqueue(item("/a", "{\"n\":1}", null, true));
		advance(0);
		assertEquals(Collections.singletonList(1), asked);
		assertEquals(Arrays.asList("{\"n\":1}", "{\"s\":1}"), bodies(sender.batches.get(0)));
		// A failed batch retries its queued uploads, not the extra (the next batch gets a fresh one).
		sender.lastBatch().onNetworkFailure("offline");
		assertEquals(1, outbox.size());
		advance(SyncOutbox.BACKOFF_MAX_MS);
		assertEquals(2, sender.batches.size());
		assertEquals(Arrays.asList("{\"n\":1}", "{\"s\":1}"), bodies(sender.batches.get(1)));
		sender.okLast(null);
		assertEquals(0, outbox.size());
	}

	@Test
	public void batchExtraForAnotherServerOrKeyIsLeftOut() {
		outbox.setBatchExtra(chunk -> new SyncOutbox.Item("https://other", "k", "/activity", "POST", "{}", "activity", null, false, false, null));
		outbox.enqueue(item("/a", "{\"n\":1}", null, true));
		advance(0);
		assertEquals(1, sender.batches.get(0).size());
	}
}
