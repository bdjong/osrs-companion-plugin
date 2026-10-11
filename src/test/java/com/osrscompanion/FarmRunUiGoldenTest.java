package com.osrscompanion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.ui.UiKit;
import org.junit.Test;

/** Golden renders of the farm run guide's step box (see {@link UiKit}). */
public class FarmRunUiGoldenTest {
	private final UiKit kit = new UiKit();
	private final TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
	private final FarmRunManager manager = mock(FarmRunManager.class);
	private final FarmRoute route = UiSamples.route();

	private FarmRunOverlay stepBox() {
		return new FarmRunOverlay(config, manager, kit.itemManager());
	}

	private void atStop(int index, int done, int total) {
		FarmRoute.Stop stop = route.getStops().get(index);
		when(manager.isRunning()).thenReturn(true);
		when(manager.getRoute()).thenReturn(route);
		when(manager.getCurrentStop()).thenReturn(stop);
		when(manager.getStopIndex()).thenReturn(index);
		when(manager.progress()).thenReturn(new int[]{done, total});
		when(manager.travelOf(stop)).thenReturn(stop.getTravel());
		when(manager.statusOf(any())).thenReturn(Status.TODO);
		when(manager.compostStack()).thenReturn(route.getInventory().get(3));
	}

	private FarmRoute.Patch patch(int stop, int i) {
		return route.getStops().get(stop).getPatches().get(i);
	}

	@Test
	public void firstStopWithMixedPatchStates() {
		atStop(0, 1, 6);
		when(manager.statusOf(patch(0, 0))).thenReturn(Status.DONE);
		when(manager.statusOf(patch(0, 1))).thenReturn(Status.NOT_READY);
		when(manager.needsCompost(patch(0, 0))).thenReturn(true);
		UiKit.golden("farm-run-overlay-stop", kit.render(stepBox(), 400, 500));
	}

	@Test
	public void minigameCooldownTakesTheFallbackWithCapeAndCompostBehind() {
		atStop(1, 3, 6);
		FarmRoute.Stop stop = route.getStops().get(1);
		when(manager.travelOf(stop)).thenReturn(stop.getTravel().getFallback());
		when(manager.minigameCooldownMinutes()).thenReturn(1);
		when(manager.needsFarmingCape()).thenReturn(true);
		when(manager.compostBehind()).thenReturn(2);
		UiKit.golden("farm-run-overlay-fallback", kit.render(stepBox(), 400, 500));
	}

	@Test
	public void lastStopSkippedAndCompostBin() {
		atStop(2, 5, 6);
		when(manager.statusOf(patch(2, 0))).thenReturn(Status.SKIPPED);
		when(manager.minigameCooldownMinutes()).thenReturn(12);
		when(manager.compostBehind()).thenReturn(1);
		UiKit.golden("farm-run-overlay-last", kit.render(stepBox(), 400, 500));
	}

	@Test
	public void runCompleteWithCompostStillToUse() {
		atStop(2, 6, 6);
		when(manager.isComplete()).thenReturn(true);
		when(manager.needsCompost(patch(2, 0))).thenReturn(true);
		UiKit.golden("farm-run-overlay-complete", kit.render(stepBox(), 400, 300));
	}

	@Test
	public void nothingWithoutARun() {
		UiKit.golden("farm-run-overlay-none", kit.render(stepBox(), 100, 100));
	}
}
