package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Properties;
import org.junit.Test;

public class PluginVersionTest {
	private static Properties props(String value) {
		Properties p = new Properties();
		p.setProperty("version", value);
		return p;
	}

	@Test
	public void usesTheVersionFromTheFile() {
		assertEquals("1.0", PluginVersion.resolve(props("1.0")));
		assertEquals("1.2", PluginVersion.resolve(props(" 1.2 ")));
	}

	@Test
	public void unsafeOrMissingValuesFallBack() {
		assertEquals("0.0", PluginVersion.resolve(props("1.0\r\nX: y")));
		assertEquals("0.0", PluginVersion.resolve(props(" ")));
		assertEquals("0.0", PluginVersion.resolve(null));
	}

	@Test
	public void thisBuildHasAReleaseVersion() {
		// A plain release number (1.0, 1.1, 2.0): never a "-dev" or generated one.
		assertTrue(PluginVersion.VERSION, PluginVersion.VERSION.matches("\\d+\\.\\d+"));
		assertEquals("OSRS Companion v" + PluginVersion.VERSION, PluginVersion.label());
	}
}
