package com.osrscompanion;

import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.GearPlan;
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

	private final OSRSCompanionApiClient apiClient;
	private final RoadmapManager roadmapManager;

	private volatile boolean open;
	private volatile boolean loading;
	private volatile boolean loaded;
	private volatile GearPlan plan;
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
				setPlan(result.getPlan());
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
				setPlan(result.getPlan());
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

	private void setPlan(GearPlan newPlan) {
		plan = newPlan;
		if (newPlan != null && selectedItemId >= 0 && newPlan.findItem(selectedItemId) == null) {
			selectedItemId = -1;
		}
	}

	public GearPlan getPlan() {
		return plan;
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
		loaded = false;
		error = null;
		status = null;
		selectedTier = -1;
		selectedItemId = -1;
	}
}
