package com.osrscompanion;

import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.GearPlan;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * State behind the in-game gear progression window: whether it is open, the plan
 * last loaded from the website, the selected tier and item, and the actions the
 * window offers (tick an item, make it the current roadmap goal). All network work
 * runs on OkHttp threads; fields are volatile so the render and input threads see
 * the latest values.
 */
@Slf4j
@Singleton
public class GearProgressionManager {
	/** While the window is open the plan is re-read at most this often. */
	static final long REFRESH_INTERVAL_MS = 60_000;
	/** For the in-game box alone (window closed) the plan is re-read at most this often. */
	static final long OVERLAY_REFRESH_INTERVAL_MS = 120_000;
	/** After a failed read the box waits this long before trying again. */
	static final long OVERLAY_RETRY_MS = 300_000;

	private final OSRSCompanionApiClient apiClient;
	private final RoadmapManager roadmapManager;

	private volatile boolean open;
	private volatile boolean loading;
	private volatile boolean loaded;
	private volatile GearPlan plan;
	private volatile List<GearPlan.Summary> plans = Collections.emptyList();
	private volatile String error;
	private volatile String status;
	private volatile long fetchedAtMs;
	private volatile int selectedTier = -1;
	private volatile int selectedItemId = -1;
	private volatile boolean busy;

	@Inject
	public GearProgressionManager(OSRSCompanionApiClient apiClient, RoadmapManager roadmapManager) {
		this.apiClient = apiClient;
		this.roadmapManager = roadmapManager;
	}

	public boolean isOpen() {
		return open;
	}

	public void toggle() {
		if (open) {
			close();
		} else {
			open();
		}
	}

	public void open() {
		open = true;
		status = null;
		refresh();
	}

	public void close() {
		open = false;
	}

	/** Re-reads the plan when the window is open and the copy is older than the refresh interval. */
	public void refreshIfStale() {
		if (open && !loading && System.currentTimeMillis() - fetchedAtMs > REFRESH_INTERVAL_MS) {
			refresh();
		}
	}

	/**
	 * Keeps the plan fresh for the in-game box while the window is closed: re-reads it
	 * every couple of minutes when logged in with an App Key, and backs off after a
	 * failed read. Cheap enough to call every render frame.
	 */
	public void refreshForOverlay() {
		if (open || loading || roadmapManager.getPlayerUsername() == null || !roadmapManager.hasAppKey()) {
			return;
		}
		long wait = error != null ? OVERLAY_RETRY_MS : OVERLAY_REFRESH_INTERVAL_MS;
		if (System.currentTimeMillis() - fetchedAtMs > wait) {
			refresh();
		}
	}

	public void refresh() {
		String player = roadmapManager.getPlayerUsername();
		if (player == null) {
			error = "Log in to the game to see your gear progression.";
			return;
		}
		if (!roadmapManager.hasAppKey()) {
			error = "Add your App Key in the OSRS Companion settings first.";
			return;
		}
		loading = true;
		fetchedAtMs = System.currentTimeMillis();
		apiClient.getGearPlan(player, new OSRSCompanionApiClient.RoadmapCallback<GearPlan.Response>() {
			@Override
			public void onSuccess(GearPlan.Response result) {
				apply(result);
				error = null;
				loaded = true;
				loading = false;
			}

			@Override
			public void onError(String message) {
				error = "Could not load your gear progression (" + message + ").";
				loading = false;
			}
		});
	}

	/** Ticks or unticks an item the plugin cannot detect by itself. */
	public void toggleDone(GearPlan.Item item) {
		String player = roadmapManager.getPlayerUsername();
		if (item == null || player == null || busy) {
			return;
		}
		busy = true;
		apiClient.toggleGearDone(item.getId(), player, new OSRSCompanionApiClient.RoadmapCallback<GearPlan.Response>() {
			@Override
			public void onSuccess(GearPlan.Response result) {
				apply(result);
				GearPlan.Item updated = plan == null ? null : plan.findItem(item.getId());
				status = updated != null && updated.isDone() ? item.getName() + " marked as done." : item.getName() + " is no longer ticked.";
				busy = false;
			}

			@Override
			public void onError(String message) {
				status = "Could not update " + item.getName() + " (" + message + ").";
				busy = false;
			}
		});
	}

