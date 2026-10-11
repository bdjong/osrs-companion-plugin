package com.osrscompanion;

import lombok.Getter;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapList;
import com.osrscompanion.model.RoadmapSummary;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;

/**
 * Shared roadmap state for the side panel and the overlay.
 *
 * <p>Holds the currently selected roadmap id (persisted per RuneLite profile via
 * {@link ConfigManager}) and the last fetched {@link Roadmap}, so the panel and
 * the overlay render the same data without each issuing their own requests. All
 * mutation happens through here; the panel drives fetches, the overlay reads the
 * cached roadmap.
 *
 * <p>Also owns the background reconciliation: a periodic re-read of every one
 * of the player's roadmaps plus debounced re-reads requested shortly after data
 * uploads, so the server's view of goal progress reaches the plugin without the
 * panel open. Every fetched roadmap is handed to the roadmap listener (the goal
 * tracker); only the selected one becomes the overlay's current roadmap.
 *
 * <p>The roadmap's background work runs on its own small daemon thread ({@link #executor()}), never
 * on RuneLite's shared executor: every plugin's scheduled jobs share that one thread, so a slow
 * roadmap pass there (a "Slow: roadmap took 4667 ms on pool-2-thread-1") held all of them up. The
 * thread exists only while roadmap work is scheduled.
 */
@Slf4j
@Singleton
public class RoadmapManager {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "roadmap";

	static final String CONFIG_GROUP = "osrscompanion";
	static final String SELECTED_ROADMAP_KEY = "selectedRoadmapId";
	static final String COMPLETED_EXPANDED_KEY = "completedGoalsExpanded";
	static final String THREAD_NAME = "osrs-companion-roadmap";
	/** The roadmap thread ends after this long without work. */
	static final long IDLE_SECONDS = 30;

	private final Client client;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executor;
	private final ReconcileScheduler scheduler;
	private final RoadmapListCache listCache = new RoadmapListCache();

	/** The last fetched roadmap (may be null), used by the overlay. */
	@Getter
	private volatile Roadmap currentRoadmap;
	/** Hears every fetched roadmap, selected or not; set by the plugin. */
	private volatile Consumer<Roadmap> roadmapListener;
	/** Hears the player's current set of roadmap ids after each list fetch. */
	private volatile Consumer<Set<Integer>> roadmapSetListener;
	/** Hears roadmaps that arrived from a background refresh; set by the open panel. */
	private volatile Consumer<Roadmap> panelListener;
	private volatile boolean panelActive;

	/**
	 * How often {@link #pollWhileWaiting()} looks for a roadmap while the box has none: a backstop, as
	 * a roadmap made on the website is picked up by the web version check ({@link WebVersionWatcher}); this
	 * also retries a load that failed at login.
	 */
	static final int WAITING_POLL_SECONDS = 2 * 60;

	@Inject
	public RoadmapManager(
			Client client,
			OSRSCompanionConfig config,
			OSRSCompanionApiClient apiClient,
			ConfigManager configManager) {
		this.executor = GuardedExecutor.wrap(GUARD_FEATURE, newOwnExecutor());
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.scheduler = new ReconcileScheduler(executor, this::refreshAllQuietly);
	}

	/**
	 * The roadmap's own executor: one daemon thread, started on demand and ended after
	 * {@value #IDLE_SECONDS} s without work, so nothing is left running once the plugin stops (its
	 * periodic jobs are cancelled then). Its tasks are run through the {@link Guard}.
	 */
	ScheduledExecutorService executor() {
		return executor;
	}

