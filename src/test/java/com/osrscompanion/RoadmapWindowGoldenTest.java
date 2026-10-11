package com.osrscompanion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.reflect.TypeToken;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapGoal;
import com.osrscompanion.model.RoadmapSummary;
import java.awt.Color;
import java.util.List;
import net.runelite.api.Client;
import org.junit.Before;
import org.junit.Test;

/** Golden renders of the roadmap window, with the mouse on it (see {@link UiKit}, {@link WindowDriver}). */
public class RoadmapWindowGoldenTest {
	private static final int W = 1000;
	private static final int H = 700;

	static final String ROADMAP_JSON = "{\"collection_id\":3,\"title\":\"Ironman early game\",\"goal_count\":7,\"goals\":["
		+ "{\"id\":1,\"goal_type\":\"quest\",\"sort_order\":1,\"name\":\"Complete Waterfall Quest\",\"current\":1,\"target\":1,\"is_complete\":true,\"completed_at\":\"2026-09-01T10:00:00Z\"},"
		+ "{\"id\":2,\"goal_type\":\"skill_level\",\"sort_order\":2,\"name\":\"Reach level 43 Prayer\",\"current\":50339,\"target\":50339,\"is_complete\":true,\"meta\":{\"skill\":\"Prayer\"}},"
		+ "{\"id\":3,\"goal_type\":\"item_owned\",\"sort_order\":3,\"name\":\"Get a Dragon defender\",\"current\":0,\"target\":1,\"meta\":{\"item_id\":12954}},"
		+ "{\"id\":4,\"goal_type\":\"skill_xp\",\"sort_order\":4,\"name\":\"Reach 1M Slayer XP\",\"current\":612000,\"target\":1000000,\"progress_percent\":61,\"meta\":{\"skill\":\"Slayer\"},\"dependency_ids\":[3]},"
		+ "{\"id\":5,\"goal_type\":\"kc\",\"sort_order\":5,\"name\":\"Kill Zulrah 50 times for the Tanzanite fang and Magic fang (and a long name that wraps)\",\"current\":12,\"target\":50,\"progress_percent\":24,"
		+ " \"image_url\":\"https://oldschool.runescape.wiki/images/Zulrah.png\"},"
		+ "{\"id\":6,\"goal_type\":\"clog_item\",\"sort_order\":6,\"name\":\"Barrows gloves\",\"current\":0,\"target\":1,\"meta\":{\"item_id\":7462}},"
		+ "{\"id\":7,\"goal_type\":\"custom\",\"sort_order\":7,\"name\":\"Do the Fight Caves\",\"current\":0,\"target\":1}"
		+ "]}";

	private final UiKit kit = new UiKit();
	private final Client client = mock(Client.class);
	private final RoadmapManager manager = mock(RoadmapManager.class);
	private final GoalProgressTracker tracker = mock(GoalProgressTracker.class);
	private final GoalImageCache images = mock(GoalImageCache.class);
	private RoadmapWindow window;

	@Before
	public void setUp() {
		when(client.getCanvasWidth()).thenReturn(W);
		when(client.getCanvasHeight()).thenReturn(H);
		when(manager.hasAppKey()).thenReturn(true);
		Roadmap roadmap = UiSamples.GSON.fromJson(ROADMAP_JSON, Roadmap.class);
		when(manager.getCurrentRoadmap()).thenReturn(roadmap);
		when(manager.getSelectedRoadmapId()).thenReturn(3);
		when(tracker.progressFor(any(RoadmapGoal.class))).thenAnswer(a -> GoalProgressView.fromServer(a.getArgument(0)));
		when(images.get("https://oldschool.runescape.wiki/images/Zulrah.png")).thenReturn(kit.image("wiki:zulrah", 30, 30, Color.GREEN));
		window = new RoadmapWindow(client, manager, tracker, images);
		List<RoadmapSummary> list = UiSamples.GSON.fromJson("[{\"collection_id\":3,\"title\":\"Ironman early game\",\"goal_count\":7},"
			+ "{\"collection_id\":4,\"title\":\"Quest cape\",\"goal_count\":158},{\"collection_id\":5,\"title\":\"Diaries\",\"goal_count\":0}]",
			new TypeToken<List<RoadmapSummary>>() { }.getType());
		WindowDriver.set(window, "open", true);
		WindowDriver.set(window, "roadmaps", list);
	}

	private UiKit.Rendered render() {
		kit.render(window, W, H);
		return kit.render(window, W, H);
	}

	@Test
	public void goalsWithCompletedCollapsed() {
		UiKit.golden("roadmap-window-goals", render());
	}

	@Test
	public void completedGoalsExpanded() {
		when(manager.isCompletedGoalsExpanded()).thenReturn(true);
		UiKit.golden("roadmap-window-completed-open", render());
	}

	@Test
	public void hoverOnRemoveShowsTooltip() {
		render();
		WindowDriver.move(window, WindowDriver.area(window, "Remove this goal").centre());
		UiKit.golden("roadmap-window-remove-tooltip", kit.render(window, W, H));
	}

	@Test
	public void firstClickOnCompleteAsksToConfirm() {
		render();
		WindowDriver.Area done = WindowDriver.area(window, "Mark this goal complete");
		WindowDriver.click(window, done.centre());
		WindowDriver.move(window, done.centre());
		UiKit.golden("roadmap-window-confirm-complete", kit.render(window, W, H));
	}

	@Test
	public void roadmapSwitcherOpen() {
		render();
		WindowDriver.click(window, WindowDriver.area(window, "Switch to another roadmap").centre());
		UiKit.golden("roadmap-window-switcher", kit.render(window, W, H));
	}

	@Test
	public void syncingWhileLoading() {
		when(manager.isSyncing()).thenReturn(true);
		WindowDriver.set(window, "loading", true);
		UiKit.golden("roadmap-window-syncing", render());
	}

	@Test
	public void noRoadmapWithStatus() {
		when(manager.getCurrentRoadmap()).thenReturn(null);
		WindowDriver.set(window, "status", "Add your OSRS Companion App Key in the plugin settings.");
		UiKit.golden("roadmap-window-status", render());
	}

	@Test
	public void smallScreenScrolled() {
		when(client.getCanvasWidth()).thenReturn(765);
		when(client.getCanvasHeight()).thenReturn(503);
		kit.render(window, 765, 503);
		java.awt.Rectangle body = (java.awt.Rectangle) WindowDriver.get(window, "body");
		WindowDriver.scroll(window, new java.awt.Point(body.x + 30, body.y + 30), 4);
		UiKit.golden("roadmap-window-small-scrolled", kit.render(window, 765, 503));
	}
}
