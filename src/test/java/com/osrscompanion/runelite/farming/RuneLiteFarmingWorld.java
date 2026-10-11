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

// RuneLite's FarmingWorld (net.runelite.client.plugins.timetracking.farming), the reference for the plugin's
// farming-patches.txt (FarmingWorldTableTest). Only the patch list matters here: the farmer NPCs and the region
// bounds are kept as RuneLite has them but not compared (the plugin does not use them).

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;
import com.osrscompanion.runelite.Tab;
class RuneLiteFarmingWorld
{
	@SuppressWarnings("PMD.ImmutableField")
	private Multimap<Integer, Region> regions = HashMultimap.create();

	@SuppressWarnings("PMD.ImmutableField")
	private Map<Tab, Set<Patch>> tabs = new HashMap<>();

	private final Comparator<Patch> tabSorter = Comparator
		.comparing(Patch::getImplementation)
		.thenComparing((Patch p) -> p.getRegion().getName())
		.thenComparing(Patch::getName);

	private final Region farmingGuildRegion;

	RuneLiteFarmingWorld()
	{
		// Some of these patches get updated in multiple regions.
		// It may be worth it to add a specialization for these patches
		add(new Region("Al Kharid", 13106, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.CACTUS, NpcID.FARMING_GARDENER_CACTUS)
		), 13362, 13105);

