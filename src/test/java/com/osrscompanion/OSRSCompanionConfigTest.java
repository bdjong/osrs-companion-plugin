package com.osrscompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for OSRSCompanionConfig default values.
 */
public class OSRSCompanionConfigTest
{
	@Test
	public void testDefaultAppKeyIsEmpty()
	{
		TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
		assertEquals("", config.osrsCompanionAppKey());
	}

	@Test
	public void testDefaultSyncTimeTrackingIsTrue()
	{
		TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
		assertTrue(config.syncTimeTracking());
	}

	@Test
	public void testDefaultSyncQuestsIsTrue()
	{
		TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
		assertTrue(config.syncQuests());
	}

	@Test
	public void testDefaultSyncCombatAchievementsIsTrue()
	{
		TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
		assertTrue(config.syncCombatAchievements());
	}

	@Test
	public void testDefaultSyncKillCountsIsTrue()
	{
		TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
		assertTrue(config.syncKillCounts());
	}

	@Test
	public void testDefaultShowNextGoalIsTrue()
	{
		// The overlay is on by default, matching OSRSCompanionConfig.showNextGoal().
		assertTrue(new TestOSRSCompanionConfig().showNextGoal());
	}

	@Test
	public void testDefaultGoalFeaturesAreOn()
	{
		TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
		assertTrue(config.showGoalProgress());
		assertTrue(config.showGoalPopup());
		assertEquals(GoalCompleteSound.IN_GAME, config.goalCompleteSound());
	}
}
