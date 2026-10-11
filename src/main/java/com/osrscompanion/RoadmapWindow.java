package com.osrscompanion;

import lombok.Getter;
import static com.osrscompanion.GameUi.*;

import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.Roadmap;
import com.osrscompanion.model.RoadmapGoal;
import com.osrscompanion.model.RoadmapList;
import com.osrscompanion.model.RoadmapSummary;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.FontManager;

/**
 * The roadmap window, drawn over the game like the farm run and boss gear windows: the selected
 * roadmap (with a switcher) as the app's goal tree - prerequisites first, indented with connector
 * lines, each goal with its picture, progress bar and Complete / Delete - and the finished goals
 * in a "Completed" list. It replaces the goal list that used to live in the side panel.
 */
@Singleton
public class RoadmapWindow extends GameWindow {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "roadmap-window";

	private static final int MAX_WIDTH = 580;
	private static final int MAX_HEIGHT = 470;
	private static final int MIN_WIDTH = 400;
	private static final int MIN_HEIGHT = 300;
	private static final int SUBTITLE_HEIGHT = 22;
	private static final int BUTTON_HEIGHT = 20;
	private static final int INDENT = 16;
	private static final int ROW_GAP = 5;
	private static final int ICON = 32;
	private static final int ACTION_W = 58;
	private static final int ACTION_H = 16;
	private static final long CONFIRM_MS = 4000;
	private static final Color GUIDE = new Color(0x6B5D48);
	private static final Color DONE = new Color(0x5CD65C);

	private final RoadmapManager roadmapManager;
	private final GoalProgressTracker tracker;
	private final GoalImageCache images;

	@Getter
	private volatile boolean open;
	private volatile List<RoadmapSummary> roadmaps = Collections.emptyList();
	private volatile String status;
	private volatile boolean loading;

	private volatile Rectangle body;
	private volatile int scroll;
	private volatile int contentHeight;
	/** A Complete / Delete click waiting for its second (confirming) click. */
	private volatile String confirmKey;
	private volatile long confirmUntil;

	@Inject
	public RoadmapWindow(Client client, RoadmapManager roadmapManager, GoalProgressTracker tracker, GoalImageCache images) {
		super(client, GUARD_FEATURE);
		this.roadmapManager = roadmapManager;
		this.tracker = tracker;
		this.images = images;
	}

	// ------------------------------------------------------------------ open / load

	public void toggle() {
		if (open) {
			close();
		} else {
			openWindow();
		}
	}

	/** Opens the window and re-reads the roadmaps (background refreshes run while it is open). */
	public void openWindow() {
		open = true;
		switcherOpen = false;
		roadmapManager.setPanelActive(true);
		load();
	}

	public void close() {
		open = false;
		switcherOpen = false;
		roadmapManager.setPanelActive(false);
	}

	private void load() {
		if (!roadmapManager.hasAppKey()) {
			status = "Add your OSRS Companion App Key in the plugin settings.";
			return;
		}
		loading = true;
		status = null;
		roadmapManager.fetchRoadmaps(new OSRSCompanionApiClient.RoadmapCallback<RoadmapList>() {
			@Override
			public void onSuccess(RoadmapList result) {
				List<RoadmapSummary> list = result.getRoadmaps();
				roadmaps = list;
				if (list.isEmpty()) {
					loading = false;
					status = "No roadmaps yet. Create one on the OSRS Companion website.";
					return;
				}
				Integer selected = roadmapManager.getSelectedRoadmapId();
				RoadmapSummary pick = list.get(0);
				for (RoadmapSummary s : list) {
					if (selected != null && s.getCollectionId() == selected) {
						pick = s;
					}
				}
				select(pick);
			}

			@Override
			public void onError(String message) {
				loading = false;
				status = message;
			}
		});
	}

