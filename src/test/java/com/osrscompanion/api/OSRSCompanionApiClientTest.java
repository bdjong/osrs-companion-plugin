package com.osrscompanion.api;

import com.google.gson.Gson;
import com.osrscompanion.OSRSCompanionConfig;
import com.osrscompanion.model.BankSyncPayload;
import com.osrscompanion.model.PlayerSkillsSyncPayload;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests that OSRSCompanionApiClient handles config correctly and payload structure.
 */
public class OSRSCompanionApiClientTest {
	private static OSRSCompanionConfig emptyKeyConfig() {
		com.osrscompanion.TestOSRSCompanionConfig c = new com.osrscompanion.TestOSRSCompanionConfig();
		c.setOsrsCompanionAppKey("");
		return c;
	}

	@Test
	public void testSendWithEmptyAppKeyDoesNotThrow() {
		OSRSCompanionApiClient client = new OSRSCompanionApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		client.sendTimeTracking("{\"player_username\":\"TestPlayer\",\"profile\":{}}", "TestPlayer");
		// Should not throw; with empty key it returns early
	}

	@Test
	public void testSendPlayerSkillsWithEmptyAppKeyDoesNotThrow() {
		OSRSCompanionApiClient client = new OSRSCompanionApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		skills.put("Attack", new PlayerSkillsSyncPayload.SkillData(75, 1200000));
		skills.put("Defence", new PlayerSkillsSyncPayload.SkillData(70, 800000));
		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("TestPlayer", skills, 145, 85);
		client.sendPlayerSkillsSync(payload);
		// Should not throw; with empty key it returns early
	}

	@Test
	public void testSendBankSyncWithEmptyAppKeyDoesNotThrow() {
		OSRSCompanionApiClient client = new OSRSCompanionApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		Map<String, java.util.List<BankSyncPayload.BankItem>> items = new HashMap<>();
		items.put("bank", Arrays.asList(new BankSyncPayload.BankItem(4151, 1)));
		BankSyncPayload payload = new BankSyncPayload("TestPlayer", items);
		client.sendBankSync(payload);
		// Should not throw; with empty key it returns early
	}

	@Test
	public void testPlayerSkillsPayloadStructure() {
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		skills.put("Attack", new PlayerSkillsSyncPayload.SkillData(75, 1200000));
		skills.put("Defence", new PlayerSkillsSyncPayload.SkillData(70, 800000));
		skills.put("Strength", new PlayerSkillsSyncPayload.SkillData(80, 2000000));
		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("TestPlayer", skills, 225, 95);
		String json = payload.toJson(new Gson());

		assertNotNull(json);
		assertTrue(json.contains("\"player\":\"TestPlayer\""));
		assertTrue(json.contains("\"skills\""));
		/* API expects skills as {"SkillName": {"level": N, "current_xp": N}} */
		assertTrue(json.contains("Attack"));
		assertTrue(json.contains("\"level\":75"));
		assertTrue(json.contains("\"level\":70"));
		assertTrue(json.contains("\"level\":80"));
		assertTrue(json.contains("\"current_xp\":1200000"));
		assertTrue(json.contains("\"current_xp\":800000"));
		assertTrue(json.contains("\"current_xp\":2000000"));
	}

	@Test
	public void testGetRoadmapsWithEmptyKeyCallsOnError() {
		OSRSCompanionApiClient client = new OSRSCompanionApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		AtomicReference<String> error = new AtomicReference<>();
		AtomicBoolean success = new AtomicBoolean(false);
		client.getRoadmaps("TestPlayer", new OSRSCompanionApiClient.RoadmapCallback<RoadmapList>() {
			@Override
			public void onSuccess(RoadmapList result) {
				success.set(true);
			}

			@Override
			public void onError(String message) {
				error.set(message);
			}
		});
		// No key => synchronous onError, no network call.
		assertFalse(success.get());
		assertNotNull(error.get());
	}

	@Test
	public void testRecomputeRoadmapWithEmptyKeyCallsOnError() {
		OSRSCompanionApiClient client = new OSRSCompanionApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		AtomicReference<String> error = new AtomicReference<>();
		client.recomputeRoadmap(1, "TestPlayer", new OSRSCompanionApiClient.RoadmapCallback<Roadmap>() {
			@Override
			public void onSuccess(Roadmap result) {
			}

			@Override
			public void onError(String message) {
				error.set(message);
			}
		});
		assertEquals("No OSRS Companion App Key configured", error.get());
	}

	@Test
	public void testIsDuplicateReturnsFalseWhenPreviousNull() {
		/* First-ever send for a syncType always goes out. */
		assertFalse(OSRSCompanionApiClient.isDuplicate(null, "{\"timers\":[]}"));
	}

	@Test
	public void testIsDuplicateReturnsTrueWhenBytesEqual() {
		String body = "{\"timers\":[{\"timer_type\":\"tree\"}]}";
		assertTrue(OSRSCompanionApiClient.isDuplicate(body, body));
	}

