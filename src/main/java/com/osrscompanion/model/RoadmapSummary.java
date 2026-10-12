package com.osrscompanion.model;

import lombok.Getter;
import com.google.gson.annotations.SerializedName;

/**
 * Lightweight roadmap entry from {@code GET /api/runelite/roadmaps/}, used to
 * populate the panel's roadmap selector.
 */
public class RoadmapSummary {
	@Getter
	@SerializedName("collection_id")
	private int collectionId;

	@Getter
	@SerializedName("title")
	private String title;

	@Getter
	@SerializedName("goal_count")
	private int goalCount;

	public RoadmapSummary() {
	}

	public RoadmapSummary(int collectionId, String title, int goalCount) {
		this.collectionId = collectionId;
		this.title = title;
		this.goalCount = goalCount;
	}

	@Override
	public String toString() {
		return title == null ? "Roadmap " + collectionId : title;
	}
}
