package com.osrscompanion.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The active farm route as returned by GET /api/runelite/farm-route/: the stops in run
 * order, each with the way there and the patches to farm, plus the loadout.
 */
public class FarmRoute {
	@SerializedName("id")
	private int id;

	@SerializedName("name")
	private String name;

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
	@SerializedName("spellbook")
	private String spellbook;

	/** How many things changed since the route was saved (new unlocks etc.); 0 = nothing to review. */
	@SerializedName("review_changes")
	private int reviewChanges;

	/** The run is ready (website farmRoute.ts routeReady): no crop of it still growing or diseased, at least one ready; compost bins, empty, dead and never-checked patches do not count. */
	@SerializedName("ready")
	private boolean ready;

	public int getId() {
		return id;
	}

	public String getName() {
		return name == null ? "Farm run" : name;
	}

	public Summary getSummary() {
		return summary;
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

	public String getSpellbook() {
		return spellbook;
	}

	public int getReviewChanges() {
		return reviewChanges;
	}

	public boolean isReady() {
		return ready;
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
		@SerializedName("patches")
		private int patches;

		@SerializedName("xp")
		private int xp;

		@SerializedName("activeSec")
		private int activeSec;

		@SerializedName("gp")
		private int gp;

		public int getPatches() {
			return patches;
		}

		public int getXp() {
			return xp;
		}

		public int getActiveSec() {
			return activeSec;
		}

		/** Estimated profit of every patch (GE prices). */
		public int getGp() {
			return gp;
		}
	}

	public static class Stop {
		@SerializedName("key")
		private String key;

		@SerializedName("name")
		private String name;

		@SerializedName("x")
		private int x;

		@SerializedName("y")
		private int y;

		@SerializedName("plane")
		private int plane;

		@SerializedName("travel")
		private Travel travel;

		@SerializedName("note")
		private String note;

		@SerializedName("patches")
		private List<Patch> patches;

		public String getKey() {
			return key;
		}

		public String getName() {
			return name == null ? "" : name;
		}

		public int getX() {
			return x;
		}

		public int getY() {
			return y;
		}

		public int getPlane() {
			return plane;
		}

		public Travel getTravel() {
			return travel == null ? new Travel() : travel;
		}

		public String getNote() {
			return note;
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

		@SerializedName("spell")
		private boolean spell;

		@SerializedName("walk")
		private boolean walk;

		/** A minigame (Grouping) teleport: they share a 20-minute cooldown. */
		@SerializedName("minigame")
		private boolean minigame;

		/** For a minigame teleport: the way there while it is on cooldown; null when there is none. */
		@SerializedName("fallback")
		private Travel fallback;

		public String getName() {
			return name == null ? "Walk there" : name;
		}

		public String getHow() {
			return how == null ? "" : how;
		}

		public List<Integer> getItems() {
			return items == null ? Collections.emptyList() : items;
		}

		public boolean isSpell() {
			return spell;
		}

		public boolean isWalk() {
			return walk;
		}

		public boolean isMinigame() {
			return minigame;
		}

		public Travel getFallback() {
			return fallback;
		}
	}

	public static class Patch {
		@SerializedName("key")
		private String key;

		@SerializedName("type")
		private String type;

		@SerializedName("name")
		private String name;

		@SerializedName("region")
		private String region;

		@SerializedName("varbit")
		private int varbit;

		@SerializedName("impl")
		private String impl;

		@SerializedName("crop")
		private String crop;

		@SerializedName("seed")
		private Integer seed;

		@SerializedName("seed_name")
		private String seedName;

		@SerializedName("seed_qty")
		private int seedQty;

		@SerializedName("protect")
		private List<Stack> protect;

		@SerializedName("ready")
		private String ready;

		@SerializedName("note")
		private String note;

		/** Whether compost goes on it (null from an older website: every patch but compost bins). */
		@SerializedName("compost")
		private Boolean compost;

		/** Estimated profit of the crop (GE prices). */
		@SerializedName("gp")
		private int gp;

		/** What the crop gives (the herb, the fruit...); null for trees and from an older website. */
		@SerializedName("produce")
		private Integer produce;

		public String getKey() {
			return key;
		}

		public String getType() {
			return type == null ? "Patch" : type;
		}

		public String getName() {
			return name == null ? "" : name;
		}

		public String getRegion() {
			return region;
		}

		public int getVarbit() {
			return varbit;
		}

		public String getImpl() {
			return impl;
		}

		public String getCrop() {
			return crop;
		}

		public Integer getSeed() {
			return seed;
		}

		public String getSeedName() {
			return seedName;
		}

		public int getSeedQty() {
			return seedQty;
		}

		public List<Stack> getProtect() {
			return protect == null ? Collections.emptyList() : protect;
		}

		/** "ready", "growing", "diseased", "dead", "empty" or "unknown" when the route was loaded. */
		public String getReady() {
			return ready == null ? "unknown" : ready;
		}

		public String getNote() {
			return note;
		}

		public boolean takesCompost() {
			return compost != null ? compost : impl == null || !impl.contains("COMPOST");
		}

		public int getGp() {
			return gp;
		}

		public Integer getProduce() {
			return produce;
		}
	}

	public static class Stack {
		@SerializedName("item")
		private int item;

		@SerializedName("qty")
		private int qty = 1;

		@SerializedName("name")
		private String name;

		@SerializedName("why")
		private String why;

		@SerializedName("slot")
		private String slot;

		/** Quantity per storage it was in at the last sync: bank, seed_vault, seed_box, ... */
		@SerializedName("where")
		private Map<String, Integer> where;

		public int getItem() {
			return item;
		}

		public int getQty() {
			return qty;
		}

		public String getName() {
			return name == null ? "Item" : name;
		}

		public String getWhy() {
			return why;
		}

		public String getSlot() {
			return slot;
		}

		public Map<String, Integer> getWhere() {
			return where == null ? Collections.emptyMap() : where;
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
		@SerializedName("id")
		private int id;

		@SerializedName("name")
		private String name;

		@SerializedName("active")
		private boolean active;

		public int getId() {
			return id;
		}

		public String getName() {
			return name == null ? "Route" : name;
		}

		public boolean isActive() {
			return active;
		}

		@Override
		public String toString() {
			return getName();
		}
	}

	/** GET /api/runelite/farm-route/ and the activate call: the active route (or null) and every route. */
	public static class Response {
		@SerializedName("route")
		private FarmRoute route;

		@SerializedName("routes")
		private List<RouteSummary> routes;

		public FarmRoute getRoute() {
			return route;
		}

		public List<RouteSummary> getRoutes() {
			return routes == null ? Collections.emptyList() : routes;
		}
	}
}
