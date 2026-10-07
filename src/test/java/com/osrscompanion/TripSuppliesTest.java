package com.osrscompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TripSuppliesTest {
	@Test
	public void dosedNamesCountByDose() {
		TripSupplies.Dosed d = TripSupplies.dosed("Prayer potion(3)");
		assertEquals("prayer potion", d.stem);
		assertEquals(3, d.dose);
		assertEquals("divine super combat potion", TripSupplies.dosed("Divine super combat potion(4)").stem);
		assertNull(TripSupplies.dosed("Shark"));
		assertNull(TripSupplies.dosed("Black mask (10)"));
		assertEquals("d:prayer potion", TripSupplies.key(2434, "Prayer potion(4)"));
		assertEquals("i:385", TripSupplies.key(385, "Shark"));
		assertEquals(12, TripSupplies.units("Prayer potion(4)", 3));
		assertEquals(500, TripSupplies.units("Dragon arrow", 500));
	}

	@Test
	public void usedCountsOnlyWhatWentDownAndWasBrought() {
		Map<String, Long> before = new HashMap<>();
		before.put("d:prayer potion", 16L); // four (4)s
		before.put("i:385", 10L);
		before.put("i:536", 2L); // dragon bones picked up, not brought
		Map<String, Long> after = new HashMap<>();
		after.put("d:prayer potion", 13L); // a (4) became a (3): one dose
		after.put("i:385", 12L); // sharks picked up: not negative use
		Map<String, Long> used = new HashMap<>();
		TripSupplies.addUsed(before, after, new HashSet<>(Arrays.asList("d:prayer potion", "i:385")), used);
		assertEquals(Long.valueOf(3), used.get("d:prayer potion"));
		assertNull(used.get("i:385"));
		assertNull(used.get("i:536"));

		// Eating the picked-up sharks later counts: sharks were brought.
		Map<String, Long> later = new HashMap<>(after);
		later.put("i:385", 9L);
		TripSupplies.addUsed(after, later, new HashSet<>(Arrays.asList("d:prayer potion", "i:385")), used);
		assertEquals(Long.valueOf(3), used.get("i:385"));
	}

	@Test
	public void tripsSupportedCountsDosesAcrossVariants() {
		// Bank: 10 x Prayer potion(4) and 3 x (3) = 49 doses; a trip drinks 8. 50 sharks, 6 a trip.
		Map<String, Long> have = new HashMap<>();
		have.put("d:prayer potion", TripSupplies.units("Prayer potion(4)", 10) + TripSupplies.units("Prayer potion(3)", 3));
		have.put("i:385", 50L);
		Map<String, Double> need = new LinkedHashMap<>();
		need.put("i:385", 6.0);
		need.put("d:prayer potion", 8.0);
		TripSupplies.Check check = TripSupplies.tripsSupported(need, have);
		assertEquals(6, check.trips);
		assertEquals("d:prayer potion", check.limiting().key);
		assertEquals(8, check.needs.get(1).trips);
	}

	@Test
	public void missingSupplyMeansNoTrip() {
		Map<String, Double> need = new LinkedHashMap<>();
		need.put("i:560", 250.0);
		TripSupplies.Check check = TripSupplies.tripsSupported(need, Collections.singletonMap("i:560", 249L));
		assertEquals(0, check.trips);
		assertEquals(0, TripSupplies.tripsSupported(Collections.emptyMap(), Collections.emptyMap()).trips);
	}

	@Test
	public void averagePerTripCountsTripsThatUsedNone() {
		Map<String, Long> a = new HashMap<>();
		a.put("d:prayer potion", 10L);
		a.put("i:385", 4L);
		Map<String, Long> b = new HashMap<>();
		b.put("d:prayer potion", 6L);
		Map<String, Double> avg = TripSupplies.averagePerTrip(Arrays.asList(a, b));
		assertEquals(8.0, avg.get("d:prayer potion"), 1e-9);
		assertEquals(2.0, avg.get("i:385"), 1e-9);
		// 33 doses with 8.0 a trip: 4 trips, but no sharks: none, and sharks run out first.
		TripSupplies.Check check = TripSupplies.tripsSupported(avg, Collections.singletonMap("d:prayer potion", 33L));
		assertEquals(0, check.trips);
		assertEquals("i:385", check.limiting().key);
		assertEquals(4, check.needs.get(1).trips);
	}

	@Test
	public void bossNamesMatchTheBossGearPage() {
		assertEquals("vorkath", TripSupplies.bossKey("Vorkath"));
		assertEquals(TripSupplies.bossKey("Dagannoth Kings"), TripSupplies.bossKey("Dagannoth Rex"));
		assertEquals(TripSupplies.bossKey("The Nightmare"), TripSupplies.bossKey("Nightmare"));
		assertEquals(TripSupplies.bossKey("Kree'arra"), TripSupplies.bossKey("kreearra"));
		assertEquals("theatreofblood", TripSupplies.bossKey("Theatre of Blood: Hard Mode"));
		assertEquals("barrows", TripSupplies.bossKey("Barrows chest"));
	}

	@Test
	public void killCountMessagesNameTheBoss() {
		assertEquals("Vorkath", BossTripTracker.killCountBoss("Your Vorkath kill count is: 152."));
		assertEquals("Chambers of Xeric", BossTripTracker.killCountBoss("Your completed Chambers of Xeric count is: 12."));
		assertEquals("Barrows chest", BossTripTracker.killCountBoss("Your Barrows chest count is: 300."));
		assertNull(BossTripTracker.killCountBoss("Your Gnome Stronghold Agility lap count is: 5."));
		assertNull(BossTripTracker.killCountBoss("Your subdued Wintertodt count is: 40."));
		assertNull(BossTripTracker.killCountBoss("Fight duration: 1:23"));
	}
}
