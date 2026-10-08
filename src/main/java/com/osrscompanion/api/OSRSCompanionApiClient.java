package com.osrscompanion.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.osrscompanion.OSRSCompanionConfig;
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
import java.util.concurrent.TimeUnit;
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
	 * This release, sent with every OSRS Companion request so the server can tell plugin
	 * releases apart (Plugin Hub builds carry no version of their own). Bump it
	 * in the commit a Plugin Hub update points at.
	 */
	public static final String PLUGIN_VERSION = "2026.10.05";
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

	@Inject
	public OSRSCompanionApiClient(OSRSCompanionConfig config, Gson gson, OkHttpClient okHttpClient)
	{
		this.config = config;
		this.gson = gson;
		this.gsonWithNulls = gson.newBuilder().serializeNulls().create();
		this.okHttpClient = withVersionHeader(okHttpClient.newBuilder())
			.callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.build();
		this.largeRequestClient = withVersionHeader(okHttpClient.newBuilder())
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
		postAsync(TIMERS_ENDPOINT, json, "timers", false, true,
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
		postAsync(SKILLS_ENDPOINT, payload.toJson(gson), "skills", false, false,
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
		postAsync(LOADOUT_ENDPOINT, payload.toJson(gson), "loadout", false, true,
			() -> log.debug("OSRS Companion loadout sync successful for player: {}", payload.getPlayerUsername()));
	}

	public void sendBankSync(BankSyncPayload payload)
	{
		postAsync(BANK_ENDPOINT, payload.toJson(gson), "bank", false, true,
			() -> log.debug("OSRS Companion bank sync successful: {} items for player: {}",
				payload.getTotalItemCount(), payload.getPlayerUsername()));
	}

	public void sendCollectionLogSync(CollectionLogSyncPayload payload)
	{
		postAsync(COLLECTION_LOG_ENDPOINT, payload.toJson(gson), "collection-log", false, true,
			() -> log.debug("OSRS Companion collection log sync successful: {} items for player: {}",
				payload.getItemCount(), payload.getPlayerUsername()));
	}

	public void sendQuestsSync(QuestsSyncPayload payload)
	{
		postAsync(QUESTS_ENDPOINT, payload.toJson(gson), "quests", false, true,
			() -> log.debug("OSRS Companion quests sync successful: {} quests for player: {}",
				payload.getQuests().size(), payload.getPlayerUsername()));
	}

	public void sendAchievementDiariesSync(AchievementDiariesSyncPayload payload)
	{
		postAsync(ACHIEVEMENT_DIARIES_ENDPOINT, payload.toJson(gson), "achievement-diaries", false, true,
			() -> log.debug("OSRS Companion achievement diaries sync successful: {} regions for player: {}",
				payload.getAchievementDiaries().size(), payload.getPlayerUsername()));
	}

	/** Auto-weed and Barbarian Farming state, for the farm route's loadout. */
	public void sendFarmingUnlocks(String json)
	{
		postAsync(FARMING_UNLOCKS_ENDPOINT, json, "farming-unlocks", false, true,
			() -> log.debug("OSRS Companion farming unlocks synced"));
	}

	/** One patch picked clean (Harvest stats); an event, so never deduped. */
	public void sendFarmHarvest(String json)
	{
		postAsync(FARM_HARVESTS_ENDPOINT, json, "farm-harvest", false, false,
			() -> log.debug("OSRS Companion farm harvest recorded"));
	}

	/** A guided farm run that ended (Run history); an event, so never deduped. */
	public void sendFarmRun(Map<String, Object> body)
	{
		postAsync(FARM_RUNS_ENDPOINT, gson.toJson(body), "farm-run", false, false,
			() -> log.debug("OSRS Companion farm run recorded"));
	}

	/** One finished boss trip (Boss Gear page, Trips); an event, so never deduped. */
	public void sendBossTrip(String json)
	{
		postAsync(BOSS_TRIPS_ENDPOINT, json, "boss-trip", false, false,
			() -> log.debug("OSRS Companion boss trip recorded"));
	}

	public void sendHouseLocationSync(HouseLocationSyncPayload payload)
	{
		postAsync(HOUSE_LOCATION_ENDPOINT, payload.toJson(gson), "house-location", false, true,
			() -> log.debug("OSRS Companion house location sync successful: varbit {} for player: {}",
				payload.getHouseLocationVarbit(), payload.getPlayerUsername()));
	}

	public void sendKingdomSync(KingdomSyncPayload payload)
	{
		postAsync(KINGDOM_ENDPOINT, payload.toJson(gson), "kingdom", false, true,
			() -> log.debug("OSRS Companion kingdom sync successful: approval {} coffer {} for player: {}",
				payload.getApprovalPoints(), payload.getCoffer(), payload.getPlayerUsername()));
	}

	public void sendCombatAchievementsSync(CombatAchievementsSyncPayload payload)
	{
		postAsync(COMBAT_ACHIEVEMENTS_ENDPOINT, payload.toJson(gson), "combat-achievements", false, true,
			() -> log.debug("OSRS Companion combat achievements sync successful: {} tasks for player: {}",
				payload.getCombatAchievements().size(), payload.getPlayerUsername()));
	}

	/**
	 * Sends the queued deaths. Never deduped: an identical body is a retry of
	 * deaths the backend has not acknowledged yet, or the login handshake.
	 */
	public void sendDeathsSync(DeathSyncPayload payload, Runnable onSuccess)
	{
		postAsync(DEATHS_ENDPOINT, payload.toJson(gson), "deaths", false, false,
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
		postAsync(SLAYER_ENDPOINT, payload.toJson(gson), "slayer", false, false,
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
		postAsync(SLAYER_CATALOG_ENDPOINT, payload.toJson(gson), "slayer-catalog", true, true,
			() -> log.debug("OSRS Companion slayer catalog upload successful: hash {}",
				payload.getPayloadHash()));
	}

	public void sendSlayerRewards(SlayerRewardsPayload payload)
	{
		// Dedupe off: identical scrapes are still distinct per-player facts,
		// and the monitor already skips byte-identical repeats in-session.
		postAsync(SLAYER_REWARDS_ENDPOINT, payload.toJson(gson), "slayer-rewards", false, false,
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
		postAsync(SLAYER_SESSIONS_ENDPOINT, gsonWithNulls.toJson(body), "slayer-session", false, false,
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
		postAsync(LOOT_ENDPOINT, payload.toJson(gson), "loot", true, true,
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
		postAsync(LOOT_DROP_ENDPOINT, json, "loot-drops", true, false,
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
		postAsync(SESSION_ENDPOINT, gson.toJson(body), "session", false, false,
			() -> log.debug("OSRS Companion session {} recorded for {}", event, playerUsername));
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
		postAsync(DAILIES_ENDPOINT, json, "dailies", false, true,
			() -> log.debug("OSRS Companion dailies synced"));
	}

	/** Fetches the dailies guide: every daily with its state, the way there and the steps. */
	public void getDailies(String playerUsername, RoadmapCallback<Dailies> callback)
	{
		getAsync(DAILIES_ENDPOINT + "?player=" + urlEncode(playerUsername), "dailies", Dailies.class, callback);
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

		okHttpClient.newCall(request).enqueue(parsingCallback(label, type, callback));
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

		largeRequestClient.newCall(request).enqueue(parsingCallback(label, type, callback));
	}

	private <T> Callback parsingCallback(String label, Class<T> type, RoadmapCallback<T> callback)
	{
		return new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed {} request to OSRS Companion API: {}", label, e.getMessage());
				callback.onError("Network error");
			}

			@Override
			public void onResponse(Call call, Response response)
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
				catch (IOException | JsonSyntaxException e)
				{
					log.warn("Failed to parse {} response: {}", label, e.getMessage());
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

	private void postAsync(String endpoint, String json, String syncType, boolean largeRequest,
		boolean dedupe, Runnable onSuccess)
	{
		if (!SyncGuard.hasAppKey(config))
		{
			log.debug("Skipping {} sync: no OSRS Companion App Key configured", syncType);
			return;
		}

		if (dedupe && isDuplicate(lastSentBodyByType.get(syncType), json))
		{
			log.debug("Skipping {} sync: identical to last sent request", syncType);
			return;
		}

		String appKey = config.osrsCompanionAppKey();
		String url = config.apiBaseUrl().replaceAll("/+$", "") + endpoint;

		Request request = new Request.Builder()
			.url(url)
			.header("Content-Type", "application/json")
			.header("X-RuneLite-Key", appKey.trim())
			.post(RequestBody.create(JSON_MEDIA_TYPE, json))
			.build();

		OkHttpClient client = largeRequest ? largeRequestClient : okHttpClient;
		client.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to send {} sync to OSRS Companion API: {}", syncType, e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (response.code() >= HTTP_OK_MIN && response.code() < HTTP_OK_MAX)
					{
						if (dedupe)
						{
							lastSentBodyByType.put(syncType, json);
						}
						onSuccess.run();
					}
					else
					{
						log.warn("OSRS Companion API returned {} for {} sync", response.code(), syncType);
					}
				}
				finally
				{
					response.close();
				}
			}
		});
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
