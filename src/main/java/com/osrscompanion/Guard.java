package com.osrscompanion;

import java.awt.Dimension;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the plugin's code so a bug in one feature can never break or freeze the RuneLite client.
 *
 * <ul>
 *   <li>{@link #run}, {@link #render} and {@link #call} catch every Throwable except {@link ThreadDeath}
 *       (rethrown). The first failure of a feature is logged with its stack, later ones at debug, and
 *       every failure goes to the {@link Listener} (which reports it).</li>
 *   <li>Each feature has a {@link CircuitBreaker}: {@value #FAILURE_LIMIT} failures within
 *       {@value #FAILURE_WINDOW_MS} ms, a fatal error ({@link VirtualMachineError}, such as
 *       OutOfMemoryError or StackOverflowError) or a freeze turn the feature off for the rest of the
 *       session: its sections are skipped (renders draw nothing). The listener hears it once.</li>
 *   <li>On the watched thread (the client thread), the outermost running section's feature and
 *       start time are published for the {@link FreezeWatchdog}; in debug logging, any section over
 *       {@value #SLOW_MS} ms is logged as slow.</li>
 * </ul>
 *
 * The static methods use one shared instance; tests make their own (fake clocks, no client).
 */
@Slf4j
public final class Guard {
	static final int FAILURE_LIMIT = 5;
	static final long FAILURE_WINDOW_MS = 60_000;
	static final long SLOW_MS = 50;
	/** More distinct features than this share one breaker, so the map stays bounded. */
	static final int MAX_FEATURES = 256;
	static final String OTHER_FEATURE = "other";

	/** What the plugin does with failures: report them, persist fatal ones, tell the player. Called on any thread. */
	public interface Listener {
		/** Every caught failure (the reporter counts repeats). */
		void onFailure(String feature, Throwable error);

		/** An error the JVM may not survive (OutOfMemoryError...): worth persisting before anything else. */
		void onFatal(String feature, Throwable error);

		/** A section ran longer than the freeze limit on the watched thread. */
		void onFreeze(String feature, long elapsedMs, StackTraceElement[] stack);

		/** The feature is now off for the rest of the session (once per feature). */
		void onDisabled(String feature);
	}

	private static final Listener NO_LISTENER = new Listener() {
		@Override
		public void onFailure(String feature, Throwable error) {
		}

		@Override
		public void onFatal(String feature, Throwable error) {
		}

		@Override
		public void onFreeze(String feature, long elapsedMs, StackTraceElement[] stack) {
		}

		@Override
		public void onDisabled(String feature) {
		}
	};

	private static volatile Guard shared = new Guard(System::currentTimeMillis, System::nanoTime);

	private final LongSupplier clockMs;
	private final LongSupplier nanoTime;
	private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
	/** Features whose first failure was logged with its stack. */
	private final Map<String, Boolean> logged = new ConcurrentHashMap<>();
	private volatile Listener listener = NO_LISTENER;

	// The watched thread's outermost section, published as a seqlock: odd seq = running.
	private volatile Thread watched;
	/** Only the watched thread touches this. */
	private int depth;
	private volatile long seq;
	private volatile String sectionFeature;
	private volatile long sectionStartNanos;

	Guard(LongSupplier clockMs, LongSupplier nanoTime) {
		this.clockMs = clockMs;
		this.nanoTime = nanoTime;
	}

	/** The instance the static methods use. */
	static Guard shared() {
		return shared;
	}

	/** Swaps the shared instance (tests). */
	static void setShared(Guard guard) {
		shared = guard;
	}

	// ------------------------------------------------------------------ static entry points

	public static void run(String feature, Runnable body) {
		shared.runIn(feature, body);
	}

	/** An overlay's render: null (nothing drawn) when it failed or the feature is off. */
	public static Dimension render(String feature, Supplier<Dimension> body) {
		return shared.callIn(feature, body, null);
	}

	public static <T> T call(String feature, Supplier<T> body, T fallback) {
		return shared.callIn(feature, body, fallback);
	}

	/** The task, guarded: for executors and {@code clientThread.invokeLater}. */
	public static Runnable wrap(String feature, Runnable body) {
		return () -> shared.runIn(feature, body);
	}

	public static boolean isEnabled(String feature) {
		return !shared.isDisabled(feature);
	}

	// ------------------------------------------------------------------ instance

	void setListener(Listener listener) {
		this.listener = listener == null ? NO_LISTENER : listener;
	}

	/** The thread whose sections the watchdog times (the client thread); null for none. */
	void watchThread(Thread thread) {
		this.watched = thread;
	}

	Thread watchedThread() {
		return watched;
	}

	/** Turns every feature back on (plugin start: a fresh session). */
	void resetBreakers() {
		breakers.clear();
		logged.clear();
	}

	boolean isDisabled(String feature) {
		CircuitBreaker b = breakers.get(key(feature));
		return b != null && b.isOpen();
	}

	void runIn(String feature, Runnable body) {
		callIn(feature, () -> {
			body.run();
			return null;
		}, null);
	}

	<T> T callIn(String feature, Supplier<T> body, T fallback) {
		CircuitBreaker breaker = breaker(feature);
		if (breaker.isOpen()) {
			return fallback;
		}
		boolean tracked = Thread.currentThread() == watched && depth++ == 0;
		long start = nanoTime.getAsLong();
		if (tracked) {
			sectionFeature = feature;
			sectionStartNanos = start;
			seq++;
		}
		try {
			return body.get();
		} catch (ThreadDeath death) {
			throw death;
		} catch (Throwable error) {
			failed(feature, breaker, error);
			return fallback;
		} finally {
			if (Thread.currentThread() == watched && depth > 0 && --depth == 0 && (seq & 1) == 1) {
				seq++;
			}
			if (log.isDebugEnabled()) {
				long ms = (nanoTime.getAsLong() - start) / 1_000_000;
				if (ms > SLOW_MS) {
					log.debug("Slow: {} took {} ms on {}", feature, ms, Thread.currentThread().getName());
				}
			}
		}
	}

	private void failed(String feature, CircuitBreaker breaker, Throwable error) {
		boolean fatal = error instanceof VirtualMachineError;
		try {
			if (logged.putIfAbsent(key(feature), Boolean.TRUE) == null || fatal) {
				log.warn("OSRS Companion: {} failed", feature, error);
			} else {
				log.debug("OSRS Companion: {} failed again: {}", feature, error.toString());
			}
		} catch (Throwable ignored) {
			// Logging itself failed (out of memory?): the breaker below still works.
		}
		boolean opened = fatal ? breaker.trip() : breaker.recordFailure(clockMs.getAsLong());
		notify(() -> {
			if (fatal) {
				listener.onFatal(feature, error);
			}
			listener.onFailure(feature, error);
		});
		if (opened) {
			disabled(feature, fatal ? "a fatal error" : FAILURE_LIMIT + " errors within a minute");
		}
	}

	/**
	 * A failure caught outside a guarded section (a plugin start or stop step): logged and reported
	 * like the others, without counting towards a breaker.
	 */
	void reportOnly(String feature, Throwable error) {
		try {
			log.warn("OSRS Companion: {} failed", feature, error);
		} catch (Throwable ignored) {
			// see failed()
		}
		notify(() -> listener.onFailure(feature, error));
	}

	/** The watchdog saw a freeze in {@code feature}: off from now on, and reported once. */
	void frozen(String feature, long elapsedMs, StackTraceElement[] stack) {
		notify(() -> listener.onFreeze(feature, elapsedMs, stack));
		if (breaker(feature).trip()) {
			disabled(feature, "a freeze of " + elapsedMs + " ms");
		}
	}

	private void disabled(String feature, String why) {
		try {
			log.warn("OSRS Companion: {} is turned off for this session after {}", feature, why);
		} catch (Throwable ignored) {
			// see failed()
		}
		notify(() -> listener.onDisabled(feature));
	}

	/** The listener's own failures never escape, and are not reported (no loops). */
	private static void notify(Runnable call) {
		try {
			call.run();
		} catch (ThreadDeath death) {
			throw death;
		} catch (Throwable e) {
			try {
				log.debug("Guard listener failed", e);
			} catch (Throwable ignored) {
				// nothing left to do
			}
		}
	}

	private CircuitBreaker breaker(String feature) {
		String key = key(feature);
		CircuitBreaker b = breakers.get(key);
		if (b != null) {
			return b;
		}
		if (breakers.size() >= MAX_FEATURES) {
			key = OTHER_FEATURE;
		}
		return breakers.computeIfAbsent(key, k -> new CircuitBreaker(FAILURE_LIMIT, FAILURE_WINDOW_MS));
	}

	private static String key(String feature) {
		return feature == null ? OTHER_FEATURE : feature;
	}

	/** The watched thread's running outermost section, or null; consistent (never a mix of two). */
	Section currentSection() {
		for (int attempt = 0; attempt < 3; attempt++) {
			long s1 = seq;
			if ((s1 & 1) == 0) {
				return null;
			}
			String feature = sectionFeature;
			long start = sectionStartNanos;
			if (seq == s1) {
				return new Section(s1, feature, start);
			}
		}
		return null;
	}

	long nanoTime() {
		return nanoTime.getAsLong();
	}

	/** One run of a section on the watched thread; {@code id} tells two runs of the same feature apart. */
	static final class Section {
		final long id;
		final String feature;
		final long startNanos;

		Section(long id, String feature, long startNanos) {
			this.id = id;
			this.feature = feature;
			this.startNanos = startNanos;
		}
	}

	/** "slayer-helper-overlay" as the player reads it: "Slayer helper overlay". */
	static String displayName(String feature) {
		if (feature == null || feature.isEmpty()) {
			return "a feature";
		}
		String s = feature.replace('-', ' ');
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
