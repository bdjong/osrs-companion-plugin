package com.osrscompanion;

import java.util.*;

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
	static final Map<String, List<String>> BOSSES = new LinkedHashMap<>();

	static {
		Tables.map("slayerBosses").forEach((task, names) -> BOSSES.put(task, Tables.strings(names)));
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
