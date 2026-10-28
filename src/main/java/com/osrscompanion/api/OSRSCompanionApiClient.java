package com.osrscompanion.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.osrscompanion.Guard;
import com.osrscompanion.OSRSCompanionConfig;
import com.osrscompanion.PluginVersion;
import com.osrscompanion.SyncGuard;
import com.osrscompanion.model.BankSyncPayload;
import com.osrscompanion.model.CollectionLogSyncPayload;
import com.osrscompanion.model.BossGear;
import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.model.KillCountsSyncPayload;
import com.osrscompanion.model.LoadoutSyncPayload;
import com.osrscompanion.model.LootDropPayload;
import com.osrscompanion.model.LootSyncPayload;
import com.osrscompanion.model.SlayerCatalogPayload;
import com.osrscompanion.model.SlayerGuide;
import com.osrscompanion.model.SlayerRewardsPayload;
import com.osrscompanion.model.SlayerSyncPayload;
import com.osrscompanion.model.PlayerSkillsSyncPayload;
import com.osrscompanion.model.QuestsSyncPayload;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapList;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Singleton
public class OSRSCompanionApiClient
{
	/**
	 * The OSRS Companion website every request goes to. A name of our own (not the host's), so the website can
	 * move to another host without a plugin update; older versions used osrs-companion-web.vercel.app.
	 */
	public static final String BASE_URL = "https://osrs-companion.duckdns.org";
	private static final long REQUEST_TIMEOUT_SECONDS = 10;
	private static final long LARGE_REQUEST_TIMEOUT_SECONDS = 60;
	private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

	/**
	 * This build's version (see {@link PluginVersion}), sent with every OSRS Companion request so the
	 * website can show which plugin version each player runs.
	 */
	public static final String PLUGIN_VERSION = PluginVersion.VERSION;
	static final String VERSION_HEADER = "X-OSRSCompanion-Plugin-Version";
	private static final Interceptor VERSION_INTERCEPTOR = chain -> chain.proceed(
		chain.request().newBuilder().header(VERSION_HEADER, PLUGIN_VERSION).build());
	private static final String TIME_TRACKING_ENDPOINT = "/api/runelite/time-tracking/";
	private static final String SKILLS_ENDPOINT = "/api/runelite/skills/";
	private static final String BANK_ENDPOINT = "/api/runelite/bank/";
	private static final String COLLECTION_LOG_ENDPOINT = "/api/runelite/collection-log/";
	private static final String QUESTS_ENDPOINT = "/api/runelite/quests/";
	private static final String FARMING_UNLOCKS_ENDPOINT = "/api/runelite/farming-unlocks/";
	private static final String FARM_RUN_ENDPOINT = "/api/runelite/farm-run/";
	private static final String KILL_COUNTS_ENDPOINT = "/api/runelite/kill-counts/";
	private static final String LOADOUT_ENDPOINT = "/api/runelite/loadouts/";
	private static final String LOOT_ENDPOINT = "/api/runelite/loot/";
	private static final String LOOT_DROP_ENDPOINT = "/api/runelite/loot/drop/";
	private static final String ROADMAPS_ENDPOINT = "/api/runelite/roadmaps/";
	private static final String FARM_ROUTE_ENDPOINT = "/api/runelite/farm-route/";
	private static final String BOSS_GEAR_ENDPOINT = "/api/runelite/boss-gear/";
	private static final String SESSION_ENDPOINT = "/api/runelite/session/";
	private static final String SLAYER_ENDPOINT = "/api/runelite/slayer/";
	private static final String SLAYER_CATALOG_ENDPOINT = "/api/runelite/slayer/catalog/";
	private static final String SLAYER_CATALOG_STATUS_ENDPOINT = "/api/runelite/slayer/catalog/status/";
	private static final String SLAYER_REWARDS_ENDPOINT = "/api/runelite/slayer/rewards/";
	private static final String SLAYER_GUIDE_ENDPOINT = "/api/runelite/slayer/guide/";
	private static final String SLAYER_GEAR_ENDPOINT = "/api/runelite/slayer/gear/";
	private static final String SLAYER_SESSIONS_ENDPOINT = "/api/runelite/slayer/sessions/";
	private static final String VARS_ENDPOINT = "/api/runelite/vars/";
	private static final String EVENTS_ENDPOINT = "/api/runelite/events/";
	static final String BATCH_ENDPOINT = "/api/runelite/batch";
	static final String VERSION_ENDPOINT = "/api/runelite/version";
	/** Live activity status (online, activity, world, area): its own upload, and riding along with every batch. */
	static final String ACTIVITY_ENDPOINT = "/api/runelite/activity";
	static final String ACTIVITY_LABEL = "activity";

