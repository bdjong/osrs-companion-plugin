package com.osrscompanion.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
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

	public static class Summary {
		@SerializedName("patches")
		private int patches;

		@SerializedName("xp")
		private int xp;

		@SerializedName("activeSec")
		private int activeSec;

		public int getPatches() {
			return patches;
		}

		public int getXp() {
			return xp;
		}

		public int getActiveSec() {
			return activeSec;
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

		/** "ready", "growing", "diseased", "dead" or "unknown" when the route was loaded. */
		public String getReady() {
			return ready == null ? "unknown" : ready;
		}

		public String getNote() {
			return note;
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