	private void select(RoadmapSummary summary) {
		roadmapManager.setSelectedRoadmapId(summary.getCollectionId());
		loading = true;
		scroll = 0;
		roadmapManager.fetchRoadmap(summary.getCollectionId(), new OSRSCompanionApiClient.RoadmapCallback<Roadmap>() {
			@Override
			public void onSuccess(Roadmap result) {
				loading = false;
				status = null;
			}

			@Override
			public void onError(String message) {
				loading = false;
				status = message;
			}
		});
	}

	/** Complete / Delete on the server; the manager caches the re-rendered roadmap. */
	private void act(RoadmapGoal goal, boolean delete) {
		Roadmap roadmap = roadmapManager.getCurrentRoadmap();
		if (roadmap == null) {
			return;
		}
		status = delete ? "Deleting..." : "Marking complete...";
		OSRSCompanionApiClient.RoadmapCallback<Roadmap> done = new OSRSCompanionApiClient.RoadmapCallback<Roadmap>() {
			@Override
			public void onSuccess(Roadmap result) {
				status = null;
			}

			@Override
			public void onError(String message) {
				status = message;
			}
		};
		if (delete) {
			roadmapManager.deleteGoal(roadmap.getCollectionId(), goal.getId(), done);
		} else {
			roadmapManager.completeGoal(roadmap.getCollectionId(), goal.getId(), done);
		}
	}

	/** First click arms the action (the button asks "Sure?"), a second click within a few seconds does it. */
	private void confirmThen(String key, Runnable action) {
		long now = System.currentTimeMillis();
		if (key.equals(confirmKey) && now < confirmUntil) {
			confirmKey = null;
			action.run();
		} else {
			confirmKey = key;
			confirmUntil = now + CONFIRM_MS;
		}
	}

	private boolean armed(String key) {
		return key.equals(confirmKey) && System.currentTimeMillis() < confirmUntil;
	}

	// ------------------------------------------------------------------ render

	@Override
	public Dimension render(Graphics2D g) {
		return Guard.render(GUARD_FEATURE, () -> {
			List<Hit> newHits = new ArrayList<>();
			Rectangle win = frame(g, client.getWidget(InterfaceID.Bankmain.UNIVERSE) == null && open, MAX_WIDTH, MAX_HEIGHT, MIN_WIDTH, MIN_HEIGHT, "Roadmap", newHits);
			if (win == null) {
				return null;
			}
			int x = win.x;
			int y = win.y;
			int w = win.width;
			int h = win.height;

			Roadmap roadmap = roadmapManager.getCurrentRoadmap();
			Rectangle switchButton = subtitle(g, roadmap, win, newHits);

			Rectangle area = new Rectangle(x + 8, y + TITLE_HEIGHT + SUBTITLE_HEIGHT + 6, w - 16, h - TITLE_HEIGHT - SUBTITLE_HEIGHT - 16);
			panel(g, area);
			body = area;
			if (roadmap == null) {
				message(g, area, loading ? "Loading your roadmap..." : status != null ? status : "Loading your roadmap...");
			} else {
				renderGoals(g, roadmap, area, newHits);
			}

			if (switcherOpen && switchButton != null) {
				renderSwitcher(g, switchButton, win, newHits);
			} else {
				switcherArea = null;
			}
			finish(g, win, newHits);
			return new Dimension(w, h);
		});
	}

