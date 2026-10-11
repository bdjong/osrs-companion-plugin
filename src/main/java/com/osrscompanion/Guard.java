package com.osrscompanion;

import java.awt.Dimension;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;

/**
 * Runs the plugin's code so a bug in one feature can never break the RuneLite client.
 *
 * <ul>
 *   <li>{@link #run}, {@link #render} and {@link #call} catch every Throwable except {@link ThreadDeath}
 *       (rethrown). The first failure of a feature is logged with its stack.</li>
 *   <li>Each feature has a {@link CircuitBreaker}: {@value #FAILURE_LIMIT} failures within
 *       {@value #FAILURE_WINDOW_MS} ms or a fatal error ({@link VirtualMachineError}, such as
 *       OutOfMemoryError or StackOverflowError) turn the feature off for the rest of the session: its
 *       sections are skipped (renders draw nothing), and the player is told once.</li>
 *   <li>The features in {@link #NEVER_DISABLED} (uploads, the website version watcher) are exempt: their
 *       failures are still caught and logged, but they are never turned off, so one bad upload cannot
 *       stop every upload for the session.</li>
 *   <li>Under tests (system property {@value #RETHROW_TEST_FAILURES_PROPERTY}, set by build.gradle's
 *       {@code test} task), {@link AssertionError} is rethrown, so a failing assertion inside guarded
 *       code fails the test instead of being swallowed. Never in production.</li>
 * </ul>
 *
 * The static methods use one shared instance; tests make their own (fake clock).
 */
