// Auto-generated from RuneLite. Do not edit. Run: node update.js

/*
 * Copyright (c) 2018 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 *  ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.osrscompanion.runelite.farming;

import java.time.Instant;
import java.util.EnumSet;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;

/** RuneLite's patch prediction from what its Time Tracking plugin stored (read only). */
@RequiredArgsConstructor
public class FarmingTracker
{
	private final Client client;
	private final ConfigManager configManager;

	@Nullable
	public PatchPrediction predictPatch(FarmingPatch patch)
	{
		return predictPatch(patch, configManager.getRSProfileKey());
	}

	@Nullable
	public PatchPrediction predictPatch(FarmingPatch patch, String profile)
	{
		long unixNow = Instant.now().getEpochSecond();

		boolean autoweed = Integer.toString(Autoweed.ON.ordinal())
			.equals(configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profile, TimeTrackingConfig.AUTOWEED));

		String key = patch.configKey();
		String storedValue = configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profile, key);

		if (storedValue == null)
		{
			return null;
		}

		long unixTime = 0;
		int value = 0;
		{
			String[] parts = storedValue.split(":");
			if (parts.length == 2)
			{
				try
				{
					value = Integer.parseInt(parts[0]);
					unixTime = Long.parseLong(parts[1]);
				}
				catch (NumberFormatException e)
				{
				}
			}
		}

		if (unixTime <= 0)
		{
			return null;
		}

		PatchState state = patch.getImplementation().forVarbitValue(value);

		if (state == null)
		{
			return null;
		}

		int stage = state.getStage();
		int stages = state.getStages();
		int tickrate = state.getTickRate();

		// Farming ticks on leagues worlds are 1 minute instead of 5
		if (isLeaguesWorld())
		{
			tickrate = tickrate / 5;
		}

		if (autoweed && state.getProduce() == Produce.WEEDS)
		{
			stage = 0;
			stages = 1;
			tickrate = 0;
		}

		long doneEstimate = 0;
		if (tickrate > 0)
		{
			long tickNow = getTickTime(tickrate, 0, unixNow, profile);
			long tickTime = getTickTime(tickrate, 0, unixTime, profile);
			int delta = (int) (tickNow - tickTime) / (tickrate * 60);

			doneEstimate = getTickTime(tickrate, stages - 1 - stage, tickTime, profile);

			stage += delta;
			if (stage >= stages)
			{
				stage = stages - 1;
			}
		}

		return new PatchPrediction(
			state.getProduce(),
			state.getCropState(),
			doneEstimate,
			stage,
			stages
		);
	}

	public long getTickTime(int tickRate, int ticks, long requestedTime, String profile)
	{
		Integer offsetPrecisionMins = configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profile, TimeTrackingConfig.FARM_TICK_OFFSET_PRECISION, int.class);
		Integer offsetTimeMins = configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profile, TimeTrackingConfig.FARM_TICK_OFFSET, int.class);

		//All offsets are negative but are stored as positive
		long calculatedOffsetTime = 0L;
		if (offsetPrecisionMins != null && offsetTimeMins != null && (offsetPrecisionMins >= tickRate || offsetPrecisionMins >= 40))
		{
			calculatedOffsetTime = (offsetTimeMins % tickRate) * 60;
		}

		//Calculate "now" as +offset seconds in the future so we calculate the correct ticks
		long unixNow = requestedTime + calculatedOffsetTime;

		//The time that the tick requested will happen
		long timeOfCurrentTick = (unixNow - (unixNow % (tickRate * 60)));
		long timeOfGoalTick = timeOfCurrentTick + (ticks * tickRate * 60);

		//Move ourselves back to real time
		return timeOfGoalTick - calculatedOffsetTime;
	}

	private boolean isLeaguesWorld()
	{
		final EnumSet<WorldType> worldTypes = client.getWorldType();
		return (worldTypes.contains(WorldType.SEASONAL) && !worldTypes.contains(WorldType.DEADMAN));
	}
}
