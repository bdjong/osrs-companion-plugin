package com.osrscompanion.model;

import lombok.Getter;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A fully rendered roadmap (goals + progress) from the roadmap detail / recompute
 * endpoints.
 */
public class Roadmap {
	@Getter
	@SerializedName("collection_id")
	private int collectionId;

	@Getter
	@SerializedName("title")
	private String title;

	@Getter
	@SerializedName("goal_count")
	private int goalCount;

	@SerializedName("goals")
	private List<RoadmapGoal> goals;

	public List<RoadmapGoal> getGoals() {
		return goals == null ? Collections.emptyList() : goals;
	}

	/**
	 * Returns the goals in sort order. Defensive copy so callers can't mutate the
	 * parsed list.
	 */
	public List<RoadmapGoal> getGoalsSorted() {
		List<RoadmapGoal> sorted = new ArrayList<>(getGoals());
		sorted.sort((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
		return sorted;
	}

	/**
	 * Returns the first uncompleted goal in sort order, or null if the roadmap is
	 * fully complete or empty. This is what the overlay shows.
	 */
	public RoadmapGoal firstIncompleteGoal() {
		for (RoadmapGoal goal : getGoalsSorted()) {
			if (!goal.isComplete()) {
				return goal;
			}
		}
		return null;
	}
}
