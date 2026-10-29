package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapGoal;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

/** Skill and kill-count goals moved on by what the game shows, before the website confirms them. */
public class GoalProgressTrackerTest {
	// 45 Crafting added at 60,000 XP (1,512 XP to go), then 10 more Zulrah kills added at 597.
	private static final String ROADMAP = "{\"collection_id\":7,\"title\":\"My Roadmap\",\"goals\":["
		+ "{\"id\":1,\"goal_type\":\"skill_level\",\"sort_order\":0,\"name\":\"Crafting level 45\",\"current\":0,\"target\":1512,"
		+ "\"progress_percent\":0,\"is_complete\":false,\"meta\":{\"skill\":\"crafting\",\"start_xp\":60000}},"
		+ "{\"id\":2,\"goal_type\":\"kc\",\"sort_order\":1,\"name\":\"10 Zulrah kills\",\"current\":0,\"target\":10,\"start_value\":597,"
		+ "\"progress_percent\":0,\"is_complete\":false,\"meta\":{\"npc_name\":\"Zulrah\"}}]}";

	private final RoadmapManager roadmaps = mock(RoadmapManager.class);
	private final GoalCompletionNotifier popups = mock(GoalCompletionNotifier.class);
	private final Client client = mock(Client.class);
	private final GoalProgressTracker tracker = new GoalProgressTracker(roadmaps, popups, client);
	private Roadmap roadmap;

	@Before
	public void setUp() {
		roadmap = new Gson().fromJson(ROADMAP, Roadmap.class);
		when(roadmaps.getCurrentRoadmap()).thenReturn(roadmap);
	}

	private RoadmapGoal goal(int i) {
		return roadmap.getGoalsSorted().get(i);
	}

	@Test
	public void theBarFollowsTheLiveXpAndTheGoalTicksOffAtOnce() {
		when(client.getSkillExperience(Skill.CRAFTING)).thenReturn(60_756);
		assertEquals(Integer.valueOf(50), tracker.progressFor(goal(0)).getPercent());
		assertFalse(tracker.checkReached());

		when(client.getSkillExperience(Skill.CRAFTING)).thenReturn(61_512);
		assertTrue(tracker.checkReached());
		verify(popups).show(goal(0), "My Roadmap", 1, 2);
		assertTrue(tracker.progressFor(goal(0)).isComplete());
		// The goal box moves on to the next goal.
		assertEquals(2, tracker.firstIncompleteGoal(roadmap).getId());
		// Once only.
		assertFalse(tracker.checkReached());
	}

	@Test
	public void aGoalAlreadyReachedWhenItArrivesTicksOffWhenShown() {
		// The XP came in before the plugin had the goal: no XP drop crosses it any more.
		when(client.getSkillExperience(Skill.CRAFTING)).thenReturn(62_000);
		assertTrue(tracker.progressFor(goal(0)).isComplete());
		verify(popups).show(goal(0), "My Roadmap", 1, 2);
		assertEquals(2, tracker.firstIncompleteGoal(roadmap).getId());
		tracker.progressFor(goal(0));
		verify(popups, times(1)).show(any(), anyString(), anyInt(), anyInt());
	}

	@Test
	public void killCountGoalsFromTheGamesKillCount() {
		assertFalse(tracker.onKillCount("zulrah", 601));
		assertEquals(4, tracker.progressFor(goal(1)).getCurrent());
		assertTrue(tracker.onKillCount("zulrah", 607));
		verify(popups).show(goal(1), "My Roadmap", 1, 2);
	}

	@Test
	public void theWebsitesConfirmationShowsNoSecondPopup() {
		Roadmap before = new Gson().fromJson(ROADMAP, Roadmap.class);
		tracker.onServerRoadmap(before);
		tracker.onKillCount("zulrah", 607);
		verify(popups, times(1)).show(any(), anyString(), anyInt(), anyInt());
		// The website now has it complete too.
		Roadmap confirmed = new Gson().fromJson(ROADMAP.replace("\"start_value\":597,\"progress_percent\":0,\"is_complete\":false",
			"\"start_value\":597,\"progress_percent\":100,\"is_complete\":true"), Roadmap.class);
		tracker.onServerRoadmap(confirmed);
		verify(popups, times(1)).show(any(), anyString(), anyInt(), anyInt());
		verify(roadmaps, never()).selectRoadmap(any());
	}
}
