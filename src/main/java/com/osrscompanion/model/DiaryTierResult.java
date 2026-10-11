package com.osrscompanion.model;

import lombok.Getter;
import java.util.List;

/**
 * Completion state of a single achievement diary tier, mirroring WikiSync's
 * {@code {complete, tasks}} shape.
 *
 * <p>{@code tasks} is positional in in-game varbit order; index {@code i} is the
 * i-th task of that region/tier, which the backend correlates against
 * {@code OSRSAchievementDiaryTask.sequence}. The field names serialise to
 * {@code complete} / {@code tasks} to match the payload the backend expects.
 */
public class DiaryTierResult {
	@Getter
	private final boolean complete;
	@Getter
	private final List<Boolean> tasks;

	public DiaryTierResult(boolean complete, List<Boolean> tasks) {
		this.complete = complete;
		this.tasks = tasks;
	}

}
