/*
 * Copyright (c) 2018 Abex
 * Copyright (c) 2018, NotFoxtrot <https://github.com/NotFoxtrot>
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * RuneLite's farming produce. Each one's name, contract name, patch type, item, growth ticks and stages come from
 * {@code produce.txt}, a table generated from RuneLite's own Produce (kept in the tests as {@code RuneLiteProduce},
 * which checks every value against it). Run: node update.js
 */
@Getter
public enum Produce
{
	WEEDS, SCARECROW, POTATO, ONION, CABBAGE, TOMATO, SWEETCORN, STRAWBERRY, WATERMELON, SNAPE_GRASS, MARIGOLD, ROSEMARY,
	NASTURTIUM, WOAD, LIMPWURT, WHITE_LILY, REDBERRIES, CADAVABERRIES, DWELLBERRIES, JANGERBERRIES, WHITEBERRIES,
	POISON_IVY, BARLEY, HAMMERSTONE, ASGARNIAN, JUTE, YANILLIAN, FLAX, KRANDORIAN, WILDBLOOD, HEMP, COTTON, GUAM,
	MARRENTILL, TARROMIN, HARRALANDER, RANARR, TOADFLAX, IRIT, AVANTOE, KWUARM, HUASCA, SNAPDRAGON, CADANTINE, LANTADYME,
	DWARF_WEED, TORSTOL, GOUTWEED, ANYHERB, OAK, WILLOW, MAPLE, YEW, MAGIC, APPLE, BANANA, ORANGE, CURRY, PINEAPPLE,
	PAPAYA, PALM, DRAGONFRUIT, CACTUS, POTATO_CACTUS, TEAK, MAHOGANY, CAMPHOR, IRONWOOD, ROSEWOOD, ATTAS, IASOR, KRONOS,
	ELKHORN_CORAL, PILLAR_CORAL, UMBRAL_CORAL, SEAWEED, GRAPE, MUSHROOM, BELLADONNA, CALQUAT, SPIRIT_TREE, CELASTRUS,
	REDWOOD, HESPORI, CRYSTAL_TREE, EMPTY_COMPOST_BIN, COMPOST, SUPERCOMPOST, ULTRACOMPOST, ROTTEN_TOMATO,
	EMPTY_BIG_COMPOST_BIN, BIG_COMPOST, BIG_SUPERCOMPOST, BIG_ULTRACOMPOST, BIG_ROTTEN_TOMATO;

	static final String TABLE = "produce.txt";

	/** User-visible name */
	private String name;
	/** Farming contract names */
	private String contractName;
	/** Patch type for the crop */
	private PatchImplementation patchImplementation;
	/** User-visible item icon */
	private int itemID;
	/** How many minutes per growth tick */
	private int tickrate;
	/** How many states this crop has during growth. Typically tickcount+1 */
	private int stages;
	/** How many minutes to regrow crops, or zero if it doesn't regrow */
	private int regrowTickrate;
	/** How many states this crop has during harvest. This is often called lives. */
	private int harvestStages;

	static
	{
		try (InputStream stream = Produce.class.getResourceAsStream(TABLE))
		{
			if (stream == null)
			{
				throw new IllegalStateException(TABLE + " is missing");
			}
			BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
			String line;
			while ((line = in.readLine()) != null)
			{
				if (line.isEmpty() || line.charAt(0) == '#')
				{
					continue;
				}
				// NAME, name, contract name, patch type (- for none), item, tick rate, stages, regrow tick rate, harvest stages
				String[] f = line.split("\t");
				Produce p = valueOf(f[0]);
				p.name = f[1];
				p.contractName = f[2];
				p.patchImplementation = "-".equals(f[3]) ? null : PatchImplementation.valueOf(f[3]);
				p.itemID = Integer.parseInt(f[4]);
				p.tickrate = Integer.parseInt(f[5]);
				p.stages = Integer.parseInt(f[6]);
				p.regrowTickrate = Integer.parseInt(f[7]);
				p.harvestStages = Integer.parseInt(f[8]);
			}
		}
		catch (IOException e)
		{
			throw new IllegalStateException("Could not read " + TABLE, e);
		}
	}

	@Nullable
	static Produce getByItemID(int itemId)
	{
		for (Produce produce : Produce.values())
		{
			if (produce.getItemID() == itemId)
			{
				return produce;
			}
		}
		return null;
	}

	@Nullable
	static Produce getByContractName(String contractName)
	{
		for (Produce produce : Produce.values())
		{
			if (produce.getContractName().equalsIgnoreCase(contractName))
			{
				return produce;
			}
		}
		return null;
	}
}
