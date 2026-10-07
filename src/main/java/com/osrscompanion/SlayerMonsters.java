package com.osrscompanion;

import java.util.List;

/** Whether an NPC counts as the slayer task's monster. No game access. */
final class SlayerMonsters {
	private SlayerMonsters() {
	}

	/**
	 * True when the NPC's name is one of the location's monsters (any case), or, when the location
	 * names none, loosely matches the task: "Abyssal demon" for "Abyssal demons", "Kalphite worker"
	 * for "Kalphite", "Greater demon" for "Greater demons".
	 */
	static boolean matches(String npcName, List<String> monsters, String taskName) {
		String npc = clean(npcName);
		if (npc.isEmpty()) {
			return false;
		}
		if (monsters != null && !monsters.isEmpty()) {
			for (String m : monsters) {
				if (npc.equals(clean(m))) {
					return true;
				}
			}
			return false;
		}
		String task = clean(taskName);
		if (task.isEmpty()) {
			return false;
		}
		// "Zombies" is "zombie" + s, "Wolves" is "wolf": try the plain cut and the English singular.
		String[] tasks = {task, task.endsWith("s") ? task.substring(0, task.length() - 1) : task, singular(task)};
		String[] npcs = {npc, singular(npc)};
		for (String n : npcs) {
			for (String t : tasks) {
				if (n.equals(t) || n.startsWith(t + " ") || n.endsWith(" " + t)) {
					return true;
				}
			}
		}
		return false;
	}

	private static String clean(String s) {
		return s == null ? "" : s.replaceAll("<[^>]*>", "").replace(' ', ' ').trim().toLowerCase();
	}

	/** "abyssal demons" -> "abyssal demon", "dagannoth" stays, "wolves" -> "wolf", "harpie bug swarms" -> "harpie bug swarm". Lower case in. */
	static String singular(String s) {
		if (s.endsWith("ves")) {
			return s.substring(0, s.length() - 3) + "f";
		}
		if (s.endsWith("ies") && s.length() > 4) {
			return s.substring(0, s.length() - 3) + "y";
		}
		if (s.endsWith("ches") || s.endsWith("shes") || s.endsWith("xes") || s.endsWith("sses")) {
			return s.substring(0, s.length() - 2);
		}
		if (s.endsWith("s") && !s.endsWith("ss") && s.length() > 3) {
			return s.substring(0, s.length() - 1);
		}
		return s;
	}
}
