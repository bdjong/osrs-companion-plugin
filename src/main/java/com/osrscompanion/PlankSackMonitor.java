package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.IntUnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.Subscribe;

/**
 * Tracks the plank sack from its per-plank varbits (exact, no interface
 * needed), feeding the goal tracker live and uploading bank-memory source
 * {@code plank_sack} through a debounced {@link SourceSyncer}.
 */
@Slf4j
@Singleton
public class PlankSackMonitor {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "plank-sack-sync";

	static final String SOURCE = "plank_sack";

	/** Varbit to the plank item it counts. */
	static final Map<Integer, Integer> PLANK_VARBITS;

	static {
		Map<Integer, Integer> m = new LinkedHashMap<>();
		Tables.list("plankSack").forEach(p -> m.put(Tables.id(p.getAsJsonArray().get(0)), Tables.id(p.getAsJsonArray().get(1))));
		PLANK_VARBITS = java.util.Collections.unmodifiableMap(m);
	}

	private final Client client;
	private final SourceSyncer syncer;

	private boolean dirty;
	private GameState previousGameState = GameState.UNKNOWN;

	@Inject
	public PlankSackMonitor(
			Client client,
			OSRSCompanionConfig config,
			OSRSCompanionApiClient apiClient,
			Gson gson,
			ScheduledExecutorService executor) {
		executor = GuardedExecutor.wrap(GUARD_FEATURE, executor);
		this.client = client;
		this.syncer = new SourceSyncer(SOURCE, gson, executor,
				() -> config.syncBankMemory() && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client),
				() -> SyncGuard.getPlayerUsername(client),
				apiClient::sendBankSync);
		this.syncer.setPriorityFlush(apiClient::requestOutboxFlush);
	}

	public void stop() {
		syncer.stop();
		dirty = false;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (PLANK_VARBITS.containsKey(event.getVarbitId())) {
				dirty = true;
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			GameState newState = event.getGameState();
			if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
				dirty = true;
			} else if (SyncGuard.isLogout(previousGameState, newState)) {
				syncer.flushPending();
			}
			previousGameState = newState;
		});
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (!dirty) {
				return;
			}
			dirty = false;
			Map<Integer, Integer> contents = contents(client::getVarbitValue);
			syncer.submit(contents, false);
		});
	}

	/** Plank item id to count from the varbit values. */
	static Map<Integer, Integer> contents(IntUnaryOperator varbitValue) {
		Map<Integer, Integer> contents = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> e : PLANK_VARBITS.entrySet()) {
			int count = varbitValue.applyAsInt(e.getKey());
			if (count > 0) {
				contents.put(e.getValue(), count);
			}
		}
		return contents;
	}
}
