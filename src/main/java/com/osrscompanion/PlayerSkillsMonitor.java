package com.osrscompanion;

import lombok.Getter;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.PlayerSkillsSyncPayload;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Uploads skill levels and XP: on login, on logout, and while training (at most
 * every {@link #TRAINING_SYNC_INTERVAL_SECONDS} while XP is actually rising) so
 * the server can keep roadmap skill goals current without waiting for logout.
 */
@Slf4j
@Singleton
public class PlayerSkillsMonitor {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "skills-sync";

	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	/** How often to re-upload while training; nothing is sent when XP is static. */
	static final int TRAINING_SYNC_INTERVAL_SECONDS = 60;

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final ScheduledExecutorService executorService;

	private GameState previousGameState = GameState.UNKNOWN;
	/** Last XP seen per skill (client thread); the first value after login is the replay. */
	private final Map<String, Integer> lastSeenXp = new HashMap<>();
	private volatile boolean xpDirty;
	private ScheduledFuture<?> trainingTask;

	@Inject
	public PlayerSkillsMonitor(
			Client client,
			ClientThread clientThread,
			OSRSCompanionConfig config,
			OSRSCompanionApiClient apiClient,
			ScheduledExecutorService executorService) {
		executorService = GuardedExecutor.wrap(GUARD_FEATURE, executorService);
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.executorService = executorService;
	}

	public void start() {
		if (trainingTask == null) {
			trainingTask = executorService.scheduleAtFixedRate(this::syncIfDirty,
					TRAINING_SYNC_INTERVAL_SECONDS, TRAINING_SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);
		}
	}

	public void stop() {
		if (trainingTask != null) {
			trainingTask.cancel(false);
			trainingTask = null;
		}
		previousGameState = GameState.UNKNOWN;
		lastSeenXp.clear();
		xpDirty = false;
	}

	/** Re-runs the skills sync now (App Key entered while logged in, or the roadmap's "Sync &amp; refresh"). */
	public void forceSync() {
		syncOnClientThread();
	}

	/** The game is read on the client thread only: RuneLite asserts it for varbits and the local player. */
	private void syncOnClientThread() {
		Guard.invokeLater(clientThread, GUARD_FEATURE, this::syncPlayerSkills);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			GameState newState = event.getGameState();

			if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
				log.debug("Player logged in, scheduling skills sync in {}s", LOGIN_SYNC_DELAY_SECONDS);
				executorService.schedule(this::syncOnClientThread, LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
			} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
				log.debug("Player logged out, syncing skills");
				lastSeenXp.clear();
				xpDirty = false;
				syncPlayerSkills();
			}

			previousGameState = newState;
		});
	}

	@Subscribe
	public void onStatChanged(StatChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			Integer previous = lastSeenXp.put(event.getSkill().getName(), event.getXp());
			if (xpIncreased(previous, event.getXp())) {
				xpDirty = true;
			}
		});
	}

	/** True only for a real gain: the first observation after login is the server
	 * replay, and boosted-level events carry the same XP. */
	static boolean xpIncreased(Integer previous, int now) {
		return previous != null && now > previous;
	}

	private void syncIfDirty() {
		if (!xpDirty || client.getGameState() != GameState.LOGGED_IN) {
			return;
		}
		xpDirty = false;
		syncOnClientThread();
	}

	/**
	 * Reads all skill levels and XP from the client, builds a payload with
	 * total and combat levels, and sends it to the OSRS Companion API. Client thread.
	 */
	private void syncPlayerSkills() {
		if (!SyncGuard.hasAppKey(config)) {
			log.debug("Player skills sync skipped: no App Key configured");
			return;
		}
		if (!config.syncSkills()) {
			log.debug("Player skills sync skipped: Skills & XP sync is off");
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			log.debug("Player skills sync skipped: special game mode detected (Leagues, DMM, etc.)");
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			log.warn("Player skills sync skipped: could not get player username");
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null) {
			return;
		}
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		int totalLevel = 0;

		for (Skill skill : Skill.values()) {
			int level = client.getRealSkillLevel(skill);
			int xp = client.getSkillExperience(skill);
			skills.put(skill.getName(), new PlayerSkillsSyncPayload.SkillData(level, xp));
			totalLevel += level;
		}

		int combatLevel = localPlayer.getCombatLevel();

		// Account type (Group Ironman helmet on the website): varbit IRONMAN, sent only when it names an iron type.
		Integer accountType = PlayerSkillsSyncPayload.accountTypeOf(client.getVarbitValue(VarbitID.IRONMAN));
		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload(playerUsername, skills, totalLevel, combatLevel, accountType);
		log.debug("Syncing {} skills for player: {} (Total Level: {}, Combat Level: {})",
				skills.size(), playerUsername, totalLevel, combatLevel);
		apiClient.sendPlayerSkillsSync(payload);
	}
}