	private static final int HTTP_OK_MIN = 200;
	private static final int HTTP_OK_MAX = 300;

	private final OSRSCompanionConfig config;
	private final Gson gson;
	/** The same Gson writing null fields too, for bodies whose contract lists every key (slayer sessions). */
	private final Gson gsonWithNulls;
	private final OkHttpClient okHttpClient;
	private final OkHttpClient largeRequestClient;

	// Last successfully-sent body per syncType; skips byte-identical idempotent
	// re-syncs. Excludes loot-drops, which are append-style events.
	private final Map<String, String> lastSentBodyByType = new ConcurrentHashMap<>();

	/** Queues and batches the uploads once the plugin has started it; null before that. */
	private volatile SyncOutbox outbox;
	/** The website's data version last seen this login; null until the first answer. */
	private Integer lastWebVersion;
	private volatile Runnable webVersionChangedListener;
	/** The current live activity status as JSON, or null when none is to be shared (see {@link #setActivitySource}). */
	private volatile Supplier<String> activitySource;

	@Inject
	public OSRSCompanionApiClient(OSRSCompanionConfig config, Gson gson, OkHttpClient okHttpClient)
	{
		this.config = config;
		this.gson = gson;
		this.gsonWithNulls = gson.newBuilder().serializeNulls().create();
		this.okHttpClient = withVersionHeader(okHttpClient.newBuilder())
			.connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.build();
		this.largeRequestClient = withVersionHeader(okHttpClient.newBuilder())
			.connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.writeTimeout(LARGE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.callTimeout(LARGE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.readTimeout(LARGE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.build();
	}

	/** Tags every request these clients send (first, so the client's own
	 * interceptors see the header too). */
	private static OkHttpClient.Builder withVersionHeader(OkHttpClient.Builder builder)
	{
		builder.interceptors().add(0, VERSION_INTERCEPTOR);
		return builder;
	}

	/** What RuneLite's Time Tracking stored ({@link com.osrscompanion.TimerMonitor}); merged per player. */
	public void sendTimeTracking(String json, String playerUsername)
	{
		postAsync(TIME_TRACKING_ENDPOINT, json, "time-tracking", false, true, key("time-tracking", playerUsername), false,
			null);
	}

	public void sendPlayerSkillsSync(PlayerSkillsSyncPayload payload)
	{
		sendPlayerSkillsSync(payload, null);
	}

	/** Skills sync with a success callback (runs on the OkHttp thread). */
	public void sendPlayerSkillsSync(PlayerSkillsSyncPayload payload, Runnable onSuccess)
	{
		// Never dedupe skills: the backend skills endpoint also triggers roadmap
		// rechecks and WikiSync, so it must run even when the payload is unchanged.
		postAsync(SKILLS_ENDPOINT, payload.toJson(gson), "skills", false, false, key("skills", payload.getPlayer()), false,
			() -> {
				if (onSuccess != null)
				{
					onSuccess.run();
				}
			});
	}

	public void sendLoadoutSync(LoadoutSyncPayload payload)
	{
		postAsync(LOADOUT_ENDPOINT, payload.toJson(gson), "loadout", false, true, key("loadout", payload.getPlayerUsername()), false,
			null);
	}

	public void sendBankSync(BankSyncPayload payload)
	{
		// Each upload carries some sources (bank, a vault, a pouch...): only the same set replaces a queued one.
		String sources = payload.getItems() == null ? "" : String.join(",", new java.util.TreeSet<>(payload.getItems().keySet()));
		postAsync(BANK_ENDPOINT, payload.toJson(gson), "bank", false, true,
			key("bank", payload.getPlayerUsername() + "|" + sources), false,
			null);
	}

	public void sendCollectionLogSync(CollectionLogSyncPayload payload)
	{
		postAsync(COLLECTION_LOG_ENDPOINT, payload.toJson(gson), "collection-log", false, true,
			key("collection-log", payload.getPlayerUsername()), false,
			null);
	}

	public void sendQuestsSync(QuestsSyncPayload payload)
	{
		postAsync(QUESTS_ENDPOINT, payload.toJson(gson), "quests", false, true, key("quests", payload.getPlayerUsername()), false,
			null);
	}


	/** Auto-weed and Barbarian Farming state, for the farm route's loadout. */
	public void sendFarmingUnlocks(String json)
	{
		postAsync(FARMING_UNLOCKS_ENDPOINT, json, "farming-unlocks", false, true, key("farming-unlocks", playerInBody(json)), false,
			null);
	}

	/** A guided farm run that ended (Run history); an event, so never deduped. */
	/** The farm run guide's action and state ({@link com.osrscompanion.FarmRunManager}), answered at once. */
	public <T> void postFarmRun(String json, Class<T> type, RoadmapCallback<T> callback)
	{
		postForResult(FARM_RUN_ENDPOINT, json, "farm-run", type, callback);
	}





	public void sendKillCountsSync(KillCountsSyncPayload payload)
	{
		postAsync(KILL_COUNTS_ENDPOINT, payload.toJson(gson), "kill-counts", false, true,
			key("kill-counts", payload.getPlayerUsername()), false,
			null);
	}

	/** @param urgent send at once (a kill: the task count went down), not with the next timed flush */
	public void sendSlayerSync(SlayerSyncPayload payload, boolean urgent)
	{
		// The state is whole, so a newer payload holds the older one's; a kill goes out at once.
		postAsync(SLAYER_ENDPOINT, payload.toJson(gson), "slayer", false, false, key("slayer", payload.getPlayerUsername()), urgent, null);
	}

	/** Asks whether the server already has the slayer catalog with this hash. */
	public void getSlayerCatalogStatus(String payloadHash, RoadmapCallback<SlayerCatalogPayload.Status> callback)
	{
		String url = SLAYER_CATALOG_STATUS_ENDPOINT + "?hash=" + urlEncode(payloadHash);
		getAsync(url, "slayer-catalog-status", SlayerCatalogPayload.Status.class, callback);
	}

	public void sendSlayerCatalog(SlayerCatalogPayload payload)
	{
		postAsync(SLAYER_CATALOG_ENDPOINT, payload.toJson(gson), "slayer-catalog", true, true, "slayer-catalog", false,
			null);
	}

	public void sendSlayerRewards(SlayerRewardsPayload payload)
	{
		// Dedupe off: identical scrapes are still distinct per-player facts,
		// and the monitor already skips byte-identical repeats in-session.
		postAsync(SLAYER_REWARDS_ENDPOINT, payload.toJson(gson), "slayer-rewards", false, false, null, false,
			null);
	}

	/** Fetches the current slayer task and its locations, best first (see the Slayer helper contract). */
	/** The DPS calculator's best owned setups against the current slayer task's monster. */
	public void getSlayerGear(String playerUsername, RoadmapCallback<com.osrscompanion.model.SlayerGear> callback)
	{
		getAsync(SLAYER_GEAR_ENDPOINT + "?player=" + urlEncode(playerUsername), "slayer-gear", com.osrscompanion.model.SlayerGear.class, callback);
	}

	public void getSlayerGuide(String playerUsername, RoadmapCallback<SlayerGuide> callback)
	{
		getAsync(SLAYER_GUIDE_ENDPOINT + "?player=" + urlEncode(playerUsername), "slayer-guide", SlayerGuide.class, callback);
	}

	/**
	 * Sends one Slayer helper session (upserted by its session_uuid, so re-sending it as it grows is
	 * fine). Never deduped: the helper only sends when something changed. The callback runs on the
	 * OkHttp thread.
	 */
	public void sendSlayerSession(Map<String, Object> body, Runnable onSuccess)
	{
		sendSlayerSession(body, onSuccess, false);
	}

	/** @param urgent send at once (a kill), not with the next timed flush */
	public void sendSlayerSession(Map<String, Object> body, Runnable onSuccess, boolean urgent)
	{
		postAsync(SLAYER_SESSIONS_ENDPOINT, gsonWithNulls.toJson(body), "slayer-session", false, false,
			key("slayer-session", String.valueOf(body.get("session_uuid"))), urgent,
			() -> {
				if (onSuccess != null)
				{
					onSuccess.run();
				}
			});
	}

	public void sendLootSync(LootSyncPayload payload)
	{
		postAsync(LOOT_ENDPOINT, payload.toJson(gson), "loot", true, true, key("loot", payload.getPlayerUsername()), false,
			null);
	}

	public void sendLootDrops(List<LootDropPayload> drops)
	{
		if (drops.isEmpty())
		{
			return;
		}

		// Build batch payload: { player_username, source_client, drops: [{npc_id, npc_name, kill_count, items}, ...] }
		String playerUsername = drops.get(0).getPlayerUsername();
		String sourceClient = drops.get(0).getSourceClient();
		List<Map<String, Object>> dropList = new ArrayList<>();
		for (LootDropPayload drop : drops)
		{
			Map<String, Object> dropMap = new LinkedHashMap<>();
			dropMap.put("npc_id", drop.getNpcId());
			dropMap.put("npc_name", drop.getNpcName());
			dropMap.put("kill_count", drop.getKillCount());
			dropMap.put("dropped_at", drop.getDroppedAt());
			dropMap.put("items", drop.getItems());
			dropList.add(dropMap);
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("player_username", playerUsername);
		payload.put("source_client", sourceClient);
		payload.put("drops", dropList);

		String json = gson.toJson(payload);
		// Append-style events: each batch is distinct (fresh dropped_at per drop), so never dedupe.
		postAsync(LOOT_DROP_ENDPOINT, json, "loot-drops", true, false, null, true,
			null);
	}

	/**
	 * Result callback for roadmap reads. Both methods are invoked on the OkHttp
	 * dispatcher thread (not the client/EDT thread); callers that touch Swing must
	 * marshal back via SwingUtilities.invokeLater.
	 *
	 * @param <T> parsed response type
	 */
	public interface RoadmapCallback<T>
	{
		void onSuccess(T result);

		void onError(String message);
	}

	/**
	 * Fetches the player's roadmaps (id/title/goal_count) for the panel selector.
	 */
	public void getRoadmaps(String playerUsername, RoadmapCallback<RoadmapList> callback)
	{
		String url = ROADMAPS_ENDPOINT + "?player=" + urlEncode(playerUsername);
		getAsync(url, "roadmaps", RoadmapList.class, callback);
	}

	/**
	 * Fetches a single roadmap fully rendered (goals + progress).
	 */
	public void getRoadmap(int collectionId, String playerUsername, RoadmapCallback<Roadmap> callback)
	{
		String url = ROADMAPS_ENDPOINT + collectionId + "/?player=" + urlEncode(playerUsername);
		getAsync(url, "roadmap", Roadmap.class, callback);
	}

	/**
	 * Recomputes a roadmap's goal completion against current synced data and
	 * returns the re-rendered roadmap.
	 */
	public void recomputeRoadmap(int collectionId, String playerUsername, RoadmapCallback<Roadmap> callback)
	{
		String url = ROADMAPS_ENDPOINT + collectionId + "/recompute/";
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		postForResult(url, gson.toJson(body), "roadmap-recompute", Roadmap.class, callback);
	}

	/** Reports a login ("start"), a "still playing" ping or a logout ("end"). */
	public void sendSessionEvent(String playerUsername, String event, String at)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", playerUsername);
		body.put("event", event);
		body.put("at", at);
		postAsync(SESSION_ENDPOINT, gson.toJson(body), "session", false, false, null, false,
			null);
	}

	/**
	 * Sends the live activity status ({@code POST /api/runelite/activity}). A newer status replaces one
	 * still queued; a priority one (a change, the heartbeat, logout) goes out at once.
	 */
	public void sendActivity(String playerUsername, String json, boolean priority)
	{
		postAsync(ACTIVITY_ENDPOINT, json, ACTIVITY_LABEL, false, false, key(ACTIVITY_LABEL, playerUsername), priority,
			null);
	}

	/**
	 * Where the current activity status comes from: every batch carries it (unless it already has one),
	 * so the server sees the player as online without a request of its own. Null: none.
	 */
	public void setActivitySource(Supplier<String> source)
	{
		this.activitySource = source;
	}

	/** The status to add to a batch: same server, key and player as the batch, and none in it yet. */
	SyncOutbox.Item activityExtra(List<SyncOutbox.Item> chunk)
	{
		Supplier<String> source = activitySource;
		if (source == null || chunk.isEmpty() || !SyncGuard.hasAppKey(config))
		{
			return null;
		}
		String player = null;
		for (SyncOutbox.Item item : chunk)
		{
			if (ACTIVITY_LABEL.equals(item.getLabel()))
			{
				return null;
			}
			if (player == null)
			{
				player = playerInBody(item.getBody());
			}
		}
		String baseUrl = BASE_URL;
		String appKey = config.osrsCompanionAppKey().trim();
		SyncOutbox.Item first = chunk.get(0);
		if (!baseUrl.equals(first.getBaseUrl()) || !appKey.equals(first.getAppKey()))
		{
			return null;
		}
		String json = source.get();
		if (json == null || player != null && !player.equals(playerInBody(json)))
		{
			return null;
		}
		return new SyncOutbox.Item(baseUrl, appKey, ACTIVITY_ENDPOINT, "POST", json, ACTIVITY_LABEL, null, false, false, null);
	}

	/** Fetches the player's active farm route ({ route: null } until one is saved on the website). */
	public void getFarmRoute(String playerUsername, RoadmapCallback<FarmRoute.Response> callback)
	{
		getAsync(FARM_ROUTE_ENDPOINT + "?player=" + urlEncode(playerUsername), "farm-route", FarmRoute.Response.class, callback);
	}

	/** Makes another farm route the active one and returns it. */
	public void activateFarmRoute(int routeId, String playerUsername, RoadmapCallback<FarmRoute.Response> callback)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		postForResult(FARM_ROUTE_ENDPOINT + "routes/" + routeId + "/activate/", gson.toJson(body), "farm-route-activate",
			FarmRoute.Response.class, callback);
	}

	/** Fetches the player's active boss gear set ({ set: null } until one is picked on the website). */
	public void getBossGear(String playerUsername, RoadmapCallback<BossGear.Response> callback)
	{
		getAsync(BOSS_GEAR_ENDPOINT + "?player=" + urlEncode(playerUsername), "boss-gear", BossGear.Response.class, callback);
	}

	/** Makes any boss preset (boss key + setup index) the active boss gear and returns it. */
	public void activateBossGearPreset(String bossKey, int setup, String playerUsername, RoadmapCallback<BossGear.Response> callback)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		body.put("boss", bossKey);
		body.put("setup", setup);
		postForResult(BOSS_GEAR_ENDPOINT + "activate/", gson.toJson(body), "boss-gear-activate-preset",
			BossGear.Response.class, callback);
	}

	/** Sets the gear mode of wiki presets (bis | owned | alternatives), on the website too, and returns the reloaded set. */
	public void setBossGearMode(String mode, String playerUsername, RoadmapCallback<BossGear.Response> callback)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		body.put("mode", mode);
		postForResult(BOSS_GEAR_ENDPOINT + "mode/", gson.toJson(body), "boss-gear-mode",
			BossGear.Response.class, callback);
	}

