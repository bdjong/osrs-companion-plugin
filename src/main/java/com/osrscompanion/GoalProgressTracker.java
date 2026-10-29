package com.osrscompanion;

import lombok.RequiredArgsConstructor;
import com.osrscompanion.model.GoalType;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapGoal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Skill;

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
	private final Client client;
	/** Kill counts RuneLite stored this session (boss name in lower case), for kill-count goals. */
	private final Map<String, Integer> kills = new ConcurrentHashMap<>();
	/** Goals the game shows reached before the website confirms them: their popup already showed. */
	private final Set<Integer> reachedLocally = ConcurrentHashMap.newKeySet();
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
			if (goal.isComplete() && !before.contains(goal.getId()) && !reachedLocally.contains(goal.getId())) {
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
		kills.clear();
		reachedLocally.clear();
	}

	/** A boss kill count RuneLite stored; true when it reaches a kill-count goal. Client thread. */
	public boolean onKillCount(String boss, int count) {
		kills.put(boss.toLowerCase(), count);
		return checkReached();
	}

	/**
	 * Goals of the roadmap shown that the game says are reached (XP or kills) get their popup now, before the
	 * website confirms them; true when one did. Client thread.
	 */
	public boolean checkReached() {
		Roadmap roadmap = roadmapManager.getCurrentRoadmap();
		boolean any = false;
		for (RoadmapGoal g : roadmap == null ? Collections.<RoadmapGoal>emptyList() : roadmap.getGoalsSorted()) {
			Long live = isComplete(g) ? null : liveCurrent(g);
			if (live != null && live >= g.getTarget() && reachedLocally.add(g.getId())) {
				completedThisSession.add(g.getId());
				int done = (int) roadmap.getGoals().stream().filter(this::isComplete).count();
				completionNotifier.show(g, roadmap.getTitle(), done, roadmap.getGoals().size());
				any = true;
			}
		}
		if (any) {
			roadmapManager.notifyPanel();
		}
		return any;
	}

	/** The goal's progress in the game right now (XP or kills gained since it was added), null when unknown. */
	private Long liveCurrent(RoadmapGoal g) {
		if (g.getType().isSkill() && g.getStartXp() != null && g.getSkillName() != null) {
			for (Skill s : Skill.values()) {
				// "runecrafting" goals are Runecraft.
				if (g.getSkillName().toLowerCase().startsWith(s.getName().toLowerCase())) {
					return (long) client.getSkillExperience(s) - g.getStartXp();
				}
			}
			return null;
		}
		Integer kc = g.getType() == GoalType.KC && g.getNpcName() != null && g.getStartValue() != null ? kills.get(g.getNpcName().toLowerCase()) : null;
		return kc == null ? null : (long) kc - g.getStartValue();
	}

	public boolean isComplete(RoadmapGoal goal) {
		return goal.isComplete() || reachedLocally.contains(goal.getId());
	}

	/** True when the goal's completion was first seen this session (it lingers in the tree). */
	public boolean completedThisSession(RoadmapGoal goal) {
		return goal != null && completedThisSession.contains(goal.getId());
	}

	/** The website's progress, moved on to what the game shows (live XP, kills) when that is further. Client thread. */
	public GoalProgressView progressFor(RoadmapGoal goal) {
		Long live = goal.isComplete() || goal.getProgressPercent() == null ? null : liveCurrent(goal);
		if (live == null || live <= goal.getCurrent()) {
			return GoalProgressView.fromServer(goal);
		}
		int target = Math.max(1, goal.getTarget());
		int current = (int) Math.min(live, target);
		return new GoalProgressView(goal.getType(), current, goal.getTarget(), current * 100 / target, current >= target);
	}

	/** The first goal in sort order the website does not have complete. */
	public RoadmapGoal firstIncompleteGoal(Roadmap roadmap) {
		if (roadmap == null) {
			return null;
		}
		for (RoadmapGoal goal : roadmap.getGoalsSorted()) {
			if (!isComplete(goal)) {
				return goal;
			}
		}
		return null;
	}
}
