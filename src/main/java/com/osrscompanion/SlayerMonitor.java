/*
 * Task and area name resolution in resolveTaskName()/resolveAreaName() is
 * adapted from RuneLite's SlayerPlugin (BSD 2-Clause):
 * Copyright (c) 2017, Tyler <https://github.com/tylerthardy>
 * Copyright (c) 2018, Shaun Dreclin <shaundreclin@gmail.com>
 * All rights reserved. See https://github.com/runelite/runelite for terms.
 */
package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.SlayerSyncPayload;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors slayer state (assignment, points, streaks, block list, unlocks,
 * the Task Storage slot) and task transitions, and syncs them to the OSRS Companion
 * API.
 *
 * <p>State is read directly from the slayer varps/varbits (no dependency on
 * RuneLite's Slayer plugin being enabled), with task and area names resolved
 * client-side through the game-cache DB tables. Each read, each change of the task count and each
 * completion chat line is also recorded as a game event ({@link GameEvents}): the website works out from
 * them how each task ended (completed, skipped, blocked...) and the time spent on it.
 */
@Slf4j
@Singleton
public class SlayerMonitor {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "slayer-task-sync";

	private static final int LOGIN_SYNC_DELAY_SECONDS = 5;
	private static final int RESYNC_THROTTLE_TICKS = 3;
	/**
	 * Minimum interval between amount-only syncs (task progress ticks down every kill). Uploads are
	 * batched by the outbox, so this can be short; a kill inside the window is sent when it closes.
	 */
	private static final long AMOUNT_SYNC_MIN_INTERVAL_MS = 10_000L;
	/**
	 * Ticks between a task ending and the website closing it: the completion chat message can arrive a tick
	 * after the varp flips, so the website waits for this "slayer_fin" event.
	 */
	private static final int EVENT_FINALIZE_DELAY_TICKS = 3;
	/** SLAYER_TARGET value meaning "a boss task"; resolve via the sublist table. */
	private static final int BOSS_TASK_SENTINEL = 98;
	/**
	 * The slayer task-offer dialog (Mortimer's 2-3 choices, each with a name,
	 * an amount range and a modifier). It raises no WidgetLoaded event, being
	 * a nested child of the game frame rather than its own interface, so it is
	 * polled instead. A wrong id costs nothing beyond a null lookup: the parse
	 * only accepts offer-shaped rows, so it can never yield bad data.
	 *
	 * <p>The whole subtree under the container is read, not one child list.
	 * Reading only the dynamic children captured every name and amount but
	 * never a modifier from any live dialog, so the modifier rows do not live
	 * where the names do.
	 */
	private static final int TASK_OFFER_GROUP = 236;
	private static final int TASK_OFFER_CHILD = 3;
	private static final int OFFER_SCAN_INTERVAL_TICKS = 5;
	/** How deep under the dialog container its text rows may sit. */
	private static final int OFFER_SCAN_MAX_DEPTH = 5;
	/**
	 * How long a capture stays the "same dialog" for downgrade purposes. A
	 * re-read of the open dialog must never replace a richer capture with a
	 * thinner one; the same two tasks offered again later is a new dialog and
	 * replaces it whatever it carries.
	 */
	private static final long OFFER_REFRESH_WINDOW_MS = 60_000L;
	/** How long captured offers ride along in the state sync before expiring. */
	private static final long OFFER_RETENTION_MS = 30 * 60_000L;
	private static final String STORED_TASK_CONFIG_KEY = "storedSlayerTask";

	/**
	 * Clientscripts that draw the rewards-interface Tasks tab: 328 runs once at
	 * interface setup, 421 re-runs on every var retransmit while it stays open.
	 * Both render the current and stored task from the IF1-IF6 transfer varps,
	 * so firing right after them reads values the server just served.
	 */
	private static final int SLAYER_REWARDS_TASKS_INIT_SCRIPT = 328;
	private static final int SLAYER_REWARDS_TASKS_REDRAW_SCRIPT = 421;

	// Loose on purpose: matches the known completion message variants ("You've
	// completed X tasks in a row...", points and Wilderness forms). The website
	// treats chat as corroboration, so a miss degrades gracefully.
	static final java.util.regex.Pattern COMPLETION_CHAT_PATTERN =
			java.util.regex.Pattern.compile("completed.{0,40}?\\d[\\d,]* tasks?",
					java.util.regex.Pattern.CASE_INSENSITIVE);

	private static final Set<Integer> WATCHED_VARPS = Set.of(
			VarPlayerID.SLAYER_COUNT,
			VarPlayerID.SLAYER_TARGET,
			VarPlayerID.SLAYER_AREA,
			VarPlayerID.SLAYER_COUNT_ORIGINAL,
			VarPlayerID.SLAYER_REWARDS_UNLOCKS,
			VarPlayerID.SLAYER_REWARDS_UNLOCKS1,
			VarPlayerID.SLAYER_REWARDS_UNLOCKS2,
			VarPlayerID.SLAYER_REWARDS_BLOCKED);
	/**
	 * Each slayer master's own block list: one varbit per slot holding a task id (six slots and the
	 * Lumbridge & Draynor elite diary slot; Mortimer two). Turael's list is shared with Spria and Aya.
	 */
	static final Map<String, int[]> MASTER_BLOCK_VARBITS = new LinkedHashMap<>();
	static {
		MASTER_BLOCK_VARBITS.put("Turael", new int[] {VarbitID.SLAYER_BLOCKED_TURAEL_1, VarbitID.SLAYER_BLOCKED_TURAEL_2,
				VarbitID.SLAYER_BLOCKED_TURAEL_3, VarbitID.SLAYER_BLOCKED_TURAEL_4, VarbitID.SLAYER_BLOCKED_TURAEL_5,
				VarbitID.SLAYER_BLOCKED_TURAEL_6, VarbitID.SLAYER_BLOCKED_TURAEL_DIARY});
		MASTER_BLOCK_VARBITS.put("Mazchna", new int[] {VarbitID.SLAYER_BLOCKED_MAZCHNA_1, VarbitID.SLAYER_BLOCKED_MAZCHNA_2,
				VarbitID.SLAYER_BLOCKED_MAZCHNA_3, VarbitID.SLAYER_BLOCKED_MAZCHNA_4, VarbitID.SLAYER_BLOCKED_MAZCHNA_5,
				VarbitID.SLAYER_BLOCKED_MAZCHNA_6, VarbitID.SLAYER_BLOCKED_MAZCHNA_DIARY});
		MASTER_BLOCK_VARBITS.put("Vannaka", new int[] {VarbitID.SLAYER_BLOCKED_VANNAKA_1, VarbitID.SLAYER_BLOCKED_VANNAKA_2,
				VarbitID.SLAYER_BLOCKED_VANNAKA_3, VarbitID.SLAYER_BLOCKED_VANNAKA_4, VarbitID.SLAYER_BLOCKED_VANNAKA_5,
				VarbitID.SLAYER_BLOCKED_VANNAKA_6, VarbitID.SLAYER_BLOCKED_VANNAKA_DIARY});
		MASTER_BLOCK_VARBITS.put("Chaeldar", new int[] {VarbitID.SLAYER_BLOCKED_CHAELDAR_1, VarbitID.SLAYER_BLOCKED_CHAELDAR_2,
				VarbitID.SLAYER_BLOCKED_CHAELDAR_3, VarbitID.SLAYER_BLOCKED_CHAELDAR_4, VarbitID.SLAYER_BLOCKED_CHAELDAR_5,
				VarbitID.SLAYER_BLOCKED_CHAELDAR_6, VarbitID.SLAYER_BLOCKED_CHAELDAR_DIARY});
		MASTER_BLOCK_VARBITS.put("Konar", new int[] {VarbitID.SLAYER_BLOCKED_KONAR_1, VarbitID.SLAYER_BLOCKED_KONAR_2,
				VarbitID.SLAYER_BLOCKED_KONAR_3, VarbitID.SLAYER_BLOCKED_KONAR_4, VarbitID.SLAYER_BLOCKED_KONAR_5,
				VarbitID.SLAYER_BLOCKED_KONAR_6, VarbitID.SLAYER_BLOCKED_KONAR_DIARY});
		MASTER_BLOCK_VARBITS.put("Nieve", new int[] {VarbitID.SLAYER_BLOCKED_NIEVE_1, VarbitID.SLAYER_BLOCKED_NIEVE_2,
				VarbitID.SLAYER_BLOCKED_NIEVE_3, VarbitID.SLAYER_BLOCKED_NIEVE_4, VarbitID.SLAYER_BLOCKED_NIEVE_5,
				VarbitID.SLAYER_BLOCKED_NIEVE_6, VarbitID.SLAYER_BLOCKED_NIEVE_DIARY});
		MASTER_BLOCK_VARBITS.put("Duradel", new int[] {VarbitID.SLAYER_BLOCKED_DURADEL_1, VarbitID.SLAYER_BLOCKED_DURADEL_2,
				VarbitID.SLAYER_BLOCKED_DURADEL_3, VarbitID.SLAYER_BLOCKED_DURADEL_4, VarbitID.SLAYER_BLOCKED_DURADEL_5,
				VarbitID.SLAYER_BLOCKED_DURADEL_6, VarbitID.SLAYER_BLOCKED_DURADEL_DIARY});
		MASTER_BLOCK_VARBITS.put("Krystilia", new int[] {VarbitID.SLAYER_BLOCKED_KRYSTILIA_1, VarbitID.SLAYER_BLOCKED_KRYSTILIA_2,
				VarbitID.SLAYER_BLOCKED_KRYSTILIA_3, VarbitID.SLAYER_BLOCKED_KRYSTILIA_4, VarbitID.SLAYER_BLOCKED_KRYSTILIA_5,
				VarbitID.SLAYER_BLOCKED_KRYSTILIA_6, VarbitID.SLAYER_BLOCKED_KRYSTILIA_DIARY});
		MASTER_BLOCK_VARBITS.put("Mortimer", new int[] {VarbitID.SLAYER_BLOCKED_MORTIMER_1, VarbitID.SLAYER_BLOCKED_MORTIMER_2});
	}

	private static final Set<Integer> WATCHED_VARBITS = watchedVarbits();

	private static Set<Integer> watchedVarbits() {
		Set<Integer> out = new java.util.HashSet<>(Set.of(
				VarbitID.SLAYER_MASTER,
				VarbitID.SLAYER_POINTS,
				VarbitID.SLAYER_TASKS_COMPLETED,
				VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED,
				VarbitID.SLAYER_TARGET_BOSSID));
		for (int[] ids : MASTER_BLOCK_VARBITS.values()) {
			for (int id : ids) {
				out.add(id);
			}
		}
		return java.util.Collections.unmodifiableSet(out);
	}

	// Ordered block list varps: the shared 15 slots (1 + 12 + 2 diary).
	private static final int[] BLOCK_LIST_VARPS = {
			VarPlayerID.SLAYER_REWARDS_BLOCKED,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_2,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_3,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_4,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_5,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_6,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_7,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_8,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_9,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_10,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_11,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_12,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_13,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_DIARY_1,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_DIARY_2};

	/** Snapshot of the slayer varp/varbit state at one read. */
	static class Snapshot {
		Integer taskId;
		String taskName;
		Integer bossTaskId;
		Integer areaId;
		Integer masterId;
		int amountRemaining;
		int amountOriginal;
		int points;
		int streak;
		int wildernessStreak;
		List<Integer> blockList = new ArrayList<>();
		/** Each master's block list (task ids), from the per-master varbits. */
		Map<String, List<Integer>> blockedByMaster = new LinkedHashMap<>();
		Map<String, Integer> unlockBitfields = new LinkedHashMap<>();
		Integer slayerLevel;
		Long slayerXp;

		boolean hasTask() {
			return amountRemaining > 0 && (taskId != null && taskId != 0);
		}

		/**
		 * Task identity, ignoring progress. Boss tasks differ by sublist id.
		 * The master is part of identity: a different master can reassign the
		 * SAME monster in one interaction (task id unchanged, amount and
		 * master varbit updated together), and without the master in the
		 * comparison that transition is invisible: the old row never closes
		 * and the skip goes unrecorded. The same master can never deal the
		 * same monster back-to-back (the current task is excluded from the
		 * assignment pool), so including it costs nothing.
		 */
		boolean sameTask(Snapshot other) {
			return java.util.Objects.equals(taskId, other.taskId)
					&& java.util.Objects.equals(bossTaskId, other.bossTaskId)
					&& java.util.Objects.equals(areaId, other.areaId)
					&& java.util.Objects.equals(masterId, other.masterId);
		}
	}

	/**
	 * The Task Storage slot, captured opportunistically. No persistent varp
	 * carries the banked task: the server holds it and only copies it into the
	 * shared IF4/IF5/IF6 interface-transfer varps (amount, task id, boss
	 * sublist id) while the slayer rewards screen is open. Captures persist to
	 * RSProfile config so the last known slot survives restarts.
	 */
	static class StoredTaskCapture {
		int taskId;
		int amount;
		int bossTaskId;
		String taskName;
		String capturedAt;
	}

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final ScheduledExecutorService executorService;
	private final Gson gson;
	private final ConfigManager configManager;
	private final GameEvents gameEvents;

	private GameState previousGameState = GameState.UNKNOWN;
	private boolean checkPending;
	private int lastReadTick = -1;
	private String lastSyncJson;
	private long lastAmountSyncMs;
	/** The task's kills left in the last sync (-1: none yet). */
	private int lastSyncedAmount = -1;
	private StoredTaskCapture storedTask;
	private List<Map<String, Object>> pendingOffers = new ArrayList<>();
	private List<String> pendingOfferRows;
	private long offersCapturedMs;
	private String lastOffersKey;
	private int lastOfferScanTick = -1;
	private String offersCapturedAt;
	private boolean storedTaskDirty;
	private Snapshot previous;
	/** The tick to send "slayer_fin" at (a task ended), or -1. */
	private int finalizeAt = -1;
	/** A task was closed since the last sync: the next read syncs (the website gets the new state with it). */
	private boolean closed;

	@Inject
	public SlayerMonitor(
			Client client,
			ClientThread clientThread,
			OSRSCompanionConfig config,
			OSRSCompanionApiClient apiClient,
			ScheduledExecutorService executorService,
			Gson gson,
			ConfigManager configManager,
			GameEvents gameEvents) {
		executorService = GuardedExecutor.wrap(GUARD_FEATURE, executorService);
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.executorService = executorService;
		this.gson = gson;
		this.configManager = configManager;
		this.gameEvents = gameEvents;
	}

	public void stop() {
		previousGameState = GameState.UNKNOWN;
		checkPending = false;
		lastReadTick = -1;
		lastSyncJson = null;
		lastAmountSyncMs = 0;
		lastSyncedAmount = -1;
		previous = null;
		finalizeAt = -1;
		closed = false;
		// Drop the in-memory capture but keep the persisted copy: the stored
		// task is only readable while the rewards screen is open, so the last
		// capture is restored on the next login.
		storedTask = null;
		storedTaskDirty = false;
	}

	/** Re-reads and re-pushes slayer state; clears the dedup cache. */
	public void forceSync() {
		Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
			lastSyncJson = null;
			readAndSync(true);
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			GameState newState = event.getGameState();
			if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
				// Slayer varps and DB tables settle shortly after login.
				executorService.schedule(() -> Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
					previous = null; // don't fabricate a transition across sessions
					readAndSync(true);
				}), LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
			} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
				readAndSync(true);
			}
			previousGameState = newState;
		});
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getType() != ChatMessageType.GAMEMESSAGE) {
				return;
			}
			String message = net.runelite.client.util.Text.removeTags(event.getMessage());
			if (COMPLETION_CHAT_PATTERN.matcher(message).find()) {
				gameEvents.add("slayer_chat", "text", message);
				checkPending = true;
			}
		});
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getScriptId() == SLAYER_REWARDS_TASKS_INIT_SCRIPT
					|| event.getScriptId() == SLAYER_REWARDS_TASKS_REDRAW_SCRIPT) {
				captureStoredTask();
			}
		});
	}

	/**
	 * Reads the Task Storage slot from the IF transfer varps. Client thread
	 * only (script events fire there).
	 *
	 * <p>The IF varps are scratch shared by many interfaces (bank, colosseum,
	 * bond conversion, ...). The tasks-tab script also mirrors the ACTIVE task
	 * into IF1/IF2; requiring those to match the live task varps proves the
	 * scratch currently serves the slayer rewards screen.
	 */
	private void captureStoredTask() {
		if (!config.syncSlayer() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (client.getVarpValue(VarPlayerID.IF1) != client.getVarpValue(VarPlayerID.SLAYER_COUNT)
				|| client.getVarpValue(VarPlayerID.IF2)
						!= client.getVarpValue(VarPlayerID.SLAYER_TARGET)) {
			return;
		}
		int amount = client.getVarpValue(VarPlayerID.IF4);
		int taskId = client.getVarpValue(VarPlayerID.IF5);
		int bossTaskId = client.getVarpValue(VarPlayerID.IF6);
		if (!hasStoredTask(taskId, amount)) {
			// Normalize "no stored task" so residual junk in the scratch varps
			// can't masquerade as a banked assignment.
			amount = 0;
			taskId = 0;
			bossTaskId = 0;
		}
		if (storedTask != null && storedTask.taskId == taskId
				&& storedTask.amount == amount && storedTask.bossTaskId == bossTaskId) {
			return; // unchanged; keep the original capture timestamp
		}
		StoredTaskCapture capture = new StoredTaskCapture();
		capture.taskId = taskId;
		capture.amount = amount;
		capture.bossTaskId = bossTaskId;
		capture.taskName = taskId == 0
				? null
				: resolveTaskName(taskId, bossTaskId == 0 ? null : bossTaskId);
		capture.capturedAt = Instant.now().toString();
		storedTask = capture;
		storedTaskDirty = true;
		configManager.setRSProfileConfiguration(
				"osrscompanion", STORED_TASK_CONFIG_KEY, gson.toJson(capture));
		checkPending = true;
	}

	/**
	 * Whether the raw IF varp pair encodes a banked task, mirroring the render
	 * conditions in the rewards-interface script (which draws name-only for a
	 * boss slot with amount -1).
	 */
	static boolean hasStoredTask(int taskId, int amount) {
		if (taskId == BOSS_TASK_SENTINEL) {
			return amount > 0 || amount == -1;
		}
		return taskId != 0 && amount > 0;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getVarpId() == VarPlayerID.SLAYER_COUNT) {
				// Kills (the count going down), for the time spent on the task.
				gameEvents.add("slayer_count", "v", event.getValue());
			}
			if (WATCHED_VARPS.contains(event.getVarpId())
					|| WATCHED_VARBITS.contains(event.getVarbitId())) {
				checkPending = true;
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (lastOfferScanTick == -1
					|| client.getTickCount() - lastOfferScanTick >= OFFER_SCAN_INTERVAL_TICKS) {
				lastOfferScanTick = client.getTickCount();
				scanForTaskOffers();
			}
			if (finalizeAt >= 0 && client.getTickCount() >= finalizeAt) {
				finalizeAt = -1;
				closed = true;
				gameEvents.add("slayer_fin");
				readAndSync(false);
				return;
			}
			if (!checkPending) {
				return;
			}
			if (lastReadTick != -1 && client.getTickCount() - lastReadTick < RESYNC_THROTTLE_TICKS) {
				return;
			}
			checkPending = false;
			lastReadTick = client.getTickCount();
			readAndSync(false);
		});
	}

	/** Reads the snapshot, detects transitions, and syncs. Client thread only. */
	private void readAndSync(boolean force) {
		if (!config.syncSlayer() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}

		Snapshot now = readSnapshot();
		// First read of the session: the last stored-task capture unless a live capture already replaced it.
		if (previous == null && storedTask == null) {
			restoreStoredTask();
		}
		// For the website (task outcomes and times). first: the first read of a session (no transition from
		// the one before); force: logout or a forced sync (a task that ended is closed at once).
		gameEvents.add("slayer", "first", previous == null, "force", force, "task", now.taskId, "boss", now.bossTaskId,
				"area", now.areaId, "master", now.masterId, "name", now.taskName, "left", now.amountRemaining,
				"orig", now.amountOriginal, "pts", now.points, "st", now.streak, "wst", now.wildernessStreak,
				"blk", now.blockList, "bm", now.blockedByMaster);
		// A forced read closes a task that ended at once; a second task ending closes the first.
		closed |= force && finalizeAt >= 0;
		if (previous != null && previous.hasTask() && (!now.hasTask() || !now.sameTask(previous))) {
			closed |= finalizeAt >= 0;
			finalizeAt = client.getTickCount() + EVENT_FINALIZE_DELAY_TICKS;
		}
		boolean taskChanged = previous == null
				|| previous.hasTask() != now.hasTask()
				|| (now.hasTask() && !now.sameTask(previous));
		// A kill on the task (its count went down): sent at once, outside the amount window.
		boolean killed = !taskChanged && previous != null && now.hasTask() && now.amountRemaining < previous.amountRemaining;
		previous = now;
		if (finalizeAt >= 0) {
			if (!force) {
				// The task's end is still open; the sync goes with its closing in a moment.
				return;
			}
			closed = true;
			finalizeAt = -1;
		}

		boolean amountWindowOpen =
				System.currentTimeMillis() - lastAmountSyncMs >= AMOUNT_SYNC_MIN_INTERVAL_MS;
		if (!force && !taskChanged && !killed && !closed && !amountWindowOpen
				&& !storedTaskDirty) {
			// A kill inside the window: look again on a later tick, so it goes up once the window
			// closes instead of waiting for the next kill.
			if (now.amountRemaining != lastSyncedAmount) {
				checkPending = true;
			}
			return;
		}

		SlayerSyncPayload payload = buildPayload(playerUsername, now);
		storedTaskDirty = false;
		String json = payload.toJson(gson);
		if (json.equals(lastSyncJson) && !closed) {
			return;
		}
		lastSyncJson = closed ? null : json;
		closed = false;
		lastAmountSyncMs = System.currentTimeMillis();
		lastSyncedAmount = now.amountRemaining;

		apiClient.sendSlayerSync(payload, killed);
	}

	private Snapshot readSnapshot() {
		Snapshot s = new Snapshot();
		int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		s.taskId = taskId == 0 ? null : taskId;
		s.amountRemaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		s.amountOriginal = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
		int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
		s.areaId = areaId == 0 ? null : areaId;
		int bossId = client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID);
		s.bossTaskId = bossId == 0 ? null : bossId;
		int masterId = client.getVarbitValue(VarbitID.SLAYER_MASTER);
		s.masterId = masterId == 0 ? null : masterId;
		if (taskId != 0 && s.amountRemaining > 0) {
			s.amountOriginal = originalAmount(taskId, bossId, areaId, masterId, s.amountOriginal, s.amountRemaining);
		}
		s.points = client.getVarbitValue(VarbitID.SLAYER_POINTS);
		s.streak = client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
		s.wildernessStreak = client.getVarbitValue(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED);
		for (int varp : BLOCK_LIST_VARPS) {
			s.blockList.add(client.getVarpValue(varp));
		}
		for (Map.Entry<String, int[]> e : MASTER_BLOCK_VARBITS.entrySet()) {
			List<Integer> ids = new ArrayList<>();
			for (int varbit : e.getValue()) {
				int id = client.getVarbitValue(varbit);
				if (id != 0) {
					ids.add(id);
				}
			}
			s.blockedByMaster.put(e.getKey(), ids);
		}
		s.unlockBitfields.put(String.valueOf(VarPlayerID.SLAYER_REWARDS_UNLOCKS),
				client.getVarpValue(VarPlayerID.SLAYER_REWARDS_UNLOCKS));
		s.unlockBitfields.put(String.valueOf(VarPlayerID.SLAYER_REWARDS_UNLOCKS1),
				client.getVarpValue(VarPlayerID.SLAYER_REWARDS_UNLOCKS1));
		s.unlockBitfields.put(String.valueOf(VarPlayerID.SLAYER_REWARDS_UNLOCKS2),
				client.getVarpValue(VarPlayerID.SLAYER_REWARDS_UNLOCKS2));
		s.slayerLevel = client.getRealSkillLevel(Skill.SLAYER);
		s.slayerXp = (long) client.getSkillExperience(Skill.SLAYER);
		if (s.hasTask()) {
			s.taskName = resolveTaskName(s.taskId, s.bossTaskId);
		}
		return s;
	}

	private static final String TASK_SIZE_CONFIG_KEY = "slayerTaskSize";
	private final TaskSize taskSize = new TaskSize();
	private boolean taskSizeLoaded;

	/**
	 * The task's size as the plugin sends it (amount_original): SLAYER_COUNT_ORIGINAL, but never below the
	 * highest count seen on this assignment. The varp can be below the count: Mortimer's quantity modifier
	 * adds kills on top of the amount rolled (the varp keeps the roll), and the varp can still hold an
	 * earlier task's size. Below the count, killed (size - count) went negative on the website. Kept per
	 * RuneScape profile, so a restart mid-task keeps the size. Client thread only.
	 */
	synchronized int originalAmount(int taskId, int bossId, int areaId, int masterId, int varpOriginal, int remaining) {
		if (!taskSizeLoaded) {
			taskSizeLoaded = true;
			taskSize.restore(configManager.getRSProfileConfiguration("osrscompanion", TASK_SIZE_CONFIG_KEY));
		}
		String before = taskSize.stored();
		int size = taskSize.update(taskId + ":" + bossId + ":" + areaId + ":" + masterId + ":" + varpOriginal, varpOriginal, remaining);
		String after = taskSize.stored();
		if (after != null && !after.equals(before)) {
			configManager.setRSProfileConfiguration("osrscompanion", TASK_SIZE_CONFIG_KEY, after);
		}
		return size;
	}

	/**
	 * The highest task count seen on one assignment (task, boss, area, master and the varp's size: a new
	 * assignment changes one of them). No game access.
	 */
	static final class TaskSize {
		private String key;
		private int peak;

		/** The size for this read: the larger of the varp's size and the highest count seen on the assignment. */
		int update(String assignment, int varpOriginal, int remaining) {
			if (remaining <= 0) {
				return Math.max(0, varpOriginal);
			}
			if (!assignment.equals(key)) {
				key = assignment;
				peak = 0;
			}
			peak = Math.max(peak, remaining);
			return Math.max(varpOriginal, peak);
		}

		/** "assignment|peak" for the config, or null before the first task. */
		String stored() {
			return key == null ? null : key + "|" + peak;
		}

		void restore(String stored) {
			if (stored == null) {
				return;
			}
			int bar = stored.lastIndexOf('|');
			if (bar <= 0) {
				return;
			}
			try {
				peak = Integer.parseInt(stored.substring(bar + 1));
				key = stored.substring(0, bar);
			} catch (NumberFormatException e) {
				key = null;
				peak = 0;
			}
		}
	}

	/**
	 * The task's display name ("Abyssal demons"), or null when it can't be read; for the Slayer
	 * helper, which reads the task varps itself. Client thread only.
	 */
	String taskName(int taskId, Integer bossTaskId) {
		return resolveTaskName(taskId, bossTaskId);
	}

	/**
	 * Resolves the task display name via the game-cache DB tables, handling the
	 * boss-task sentinel. Adapted from RuneLite SlayerPlugin.updateTask().
	 */
	private String resolveTaskName(int taskId, Integer bossTaskId) {
		try {
			int taskDbRow;
			if (taskId == BOSS_TASK_SENTINEL && bossTaskId != null) {
				List<Integer> bossRows = client.getDBRowsByValue(
						DBTableID.SlayerTaskSublist.ID,
						DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID,
						0,
						bossTaskId);
				if (bossRows.isEmpty()) {
					return null;
				}
				taskDbRow = (Integer) client.getDBTableField(
						bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
			} else {
				List<Integer> taskRows = client.getDBRowsByValue(
						DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
				if (taskRows.isEmpty()) {
					return null;
				}
				taskDbRow = taskRows.get(0);
			}
			return (String) client.getDBTableField(
					taskDbRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
		} catch (RuntimeException e) {
			return null;
		}
	}


	/** Reads the task-offer dialog if it is open. Client thread only. */
	private void scanForTaskOffers() {
		if (!config.syncSlayer() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		if (SyncGuard.getPlayerUsername(client) == null) {
			return;
		}
		Widget container = client.getWidget(TASK_OFFER_GROUP, TASK_OFFER_CHILD);
		if (container == null) {
			return;
		}
		List<OfferRow> rows = new ArrayList<>();
		collectRows(container, 0, rows);
		if (rows.isEmpty()) {
			return;
		}
		List<String> texts = readingOrder(rows);
		List<Map<String, Object>> offers = parseTaskOffers(texts);
		if (!offers.isEmpty()) {
			acceptOffers(offers, texts);
		}
	}

	/**
	 * Every text-bearing widget under the dialog, whichever child list holds
	 * it. The static, dynamic and nested lists are all walked because the
	 * modifier row is not in the same one as the name and amount rows.
	 */
	private static void collectRows(Widget widget, int depth, List<OfferRow> rows) {
		if (widget == null || depth > OFFER_SCAN_MAX_DEPTH) {
			return;
		}
		String text = widget.getText();
		if (text != null && !text.isEmpty()) {
			Point at = widget.getCanvasLocation();
			rows.add(at == null
					? new OfferRow(text, null, null)
					: new OfferRow(text, at.getX(), at.getY()));
		}
		for (Widget[] set : new Widget[][] {
				widget.getStaticChildren(), widget.getDynamicChildren(),
				widget.getNestedChildren()}) {
			if (set == null) {
				continue;
			}
			for (Widget child : set) {
				collectRows(child, depth + 1, rows);
			}
		}
	}

	/** One text row of the offer dialog, with where on screen it drew. */
	static final class OfferRow {
		final String text;
		final Integer x;
		final Integer y;

		OfferRow(String text, Integer x, Integer y) {
			this.text = text;
			this.x = x;
			this.y = y;
		}

		boolean positioned() {
			return x != null && y != null && y >= 0;
		}
	}

	/**
	 * The rows top to bottom as the player reads them. The dialog is a
	 * vertical list, so screen position restores the reading order even when
	 * the rows are gathered from different child lists, which walk order
	 * groups rather than interleaves. Positions are only trusted when every
	 * row has one; otherwise walk order stands.
	 */
	static List<String> readingOrder(List<OfferRow> rows) {
		List<OfferRow> ordered = new ArrayList<>(rows);
		boolean positioned = true;
		for (OfferRow row : rows) {
			positioned &= row.positioned();
		}
		if (positioned) {
			ordered.sort(Comparator.comparingInt((OfferRow row) -> row.y)
					.thenComparingInt(row -> row.x));
		}
		List<String> texts = new ArrayList<>(ordered.size());
		for (OfferRow row : ordered) {
			texts.add(row.text);
		}
		return texts;
	}

	/** Stores newly seen offers and ships them with the next state sync. */
	private void acceptOffers(List<Map<String, Object>> offers, List<String> rows) {
		String key = gson.toJson(offers);
		if (key.equals(lastOffersKey)) {
			return; // same dialog still open
		}
		if (isThinnerReread(offers)) {
			return;
		}
		lastOffersKey = key;
		pendingOffers = offers;
		// The rows only ride along when a modifier is missing: they are what
		// says where the missing row actually lives, without a release to ask.
		pendingOfferRows = modifierCount(offers) < offers.size() ? rows : null;
		offersCapturedMs = System.currentTimeMillis();
		offersCapturedAt = Instant.now().toString();
		readAndSync(true);
	}

	/**
	 * Whether this read is the dialog already captured, seen with fewer
	 * modifiers than last time. A row that populates late (or blanks as the
	 * dialog closes) makes the same dialog read differently on either side of
	 * it, and the fuller read is the true one.
	 */
	private boolean isThinnerReread(List<Map<String, Object>> offers) {
		if (pendingOffers.isEmpty()
				|| System.currentTimeMillis() - offersCapturedMs > OFFER_REFRESH_WINDOW_MS) {
			return false;
		}
		return offerNames(offers).equals(offerNames(pendingOffers))
				&& modifierCount(offers) < modifierCount(pendingOffers);
	}

	private static List<Object> offerNames(List<Map<String, Object>> offers) {
		List<Object> names = new ArrayList<>(offers.size());
		for (Map<String, Object> offer : offers) {
			names.add(offer.get("name"));
		}
		return names;
	}

	private static int modifierCount(List<Map<String, Object>> offers) {
		int count = 0;
		for (Map<String, Object> offer : offers) {
			if (offer.containsKey("modifier_text")) {
				count++;
			}
		}
		return count;
	}

	/** "Amount: 80 to 120", or a single "Amount: 48". */
	static final java.util.regex.Pattern AMOUNT_ROW = java.util.regex.Pattern.compile(
			"^Amount:\\s*(\\d+)(?:\\s*to\\s*(\\d+))?\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);
	/**
	 * "+15 Slayer points", "+30% Slayer XP", "+100 Assigned", "x2 clue
	 * scrolls", "-20 Assigned". Modifiers subtract as well as add, so the sign
	 * is part of the value: a "-20 Assigned" task asks for 20 fewer kills than
	 * the amount rolled, and reading it as +20 would have it ask for 40 more.
	 */
	static final java.util.regex.Pattern MODIFIER_ROW = java.util.regex.Pattern.compile(
			"^([+\\-x])?\\s*(\\d+)(%?)\\s+(.+?)\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);
	/** How many rows either side of an amount row still belong to that offer. */
	private static final int OFFER_BLOCK_SPAN = 6;

	private static String plain(List<String> texts, int index) {
		if (index < 0 || index >= texts.size() || texts.get(index) == null) {
			return "";
		}
		return net.runelite.client.util.Text.removeTags(texts.get(index)).trim();
	}

	/**
	 * Parses the offer dialog's rows, anchored on each "Amount: X to Y" row:
	 * the name is the row before it and the modifier the row after, with blank
	 * rows skipped on both sides. The rows are only consecutive once the
	 * dialog's untexted widgets are stepped over, which is why this searches
	 * rather than indexes.
	 *
	 * <p>The modifier row is kept verbatim as modifier_text even when it does
	 * not parse, so a wording this pattern misses still reaches the server.
	 * Package-private for tests.
	 */
	static List<Map<String, Object>> parseTaskOffers(List<String> texts) {
		List<Map<String, Object>> offers = new ArrayList<>();
		for (int i = 0; i < texts.size(); i++) {
			java.util.regex.Matcher amount = AMOUNT_ROW.matcher(plain(texts, i));
			if (!amount.matches()) {
				continue;
			}
			String name = nameBefore(texts, i);
			if (name == null) {
				continue;
			}
			Map<String, Object> offer = new LinkedHashMap<>();
			offer.put("name", name);
			int min = Integer.parseInt(amount.group(1));
			offer.put("amount_min", min);
			offer.put("amount_max",
					amount.group(2) == null ? min : Integer.parseInt(amount.group(2)));

			String modifier = modifierAfter(texts, i);
			if (modifier != null) {
				offer.put("modifier_text", modifier);
				java.util.regex.Matcher mod = MODIFIER_ROW.matcher(modifier);
				if (mod.matches()) {
					String sign = mod.group(1) == null ? "" : mod.group(1);
					int value = Integer.parseInt(mod.group(2));
					offer.put("modifier_value", "-".equals(sign) ? -value : value);
					offer.put("modifier_is_percent", !mod.group(3).isEmpty());
					offer.put("modifier_multiplies", "x".equalsIgnoreCase(sign));
					offer.put("modifier_label", mod.group(4));
				}
			}
			offers.add(offer);
		}
		return offers;
	}

	/** The task name an amount row belongs to: the row above it, blanks aside. */
	private static String nameBefore(List<String> texts, int amountIndex) {
		for (int i = amountIndex - 1;
				i >= 0 && i >= amountIndex - OFFER_BLOCK_SPAN; i--) {
			String row = plain(texts, i);
			if (row.isEmpty() || MODIFIER_ROW.matcher(row).matches()) {
				continue;
			}
			return AMOUNT_ROW.matcher(row).matches() ? null : row;
		}
		return null;
	}

	/**
	 * The modifier an amount row belongs to: the first row below it, blanks
	 * aside. The next offer's name sits in that same gap, so a row butting up
	 * against the next amount row is that name and not this offer's modifier.
	 */
	private static String modifierAfter(List<String> texts, int amountIndex) {
		List<String> block = new ArrayList<>();
		boolean nextOfferReached = false;
		for (int i = amountIndex + 1;
				i < texts.size() && i <= amountIndex + OFFER_BLOCK_SPAN; i++) {
			String row = plain(texts, i);
			if (row.isEmpty()) {
				continue;
			}
			if (AMOUNT_ROW.matcher(row).matches()) {
				nextOfferReached = true;
				break;
			}
			block.add(row);
		}
		if (nextOfferReached && !block.isEmpty()) {
			block.remove(block.size() - 1);
		}
		return block.isEmpty() ? null : block.get(0);
	}

	private SlayerSyncPayload buildPayload(String playerUsername, Snapshot s) {
		SlayerSyncPayload.State state = new SlayerSyncPayload.State(
				s.taskId,
				s.taskName,
				s.bossTaskId,
				s.areaId,
				s.masterId,
				s.amountRemaining,
				s.amountOriginal,
				s.points,
				s.streak,
				s.wildernessStreak,
				s.blockList,
				s.unlockBitfields,
				buildExtra(s),
				s.slayerLevel,
				s.slayerXp,
				Instant.now().toString());
		return new SlayerSyncPayload(playerUsername, state);
	}

	/** Assembles the state extra map: stored task, each master's block list and any recent task offers. */
	private Map<String, Object> buildExtra(Snapshot s) {
		Map<String, Object> extra = storedTaskExtra(storedTask);
		extra.put("blocked_by_master", s.blockedByMaster);
		if (!pendingOffers.isEmpty()
				&& System.currentTimeMillis() - offersCapturedMs <= OFFER_RETENTION_MS) {
			extra.put("task_offers", pendingOffers);
			extra.put("task_offers_captured_at", offersCapturedAt);
			if (pendingOfferRows != null) {
				extra.put("task_offer_rows", pendingOfferRows);
			}
		}
		return extra;
	}

	/**
	 * The extra-map entries for the captured Task Storage slot. Empty until a
	 * capture exists; zeros mean "capture confirmed the storage is empty",
	 * which the backend distinguishes from never-captured.
	 */
	static Map<String, Object> storedTaskExtra(StoredTaskCapture capture) {
		Map<String, Object> extra = new LinkedHashMap<>();
		if (capture == null) {
			return extra;
		}
		extra.put("stored_task_id", capture.taskId);
		extra.put("stored_task_amount", capture.amount);
		extra.put("stored_boss_task_id", capture.bossTaskId);
		if (capture.taskName != null) {
			extra.put("stored_task_name", capture.taskName);
		}
		extra.put("stored_task_captured_at", capture.capturedAt);
		return extra;
	}

	/** Reloads the last stored-task capture so restarts keep the known slot. */
	private void restoreStoredTask() {
		String stored = configManager.getRSProfileConfiguration("osrscompanion", STORED_TASK_CONFIG_KEY);
		if (stored == null || stored.isEmpty()) {
			return;
		}
		try {
			StoredTaskCapture parsed = gson.fromJson(stored, StoredTaskCapture.class);
			if (parsed != null && parsed.capturedAt != null) {
				storedTask = parsed;
			}
		} catch (RuntimeException e) {
			configManager.unsetRSProfileConfiguration("osrscompanion", STORED_TASK_CONFIG_KEY);
		}
	}
}
