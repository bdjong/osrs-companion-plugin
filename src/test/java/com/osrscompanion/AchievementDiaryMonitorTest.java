package com.osrscompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for AchievementDiaryMonitor config toggle behavior.
 */
public class AchievementDiaryMonitorTest {
	private TestOSRSCompanionConfig config;

	@Before
	public void setUp() {
		config = new TestOSRSCompanionConfig();
	}

	@Test
	public void testConfigSyncAchievementDiariesDefaultIsTrue() {
		assertTrue(config.syncAchievementDiaries());
	}

	@Test
	public void testConfigSyncAchievementDiariesCanBeDisabled() {
		config.setSyncAchievementDiaries(false);
		assertFalse(config.syncAchievementDiaries());
	}

	@Test
	public void testConfigSyncAchievementDiariesCanBeReEnabled() {
		config.setSyncAchievementDiaries(false);
		assertFalse(config.syncAchievementDiaries());

		config.setSyncAchievementDiaries(true);
		assertTrue(config.syncAchievementDiaries());
	}
}
