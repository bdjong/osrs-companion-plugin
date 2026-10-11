package com.osrscompanion;

import static org.junit.Assert.assertEquals;

import com.osrscompanion.FarmPatchProgress.Snapshot;
import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.runelite.farming.CropState;
import org.junit.Test;

public class FarmPatchProgressTest {
	private static Snapshot snap(CropState state, long done) {
		return new Snapshot(state, false, done);
	}

	@Test
	public void readyPatchIsToDoUntilReplanted() {
		Snapshot start = snap(CropState.HARVESTABLE, 1000);
		assertEquals(Status.TODO, FarmPatchProgress.classify(start, start));
		assertEquals(Status.TODO, FarmPatchProgress.classify(start, snap(CropState.EMPTY, 0)));
		assertEquals(Status.DONE, FarmPatchProgress.classify(start, snap(CropState.GROWING, 9000)));
	}

	@Test
	public void weedsAreNotAPlantedCrop() {
		Snapshot start = snap(CropState.EMPTY, 0);
		assertEquals(Status.TODO, FarmPatchProgress.classify(start, new Snapshot(CropState.GROWING, true, 0)));
	}

	@Test
	public void stillGrowingAtStartIsNotReady() {
		Snapshot start = snap(CropState.GROWING, 5000);
		assertEquals(Status.NOT_READY, FarmPatchProgress.classify(start, start));
		// The same planting read again a few seconds later is still the same crop.
		assertEquals(Status.NOT_READY, FarmPatchProgress.classify(start, snap(CropState.GROWING, 5030)));
		// Cleared and replanted anyway: done.
		assertEquals(Status.DONE, FarmPatchProgress.classify(start, snap(CropState.GROWING, 9000)));
	}

	@Test
	public void grownByItsTimerIsReadyNotGrowing() {
		// Time Tracking still says "growing" (the last state seen in game), but the timer reached the last stage.
		Snapshot start = new Snapshot(CropState.GROWING, false, 5000, true);
		assertEquals(Status.TODO, FarmPatchProgress.classify(start, start));
		assertEquals(Status.TODO, FarmPatchProgress.classify(start, snap(CropState.HARVESTABLE, 5000)));
		assertEquals(Status.DONE, FarmPatchProgress.classify(start, snap(CropState.GROWING, 9000)));
	}

	@Test
	public void diseasedAndDeadNeedAVisit() {
		assertEquals(Status.TODO, FarmPatchProgress.classify(snap(CropState.DISEASED, 0), snap(CropState.DISEASED, 0)));
		assertEquals(Status.TODO, FarmPatchProgress.classify(snap(CropState.DEAD, 0), snap(CropState.DEAD, 0)));
		assertEquals(Status.DONE, FarmPatchProgress.classify(snap(CropState.DEAD, 0), snap(CropState.GROWING, 7000)));
	}

	@Test
	public void unknownPatchesStayToDo() {
		assertEquals(Status.TODO, FarmPatchProgress.classify(null, null));
		assertEquals(Status.DONE, FarmPatchProgress.classify(null, snap(CropState.GROWING, 100)));
	}
}
