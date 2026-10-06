package com.osrscompanion.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * The active boss gear set as returned by GET /api/runelite/boss-gear/: per worn slot the item to
 * bring (the best one owned, else the best in slot), the wiki's 28 inventory slots and rune pouch,
 * plus every saved set to switch between.
 */
public class BossGear {
	@SerializedName("id")
	private int id;

	@SerializedName("boss")
	private String boss;

	@SerializedName("boss_name")
	private String bossName;

	/** The boss picture on the OSRS Wiki (for the side panel). */
	@SerializedName("image")
	private String image;

	@SerializedName("style")
	private String style;

	@SerializedName("inventory_label")
	private String inventoryLabel;

	/** Whether the bank was ever synced: without it "owned" says nothing. */
	@SerializedName("synced")
	private boolean synced;

	@SerializedName("equipment")
	private List<Slot> equipment;

	/** The 28 inventory slots in order; null entries are empty slots. */
	@SerializedName("inventory")
	private List<Item> inventory;

	@SerializedName("pouch")
	private List<Item> pouch;

	public int getId() {
		return id;
	}

	public String getBoss() {
		return boss;
	}

	public String getBossName() {
		return bossName == null ? "Boss" : bossName;
	}

	public String getImage() {
		return image;
	}

	public String getStyle() {
		return style == null ? "" : style;
	}

	public String getInventoryLabel() {
		return inventoryLabel;
	}

	public boolean isSynced() {
		return synced;
	}

	public List<Slot> getEquipment() {
		return equipment == null ? Collections.emptyList() : equipment;
	}

	public List<Item> getInventory() {
		return inventory == null ? Collections.emptyList() : inventory;
	}

	public List<Item> getPouch() {
		return pouch == null ? Collections.emptyList() : pouch;
	}

	/** The item for a worn slot (head, cape, ..., special), or null. */
	public Slot slot(String name) {
		for (Slot s : getEquipment()) {
			if (name.equals(s.getSlot())) {
				return s;
			}
		}
		return null;
	}

	public static class Item {
		/** The id to show: the version the player owns, else the item itself. */
		@SerializedName("item")
		private int item;

		@SerializedName("name")
		private String name;

		/** Every id that counts as this item (owned version, the item, its other versions). */
		@SerializedName("ids")
		private List<Integer> ids;

		/** In the bank or other synced storage, worn or carried at the last sync. */
		@SerializedName("owned")
		private boolean owned;

		@SerializedName("qty")
		private int qty;

		@SerializedName("noted")
		private boolean noted;

		public int getItem() {
			return item;
		}

		public String getName() {
			return name == null ? "Item" : name;
		}

		public List<Integer> getIds() {
			return ids == null || ids.isEmpty() ? Collections.singletonList(item) : ids;
		}

		public boolean isOwned() {
			return owned;
		}

		/** The stack size, 1 when the wiki gives none. */
		public int getQty() {
			return Math.max(1, qty);
		}

		public boolean isNoted() {
			return noted;
		}
	}

	public static class Slot extends Item {
		@SerializedName("slot")
		private String slot;

		/** bis | partial | missing | unknown, as on the website. */
		@SerializedName("status")
		private String status;

		@SerializedName("rank")
		private Integer rank;

		public String getSlot() {
			return slot;
		}

		public String getStatus() {
			return status == null ? "unknown" : status;
		}

		public Integer getRank() {
			return rank;
		}
	}

	public static class SetSummary {
		@SerializedName("id")
		private int id;

		@SerializedName("boss_name")
		private String bossName;

		@SerializedName("style")
		private String style;

		@SerializedName("active")
		private boolean active;

		/** Your own edited copy of a preset: shown by its name. */
		@SerializedName("custom")
		private boolean custom;

		@SerializedName("name")
		private String name;

		public int getId() {
			return id;
		}

		public String getName() {
			String label = getLabel();
			return (bossName == null ? "Boss" : bossName) + (label == null || label.isEmpty() ? "" : " - " + label);
		}

		/** The build without the boss: your own name for a custom build, else the wiki setup's style. */
		public String getLabel() {
			return custom && name != null ? name : style;
		}

		public boolean isCustom() {
			return custom;
		}

		public boolean isActive() {
			return active;
		}

		@Override
		public String toString() {
			return getName();
		}
	}

	/** A boss and its wiki setups (styles), to switch to any of them from the game. */
	public static class CatalogBoss {
		@SerializedName("key")
		private String key;

		@SerializedName("name")
		private String name;

		@SerializedName("setups")
		private List<String> setups;

		public String getKey() {
			return key;
		}

		public String getName() {
			return name == null ? key : name;
		}

		public List<String> getSetups() {
			return setups == null ? Collections.emptyList() : setups;
		}
	}

	/** GET /api/runelite/boss-gear/ and the activate calls: the active set (or null), every saved set and every boss. */
	public static class Response {
		@SerializedName("set")
		private BossGear set;

		@SerializedName("sets")
		private List<SetSummary> sets;

		@SerializedName("catalog")
		private List<CatalogBoss> catalog;

		public BossGear getSet() {
			return set;
		}

		public List<SetSummary> getSets() {
			return sets == null ? Collections.emptyList() : sets;
		}

		public List<CatalogBoss> getCatalog() {
			return catalog == null ? Collections.emptyList() : catalog;
		}
	}
}
