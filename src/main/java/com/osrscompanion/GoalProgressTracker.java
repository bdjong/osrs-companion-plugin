package com.osrscompanion;

import lombok.RequiredArgsConstructor;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapGoal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Goal progress as the website works it out from the synced data: the goal box and the roadmap window show
 * each goal's progress from the roadmap the website sent, and a goal the website newly reports complete (since
 * its last roadmap read this session) gets the completion popup and sound.
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
public class GoalProgressTracker {
	private final RoadmapManager roadmapManager;
	private final GoalCompletionNotifier completionNotifier;
	/** Per roadmap, the goals complete at the website's last read this session; none yet: the first read sets it. */
	private final Map<Integer, Set<Integer>> completed = new ConcurrentHashMap<>();
	/** Goals completed during this session (they linger in the roadmap window's tree). */
	private final Set<Integer> completedThisSession = ConcurrentHashMap.newKeySet();

	/** A roadmap read from the website: goals newly complete since its last read get the popup. */
	public void onServerRoadmap(Roadmap roadmap) {
		if (roadmap == null) {
			return;
		}
		Set<Integer> now = new HashSet<>();
		for (RoadmapGoal goal : roadmap.getGoals()) {
			if (goal.isComplete()) {
				now.add(goal.getId());
			}
		}
		Set<Integer> before = completed.put(roadmap.getCollectionId(), now);
		if (before == null) {
			return;
		}
		for (RoadmapGoal goal : roadmap.getGoalsSorted()) {
			if (goal.isComplete() && !before.contains(goal.getId())) {
				completedThisSession.add(goal.getId());
				// A completion in another roadmap makes that roadmap the active one.
				roadmapManager.selectRoadmap(roadmap);
				completionNotifier.show(goal, roadmap.getTitle(), now.size(), roadmap.getGoals().size());
				roadmapManager.notifyPanel();
			}
		}
	}

	/** The player's current set of roadmaps; any others are forgotten. */
	public void retainRoadmaps(Set<Integer> collectionIds) {
		completed.keySet().retainAll(collectionIds);
	}

	public void clear() {
		completed.clear();
		completedThisSession.clear();
	}

	public boolean isComplete(RoadmapGoal goal) {
		return goal.isComplete();
	}

	/** True when the goal's completion was first seen this session (it lingers in the tree). */
	public boolean completedThisSession(RoadmapGoal goal) {
		return goal != null && completedThisSession.contains(goal.getId());
	}

	public GoalProgressView progressFor(RoadmapGoal goal) {
		return GoalProgressView.fromServer(goal);
	}

	/** The first goal in sort order the website does not have complete. */
	public RoadmapGoal firstIncompleteGoal(Roadmap roadmap) {
		if (roadmap == null) {
			return null;
		}
		for (RoadmapGoal goal : roadmap.getGoalsSorted()) {
			if (!goal.isComplete()) {
				return goal;
			}
		}
		return null;
	}
}
