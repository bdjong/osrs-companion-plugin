package com.osrscompanion;

import com.osrscompanion.api.OSRSCompanionApiClient;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Tells the website when a play session starts (login) and ends (logout), with a ping
 * every few minutes in between so a client closed without logging out still ends the
 * session at the last ping. World hops and reconnects stay in the same session.
 */
@Slf4j
@Singleton
public class SessionMonitor {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "play-sessions";

	static final int PING_INTERVAL_MINUTES = 5;

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final ScheduledExecutorService executorService;

	/** Name of the player the open session belongs to; null when logged out. */
	private volatile String player;
	private ScheduledFuture<?> pingTask;

	@Inject
	public SessionMonitor(Client client, ClientThread clientThread, OSRSCompanionConfig config,
			OSRSCompanionApiClient apiClient, ScheduledExecutorService executorService) {
		executorService = GuardedExecutor.wrap(GUARD_FEATURE, executorService);
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.executorService = executorService;
	}

	public void start() {
		pingTask = executorService.scheduleAtFixedRate(this::ping, PING_INTERVAL_MINUTES, PING_INTERVAL_MINUTES, TimeUnit.MINUTES);
		// Enabled (or rebuilt) while already in game: that is a session too.
		Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
			if (client.getGameState() == GameState.LOGGED_IN) {
				begin();
			}
		});
	}

	public void stop() {
		if (pingTask != null) {
			pingTask.cancel(false);
			pingTask = null;
		}
		finish();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			GameState state = event.getGameState();
			if (state == GameState.LOGGED_IN && player == null) {
				begin();
			} else if (state == GameState.LOGIN_SCREEN && player != null) {
				// Only the login screen ends a session: HOPPING, LOADING and CONNECTION_LOST come back.
				finish();
			}
		});
	}

	/** Waits (tick by tick) until the player's name is known, then reports the login. Client thread. */
	private void begin() {
		Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
			if (client.getGameState() != GameState.LOGGED_IN) {
				return true;
			}
			String name = SyncGuard.getPlayerUsername(client);
			if (name == null) {
				return false;
			}
			if (player == null && reportable()) {
				player = name;
				send(name, "start");
			}
			return true;
		});
	}

	private void finish() {
		String name = player;
		player = null;
		if (name != null && reportable()) {
			send(name, "end");
		}
	}

	private void ping() {
		String name = player;
		if (name != null && reportable() && client.getGameState() == GameState.LOGGED_IN) {
			send(name, "ping");
		}
	}

	private boolean reportable() {
		return SyncGuard.hasAppKey(config) && config.syncSessions() && !GameModeUtil.isSpecialGameMode(client);
	}

	private void send(String name, String event) {
		log.debug("Play session {} for {}", event, name);
		apiClient.sendSessionEvent(name, event, Instant.now().toString());
	}
}