	/**
	 * Saves what you wear and carry as your own set for a boss ({@code loadout}: equipment by slot name,
	 * 28 inventory cells, the rune pouch), makes it the active set and returns it.
	 */
	public void saveBossGearCustom(String bossKey, int setup, String name, Map<String, Object> loadout, String playerUsername,
		RoadmapCallback<BossGear.Response> callback)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		body.put("boss", bossKey);
		body.put("setup", setup);
		body.put("name", name);
		body.put("loadout", loadout);
		postForResult(BOSS_GEAR_ENDPOINT + "custom/", gson.toJson(body), "boss-gear-custom", BossGear.Response.class, callback);
	}

	/** Makes another saved boss gear set the active one and returns it. */
	public void activateBossGear(int setId, String playerUsername, RoadmapCallback<BossGear.Response> callback)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		postForResult(BOSS_GEAR_ENDPOINT + "sets/" + setId + "/activate/", gson.toJson(body), "boss-gear-activate",
			BossGear.Response.class, callback);
	}

	/** The data pump's watch list: per data type, the values the website wants ({@link com.osrscompanion.VarPump}). */
	public <T> void getVarWatchList(String playerUsername, Class<T> type, RoadmapCallback<T> callback)
	{
		getAsync(VARS_ENDPOINT + "?player=" + urlEncode(playerUsername), "vars", type, callback);
	}

	/** The game events the website works the boss trips and history out from ({@link com.osrscompanion.GameEvents}); in order, never merged. */
	public void sendEvents(String json, boolean priority)
	{
		postAsync(EVENTS_ENDPOINT, json, "events", false, false, null, priority, null);
	}

	/** The data pump's raw values (merged per player: only the newest set matters). */
	public void sendVars(String json, String playerUsername)
	{
		postAsync(VARS_ENDPOINT, json, "vars", false, true, key("vars", playerUsername), false,
			null);
	}


	private <T> void getAsync(String endpoint, String label, Class<T> type, RoadmapCallback<T> callback)
	{
		if (!SyncGuard.hasAppKey(config))
		{
			callback.onError("No OSRS Companion App Key configured");
			return;
		}

		Request request = new Request.Builder()
			.url(BASE_URL + endpoint)
			.header("X-RuneLite-Key", config.osrsCompanionAppKey().trim())
			.get()
			.build();

		okHttpClient.newCall(request).enqueue(parsingCallback(endpoint, label, type, callback));
	}

	private <T> void postForResult(String endpoint, String json, String label, Class<T> type,
		RoadmapCallback<T> callback)
	{
		if (!SyncGuard.hasAppKey(config))
		{
			callback.onError("No OSRS Companion App Key configured");
			return;
		}

		Request request = new Request.Builder()
			.url(BASE_URL + endpoint)
			.header("Content-Type", "application/json")
			.header("X-RuneLite-Key", config.osrsCompanionAppKey().trim())
			.post(RequestBody.create(JSON_MEDIA_TYPE, json))
			.build();

		largeRequestClient.newCall(request).enqueue(parsingCallback(endpoint, label, type, callback));
	}

	/** The {@link Guard} feature of a website answer's callback, by what was asked ("roadmap", "slayer guide"...). */
	static String feature(String label)
	{
		return "api-" + (label == null ? "request" : label.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
	}

	private <T> Callback parsingCallback(String endpoint, String label, Class<T> type, RoadmapCallback<T> callback)
	{
		return new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed {} request to OSRS Companion API: {}", label, e.getMessage());
				// The callback is a feature's code, on OkHttp's thread: guarded like the rest.
				Guard.run(feature(label), () -> callback.onError("Network error"));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				Guard.run(feature(label), () -> handle(response));
			}

			private void handle(Response response)
			{
				try
				{
					if (response.code() < HTTP_OK_MIN || response.code() >= HTTP_OK_MAX)
					{
						log.warn("OSRS Companion API returned {} for {}", response.code(), label);
						callback.onError("Server returned " + response.code());
						return;
					}
					String responseBody = response.body() != null ? response.body().string() : "";
					T parsed = gson.fromJson(responseBody, type);
					if (parsed == null)
					{
						callback.onError("Empty response");
						return;
					}
					callback.onSuccess(parsed);
				}
				catch (JsonSyntaxException e)
				{
					log.warn("Failed to parse {} response: {}", label, e.getMessage());
					callback.onError("Could not read response");
				}
				catch (IOException e)
				{
					log.warn("Failed to read {} response: {}", label, e.getMessage());
					callback.onError("Could not read response");
				}
				finally
				{
					response.close();
				}
			}
		};
	}

	private static String urlEncode(String value)
	{
		if (value == null)
		{
			return "";
		}
		try
		{
			return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
		}
		catch (UnsupportedEncodingException e)
		{
			return value;
		}
	}

	/** Coalescing key of a full-state upload for one player. */
	private static String key(String syncType, String player)
	{
		return syncType + "|" + player;
	}

	/**
	 * A fire-and-forget upload. With the outbox running (see {@link #startOutbox}) it is queued and
	 * sent batched; before that (or in tests) it goes out on its own straight away, as it always did.
	 *
	 * @param coalesceKey non-null for a full-state upload a newer copy may replace while queued
	 * @param priority    send with the next flush at once (drops, finished tasks)
	 */
	private void postAsync(String endpoint, String json, String syncType, boolean largeRequest,
		boolean dedupe, String coalesceKey, boolean priority, Runnable onSuccess)
	{
		if (!SyncGuard.hasAppKey(config))
		{
			return;
		}

		SyncOutbox box = outbox;
		// An identical body is skipped only when no other upload of this type is still on its way:
		// otherwise going back to what the server had must still replace the newer queued one.
		if (dedupe && isDuplicate(lastSentBodyByType.get(syncType), json) && (box == null || !box.hasPending(syncType)))
		{
			return;
		}

		Runnable success = () -> {
			if (dedupe)
			{
				lastSentBodyByType.put(syncType, json);
			}
			if (onSuccess != null)
			{
				onSuccess.run();
			}
		};
		String baseUrl = BASE_URL;
		String appKey = config.osrsCompanionAppKey().trim();
		SyncOutbox.Item item = new SyncOutbox.Item(baseUrl, appKey, endpoint, "POST", json, syncType,
			coalesceKey, priority, largeRequest, success);
		if (box != null)
		{
			box.enqueue(item);
			return;
		}
		sendOne(item, new SyncOutbox.SingleCallback()
		{
			@Override
			public void onResponse(int httpStatus)
			{
				if (httpStatus >= HTTP_OK_MIN && httpStatus < HTTP_OK_MAX)
				{
					success.run();
				}
				else
				{
					log.warn("OSRS Companion API returned {} for {} sync", httpStatus, syncType);
				}
			}

			@Override
			public void onNetworkFailure(String message)
			{
				log.warn("Failed to send {} sync to OSRS Companion API: {}", syncType, message);
			}
		});
	}

	// ------------------------------------------------------------------ outbox

	/**
	 * Starts queueing uploads in the outbox (batched, retried) on {@code executor}. Safe to call
	 * again after {@link #stopOutbox()}.
	 */
	public synchronized void startOutbox(ScheduledExecutorService executor)
	{
		if (outbox == null)
		{
			SyncOutbox box = new SyncOutbox(new SyncOutbox.Sender()
			{
				@Override
				public void sendBatch(String baseUrl, String appKey, List<SyncOutbox.Item> items, SyncOutbox.BatchCallback callback)
				{
					OSRSCompanionApiClient.this.sendBatch(baseUrl, appKey, items, callback);
				}

				@Override
				public void sendOne(SyncOutbox.Item item, SyncOutbox.SingleCallback callback)
				{
					OSRSCompanionApiClient.this.sendOne(item, callback);
				}
			}, executor, System::currentTimeMillis);
			box.setWebVersionListener(this::observeWebVersion);
			box.setBatchExtra(this::activityExtra);
			outbox = box;
		}
		outbox.start();
	}

	/** Sends what is queued one last time and stops retrying (plugin shutdown). */
	public void stopOutbox()
	{
		SyncOutbox box = outbox;
		if (box != null)
		{
			box.stop();
		}
	}

	/** Sends everything queued now, ignoring any network backoff (logout, shutdown). */
	public void flushOutbox()
	{
		SyncOutbox box = outbox;
		if (box != null)
		{
			box.flushNow();
		}
	}

	/** Sends what is queued soon (a goal or a container that should show quickly). */
	/** Whether an upload of this label (e.g. "bank") is still queued or on its way. */
	public boolean hasPendingUpload(String label)
	{
		SyncOutbox box = outbox;
		return box != null && box.hasPending(label);
	}

	public void requestOutboxFlush()
	{
		SyncOutbox box = outbox;
		if (box != null)
		{
			box.requestFlush();
		}
	}

	/** When the last batch response arrived (epoch ms), or {@link Long#MIN_VALUE} if none yet. */
	public long getLastBatchResponseMs()
	{
		SyncOutbox box = outbox;
		return box == null ? Long.MIN_VALUE : box.getLastBatchResponseMs();
	}

	private void sendBatch(String baseUrl, String appKey, List<SyncOutbox.Item> items, SyncOutbox.BatchCallback callback)
	{
		Request request = new Request.Builder()
			.url(baseUrl + BATCH_ENDPOINT)
			.header("Content-Type", "application/json")
			.header("X-RuneLite-Key", appKey)
			.post(RequestBody.create(JSON_MEDIA_TYPE, batchBody(gson, items)))
			.build();
		largeRequestClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.onNetworkFailure(e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				List<Integer> statuses = null;
				Integer webVersion = null;
				int code = response.code();
				try
				{
					if (code >= HTTP_OK_MIN && code < HTTP_OK_MAX && response.body() != null)
					{
						BatchResult parsed = parseBatchResponse(response.body().string());
						statuses = parsed.statuses;
						webVersion = parsed.webVersion;
					}
				}
				catch (IOException e)
				{
					log.warn("Failed to read the batch response: {}", e.getMessage());
				}
				catch (RuntimeException e)
				{
					log.warn("Failed to read the batch response: {}", e.getMessage());
				}
				finally
				{
					response.close();
				}
				callback.onResponse(code, statuses, webVersion);
			}
		});
	}

	/** {@code {"requests":[{"path","method","body"}]}}, the bodies spliced in as the JSON they already are. */
	static String batchBody(Gson gson, List<SyncOutbox.Item> items)
	{
		StringBuilder sb = new StringBuilder("{");
		// The top-level player lets the server resolve webVersion even when no entry succeeds.
		for (SyncOutbox.Item item : items)
		{
			String player = playerInBody(item.getBody());
			if (player != null)
			{
				sb.append("\"player_username\":").append(gson.toJson(player)).append(',');
				break;
			}
		}
		sb.append("\"requests\":[");
		for (int i = 0; i < items.size(); i++)
		{
			SyncOutbox.Item item = items.get(i);
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append("{\"path\":").append(gson.toJson(item.getPath()))
				.append(",\"method\":").append(gson.toJson(item.getMethod()))
				.append(",\"body\":").append(item.getBody() == null || item.getBody().isEmpty() ? "null" : item.getBody())
				.append('}');
		}
		return sb.append("]}").toString();
	}

	private static final java.util.regex.Pattern PLAYER_FIELD =
		java.util.regex.Pattern.compile("\"(?:player_username|player)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

	/** The first player name a JSON body carries (cheap scan, no full parse), or null. */
	static String playerInBody(String body)
	{
		if (body == null)
		{
			return null;
		}
		java.util.regex.Matcher m = PLAYER_FIELD.matcher(body);
		if (!m.find())
		{
			return null;
		}
		try
		{
			return new JsonParser().parse("\"" + m.group(1) + "\"").getAsString();
		}
		catch (RuntimeException e)
		{
			return m.group(1);
		}
	}

	static final class BatchResult
	{
		final List<Integer> statuses;
		final Integer webVersion;

		BatchResult(List<Integer> statuses, Integer webVersion)
		{
			this.statuses = statuses;
			this.webVersion = webVersion;
		}
	}

	/** Reads {@code {"results":[{"status","body"}],"webVersion"}}; missing parts come back null. */
	static BatchResult parseBatchResponse(String json)
	{
		JsonElement root = new JsonParser().parse(json);
		if (root == null || !root.isJsonObject())
		{
			return new BatchResult(null, null);
		}
		JsonObject obj = root.getAsJsonObject();
		List<Integer> statuses = null;
		JsonElement results = obj.get("results");
		if (results != null && results.isJsonArray())
		{
			statuses = new ArrayList<>();
			for (JsonElement r : results.getAsJsonArray())
			{
				JsonElement status = r.isJsonObject() ? r.getAsJsonObject().get("status") : null;
				statuses.add(status != null && status.isJsonPrimitive() ? status.getAsInt() : null);
			}
		}
		return new BatchResult(statuses, readWebVersion(obj));
	}

	private static Integer readWebVersion(JsonObject obj)
	{
		JsonElement v = obj.get("webVersion");
		return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsInt() : null;
	}

	/** One upload on its own (no outbox yet, or a server without the batch endpoint). */
	private void sendOne(SyncOutbox.Item item, SyncOutbox.SingleCallback callback)
	{
		Request request = new Request.Builder()
			.url(item.getBaseUrl() + item.getPath())
			.header("Content-Type", "application/json")
			.header("X-RuneLite-Key", item.getAppKey())
			.post(RequestBody.create(JSON_MEDIA_TYPE, item.getBody()))
			.build();

		OkHttpClient client = item.isLarge() ? largeRequestClient : okHttpClient;
		client.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.onNetworkFailure(e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				int code = response.code();
				response.close();
				callback.onResponse(code);
			}
		});
	}

	// ------------------------------------------------------------- web version

	/**
	 * Asks the website for the player's webVersion (bumped whenever they change something there).
	 * The answer is passed to {@link #observeWebVersion} as well as to the callback.
	 */
	public void getWebVersion(String playerUsername, RoadmapCallback<Integer> callback)
	{
		if (!SyncGuard.hasAppKey(config))
		{
			callback.onError("No OSRS Companion App Key configured");
			return;
		}
		Request request = new Request.Builder()
			.url(BASE_URL + VERSION_ENDPOINT + "?player=" + urlEncode(playerUsername))
			.header("X-RuneLite-Key", config.osrsCompanionAppKey().trim())
			.get()
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.onError("Network error");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				Integer version = null;
				try
				{
					if (response.code() >= HTTP_OK_MIN && response.code() < HTTP_OK_MAX && response.body() != null)
					{
						JsonElement root = new JsonParser().parse(response.body().string());
						version = root != null && root.isJsonObject() ? readWebVersion(root.getAsJsonObject()) : null;
					}
				}
				catch (IOException e)
				{
				}
				catch (RuntimeException e)
				{
				}
				finally
				{
					response.close();
				}
				if (version == null)
				{
					callback.onError("No web version");
					return;
				}
				observeWebVersion(version);
				callback.onSuccess(version);
			}
		});
	}

	/** Hears that the website's data changed (a different webVersion than the last one seen). */
	public void setWebVersionChangedListener(Runnable listener)
	{
		this.webVersionChangedListener = listener;
	}

	/**
	 * Notes a webVersion from a batch response or the version check; a change from the last value
	 * seen (not the first value since {@link #resetWebVersion()}) tells the listener.
	 */
	public void observeWebVersion(int version)
	{
		boolean changed;
		synchronized (this)
		{
			changed = lastWebVersion != null && lastWebVersion != version;
			lastWebVersion = version;
		}
		Runnable listener = webVersionChangedListener;
		if (changed && listener != null)
		{
			listener.run();
		}
	}

	/** Forgets the last webVersion (logout): the next login's first value is a baseline, not a change. */
	public synchronized void resetWebVersion()
	{
		lastWebVersion = null;
	}

	static boolean isDuplicate(String previousBody, String currentJson)
	{
		return previousBody != null && previousBody.equals(currentJson);
	}

	/**
	 * Forgets the last-sent body for every sync type, so the next send of each
	 * type goes out even if its payload is byte-identical to the previous one.
	 *
	 * <p>The roadmap panel's "Sync &amp; refresh" is an explicit, user-triggered
	 * re-sync: the dedupe guard (which silently skips unchanged idempotent syncs)
	 * must not swallow it, or the button would do nothing when the player's data
	 * hasn't changed since the last automatic sync.
	 */
	public void clearDedupeCache()
	{
		lastSentBodyByType.clear();
	}
}
