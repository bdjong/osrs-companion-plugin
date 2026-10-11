// Auto-generated from RuneLite. Do not edit. Run: node update.js

/*
 * Copyright (c) 2018, NotFoxtrot <https://github.com/NotFoxtrot>
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

import com.osrscompanion.runelite.Tab;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.Getter;

/**
 * RuneLite's farming patches by Time Tracking tab, read from {@code farming-patches.txt} (generated from
 * RuneLite's FarmingWorld and checked against it by FarmingWorldTableTest).
 */
public class FarmingWorld
{
	static final String TABLE = "farming-patches.txt";

	@Getter
	private final Map<Tab, Set<FarmingPatch>> tabs;

	public FarmingWorld()
	{
		Comparator<FarmingPatch> tabSorter = Comparator
			.comparing(FarmingPatch::getImplementation)
			.thenComparing((FarmingPatch p) -> p.getRegion().getName())
			.thenComparing(FarmingPatch::getName);
		Map<Tab, Set<FarmingPatch>> byTab = new TreeMap<>();
		for (FarmingRegion r : regions())
		{
			for (FarmingPatch p : r.getPatches())
			{
				byTab.computeIfAbsent(p.getImplementation().getTab(), k -> new TreeSet<>(tabSorter)).add(p);
			}
		}
		byTab.replaceAll((tab, patches) -> Collections.unmodifiableSet(patches));
		tabs = Collections.unmodifiableMap(byTab);
	}

	/** One line per patch, {@code REGION|REGION_ID|PATCH|VARBIT|TYPE}; a region's patches are consecutive. */
	static List<FarmingRegion> regions()
	{
		List<FarmingRegion> out = new ArrayList<>();
		try (InputStream stream = FarmingWorld.class.getResourceAsStream(TABLE))
		{
			if (stream == null)
			{
				throw new IllegalStateException(TABLE + " is missing");
			}
			BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
			List<FarmingPatch> patches = new ArrayList<>();
			String region = null;
			for (String line = in.readLine(); ; line = in.readLine())
			{
				String[] f = line == null || line.startsWith("#") ? null : line.split("\\|", -1);
				String key = f == null ? null : f[0] + "|" + f[1];
				if (region != null && (line == null || f != null && !key.equals(region)))
				{
					String[] r = region.split("\\|");
					out.add(new FarmingRegion(r[0], Integer.parseInt(r[1]), patches.toArray(new FarmingPatch[0])));
					patches.clear();
				}
				if (line == null)
				{
					return out;
				}
				if (f != null)
				{
					region = key;
					patches.add(new FarmingPatch(f[2], Integer.parseInt(f[3]), PatchImplementation.valueOf(f[4])));
				}
			}
		}
		catch (IOException e)
		{
			throw new IllegalStateException("Could not read " + TABLE, e);
		}
	}
}
