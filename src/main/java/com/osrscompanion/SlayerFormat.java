package com.osrscompanion;

import java.util.Locale;

/** How the Slayer helper writes times and amounts. No game access. */
final class SlayerFormat {
	private SlayerFormat() {
	}

	/** "4:05", "12:30", "1:02:09". */
	static String clock(long ms) {
		long total = Math.max(0, ms) / 1000;
		long h = total / 3600;
		long m = (total % 3600) / 60;
		long s = total % 60;
		return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
	}

	/** "18.5s" (under a minute), else like {@link #clock}. */
	static String seconds(long ms) {
		if (ms < 60_000) {
			return String.format(Locale.ROOT, "%.1fs", Math.max(0, ms) / 1000.0);
		}
		return clock(ms);
	}

	/** "950 gp", "33.5K", "2.35M". */
	static String gp(long gp) {
		if (Math.abs(gp) < 10_000) {
			return gp + " gp";
		}
		if (Math.abs(gp) < 1_000_000) {
			return trim(gp / 1000.0) + "K";
		}
		return trim(gp / 1_000_000.0) + "M";
	}

	private static String trim(double v) {
		String s = Math.abs(v) >= 100 ? String.format(Locale.ROOT, "%.0f", v) : Math.abs(v) >= 10 ? String.format(Locale.ROOT, "%.1f", v) : String.format(Locale.ROOT, "%.2f", v);
		if (s.contains(".")) {
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return s;
	}

	/** "north-east" for a step of (dx, dy) on the game map (y grows north); null when there is no step. */
	static String compass(int dx, int dy) {
		if (dx == 0 && dy == 0) {
			return null;
		}
		double angle = Math.toDegrees(Math.atan2(dy, dx));
		String[] names = {"east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east"};
		return names[(int) Math.round(((angle + 360) % 360) / 45.0) % 8];
	}
}
