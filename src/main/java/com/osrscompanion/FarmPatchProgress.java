package com.osrscompanion;

import com.osrscompanion.runelite.farming.CropState;

/**
 * Decides, from a patch's farming state when the run started and now, whether the
 * patch still needs a visit. Pure logic so it can be tested without a game client.
 */
final class FarmPatchProgress {
	enum Status {
		/** Still to harvest / clear / plant. */
		TODO,
		/** Planted (or refilled) during this run. */
		DONE,
		/** Still growing when the run started: nothing to do this time. */
		NOT_READY,
		/** Skipped by the player. */
		SKIPPED;

		boolean isOpen() {
			return this == TODO;
		}
	}

	/** One reading of a patch: its crop state, whether it holds weeds, and when it is expected to finish. */
	static final class Snapshot {
		final CropState state;
		final boolean weeds;
		final long doneEstimate;

		Snapshot(CropState state, boolean weeds, long doneEstimate) {
			this.state = state;
			this.weeds = weeds;
			this.doneEstimate = doneEstimate;
		}

		/** A crop (not weeds) that is still growing. */
		boolean growingCrop() {
			return state == CropState.GROWING && !weeds;
		}
	}

	/** A replanted crop finishes later than the old one did; anything within a minute is the same planting. */
	static final long SAME_PLANTING_SECONDS = 60;

	private FarmPatchProgress() {
	}

	/**
	 * @param start the patch when the run started (null: never seen, so not known)
	 * @param now   the patch now (null: not known)
	 */
	static Status classify(Snapshot start, Snapshot now) {
		if (start != null && start.growingCrop()) {
			// It was still growing when the run started: only counts as done if replanted anyway.
			if (now != null && now.growingCrop() && now.doneEstimate > start.doneEstimate + SAME_PLANTING_SECONDS) {
				return Status.DONE;
			}
			return Status.NOT_READY;
		}
		if (now != null && now.growingCrop()) {
			return Status.DONE;
		}
		return Status.TODO;
	}
}
