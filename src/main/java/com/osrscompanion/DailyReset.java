package com.osrscompanion;

/** The daily reset (00:00 UTC) in plain maths, so it is unit-tested without a clock. */
final class DailyReset {
	static final long DAY_MS = 86_400_000L;

	private DailyReset() {
	}

	/** The UTC day number (days since 1 January 1970) of a moment. */
	static long day(long millis) {
		return Math.floorDiv(millis, DAY_MS);
	}

	/** The next 00:00 UTC after a moment. */
	static long nextReset(long millis) {
		return (day(millis) + 1) * DAY_MS;
	}

	/** "5h 12m", "48m" or "1d 3h" until a moment; "now" once it has passed. */
	static String until(long now, long at) {
		long minutes = Math.max(0, (at - now + 59_999) / 60_000);
		if (minutes <= 0) {
			return "now";
		}
		long hours = minutes / 60;
		if (hours >= 24) {
			return hours / 24 + "d " + hours % 24 + "h";
		}
		return hours > 0 ? hours + "h " + minutes % 60 + "m" : minutes + "m";
	}
}
