package com.osrscompanion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The maths behind the boss trip log and the supplies check, free of the game client so it is unit
 * tested. Supplies are counted in units per key: an item whose name ends in a dose ("Prayer
 * potion(3)") counts its doses under the name without the dose, so a (4) and a (3) add up to 7
 * doses; any other item counts one per item under its base id (its variants together).
 */
final class TripSupplies {
	private static final Pattern DOSED = Pattern.compile("^(.+?)\\s?\\((\\d)\\)$");

	/** Kill count messages name some bosses differently from the Boss Gear page: both map to one key. */
	private static final Map<String, String> BOSS_ALIASES = new HashMap<>();
	/** Raids and their modes ("Theatre of Blood: Hard Mode") count as the raid. */
	private static final String[] PREFIX_BOSSES = {"chambersofxeric", "theatreofblood", "tombsofamascut"};

	static {
		BOSS_ALIASES.put("dagannothrex", "dagannothkings");
		BOSS_ALIASES.put("dagannothprime", "dagannothkings");
		BOSS_ALIASES.put("dagannothsupreme", "dagannothkings");
		BOSS_ALIASES.put("barrowschest", "barrows");
		BOSS_ALIASES.put("lunarchest", "moonsofperil");
		BOSS_ALIASES.put("tztokjad", "tzhaarfightcave");
		BOSS_ALIASES.put("tzkalzuk", "inferno");
		BOSS_ALIASES.put("solheredit", "fortiscolosseum");
		BOSS_ALIASES.put("royaltitan", "royaltitans");
	}

	private TripSupplies() {
	}

	/** A name with a dose: the name without it (lower case) and the dose. */
	static final class Dosed {
		final String stem;
		final int dose;

		Dosed(String stem, int dose) {
			this.stem = stem;
			this.dose = dose;
		}
	}

	/** "Prayer potion(3)" gives prayer potion, 3; null for a name without a dose (or a dose of 0). */
	static Dosed dosed(String name) {
		if (name == null) {
			return null;
		}
		Matcher m = DOSED.matcher(name.trim());
		if (!m.matches()) {
			return null;
		}
		int dose = m.group(2).charAt(0) - '0';
		return dose <= 0 ? null : new Dosed(m.group(1).trim().toLowerCase(Locale.ROOT), dose);
	}

	/** The supply key of an item: its dose-less name for dosed items, else its base id. */
	static String key(int baseId, String name) {
		Dosed d = dosed(name);
		return d != null ? "d:" + d.stem : "i:" + baseId;
	}

	/** How many units a stack is: doses for dosed items, else the quantity. */
	static long units(String name, long quantity) {
		Dosed d = dosed(name);
		return d == null ? quantity : quantity * d.dose;
	}

	/**
	 * Adds what went down between two counts to {@code used}. Only keys in {@code brought} count (what
	 * you left the bank with): loot picked up and dropped or bagged again is not a supply. Increases
	 * (loot, empty vials, a switch taken off) are ignored.
	 */
	static void addUsed(Map<String, Long> before, Map<String, Long> after, Set<String> brought, Map<String, Long> used) {
		for (Map.Entry<String, Long> e : before.entrySet()) {
			if (!brought.contains(e.getKey())) {
				continue;
			}
			long down = e.getValue() - after.getOrDefault(e.getKey(), 0L);
			if (down > 0) {
				used.merge(e.getKey(), down, Long::sum);
			}
		}
	}

	/** Units used per trip on average over the given trips (every trip counts, also those where a key was not used). */
	static Map<String, Double> averagePerTrip(Collection<Map<String, Long>> trips) {
		Map<String, Double> out = new LinkedHashMap<>();
		if (trips.isEmpty()) {
			return out;
		}
		for (Map<String, Long> trip : trips) {
			for (Map.Entry<String, Long> e : trip.entrySet()) {
				out.merge(e.getKey(), e.getValue().doubleValue(), Double::sum);
			}
		}
		out.replaceAll((k, v) -> v / trips.size());
		out.values().removeIf(v -> v <= 0);
		return out;
	}

	/** One needed supply: how many units a trip takes, how many you have and how many trips that is. */
	static final class Need {
		final String key;
		final double perTrip;
		final long have;
		final int trips;

		Need(String key, double perTrip, long have) {
			this.key = key;
			this.perTrip = perTrip;
			this.have = have;
			this.trips = perTrip <= 0 ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, Math.floor(have / perTrip + 1e-9));
		}
	}

	/** How many trips your supplies last: the fewest any needed supply allows, and that supply (the first of it). */
	static final class Check {
		final int trips;
		final List<Need> needs;

		Check(int trips, List<Need> needs) {
			this.trips = trips;
			this.needs = needs;
		}

		/** The supply that runs out first, or null when nothing is needed. */
		Need limiting() {
			return needs.isEmpty() ? null : needs.get(0);
		}
	}

	/** How many trips {@code have} supports with {@code perTrip} needed each trip; the needs come fewest trips first. */
	static Check tripsSupported(Map<String, Double> perTrip, Map<String, Long> have) {
		List<Need> needs = new ArrayList<>();
		for (Map.Entry<String, Double> e : perTrip.entrySet()) {
			if (e.getValue() != null && e.getValue() > 0) {
				needs.add(new Need(e.getKey(), e.getValue(), have.getOrDefault(e.getKey(), 0L)));
			}
		}
		needs.sort(Comparator.comparingInt((Need n) -> n.trips).thenComparing(n -> n.key));
		return new Check(needs.isEmpty() ? 0 : needs.get(0).trips, needs);
	}

	/** A boss name as one comparable key: lower case letters and digits, no leading "the", aliases applied. */
	static String bossKey(String name) {
		if (name == null) {
			return "";
		}
		String n = name.toLowerCase(Locale.ROOT).trim();
		if (n.startsWith("the ")) {
			n = n.substring(4);
		}
		n = n.replaceAll("[^a-z0-9]", "");
		for (String raid : PREFIX_BOSSES) {
			if (n.startsWith(raid)) {
				return raid;
			}
		}
		return BOSS_ALIASES.getOrDefault(n, n);
	}
}
