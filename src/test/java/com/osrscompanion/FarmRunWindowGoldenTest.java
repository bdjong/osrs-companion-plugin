package com.osrscompanion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.reflect.TypeToken;
import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.ui.UiKit;
import java.awt.event.KeyEvent;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Before;
import org.junit.Test;

/** Golden renders of the farm run window, with the mouse on it (see {@link UiKit}, {@link WindowDriver}). */
public class FarmRunWindowGoldenTest {
	private static final int W = 1000;
	private static final int H = 700;

	private final UiKit kit = new UiKit();
	private final Client client = mock(Client.class);
	private final FarmRunManager manager = mock(FarmRunManager.class);
	private final FarmRoute route = UiSamples.route();
	private FarmRunWindow window;

	@Before
	public void setUp() {
		when(client.getCanvasWidth()).thenReturn(W);
		when(client.getCanvasHeight()).thenReturn(H);
		when(manager.isOpen()).thenReturn(true);
		when(manager.isLoaded()).thenReturn(true);
		when(manager.getRoute()).thenReturn(route);
		List<FarmRoute.RouteSummary> routes = UiSamples.GSON.fromJson(
			"[{\"id\":7,\"name\":\"Herbs + trees\",\"active\":true},{\"id\":8,\"name\":\"Herbs only (fast)\",\"active\":false},{\"id\":9,\"name\":\"Everything\",\"active\":false}]",
			new TypeToken<List<FarmRoute.RouteSummary>>() { }.getType());
		when(manager.getRoutes()).thenReturn(routes);
		when(manager.progress()).thenReturn(new int[]{0, 6});
		when(manager.statusOf(any())).thenReturn(Status.TODO);
		for (FarmRoute.Stop s : route.getStops()) {
			when(manager.travelOf(s)).thenReturn(s.getTravel());
		}
		when(manager.loadout()).thenReturn(route.getInventory());
		when(manager.inventory()).thenReturn(route.getInventory());
		when(manager.nextRunAt()).thenReturn(-1L);
		when(manager.carried(anyInt())).thenReturn(0);
		when(manager.carried(5341)).thenReturn(1);
		when(manager.carried(5295)).thenReturn(1);
		when(manager.inLeprechaun(anyInt())).thenReturn(0);
		when(manager.inLeprechaun(952)).thenReturn(1);
		window = new FarmRunWindow(client, manager, kit.itemManager());
	}

	private UiKit.Rendered render() {
		// One render to lay out the clickable areas, then the one recorded.
		kit.render(window, W, H);
		return kit.render(window, W, H);
	}

	@Test
	public void routeBeforeTheRun() {
		UiKit.golden("farm-window-before-run", render());
	}

	@Test
	public void hoverOnALoadoutItemShowsItsTooltip() {
		render();
		WindowDriver.move(window, WindowDriver.area(window, "Ranarr seed").centre());
		UiKit.golden("farm-window-item-tooltip", kit.render(window, W, H));
	}

	@Test
	public void duringTheRunAtTheSecondStop() {
		when(manager.isRunning()).thenReturn(true);
		when(manager.getStopIndex()).thenReturn(1);
		when(manager.getSelectedStop()).thenReturn(1);
		when(manager.progress()).thenReturn(new int[]{3, 6});
		when(manager.statusOf(route.getStops().get(0).getPatches().get(0))).thenReturn(Status.DONE);
		when(manager.statusOf(route.getStops().get(0).getPatches().get(1))).thenReturn(Status.SKIPPED);
		when(manager.statusOf(route.getStops().get(0).getPatches().get(2))).thenReturn(Status.NOT_READY);
		when(manager.nextRunAt()).thenReturn(System.currentTimeMillis() / 1000 + 2 * 3600 + 15 * 60 + 30);
		UiKit.golden("farm-window-running", render());
	}

	@Test
	public void routeSwitcherOpen() {
		render();
		WindowDriver.click(window, WindowDriver.area(window, "Switch to another route").centre());
		UiKit.golden("farm-window-switcher", kit.render(window, W, H));
	}

	@Test
	public void escapeClosesTheSwitcherFirst() {
		render();
		WindowDriver.click(window, WindowDriver.area(window, "Switch to another route").centre());
		WindowDriver.key(window, KeyEvent.VK_ESCAPE);
		UiKit.golden("farm-window-before-run-after-escape", kit.render(window, W, H));
	}

	@Test
	public void runComplete() {
		when(manager.isRunning()).thenReturn(true);
		when(manager.isComplete()).thenReturn(true);
		when(manager.getStopIndex()).thenReturn(2);
		when(manager.getSelectedStop()).thenReturn(2);
		when(manager.progress()).thenReturn(new int[]{6, 6});
		when(manager.statusOf(any())).thenReturn(Status.DONE);
		when(manager.nextRunAt()).thenReturn(0L);
		UiKit.golden("farm-window-complete", render());
	}

	@Test
	public void routeChangedOnTheWebsite() {
		FarmRoute changed = UiSamples.GSON.fromJson(UiSamples.ROUTE_JSON.replace("\"review_changes\":0", "\"review_changes\":2"), FarmRoute.class);
		when(manager.getRoute()).thenReturn(changed);
		for (FarmRoute.Stop s : changed.getStops()) {
			when(manager.travelOf(s)).thenReturn(s.getTravel());
		}
		UiKit.golden("farm-window-review-changes", render());
	}

	@Test
	public void noRouteYetLoading() {
		when(manager.getRoute()).thenReturn(null);
		when(manager.isLoaded()).thenReturn(false);
		when(manager.isLoading()).thenReturn(true);
		UiKit.golden("farm-window-loading", render());
	}

	@Test
	public void noRouteError() {
		when(manager.getRoute()).thenReturn(null);
		when(manager.getError()).thenReturn("Could not reach OSRS Companion (HTTP 503)");
		UiKit.golden("farm-window-error", render());
	}

	@Test
	public void hiddenWhileTheBankIsOpen() {
		when(client.getWidget(InterfaceID.Bankmain.UNIVERSE)).thenReturn(mock(net.runelite.api.widgets.Widget.class));
		UiKit.golden("farm-window-bank-open", kit.render(window, 200, 100));
	}

	@Test
	public void smallScreenIsTooSmall() {
		when(client.getCanvasWidth()).thenReturn(500);
		UiKit.golden("farm-window-too-small", kit.render(window, 200, 100));
	}

	@Test
	public void scrolledStopsOnASmallScreen() {
		when(client.getCanvasWidth()).thenReturn(765);
		when(client.getCanvasHeight()).thenReturn(503);
		kit.render(window, 765, 503);
		java.awt.Rectangle stops = (java.awt.Rectangle) WindowDriver.get(window, "stopsArea");
		WindowDriver.scroll(window, new java.awt.Point(stops.x + 20, stops.y + 20), 3);
		UiKit.golden("farm-window-small-scrolled", kit.render(window, 765, 503));
	}
}
