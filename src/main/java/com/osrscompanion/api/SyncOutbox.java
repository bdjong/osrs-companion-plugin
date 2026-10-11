package com.osrscompanion.api;

import lombok.Getter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Holds the fire-and-forget uploads to {@code /api/runelite/*} and sends them together through
 * {@code POST /api/runelite/batch}, so a burst of changes costs one request instead of one each.
 *
 * <ul>
 *   <li>A flush runs at most every {@link #FLUSH_INTERVAL_MS} while something is queued, and right
 *       away for a priority request (a drop, a finished task, a goal) or {@link #flushNow()} (logout,
 *       plugin stop). Nothing queued means no request at all.</li>
 *   <li>A full-state upload (its {@code coalesceKey} set) queued again before it went out replaces
 *       the older copy; both callers' success callbacks still run. Events (no key) are never merged
 *       or dropped.</li>
 *   <li>Order is kept: one batch at a time, oldest first, at most {@link #MAX_BATCH_REQUESTS}
 *       requests (and about {@link #MAX_BATCH_CHARS} characters) each.</li>
 *   <li>A network failure (or a 5xx/429 for the whole batch) keeps the requests and tries again
 *       after 10 s, 20 s, 40 s... capped at {@link #BACKOFF_MAX_MS}; a request still unsent after
 *       {@link #MAX_AGE_MS} is dropped, so memory stays bounded.</li>
 *   <li>A server without the batch endpoint (404) gets each request on its own, in order, as before,
 *       and the batch endpoint is tried again {@link #BATCH_RETRY_AFTER_404_MS} later.</li>
 * </ul>
 *
 * The network side is a {@link Sender} and time is a clock, so the logic is unit-testable.
 */
@Slf4j
public final class SyncOutbox {
	static final long FLUSH_INTERVAL_MS = 10_000;
	static final long BACKOFF_BASE_MS = 10_000;
	static final long BACKOFF_MAX_MS = 120_000;
	static final long MAX_AGE_MS = 30 * 60_000L;
	static final int MAX_BATCH_REQUESTS = 50;
	static final int MAX_BATCH_CHARS = 2_000_000;
	static final long BATCH_RETRY_AFTER_404_MS = 10 * 60_000L;

	/** One queued upload. */
	public static final class Item {
		@Getter
		final String baseUrl;
		@Getter
		final String appKey;
		@Getter
		final String path;
		@Getter
		final String method;
		@Getter
		final String body;
		@Getter
		final String label;
		/** Non-null for a full-state upload: a newer one with the same key replaces it while queued. */
		final String coalesceKey;
		@Getter
		final boolean large;
		boolean priority;
		final List<Runnable> onSuccess = new ArrayList<>();
		long enqueuedAtMs;
		long seq;
		boolean inFlight;

		public Item(String baseUrl, String appKey, String path, String method, String body, String label,
			String coalesceKey, boolean priority, boolean large, Runnable onSuccess) {
			this.baseUrl = baseUrl;
			this.appKey = appKey;
			this.path = path;
			this.method = method;
			this.body = body;
			this.label = label;
			this.coalesceKey = coalesceKey;
			this.priority = priority;
			this.large = large;
			if (onSuccess != null) {
				this.onSuccess.add(onSuccess);
			}
		}

	}

	/** How a batch went: the HTTP status of the batch, and per request (in order) its status. */
	public interface BatchCallback {
		/** {@code statuses} and {@code webVersion} may be null when the response had none. */
		void onResponse(int httpStatus, List<Integer> statuses, Integer webVersion);

		void onNetworkFailure(String message);
	}

	public interface SingleCallback {
		void onResponse(int httpStatus);

		void onNetworkFailure(String message);
	}

	/** The network: sends asynchronously and answers exactly once through the callback. */
	public interface Sender {
		void sendBatch(String baseUrl, String appKey, List<Item> items, BatchCallback callback);

		void sendOne(Item item, SingleCallback callback);
	}

	private final Sender sender;
	private final ScheduledExecutorService executor;
	private final LongSupplier clockMs;

	private final List<Item> queue = new ArrayList<>();
	private long nextSeq;
	private boolean inFlight;
	/** Items queued before the current flush started (a split batch's rest goes on at once). */
	private long flushUpToSeq = -1;
	private boolean flushAfterInFlight;
	private ScheduledFuture<?> scheduled;
	private long scheduledDueMs;
	private int failures;
	private long backoffUntilMs;
	private long batchUnsupportedUntilMs;
	private boolean stopped;
	/** When a batch response last arrived (clock ms), or {@link Long#MIN_VALUE} if never. */
	@Getter
	private volatile long lastBatchResponseMs = Long.MIN_VALUE;
	private volatile IntConsumer webVersionListener;

	/**
	 * Adds one request to a batch about to be sent (the live activity status rides along with every
	 * batch this way). It is sent once with that batch and never queued or retried on its own.
	 */
	public interface BatchExtra {
		/** The request to add to this chunk (same server and key), or null for none. */
		Item extraFor(List<Item> chunk);
	}

	private volatile BatchExtra batchExtra;

	public SyncOutbox(Sender sender, ScheduledExecutorService executor, LongSupplier clockMs) {
		this.sender = sender;
		this.executor = executor;
		this.clockMs = clockMs;
	}

	public void setWebVersionListener(IntConsumer listener) {
		this.webVersionListener = listener;
	}

	public void setBatchExtra(BatchExtra extra) {
		this.batchExtra = extra;
	}

	public synchronized int size() {
		return queue.size();
	}

	/** True while an upload of this label is queued or on its way (the dedupe check must not skip then). */
	public synchronized boolean hasPending(String label) {
		for (Item i : queue) {
			if (i.label.equals(label)) {
				return true;
			}
		}
		return false;
	}

	/** Queues an upload; it goes out with the next flush (at once when it is a priority one). */
	public void enqueue(Item item) {
		boolean runNow;
		synchronized (this) {
			long now = clockMs.getAsLong();
			pruneExpired(now);
			item.enqueuedAtMs = now;
			if (item.coalesceKey != null) {
				for (Iterator<Item> it = queue.iterator(); it.hasNext(); ) {
					Item old = it.next();
					if (!old.inFlight && item.coalesceKey.equals(old.coalesceKey)
						&& Objects.equals(item.baseUrl, old.baseUrl) && Objects.equals(item.appKey, old.appKey)) {
						it.remove();
						// The older callers learn of the upload too, before the newer one.
						List<Runnable> merged = new ArrayList<>(old.onSuccess);
						merged.addAll(item.onSuccess);
						item.onSuccess.clear();
						item.onSuccess.addAll(merged);
						item.priority |= old.priority;
						log.debug("Outbox: {} replaced by a newer copy before it was sent", item.label);
					}
				}
			}
			item.seq = nextSeq++;
			queue.add(item);
			runNow = requestFlushLocked(item.priority || stopped ? 0 : FLUSH_INTERVAL_MS, now);
		}
		if (runNow) {
			flush();
		}
	}

	/** Asks for a flush soon (a priority moment), still waiting out a network backoff. */
	public void requestFlush() {
		boolean runNow;
		synchronized (this) {
			if (queue.isEmpty()) {
				return;
			}
			runNow = requestFlushLocked(0, clockMs.getAsLong());
		}
		if (runNow) {
			flush();
		}
	}

	/** Sends everything queued now, ignoring any backoff (logout, plugin stop). */
	public void flushNow() {
		synchronized (this) {
			backoffUntilMs = 0;
			if (inFlight) {
				flushAfterInFlight = true;
				return;
			}
		}
		flush();
	}

	/** Re-enables retries after {@link #stop()} (plugin started again). */
	public synchronized void start() {
		stopped = false;
	}

	/**
	 * Sends what is queued one last time; afterwards nothing is retried (failed requests are dropped)
	 * and later requests go out at once, so nothing lingers once the plugin is off.
	 */
	public void stop() {
		synchronized (this) {
			stopped = true;
			cancelScheduledLocked();
		}
		flushNow();
	}

	/** True when a flush should run on the caller's thread right now. */
	private boolean requestFlushLocked(long delayMs, long now) {
		if (inFlight) {
			if (delayMs == 0) {
				flushAfterInFlight = true;
			}
			return false;
		}
		long due = Math.max(now + delayMs, backoffUntilMs);
		if (scheduled != null && scheduledDueMs <= due) {
			return false;
		}
		cancelScheduledLocked();
		scheduledDueMs = due;
		scheduled = executor.schedule(this::flush, Math.max(0, due - now), TimeUnit.MILLISECONDS);
		return false;
	}

	private void cancelScheduledLocked() {
		if (scheduled != null) {
			scheduled.cancel(false);
			scheduled = null;
		}
	}

	/** Sends the next batch (runs on the executor; the sending itself is asynchronous). */
	void flush() {
		List<Item> chunk;
		boolean useBatch;
		synchronized (this) {
			scheduled = null;
			if (inFlight) {
				return;
			}
			long now = clockMs.getAsLong();
			pruneExpired(now);
			if (queue.isEmpty()) {
				return;
			}
			if (now < backoffUntilMs) {
				requestFlushLocked(0, now);
				return;
			}
			flushUpToSeq = nextSeq - 1;
			chunk = takeChunkLocked();
			for (Item i : chunk) {
				i.inFlight = true;
			}
			inFlight = true;
			flushAfterInFlight = false;
			useBatch = now >= batchUnsupportedUntilMs;
		}
		if (useBatch) {
			addExtra(chunk);
			sendBatch(chunk);
		} else {
			sendIndividually(chunk, 0, new Integer[chunk.size()]);
		}
	}

	/** The {@link BatchExtra}'s request, appended (in flight with the chunk, never queued). */
	private void addExtra(List<Item> chunk) {
		BatchExtra extra = batchExtra;
		if (extra == null || chunk.size() >= MAX_BATCH_REQUESTS) {
			return;
		}
		try {
			Item item = extra.extraFor(Collections.unmodifiableList(chunk));
			Item first = chunk.get(0);
			if (item != null && Objects.equals(item.baseUrl, first.baseUrl) && Objects.equals(item.appKey, first.appKey)) {
				item.inFlight = true;
				item.enqueuedAtMs = clockMs.getAsLong();
				chunk.add(item);
			}
		} catch (RuntimeException e) {
			log.debug("Outbox: the batch extra failed", e);
		}
	}

	/** The oldest requests that share one server and key, at most 50 and about 2 MB. */
	private List<Item> takeChunkLocked() {
		List<Item> chunk = new ArrayList<>();
		Item first = queue.get(0);
		long chars = 0;
		for (Item i : queue) {
			if (chunk.size() >= MAX_BATCH_REQUESTS
				|| !Objects.equals(i.baseUrl, first.baseUrl) || !Objects.equals(i.appKey, first.appKey)) {
				break;
			}
			long size = i.body.length() + i.path.length() + 64;
			if (!chunk.isEmpty() && chars + size > MAX_BATCH_CHARS) {
				break;
			}
			chunk.add(i);
			chars += size;
		}
		return chunk;
	}

	private void sendBatch(List<Item> chunk) {
		Item first = chunk.get(0);
		try {
			sender.sendBatch(first.baseUrl, first.appKey, Collections.unmodifiableList(chunk), new BatchCallback() {
				@Override
				public void onResponse(int httpStatus, List<Integer> statuses, Integer webVersion) {
					onBatchResponse(chunk, httpStatus, statuses, webVersion);
				}

				@Override
				public void onNetworkFailure(String message) {
					log.warn("Failed to send {} queued uploads to the OSRS Companion API: {}", chunk.size(), message);
					complete(chunk, new Integer[chunk.size()], true);
				}
			});
		} catch (RuntimeException e) {
			log.warn("Failed to send queued uploads to the OSRS Companion API", e);
			complete(chunk, new Integer[chunk.size()], true);
		}
	}

	private void onBatchResponse(List<Item> chunk, int httpStatus, List<Integer> statuses, Integer webVersion) {
		Integer[] results = new Integer[chunk.size()];
		if (httpStatus == 404) {
			synchronized (this) {
				batchUnsupportedUntilMs = clockMs.getAsLong() + BATCH_RETRY_AFTER_404_MS;
			}
			log.debug("Outbox: no batch endpoint on this server, sending {} requests one by one", chunk.size());
			sendIndividually(chunk, 0, results);
			return;
		}
		if (httpStatus == 429 || httpStatus >= 500) {
			log.warn("OSRS Companion API returned {} for a batch of {} uploads; will retry", httpStatus, chunk.size());
			complete(chunk, results, true);
			return;
		}
		if (httpStatus < 200 || httpStatus >= 300) {
			log.warn("OSRS Companion API returned {} for a batch of {} uploads; dropped", httpStatus, chunk.size());
			Arrays.fill(results, httpStatus);
			complete(chunk, results, false);
			return;
		}
		lastBatchResponseMs = clockMs.getAsLong();
		for (int i = 0; i < results.length; i++) {
			results[i] = statuses != null && i < statuses.size() && statuses.get(i) != null ? statuses.get(i) : -1;
		}
		complete(chunk, results, false);
		IntConsumer listener = webVersionListener;
		if (webVersion != null && listener != null) {
			try {
				listener.accept(webVersion);
			} catch (RuntimeException e) {
				log.warn("Web version listener failed", e);
			}
		}
	}

	/** The old way (a server without the batch endpoint): one request at a time, in order. */
	private void sendIndividually(List<Item> chunk, int index, Integer[] results) {
		if (index >= chunk.size()) {
			complete(chunk, results, false);
			return;
		}
		try {
			sender.sendOne(chunk.get(index), new SingleCallback() {
				@Override
				public void onResponse(int httpStatus) {
					results[index] = httpStatus;
					sendIndividually(chunk, index + 1, results);
				}

				@Override
				public void onNetworkFailure(String message) {
					log.warn("Failed to send {} sync to OSRS Companion API: {}", chunk.get(index).label, message);
					complete(chunk, results, true);
				}
			});
		} catch (RuntimeException e) {
			log.warn("Failed to send {} sync to OSRS Companion API", chunk.get(index).label, e);
			complete(chunk, results, true);
		}
	}

	/**
	 * Settles a chunk: requests with a status are done (success callbacks for 2xx, dropped otherwise);
	 * those without one stay queued for a retry when {@code retry}.
	 */
	private void complete(List<Item> chunk, Integer[] results, boolean retry) {
		List<Runnable> callbacks = new ArrayList<>();
		boolean runNow;
		synchronized (this) {
			long now = clockMs.getAsLong();
			inFlight = false;
			for (int i = 0; i < chunk.size(); i++) {
				Item item = chunk.get(i);
				item.inFlight = false;
				Integer status = results[i];
				if (status == null) {
					if (stopped) {
						queue.remove(item);
						log.debug("Outbox: {} dropped (plugin stopped)", item.label);
					}
					continue;
				}
				queue.remove(item);
				if (status >= 200 && status < 300) {
					callbacks.addAll(item.onSuccess);
				} else {
					log.warn("OSRS Companion API returned {} for {} sync", status, item.label);
				}
			}
			if (retry && !stopped) {
				failures++;
				long backoff = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS << Math.min(failures - 1, 10));
				backoffUntilMs = now + backoff;
				mergeRetriedLocked(chunk);
			} else if (!retry) {
				failures = 0;
				backoffUntilMs = 0;
			}
			pruneExpired(now);
			runNow = false;
			if (!queue.isEmpty()) {
				boolean continuing = !retry && queue.get(0).seq <= flushUpToSeq;
				boolean urgent = flushAfterInFlight || stopped || anyPriorityLocked();
				flushAfterInFlight = false;
				if (continuing && now >= backoffUntilMs) {
					runNow = true;
				} else {
					requestFlushLocked(continuing || urgent ? 0 : FLUSH_INTERVAL_MS, now);
				}
			}
		}
		for (Runnable r : callbacks) {
			try {
				r.run();
			} catch (RuntimeException e) {
				log.warn("Upload callback failed", e);
			}
		}
		if (runNow) {
			flush();
		}
	}

	/** A failed full-state upload that a newer copy queued meanwhile replaces is not sent twice. */
	private void mergeRetriedLocked(List<Item> chunk) {
		for (Item failed : chunk) {
			if (failed.coalesceKey == null || !queue.contains(failed)) {
				continue;
			}
			for (Item later : queue) {
				if (later != failed && later.seq > failed.seq && failed.coalesceKey.equals(later.coalesceKey)
					&& Objects.equals(failed.baseUrl, later.baseUrl) && Objects.equals(failed.appKey, later.appKey)) {
					List<Runnable> merged = new ArrayList<>(failed.onSuccess);
					merged.addAll(later.onSuccess);
					later.onSuccess.clear();
					later.onSuccess.addAll(merged);
					later.priority |= failed.priority;
					queue.remove(failed);
					break;
				}
			}
		}
	}

	private boolean anyPriorityLocked() {
		for (Item i : queue) {
			if (i.priority) {
				return true;
			}
		}
		return false;
	}

	private void pruneExpired(long now) {
		for (Iterator<Item> it = queue.iterator(); it.hasNext(); ) {
			Item i = it.next();
			if (!i.inFlight && now - i.enqueuedAtMs > MAX_AGE_MS) {
				it.remove();
				log.warn("Dropped {} sync: not delivered within {} minutes", i.label, MAX_AGE_MS / 60_000);
			}
		}
	}
}
