package com.osrscompanion;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * This plugin's version (for example {@code 1.0.0}), from {@code version.properties}. It is set by
 * hand for each Plugin Hub release, so every build of the same commit reports the same version.
 */
public final class PluginVersion {
	static final String RESOURCE = "version.properties";
	/** What a version may look like: it is sent as an HTTP header and stored by the website. */
	private static final Pattern SAFE = Pattern.compile("[0-9A-Za-z._-]{1,32}");
	private static final String FALLBACK = "0.0";

	/** This build's version, for example {@code 1.0.0}. */
	public static final String VERSION = resolve(load(RESOURCE));

	private PluginVersion() {
	}

	/** "OSRS Companion v1.0.0", for the side panel; a local build adds its commit ("v1.0.2-dev 48a4256"). */
	public static String label() {
		Properties dev = load("dev-build.properties");
		String commit = dev == null ? null : dev.getProperty("commit");
		return "OSRS Companion v" + VERSION + (commit == null ? "" : "-dev " + commit);
	}

	/** The file's version, or {@value #FALLBACK} when it is missing or not header-safe. */
	static String resolve(Properties props) {
		String v = props == null ? null : props.getProperty("version");
		v = v == null ? null : v.trim();
		return v != null && SAFE.matcher(v).matches() ? v : FALLBACK;
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
