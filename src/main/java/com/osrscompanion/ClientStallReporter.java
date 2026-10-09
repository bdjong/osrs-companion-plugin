package com.osrscompanion;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What a whole-client-thread stall becomes ({@link FreezeWatchdog.StallListener}): persisted at once
 * as a "freeze" incident (the player may kill a frozen client any moment), and reported. When the
 * heartbeat resumes, the report gets the stall's length ({@code stalled_ms}) and the persisted
 * incident is cleared once that report reached the website. A client that never recovers leaves the
 * incident for the next start ({@link PreviousIncident}).
 *
 * No breaker is tripped: "client" is not a feature of ours. Runs on the watchdog thread; the
 * sent callback on OkHttp's.
 */
final class ClientStallReporter implements FreezeWatchdog.StallListener {
	private final Supplier<IncidentStore> store;
	private final Supplier<ErrorReporter> reporter;
	private final LongSupplier clockMs;
	/** The incident saved for the stall that has not resumed yet, as stored. */
	private volatile String savedRaw;
	private volatile String fingerprint;
	/** The resumed stall's incident whose report is on its way (cleared once sent). */
	private volatile String unsentRaw;

	ClientStallReporter(Supplier<IncidentStore> store, Supplier<ErrorReporter> reporter, LongSupplier clockMs) {
		this.store = store;
		this.reporter = reporter;
		this.clockMs = clockMs;
	}

	@Override
	public void onStall(FreezeWatchdog.Stall stall) {
		IncidentStore.Incident incident = new IncidentStore.Incident("freeze", stall.feature,
			ErrorReporter.stallMessage(stall.feature, stall.elapsedMs, stall.terminated),
			IncidentStore.shortStack(ErrorReporter.stallHeader(stall.stack, stall.terminated, stall.threadState),
				stall.stack, IncidentStore.STALL_FRAMES),
			clockMs.getAsLong());
		IncidentStore s = store.get();
		if (s != null) {
			s.save(incident);
			savedRaw = IncidentStore.encode(incident);
		}
		ErrorReporter r = reporter.get();
		fingerprint = r == null ? null : r.reportClientStall(stall.feature, stall.elapsedMs, stall.stack, stall.terminated,
			stall.threadState, IncidentStore.STALL_FRAMES);
	}

	@Override
	public void onResume(FreezeWatchdog.Stall stall, long stalledMs) {
		String raw = savedRaw;
		String fp = fingerprint;
		savedRaw = null;
		fingerprint = null;
		IncidentStore s = store.get();
		unsentRaw = raw;
		Runnable clear = () -> {
			if (s != null) {
				s.clearIf(raw);
			}
			if (raw != null && raw.equals(unsentRaw)) {
				unsentRaw = null;
			}
		};
		ErrorReporter r = reporter.get();
		boolean queued = r != null && r.reportStallEnded(fp, stall.feature, stalledMs, clear);
		if (!queued) {
			// Nothing will be sent (reports off): the client survived, so nothing is left to report later.
			clear.run();
		}
	}

	/** Whether {@code raw} is a stall incident of this session not known to be sent (a clean stop keeps it). */
	boolean holds(String raw) {
		return raw != null && (raw.equals(savedRaw) || raw.equals(unsentRaw));
	}
}
