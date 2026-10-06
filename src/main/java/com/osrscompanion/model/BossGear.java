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

		public int getId() {
			return id;
		}

		public String getName() {
			return (bossName == null ? "Boss" : bossName) + (style == null || style.isEmpty() ? "" : " - " + style);
		}

		public boolean isActive() {
			return active;
		}

		@Override
		public String toString() {
			return getName();
		}
	}

	/** GET /api/runelite/boss-gear/ and the activate call: the active set (or null) and every saved set. */
	public static class Response {
		@SerializedName("set")
		private BossGear set;

		@SerializedName("sets")
		private List<SetSummary> sets;

		public BossGear getSet() {
			return set;
		}

		public List<SetSummary> getSets() {
			return sets == null ? Collections.emptyList() : sets;
		}
	}
}