	/** Puts the item at the top of the player's roadmap so the goal overlay tracks it. */
	public void setAsGoal(GearPlan.Item item) {
		String player = roadmapManager.getPlayerUsername();
		if (item == null || player == null || busy) {
			return;
		}
		busy = true;
		apiClient.setGearGoal(item.getId(), player, new OSRSCompanionApiClient.RoadmapCallback<GearPlan.GoalResult>() {
			@Override
			public void onSuccess(GearPlan.GoalResult result) {
				status = result.getMessage();
				busy = false;
				roadmapManager.refreshAllQuietly();
			}

			@Override
			public void onError(String message) {
				status = "Could not set " + item.getName() + " as your goal (" + message + ").";
				busy = false;
			}
		});
	}

	/** Hides an item: it leaves the window and stops counting. It can be unhidden on the website. */
	public void hide(GearPlan.Item item) {
		String player = roadmapManager.getPlayerUsername();
		if (item == null || player == null || busy) {
			return;
		}
		busy = true;
		apiClient.toggleGearHidden(item.getId(), player, new OSRSCompanionApiClient.RoadmapCallback<GearPlan.Response>() {
			@Override
			public void onSuccess(GearPlan.Response result) {
				selectedItemId = -1;
				apply(result);
				status = item.getName() + " hidden. Unhide it on the website (Show hidden items).";
				busy = false;
			}

			@Override
			public void onError(String message) {
				status = "Could not hide " + item.getName() + " (" + message + ").";
				busy = false;
			}
		});
	}

	/** Makes another roadmap the active one (also on the website) and shows it. */
	public void activatePlan(GearPlan.Summary summary) {
		String player = roadmapManager.getPlayerUsername();
		if (summary == null || player == null || busy) {
			return;
		}
		busy = true;
		apiClient.activateGearPlan(summary.getId(), player, new OSRSCompanionApiClient.RoadmapCallback<GearPlan.Response>() {
			@Override
			public void onSuccess(GearPlan.Response result) {
				selectedTier = -1;
				selectedItemId = -1;
				apply(result);
				status = "Switched to " + summary.getName() + ".";
				busy = false;
			}

			@Override
			public void onError(String message) {
				status = "Could not switch to " + summary.getName() + " (" + message + ").";
				busy = false;
			}
		});
	}

	private void apply(GearPlan.Response result) {
		GearPlan newPlan = result.getPlan();
		plans = result.getPlans();
		plan = newPlan;
		if (newPlan != null && selectedItemId >= 0 && newPlan.findItem(selectedItemId) == null) {
			selectedItemId = -1;
		}
	}

	public GearPlan getPlan() {
		return plan;
	}

	/** Every roadmap of the character (the active one included), oldest first. */
	public List<GearPlan.Summary> getPlans() {
		return plans;
	}

	public boolean isLoading() {
		return loading;
	}

	public boolean isLoaded() {
		return loaded;
	}

	public boolean isBusy() {
		return busy;
	}

	public String getError() {
		return error;
	}

	public String getStatus() {
		return status;
	}

	/** The tier on show: the one the player picked, else the tier with the next open step. */
	public GearPlan.Tier getSelectedTier() {
		GearPlan p = plan;
		if (p == null || p.getTiers().isEmpty()) {
			return null;
		}
		int wanted = selectedTier > 0 ? selectedTier : p.getNextTier() != null ? p.getNextTier() : 1;
		for (GearPlan.Tier t : p.getTiers()) {
			if (t.getTier() == wanted) {
				return t;
			}
		}
		return p.getTiers().get(0);
	}

	public void selectTier(int tier) {
		selectedTier = tier;
		selectedItemId = -1;
	}

	/** The item shown in the details panel: the one clicked, else the first open item of the next step. */
	public GearPlan.Item getSelectedItem() {
		GearPlan p = plan;
		if (p == null) {
			return null;
		}
		if (selectedItemId >= 0) {
			GearPlan.Item picked = p.findItem(selectedItemId);
			if (picked != null) {
				return picked;
			}
		}
		GearPlan.Tier tier = getSelectedTier();
		if (tier == null) {
			return null;
		}
		for (GearPlan.Step s : tier.getSteps()) {
			for (GearPlan.Item i : s.getItems()) {
				if (i.isNext()) {
					return i;
				}
			}
		}
		return null;
	}

	public void selectItem(int itemId) {
		selectedItemId = itemId;
		status = null;
	}

	public void clear() {
		open = false;
		plan = null;
		plans = Collections.emptyList();
		loaded = false;
		error = null;
		status = null;
		selectedTier = -1;
		selectedItemId = -1;
	}
}
