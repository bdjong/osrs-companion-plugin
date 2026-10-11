/*
 * Copyright (c) 2019 Abex
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

import com.osrscompanion.runelite.Tab;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * RuneLite's farming patch types. What a patch's varbit value means (produce, crop state, stage) comes from
 * {@code patch-states.txt}, a table generated from RuneLite's own PatchImplementation (kept in the tests as
 * {@code RuneLitePatchImplementation}, which checks every value against it). Run: node update.js
 */
@RequiredArgsConstructor
@Getter
public enum PatchImplementation
{
	MUSHROOM(Tab.SPECIAL, "", false),
	HESPORI(Tab.SPECIAL, "", true),
	ALLOTMENT(Tab.ALLOTMENT, "", false),
	HERB(Tab.HERB, "", false),
	FLOWER(Tab.FLOWER, "", false),
	BUSH(Tab.BUSH, "", true),
	FRUIT_TREE(Tab.FRUIT_TREE, "", true),
	HOPS(Tab.HOPS, "", false),
	TREE(Tab.TREE, "", true),
	HARDWOOD_TREE(Tab.TREE, "Hardwood Trees", true),
	REDWOOD(Tab.TREE, "Redwood Trees", true),
	SPIRIT_TREE(Tab.TREE, "Spirit Trees", true),
	ANIMA(Tab.SPECIAL, "", false),
	BELLADONNA(Tab.SPECIAL, "Belladonna", false),
	CACTUS(Tab.SPECIAL, "Cactus", true),
	CORAL(Tab.SPECIAL, "Coral", false),
	SEAWEED(Tab.SPECIAL, "Seaweed", false),
	CALQUAT(Tab.FRUIT_TREE, "Calquat", true),
	CELASTRUS(Tab.FRUIT_TREE, "Celastrus", true),
	GRAPES(Tab.GRAPE, "", true),
	CRYSTAL_TREE(Tab.FRUIT_TREE, "Crystal Tree", true),
	COMPOST(Tab.SPECIAL, "Compost Bin", true),
	BIG_COMPOST(Tab.SPECIAL, "Big Compost Bin", true);

	/** Varbit values a patch can have (RuneLite's mapping covers 0 to 255). */
	static final int VALUES = 256;
	static final String TABLE = "patch-states.txt";

	private static volatile Map<PatchImplementation, PatchState[]> states;

	private final Tab tab;

	private final String name;

	private final boolean healthCheckRequired;

	@Nullable
	PatchState forVarbitValue(int value)
	{
		PatchState[] byValue = table().get(this);
		return byValue == null || value < 0 || value >= VALUES ? null : byValue[value];
	}

	private static Map<PatchImplementation, PatchState[]> table()
	{
		Map<PatchImplementation, PatchState[]> t = states;
		if (t == null)
		{
			t = load();
			states = t;
		}
		return t;
	}

	/**
	 * One line per run of values: {@code TYPE FROM TO PRODUCE CROP_STATE STAGE STEP}, the stage being
	 * {@code STAGE + STEP * (value - FROM)}. Values without a line mean nothing (null), as in RuneLite.
	 */
	static Map<PatchImplementation, PatchState[]> parse(BufferedReader in) throws IOException
	{
		Map<PatchImplementation, PatchState[]> out = new EnumMap<>(PatchImplementation.class);
		String line;
		while ((line = in.readLine()) != null)
		{
			if (line.isEmpty() || line.charAt(0) == '#')
			{
				continue;
			}
			String[] f = line.split(" ");
			PatchState[] byValue = out.computeIfAbsent(valueOf(f[0]), k -> new PatchState[VALUES]);
			int from = Integer.parseInt(f[1]);
			int to = Integer.parseInt(f[2]);
			Produce produce = Produce.valueOf(f[3]);
			CropState cropState = CropState.valueOf(f[4]);
			int stage = Integer.parseInt(f[5]);
			int step = Integer.parseInt(f[6]);
			for (int v = from; v <= to; v++)
			{
				byValue[v] = new PatchState(produce, cropState, stage + step * (v - from));
			}
		}
		return out;
	}

	private static Map<PatchImplementation, PatchState[]> load()
	{
		try (InputStream stream = PatchImplementation.class.getResourceAsStream(TABLE))
		{
			if (stream == null)
			{
				throw new IllegalStateException(TABLE + " is missing");
			}
			return parse(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
		}
		catch (IOException e)
		{
			throw new IllegalStateException("Could not read " + TABLE, e);
		}
	}
}
