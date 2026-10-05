package com.osrscompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for HouseLocationMonitor config toggle behavior.
 */
public class HouseLocationMonitorTest {
	private TestOSRSCompanionConfig config;

	@Before
	public void setUp() {
		config = new TestOSRSCompanionConfig();
	}

	@Test
	public void testConfigSyncHouseLocationDefaultIsTrue() {
		assertTrue(config.syncHouseLocation());
	}

	@Test
	public void testConfigSyncHouseLocationCanBeDisabled() {
		config.setSyncHouseLocation(false);
		assertFalse(config.syncHouseLocation());
	}

	@Test
	public void testConfigSyncHouseLocationCanBeReEnabled() {
		config.setSyncHouseLocation(false);
		assertFalse(config.syncHouseLocation());

		config.setSyncHouseLocation(true);
		assertTrue(config.syncHouseLocation());
	}
}
