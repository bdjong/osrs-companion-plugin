package com.osrscompanion.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * The active boss gear set as returned by GET /api/runelite/boss-gear/: per worn slot the item to
 * bring (picked by the gear mode), the wiki's 28 inventory slots and rune pouch, plus every saved set
 * to switch between. Besides the worn slots, "special" is the special attack weapon and "switch" /
 * "switch_shield" the Alternate weapon and its off-hand; with a two-handed weapon there is no "shield".
 */
public class BossGear {
	/** Gear modes of wiki presets: always the best in slot, the best owned from the wiki's list, or that plus owned alternatives. */
	public static final String MODE_BIS = "bis";
	public static final String MODE_OWNED = "owned";
	public static final String MODE_ALTERNATIVES = "alternatives";
	public static final String[] MODES = {MODE_BIS, MODE_OWNED, MODE_ALTERNATIVES};

	/** A gear mode's name as the website shows it. */
	public static String modeLabel(String mode) {
		return MODE_BIS.equals(mode) ? "Best in slot" : MODE_ALTERNATIVES.equals(mode) ? "Owned alternatives" : "Best owned";
	}

	/** A short name for narrow places. */
	public static String modeShortLabel(String mode) {
		return MODE_BIS.equals(mode) ? "BiS" : MODE_ALTERNATIVES.equals(mode) ? "Alts" : "Owned";
	}

	/** What a gear mode does, in one line. */
	public static String modeDescription(String mode) {
		return MODE_BIS.equals(mode) ? "Every slot shows the wiki's best in slot, owned or not."
			: MODE_ALTERNATIVES.equals(mode) ? "Best owned from the wiki's list; where you own none, your best item of the setup's style (marked A)."
			: "The best item you own from the slot's wiki list; red when you own none. Inventory items you lack become an owned one doing the same job (marked A).";
	}

	/** The mode after this one (bis, owned, alternatives, bis, ...). */
	public static String nextMode(String mode) {
		for (int i = 0; i < MODES.length; i++) {
			if (MODES[i].equals(mode)) {
				return MODES[(i + 1) % MODES.length];
			}
		}
		return MODE_OWNED;
	}

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

	/** The boss's strategy page on the OSRS Wiki (Go to wiki). */
	@SerializedName("url")
	private String url;

	/** The weapon brought is two-handed: the shield slot stays empty. */
	@SerializedName("two_handed")
	private boolean twoHanded;

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

	public String getUrl() {
		return url;
	}

	public boolean isTwoHanded() {
		return twoHanded;
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

		/** bis | partial | missing | alt | unknown for worn slots; inventory and pouch items only say "alt". */
		@SerializedName("status")
		private String status;

		/** Status "alt": the wiki item(s) this owned item stands in for (not owned). */
		@SerializedName("alt_for")
		private String altFor;

		/** Why this item and nothing weaker (a boss mechanic), or why a slot brings it (crystal armour with the Bow of Faerdhinen). */
		@SerializedName("need")
		private String need;

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

		public String getStatus() {
			return status == null ? "unknown" : status;
		}

		/**
		 * An owned item brought instead of the wiki's that you don't own: an owned alternative in a worn
		 * slot, or an inventory item that does the same job ("Best owned" / "Owned alternatives").
		 */
		public boolean isAlternative() {
			return "alt".equals(status);
		}

		public String getAltFor() {
			return altForOr("the wiki's item");
		}

		/** The mechanic or rule behind the item, null when there is none. */
		public String getNeed() {
			return need == null || need.isEmpty() ? null : need;
		}

		protected String altForOr(String fallback) {
			return altFor == null || altFor.isEmpty() ? fallback : altFor;
		}
	}

	public static class Slot extends Item {
		@SerializedName("slot")
		private String slot;

		@SerializedName("rank")
		private Integer rank;

		public String getSlot() {
			return slot;
		}

		public Integer getRank() {
			return rank;
		}

		@Override
		public String getAltFor() {
			return altForOr("the best in slot");
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

		/** The gear mode of wiki presets (bis | owned | alternatives): shared with the website's Boss Gear page. */
		@SerializedName("mode")
		private String mode;

		/** From before the gear modes: true meant the alternatives mode. */
		@SerializedName("alternatives")
		private boolean alternatives;

		public BossGear getSet() {
			return set;
		}

		public List<SetSummary> getSets() {
			return sets == null ? Collections.emptyList() : sets;
		}

		public List<CatalogBoss> getCatalog() {
			return catalog == null ? Collections.emptyList() : catalog;
		}

		/** The gear mode, "owned" when the website says none. */
		public String getMode() {
			for (String m : MODES) {
				if (m.equals(mode)) {
					return m;
				}
			}
			return alternatives ? MODE_ALTERNATIVES : MODE_OWNED;
		}
	}
}
