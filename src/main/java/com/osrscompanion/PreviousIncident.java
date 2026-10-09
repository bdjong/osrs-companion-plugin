package com.osrscompanion;

/**
 * The fatal error or freeze an earlier session persisted ({@link IncidentStore}) and may not have
 * reported: read at plugin start, reported once a player is logged in ({@link #report}), and cleared
 * from the store only once that report reached the website, so a client that dies again before
 * sending it still reports it on the start after.
 */
final class PreviousIncident {
	private final IncidentStore store;
	/** As stored at start; null when there was none. */
	private final String raw;
	private IncidentStore.Incident incident;

	PreviousIncident(IncidentStore store) {
		this.store = store;
		String stored = store.peekRaw();
		IncidentStore.Incident decoded = stored == null ? null : IncidentStore.decode(stored);
		if (stored != null && decoded == null) {
			// Unreadable (an older format): drop it.
			store.clearIf(stored);
			stored = null;
		}
		this.raw = stored;
		this.incident = decoded;
	}

	/** True until {@link #report} has handed it to the reporter. */
	synchronized boolean isWaiting() {
		return incident != null;
	}

	/**
	 * Reports it once (later calls do nothing). Cleared from the store when the report was sent, or
	 * at once when nothing will be sent (reports off).
	 */
	void report(ErrorReporter reporter) {
		IncidentStore.Incident i;
		synchronized (this) {
			if (incident == null || reporter == null) {
				return;
			}
			i = incident;
			incident = null;
		}
		boolean queued = reporter.reportPreviousSession(i.level, i.feature, i.message, i.stack, i.timeMs,
			() -> store.clearIf(raw));
		if (!queued) {
			store.clearIf(raw);
		}
	}

	/** Whether the store still holds this incident (not yet sent): a clean stop keeps it for the next start. */
	boolean isStillStored() {
		return raw != null && raw.equals(store.peekRaw());
	}
}
