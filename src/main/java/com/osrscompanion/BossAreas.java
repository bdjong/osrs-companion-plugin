package com.osrscompanion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The boss lairs the boss history ({@link BossHistory}) knows: per boss its map regions (the template
 * region inside an instance, as {@code WorldPoint.fromLocalInstance} gives it), the names it goes by (NPCs,
 * the kill count message and the loot tracker's source) and whether the boss NPC's death can stand in for a
 * kill count message.
 *
 * <p>Regions come from RuneLite's Discord plugin area list and the OSRS Wiki's map coordinates. A boss
 * without a region here (or a lair at another region) is learned in the game: a kill count message of the
 * boss marks the region you stand in as its lair ({@link BossHistory} keeps those per profile). Keys are the
 * website's Boss Gear keys (bossGear.json) where it has the boss. No game access.
 *
 * <p>The website holds the same list (src/lib/bossAreas.ts, kept in step by a test there) with the admins'
 * fixes on top, and serves it (GET /api/runelite/boss-areas, {@link BossAreasSync}). Once it has arrived,
 * {@link #setServer} makes its regions the lairs of the bosses it lists; a boss it does not list keeps the
 * regions built in here. Learned lairs count on top of either.
 */
final class BossAreas {
	private BossAreas() {
	}

	enum Kind {
		BOSS("boss"), RAID("raid");

		final String wire;

		Kind(String wire) {
			this.wire = wire;
		}
	}

	/** One boss (or a group fought together: the Dagannoth Kings, a raid). */
	static final class Boss {
		final String key;
		final String name;
		final Kind kind;
		final Set<Integer> regions;
		/** {@link #norm} of every name it goes by. */
		final Set<String> names;
		/**
		 * One NPC, one death per kill (no phases that "die", no second boss), so a death you fought that no
		 * kill count message follows still counts as a kill.
		 */
		final boolean deathIsKill;

		Boss(String key, String name, Kind kind, int[] regions, boolean deathIsKill, String... names) {
			this.key = key;
			this.name = name;
			this.kind = kind;
			Set<Integer> r = new HashSet<>();
			for (int id : regions) {
				r.add(id);
			}
			this.regions = Collections.unmodifiableSet(r);
			Set<String> n = new HashSet<>();
			n.add(norm(name));
			for (String s : names) {
				n.add(norm(s));
			}
			this.names = Collections.unmodifiableSet(n);
			this.deathIsKill = deathIsKill;
		}

		boolean hasName(String name) {
			String n = norm(name);
			return !n.isEmpty() && names.contains(n);
		}

		@Override
		public String toString() {
			return key;
		}
	}

	/** The website's lairs (boss key -> regions) and the region index over them; null: the built-in ones. */
	private static final class Server {
		final String version;
		final Map<String, Set<Integer>> regions;
		final Map<Integer, List<Boss>> byRegion;

		Server(String version, Map<String, Set<Integer>> regions, Map<Integer, List<Boss>> byRegion) {
			this.version = version;
			this.regions = regions;
			this.byRegion = byRegion;
		}
	}

	private static volatile Server server;

	private static final Map<String, Boss> BY_KEY = new LinkedHashMap<>();
	private static final Map<Integer, List<Boss>> BY_REGION = new HashMap<>();
	private static final Map<String, Boss> BY_NAME = new HashMap<>();

	private static int[] r(int... ids) {
		return ids;
	}

	private static void boss(String key, String name, int[] regions, boolean deathIsKill, String... names) {
		add(new Boss(key, name, Kind.BOSS, regions, deathIsKill, names));
	}

	private static void raid(String key, String name, int[] regions, String... names) {
		add(new Boss(key, name, Kind.RAID, regions, false, names));
	}

	private static void add(Boss b) {
		BY_KEY.put(b.key, b);
		for (int region : b.regions) {
			BY_REGION.computeIfAbsent(region, k -> new ArrayList<>()).add(b);
		}
		for (String n : b.names) {
			BY_NAME.putIfAbsent(n, b);
		}
	}

	static {
		// God Wars Dungeon: each general's room.
		boss("general-graardor", "General Graardor", r(11347), true);
		boss("kreearra", "Kree'arra", r(11346), true);
		boss("commander-zilyana", "Commander Zilyana", r(11602), true);
		boss("kril-tsutsaroth", "K'ril Tsutsaroth", r(11603), true);
		boss("nex", "Nex", r(11601), false);
		boss("vorkath", "Vorkath", r(9023), true);
		boss("zulrah", "Zulrah", r(9007), false);
		boss("dagannoth-kings", "Dagannoth Kings", r(11588, 11589), true, "Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme");
		boss("kalphite-queen", "Kalphite Queen", r(13972), false);
		boss("king-black-dragon", "King Black Dragon", r(9033), true);
		boss("giant-mole", "Giant Mole", r(6993, 6992), true);
		boss("sarachnis", "Sarachnis", r(7322), true);
		boss("skotizo", "Skotizo", r(9048), true);
		boss("corporeal-beast", "Corporeal Beast", r(11842, 11844), true);
		boss("the-nightmare", "The Nightmare", r(15515), false, "Nightmare", "The Nightmare of Ashihama");
		boss("phosanis-nightmare", "Phosani's Nightmare", r(15515), false);
		boss("phantom-muspah", "Phantom Muspah", r(11330), false);
		// Slayer bosses.
		boss("cerberus", "Cerberus", r(4883, 5140, 5395), true);
		boss("alchemical-hydra", "Alchemical Hydra", r(5536), false);
		boss("abyssal-sire", "Abyssal Sire", r(11851, 11850, 12363, 12362), false);
		boss("kraken", "Kraken", r(9116), true);
		boss("thermonuclear-smoke-devil", "Thermonuclear smoke devil", r(9363), true);
		boss("grotesque-guardians", "Grotesque Guardians", r(6727), false, "Dusk", "Dawn");
		boss("araxxor", "Araxxor", r(14489), true);
		// Desert Treasure II.
		boss("duke-sucellus", "Duke Sucellus", r(12132), false);
		boss("the-leviathan", "The Leviathan", r(8291), false);
		boss("the-whisperer", "The Whisperer", r(10595), false);
		boss("vardorvis", "Vardorvis", r(4405), false);
		// Varlamore and later.
		boss("the-hueycoatl", "The Hueycoatl", r(5939), false, "Hueycoatl");
		boss("moons-of-peril", "Moons of Peril", r(5783, 6038, 5526), false, "Blue Moon", "Eclipse Moon", "Blood Moon", "Lunar Chest");
		boss("amoxliatl", "Amoxliatl", r(5446), true);
		boss("royal-titans", "Royal Titans", r(11669), false, "Royal Titan", "Branda the Fire Queen", "Eldric the Ice King");
		boss("yama", "Yama", r(6045), false);
		boss("doom-of-mokhaiotl", "Doom of Mokhaiotl", r(), false);
		boss("scurrius", "Scurrius", r(13210), true);
		// The Wilderness (the regions of the overworld bosses are where they stand; others are learned).
		boss("callisto", "Callisto", r(13116), false);
		boss("venenatis", "Venenatis", r(13115), true);
		boss("vetion", "Vet'ion", r(12859), false, "Vet'ion Reborn");
		boss("artio", "Artio", r(12345), true);
		boss("spindel", "Spindel", r(), true);
		boss("calvarion", "Calvar'ion", r(), false);
		boss("scorpia", "Scorpia", r(12961), true);
		boss("chaos-elemental", "Chaos Elemental", r(12861), true);
		boss("chaos-fanatic", "Chaos Fanatic", r(11836), true);
		boss("crazy-archaeologist", "Crazy archaeologist", r(11833), true);
		boss("deranged-archaeologist", "Deranged archaeologist", r(14649), true);
		boss("obor", "Obor", r(), true);
		boss("bryophyta", "Bryophyta", r(), true);
		// Minigame bosses.
		boss("the-gauntlet", "The Gauntlet", r(7512), false, "Gauntlet", "Crystalline Hunllef");
		boss("corrupted-gauntlet", "Corrupted Gauntlet", r(7768), false, "The Corrupted Gauntlet", "Corrupted Hunllef");
		boss("zalcano", "Zalcano", r(12126), false);
		boss("barrows", "Barrows", r(14231), false, "Barrows chest");
		boss("tzhaar-fight-cave", "TzHaar Fight Cave", r(9551), false, "TzTok-Jad");
		boss("inferno", "Inferno", r(9043), false, "TzKal-Zuk");
		boss("fortis-colosseum", "Fortis Colosseum", r(7216), false, "Sol Heredit");
		// Raids: one raid is one kill (completion).
		raid("chambers-of-xeric", "Chambers of Xeric",
			r(12889, 13136, 13137, 13138, 13139, 13140, 13141, 13145, 13393, 13394, 13395, 13396, 13397, 13401), "Chambers of Xeric Challenge Mode");
		raid("theatre-of-blood", "Theatre of Blood", r(12611, 12612, 12613, 12867, 12869, 13122, 13123, 13125, 13379), "Theatre of Blood Hard Mode");
		raid("tombs-of-amascut", "Tombs of Amascut",
			r(14160, 14162, 14164, 14672, 14674, 14676, 15184, 15186, 15188, 15696, 15698, 15700), "Tombs of Amascut Expert Mode");
	}

	/** Every boss, in the table's order. */
	static List<Boss> all() {
		return new ArrayList<>(BY_KEY.values());
	}

	static Boss byKey(String key) {
		return key == null ? null : BY_KEY.get(key);
	}

	/**
	 * The boss of an NPC, kill count message or loot source name; null when none. Raids match by their
	 * prefix ("Theatre of Blood: Hard Mode"), as {@link TripSupplies#bossKey} does.
	 */
	static Boss byName(String name) {
		String n = norm(name);
		return n.isEmpty() ? null : BY_NAME.get(n);
	}

	/** The bosses whose lair is this region (several share one: The Nightmare and Phosani's); empty when none. */
	static List<Boss> byRegion(int region) {
		Server s = server;
		List<Boss> l = (s == null ? BY_REGION : s.byRegion).get(region);
		return l == null ? Collections.emptyList() : l;
	}

	/** The lair regions of a boss: the website's when it lists the boss, else the built-in ones. */
	static Set<Integer> regionsOf(Boss b) {
		if (b == null) {
			return Collections.emptySet();
		}
		Server s = server;
		Set<Integer> r = s == null ? null : s.regions.get(b.key);
		return r != null ? r : b.regions;
	}

	/** Whether the lairs come from the website. */
	static boolean fromServer() {
		return server != null;
	}

	/** The version of the website's list in use (null: the built-in one). */
	static String serverVersion() {
		Server s = server;
		return s == null ? null : s.version;
	}

	/**
	 * Uses the website's lairs (boss key -> regions): a boss it lists gets exactly those regions (an empty list
	 * included), the others keep their built-in ones; keys this plugin does not know are skipped. Null or empty:
	 * back to the built-in list. Thread safe (one snapshot swapped in).
	 */
	static void setServer(String version, Map<String, ? extends java.util.Collection<Integer>> lairs) {
		if (lairs == null || lairs.isEmpty()) {
			server = null;
			return;
		}
		Map<String, Set<Integer>> regions = new HashMap<>();
		for (Map.Entry<String, ? extends java.util.Collection<Integer>> e : lairs.entrySet()) {
			if (e.getKey() != null && BY_KEY.containsKey(e.getKey()) && e.getValue() != null) {
				Set<Integer> set = new HashSet<>();
				for (Integer id : e.getValue()) {
					if (id != null && id >= 0) {
						set.add(id);
					}
				}
				regions.put(e.getKey(), Collections.unmodifiableSet(set));
			}
		}
		if (regions.isEmpty()) {
			server = null;
			return;
		}
		// The index in the table's order, so a shared lair picks the same boss as the built-in one.
		Map<Integer, List<Boss>> byRegion = new HashMap<>();
		for (Boss b : BY_KEY.values()) {
			Set<Integer> r = regions.get(b.key);
			for (int id : r != null ? r : b.regions) {
				byRegion.computeIfAbsent(id, k -> new ArrayList<>()).add(b);
			}
		}
		server = new Server(version, Collections.unmodifiableMap(regions), byRegion);
	}

	/**
	 * The boss whose lair this region is: {@code prefer} when it is one of them (the open session's boss),
	 * else the first; a learned lair ({@code learned}: boss key -> regions) counts too. Null outside every lair.
	 */
	static Boss lairAt(int region, Boss prefer, Map<String, ? extends java.util.Collection<Integer>> learned) {
		if (prefer != null && (regionsOf(prefer).contains(region) || learnedHas(learned, prefer.key, region))) {
			return prefer;
		}
		List<Boss> l = byRegion(region);
		if (!l.isEmpty()) {
			return l.get(0);
		}
		if (learned != null) {
			for (Map.Entry<String, ? extends java.util.Collection<Integer>> e : learned.entrySet()) {
				if (e.getValue() != null && e.getValue().contains(region)) {
					Boss b = byKey(e.getKey());
					if (b != null) {
						return b;
					}
				}
			}
		}
		return null;
	}

	/** Whether this region is a lair of this boss (its own or learned). */
	static boolean isLair(Boss b, int region, Map<String, ? extends java.util.Collection<Integer>> learned) {
		return b != null && (regionsOf(b).contains(region) || learnedHas(learned, b.key, region));
	}

	/** Whether this region is one of the boss's known lair regions (the website's or built in; not learned). */
	static boolean isKnownLair(Boss b, int region) {
		return b != null && regionsOf(b).contains(region);
	}

	static boolean learnedHas(Map<String, ? extends java.util.Collection<Integer>> learned, String key, int region) {
		if (learned == null) {
			return false;
		}
		java.util.Collection<Integer> l = learned.get(key);
		return l != null && l.contains(region);
	}

	/** A name as one comparable key: {@link TripSupplies#bossKey} (lower case letters and digits, raids by prefix). */
	static String norm(String name) {
		return TripSupplies.bossKey(name == null ? null : name.replaceAll("<[^>]*>", ""));
	}

	/** For tests and the docs: every key. */
	static List<String> keys() {
		return Arrays.asList(BY_KEY.keySet().toArray(new String[0]));
	}
}