		add(new Region("Aldarin", 5421, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HOPS, NpcID.FARMING_GARDENER_HOPS_5)
		), 5165, 5166, 5422, 5677, 5678);

		add(new Region("Anglers' Retreat", 9770, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HARDWOOD_TREE, NpcID.FARMING_GARDENER_HARDWOOD_TREE_5)
		));

		add(new Region("Ardougne", 10290, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.BUSH, NpcID.FARMING_GARDENER_BUSH_4)
		), 10546);
		add(new Region("Ardougne", 10548, false,
			new Patch("North", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.KRAGEN, 0),
			new Patch("South", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.KRAGEN, 1),
			new Patch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER),
			new Patch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB),
			new Patch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST)
		));

		add(new Region("Auburnvale", 5427, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_7),
			new Patch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.BELLADONNA)
		), 5428, 5684);

		add(new Region("Avium Savannah", 6702, true,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HARDWOOD_TREE, NpcID.FROG_QUEST_MARCELLUS)
		), 6446);

		add(new Region("Brimhaven", 11058, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.FRUIT_TREE, NpcID.GARTH),
			new Patch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.SPIRIT_TREE, NpcID.FARMING_GARDENER_SPIRIT_TREE_3)
		), 11057);

		add(new Region("Catherby", 11062, false,
			new Patch("North", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.DANTAERA, 0),
			new Patch("South", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.DANTAERA, 1),
			new Patch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER),
			new Patch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB),
			new Patch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST)
		)
		{
			@Override
			public boolean isInBounds(WorldPoint loc)
			{
				if (loc.getX() >= 2816 && loc.getY() < 3456)
				{
					//Upstairs sends different varbits
					return loc.getX() < 2840 && loc.getY() >= 3440 && loc.getPlane() == 0;
				}
				return true;
			}
		}, 11061, 11318, 11317);
		add(new Region("Catherby", 11317, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FRUIT_4)
		)
		{
			//The fruit tree patch is always sent when upstairs in 11317
			@Override
			public boolean isInBounds(WorldPoint loc)
			{
				return loc.getX() >= 2840 || loc.getY() < 3440 || loc.getPlane() == 1;
			}
		});

		add(new Region("Civitas illa Fortis", 6192, false,
			new Patch("North", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.FORTIS_GARDENER, 0),
			new Patch("South", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.FORTIS_GARDENER, 1),
			new Patch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER),
			new Patch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB),
			new Patch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST)
		), 6447, 6448, 6449, 6191, 6193);

		add(new Region("Champions' Guild", 12596, true,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.BUSH, NpcID.FARMING_GARDENER_BUSH_1)
		));

		add(new Region("Draynor Manor", 12340, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.BELLADONNA)
		));

		add(new Region("Entrana", 11060, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HOPS, NpcID.FRANCIS)
		), 11316);

		add(new Region("Etceteria", 10300, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.BUSH, NpcID.FARMING_GARDENER_BUSH_3),
			new Patch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.SPIRIT_TREE, NpcID.FARMING_GARDENER_SPIRIT_TREE_2)
		));

		add(new Region("Falador", 11828, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_2)
		), 12084);
		add(new Region("Falador", 12083, false,
			new Patch("North West", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.ELSTAN, 0),
			new Patch("South East", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.ELSTAN, 1),
			new Patch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER),
			new Patch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB),
			new Patch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST)
		)
		{
			@Override
			public boolean isInBounds(WorldPoint loc)
			{
				//Not on region boundary due to Port Sarim Spirit Tree patch
				return loc.getY() >= 3272;
			}
		});

		add(new Region("Fossil Island", 14651, false,
			new Patch("East", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HARDWOOD_TREE, NpcID.FOSSIL_SQUIRREL_GARDENER1),
			new Patch("Middle", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.HARDWOOD_TREE, NpcID.FOSSIL_SQUIRREL_GARDENER2),
			new Patch("West", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.HARDWOOD_TREE, NpcID.FOSSIL_SQUIRREL_GARDENER3)
		)
		{
			@Override
			public boolean isInBounds(WorldPoint loc)
			{
				//Hardwood tree varbits are sent anywhere on plane 0 of fossil island.
				//Varbits get sent 1 tick earlier than expected when climbing certain ladders and stairs

				//Stairs to house on the hill
				if (loc.getX() == 3753 && loc.getY() >= 3868 && loc.getY() <= 3870)
				{
					return false;
				}

				//East and west ladders to rope bridge
				if ((loc.getX() == 3729 || loc.getX() == 3728 || loc.getX() == 3747 || loc.getX() == 3746)
					&& loc.getY() <= 3832 && loc.getY() >= 3830)
				{
					return false;
				}

				return loc.getPlane() == 0;
			}
		}, 14907, 14908, 15164, 14652, 14906, 14650, 15162, 15163);
		add(new Region("Seaweed", 15008, false,
			new Patch("North", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.SEAWEED, NpcID.FOSSIL_GARDENER_UNDERWATER, 0),
			new Patch("South", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.SEAWEED, NpcID.FOSSIL_GARDENER_UNDERWATER, 1)
		));

		add(new Region("Gnome Stronghold", 9781, true,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_GNOME),
			new Patch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FRUIT_1)
		), 9782, 9526, 9525);

		add(new Region("Great Conch", 12581, true,
			new Patch("East", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.CORAL, NpcID.TORTUGAN_CORAL_FARMER, 0),
			new Patch("West", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.CORAL, NpcID.TORTUGAN_CORAL_FARMER, 1),
			new Patch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.CALQUAT, NpcID.FARMING_GARDENER_CALQUAT_3)
		),  12325, 12326, 12327, 12580, 12581, 12582, 12583, 12836, 12837, 12838, 12839, 13092, 13093, 13194);

		add(new Region("Harmony", 15148, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT),
			new Patch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.HERB)
		));

		add(new Region("Kastori", 5423, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.CALQUAT, NpcID.FARMING_GARDENER_CALQUAT_2),
			new Patch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FRUIT_7),
			new Patch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER)
		), 5167, 5424);

		add(new Region("Kourend", 6967, false,
			new Patch("North East", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.HOSIDIUS_ALLOTMENT_GARDENER, 0),
			new Patch("South West", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.HOSIDIUS_ALLOTMENT_GARDENER, 1),
			new Patch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER),
			new Patch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB),
			new Patch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST),
			new Patch("", VarbitID.FARMING_TRANSMIT_F, PatchImplementation.SPIRIT_TREE, NpcID.FARMING_GARDENER_SPIRIT_TREE_4)
		), 6711);
		add(new Region("Kourend", 7223, false,
			new Patch("East 1", VarbitID.FARMING_TRANSMIT_A1, PatchImplementation.GRAPES),
			new Patch("East 2", VarbitID.FARMING_TRANSMIT_A2, PatchImplementation.GRAPES),
			new Patch("East 3", VarbitID.FARMING_TRANSMIT_B1, PatchImplementation.GRAPES),
			new Patch("East 4", VarbitID.FARMING_TRANSMIT_B2, PatchImplementation.GRAPES),
			new Patch("East 5", VarbitID.FARMING_TRANSMIT_C1, PatchImplementation.GRAPES),
			new Patch("East 6", VarbitID.FARMING_TRANSMIT_C2, PatchImplementation.GRAPES),
			new Patch("West 1", VarbitID.FARMING_TRANSMIT_D1, PatchImplementation.GRAPES),
			new Patch("West 2", VarbitID.FARMING_TRANSMIT_D2, PatchImplementation.GRAPES),
			new Patch("West 3", VarbitID.FARMING_TRANSMIT_E1, PatchImplementation.GRAPES),
			new Patch("West 4", VarbitID.FARMING_TRANSMIT_E2, PatchImplementation.GRAPES),
			new Patch("West 5", VarbitID.FARMING_TRANSMIT_F1, PatchImplementation.GRAPES),
			new Patch("West 6", VarbitID.FARMING_TRANSMIT_F2, PatchImplementation.GRAPES)
		));

		add(new Region("Lletya", 9265, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FRUIT_TREE_5)
		), 11103);

		add(new Region("Lumbridge", 12851, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HOPS, NpcID.FARMING_GARDENER_HOPS_3)
		));
		add(new Region("Lumbridge", 12594, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_4)
		), 12850);

		add(new Region("Morytania", 13622, false,
			new Patch("Mushroom", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.MUSHROOM)
		), 13878);
		add(new Region("Morytania", 14391, false,
			new Patch("North West", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.LYRA, 0),
			new Patch("South East", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.LYRA, 1),
			new Patch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER),
			new Patch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB),
			new Patch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST)
		), 14390);

		add(new Region("Port Sarim", 12082, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.SPIRIT_TREE, NpcID.FARMING_GARDENER_SPIRIT_TREE_1)
		)
		{
			@Override
			public boolean isInBounds(WorldPoint loc)
			{
				return loc.getY() < 3272;
			}
		}, 12083);

		add(new Region("Rimmington", 11570, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.BUSH, NpcID.FARMING_GARDENER_BUSH_2)
		), 11826);

		add(new Region("Seers' Village", 10551, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HOPS, NpcID.FARMING_GARDENER_HOPS_4)
		), 10550);

		add(new Region("Tai Bwo Wannai", 11056, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.CALQUAT, NpcID.FARMING_GARDENER_CALQUAT)
		));

		add(new Region("Taverley", 11573, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_1)
		), 11829);

		add(new Region("Tree Gnome Village", 9777, true,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FRUIT_2)
		), 10033);

		add(new Region("Troll Stronghold", 11321, true,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HERB)
		));

		add(new Region("Varrock", 12854, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_3_02)
		), 12853);

		add(new Region("Yanille", 10288, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HOPS, NpcID.FARMING_GARDENER_HOPS_1)
		));

		add(new Region("Weiss", 11325, false,
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HERB)
		));

		add(new Region("Farming Guild", 5021, true,
			new Patch("Hespori", VarbitID.FARMING_TRANSMIT_J, PatchImplementation.HESPORI)
		));

		//Full 3x3 region area centered on farming guild
		add(farmingGuildRegion = new Region("Farming Guild", 4922, true,
			new Patch("", VarbitID.FARMING_TRANSMIT_G, PatchImplementation.TREE, NpcID.FARMING_GARDENER_FARMGUILD_T2),
			new Patch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.HERB),
			new Patch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.BUSH, NpcID.FARMING_GARDENER_FARMGUILD_T1, 3),
			new Patch("", VarbitID.FARMING_TRANSMIT_H, PatchImplementation.FLOWER),
			new Patch("North", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.ALLOTMENT, NpcID.FARMING_GARDENER_FARMGUILD_T1, 1),
			new Patch("South", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.ALLOTMENT, NpcID.FARMING_GARDENER_FARMGUILD_T1, 2),
			new Patch("", VarbitID.FARMING_TRANSMIT_N, PatchImplementation.BIG_COMPOST),
			new Patch("", VarbitID.FARMING_TRANSMIT_F, PatchImplementation.CACTUS, NpcID.FARMING_GARDENER_FARMGUILD_T1, 0),
			new Patch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.SPIRIT_TREE, NpcID.FARMING_GARDENER_SPIRIT_TREE_5),
			new Patch("", VarbitID.FARMING_TRANSMIT_K, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FARMGUILD_T3),
			new Patch("Anima", VarbitID.FARMING_TRANSMIT_M, PatchImplementation.ANIMA),
			new Patch("", VarbitID.FARMING_TRANSMIT_L, PatchImplementation.CELASTRUS, NpcID.FARMING_GARDENER_FARMGUILD_CELASTRUS),
			new Patch("", VarbitID.FARMING_TRANSMIT_I, PatchImplementation.REDWOOD, NpcID.FARMING_GARDENER_FARMGUILD_REDWOOD)
		), 5177, 5178, 5179, 4921, 4923, 4665, 4666, 4667);

		//All of Prifddinas, and all of Prifddinas Underground
		add(new Region("Prifddinas", 13151, false,
				new Patch("North", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.PRIF_GARDENER, 0),
				new Patch("South", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.PRIF_GARDENER, 1),
				new Patch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER),
				new Patch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.CRYSTAL_TREE),
				new Patch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.COMPOST)
			), 12895, 12894, 13150,
			/* Underground */ 12994, 12993, 12737, 12738, 12126, 12127, 13250);

		// Finalize
		this.regions = Multimaps.unmodifiableMultimap(this.regions);
		Map<Tab, Set<Patch>> umtabs = new TreeMap<>();
		for (Map.Entry<Tab, Set<Patch>> e : tabs.entrySet())
		{
			umtabs.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
		}
		this.tabs = Collections.unmodifiableMap(umtabs);
	}

	/** Every region, in the order added. */
	final java.util.List<Region> all = new java.util.ArrayList<>();

	private void add(Region r, int... extraRegions)
	{
		all.add(r);
		regions.put(r.getRegionID(), r);
		for (int er : extraRegions)
		{
			regions.put(er, r);
		}
		for (Patch p : r.getPatches())
		{
			tabs
				.computeIfAbsent(p.getImplementation().getTab(), k -> new TreeSet<>(tabSorter))
				.add(p);
		}
	}

	Collection<Region> getRegionsForLocation(WorldPoint location)
	{
		return this.regions.get(location.getRegionID()).stream()
			.filter(region -> region.isInBounds(location))
			.collect(Collectors.toSet());
	}

	static class Region
	{
		final String name;
		final int regionID;
		final Patch[] patches;

		Region(String name, int regionID, boolean definite, Patch... patches)
		{
			this.name = name;
			this.regionID = regionID;
			this.patches = patches;
			for (Patch p : patches)
			{
				p.region = this;
			}
		}

		String getName()
		{
			return name;
		}

		int getRegionID()
		{
			return regionID;
		}

		Patch[] getPatches()
		{
			return patches;
		}

		public boolean isInBounds(WorldPoint loc)
		{
			return true;
		}
	}

	static class Patch
	{
		Region region;
		final String name;
		final int varbit;
		final PatchImplementation implementation;

		Patch(String name, int varbit, PatchImplementation implementation, int... npcAndPatchNumber)
		{
			this.name = name;
			this.varbit = varbit;
			this.implementation = implementation;
		}

		Region getRegion()
		{
			return region;
		}

		String getName()
		{
			return name;
		}

		int getVarbit()
		{
			return varbit;
		}

		PatchImplementation getImplementation()
		{
			return implementation;
		}
	}

	Map<Tab, Set<Patch>> getTabs()
	{
		return tabs;
	}
}
