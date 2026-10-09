package com.osrscompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Slayer tasks that can also be done on a boss variant, and the boss NPCs that count for them (OSRS
 * Wiki, each task's "boss variants count towards the task"). A boss kill counts as one task kill but
 * takes far longer, so a session records how many of its kills were the boss (see {@link SlayerSession}).
 * The website mirrors this list in src/lib/slayerBosses.ts. No game access.
 *
 * <p>Not here: the slayer "Boss" task itself (its boss is the task), and variants that are no boss
 * (basilisk knights, demonic gorillas, superiors, lizardman shamans).
 */
final class SlayerBosses {
	private SlayerBosses() {
	}

	/** Task name (lower case, as the game names the task) -> the boss NPC names that count for it. */
	static final Map<String, List<String>> BOSSES;

	static {
		Map<String, List<String>> m = new LinkedHashMap<>();
		m.put("abyssal demons", Collections.singletonList("Abyssal Sire"));
		m.put("araxytes", Collections.singletonList("Araxxor"));
		m.put("hydras", Collections.singletonList("Alchemical Hydra"));
		m.put("cave kraken", Collections.singletonList("Kraken"));
		m.put("smoke devils", Collections.singletonList("Thermonuclear smoke devil"));
		m.put("hellhounds", Collections.singletonList("Cerberus"));
		// The Grotesque Guardians.
		m.put("gargoyles", Arrays.asList("Dusk", "Dawn"));
		// The Dagannoth Kings.
		m.put("dagannoth", Arrays.asList("Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme"));
		m.put("kalphite", Collections.singletonList("Kalphite Queen"));
		m.put("black dragons", Collections.singletonList("King Black Dragon"));
		m.put("blue dragons", Collections.singletonList("Vorkath"));
		// Skotizo counts for both black and greater demons (OSRS Wiki, Skotizo).
		m.put("greater demons", Arrays.asList("K'ril Tsutsaroth", "Tstanon Karlak", "Skotizo"));
		m.put("black demons", Collections.singletonList("Skotizo"));
		m.put("skeletons", Arrays.asList("Vet'ion", "Calvar'ion"));
		// The Royal Titans: Branda for fire giants, Eldric for ice giants.
		m.put("fire giants", Collections.singletonList("Branda the Fire Queen"));
		m.put("ice giants", Collections.singletonList("Eldric the Ice King"));
		m.put("aviansie", Collections.singletonList("Kree'arra"));
		m.put("spiders", Arrays.asList("Sarachnis", "Venenatis", "Spindel"));
		m.put("bears", Arrays.asList("Callisto", "Artio"));
		m.put("scorpions", Collections.singletonList("Scorpia"));
		m.put("rats", Collections.singletonList("Scurrius"));
		m.put("hill giants", Collections.singletonList("Obor"));
		m.put("moss giants", Collections.singletonList("Bryophyta"));
		m.put("gryphons", Collections.singletonList("Shellbane Gryphon"));
		BOSSES = Collections.unmodifiableMap(m);
	}

	/** The boss NPCs that count for a task ("Hydras" -> Alchemical Hydra); empty when it has none. */
	static List<String> forTask(String taskName) {
		String task = clean(taskName);
		if (task.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> exact = BOSSES.get(task);
		if (exact != null) {
			return exact;
		}
		// "Hydra", "Cave krakens", "Kalphites": the same task by its singular.
		String one = SlayerMonsters.singular(task);
		for (Map.Entry<String, List<String>> e : BOSSES.entrySet()) {
			if (SlayerMonsters.singular(e.getKey()).equals(one)) {
				return e.getValue();
			}
		}
		return Collections.emptyList();
	}

	/** Whether this NPC is a boss variant of the task (any case, tags ignored). */
	static boolean isBoss(String taskName, String npcName) {
		String npc = clean(npcName);
		if (npc.isEmpty()) {
			return false;
		}
		for (String b : forTask(taskName)) {
			if (npc.equals(clean(b))) {
				return true;
			}
		}
		return false;
	}

	/** "Alchemical Hydra" -> "boss-alchemical-hydra": the key of the area round a boss (a session's location). */
	static String areaKey(String npcName) {
		String slug = clean(npcName).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		return "boss-" + (slug.isEmpty() ? "unknown" : slug);
	}

	private static String clean(String s) {
		return s == null ? "" : s.replaceAll("<[^>]*>", "").replace(' ', ' ').trim().toLowerCase();
	}
}
