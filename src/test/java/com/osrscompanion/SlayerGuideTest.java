package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.SlayerGuide;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The guide's JSON, the area check, task monster names and the helper's number formats. */
public class SlayerGuideTest {
	private static final String GUIDE = "{\"task\":{\"task_id\":42,\"task_name\":\"Abyssal demons\",\"area_id\":null,\"area_name\":null,"
		+ "\"amount_remaining\":143,\"amount_original\":190,\"master\":\"Duradel\"},"
		+ "\"locations\":[{\"key\":\"abyssal-demons-catacombs\",\"name\":\"Catacombs of Kourend\",\"tile\":[1675,10090,0],\"radius\":20,"
		+ "\"region_ids\":[6556,6557],\"multi\":true,\"recommended\":true,\"why\":\"Multi-combat, safe spot\","
		+ "\"requirements\":[\"Kourend Catacombs access\"],\"travel\":{\"name\":\"Xeric's talisman (Xeric's Heart)\","
		+ "\"how\":\"Rub Xeric's talisman, then run into the Catacombs.\",\"items\":[13393],\"spell\":false},"
		+ "\"steps\":[\"Rub Xeric's talisman: Xeric's Heart\",\"Enter the Catacombs (statue)\"],\"monsters\":[\"Abyssal demon\"]},"
		+ "{\"key\":\"abyssal-demons-slayer-tower\",\"name\":\"Slayer Tower\",\"tile\":[3420,3570,2],\"radius\":10}],"
		+ "\"selected\":\"abyssal-demons-catacombs\"}";

	@Test
	public void readsTheContractsGuide() {
		SlayerGuide g = new Gson().fromJson(GUIDE, SlayerGuide.class);
		assertEquals(Integer.valueOf(42), g.getTask().getTaskId());
		assertNull(g.getTask().getAreaId());
		assertEquals(143, g.getTask().getAmountRemaining());
		assertEquals("Duradel", g.getTask().getMaster());
		assertEquals(2, g.getLocations().size());
		SlayerGuide.Location l = g.selectedLocation();
		assertEquals("Catacombs of Kourend", l.getName());
		assertEquals(1675, l.getX());
		assertEquals(10090, l.getY());
		assertEquals(0, l.getPlane());
		assertTrue(l.isMulti());
		assertEquals(Collections.singletonList(13393), l.getTravel().getItems());
		assertEquals(Collections.singletonList("Abyssal demon"), l.getMonsters());
		// The second one sent no travel, steps or regions.
		SlayerGuide.Location tower = g.getLocations().get(1);
		assertNull(tower.getTravel());
		assertTrue(tower.getSteps().isEmpty());
		assertTrue(tower.getRegionIds().isEmpty());
	}

	@Test
	public void picksTheSelectedThenTheRecommendedLocation() {
		Gson gson = new Gson();
		SlayerGuide picked = gson.fromJson(GUIDE.replace("\"selected\":\"abyssal-demons-catacombs\"", "\"selected\":\"abyssal-demons-slayer-tower\""),
			SlayerGuide.class);
		assertEquals("Slayer Tower", picked.selectedLocation().getName());
		SlayerGuide none = gson.fromJson(GUIDE.replace("\"selected\":\"abyssal-demons-catacombs\"", "\"selected\":null"), SlayerGuide.class);
		assertEquals("Catacombs of Kourend", none.selectedLocation().getName());
		assertNull(gson.fromJson("{\"task\":null,\"locations\":[],\"selected\":null}", SlayerGuide.class).selectedLocation());
	}

	@Test
	public void inTheAreaByRadiusOnTheSamePlaneOrByRegion() {
		SlayerGuide.Location l = new Gson().fromJson(GUIDE, SlayerGuide.class).selectedLocation();
		assertTrue(l.contains(1675, 10090, 0, 0));
		assertTrue(l.contains(1695, 10070, 0, 0));
		assertFalse(l.contains(1696, 10090, 0, 0));
		// Same tiles, another floor.
		assertFalse(l.contains(1675, 10090, 1, 0));
		// Anywhere in one of its map regions.
		assertTrue(l.contains(1600, 10000, 1, 6557));
	}

	@Test
	public void taskMonstersByTheLocationsNamesOrTheTask() {
		assertTrue(SlayerMonsters.matches("Abyssal demon", Arrays.asList("Abyssal demon"), "Abyssal demons"));
		// The location's list decides when there is one.
		assertFalse(SlayerMonsters.matches("Greater abyssal demon", Arrays.asList("Abyssal demon"), "Abyssal demons"));
		assertTrue(SlayerMonsters.matches("<col=ffff00>abyssal demon</col>", Arrays.asList("Abyssal demon"), null));
		// Without one, loosely by the task's name.
		assertTrue(SlayerMonsters.matches("Abyssal demon", null, "Abyssal demons"));
		assertTrue(SlayerMonsters.matches("Zombie", null, "Zombies"));
		assertTrue(SlayerMonsters.matches("Wolf", null, "Wolves"));
		assertTrue(SlayerMonsters.matches("Kalphite Worker", null, "Kalphite"));
		assertTrue(SlayerMonsters.matches("Mutated Bloodveld", null, "Bloodvelds"));
		assertTrue(SlayerMonsters.matches("Dust devil", null, "Dust devils"));
		assertFalse(SlayerMonsters.matches("Black demon", null, "Abyssal demons"));
		assertFalse(SlayerMonsters.matches("Guard", null, "Abyssal demons"));
		assertFalse(SlayerMonsters.matches(null, null, "Abyssal demons"));
		assertFalse(SlayerMonsters.matches("Abyssal demon", null, null));
	}

	@Test
	public void formats() {
		assertEquals("0:00", SlayerFormat.clock(0));
		assertEquals("4:05", SlayerFormat.clock(245_400));
		assertEquals("1:02:09", SlayerFormat.clock(3_729_000));
		assertEquals("18.5s", SlayerFormat.seconds(18_500));
		assertEquals("1:05", SlayerFormat.seconds(65_000));
		assertEquals("950 gp", SlayerFormat.gp(950));
		assertEquals("33.5K", SlayerFormat.gp(33_500));
		assertEquals("2.35M", SlayerFormat.gp(2_350_000));
		assertEquals("120K", SlayerFormat.gp(120_000));
	}

	@Test
	public void compassDirections() {
		assertEquals("north", SlayerFormat.compass(0, 10));
		assertEquals("north-east", SlayerFormat.compass(100, 90));
		assertEquals("south-west", SlayerFormat.compass(-50, -60));
		assertEquals("west", SlayerFormat.compass(-300, 20));
		assertNull(SlayerFormat.compass(0, 0));
	}

	@Test
	public void readsTheWayPointsInOrder() {
		String json = "{\"locations\":[{\"key\":\"black-demons-crash-site-cavern\",\"tile\":[2112,5660,0],"
			+ "\"waypoints\":[[2453,3520,0],[2027,5612,0],[1,2]]}]}";
		SlayerGuide.Location l = new Gson().fromJson(json, SlayerGuide.class).getLocations().get(0);
		assertEquals(2, l.getWaypoints().size());
		assertEquals(2453, l.getWaypoints().get(0)[0]);
		assertEquals(5612, l.getWaypoints().get(1)[1]);
		assertTrue(new Gson().fromJson("{\"locations\":[{\"key\":\"x\"}]}", SlayerGuide.class).getLocations().get(0).getWaypoints().isEmpty());
	}
}
