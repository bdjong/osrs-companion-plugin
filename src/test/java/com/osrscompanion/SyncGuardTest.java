package com.osrscompanion;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link SyncGuard#isCompleteAppKeyEntry}, which decides whether a
 * ConfigChanged event should trigger an auto full-sync.
 */
public class SyncGuardTest
{
	private static final String FULL_KEY = "ABCDEFGHJKLMNPQR"; // 16 chars

	@Test
	public void testCompleteKeyInOSRSCompanionGroupTriggers()
	{
		assertTrue(SyncGuard.isCompleteAppKeyEntry(
			OSRSCompanionConfig.CONFIG_GROUP, OSRSCompanionConfig.APP_KEY, FULL_KEY));
	}

	@Test
	public void testKeyWithSurroundingWhitespaceIsTrimmed()
	{
		assertTrue(SyncGuard.isCompleteAppKeyEntry(
			OSRSCompanionConfig.CONFIG_GROUP, OSRSCompanionConfig.APP_KEY, "  " + FULL_KEY + "  "));
	}

	@Test
	public void testShortPartialKeyDoesNotTrigger()
	{
		// 7 chars, below the minimum length.
		assertFalse(SyncGuard.isCompleteAppKeyEntry(
			OSRSCompanionConfig.CONFIG_GROUP, OSRSCompanionConfig.APP_KEY, "ABCDEFG"));
	}

	@Test
	public void testMinLengthKeyTriggers()
	{
		// Exactly at the minimum length (boundary).
		assertTrue(SyncGuard.isCompleteAppKeyEntry(
			OSRSCompanionConfig.CONFIG_GROUP, OSRSCompanionConfig.APP_KEY, "ABCDEFGH"));
	}

	@Test
	public void testLongerThanCurrentKeyStillTriggers()
	{
		// A future, longer key must still fire (the check is a floor, not equality).
		assertTrue(SyncGuard.isCompleteAppKeyEntry(
			OSRSCompanionConfig.CONFIG_GROUP, OSRSCompanionConfig.APP_KEY, FULL_KEY + "EXTRA"));
	}

	@Test
	public void testClearedKeyDoesNotTrigger()
	{
		assertFalse(SyncGuard.isCompleteAppKeyEntry(
			OSRSCompanionConfig.CONFIG_GROUP, OSRSCompanionConfig.APP_KEY, ""));
	}

	@Test
	public void testNullValueDoesNotTrigger()
	{
		assertFalse(SyncGuard.isCompleteAppKeyEntry(
			OSRSCompanionConfig.CONFIG_GROUP, OSRSCompanionConfig.APP_KEY, null));
	}

	@Test
	public void testOtherConfigItemDoesNotTrigger()
	{
		// A full-length value on a different key (e.g. a sync toggle) must not fire.
		assertFalse(SyncGuard.isCompleteAppKeyEntry(
			OSRSCompanionConfig.CONFIG_GROUP, "syncTimeTracking", FULL_KEY));
	}

	@Test
	public void testOtherConfigGroupDoesNotTrigger()
	{
		assertFalse(SyncGuard.isCompleteAppKeyEntry(
			"timetracking", OSRSCompanionConfig.APP_KEY, FULL_KEY));
	}

	@Test
	public void onlyTheLoginScreenIsALogout() {
		assertTrue(SyncGuard.isLogout(net.runelite.api.GameState.LOGGED_IN, net.runelite.api.GameState.LOGIN_SCREEN));
		assertTrue(SyncGuard.isLogout(net.runelite.api.GameState.CONNECTION_LOST, net.runelite.api.GameState.LOGIN_SCREEN));
		assertFalse(SyncGuard.isLogout(net.runelite.api.GameState.LOGGED_IN, net.runelite.api.GameState.LOADING));
		assertFalse(SyncGuard.isLogout(net.runelite.api.GameState.LOGGED_IN, net.runelite.api.GameState.HOPPING));
		assertFalse(SyncGuard.isLogout(net.runelite.api.GameState.LOGIN_SCREEN, net.runelite.api.GameState.LOGIN_SCREEN_AUTHENTICATOR));
	}
}
