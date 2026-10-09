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
import com.osrscompanion.model.AchievementDiariesSyncPayload;
import com.osrscompanion.model.HouseLocationSyncPayload;
import com.osrscompanion.model.BankSyncPayload;
import com.osrscompanion.model.CollectionLogSyncPayload;
import com.osrscompanion.model.CombatAchievementsSyncPayload;
import com.osrscompanion.model.Dailies;
import com.osrscompanion.model.DeathSyncPayload;
import com.osrscompanion.model.BossGear;
import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.model.GearPlan;
import com.osrscompanion.model.KillCountsSyncPayload;
import com.osrscompanion.model.KingdomSyncPayload;
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
import com.osrscompanion.model.TimerSyncItem;
import com.osrscompanion.model.TimersSyncPayload;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
	private static final long REQUEST_TIMEOUT_SECONDS = 10;
	private static final long LARGE_REQUEST_TIMEOUT_SECONDS = 60;
	private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
	// Resolved at call-time from config so the user can point at localhost or any server.

	/**
	 * This build's version (see {@link PluginVersion}), sent with every OSRS Companion request so the
	 * website can show which plugin version each player runs.
	 */
	public static final String PLUGIN_VERSION = PluginVersion.VERSION;
	static final String VERSION_HEADER = "X-OSRSCompanion-Plugin-Version";
	private static final Interceptor VERSION_INTERCEPTOR = chain -> chain.proceed(
		chain.request().newBuilder().header(VERSION_HEADER, PLUGIN_VERSION).build());
	private static final String TIMERS_ENDPOINT = "/api/runelite/timers/";
	private static final String SKILLS_ENDPOINT = "/api/runelite/skills/";
	private static final String BANK_ENDPOINT = "/api/runelite/bank/";
	private static final String COLLECTION_LOG_ENDPOINT = "/api/runelite/collection-log/";
	private static final String QUESTS_ENDPOINT = "/api/runelite/quests/";
	private static final String ACHIEVEMENT_DIARIES_ENDPOINT = "/api/runelite/achievement-diaries/";
	private static final String HOUSE_LOCATION_ENDPOINT = "/api/runelite/house-location/";
	private static final String FARMING_UNLOCKS_ENDPOINT = "/api/runelite/farming-unlocks/";
	private static final String FARM_HARVESTS_ENDPOINT = "/api/runelite/farm-harvests/";
	private static final String FARM_RUNS_ENDPOINT = "/api/runelite/farm-runs/";
	private static final String BOSS_TRIPS_ENDPOINT = "/api/runelite/boss-trips/";
	private static final String COMBAT_ACHIEVEMENTS_ENDPOINT = "/api/runelite/combat-achievements/";
	private static final String DEATHS_ENDPOINT = "/api/runelite/deaths/";
	private static final String KILL_COUNTS_ENDPOINT = "/api/runelite/kill-counts/";
	private static final String KINGDOM_ENDPOINT = "/api/runelite/kingdom/";
	private static final String LOADOUT_ENDPOINT = "/api/runelite/loadouts/";
	private static final String LOOT_ENDPOINT = "/api/runelite/loot/";
	private static final String LOOT_DROP_ENDPOINT = "/api/runelite/loot/drop/";
	private static final String ROADMAPS_ENDPOINT = "/api/runelite/roadmaps/";
	private static final String GEAR_ENDPOINT = "/api/runelite/gear/";
	private static final String FARM_ROUTE_ENDPOINT = "/api/runelite/farm-route/";
	private static final String BOSS_GEAR_ENDPOINT = "/api/runelite/boss-gear/";
	private static final String SESSION_ENDPOINT = "/api/runelite/session/";
	private static final String SLAYER_ENDPOINT = "/api/runelite/slayer/";
	private static final String SLAYER_CATALOG_ENDPOINT = "/api/runelite/slayer/catalog/";
	private static final String SLAYER_CATALOG_STATUS_ENDPOINT = "/api/runelite/slayer/catalog/status/";
	private static final String SLAYER_REWARDS_ENDPOINT = "/api/runelite/slayer/rewards/";
	private static final String DAILIES_ENDPOINT = "/api/runelite/dailies/";
	private static final String SLAYER_GUIDE_ENDPOINT = "/api/runelite/slayer/guide/";
	private static final String SLAYER_GEAR_ENDPOINT = "/api/runelite/slayer/gear/";
	private static final String SLAYER_SESSIONS_ENDPOINT = "/api/runelite/slayer/sessions/";
	static final String BATCH_ENDPOINT = "/api/runelite/batch";
	static final String VERSION_ENDPOINT = "/api/runelite/version";
	static final String ERRORS_ENDPOINT = "/api/runelite/errors";
	static final String ERRORS_LABEL = "errors";
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
	private volatile ErrorListener errorListener;
	/** The current live activity status as JSON, or null when none is to be shared (see {@link #setActivitySource}). */
	private volatile Supplier<String> activitySource;

	/** Hears of failures worth an error report: refused uploads and website answers that could not be read. */
	public interface ErrorListener
	{
		/** An upload the server refused (non-2xx) and that is not retried. */
		void onUploadDropped(String label, String path, int httpStatus);

		/** A website response that was not the JSON expected. */
		void onResponseParseFailed(String label, String path, String error);
	}

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

	public void sendTimersSync(List<TimerSyncItem> timers)
	{
		sendTimersSync(timers, null);
	}

	/** Timers sync naming the player at the top level, so an empty list still reaches the character. */
	public void sendTimersSync(List<TimerSyncItem> timers, String playerUsername)
	{
		String json = TimersSyncPayload.toJson(timers, playerUsername);
		postAsync(TIMERS_ENDPOINT, json, "timers", false, true, key("timers", playerUsername), false,
			() -> log.debug("OSRS Companion timers sync successful: {} timers", timers.size()));
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
				log.debug("OSRS Companion player skills sync successful for player: {}", payload.getPlayer());
				if (onSuccess != null)
				{
					onSuccess.run();
				}
			});
	}

	public void sendLoadoutSync(LoadoutSyncPayload payload)
	{
		postAsync(LOADOUT_ENDPOINT, payload.toJson(gson), "loadout", false, true, key("loadout", payload.getPlayerUsername()), false,
			() -> log.debug("OSRS Companion loadout sync successful for player: {}", payload.getPlayerUsername()));
	}

	public void sendBankSync(BankSyncPayload payload)
	{
		// Each upload carries some sources (bank, a vault, a pouch...): only the same set replaces a queued one.
		String sources = payload.getItems() == null ? "" : String.join(",", new java.util.TreeSet<>(payload.getItems().keySet()));
		postAsync(BANK_ENDPOINT, payload.toJson(gson), "bank", false, true,
			key("bank", payload.getPlayerUsername() + "|" + sources), false,
			() -> log.debug("OSRS Companion bank sync successful: {} items for player: {}",
				payload.getTotalItemCount(), payload.getPlayerUsername()));
	}

	public void sendCollectionLogSync(CollectionLogSyncPayload payload)
	{
		postAsync(COLLECTION_LOG_ENDPOINT, payload.toJson(gson), "collection-log", false, true,
			key("collection-log", payload.getPlayerUsername()), false,
			() -> log.debug("OSRS Companion collection log sync successful: {} items for player: {}",
				payload.getItemCount(), payload.getPlayerUsername()));
	}

	public void sendQuestsSync(QuestsSyncPayload payload)
	{
		postAsync(QUESTS_ENDPOINT, payload.toJson(gson), "quests", false, true, key("quests", payload.getPlayerUsername()), false,
			() -> log.debug("OSRS Companion quests sync successful: {} quests for player: {}",
				payload.getQuests().size(), payload.getPlayerUsername()));
	}

	public void sendAchievementDiariesSync(AchievementDiariesSyncPayload payload)
	{
		postAsync(ACHIEVEMENT_DIARIES_ENDPOINT, payload.toJson(gson), "achievement-diaries", false, true,
			key("achievement-diaries", payload.getPlayerUsername()), false,
			() -> log.debug("OSRS Companion achievement diaries sync successful: {} regions for player: {}",
				payload.getAchievementDiaries().size(), payload.getPlayerUsername()));
	}

	/** Auto-weed and Barbarian Farming state, for the farm route's loadout. */
	public void sendFarmingUnlocks(String json)
	{
		postAsync(FARMING_UNLOCKS_ENDPOINT, json, "farming-unlocks", false, true, key("farming-unlocks", playerInBody(json)), false,
			() -> log.debug("OSRS Companion farming unlocks synced"));
	}

	/** One patch picked clean (Harvest stats); an event, so never deduped. */
	public void sendFarmHarvest(String json)
	{
		postAsync(FARM_HARVESTS_ENDPOINT, json, "farm-harvest", false, false, null, false,
			() -> log.debug("OSRS Companion farm harvest recorded"));
	}

	/** A guided farm run that ended (Run history); an event, so never deduped. */
	public void sendFarmRun(Map<String, Object> body)
	{
		postAsync(FARM_RUNS_ENDPOINT, gson.toJson(body), "farm-run", false, false, null, false,
			() -> log.debug("OSRS Companion farm run recorded"));
	}

	/** One finished boss trip (Boss Gear page, Trips); an event, so never deduped. */
	public void sendBossTrip(String json)
	{
		postAsync(BOSS_TRIPS_ENDPOINT, json, "boss-trip", false, false, null, false,
			() -> log.debug("OSRS Companion boss trip recorded"));
	}

	public void sendHouseLocationSync(HouseLocationSyncPayload payload)
	{
		postAsync(HOUSE_LOCATION_ENDPOINT, payload.toJson(gson), "house-location", false, true,
			key("house-location", payload.getPlayerUsername()), false,
			() -> log.debug("OSRS Companion house location sync successful: varbit {} for player: {}",
				payload.getHouseLocationVarbit(), payload.getPlayerUsername()));
	}

	public void sendKingdomSync(KingdomSyncPayload payload)
	{
		postAsync(KINGDOM_ENDPOINT, payload.toJson(gson), "kingdom", false, true, key("kingdom", payload.getPlayerUsername()), false,
			() -> log.debug("OSRS Companion kingdom sync successful: approval {} coffer {} for player: {}",
				payload.getApprovalPoints(), payload.getCoffer(), payload.getPlayerUsername()));
	}

	public void sendCombatAchievementsSync(CombatAchievementsSyncPayload payload)
	{
		postAsync(COMBAT_ACHIEVEMENTS_ENDPOINT, payload.toJson(gson), "combat-achievements", false, true,
			key("combat-achievements", payload.getPlayerUsername()), false,
			() -> log.debug("OSRS Companion combat achievements sync successful: {} tasks for player: {}",
				payload.getCombatAchievements().size(), payload.getPlayerUsername()));
	}

	/**
	 * Sends the queued deaths. Never deduped: an identical body is a retry of
	 * deaths the backend has not acknowledged yet, or the login handshake.
	 */
	public void sendDeathsSync(DeathSyncPayload payload, Runnable onSuccess)
	{
		// The body is every death not yet acknowledged, so a newer one holds the older one's too.
		postAsync(DEATHS_ENDPOINT, payload.toJson(gson), "deaths", false, false, key("deaths", payload.getPlayerUsername()), false,
			() -> {
				log.debug("OSRS Companion deaths sync successful: {} deaths for player: {}",
					payload.getEvents().size(), payload.getPlayerUsername());
				if (onSuccess != null)
				{
					onSuccess.run();
				}
			});
	}

	public void sendKillCountsSync(KillCountsSyncPayload payload)
	{
		postAsync(KILL_COUNTS_ENDPOINT, payload.toJson(gson), "kill-counts", false, true,
			key("kill-counts", payload.getPlayerUsername()), false,
			() -> log.debug("OSRS Companion kill counts sync successful: {} bosses for player: {}",
				payload.getKillCounts().size(), payload.getPlayerUsername()));
	}

	/**
	 * Syncs slayer state + task transition events. Dedupe is disabled: the
	 * payload carries pending events whose delivery must not be skipped just
	 * because the state half is unchanged; the backend is idempotent on
	 * event_uuid instead.
	 */
	public void sendSlayerSync(SlayerSyncPayload payload, Runnable onSuccess)
	{
		sendSlayerSync(payload, onSuccess, false);
	}

	/** @param urgent send at once (a kill: the task count went down), not with the next timed flush */
	public void sendSlayerSync(SlayerSyncPayload payload, Runnable onSuccess, boolean urgent)
	{
		// The state is whole and the events are every one not yet acknowledged, so a newer payload holds
		// the older one's; a task transition (events) or a kill goes out at once.
		postAsync(SLAYER_ENDPOINT, payload.toJson(gson), "slayer", false, false, key("slayer", payload.getPlayerUsername()),
			urgent || !payload.getEvents().isEmpty(),
			() -> {
				log.debug("OSRS Companion slayer sync successful: {} events for player: {}",
					payload.getEvents().size(), payload.getPlayerUsername());
				if (onSuccess != null)
				{
					onSuccess.run();
				}
			});
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
			() -> log.debug("OSRS Companion slayer catalog upload successful: hash {}",
				payload.getPayloadHash()));
	}

	public void sendSlayerRewards(SlayerRewardsPayload payload)
	{
		// Dedupe off: identical scrapes are still distinct per-player facts,
		// and the monitor already skips byte-identical repeats in-session.
		postAsync(SLAYER_REWARDS_ENDPOINT, payload.toJson(gson), "slayer-rewards", false, false, null, false,
			() -> log.debug("OSRS Companion slayer rewards sync successful: {} rows for player: {}",
				payload.getRows().size(), payload.getPlayerUsername()));
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
				log.debug("OSRS Companion slayer session {} synced", body.get("session_uuid"));
				if (onSuccess != null)
				{
					onSuccess.run();
				}
			});
	}

	public void sendLootSync(LootSyncPayload payload)
	{
		postAsync(LOOT_ENDPOINT, payload.toJson(gson), "loot", true, true, key("loot", payload.getPlayerUsername()), false,
			() -> log.debug("OSRS Companion loot sync successful: {} records for player: {}",
				payload.getLootRecords().size(), payload.getPlayerUsername()));
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
			() -> log.debug("OSRS Companion loot drops batch recorded: {} drops for player: {}",
				drops.size(), playerUsername));
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

	/**
	 * Manually marks a single goal complete and returns the re-rendered roadmap.
	 */
	public void completeRoadmapGoal(int collectionId, int goalId, String playerUsername,
		RoadmapCallback<Roadmap> callback)
	{
		String url = ROADMAPS_ENDPOINT + collectionId + "/goals/" + goalId + "/complete/";
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		postForResult(url, gson.toJson(body), "roadmap-goal-complete", Roadmap.class, callback);
	}

	/**
	 * Deletes a single goal from a roadmap and returns the re-rendered roadmap.
	 */
	public void deleteRoadmapGoal(int collectionId, int goalId, String playerUsername,
		RoadmapCallback<Roadmap> callback)
	{
		String url = ROADMAPS_ENDPOINT + collectionId + "/goals/" + goalId + "/delete/";
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		postForResult(url, gson.toJson(body), "roadmap-goal-delete", Roadmap.class, callback);
	}

	/** Fetches the player's gear progression ({ plan: null } until loaded on the website). */
	public void getGearPlan(String playerUsername, RoadmapCallback<GearPlan.Response> callback)
	{
		getAsync(GEAR_ENDPOINT + "?player=" + urlEncode(playerUsername), "gear", GearPlan.Response.class, callback);
	}

	/** Toggles the player's own "done" tick on a gear item and returns the re-rendered plan. */
	public void toggleGearDone(int itemId, String playerUsername, RoadmapCallback<GearPlan.Response> callback)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		postForResult(GEAR_ENDPOINT + "items/" + itemId + "/done/", gson.toJson(body), "gear-done",
			GearPlan.Response.class, callback);
	}

	/** Hides (or unhides) a gear item and returns the re-rendered roadmap. */
	public void toggleGearHidden(int itemId, String playerUsername, RoadmapCallback<GearPlan.Response> callback)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		postForResult(GEAR_ENDPOINT + "items/" + itemId + "/hide/", gson.toJson(body), "gear-hide",
			GearPlan.Response.class, callback);
	}

	/** Makes another of the player's gear roadmaps the active one and returns the new state. */
	public void activateGearPlan(int planId, String playerUsername, RoadmapCallback<GearPlan.Response> callback)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		postForResult(GEAR_ENDPOINT + "plans/" + planId + "/activate/", gson.toJson(body), "gear-activate",
			GearPlan.Response.class, callback);
	}

	/** Puts a gear item at the top of the player's roadmap. */
	public void setGearGoal(int itemId, String playerUsername, RoadmapCallback<GearPlan.GoalResult> callback)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player", playerUsername);
		postForResult(GEAR_ENDPOINT + "items/" + itemId + "/goal/", gson.toJson(body), "gear-goal",
			GearPlan.GoalResult.class, callback);
	}

	/** Reports a login ("start"), a "still playing" ping or a logout ("end"). */
	public void sendSessionEvent(String playerUsername, String event, String at)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", playerUsername);
		body.put("event", event);
		body.put("at", at);
		postAsync(SESSION_ENDPOINT, gson.toJson(body), "session", false, false, null, false,
			() -> log.debug("OSRS Companion session {} recorded for {}", event, playerUsername));
	}

	/**
	 * Sends the live activity status ({@code POST /api/runelite/activity}). A newer status replaces one
	 * still queued; a priority one (a change, the heartbeat, logout) goes out at once.
	 */
	public void sendActivity(String playerUsername, String json, boolean priority)
	{
		postAsync(ACTIVITY_ENDPOINT, json, ACTIVITY_LABEL, false, false, key(ACTIVITY_LABEL, playerUsername), priority,
			() -> log.debug("OSRS Companion activity sent for {}", playerUsername));
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
		String baseUrl = config.apiBaseUrl().replaceAll("/+$", "");
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

	/** Which dailies are done, open or locked (and the ones ticked by hand); deduped like the other syncs. */
	public void sendDailies(String json)
	{
		// Not merged: a body may be a single untick rather than the whole list.
		postAsync(DAILIES_ENDPOINT, json, "dailies", false, true, null, false,
			() -> log.debug("OSRS Companion dailies synced"));
	}

	/** Fetches the dailies guide: every daily with its state, the way there and the steps. */
	public void getDailies(String playerUsername, RoadmapCallback<Dailies> callback)
	{
		getAsync(DAILIES_ENDPOINT + "?player=" + urlEncode(playerUsername), "dailies", Dailies.class, callback);
	}

	/**
	 * Sends error reports ({@code POST /api/runelite/errors}) through the outbox as a normal upload.
	 * Their own failures are never reported (no loops).
	 */
	public void sendErrorReports(String playerUsername, List<Map<String, Object>> reports)
	{
		sendErrorReports(playerUsername, reports, null);
	}

	/** As above; {@code onSent} runs once the website accepted them (a 2xx answer). */
	public void sendErrorReports(String playerUsername, List<Map<String, Object>> reports, Runnable onSent)
	{
		if (reports == null || reports.isEmpty())
		{
			return;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", playerUsername);
		body.put("reports", reports);
		postAsync(ERRORS_ENDPOINT, gsonWithNulls.toJson(body), ERRORS_LABEL, false, false, null, false,
			() -> {
				log.debug("OSRS Companion error reports sent: {}", reports.size());
				if (onSent != null)
				{
					onSent.run();
				}
			});
	}

	public void setErrorListener(ErrorListener listener)
	{
		this.errorListener = listener;
	}

	private void notifyDropped(String label, String path, int status)
	{
		ErrorListener listener = errorListener;
		// A website without the activity route (404) is not a plugin error: the status is sent again anyway.
		if (listener == null || ERRORS_LABEL.equals(label) || ACTIVITY_LABEL.equals(label) && status == 404)
		{
			return;
		}
		try
		{
			listener.onUploadDropped(label, path, status);
		}
		catch (RuntimeException e)
		{
			log.debug("Error listener failed", e);
		}
	}

	private void notifyParseFailed(String label, String path, String error)
	{
		ErrorListener listener = errorListener;
		if (listener == null)
		{
			return;
		}
		int query = path.indexOf('?');
		try
		{
			listener.onResponseParseFailed(label, query >= 0 ? path.substring(0, query) : path, error);
		}
		catch (RuntimeException e)
		{
			log.debug("Error listener failed", e);
		}
	}

	private <T> void getAsync(String endpoint, String label, Class<T> type, RoadmapCallback<T> callback)
	{
		if (!SyncGuard.hasAppKey(config))
		{
			callback.onError("No OSRS Companion App Key configured");
			return;
		}

		Request request = new Request.Builder()
			.url(config.apiBaseUrl().replaceAll("/+$", "") + endpoint)
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
			.url(config.apiBaseUrl().replaceAll("/+$", "") + endpoint)
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
					notifyParseFailed(label, endpoint, e.getMessage());
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
			log.debug("Skipping {} sync: no OSRS Companion App Key configured", syncType);
			return;
		}

		SyncOutbox box = outbox;
		// An identical body is skipped only when no other upload of this type is still on its way:
		// otherwise going back to what the server had must still replace the newer queued one.
		if (dedupe && isDuplicate(lastSentBodyByType.get(syncType), json) && (box == null || !box.hasPending(syncType)))
		{
			log.debug("Skipping {} sync: identical to last sent request", syncType);
			return;
		}

		Runnable success = () -> {
			if (dedupe)
			{
				lastSentBodyByType.put(syncType, json);
			}
			onSuccess.run();
		};
		String baseUrl = config.apiBaseUrl().replaceAll("/+$", "");
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
					notifyDropped(syncType, endpoint, httpStatus);
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
			box.setDropListener((item, status) -> notifyDropped(item.getLabel(), item.getPath(), status));
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
					if (!onlyErrorReports(items))
					{
						notifyParseFailed("batch", BATCH_ENDPOINT, e.getMessage());
					}
				}
				finally
				{
					response.close();
				}
				callback.onResponse(code, statuses, webVersion);
			}
		});
	}

	/** True when a batch carries nothing but error reports (their failures are never reported). */
	static boolean onlyErrorReports(List<SyncOutbox.Item> items)
	{
		for (SyncOutbox.Item item : items)
		{
			if (!ERRORS_LABEL.equals(item.getLabel()))
			{
				return false;
			}
		}
		return true;
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
			.url(config.apiBaseUrl().replaceAll("/+$", "") + VERSION_ENDPOINT + "?player=" + urlEncode(playerUsername))
			.header("X-RuneLite-Key", config.osrsCompanionAppKey().trim())
			.get()
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Web version check failed: {}", e.getMessage());
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
					log.debug("Failed to read the web version: {}", e.getMessage());
				}
				catch (RuntimeException e)
				{
					log.debug("Failed to read the web version: {}", e.getMessage());
					notifyParseFailed("version", VERSION_ENDPOINT, e.getMessage());
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
			log.debug("Website data changed (webVersion {}), refreshing", version);
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
