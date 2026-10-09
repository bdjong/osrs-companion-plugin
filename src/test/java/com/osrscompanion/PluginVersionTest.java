package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Properties;
import org.junit.Test;

public class PluginVersionTest {
	private static Properties props(String key, String value) {
		Properties p = new Properties();
		p.setProperty(key, value);
		return p;
	}

	@Test
	public void usesTheGeneratedVersion() {
		assertEquals("0.1.37", PluginVersion.resolve(props("version", "0.1.37"), props("base", "0.1")));
	}

	@Test
	public void fallsBackToBaseDevWithoutAGeneratedVersion() {
		assertEquals("0.1-dev", PluginVersion.resolve(null, props("base", "0.1")));
		assertEquals("0.2-dev", PluginVersion.resolve(props("version", " "), props("base", "0.2")));
	}

	@Test
	public void unsafeValuesAreNotUsed() {
		assertEquals("0.1-dev", PluginVersion.resolve(props("version", "0.1.3\r\nX: y"), props("base", "0.1")));
		assertEquals("0.0-dev", PluginVersion.resolve(null, props("base", "bad value")));
		assertEquals("0.0-dev", PluginVersion.resolve(null, null));
	}

	@Test
	public void thisBuildHasAVersion() {
		assertTrue(PluginVersion.VERSION, PluginVersion.VERSION.matches("\\d+\\.\\d+(\\.\\d+|-dev)"));
		assertEquals("OSRS Companion v" + PluginVersion.VERSION, PluginVersion.label());
	}
}
