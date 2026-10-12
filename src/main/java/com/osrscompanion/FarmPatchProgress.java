package com.osrscompanion;

/** A farm run patch's progress, as the website works it out (src/lib/farmRunGuide.ts). */
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

	private FarmPatchProgress() {
	}
}
