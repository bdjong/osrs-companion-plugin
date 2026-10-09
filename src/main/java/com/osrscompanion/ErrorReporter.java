package com.osrscompanion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Collects this plugin's errors and sends them to {@code POST /api/runelite/errors}, so the admin
 * can see which player hit which error. Only runs while "Send error reports" is on (and an App Key
 * is set, outside special game modes): {@link #isEnabled()}.
 *
 * <ul>
 *   <li>An error is identified by its {@link #fingerprint}: the exception class, its message with the
 *       numbers taken out and its first {@value #FINGERPRINT_FRAMES} frames in this plugin's code.</li>
 *   <li>The same error again is counted, not stored again; {@code count} is how often it happened
 *       since it was last sent.</li>
 *   <li>A flush runs at most every {@link #FLUSH_INTERVAL_MS}; at most {@value #MAX_DISTINCT_PER_SESSION}
 *       distinct errors per session (plugin start) and {@value #MAX_PER_CALL} per call.</li>
 *   <li>The stack keeps only this plugin's frames plus the first other one; every field is cut to
 *       the website's limits.</li>
 * </ul>
 *
 * Pure logic: the sender, the clock and the gates are passed in, so it is unit-testable. This class
 * never logs above debug, so it cannot report on itself.
 */
public final class ErrorReporter {
	static final long FLUSH_INTERVAL_MS = 60_000;
	static final int MAX_DISTINCT_PER_SESSION = 30;
	static final int MAX_PER_CALL = 20;
	static final int FINGERPRINT_FRAMES = 3;
	static final int MAX_FINGERPRINT = 64;
	static final int MAX_MESSAGE = 1000;
	static final int MAX_STACK = 8000;
	static final int MAX_CONTEXT_CHARS = 4096;
	static final int MAX_FEATURE = 64;
	static final int MAX_CAUSES = 5;
	static final String OUR_PACKAGE = "com.osrscompanion.";

	public static final String LEVEL_ERROR = "error";
	public static final String LEVEL_WARN = "warn";
	/** The {@link FreezeWatchdog} saw our code block the client thread. */
	public static final String LEVEL_FREEZE = "freeze";
	/** A fatal error or freeze from an earlier session that may have ended the client ({@link IncidentStore}). */
	public static final String LEVEL_FATAL = "fatal";

	private static final Pattern NUMBERS = Pattern.compile("\\d+");

	/** Hands one call's reports (already in the contract's shape) to the network. */
	public interface Sender {
		void send(String playerUsername, List<Map<String, Object>> reports);
	}

	private static final class Pending {
		final String player;
		final String fingerprint;
		final String level;
		final String message;
		final String stack;
		final String feature;
		final Map<String, Object> context;
		long firstMs;
		int count;

		Pending(String player, String fingerprint, String level, String message, String stack, String feature,
			Map<String, Object> context, long firstMs) {
			this.player = player;
			this.fingerprint = fingerprint;
			this.level = level;
			this.message = message;
			this.stack = stack;
			this.feature = feature;
			this.context = context;
			this.firstMs = firstMs;
		}
	}

	private final Sender sender;
	private final LongSupplier clockMs;
	private final BooleanSupplier enabled;
	private final Supplier<String> player;
	private final String pluginVersion;
	private final String runeliteVersion;

	/** Every distinct error seen this session (the cap counts these, sent or not). */
	private final Set<String> seenFingerprints = new LinkedHashSet<>();
	/** Not yet sent, by player and fingerprint, oldest first. */
	private final Map<String, Pending> pending = new LinkedHashMap<>();
	private long lastFlushMs = Long.MIN_VALUE;
	private String lastPlayer;

	/**
	 * @param enabled  the setting, App Key and game-mode gate; checked when an error happens and again
	 *                 when sending
	 * @param player   the logged-in character's name, or null
	 */
	public ErrorReporter(Sender sender, LongSupplier clockMs, BooleanSupplier enabled, Supplier<String> player,
		String pluginVersion, String runeliteVersion) {
		this.sender = sender;
		this.clockMs = clockMs;
		this.enabled = enabled;
		this.player = player;
		this.pluginVersion = pluginVersion;
		this.runeliteVersion = runeliteVersion;
	}

	public boolean isEnabled() {
		try {
			return enabled.getAsBoolean();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Notes an error logged by this plugin.
	 *
	 * @param loggerName the logger (usually the class) that logged it
	 * @param message    the logged message, already formatted
	 * @param thrown     the exception, or null
	 */
	public void record(String level, String loggerName, String message, Throwable thrown, Map<String, Object> context) {
		if (!isEnabled()) {
			return;
		}
		String fp = thrown != null ? fingerprint(thrown) : hash("log|" + loggerName + "|" + normalize(message));
		String feature = featureFor(thrown != null ? featureClass(thrown, loggerName) : loggerName);
		String text = message == null || message.isEmpty() ? null : message;
		if (thrown != null) {
			String ex = thrown.toString();
			text = text == null ? ex : text.contains(ex) ? text : text + ": " + ex;
		}
		add(fp, level, text, thrown != null ? filterStack(thrown) : null, feature, context);
	}

	/** An upload the outbox dropped (non-2xx other than 401; no status means unknown, not reported); never one of the error reports themselves. */
	public void reportUploadDropped(String label, String path, int status) {
		if (status < 100 || status == 401 || (status >= 200 && status < 300) || isErrorReport(label, path) || !isEnabled()) {
			return;
		}
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("endpoint", path);
		context.put("status", status);
		context.put("sync", label);
		add(hash("sync-drop|" + path + "|" + status), LEVEL_WARN,
			"Upload dropped: POST " + path + " returned " + status + " (" + label + ")", null, "sync", context);
	}

	/** A website response that could not be read as the JSON expected. */
	public void reportParseFailure(String label, String path, String error) {
		if (isErrorReport(label, path) || !isEnabled()) {
			return;
		}
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("endpoint", path);
		context.put("response", label);
		add(hash("parse|" + label), LEVEL_WARN,
			"Could not read the website's " + label + " response: " + error, null, "sync", context);
	}

	/**
	 * An exception the {@link Guard} caught in a feature (reported here, not through the log appender,
	 * so it is counted once).
	 */
	public void reportCaught(String feature, Throwable thrown) {
		if (thrown == null || !isEnabled()) {
			return;
		}
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("guard", feature);
		context.put("thread", Thread.currentThread().getName());
		add(fingerprint(thrown), LEVEL_ERROR, thrown.toString(), filterStack(thrown), feature, context);
	}

	/** Our code blocked the client thread for {@code elapsedMs}; {@code stack} is the client thread's. */
	public void reportFreeze(String feature, long elapsedMs, StackTraceElement[] stack) {
		if (!isEnabled()) {
			return;
		}
		Throwable where = new Throwable("Client thread blocked by " + feature);
		where.setStackTrace(stack == null ? new StackTraceElement[0] : stack);
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("guard", feature);
		context.put("elapsed_ms", elapsedMs);
		StringBuilder fp = new StringBuilder("freeze|").append(feature);
		for (String frame : ourFrames(where, FINGERPRINT_FRAMES)) {
			fp.append('|').append(frame);
		}
		add(hash(fp.toString()), LEVEL_FREEZE, "The client froze for " + elapsedMs + " ms in " + feature,
			filterStack(where), feature, context);
	}

	/** A fatal error or freeze an earlier session persisted, maybe its last act (see {@link IncidentStore}). */
	public void reportPreviousSession(String kind, String feature, String message, String stack, long occurredMs) {
		if (!isEnabled()) {
			return;
		}
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("guard", feature);
		context.put("kind", kind);
		context.put("previous_session", true);
		add(hash("fatal|" + kind + "|" + feature + "|" + normalize(message)), LEVEL_FATAL,
			message == null ? kind + " in " + feature : message, stack, feature, context, occurredMs);
	}

	static boolean isErrorReport(String label, String path) {
		return "errors".equals(label) || (path != null && path.contains("/api/runelite/errors"));
	}

	private void add(String fp, String level, String message, String stack, String feature,
		Map<String, Object> context) {
		add(fp, level, message, stack, feature, context, clockMs.getAsLong());
	}

	private synchronized void add(String fp, String level, String message, String stack, String feature,
		Map<String, Object> context, long occurredMs) {
		String who = currentPlayer();
		if (!seenFingerprints.contains(fp)) {
			if (seenFingerprints.size() >= MAX_DISTINCT_PER_SESSION) {
				return;
			}
			seenFingerprints.add(fp);
		}
		String key = who + "\n" + fp;
		Pending p = pending.get(key);
		if (p == null) {
			p = new Pending(who, fp, level, truncate(message, MAX_MESSAGE), truncate(stack, MAX_STACK),
				truncate(feature, MAX_FEATURE), boundedContext(context), occurredMs);
			pending.put(key, p);
		}
		p.count++;
	}

	private String currentPlayer() {
		String name = null;
		try {
			name = player.get();
		} catch (RuntimeException e) {
			// not available on this thread right now
		}
		if (name != null && !name.isEmpty()) {
			lastPlayer = name;
		}
		return lastPlayer;
	}

	/** Sends what is waiting, unless the last flush was under a minute ago. */
	public void flush() {
		flush(false);
	}

	/**
	 * @param force send now even if the last flush was recent (plugin stop); reports with no known
	 *              player are dropped then
	 */
	public void flush(boolean force) {
		List<Map.Entry<String, List<Map<String, Object>>>> calls = new ArrayList<>();
		synchronized (this) {
			if (pending.isEmpty()) {
				return;
			}
			if (!isEnabled()) {
				pending.clear();
				return;
			}
			long now = clockMs.getAsLong();
			if (!force && lastFlushMs != Long.MIN_VALUE && now - lastFlushMs < FLUSH_INTERVAL_MS) {
				return;
			}
			String fallback = currentPlayer();
			Map<String, List<Map<String, Object>>> byPlayer = new LinkedHashMap<>();
			for (java.util.Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
				Pending p = it.next();
				String who = p.player != null ? p.player : fallback;
				if (who == null) {
					if (force) {
						it.remove();
					}
					continue;
				}
				it.remove();
				byPlayer.computeIfAbsent(who, k -> new ArrayList<>()).add(toReport(p));
			}
			if (byPlayer.isEmpty()) {
				return;
			}
			lastFlushMs = now;
			for (Map.Entry<String, List<Map<String, Object>>> e : byPlayer.entrySet()) {
				List<Map<String, Object>> all = e.getValue();
				for (int i = 0; i < all.size(); i += MAX_PER_CALL) {
					calls.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(),
						new ArrayList<>(all.subList(i, Math.min(all.size(), i + MAX_PER_CALL)))));
				}
			}
		}
		for (Map.Entry<String, List<Map<String, Object>>> call : calls) {
			try {
				sender.send(call.getKey(), call.getValue());
			} catch (RuntimeException e) {
				// Never report on our own report's failure.
			}
		}
	}

	synchronized int pendingCount() {
		return pending.size();
	}

	private Map<String, Object> toReport(Pending p) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("fingerprint", p.fingerprint);
		r.put("level", p.level);
		r.put("message", p.message == null ? "" : p.message);
		r.put("stack", p.stack);
		r.put("feature", p.feature);
		r.put("context", p.context);
		r.put("plugin_version", pluginVersion);
		r.put("runelite_version", runeliteVersion);
		r.put("occurred_at", Instant.ofEpochMilli(p.firstMs).toString());
		r.put("count", p.count);
		return r;
	}

	// ------------------------------------------------------------------ pure helpers

	/** Short hash of the exception class, its message without numbers and its first 3 frames in our code. */
	static String fingerprint(Throwable t) {
		StringBuilder sb = new StringBuilder(t.getClass().getName()).append('|').append(normalize(t.getMessage()));
		for (String frame : ourFrames(t, FINGERPRINT_FRAMES)) {
			sb.append('|').append(frame);
		}
		return hash(sb.toString());
	}

	/** The first {@code max} frames in our code (class.method, no line numbers), looking into causes too. */
	static List<String> ourFrames(Throwable t, int max) {
		List<String> out = new ArrayList<>();
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cur = t; cur != null && seen.add(cur) && out.isEmpty(); cur = cur.getCause()) {
			for (StackTraceElement f : cur.getStackTrace()) {
				if (isOurs(f)) {
					out.add(f.getClassName() + "." + f.getMethodName());
					if (out.size() >= max) {
						break;
					}
				}
			}
		}
		return out;
	}

	static boolean isOurs(StackTraceElement f) {
		return f.getClassName().startsWith(OUR_PACKAGE);
	}

	/** True when this exception (or a cause) went through our code. */
	static boolean touchesOurCode(Throwable t) {
		return !ourFrames(t, 1).isEmpty();
	}

	static String normalize(String message) {
		return message == null ? "" : NUMBERS.matcher(message).replaceAll("#");
	}

	static String hash(String s) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 8; i++) {
				hex.append(String.format("%02x", d[i]));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			return truncate(Integer.toHexString(s.hashCode()), MAX_FINGERPRINT);
		}
	}

	/**
	 * The stack text with only our frames and the first other one per exception (causes included);
	 * the rest is summed up as "... N other frames".
	 */
	static String filterStack(Throwable t) {
		StringBuilder sb = new StringBuilder();
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		int depth = 0;
		for (Throwable cur = t; cur != null && seen.add(cur) && depth < MAX_CAUSES; cur = cur.getCause(), depth++) {
			if (depth > 0) {
				sb.append("Caused by: ");
			}
			sb.append(cur).append('\n');
			boolean otherKept = false;
			int skipped = 0;
			for (StackTraceElement f : cur.getStackTrace()) {
				if (isOurs(f) || !otherKept) {
					otherKept |= !isOurs(f);
					sb.append("\tat ").append(f).append('\n');
				} else {
					skipped++;
				}
			}
			if (skipped > 0) {
				sb.append("\t... ").append(skipped).append(" other frames\n");
			}
		}
		return sb.toString().trim();
	}

	/** The class to name the feature by: the first of ours in the stack, else the logger. */
	static String featureClass(Throwable t, String loggerName) {
		if (loggerName != null && loggerName.startsWith(OUR_PACKAGE)) {
			return loggerName;
		}
		List<String> frames = ourFrames(t, 1);
		if (!frames.isEmpty()) {
			String frame = frames.get(0);
			return frame.substring(0, frame.lastIndexOf('.'));
		}
		return loggerName;
	}

	/** The plugin feature a class of ours belongs to, or null for a class that is not ours. */
	static String featureFor(String className) {
		if (className == null || !className.startsWith(OUR_PACKAGE)) {
			return null;
		}
		String rest = className.substring(OUR_PACKAGE.length());
		if (rest.startsWith("api.")) {
			return "sync";
		}
		if (rest.startsWith("runelite.farming.")) {
			return "timers";
		}
		if (rest.startsWith("runelite.hunter.")) {
			return "timers";
		}
		String simple = rest.substring(rest.lastIndexOf('.') + 1);
		int inner = simple.indexOf('$');
		if (inner > 0) {
			simple = simple.substring(0, inner);
		}
		if (rest.startsWith("model.")) {
			String f = featureFor(OUR_PACKAGE + simple);
			return f == null ? "model" : f;
		}
		String[][] prefixes = {
			{"Slayer", "slayer-helper"},
			{"FarmRun", "farm-run"},
			{"FarmRoute", "farm-run"},
			{"FarmPatch", "farm-run"},
			{"FarmingCape", "farm-run"},
			{"ToolLeprechaun", "farm-run"},
			{"FarmHarvest", "farm-harvests"},
			{"FarmingUnlocks", "farm-run"},
			{"BossGear", "boss-gear"},
			{"BossTrip", "boss-gear"},
			{"Roadmap", "roadmap"},
			{"Goal", "roadmap"},
			{"NextGoal", "roadmap"},
			{"GearProgression", "gear-progression"},
			{"GearNext", "gear-progression"},
			{"GearPlan", "gear-progression"},
			{"Dailies", "dailies"},
			{"Daily", "dailies"},
			{"Bank", "bank"},
			{"Vault", "bank"},
			{"PotionStorage", "bank"},
			{"RunePouch", "bank"},
			{"PlankSack", "bank"},
			{"StorageItem", "bank"},
			{"ItemCollector", "bank"},
			{"TripSupplies", "trip-planner"},
			{"Supplies", "trip-planner"},
			{"SupplyCounter", "trip-planner"},
			{"TeleportLeftClick", "teleports"},
			{"Timer", "timers"},
			{"Loot", "loot"},
			{"NotableLoot", "loot"},
			{"OSRSCompanionPlugin", "plugin"},
			{"Sync", "sync"},
			{"SourceSyncer", "sync"},
			{"ReconcileScheduler", "sync"},
			{"WebVersionWatcher", "sync"},
			{"ErrorReporter", "error-reports"},
			{"ErrorLogAppender", "error-reports"},
		};
		for (String[] p : prefixes) {
			if (simple.startsWith(p[0])) {
				return p[1];
			}
		}
		String base = simple.endsWith("Monitor") ? simple.substring(0, simple.length() - "Monitor".length()) : simple;
		return base.isEmpty() ? null : kebab(base);
	}

	static String kebab(String camel) {
		return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2").toLowerCase();
	}

	static String truncate(String s, int max) {
		if (s == null || s.length() <= max) {
			return s;
		}
		return s.substring(0, Math.max(0, max - 1)) + "…";
	}

	/** The context with long values cut; null when it would still be over 4 KB as JSON. */
	static Map<String, Object> boundedContext(Map<String, Object> context) {
		if (context == null || context.isEmpty()) {
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : context.entrySet()) {
			Object v = e.getValue();
			if (v != null && !(v instanceof Number) && !(v instanceof Boolean)) {
				v = truncate(String.valueOf(v), 500);
			}
			out.put(truncate(e.getKey(), 64), v);
		}
		int size = 2;
		for (Map.Entry<String, Object> e : out.entrySet()) {
			// Generous JSON size estimate: quotes, colon, comma and escapes.
			size += e.getKey().length() * 2 + 6 + (e.getValue() == null ? 4 : String.valueOf(e.getValue()).length() * 2 + 2);
		}
		return size <= MAX_CONTEXT_CHARS ? out : null;
	}
}
