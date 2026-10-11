package com.osrscompanion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.osrscompanion.model.GearPlan;
import com.osrscompanion.model.GoalType;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapGoal;
import java.awt.Color;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import org.junit.Test;

/** Golden renders of the roadmap goal box and the gear progression box (see {@link UiKit}). */
public class GoalsUiGoldenTest {
	private final UiKit kit = new UiKit();
	private final TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
	private final RoadmapManager roadmaps = mock(RoadmapManager.class);
	private final GoalProgressTracker tracker = mock(GoalProgressTracker.class);
	private final GoalImageCache images = mock(GoalImageCache.class);
	private final SkillIconManager skills = mock(SkillIconManager.class);
	private final GearProgressionManager gear = mock(GearProgressionManager.class);

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

	// ---- gear progression box ----------------------------------------------------------------------

	static final String PLAN_JSON = "{\"title\":\"Ironman gear\",\"done\":17,\"total\":64,\"next_tier\":2,\"tiers\":["
		+ "{\"tier\":1,\"name\":\"Early\",\"done\":12,\"total\":12,\"steps\":[{\"step\":1,\"items\":[{\"id\":1,\"name\":\"Rune scimitar\",\"item_id\":1333,\"done\":true}]}]},"
		+ "{\"tier\":2,\"name\":\"Mid\",\"done\":5,\"total\":20,\"steps\":["
		+ " {\"step\":1,\"items\":[{\"id\":2,\"name\":\"Dragon defender\",\"item_id\":12954,\"done\":true}]},"
		+ " {\"step\":2,\"items\":["
		+ "  {\"id\":3,\"name\":\"Barrows gloves\",\"item_id\":7462,\"next\":true},"
		+ "  {\"id\":4,\"name\":\"Fighter torso (from Barbarian Assault, all four roles to level 5)\",\"item_id\":10551,\"next\":true},"
		+ "  {\"id\":5,\"name\":\"Fire cape\",\"image_url\":\"https://oldschool.runescape.wiki/images/Fire_cape.png\",\"next\":true},"
		+ "  {\"id\":6,\"name\":\"Hidden thing\",\"item_id\":1,\"next\":true,\"hidden\":true},"
		+ "  {\"id\":7,\"name\":\"Dragon boots\",\"item_id\":11840,\"next\":true},"
		+ "  {\"id\":8,\"name\":\"Amulet of glory\",\"item_id\":1704,\"next\":true}"
		+ " ]}]}]}";

	private GearNextOverlay gearBox(TestOSRSCompanionConfig cfg) {
		when(images.get("https://oldschool.runescape.wiki/images/Fire_cape.png")).thenReturn(kit.image("wiki:fire-cape", 30, 32, Color.RED));
		return new GearNextOverlay(cfg, gear, kit.itemManager(), images);
	}

	@Test
	public void gearNextStepWithMoreItems() {
		when(gear.getPlan()).thenReturn(UiSamples.GSON.fromJson(PLAN_JSON, GearPlan.class));
		UiKit.golden("gear-overlay-next", kit.render(gearBox(config), 400, 300));
	}

	@Test
	public void gearAllDoneLongTitleNoBar() {
		GearPlan plan = UiSamples.GSON.fromJson("{\"title\":\"Main account end-game gear progression for raids\",\"done\":64,\"total\":64,\"tiers\":[]}", GearPlan.class);
		when(gear.getPlan()).thenReturn(plan);
		TestOSRSCompanionConfig off = new TestOSRSCompanionConfig();
		off.setShowGoalProgress(false);
		UiKit.golden("gear-overlay-done", kit.render(gearBox(off), 400, 200));
	}

	@Test
	public void gearNothingWithoutAPlan() {
		UiKit.golden("gear-overlay-none", kit.render(gearBox(config), 100, 100));
	}
}