	/** Roadmap name, Switch and Refresh, and what is going on. Returns the Switch button. */
	private Rectangle subtitle(Graphics2D g, Roadmap roadmap, Rectangle win, List<Hit> newHits) {
		int subY = win.y + TITLE_HEIGHT + 16;
		g.setFont(FontManager.getRunescapeFont());
		String title = roadmap != null ? roadmap.getTitle() : "";
		int after = text(g, truncate(title == null ? "Roadmap" : title, g.getFontMetrics(), win.width / 2 - 40), win.x + 12, subY, Color.WHITE);
		Rectangle switchButton = null;
		if (roadmaps.size() > 1) {
			switchButton = new Rectangle(after + 8, subY - 13, 62, 17);
			button(g, switchButton, switcherOpen ? "Close" : "Switch", false);
			newHits.add(new Hit(switchButton, () -> switcherOpen = !switcherOpen, "Switch to another roadmap"));
		}
		Rectangle refresh = new Rectangle(win.x + win.width - 12 - 80, subY - 14, 80, BUTTON_HEIGHT);
		button(g, refresh, loading ? "Loading" : "Refresh", loading);
		newHits.add(new Hit(refresh, this::load, "Reload your roadmaps from the OSRS Companion website"));
		String info = status != null ? status : roadmapManager.isSyncing() ? "Syncing..." : null;
		if (info != null) {
			g.setFont(FontManager.getRunescapeSmallFont());
			String s = truncate(info, g.getFontMetrics(), refresh.x - after - (switchButton != null ? 80 : 16));
			text(g, s, refresh.x - 8 - g.getFontMetrics().stringWidth(s), subY, BEIGE);
		}
		return switchButton;
	}

	/** One line of the goal list: a goal (tree or completed) or the "Completed (N)" heading. */
	private static final class Line {
		final RoadmapGoal goal;
		final int depth;
		final Guide[] guides;
		final String heading;

		Line(RoadmapGoal goal, int depth, Guide[] guides, String heading) {
			this.goal = goal;
			this.depth = depth;
			this.guides = guides;
			this.heading = heading;
		}
	}

