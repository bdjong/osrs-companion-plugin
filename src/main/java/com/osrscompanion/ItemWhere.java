package com.osrscompanion;

/**
 * Where an item is, as the website names its sources: "teammate:Bob:worn" (a GIM teammate's worn gear),
 * "teammate:Bob:bank", "group_storage"... Turns a source key into "worn by Bob", "in Bob's inventory",
 * "in Bob's bank" or "in group storage". Pure.
 */
final class ItemWhere {
	static final String TEAMMATE_PREFIX = "teammate:";

	private ItemWhere() {
	}

	/** Whether the key is one of the GIM group's (a teammate's items or the group storage). */
	static boolean isGroup(String key) {
		return key != null && (key.startsWith(TEAMMATE_PREFIX) || "group_storage".equals(key));
	}

	/** The label of a group source key, or null for any other key. */
	static String groupLabel(String key) {
		if (key == null) {
			return null;
		}
		if ("group_storage".equals(key)) {
			return "in group storage";
		}
		if (!key.startsWith(TEAMMATE_PREFIX)) {
			return null;
		}
		String rest = key.substring(TEAMMATE_PREFIX.length());
		int colon = rest.lastIndexOf(':');
		String name = colon < 0 ? rest : rest.substring(0, colon);
		String source = colon < 0 ? "bank" : rest.substring(colon + 1);
		switch (source) {
			case "worn":
				return "worn by " + name;
			case "carried":
				return "carried by " + name;
			case "inventory":
				return "in " + name + "'s inventory";
			case "bank":
			case "bank_deposits":
				return "in " + name + "'s bank";
			default:
				return "in " + name + "'s " + source.replace('_', ' ');
		}
	}
}
