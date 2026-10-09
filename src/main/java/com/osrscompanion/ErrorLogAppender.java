package com.osrscompanion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Hands this plugin's logged errors to the {@link ErrorReporter}: ERROR, and WARN with an exception,
 * from the {@code com.osrscompanion} loggers; and the exceptions RuneLite logs for our event
 * subscribers and executor tasks (only when the stack goes through our code).
 */
final class ErrorLogAppender extends AppenderBase<ILoggingEvent> {
	static final String OUR_LOGGER = "com.osrscompanion";
	/** RuneLite logs exceptions thrown from @Subscribe handlers here. */
	static final String EVENT_BUS_LOGGER = "net.runelite.client.eventbus.EventBus";
	/** ... and those thrown from tasks on its executor here. */
	static final String EXECUTOR_LOGGER = "net.runelite.client.util.ExecutorServiceExceptionLogger";
	static final String[] LOGGERS = {OUR_LOGGER, EVENT_BUS_LOGGER, EXECUTOR_LOGGER};
	private static final String OUTBOX_LOGGER = "com.osrscompanion.api.SyncOutbox";

	private final ErrorReporter reporter;
	private final ThreadLocal<Boolean> inside = new ThreadLocal<>();

	ErrorLogAppender(ErrorReporter reporter) {
		this.reporter = reporter;
		setName("osrs-companion-error-reports");
	}

	/** Attaches to RuneLite's Logback loggers; false when Logback is not the logging backend. */
	boolean attach() {
		ILoggerFactory factory = LoggerFactory.getILoggerFactory();
		if (!(factory instanceof LoggerContext)) {
			return false;
		}
		LoggerContext ctx = (LoggerContext) factory;
		setContext(ctx);
		start();
		for (String name : LOGGERS) {
			ctx.getLogger(name).addAppender(this);
		}
		return true;
	}

	void detach() {
		ILoggerFactory factory = LoggerFactory.getILoggerFactory();
		if (factory instanceof LoggerContext) {
			for (String name : LOGGERS) {
				Logger logger = ((LoggerContext) factory).getLogger(name);
				logger.detachAppender(this);
			}
		}
		stop();
	}

	@Override
	protected void append(ILoggingEvent event) {
		if (inside.get() != null) {
			return;
		}
		inside.set(Boolean.TRUE);
		try {
			Throwable thrown = throwableOf(event);
			if (!accepts(event.getLoggerName(), event.getLevel(), event.getFormattedMessage(), thrown)) {
				return;
			}
			Map<String, Object> context = new LinkedHashMap<>();
			context.put("logger", event.getLoggerName());
			context.put("thread", event.getThreadName());
			reporter.record(event.getLevel().isGreaterOrEqual(Level.ERROR) ? ErrorReporter.LEVEL_ERROR : ErrorReporter.LEVEL_WARN,
				event.getLoggerName(), event.getFormattedMessage(), thrown, context);
		} catch (RuntimeException e) {
			// Reporting must never break logging.
		} finally {
			inside.remove();
		}
	}

	/** Which logged events become reports. */
	static boolean accepts(String loggerName, Level level, String message, Throwable thrown) {
		if (loggerName == null || level == null) {
			return false;
		}
		boolean error = level.isGreaterOrEqual(Level.ERROR);
		boolean warnWithThrowable = level.isGreaterOrEqual(Level.WARN) && thrown != null;
		if (!error && !warnWithThrowable) {
			return false;
		}
		if (loggerName.startsWith(OUR_LOGGER + ".") || loggerName.equals(OUR_LOGGER)) {
			if (loggerName.equals(ErrorReporter.class.getName()) || loggerName.equals(ErrorLogAppender.class.getName())) {
				return false;
			}
			// The Guard and the watchdog report what they log straight to the ErrorReporter (with the
			// feature and the right level): the log line must not be counted a second time.
			if (loggerName.equals(Guard.class.getName()) || loggerName.equals(FreezeWatchdog.class.getName())) {
				return false;
			}
			// The outbox's own send failures are network trouble (reported as dropped uploads instead) and
			// may be the error reports themselves: only the callbacks it runs (other features' code) count.
			if (loggerName.equals(OUTBOX_LOGGER)) {
				return message != null && (message.startsWith("Upload callback failed") || message.startsWith("Web version listener failed"));
			}
			return true;
		}
		if (loggerName.equals(EVENT_BUS_LOGGER) || loggerName.equals(EXECUTOR_LOGGER)) {
			return thrown != null && ErrorReporter.touchesOurCode(thrown);
		}
		return false;
	}

	private static Throwable throwableOf(ILoggingEvent event) {
		IThrowableProxy proxy = event.getThrowableProxy();
		return proxy instanceof ThrowableProxy ? ((ThrowableProxy) proxy).getThrowable() : null;
	}
}