	private void renderGoals(Graphics2D g, Roadmap roadmap, Rectangle area, List<Hit> newHits) {
		List<RoadmapGoal> goals = roadmap.getGoalsSorted();
		if (goals.isEmpty()) {
			message(g, area, "This roadmap has no goals yet. Add some on the OSRS Companion website.");
			return;
		}
		GoalPartition split = GoalPartition.of(goals, tracker::isComplete, tracker::completedThisSession);
		List<Line> lines = new ArrayList<>();
		List<RoadmapGoal> ordered = orderTree(split.getTree());
		int[] depths = depths(ordered);
		Guide[][] guides = computeGuides(depths);
		for (int i = 0; i < ordered.size(); i++) {
			lines.add(new Line(ordered.get(i), depths[i], guides[i], null));
		}
		boolean expanded = roadmapManager.isCompletedGoalsExpanded();
		if (!split.getCompleted().isEmpty()) {
			lines.add(new Line(null, 0, new Guide[0], (expanded ? "- " : "+ ") + "Completed (" + split.getCompleted().size() + ")"));
			if (expanded) {
				for (RoadmapGoal goal : split.getCompleted()) {
					lines.add(new Line(goal, 0, new Guide[0], null));
				}
			}
		}

		Shape oldClip = g.getClip();
		Rectangle view = new Rectangle(area.x + 4, area.y + 4, area.width - 8, area.height - 8);
		g.clipRect(view.x, view.y, view.width, view.height);
		int max = Math.max(0, contentHeight - view.height);
		int top = Math.max(0, Math.min(scroll, max));
		scroll = top;
		int cy = view.y - top;
		int scrollbar = contentHeight > view.height ? 8 : 0;
		if (split.getTree().isEmpty()) {
			g.setFont(FontManager.getRunescapeFont());
			text(g, "Every goal is done!", view.x + 8, cy + 18, DONE);
			cy += 28;
		}
		List<Hit> rowHits = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			Line line = lines.get(i);
			if (line.heading != null) {
				Rectangle head = new Rectangle(view.x, cy, view.width - scrollbar, 24);
				g.setFont(FontManager.getRunescapeBoldFont());
				text(g, line.heading, head.x + 8, head.y + 17, Color.LIGHT_GRAY);
				if (head.intersects(view)) {
					rowHits.add(new Hit(head, () -> roadmapManager.setCompletedGoalsExpanded(!expanded), "Show or hide completed goals"));
				}
				cy += head.height + ROW_GAP;
				continue;
			}
			int indent = line.depth * INDENT;
			Rectangle card = new Rectangle(view.x + indent, cy, view.width - scrollbar - indent, 0);
			card.height = drawGoal(g, line.goal, card, cy + 200 < view.y || cy > view.y + view.height, rowHits);
			drawGuides(g, line, view.x, cy, card.height);
			cy += card.height + ROW_GAP;
		}
		contentHeight = cy + top - view.y;
		g.setClip(oldClip);
		if (scrollbar > 0) {
			int barH = Math.max(20, view.height * view.height / Math.max(1, contentHeight));
			int barY = view.y + (view.height - barH) * top / Math.max(1, max);
			g.setColor(PANEL_EDGE);
			g.fillRect(view.x + view.width - 5, view.y, 4, view.height);
			g.setColor(FRAME_LIGHT);
			g.fillRect(view.x + view.width - 5, barY, 4, barH);
		}
		// Only what can be seen can be clicked.
		for (Hit hit : rowHits) {
			Rectangle visible = hit.area.intersection(view);
			if (!visible.isEmpty()) {
				newHits.add(new Hit(visible, hit.action, hit.tooltip));
			}
		}
	}

	/** One goal card: picture, name, progress, Complete / Delete. Returns its height. */
	private int drawGoal(Graphics2D g, RoadmapGoal goal, Rectangle card, boolean offscreen, List<Hit> rowHits) {
		GoalProgressView progress = tracker.progressFor(goal);
		boolean complete = progress.isComplete();
		int textX = card.x + 8 + ICON + 8;
		int actionsW = (complete ? 0 : ACTION_W + 4) + ACTION_W + 8;
		int textW = card.x + card.width - actionsW - textX;
		g.setFont(FontManager.getRunescapeFont());
		FontMetrics fm = g.getFontMetrics();
		List<String> nameLines = wrap(goal.getName() == null ? "Goal" : goal.getName(), fm, Math.max(60, textW));
		if (nameLines.size() > 2) {
			nameLines = new ArrayList<>(nameLines.subList(0, 2));
			nameLines.set(1, truncate(nameLines.get(1) + "...", fm, textW));
		}
		boolean measurable = !complete && progress.isMeasurable();
		boolean syncing = !complete && progress.getType().isServerDriven() && roadmapManager.isSyncing();
		int height = 8 + nameLines.size() * 15 + (measurable ? 22 : complete ? 14 : 0) + (syncing ? 13 : 0) + 6;
		height = Math.max(height, ICON + 14);
		card.height = height;
		if (offscreen) {
			return height;
		}

		boolean hover = mouse != null && card.contains(mouse);
		box(g, card, hover ? TAB_ON : PANEL, PANEL_EDGE, SLOT_INNER);
		BufferedImage icon = images.get(goal.getImageUrl());
		Rectangle iconBox = new Rectangle(card.x + 8, card.y + (height - ICON) / 2, ICON, ICON);
		if (icon != null) {
			g.drawImage(icon, iconBox.x + (ICON - icon.getWidth()) / 2, iconBox.y + (ICON - icon.getHeight()) / 2, null);
		} else {
			box(g, iconBox, SLOT, SLOT_EDGE, SLOT_INNER);
		}
		if (complete) {
			tick(g, iconBox.x + ICON - 10, iconBox.y + ICON - 8);
		}

		int ty = card.y + 8 + 12;
		g.setFont(FontManager.getRunescapeFont());
		for (String l : nameLines) {
			text(g, l, textX, ty, complete ? DONE : Color.WHITE);
			ty += 15;
		}
		g.setFont(FontManager.getRunescapeSmallFont());
		if (measurable) {
			int barW = Math.max(60, card.x + card.width - 10 - textX);
			int pct = progress.getPercent() == null ? 0 : progress.getPercent();
			bar(g, textX, ty - 6, barW, 7, pct, 100);
			text(g, GoalProgressLabel.format(progress.getType(), progress.getCurrent(), progress.getTarget(), progress.getPercent()), textX, ty + 13, BEIGE);
			ty += 22;
		} else if (complete) {
			text(g, "Completed", textX, ty + 1, DONE);
			ty += 14;
		}
		if (syncing) {
			text(g, "Syncing with server...", textX, ty + 1, GREY);
		}

		// Complete (unfinished goals) and Delete, top right; each asks "Sure?" first.
		int bx = card.x + card.width - 8 - ACTION_W;
		int by = card.y + 7;
		String delKey = goal.getId() + ":delete";
		Rectangle del = new Rectangle(bx, by, ACTION_W, ACTION_H);
		smallButton(g, del, armed(delKey) ? "Sure?" : "Delete", armed(delKey) ? ORANGE : RED);
		rowHits.add(new Hit(del, () -> confirmThen(delKey, () -> act(goal, true)), armed(delKey) ? "Click again to delete this goal" : "Remove this goal from the roadmap"));
		if (!complete) {
			String doneKey = goal.getId() + ":complete";
			Rectangle done = new Rectangle(bx - 4 - ACTION_W, by, ACTION_W, ACTION_H);
			smallButton(g, done, armed(doneKey) ? "Sure?" : "Complete", armed(doneKey) ? ORANGE : GREEN);
			rowHits.add(new Hit(done, () -> confirmThen(doneKey, () -> act(goal, false)), armed(doneKey) ? "Click again to mark it complete" : "Mark this goal complete"));
		}
		return height;
	}

	private void smallButton(Graphics2D g, Rectangle r, String label, Color color) {
		boolean hover = mouse != null && r.contains(mouse);
		box(g, r, hover ? TAB_ON : SLOT, SLOT_EDGE, BUTTON_INNER);
		g.setFont(FontManager.getRunescapeSmallFont());
		centred(g, label, r.x + r.width / 2, r.y + 12, color);
	}

	private void message(Graphics2D g, Rectangle area, String message) {
		g.setFont(FontManager.getRunescapeFont());
		int lineY = area.y + area.height / 2 - 10;
		for (String line : wrap(message, g.getFontMetrics(), area.width - 60)) {
			centred(g, line, area.x + area.width / 2, lineY, Color.WHITE);
			lineY += 16;
		}
	}

	private void renderSwitcher(Graphics2D g, Rectangle anchor, Rectangle win, List<Hit> newHits) {
		List<RoadmapSummary> list = roadmaps;
		Roadmap current = roadmapManager.getCurrentRoadmap();
		int top = anchor.y + anchor.height + 2;
		int shown = Math.min(list.size(), Math.max(3, (win.y + win.height - 12 - top - 6) / 18));
		Rectangle box = new Rectangle(anchor.x, top, 260, shown * 18 + 6);
		switcherArea = box;
		java.util.function.Predicate<RoadmapSummary> open = s -> current != null && current.getCollectionId() == s.getCollectionId();
		switcher(g, box, list, open, RoadmapSummary::getTitle, s -> open.test(s) ? "open" : s.getGoalCount() + " goals", 0, 60, false,
			"Switch list", this::select, newHits);
	}

	// ------------------------------------------------------------------ tree (as the app draws it)

	private enum Guide { NONE, THROUGH, TEE, CORNER }

	/** Connector lines in the indent gutters: through, tee (a sibling follows) or corner (last). */
	private void drawGuides(Graphics2D g, Line line, int left, int y, int height) {
		if (line.guides.length == 0) {
			return;
		}
		g.setColor(GUIDE);
		g.setStroke(new BasicStroke(2f));
		int elbowY = y + 16;
		int bottom = y + height + ROW_GAP;
		int cardLeft = left + line.depth * INDENT;
		for (int c = 0; c < line.guides.length; c++) {
			int cx = left + c * INDENT + INDENT / 2;
			switch (line.guides[c]) {
				case THROUGH:
					g.drawLine(cx, y, cx, bottom);
					break;
				case TEE:
					g.drawLine(cx, y, cx, bottom);
					g.drawLine(cx, elbowY, cardLeft, elbowY);
					break;
				case CORNER:
					g.drawLine(cx, y, cx, elbowY);
					g.drawLine(cx, elbowY, cardLeft, elbowY);
					break;
				default:
					break;
			}
		}
		g.setStroke(new BasicStroke(1f));
	}

	/** Each goal after all of its prerequisites (depth first). */
	private static List<RoadmapGoal> orderTree(List<RoadmapGoal> tree) {
		Map<Integer, RoadmapGoal> byId = new HashMap<>();
		for (RoadmapGoal goal : tree) {
			byId.put(goal.getId(), goal);
		}
		List<RoadmapGoal> ordered = new ArrayList<>();
		Set<Integer> visited = new HashSet<>();
		for (RoadmapGoal goal : tree) {
			place(goal, byId, visited, ordered, new HashSet<>());
		}
		return ordered;
	}

	private static void place(RoadmapGoal goal, Map<Integer, RoadmapGoal> byId, Set<Integer> visited, List<RoadmapGoal> ordered, Set<Integer> inProgress) {
		if (visited.contains(goal.getId()) || !inProgress.add(goal.getId())) {
			return;
		}
		for (Integer dep : goal.getDependencyIds()) {
			RoadmapGoal d = byId.get(dep);
			if (d != null) {
				place(d, byId, visited, ordered, inProgress);
			}
		}
		inProgress.remove(goal.getId());
		if (visited.add(goal.getId())) {
			ordered.add(goal);
		}
	}

	/** Longest prerequisite chain per goal (0 = none). */
	private static int[] depths(List<RoadmapGoal> ordered) {
		Map<Integer, RoadmapGoal> byId = new HashMap<>();
		for (RoadmapGoal goal : ordered) {
			byId.put(goal.getId(), goal);
		}
		Map<Integer, Integer> memo = new HashMap<>();
		int[] out = new int[ordered.size()];
		for (int i = 0; i < ordered.size(); i++) {
			out[i] = depth(ordered.get(i), byId, memo, new HashSet<>());
		}
		return out;
	}

	private static int depth(RoadmapGoal goal, Map<Integer, RoadmapGoal> byId, Map<Integer, Integer> memo, Set<Integer> inProgress) {
		Integer cached = memo.get(goal.getId());
		if (cached != null) {
			return cached;
		}
		if (!inProgress.add(goal.getId())) {
			return 0;
		}
		int d = 0;
		for (Integer dep : goal.getDependencyIds()) {
			RoadmapGoal x = byId.get(dep);
			if (x != null) {
				d = Math.max(d, depth(x, byId, memo, inProgress) + 1);
			}
		}
		inProgress.remove(goal.getId());
		memo.put(goal.getId(), d);
		return d;
	}

	private static Guide[][] computeGuides(int[] depths) {
		Guide[][] result = new Guide[depths.length][];
		for (int i = 0; i < depths.length; i++) {
			int d = depths[i];
			Guide[] cols = new Guide[d];
			for (int c = 0; c < d; c++) {
				cols[c] = c == d - 1
					? (followsAt(depths, i, d) ? Guide.TEE : Guide.CORNER)
					: (followsAt(depths, i, c + 1) ? Guide.THROUGH : Guide.NONE);
			}
			result[i] = cols;
		}
		return result;
	}

	private static boolean followsAt(int[] depths, int from, int level) {
		for (int j = from + 1; j < depths.length; j++) {
			if (depths[j] < level) {
				return false;
			}
			if (depths[j] == level) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ input

	@Override
	boolean isShown() {
		return open;
	}

	@Override
	void closeWindow() {
		close();
	}

	@Override
	void scrolled(Point p, int rotation) {
		Rectangle area = body;
		Rectangle list = switcherArea;
		boolean overList = switcherOpen && list != null && list.contains(p);
		if (!overList && area != null && area.contains(p)) {
			scroll = Math.max(0, scroll + rotation * 40);
		}
	}

	@Override
	boolean keepsSwitcherOpen(Hit clicked) {
		return clicked != null && clicked.tooltip != null && clicked.tooltip.startsWith("Switch");
	}
}
