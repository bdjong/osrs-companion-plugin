package com.osrscompanion;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Watches for the plugin freezing the game: every {@value #CHECK_EVERY_MS} ms, on its own daemon
 * thread, it looks at the section of our code the {@link Guard} says is running on the client
 * thread. One that has run longer than {@value #FREEZE_MS} ms is a freeze: the client thread's stack
 * is captured ({@link Thread#getStackTrace()}) and handed to {@link Guard#frozen} once per incident,
 * which reports it and turns that feature off (skipped from then on). Only our own sections are
 * timed; the client thread outside them is not watched.
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

	private final Guard guard;
	private final long freezeNanos;
	private ScheduledExecutorService thread;
	/** The section last reported, so one incident is reported once. */
	private long reportedSection = -1;

	FreezeWatchdog(Guard guard) {
		this(guard, FREEZE_MS);
	}

	FreezeWatchdog(Guard guard, long freezeMs) {
		this.guard = guard;
		this.freezeNanos = TimeUnit.MILLISECONDS.toNanos(freezeMs);
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
