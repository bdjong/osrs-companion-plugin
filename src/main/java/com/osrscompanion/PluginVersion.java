package com.osrscompanion;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * This plugin's version, {@code <base>.<patch>} (for example {@code 0.1.37}). The base is set in
 * {@code version.properties}; the build counts the patch (commits since the base last changed) and
 * writes it to {@code build-version.properties}. A build without that file (no git history, or a
 * build.gradle that does not generate it) reports {@code <base>-dev}.
 */
public final class PluginVersion {
	static final String BASE_RESOURCE = "version.properties";
	static final String BUILD_RESOURCE = "build-version.properties";
	/** What a version may look like: it is sent as an HTTP header and stored by the website. */
	private static final Pattern SAFE = Pattern.compile("[0-9A-Za-z._-]{1,32}");
	private static final String FALLBACK_BASE = "0.0";

	/** This build's version, for example {@code 0.1.37} or {@code 0.1-dev}. */
	public static final String VERSION = resolve(load(BUILD_RESOURCE), load(BASE_RESOURCE));

	private PluginVersion() {
	}

	/** "OSRS Companion v0.1.37", for the side panel. */
	public static String label() {
		return "OSRS Companion v" + VERSION;
	}

	/** Picks the generated version, else the base plus "-dev"; never returns something header-unsafe. */
	static String resolve(Properties build, Properties base) {
		String generated = build == null ? null : trim(build.getProperty("version"));
		if (generated != null && SAFE.matcher(generated).matches()) {
			return generated;
		}
		String b = base == null ? null : trim(base.getProperty("base"));
		if (b == null || !SAFE.matcher(b + "-dev").matches()) {
			b = FALLBACK_BASE;
		}
		return b + "-dev";
	}

	private static String trim(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static Properties load(String name) {
		try (InputStream in = PluginVersion.class.getResourceAsStream(name)) {
			if (in == null) {
				return null;
			}
			Properties p = new Properties();
			p.load(in);
			return p;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
