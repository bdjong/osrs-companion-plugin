package com.osrscompanion.model;

import lombok.Getter;
import com.google.gson.annotations.SerializedName;
import java.util.*;

/**
 * The active farm route as returned by GET /api/runelite/farm-route/: the stops in run
 * order, each with the way there and the patches to farm, plus the loadout.
 */
public class FarmRoute {
	@Getter
	@SerializedName("id")
	private int id;

	@SerializedName("name")
	private String name;

	@Getter
	@SerializedName("summary")
	private Summary summary;

	@SerializedName("stops")
	private List<Stop> stops;

	@SerializedName("inventory")
	private List<Stack> inventory;

	@SerializedName("equipment")
	private List<Stack> equipment;

	@SerializedName("runes")
	private List<String> runes;

	/** The spellbook to be on ("Normal") when the route casts teleports, else null. */
	@Getter
	@SerializedName("spellbook")
	private String spellbook;

	/** How many things changed since the route was saved (new unlocks etc.); 0 = nothing to review. */
	@Getter
	@SerializedName("review_changes")
	private int reviewChanges;

	/** The run is ready (website lib/timers.ts routeReady): no crop of it still growing or diseased, at least one ready; compost bins, empty, dead and never-checked patches do not count. */
	@Getter
	@SerializedName("ready")
	private boolean ready;

	public String getName() {
		return name == null ? "Farm run" : name;
	}

	public List<Stop> getStops() {
		return stops == null ? Collections.emptyList() : stops;
	}

	public List<Stack> getInventory() {
		return inventory == null ? Collections.emptyList() : inventory;
	}

	public List<Stack> getEquipment() {
		return equipment == null ? Collections.emptyList() : equipment;
	}

	public List<String> getRunes() {
		return runes == null ? Collections.emptyList() : runes;
	}

	/**
	 * The item that pictures this route in the side panel: the patch type the route visits most
	 * (12 tree patches and 10 herbs: trees), shown as its most planted crop. Trees show their
	 * sapling (magic, papaya...), other crops what they give (a ranarr weed, a cactus spine), else
	 * the seed. Compost bins don't count. Null when no patch has a crop.
	 */
	public Integer pictureItem() {
		Map<String, Integer> perType = new LinkedHashMap<>();
		for (Stop stop : getStops()) {
			for (Patch p : stop.getPatches()) {
				if (p.getImpl() != null && !p.getImpl().contains("COMPOST") && p.getSeed() != null) {
					perType.merge(p.getImpl(), 1, Integer::sum);
				}
			}
		}
		String type = mostCommon(perType);
		if (type == null) {
			return null;
		}
		boolean tree = isTree(type);
		Map<Integer, Integer> perItem = new LinkedHashMap<>();
		for (Stop stop : getStops()) {
			for (Patch p : stop.getPatches()) {
				if (type.equals(p.getImpl()) && p.getSeed() != null) {
					perItem.merge(!tree && p.getProduce() != null ? p.getProduce() : p.getSeed(), 1, Integer::sum);
				}
			}
		}
		return mostCommon(perItem);
	}

	/** The key with the highest count; the first one seen on a tie. */
	private static <K> K mostCommon(Map<K, Integer> counts) {
		K best = null;
		for (Map.Entry<K, Integer> e : counts.entrySet()) {
			if (best == null || e.getValue() > counts.get(best)) {
				best = e.getKey();
			}
		}
		return best;
	}

	/** Patch types where a sapling is planted: the sapling pictures the tree best. */
	static boolean isTree(String impl) {
		return impl.contains("TREE") || impl.equals("REDWOOD") || impl.equals("CALQUAT") || impl.equals("CELASTRUS");
	}

	public static class Summary {
		@Getter
		@SerializedName("patches")
		private int patches;

		@Getter
		@SerializedName("xp")
		private int xp;

		@Getter
		@SerializedName("activeSec")
		private int activeSec;

		/** Estimated profit of every patch (GE prices). */
		@Getter
		@SerializedName("gp")
		private int gp;

	}

	public static class Stop {
		@Getter
		@SerializedName("key")
		private String key;

		@SerializedName("name")
		private String name;

		@Getter
		@SerializedName("x")
		private int x;

		@Getter
		@SerializedName("y")
		private int y;

		@Getter
		@SerializedName("plane")
		private int plane;

		@SerializedName("travel")
		private Travel travel;

		@Getter
		@SerializedName("note")
		private String note;

		@SerializedName("patches")
		private List<Patch> patches;

		public String getName() {
			return name == null ? "" : name;
		}

		public Travel getTravel() {
			return travel == null ? new Travel() : travel;
		}

		public List<Patch> getPatches() {
			return patches == null ? Collections.emptyList() : patches;
		}
	}

	public static class Travel {
		@SerializedName("name")
		private String name;

		@SerializedName("how")
		private String how;

		@SerializedName("items")
		private List<Integer> items;

