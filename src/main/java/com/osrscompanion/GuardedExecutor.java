package com.osrscompanion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RuneLite's shared executor, with every task of one feature run through {@link Guard}: a task that
 * throws is reported (and counts towards the feature's breaker) instead of silently cancelling a
 * periodic job, and a feature that was turned off stops doing work.
 *
 * The shared executor belongs to RuneLite: {@link #shutdown()} and {@link #shutdownNow()} only cancel
 * this feature's periodic jobs; {@link #cancelAllPeriodic()} (plugin stop) cancels every feature's.
 */
final class GuardedExecutor implements ScheduledExecutorService {
	/** Every periodic job any feature scheduled and has not cancelled, for the plugin's stop. */
	private static final Set<Future<?>> PERIODIC = ConcurrentHashMap.newKeySet();

	private final String feature;
	private final ScheduledExecutorService delegate;
	private final Set<Future<?>> mine = ConcurrentHashMap.newKeySet();

	private GuardedExecutor(String feature, ScheduledExecutorService delegate) {
		this.feature = feature;
		this.delegate = delegate;
	}

	/** The executor with this feature's tasks guarded; null stays null (tests without one). */
	static ScheduledExecutorService wrap(String feature, ScheduledExecutorService executor) {
		if (executor == null || executor instanceof GuardedExecutor && ((GuardedExecutor) executor).feature.equals(feature)) {
			return executor;
		}
		ScheduledExecutorService raw = executor instanceof GuardedExecutor ? ((GuardedExecutor) executor).delegate : executor;
		return new GuardedExecutor(feature, raw);
	}

	/** Cancels every feature's periodic jobs (plugin stop); one-off tasks still run. */
	static void cancelAllPeriodic() {
		for (Future<?> f : PERIODIC) {
			f.cancel(false);
		}
		PERIODIC.clear();
	}

	static int periodicCount() {
		PERIODIC.removeIf(Future::isDone);
		return PERIODIC.size();
	}

	private Runnable guard(Runnable task) {
		return Guard.wrap(feature, task);
	}

	private <T> Callable<T> guard(Callable<T> task) {
		return () -> Guard.call(feature, () -> {
			try {
				return task.call();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, null);
	}

	private <F extends Future<?>> F periodic(F future) {
		PERIODIC.removeIf(Future::isDone);
		mine.removeIf(Future::isDone);
		PERIODIC.add(future);
		mine.add(future);
		return future;
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
		return delegate.schedule(guard(command), delay, unit);
	}

	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
		return delegate.schedule(guard(callable), delay, unit);
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
		return periodic(delegate.scheduleAtFixedRate(guard(command), initialDelay, period, unit));
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
		return periodic(delegate.scheduleWithFixedDelay(guard(command), initialDelay, delay, unit));
	}

	@Override
	public void execute(Runnable command) {
		delegate.execute(guard(command));
	}

	@Override
	public Future<?> submit(Runnable task) {
		return delegate.submit(guard(task));
	}

	@Override
	public <T> Future<T> submit(Runnable task, T result) {
		return delegate.submit(guard(task), result);
	}

	@Override
	public <T> Future<T> submit(Callable<T> task) {
		return delegate.submit(guard(task));
	}

	@Override
	public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
		return delegate.invokeAll(guardAll(tasks));
	}

	@Override
	public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
		throws InterruptedException {
		return delegate.invokeAll(guardAll(tasks), timeout, unit);
	}

	@Override
	public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
		return delegate.invokeAny(guardAll(tasks));
	}

	@Override
	public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
		throws InterruptedException, ExecutionException, TimeoutException {
		return delegate.invokeAny(guardAll(tasks), timeout, unit);
	}

	private <T> List<Callable<T>> guardAll(Collection<? extends Callable<T>> tasks) {
		List<Callable<T>> out = new ArrayList<>(tasks.size());
		for (Callable<T> t : tasks) {
			out.add(guard(t));
		}
		return out;
	}

	/** Not RuneLite's executor: only this feature's periodic jobs stop. */
	@Override
	public void shutdown() {
		for (Future<?> f : mine) {
			f.cancel(false);
			PERIODIC.remove(f);
		}
		mine.clear();
	}

	@Override
	public List<Runnable> shutdownNow() {
		shutdown();
		return new ArrayList<>();
	}

	@Override
	public boolean isShutdown() {
		return delegate.isShutdown();
	}

	@Override
	public boolean isTerminated() {
		return delegate.isTerminated();
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		return delegate.awaitTermination(timeout, unit);
	}
}
