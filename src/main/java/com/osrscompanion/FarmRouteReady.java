package com.osrscompanion;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

/**
 * The last farm run, as the website last worked it out: when the patches it planted are all grown again. Saved per
 * account so the notification still comes after a client restart, and while logged out.
 */
@Getter
final class FarmRouteReady {
	private String route;
	private int patches;
	/** When the route is ready again (epoch seconds, 0: not known). */
	@SerializedName("ready_at")
	private long readyAt;
	/** When the run ended (epoch ms): which run this is. */
	@SerializedName("ended_at")
	private long endedAt;
	/** The notification went out already. */
	private boolean notified;

	void markNotified() {
		notified = true;
	}

	boolean isReady(long nowSec) {
		return readyAt > 0 && nowSec >= readyAt;
	}

	/** The notification: "Farm route Herb run is ready again: all 8 patches are grown." */
	static String message(String route, int patches) {
		return "Farm route " + (route == null || route.isEmpty() ? "" : route + " ") + "is ready again: "
			+ (patches == 1 ? "its patch is" : "all " + patches + " patches are") + " grown.";
	}
}