		@Getter
		@SerializedName("spell")
		private boolean spell;

		@Getter
		@SerializedName("walk")
		private boolean walk;

		/** A minigame (Grouping) teleport: they share a 20-minute cooldown. */
		@Getter
		@SerializedName("minigame")
		private boolean minigame;

		/** For a minigame teleport: the way there while it is on cooldown; null when there is none. */
		@Getter
		@SerializedName("fallback")
		private Travel fallback;

		/** Tiles on the way, in order, each x, y, plane (a boat, a cave entrance): the arrow until the stop is in view. */
		@SerializedName("way")
		private List<List<Integer>> way;

		public String getName() {
			return name == null ? "Walk there" : name;
		}

		public String getHow() {
			return how == null ? "" : how;
		}

		/** The way's tiles (malformed ones left out), in order. */
		public List<int[]> getWay() {
			List<int[]> out = new ArrayList<>();
			for (List<Integer> w : way == null ? Collections.<List<Integer>>emptyList() : way) {
				if (w != null && w.size() >= 3 && !w.contains(null)) {
					out.add(new int[]{w.get(0), w.get(1), w.get(2)});
				}
			}
			return out;
		}

		public List<Integer> getItems() {
			return items == null ? Collections.emptyList() : items;
		}

	}

	public static class Patch {
		@Getter
		@SerializedName("key")
		private String key;

		@SerializedName("type")
		private String type;

		@SerializedName("name")
		private String name;

		@Getter
		@SerializedName("region")
		private String region;

		@Getter
		@SerializedName("varbit")
		private int varbit;

		@Getter
		@SerializedName("impl")
		private String impl;

		@Getter
		@SerializedName("crop")
		private String crop;

		@Getter
		@SerializedName("seed")
		private Integer seed;

		@Getter
		@SerializedName("seed_name")
		private String seedName;

		@Getter
		@SerializedName("seed_qty")
		private int seedQty;

		@SerializedName("protect")
		private List<Stack> protect;

		@SerializedName("ready")
		private String ready;

		@Getter
		@SerializedName("note")
		private String note;

		/** Whether compost goes on it (null from an older website: every patch but compost bins). */
		@SerializedName("compost")
		private Boolean compost;

		/** Estimated profit of the crop (GE prices). */
		@Getter
		@SerializedName("gp")
		private int gp;

		/** What the crop gives (the herb, the fruit...); null for trees and from an older website. */
		@Getter
		@SerializedName("produce")
		private Integer produce;

		public String getType() {
			return type == null ? "Patch" : type;
		}

		public String getName() {
			return name == null ? "" : name;
		}

		public List<Stack> getProtect() {
			return protect == null ? Collections.emptyList() : protect;
		}

		/** "ready", "growing", "diseased", "dead", "empty" or "unknown" when the route was loaded. */
		public String getReady() {
			return ready == null ? "unknown" : ready;
		}

		public boolean takesCompost() {
			return compost != null ? compost : impl == null || !impl.contains("COMPOST");
		}

	}

	public static class Stack {
		@Getter
		@SerializedName("item")
		private int item;

		@Getter
		@SerializedName("qty")
		private int qty = 1;

		@SerializedName("name")
		private String name;

		@Getter
		@SerializedName("why")
		private String why;

		@Getter
		@SerializedName("slot")
		private String slot;

		/** Quantity per storage it was in at the last sync: bank, seed_vault, seed_box, ... */
		@SerializedName("where")
		private Map<String, Integer> where;

		public String getName() {
			return name == null ? "Item" : name;
		}

		public Map<String, Integer> getWhere() {
			return where == null ? Collections.emptyMap() : where;
		}

		/** The same entry with another quantity (the plugin's live compost count). */
		public Stack withQty(int qty) {
			Stack s = new Stack();
			s.item = item;
			s.qty = qty;
			s.name = name;
			s.why = why;
			s.slot = slot;
			s.where = where;
			return s;
		}

		/** An item the plugin adds itself (from a setting), not from the website's route. */
		public static Stack extra(int item, String name, String why) {
			Stack s = new Stack();
			s.item = item;
			s.name = name;
			s.why = why;
			return s;
		}
	}

	public static class RouteSummary {
		@Getter
		@SerializedName("id")
		private int id;

		@SerializedName("name")
		private String name;

		@Getter
		@SerializedName("active")
		private boolean active;

		public String getName() {
			return name == null ? "Route" : name;
		}

		@Override
		public String toString() {
			return getName();
		}
	}

	/** GET /api/runelite/farm-route/ and the activate call: the active route (or null) and every route. */
	public static class Response {
		@Getter
		@SerializedName("route")
		private FarmRoute route;

		@SerializedName("routes")
		private List<RouteSummary> routes;

		public List<RouteSummary> getRoutes() {
			return routes == null ? Collections.emptyList() : routes;
		}
	}
}
