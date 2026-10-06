package com.osrscompanion.model;

import java.time.Instant;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimerSyncItemTest
{
	@Test
	public void testFarmingPayloadToJson()
	{
		TimerSyncItem item = new TimerSyncItem("tree", "farming guild", "Oak tree", Instant.ofEpochSecond(1700000000L), true, "TestPlayer", null, null, null, 0);
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\"timer_type\":\"tree\""));
		assertTrue(json.contains("\"region\":\"farming guild\""));
		assertTrue(json.contains("\"entity\":\"oak tree\""));
		assertTrue(json.contains("\"completed_at\""));
		assertTrue(json.contains("\"notifications_enabled\":true"));
		assertTrue(json.contains("\"player_username\":\"TestPlayer\""));
	}

	@Test
	public void testBirdHousePayloadToJson()
	{
		TimerSyncItem item = new TimerSyncItem("bird house", "Mushroom Meadow", "Mushroom Meadow (North)", Instant.ofEpochSecond(1700000100L), false, "Player1", null, null, null, 1);
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\"timer_type\":\"bird house\""));
		assertTrue(json.contains("\"region\":\"mushroom meadow\""));
		assertTrue(json.contains("\"entity\":\"mushroom meadow (north)\""));
		assertTrue(json.contains("\"notifications_enabled\":false"));
	}

	@Test
	public void testApiFormatLowercaseAndSpaces()
	{
		TimerSyncItem item = new TimerSyncItem("Herb_Patches", "Farming_Guild", "Ranarr_weed", Instant.EPOCH, true, "Test_Player", null, null, null, 0);
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\"timer_type\":\"herb patches\""));
		assertTrue(json.contains("\"region\":\"farming guild\""));
		assertTrue(json.contains("\"entity\":\"ranarr weed\""));
	}

	@Test
	public void testJsonEscaping()
	{
		TimerSyncItem item = new TimerSyncItem("tree", "region \"quotes\"", "entity", Instant.EPOCH, true, "User", null, null, null, 0);
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\\\""));
	}

	@Test
	public void testPlayerNamedAtTopLevel()
	{
		String json = TimersSyncPayload.toJson(java.util.Collections.emptyList(), "Test \"Player\"");
		assertTrue(json.startsWith("{\"player_username\":\"Test \\\"Player\\\"\",\"timers\":[]"));
	}

	@Test
	public void testNoPlayerKeepsOldShape()
	{
		assertEquals("{\"timers\":[]}", TimersSyncPayload.toJson(java.util.Collections.emptyList(), null));
	}

	@Test
	public void testCompletedAtOmittedWhenNull()
	{
		TimerSyncItem item = new TimerSyncItem("farming contract", "Farming Guild", "Ranarr", null, true, "P", null, "empty", 257, 0);
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item), "P");
		assertFalse(json.contains("completed_at"));
		assertTrue(json.contains("\"crop_state\":\"empty\""));
		assertTrue(json.contains("\"timer_type\":\"farming contract\",\"region\":\"farming guild\",\"entity\":\"ranarr\",\"crop_state\""));
	}

	@Test
	public void testEmptyTimersList()
	{
		String json = TimersSyncPayload.toJson(java.util.Collections.emptyList());
		assertTrue(json.contains("\"timers\":[]"));
	}

	@Test
	public void testStartedAtIncludedWhenNonNull()
	{
		Instant startedAt = Instant.parse("2025-02-21T12:00:00Z");
		TimerSyncItem item = new TimerSyncItem("tree", "farming guild", "Oak tree", Instant.ofEpochSecond(1700000000L), true, "TestPlayer", startedAt, null, null, 0);
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\"started_at\":\"2025-02-21T12:00:00Z\""));
	}

	@Test
	public void testCropStateIncludedWhenNonNull()
	{
		TimerSyncItem item = new TimerSyncItem("herb", "farming guild", "Ranarr", Instant.ofEpochSecond(1700000000L), true, "TestPlayer", null, "growing", null, 0);
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\"crop_state\":\"growing\""));
	}

	@Test
	public void testCropStateOmittedWhenNull()
	{
		TimerSyncItem item = new TimerSyncItem("bird house", "Mushroom Meadow", "Mushroom Meadow (North)", Instant.ofEpochSecond(1700000000L), true, "TestPlayer", null, null, null, 1);
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(!json.contains("\"crop_state\""));
	}

	@Test
	public void testOsrsItemIdIncludedWhenNonNull()
	{
		TimerSyncItem item = new TimerSyncItem("herb", "farming guild", "Ranarr", Instant.ofEpochSecond(1700000000L), true, "TestPlayer", null, "growing", 207, 0);
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\"osrs_item_id\":207"));
	}

	@Test
	public void testOsrsItemIdOmittedWhenNull()
	{
		TimerSyncItem item = new TimerSyncItem("tears of guthix", "tears of guthix", "tears of guthix", Instant.ofEpochSecond(1700000000L), true, "TestPlayer", null, null, null, 0);
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(!json.contains("\"osrs_item_id\""));
	}
}
