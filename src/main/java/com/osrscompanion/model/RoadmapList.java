package com.osrscompanion.model;

import lombok.Getter;
import com.google.gson.annotations.SerializedName;
import java.util.*;

/**
 * Response wrapper for {@code GET /api/runelite/roadmaps/}.
 */
public class RoadmapList {
	@Getter
	@SerializedName("player")
	private String player;

	@Getter
	@SerializedName("runelite_connected")
	private boolean runeliteConnected;

	@SerializedName("roadmaps")
	private List<RoadmapSummary> roadmaps;

	public List<RoadmapSummary> getRoadmaps() {
		return roadmaps == null ? Collections.emptyList() : roadmaps;
	}
}
