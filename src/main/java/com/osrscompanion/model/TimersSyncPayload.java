package com.osrscompanion.model;

import java.util.List;

/**
 * Bulk timers sync payload matching the backend TimersSyncSerializer.
 * {"player_username"?, "timers": [{timer_type, region, entity, completed_at, started_at?, crop_state?, osrs_item_id?, notifications_enabled, player_username}, ...]}
 * All strings are lowercased with spaces (no underscores).
 * started_at is optional; when present it enables the Flutter app to show the timer as active.
 * crop_state is optional; when present (farming patches only) it indicates growing, harvestable, etc.
 */
public final class TimersSyncPayload
{
	private TimersSyncPayload() {}

	/**
	 * Serialize the timers list to JSON for the sync endpoint.
	 */
	public static String toJson(List<TimerSyncItem> timers)
	{
		return toJson(timers, null);
	}

	/**
	 * Serialize with the player named at the top level as well: the server finds the
	 * character from it, which also lets an empty list (nothing growing) clear old timers.
	 */
	public static String toJson(List<TimerSyncItem> timers, String playerUsername)
	{
		StringBuilder sb = new StringBuilder("{");
		if (playerUsername != null && !playerUsername.isBlank())
		{
			sb.append("\"player_username\":\"").append(TimerSyncItem.escapeJson(playerUsername)).append("\",");
		}
		sb.append("\"timers\":[");
		for (int i = 0; i < timers.size(); i++)
		{
			if (i > 0)
			{
				sb.append(",");
			}
			timers.get(i).appendJson(sb);
		}
		sb.append("]}");
		return sb.toString();
	}
}