	static ScheduledThreadPoolExecutor newOwnExecutor() {
		ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, THREAD_NAME);
			t.setDaemon(true);
			t.setUncaughtExceptionHandler((th, e) -> log.debug("Roadmap thread failed", e));
			return t;
		});
		ex.setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS);
		ex.allowCoreThreadTimeOut(true);
		ex.setRemoveOnCancelPolicy(true);
		return ex;
	}

	/** The logged-in RS username, or null if not logged in. */
	public String getPlayerUsername() {
		return SyncGuard.getPlayerUsername(client);
	}

	public boolean hasAppKey() {
		return SyncGuard.hasAppKey(config);
	}

	void setCurrentRoadmap(Roadmap roadmap) {
		this.currentRoadmap = roadmap;
	}

	void clear() {
		setCurrentRoadmap(null);
		listCache.invalidate();
	}

	public void setRoadmapListener(Consumer<Roadmap> listener) {
		this.roadmapListener = listener;
	}

	public void setRoadmapSetListener(Consumer<Set<Integer>> listener) {
		this.roadmapSetListener = listener;
	}

	/** Every fetched roadmap goes to the tracker; the selected one also becomes current. */
	private void onRoadmapFetched(Roadmap roadmap) {
		Consumer<Roadmap> listener = roadmapListener;
		if (listener != null) {
			listener.accept(roadmap);
		}
		Integer selected = getSelectedRoadmapId();
		if (selected != null && selected == roadmap.getCollectionId()) {
			setCurrentRoadmap(roadmap);
		}
	}

	/**
	 * Makes a roadmap the active one (a goal in it just completed): persists the
	 * selection, swaps the overlay's roadmap and lets an open panel follow.
	 */
	public void selectRoadmap(Roadmap roadmap) {
		if (roadmap == null) {
			return;
		}
		Integer selected = getSelectedRoadmapId();
		if (selected == null || selected != roadmap.getCollectionId()) {
			log.debug("Switching active roadmap to {}", roadmap.getTitle());
			setSelectedRoadmapId(roadmap.getCollectionId());
		}
		setCurrentRoadmap(roadmap);
	}

	public void setPanelListener(Consumer<Roadmap> listener) {
		this.panelListener = listener;
	}

	/** Asks an open panel to re-render the cached roadmap (local progress changed). */
	public void notifyPanel() {
		Roadmap roadmap = currentRoadmap;
		Consumer<Roadmap> listener = panelListener;
		if (roadmap != null && listener != null) {
			listener.accept(roadmap);
		}
	}

	/** Whether the side panel is open; background refreshes run while it is. */
	public void setPanelActive(boolean active) {
		this.panelActive = active;
	}

	public Integer getSelectedRoadmapId() {
		return configManager.getConfiguration(
				CONFIG_GROUP, SELECTED_ROADMAP_KEY, Integer.class);
	}

	public void setSelectedRoadmapId(int collectionId) {
		configManager.setConfiguration(CONFIG_GROUP, SELECTED_ROADMAP_KEY, collectionId);
	}

	/** Whether the panel's "Completed (N)" list is expanded; collapsed by default. */
	public boolean isCompletedGoalsExpanded() {
		Boolean expanded = configManager.getConfiguration(
				CONFIG_GROUP, COMPLETED_EXPANDED_KEY, Boolean.class);
		return expanded != null && expanded;
	}

	public void setCompletedGoalsExpanded(boolean expanded) {
		configManager.setConfiguration(CONFIG_GROUP, COMPLETED_EXPANDED_KEY, expanded);
	}

	/**
	 * Fetches the player's roadmap list. The callback runs on the OkHttp thread;
	 * Swing callers must marshal back to the EDT themselves.
	 */
	public void fetchRoadmaps(OSRSCompanionApiClient.RoadmapCallback<RoadmapList> callback) {
		String player = getPlayerUsername();
		if (player == null) {
			callback.onError("Log in to load your roadmaps");
			return;
		}
		apiClient.getRoadmaps(player, new OSRSCompanionApiClient.RoadmapCallback<RoadmapList>() {
			@Override
			public void onSuccess(RoadmapList result) {
				// Keep the tracker aware of every roadmap, not just the one the
				// panel is about to open.
				onRoadmapListFetched(result, player, null);
				callback.onSuccess(result);
			}

			@Override
			public void onError(String message) {
				callback.onError(message);
			}
		});
	}

	/**
	 * Tells the tracker which roadmaps exist and fetches each one that the caller
	 * is not already fetching itself, so goals in every roadmap are tracked.
	 *
	 * @param skipId roadmap the caller fetches on its own (may be null)
	 * @param onDone runs once every fetch has finished (may be null)
	 */
	private void onRoadmapListFetched(RoadmapList list, String player, Runnable onDone) {
		List<RoadmapSummary> summaries = list.getRoadmaps();
		listCache.record(player, summaries, System.currentTimeMillis());
		Set<Integer> ids = new HashSet<>();
		for (RoadmapSummary summary : summaries) {
			ids.add(summary.getCollectionId());
		}
		Consumer<Set<Integer>> setListener = roadmapSetListener;
		if (setListener != null) {
			setListener.accept(ids);
		}
		Integer selected = getSelectedRoadmapId();
		if (summaries.isEmpty()) {
			// No roadmaps (any more): the box has nothing to show.
			setCurrentRoadmap(null);
		} else if (selected == null || !ids.contains(selected)) {
			// None picked yet, or the picked one was deleted on the website: show the first.
			setSelectedRoadmapId(summaries.get(0).getCollectionId());
		}
		fetchEach(summaries, player, onDone);
	}

	/** Fetches each roadmap and feeds it to the tracker; {@code onDone} runs once
	 * all have finished. A failed read drops the cached list (the roadmap may be
	 * gone), so the next refresh fetches the list again. */
	private void fetchEach(List<RoadmapSummary> summaries, String player, Runnable onDone) {
		if (summaries.isEmpty()) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		AtomicInteger remaining = new AtomicInteger(summaries.size());
		for (RoadmapSummary summary : summaries) {
			apiClient.getRoadmap(summary.getCollectionId(), player, new OSRSCompanionApiClient.RoadmapCallback<Roadmap>() {
				@Override
				public void onSuccess(Roadmap result) {
					onRoadmapFetched(result);
					finish();
				}

				@Override
				public void onError(String message) {
					log.debug("Roadmap {} refresh failed: {}", summary.getCollectionId(), message);
					listCache.invalidate();
					finish();
				}

				private void finish() {
					if (remaining.decrementAndGet() == 0 && onDone != null) {
						onDone.run();
					}
				}
			});
		}
	}

	/**
	 * Fetches one roadmap fully rendered and caches it as the current roadmap on
	 * success (so the overlay picks it up). The callback also fires.
	 */
	public void fetchRoadmap(int collectionId, OSRSCompanionApiClient.RoadmapCallback<Roadmap> callback) {
		String player = getPlayerUsername();
		if (player == null) {
			callback.onError("Log in to load your roadmap");
			return;
		}
		apiClient.getRoadmap(collectionId, player, cacheThen(collectionId, callback));
	}

	/**
	 * Manually marks a goal complete on the backend and caches the re-rendered
	 * roadmap (so the overlay's next goal updates too). The callback runs on the
	 * OkHttp thread; Swing callers marshal back to the EDT themselves.
	 */
	public void completeGoal(int collectionId, int goalId,
			OSRSCompanionApiClient.RoadmapCallback<Roadmap> callback) {
		String player = getPlayerUsername();
		if (player == null) {
			callback.onError("Log in to update your roadmap");
			return;
		}
		apiClient.completeRoadmapGoal(collectionId, goalId, player, cacheThen(collectionId, callback));
	}

	/**
	 * Deletes a goal from the backend and caches the re-rendered roadmap (so the
	 * overlay's next goal updates too). Callback runs on the OkHttp thread.
	 */
	public void deleteGoal(int collectionId, int goalId,
			OSRSCompanionApiClient.RoadmapCallback<Roadmap> callback) {
		String player = getPlayerUsername();
		if (player == null) {
			callback.onError("Log in to update your roadmap");
			return;
		}
		apiClient.deleteRoadmapGoal(collectionId, goalId, player, cacheThen(collectionId, callback));
	}

	// ------------------------------------------------------- reconciliation

	/** Starts the periodic background re-read of the selected roadmap. */
	public void startPeriodicRefresh() {
		scheduler.startPeriodic();
	}

	public void stopPeriodicRefresh() {
		scheduler.stop();
	}

	/**
	 * Called every {@link #WAITING_POLL_SECONDS}: while the roadmap box is on but has no roadmap
	 * to show (none loaded yet, or none made on the website yet), re-reads the list fresh, so a
	 * roadmap shows without opening the goal overview.
	 */
	public void pollWhileWaiting() {
		if (currentRoadmap != null || !config.showNextGoal() || !hasAppKey()) {
			return;
		}
		listCache.invalidate();
		refreshAllQuietly();
	}

	/** Re-reads the roadmap list and every roadmap now (the website changed something). */
	public void refreshAllFresh() {
		listCache.invalidate();
		refreshAllQuietly();
	}

	/** Re-reads every roadmap after a short lag (debounced). */
	public void requestReconcile(int delaySeconds) {
		scheduler.request(delaySeconds);
		notifyPanel(); // so server-driven goals can show their syncing state
	}

	/** True while a server re-read is running or about to (drives "Syncing" hints). */
	public boolean isSyncing() {
		return scheduler.isSyncingSoon();
	}

	/**
	 * Refreshes every roadmap silently (no UI callback): the list (reused while
	 * recent, see {@link RoadmapListCache}), then each roadmap's rendered goals.
	 * Runs on login, on the periodic schedule and after uploads that can move a
	 * goal, so goal progress in any roadmap reaches the plugin even when the
	 * panel is closed. A plain GET re-evaluates goals server-side, so no
	 * recompute call is needed.
	 */
	public void refreshAllQuietly() {
		if (!hasAppKey() || (!config.showNextGoal() && !panelActive)) {
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN || GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String player = getPlayerUsername();
		if (player == null) {
			return;
		}
		if (!scheduler.markInFlight()) {
			log.debug("Quiet roadmap refresh skipped: one already in flight");
			return;
		}
		notifyPanel();
		Runnable done = () -> {
			scheduler.clearInFlight();
			notifyPanel();
		};
		List<RoadmapSummary> known = listCache.reusable(player, System.currentTimeMillis());
		if (known != null) {
			fetchEach(known, player, done);
			return;
		}
		apiClient.getRoadmaps(player, new OSRSCompanionApiClient.RoadmapCallback<RoadmapList>() {
			@Override
			public void onSuccess(RoadmapList result) {
				onRoadmapListFetched(result, player, done);
			}

			@Override
			public void onError(String message) {
				scheduler.clearInFlight();
				log.debug("Quiet roadmap refresh failed: {}", message);
				notifyPanel();
			}
		});
	}

	private OSRSCompanionApiClient.RoadmapCallback<Roadmap> cacheThen(
			int collectionId, OSRSCompanionApiClient.RoadmapCallback<Roadmap> callback) {
		return new OSRSCompanionApiClient.RoadmapCallback<Roadmap>() {
			@Override
			public void onSuccess(Roadmap result) {
				// Feeds the tracker; caches for the overlay only if selected.
				onRoadmapFetched(result);
				callback.onSuccess(result);
			}

			@Override
			public void onError(String message) {
				callback.onError(message);
			}
		};
	}

	/** Convenience for callers that only need the cached roadmap, no fetch. */
	public void withCurrentRoadmap(Consumer<Roadmap> consumer) {
		consumer.accept(currentRoadmap);
	}
}