@Slf4j
public final class Guard {
	static final int FAILURE_LIMIT = 5;
	static final long FAILURE_WINDOW_MS = 60_000;
	/** More distinct features than this share one breaker, so the map stays bounded. */
	static final int MAX_FEATURES = 256;
	static final String OTHER_FEATURE = "other";
	/** The outbox and upload jobs ({@code GuardedExecutor.wrap(SYNC_FEATURE, ...)}). */
	public static final String SYNC_FEATURE = "sync";
	/** Features that are never turned off: every upload goes through them. */
	static final Set<String> NEVER_DISABLED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		SYNC_FEATURE, WebVersionWatcher.GUARD_FEATURE)));
	/** {@code -D} switch the test task sets: rethrow {@link AssertionError} (see the class comment). */
	static final String RETHROW_TEST_FAILURES_PROPERTY = "osrscompanion.guard.rethrowTestFailures";

	private static volatile boolean rethrowTestFailures = Boolean.getBoolean(RETHROW_TEST_FAILURES_PROPERTY);
	/** Tests that must see every swallowed failure (the UI goldens) turn this on. */
	static volatile boolean failTests;

	private static volatile Guard shared = new Guard(System::currentTimeMillis);

	private final LongSupplier clockMs;
	private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
	/** Features whose first failure was logged with its stack. */
	private final Map<String, Boolean> logged = new ConcurrentHashMap<>();
	/** Hears once per feature that it is now off for the rest of the session (any thread). */
	private volatile Consumer<String> onDisabled = feature -> {
	};

	Guard(LongSupplier clockMs) {
		this.clockMs = clockMs;
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

	/**
	 * A {@code clientThread.invokeLater} poll, guarded. It returns false to run again next tick; when
	 * the feature is off or the body throws it returns true (done), so a broken poll never retries
	 * forever.
	 */
	public static BooleanSupplier wrapBool(String feature, BooleanSupplier body) {
		return () -> shared.callIn(feature, body::getAsBoolean, Boolean.TRUE);
	}

	/**
	 * A clean-up (plugin stop, unregistering what a feature registered), guarded: caught like the others, but it runs even when the feature is off, so nothing is left behind.
	 */
	public static Runnable wrapCleanup(String feature, Runnable body) {
		return () -> shared.callIn(feature, () -> {
			body.run();
			return null;
		}, null, true);
	}

	// The client thread's queue, each task run in its feature's section. The overloads mirror
	// ClientThread's own, so a lambda or method reference picks the same one it picked there.

	public static void invoke(ClientThread clientThread, String feature, Runnable body) {
		clientThread.invoke(wrap(feature, body));
	}

	public static void invoke(ClientThread clientThread, String feature, BooleanSupplier body) {
		clientThread.invoke(wrapBool(feature, body));
	}

	public static void invokeLater(ClientThread clientThread, String feature, Runnable body) {
		clientThread.invokeLater(wrap(feature, body));
	}

	public static void invokeLater(ClientThread clientThread, String feature, BooleanSupplier body) {
		clientThread.invokeLater(wrapBool(feature, body));
	}

	public static boolean isEnabled(String feature) {
		return !shared.isDisabled(feature);
	}

	// ------------------------------------------------------------------ instance

	void setOnDisabled(Consumer<String> listener) {
		this.onDisabled = listener == null ? feature -> {
		} : listener;
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
		return callIn(feature, body, fallback, false);
	}

	/** @param evenIfOff run the body even when the feature is off (clean-ups) */
	<T> T callIn(String feature, Supplier<T> body, T fallback, boolean evenIfOff) {
		CircuitBreaker breaker = breaker(feature);
		if (breaker.isOpen() && !evenIfOff) {
			return fallback;
		}
		try {
			return body.get();
		} catch (ThreadDeath death) {
			throw death;
		} catch (Throwable error) {
			if (isTestFailure(error)) {
				throw (AssertionError) error;
			}
			if (failTests) {
				throw new AssertionError(feature + " failed", error);
			}
			failed(feature, breaker, error);
			return fallback;
		}
	}

	private void failed(String feature, CircuitBreaker breaker, Throwable error) {
		boolean fatal = error instanceof VirtualMachineError;
		try {
			if (logged.putIfAbsent(key(feature), Boolean.TRUE) == null || fatal) {
				log.warn("OSRS Companion: {} failed", feature, error);
			} else {
			}
		} catch (Throwable ignored) {
			// Logging itself failed (out of memory?): the breaker below still works.
		}
		boolean opened = fatal ? breaker.trip() : breaker.recordFailure(clockMs.getAsLong());
		if (opened) {
			disabled(feature, fatal ? "a fatal error" : FAILURE_LIMIT + " errors within a minute");
		}
	}


	private void disabled(String feature, String why) {
		try {
			log.warn("OSRS Companion: {} is turned off for this session after {}", feature, why);
		} catch (Throwable ignored) {
			// see failed()
		}
		notify(() -> onDisabled.accept(feature));
	}

	/** The listener's own failures never escape. */
	private static void notify(Runnable call) {
		try {
			call.run();
		} catch (ThreadDeath death) {
			throw death;
		} catch (Throwable e) {
			if (isTestFailure(e)) {
				throw (AssertionError) e;
			}
			try {
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
		boolean exempt = isExempt(key);
		if (breakers.size() >= MAX_FEATURES && !exempt) {
			key = OTHER_FEATURE;
		}
		return breakers.computeIfAbsent(key, k -> new CircuitBreaker(FAILURE_LIMIT, FAILURE_WINDOW_MS, exempt));
	}

	/** True for the features that are never turned off ({@link #NEVER_DISABLED}). */
	static boolean isExempt(String feature) {
		return feature != null && NEVER_DISABLED.contains(feature);
	}

	/** A test's failed assertion, rethrown only while running under tests. */
	private static boolean isTestFailure(Throwable error) {
		return rethrowTestFailures && error instanceof AssertionError;
	}

	/** Tests switch the rethrow off to check the production behaviour (swallow and report). */
	static void setRethrowTestFailures(boolean rethrow) {
		rethrowTestFailures = rethrow;
	}

	static boolean rethrowsTestFailures() {
		return rethrowTestFailures;
	}

	private static String key(String feature) {
		return feature == null ? OTHER_FEATURE : feature;
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
