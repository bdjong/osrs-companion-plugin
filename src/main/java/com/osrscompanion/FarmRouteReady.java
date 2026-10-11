package com.osrscompanion;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The last farm run's patches, kept to tell you when that route can be run again: once every
 * patch planted on it is fully grown (or dead, or picked: anything but still growing). Saved per
 * account so it survives a client restart. Pure data and logic so it can be tested without a client.
 */
@Getter
@NoArgsConstructor
final class FarmRouteReady {
	/** A planted patch, with what it takes to find RuneLite's patch again without the route. */
	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	static final class PatchRef {
		private String key;
		private String region;
		private int varbit;
		private String impl;
	}

	private String route;
	private List<PatchRef> patches = new ArrayList<>();
	/** When the last of them is expected to be grown (epoch seconds, 0: not known), for when no patch can be read. */
	private long readyAt;
	/** The notification went out already. */
	private boolean notified;

	FarmRouteReady(String route, List<PatchRef> patches, long readyAt) {
		this.route = route;
		this.patches.addAll(patches);
		this.readyAt = readyAt;
	}

	void markNotified() {
		notified = true;
	}

	/**
	 * Whether the route is ready again.
	 *
	 * @param growing per patch: true while it is still growing, false once it is not, null when it can't be read
	 * @param nowSec  the time now (epoch seconds)
	 */
	static boolean isReady(List<Boolean> growing, long readyAt, long nowSec) {
		boolean known = false;
		for (Boolean g : growing) {
			if (g == null) {
				continue;
			}
			if (g) {
				return false;
			}
			known = true;
		}
		// No patch could be read (farming data not loaded yet): go by the time the run expected.
		return known || (readyAt > 0 && nowSec >= readyAt);
	}

	/** The notification: "Farm route Herb run is ready again: all 8 patches are grown." */
	static String message(String route, int patches) {
		return "Farm route " + (route == null || route.isEmpty() ? "" : route + " ") + "is ready again: "
			+ (patches == 1 ? "its patch is" : "all " + patches + " patches are") + " grown.";
	}
}
