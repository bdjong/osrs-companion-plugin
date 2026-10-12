package com.osrscompanion;

import lombok.Getter;
import com.osrscompanion.model.GoalType;
import com.osrscompanion.model.RoadmapGoal;

/**
 * A goal's progress as the plugin currently believes it: the server's numbers
 * plus whatever the client has observed locally since. Immutable snapshot built
 * per read by {@link GoalProgressState}.
 */
public final class GoalProgressView {
	@Getter
	private final GoalType type;
	@Getter
	private final int current;
	@Getter
	private final int target;
	/** 0-100, or null for binary goals that have no progress bar. */
	@Getter
	private final Integer percent;
	@Getter
	private final boolean complete;

	GoalProgressView(GoalType type, int current, int target, Integer percent, boolean complete) {
		this.type = type;
		this.current = current;
		this.target = target;
		this.percent = percent;
		this.complete = complete;
	}

	/** The server's view of a goal, untouched by local tracking. */
	public static GoalProgressView fromServer(RoadmapGoal goal) {
		Integer percent = goal.getProgressPercent();
		if (goal.isComplete() && percent != null) {
			percent = 100;
		}
		return new GoalProgressView(goal.getType(), goal.getCurrent(), goal.getTarget(), percent, goal.isComplete());
	}

	/** True when this goal is drawn with a progress bar. */
	public boolean isMeasurable() {
		return percent != null;
	}
}