	@Test
	public void testIsDuplicateReturnsFalseWhenBytesDiffer() {
		/* Bodies differing only by player_username are not duplicates (multi-account safety). */
		String bodyA = "{\"player_username\":\"PlayerA\",\"timers\":[]}";
		String bodyB = "{\"player_username\":\"PlayerB\",\"timers\":[]}";
		assertFalse(OSRSCompanionApiClient.isDuplicate(bodyA, bodyB));
	}

	@Test
	public void testDuplicateSendWithEmptyAppKeyDoesNotThrow() {
		/* The dedupe guard runs after the app-key guard, so the empty-key path is unaffected. */
		OSRSCompanionApiClient client = new OSRSCompanionApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		String json = "{\"player_username\":\"TestPlayer\",\"profile\":{}}";
		client.sendTimeTracking(json, "TestPlayer");
		client.sendTimeTracking(json, "TestPlayer");
		// Should not throw; with empty key both return early before the dedupe check
	}

	/** A base client that answers every call itself and records what it was sent. */
	private static OkHttpClient recordingClient(AtomicReference<Request> sent, CountDownLatch done) {
		return new OkHttpClient.Builder()
				.addInterceptor(chain -> {
					sent.set(chain.request());
					done.countDown();
					return new Response.Builder()
							.request(chain.request())
							.protocol(Protocol.HTTP_1_1)
							.code(200)
							.message("OK")
							.body(ResponseBody.create(null, "{\"roadmaps\":[]}"))
							.build();
				})
				.build();
	}

	@Test
	public void everyRequestCarriesThePluginVersion() throws InterruptedException {
		com.osrscompanion.TestOSRSCompanionConfig config = new com.osrscompanion.TestOSRSCompanionConfig();
		config.setOsrsCompanionAppKey("test-key");
		AtomicReference<Request> sent = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		OSRSCompanionApiClient client = new OSRSCompanionApiClient(config, new Gson(), recordingClient(sent, done));

		client.getRoadmaps("Zezima", new OSRSCompanionApiClient.RoadmapCallback<RoadmapList>() {
			@Override
			public void onSuccess(RoadmapList result) {
			}

			@Override
			public void onError(String message) {
			}
		});

		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals(OSRSCompanionApiClient.PLUGIN_VERSION, sent.get().header(OSRSCompanionApiClient.VERSION_HEADER));
		assertEquals("test-key", sent.get().header("X-RuneLite-Key"));
	}

	@Test
	public void pluginVersionIsHeaderSafe() {
		assertTrue(OSRSCompanionApiClient.PLUGIN_VERSION.matches("[0-9A-Za-z._-]{1,32}"));
	}

	@Test
	public void webVersionChangeIsReportedButNotTheFirstValue() {
		OSRSCompanionApiClient client = new OSRSCompanionApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
		client.setWebVersionChangedListener(changes::incrementAndGet);
		client.observeWebVersion(4);
		assertEquals("first value is the baseline", 0, changes.get());
		client.observeWebVersion(4);
		assertEquals(0, changes.get());
		client.observeWebVersion(5);
		assertEquals(1, changes.get());
		client.resetWebVersion();
		client.observeWebVersion(9);
		assertEquals("after logout the first value is a new baseline", 1, changes.get());
	}

	@Test
	public void activityRidesAlongWithABatchOfTheSamePlayerOnly() {
		com.osrscompanion.TestOSRSCompanionConfig c = new com.osrscompanion.TestOSRSCompanionConfig();
		c.setOsrsCompanionAppKey("abcdefgh");
		OSRSCompanionApiClient client = new OSRSCompanionApiClient(c, new Gson(), new OkHttpClient());
		String base = OSRSCompanionApiClient.BASE_URL;
		SyncOutbox.Item bank = new SyncOutbox.Item(base, "abcdefgh", "/api/runelite/bank/", "POST",
			"{\"player_username\":\"Zezima\"}", "bank", null, false, false, null);
		// No source: nothing.
		assertEquals(null, client.activityExtra(Collections.singletonList(bank)));
		client.setActivitySource(() -> "{\"player_username\":\"Zezima\",\"state\":\"online\"}");
		SyncOutbox.Item extra = client.activityExtra(Collections.singletonList(bank));
		assertNotNull(extra);
		assertEquals(OSRSCompanionApiClient.ACTIVITY_ENDPOINT, extra.getPath());
		assertEquals(OSRSCompanionApiClient.ACTIVITY_LABEL, extra.getLabel());
		// A batch that already has a status, or is for another player, gets none.
		assertEquals(null, client.activityExtra(Arrays.asList(bank, extra)));
		client.setActivitySource(() -> "{\"player_username\":\"Other\"}");
		assertEquals(null, client.activityExtra(Collections.singletonList(bank)));
		// Nothing to share (logged out, switched off): none.
		client.setActivitySource(() -> null);
		assertEquals(null, client.activityExtra(Collections.singletonList(bank)));
	}
}
