package com.osrscompanion.model;

import lombok.Getter;
import com.google.gson.Gson;
import java.util.List;

/**
 * Payload for syncing loadout data to the OSRS Companion API.
 * Matches the format expected by POST /api/runelite/loadouts/
 */
public class LoadoutSyncPayload {
	private final String player_username;
	private final List<LoadoutSet> loadout_sets;

	public LoadoutSyncPayload(String playerUsername, List<LoadoutSet> loadoutSets) {
		this.player_username = playerUsername;
		this.loadout_sets = loadoutSets;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}

	/**
	 * Represents a loadout set (active gear or inventory setup).
	 */
	public static class LoadoutSet {
		@Getter
		private final String name;
		@Getter
		private final String type;
		@Getter
		private final String category;
		private final List<EquipmentItem> equipment_items;
		private final List<InventoryItem> inventory_items;

		public LoadoutSet(String name, String type, String category,
				List<EquipmentItem> equipmentItems, List<InventoryItem> inventoryItems) {
			this.name = name;
			this.type = type;
			this.category = category;
			this.equipment_items = equipmentItems;
			this.inventory_items = inventoryItems;
		}

		public List<EquipmentItem> getEquipmentItems() {
			return equipment_items;
		}

		public List<InventoryItem> getInventoryItems() {
			return inventory_items;
		}
	}

	/**
	 * Represents an equipment item in a specific slot.
	 */
	public static class EquipmentItem {
		private final int item_id;
		@Getter
		private final String slot;

		public EquipmentItem(int itemId, String slot) {
			this.item_id = itemId;
			this.slot = slot;
		}

		public int getItemId() {
			return item_id;
		}

	}

	/**
	 * Represents an inventory item at a specific slot index.
	 */
	public static class InventoryItem {
		private final int item_id;
		private final int slot_index;

		public InventoryItem(int itemId, int slotIndex) {
			this.item_id = itemId;
			this.slot_index = slotIndex;
		}

		public int getItemId() {
			return item_id;
		}

		public int getSlotIndex() {
			return slot_index;
		}
	}
}
