package com.osrscompanion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.osrscompanion.model.GoalType;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapGoal;
import java.awt.Color;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import org.junit.Test;

/** Golden renders of the roadmap goal box (see {@link UiKit}). */
public class GoalsUiGoldenTest {
	private final UiKit kit = new UiKit();
	private final TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
	private final RoadmapManager roadmaps = mock(RoadmapManager.class);
	private final GoalProgressTracker tracker = mock(GoalProgressTracker.class);
	private final GoalImageCache images = mock(GoalImageCache.class);
	private final SkillIconManager skills = mock(SkillIconManager.class);

	static RoadmapGoal goal(String json) {
		return UiSamples.GSON.fromJson(json, RoadmapGoal.class);
	}

	static Roadmap roadmap(String title, RoadmapGoal... goals) {
		StringBuilder sb = new StringBuilder("{\"collection_id\":3,\"title\":" + (title == null ? "null" : "\"" + title + "\"") + ",\"goal_count\":" + goals.length + ",\"goals\":[]}");
		Roadmap r = UiSamples.GSON.fromJson(sb.toString(), Roadmap.class);
		r.getGoals().addAll(java.util.Arrays.asList(goals));
		return r;
	}

	private NextGoalOverlay goalBox() {
		when(skills.getSkillImage(any(Skill.class))).thenAnswer(a -> kit.image("skill:" + a.getArgument(0), 25, 25, Color.ORANGE));
		return new NextGoalOverlay(config, roadmaps, tracker, kit.itemManager(), skills, images);
	}

	private void current(Roadmap roadmap, RoadmapGoal goal, GoalProgressView progress) {
		when(roadmaps.hasAppKey()).thenReturn(true);
		when(roadmaps.getCurrentRoadmap()).thenReturn(roadmap);
		when(tracker.firstIncompleteGoal(roadmap)).thenReturn(goal);
		when(tracker.progressFor(goal)).thenReturn(progress);
	}

	@Test
	public void itemGoalWithProgressBar() {
		RoadmapGoal g = goal("{\"id\":11,\"goal_type\":\"item_quantity\",\"name\":\"Collect 1,000 Shark\",\"current\":420,\"target\":1000,\"meta\":{\"item_id\":385}}");
		current(roadmap("Main roadmap", g), g, new GoalProgressView(GoalType.ITEM_QUANTITY, 420, 1000, 42, false));
		UiKit.golden("goal-overlay-item", kit.render(goalBox(), 400, 200));
	}

	@Test
	public void skillGoalWithLongTitleAndName() {
		RoadmapGoal g = goal("{\"id\":12,\"goal_type\":\"skill_level\",\"name\":\"Reach level 70 Agility for the Ardougne rooftop course and graceful\",\"current\":601302,\"target\":737627,\"meta\":{\"skill\":\"Agility\"}}");
		current(roadmap("Ironman early game: Barrows gloves and Fire cape", g), g, new GoalProgressView(GoalType.SKILL_LEVEL, 601_302, 737_627, 81, false));
		UiKit.golden("goal-overlay-skill", kit.render(goalBox(), 400, 250));
	}

	@Test
	public void serverDrivenGoalWhileSyncingWithWikiImage() {
		RoadmapGoal g = goal("{\"id\":13,\"goal_type\":\"net_worth\",\"name\":\"Reach 50M net worth\",\"image_url\":\"https://oldschool.runescape.wiki/images/Coins_10000.png\",\"current\":31000000,\"target\":50000000,\"progress_percent\":62}");
		current(roadmap(null, g), g, new GoalProgressView(GoalType.NET_WORTH, 31_000_000, 50_000_000, 62, false));
		when(images.get("https://oldschool.runescape.wiki/images/Coins_10000.png")).thenReturn(kit.image("wiki:coins", 32, 28, Color.YELLOW));
		when(roadmaps.isSyncing()).thenReturn(true);
		UiKit.golden("goal-overlay-net-worth-syncing", kit.render(goalBox(), 400, 250));
	}

	@Test
	public void questGoalHasNoBar() {
		RoadmapGoal g = goal("{\"id\":14,\"goal_type\":\"quest\",\"name\":\"Complete Recipe for Disaster\",\"current\":0,\"target\":1}");
		current(roadmap("Quests", g), g, new GoalProgressView(GoalType.QUEST, 0, 1, null, false));
		UiKit.golden("goal-overlay-quest", kit.render(goalBox(), 400, 200));
	}

	@Test
	public void farmingTimerGrownWithoutPercent() {
		RoadmapGoal g = goal("{\"id\":15,\"goal_type\":\"farming_timer\",\"name\":\"Wait for the magic tree to grow\",\"current\":0,\"target\":1,\"progress_percent\":80}");
		current(roadmap("Farming", g), g, new GoalProgressView(GoalType.FARMING_TIMER, 0, 1, 80, false));
		UiKit.golden("goal-overlay-farming-timer", kit.render(goalBox(), 400, 200));
	}

	@Test
	public void progressBarSwitchedOff() {
		RoadmapGoal g = goal("{\"id\":11,\"goal_type\":\"item_quantity\",\"name\":\"Collect 1,000 Shark\",\"current\":420,\"target\":1000,\"meta\":{\"item_id\":385}}");
		current(roadmap("Main roadmap", g), g, new GoalProgressView(GoalType.ITEM_QUANTITY, 420, 1000, 42, false));
		TestOSRSCompanionConfig off = new TestOSRSCompanionConfig();
		off.setShowGoalProgress(false);
		NextGoalOverlay box = new NextGoalOverlay(off, roadmaps, tracker, kit.itemManager(), skills, images);
		UiKit.golden("goal-overlay-no-bar", kit.render(box, 400, 200));
	}
}
