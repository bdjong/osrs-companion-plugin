package com.osrscompanion;

import java.util.Arrays;
import java.util.List;

/**
 * Keeps the last fatal error or freeze in the RuneLite config, written while it happens, so it
 * survives what cannot be caught (the JVM running out of memory, the player killing a frozen
 * client). The next start reads it ({@link #peekRaw()}), reports it once a player is logged in and
 * clears it once that report was sent ({@link #clearIf}); a clean plugin stop clears what this session
 * saved, since that was reported live.
 *
 * The config is reached through {@link Storage}, so this is unit-testable.
 */
final class IncidentStore {
	static final String KEY = "pendingIncident";
	/** Room for a client thread stall's {@value #STALL_FRAMES} frames, other plugins' included. */
	static final int MAX_STACK = 6_000;
	/** Frames kept of a whole-client-thread stall: enough to see whose code it is. */
	static final int STALL_FRAMES = 40;
	static final int MAX_MESSAGE = 300;
	private static final String VERSION = "v1";

	interface Storage {
		String get();

		void set(String value);

		void unset();

		/** Writes the config out now if it can (best effort). */
		void flush();
	}

	/** What is kept of an incident: what, where, when, and a short stack. */
	static final class Incident {
		final String level;
		final String feature;
		final String message;
		final String stack;
		final long timeMs;

		Incident(String level, String feature, String message, String stack, long timeMs) {
			this.level = level;
			this.feature = feature;
			this.message = message;
			this.stack = stack;
			this.timeMs = timeMs;
		}
	}

	private final Storage storage;

	IncidentStore(Storage storage) {
		this.storage = storage;
	}

	void save(Incident incident) {
		storage.set(encode(incident));
		storage.flush();
	}

	/** The pending incident (cleared now), or null. */
	Incident take() {
		String raw = storage.get();
		if (raw == null) {
			return null;
		}
		storage.unset();
		return decode(raw);
	}

	/** The pending incident as stored (left in place), or null. */
	String peekRaw() {
		return storage.get();
	}

	/**
	 * Clears the pending incident only if it is still {@code raw} (a newer one saved since is kept).
	 * True when it was cleared.
	 */
	boolean clearIf(String raw) {
		String now = storage.get();
		if (raw == null || !raw.equals(now)) {
			return false;
		}
		storage.unset();
		return true;
	}

	void clear() {
		if (storage.get() != null) {
			storage.unset();
		}
	}

	static String encode(Incident i) {
		return VERSION + "\n" + i.timeMs + "\n" + oneLine(i.level, 16) + "\n" + oneLine(i.feature, 64) + "\n"
			+ oneLine(i.message, MAX_MESSAGE) + "\n" + cut(i.stack, MAX_STACK);
	}

	static Incident decode(String raw) {
		List<String> parts = Arrays.asList(raw.split("\n", 6));
		if (parts.size() < 5 || !VERSION.equals(parts.get(0))) {
			return null;
		}
		long time;
		try {
			time = Long.parseLong(parts.get(1));
		} catch (NumberFormatException e) {
			return null;
		}
		return new Incident(parts.get(2), emptyToNull(parts.get(3)), emptyToNull(parts.get(4)),
			parts.size() > 5 ? emptyToNull(parts.get(5)) : null, time);
	}

	/** The stack as text, at most {@code maxFrames} frames. */
	static String shortStack(Throwable t, int maxFrames) {
		if (t == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder(String.valueOf(t));
		StackTraceElement[] frames = t.getStackTrace();
		appendFrames(sb, frames, maxFrames);
		return sb.toString();
	}

	static String shortStack(StackTraceElement[] frames, int maxFrames) {
		return shortStack("Client thread", frames, maxFrames);
	}

	/** {@code header}, then at most {@code maxFrames} frames (every frame, whoever's code it is). */
	static String shortStack(String header, StackTraceElement[] frames, int maxFrames) {
		StringBuilder sb = new StringBuilder(header);
		appendFrames(sb, frames, maxFrames);
		return sb.toString();
	}

	private static void appendFrames(StringBuilder sb, StackTraceElement[] frames, int maxFrames) {
		if (frames == null) {
			return;
		}
		for (int i = 0; i < frames.length && i < maxFrames; i++) {
			sb.append("\n\tat ").append(frames[i]);
		}
		if (frames.length > maxFrames) {
			sb.append("\n\t... ").append(frames.length - maxFrames).append(" more");
		}
	}

	private static String oneLine(String s, int max) {
		return s == null ? "" : cut(s.replace('\r', ' ').replace('\n', ' '), max);
	}

	private static String cut(String s, int max) {
		return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
	}

	private static String emptyToNull(String s) {
		return s == null || s.isEmpty() ? null : s;
	}
}
