package com.osrscompanion;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Watches for the plugin freezing the game: every {@value #CHECK_EVERY_MS} ms, on its own daemon
 * thread, it looks at the section of our code the {@link Guard} says is running on the client
 * thread. One that has run longer than {@value #FREEZE_MS} ms is a freeze: the client thread's stack
 * is captured ({@link Thread#getStackTrace()}) and handed to {@link Guard#frozen} once per incident,
 * which reports it and turns that feature off (skipped from then on).
 *
 * It also watches the whole client thread, wherever it is stuck ({@link #checkClient()}): the plugin
 * calls {@link #beat()} on every client tick and frame; while in game, a heartbeat that has not moved
 * for {@value #STALL_MS} ms is a stall, and a watched thread that is no longer alive (an Error escaped
 * the client loop) is reported once with the last stack seen. Neither trips a breaker; the
 * {@link StallListener} hears them, and hears when the heartbeat resumes.
 *
 * The thread is the plugin's own (with its own uncaught-exception handler, nothing global) and also
 * runs small background chores ({@link #execute}) that must not wait behind a stuck shared executor,
 * such as persisting a fatal incident. {@link #check()} is package-private for tests.
 */
@Slf4j
final class FreezeWatchdog {
	static final long CHECK_EVERY_MS = 500;
	static final long FREEZE_MS = 2_000;
	static final String THREAD_NAME = "osrs-companion-watchdog";
	/** No heartbeat for this long while in game: the whole client thread is stuck. */
	static final long STALL_MS = 5_000;
	/** From this much silence on, the client thread's stack is sampled (kept for a thread that dies). */
	static final long SAMPLE_AFTER_MS = 1_000;
	/** The feature of a stall that is not inside one of our sections. */
	static final String CLIENT_FEATURE = "client";

	/** Hears whole-client-thread stalls and a terminated client thread. Called on the watchdog thread. */
	interface StallListener {
		void onStall(Stall stall);

		/** The heartbeat came back after a reported stall that lasted {@code stalledMs}. */
		void onResume(Stall stall, long stalledMs);
	}

	/** One stall incident of the client thread. */
	static final class Stall {
		final String feature;
		final long elapsedMs;
		/** The client thread's stack (the last one seen when it terminated), never null. */
		final StackTraceElement[] stack;
		/** The thread is no longer alive: the client loop died. */
		final boolean terminated;
		/** {@link Thread.State} when it was seen, for the report. */
		final String threadState;

		Stall(String feature, long elapsedMs, StackTraceElement[] stack, boolean terminated, String threadState) {
			this.feature = feature;
			this.elapsedMs = elapsedMs;
			this.stack = stack == null ? new StackTraceElement[0] : stack;
			this.terminated = terminated;
			this.threadState = threadState;
		}
	}

	private static final StallListener NO_STALL_LISTENER = new StallListener() {
		@Override
		public void onStall(Stall stall) {
		}

		@Override
		public void onResume(Stall stall, long stalledMs) {
		}
	};

	private final Guard guard;
	private final long freezeNanos;
	private ScheduledExecutorService thread;
	private final long stallNanos;
	/** The section last reported, so one incident is reported once. */
	private long reportedSection = -1;

	/** Bumped by the client thread only (one writer): one volatile write per tick or frame. */
	private volatile long beats;
	private volatile BooleanSupplier inGame = () -> false;
	private volatile StallListener stallListener = NO_STALL_LISTENER;
	// The rest is touched by the watchdog thread only (check runs there, or in tests).
	private Thread seenThread;
	private long seenBeats;
	private long lastBeatNanos;
	private StackTraceElement[] lastStack;
	/** The reported stall waiting for the heartbeat to resume, or null. */
	private Stall stall;
	/** The stall is one our section check already reported (not reported twice). */
	private boolean stallCovered;
	private boolean terminatedReported;

	FreezeWatchdog(Guard guard) {
		this(guard, FREEZE_MS, STALL_MS);
	}

	FreezeWatchdog(Guard guard, long freezeMs) {
		this(guard, freezeMs, STALL_MS);
	}

	FreezeWatchdog(Guard guard, long freezeMs, long stallMs) {
		this.guard = guard;
		this.freezeNanos = TimeUnit.MILLISECONDS.toNanos(freezeMs);
		this.stallNanos = TimeUnit.MILLISECONDS.toNanos(stallMs);
	}

	/** The client thread is alive and looping: call on every client tick and frame. Cheap. */
	void beat() {
		beats++;
	}

	/** Whether the player is in game (logged in or loading), when the heartbeat is expected. Any thread. */
	void setInGame(BooleanSupplier inGame) {
		this.inGame = inGame == null ? () -> false : inGame;
	}

	void setStallListener(StallListener listener) {
		this.stallListener = listener == null ? NO_STALL_LISTENER : listener;
	}

	synchronized void start() {
		if (thread != null) {
			return;
		}
		ThreadFactory factory = r -> {
			Thread t = new Thread(r, THREAD_NAME);
			t.setDaemon(true);
			t.setUncaughtExceptionHandler((th, e) -> log.debug("Watchdog thread failed", e));
			return t;
		};
		thread = Executors.newSingleThreadScheduledExecutor(factory);
		thread.scheduleWithFixedDelay(this::checkSafely, CHECK_EVERY_MS, CHECK_EVERY_MS, TimeUnit.MILLISECONDS);
	}

	synchronized void stop() {
		if (thread != null) {
			thread.shutdownNow();
			thread = null;
		}
	}

	synchronized boolean isRunning() {
		return thread != null;
	}

	/** Runs a small chore on the watchdog's thread (or here when it is not running). Never throws. */
	void execute(Runnable chore) {
		ScheduledExecutorService t;
		synchronized (this) {
			t = thread;
		}
		try {
			if (t != null) {
				t.execute(() -> runQuietly(chore));
				return;
			}
		} catch (RuntimeException rejected) {
			// shutting down: run it here
		}
		runQuietly(chore);
	}

	private void checkSafely() {
		// A periodic task that throws is cancelled: never let one escape.
		runQuietly(this::check);
		runQuietly(this::checkClient);
	}

	/** One look at the client thread; true when it found (and reported) a new freeze. */
	boolean check() {
		Guard.Section section = guard.currentSection();
		Thread watched = guard.watchedThread();
		if (section == null || watched == null || section.id == reportedSection) {
			return false;
		}
		long elapsedNanos = guard.nanoTime() - section.startNanos;
		if (elapsedNanos < freezeNanos) {
			return false;
		}
		StackTraceElement[] stack = watched.getStackTrace();
		// Still the same run? Otherwise the stack belongs to something else.
		Guard.Section now = guard.currentSection();
		if (now == null || now.id != section.id) {
			return false;
		}
		reportedSection = section.id;
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
		log.warn("OSRS Companion: {} has blocked the client thread for {} ms:{}", section.feature, elapsedMs, format(stack));
		guard.frozen(section.feature, elapsedMs, stack);
		return true;
	}

	/**
	 * One look at the whole client thread: true when it found (and reported) a new stall or a
	 * terminated thread. See the class comment.
	 */
	boolean checkClient() {
		Thread watched = guard.watchedThread();
		long now = guard.nanoTime();
		if (watched != seenThread) {
			// A new client thread (or none): start over.
			seenThread = watched;
			seenBeats = beats;
			lastBeatNanos = now;
			lastStack = null;
			stall = null;
			stallCovered = false;
			terminatedReported = false;
		}
		if (watched == null) {
			return false;
		}
		if (!watched.isAlive()) {
			if (terminatedReported) {
				return false;
			}
			terminatedReported = true;
			long silentMs = TimeUnit.NANOSECONDS.toMillis(now - lastBeatNanos);
			Stall dead = new Stall(CLIENT_FEATURE, silentMs, lastStack, true, String.valueOf(watched.getState()));
			log.warn("OSRS Companion: the client thread ({}) has terminated; last heartbeat {} ms ago; last stack seen:{}",
				watched.getName(), silentMs, lastStack == null ? " none" : format(lastStack));
			stallListener.onStall(dead);
			return true;
		}
		long b = beats;
		if (b != seenBeats) {
			long stalledMs = TimeUnit.NANOSECONDS.toMillis(now - lastBeatNanos);
			seenBeats = b;
			lastBeatNanos = now;
			lastStack = null;
			Stall ended = stall;
			boolean covered = stallCovered;
			stall = null;
			stallCovered = false;
			if (ended != null && !covered) {
				log.warn("OSRS Companion: the client thread is running again after a stall of {} ms", stalledMs);
				stallListener.onResume(ended, stalledMs);
			}
			return false;
		}
		if (stall != null) {
			return false;
		}
		if (!isInGame()) {
			// No heartbeat expected (login screen, starting up): not a stall.
			lastBeatNanos = now;
			lastStack = null;
			return false;
		}
		long silentNanos = now - lastBeatNanos;
		if (silentNanos < TimeUnit.MILLISECONDS.toNanos(SAMPLE_AFTER_MS) && silentNanos < stallNanos) {
			return false;
		}
		StackTraceElement[] stack = watched.getStackTrace();
		if (stack != null && stack.length > 0) {
			lastStack = stack;
		}
		if (silentNanos < stallNanos || beats != seenBeats) {
			return false;
		}
		Guard.Section section = guard.currentSection();
		String feature = section == null ? CLIENT_FEATURE : section.feature;
		long silentMs = TimeUnit.NANOSECONDS.toMillis(silentNanos);
		stall = new Stall(feature, silentMs, stack, false, String.valueOf(watched.getState()));
		// Our own section check already reported this one (with its feature turned off): once is enough.
		stallCovered = section != null && section.id == reportedSection;
		if (stallCovered) {
			return false;
		}
		log.warn("OSRS Companion: the client thread has not ticked for {} ms ({}, in {}):{}", silentMs,
			stall.threadState, feature, format(stack));
		stallListener.onStall(stall);
		return true;
	}

	private boolean isInGame() {
		try {
			return inGame.getAsBoolean();
		} catch (RuntimeException e) {
			return false;
		}
	}

	static String format(StackTraceElement[] stack) {
		StringBuilder sb = new StringBuilder();
		if (stack == null) {
			return "";
		}
		for (StackTraceElement f : stack) {
			sb.append("\n\tat ").append(f);
		}
		return sb.toString();
	}

	private static void runQuietly(Runnable r) {
		try {
			r.run();
		} catch (ThreadDeath death) {
			throw death;
		} catch (Throwable e) {
			try {
				log.debug("Watchdog chore failed", e);
			} catch (Throwable ignored) {
				// nothing left to do
			}
		}
	}
}
