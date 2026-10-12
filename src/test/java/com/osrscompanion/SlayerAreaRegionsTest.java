package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.SlayerGuide;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The time at a task location over its map regions: one session, one running timer anywhere in the
 * regions the location spans; paused (not reset) outside them all. Waterbirth Island Dungeon's level 1
 * is regions 9886 (west, the ladder down) and 10142 (east).
 */
public class SlayerAreaRegionsTest {
	private static final int TICK = 600;

	/** The guide's Waterbirth location as the website sends it (radius 15 + its 10 tile margin); regions optional. */
	private static SlayerGuide.Location waterbirth(String regionIds) {
		String json = "{\"key\":\"dagannoth-waterbirth\",\"name\":\"Waterbirth Island Dungeon\",\"tile\":[2445,10145,0],\"radius\":25,"
			+ "\"region_ids\":" + regionIds + ",\"monsters\":[\"Dagannoth\",\"Dagannoth spawn\"]}";
		return new Gson().fromJson(json, SlayerGuide.Location.class);
	}

	private static SlayerGuide.Location lighthouse() {
		return new Gson().fromJson("{\"key\":\"dagannoth-lighthouse\",\"name\":\"Lighthouse caves\",\"tile\":[2515,10020,0],\"radius\":25}",
			SlayerGuide.Location.class);
	}

	private static int region(int x, int y) {
		return (x >> 6) << 8 | (y >> 6);
	}

	/** What the manager does each tick in updateArea: pick the location, start or switch the session, tick it. */
	private static final class Sim {
		final List<SlayerGuide.Location> locations;
		SlayerSession session;
		int sessionsStarted;
		long now = 1_000_000;

		Sim(SlayerGuide.Location... locations) {
			this.locations = Arrays.asList(locations);
		}

		void walk(int x, int y, int ticks) {
			for (int i = 0; i < ticks; i++) {
				now += TICK;
				SlayerGuide.Location in = SlayerHelperManager.locationHere(locations, session == null ? null : session.locationKey,
					x, y, 0, region(x, y));
				if (in != null && (session == null || !session.locationKey.equals(in.getKey()))) {
					session = SlayerSession.start("dagannoth:0", 35, "Dagannoth", null, null, in.getKey(), in.getName(), 150, 150, now);
					sessionsStarted++;
				}
				if (session != null) {
					session.tick(in != null, now);
				}
			}
		}
	}

	@Test
	public void spansTheRegionsOfItsTileAndRadiusAndItsRegionIds() {
		SlayerGuide.Location wb = waterbirth("[]");
		// 2420..2470 x 10120..10170: the west edge is in 9630, the rest in 9886 (as the website's locationRegions).
		assertEquals(Arrays.asList(9630, 9886), Arrays.asList(wb.coveredRegions().toArray()));
		assertFalse("the east caves (10142) are not in the tile's square", wb.coveredRegions().contains(10142));
		assertTrue(waterbirth("[9886,10142]").coveredRegions().contains(10142));
	}

	@Test
	public void walkingBetweenTheLocationsRegionsKeepsOneContinuousTimer() {
		Sim sim = new Sim(waterbirth("[9886,10142]"), lighthouse());
		sim.walk(2445, 10145, 100);           // A: region 9886, at the tile
		assertEquals(9886, region(2445, 10145));
		SlayerSession first = sim.session;
		long a = first.timeInAreaMs;
		sim.walk(2520, 10140, 100);           // B: region 10142, the east caves
		assertEquals(10142, region(2520, 10140));
		sim.walk(2445, 10145, 100);           // back to A
		assertSame("one session over both regions", first, sim.session);
		assertEquals(1, sim.sessionsStarted);
		assertEquals(1, first.tripCount);
		// Every tick after the first counted: nothing paused or reset at the region borders.
		assertEquals(299L * TICK, first.timeInAreaMs);
		assertTrue(first.timeInAreaMs > a);
	}

	@Test
	public void theWholeOfACoveredRegionCountsOnceTheSessionIsGoing() {
		// No region ids (today's Waterbirth data): the tile's square reaches region 9886; the session goes on
		// anywhere in it, also past the radius, but never starts there.
		SlayerGuide.Location wb = waterbirth("[]");
		List<SlayerGuide.Location> list = Arrays.asList(wb);
		int x = 2490, y = 10140;              // region 9886, 45 tiles east of the tile
		assertFalse(wb.contains(x, y, 0, region(x, y)));
		assertNull(SlayerHelperManager.locationHere(list, null, x, y, 0, region(x, y)));
		assertSame(wb, SlayerHelperManager.locationHere(list, "dagannoth-waterbirth", x, y, 0, region(x, y)));
		// Region 10142 is not the location's without its region id: outside, the time pauses.
		assertNull(SlayerHelperManager.locationHere(list, "dagannoth-waterbirth", 2520, 10140, 0, region(2520, 10140)));
	}

	@Test
	public void leavingAllTheRegionsPausesAndComingBackResumesWithoutReset() {
		Sim sim = new Sim(waterbirth("[9886,10142]"), lighthouse());
		sim.walk(2445, 10145, 50);
		SlayerSession s = sim.session;
		long before = s.timeInAreaMs;
		sim.walk(2521, 3740, 30);             // up on the island (another region): paused
		assertFalse(s.inside);
		assertEquals(before, s.timeInAreaMs);
		sim.walk(2520, 10140, 50);            // back in, the other region: same session, the time goes on
		assertSame(s, sim.session);
		assertEquals(1, sim.sessionsStarted);
		assertEquals(2, s.tripCount);
		assertEquals(before + 49L * TICK, s.timeInAreaMs);
	}

	@Test
	public void anotherLocationsAreaStillSwitchesTheSession() {
		Sim sim = new Sim(waterbirth("[9886,10142]"), lighthouse());
		sim.walk(2445, 10145, 10);
		sim.walk(2515, 10020, 10);            // the Lighthouse caves' area: killing somewhere else now
		assertEquals("dagannoth-lighthouse", sim.session.locationKey);
		assertEquals(2, sim.sessionsStarted);
	}

	@Test
	public void anAdhocSpotIsNotWidenedToItsRegions() {
		SlayerGuide.Location spot = SlayerGuide.Location.area("spot-9886", "Unknown spot (region 9886)", 2445, 10145, 0, 30, null);
		List<SlayerGuide.Location> list = Arrays.asList(spot);
		assertNull(SlayerHelperManager.locationHere(list, "spot-9886", 2490, 10170, 0, region(2490, 10170)));
	}
}
