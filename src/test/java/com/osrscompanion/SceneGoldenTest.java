package com.osrscompanion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.model.FarmRoute;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import org.junit.Test;

/**
 * Golden renders of what the guides draw in the game world: patch outlines and labels, the house object to
 * use, the dailies NPC; with the hint arrow and world map marker they set (see {@link UiKit}).
 */
public class SceneGoldenTest {
	private final UiKit kit = new UiKit();
	private final Client client = mock(Client.class);
	private final ModelOutlineRenderer outlines = mock(ModelOutlineRenderer.class);
	private final WorldMapPointManager worldMap = mock(WorldMapPointManager.class);
	/** Outlines, hint arrows and map markers, in the order they happened (added to the drawing log). */
	private final List<String> events = new ArrayList<>();

	public SceneGoldenTest() {
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		doAnswer(a -> events.add("outline " + a.getArgument(0).toString() + " w=" + a.getArgument(1) + " " + rgb(a.getArgument(2)) + " f=" + a.getArgument(3)))
			.when(outlines).drawOutline(any(GameObject.class), anyInt(), any(Color.class), anyInt());
		doAnswer(a -> events.add("outline " + a.getArgument(0).toString() + " w=" + a.getArgument(1) + " " + rgb(a.getArgument(2)) + " f=" + a.getArgument(3)))
			.when(outlines).drawOutline(any(NPC.class), anyInt(), any(Color.class), anyInt());
		doAnswer(a -> events.add("hintArrow " + a.getArgument(0))).when(client).setHintArrow(any(WorldPoint.class));
		doAnswer(a -> events.add("hintArrow npc " + ((NPC) a.getArgument(0)).getName())).when(client).setHintArrow(any(NPC.class));
		doAnswer(a -> {
			WorldMapPoint p = a.getArgument(0);
			return events.add("mapPoint " + p.getWorldPoint() + " '" + p.getTooltip() + "' jump=" + p.isJumpOnClick());
		}).when(worldMap).add(any(WorldMapPoint.class));
	}

	private static String rgb(Color c) {
		return String.format("#%06x", c.getRGB() & 0xFFFFFF);
	}

	private static GameObject object(String name, int x, int y) {
		GameObject o = mock(GameObject.class, name);
		when(o.getWorldLocation()).thenReturn(new WorldPoint(x, y, 0));
		when(o.getCanvasTilePoly()).thenReturn(new Polygon(new int[]{x - 3000, x - 2980, x - 3000, x - 3020}, new int[]{200, 210, 220, 210}, 4));
		when(o.getCanvasTextLocation(any(Graphics2D.class), any(String.class), anyInt())).thenReturn(new Point(x - 3010, 190));
		return o;
	}

	private UiKit.Rendered withEvents(UiKit.Rendered r) {
		List<String> log = new ArrayList<>(events);
		log.add("--- drawing");
		log.addAll(r.log);
		return new UiKit.Rendered(log, r.image);
	}

	// ---- farm run patches ------------------------------------------------------------------------

	@Test
	public void farmRunPatchesWithLabelsAndHouseObject() throws Exception {
		FarmRunManager manager = mock(FarmRunManager.class);
		FarmRoute route = UiSamples.route();
		FarmRoute.Stop stop = route.getStops().get(0);
		FarmRoute.Patch herb = stop.getPatches().get(0);
		FarmRoute.Patch flower = stop.getPatches().get(1);
		FarmRoute.Patch allotment = stop.getPatches().get(2);
		when(manager.isRunning()).thenReturn(true);
		when(manager.statusOf(herb)).thenReturn(Status.TODO);
		when(manager.nextStep(herb)).thenReturn("Harvest the herbs");
		when(manager.statusOf(flower)).thenReturn(Status.DONE);
		when(manager.needsCompost(flower)).thenReturn(true);
		when(manager.statusOf(allotment)).thenReturn(Status.NOT_READY);
		FarmRunSceneOverlay overlay = new FarmRunSceneOverlay(client, manager, outlines, worldMap, kit.itemManager());
		Constructor<?> found = Class.forName("com.osrscompanion.FarmRunSceneOverlay$Found").getDeclaredConstructor(GameObject.class, FarmRoute.Patch.class);
		found.setAccessible(true);
		WindowDriver.set(overlay, "found", Arrays.asList(
			found.newInstance(object("herb-patch", 3058, 3311), herb),
			found.newInstance(object("flower-patch", 3054, 3307), flower),
			found.newInstance(object("allotment-patch", 3050, 3307), allotment)));
		WindowDriver.set(overlay, "houseObject", object("portal-nexus", 3100, 3300));
		WindowDriver.set(overlay, "houseObjectName", "Portal Nexus");
		UiKit.golden("scene-farm-run", withEvents(kit.render(overlay, 400, 300)));
	}

	@Test
	public void farmCapeBlocksPicking() throws Exception {
		FarmRunManager manager = mock(FarmRunManager.class);
		FarmRoute.Patch herb = UiSamples.route().getStops().get(2).getPatches().get(0);
		when(manager.isRunning()).thenReturn(true);
		when(manager.statusOf(herb)).thenReturn(Status.TODO);
		when(manager.capeBlocks(herb)).thenReturn(true);
		FarmRunSceneOverlay overlay = new FarmRunSceneOverlay(client, manager, outlines, worldMap, kit.itemManager());
		Constructor<?> found = Class.forName("com.osrscompanion.FarmRunSceneOverlay$Found").getDeclaredConstructor(GameObject.class, FarmRoute.Patch.class);
		found.setAccessible(true);
		WindowDriver.set(overlay, "found", Arrays.asList(found.newInstance(object("guild-herb", 3060, 3300), herb)));
		UiKit.golden("scene-farm-cape", withEvents(kit.render(overlay, 400, 300)));
	}

	// ---- slayer guide: the house object on the way --------------------------------------------------

	@Test
	public void slayerGuideHouseObject() {
		SlayerHelperManager manager = mock(SlayerHelperManager.class);
		when(manager.isGuiding()).thenReturn(true);
		SlayerGuideHouseOverlay overlay = new SlayerGuideHouseOverlay(client, manager, mock(FarmRunManager.class), outlines);
		WindowDriver.set(overlay, "houseObject", object("jewellery-box", 3080, 3300));
		WindowDriver.set(overlay, "houseObjectName", "Ornate jewellery box");
		UiKit.golden("scene-slayer-house", withEvents(kit.render(overlay, 400, 300)));
	}

	// ---- dailies: the NPC, the arrow and the map marker --------------------------------------------

}
