package com.osrscompanion;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;

/**
 * Counts items as supplies ({@link TripSupplies} keys and units) for the boss trip log and the supplies
 * check, and remembers for each key the item that pictures it (the highest dose seen) and its name.
 * Client thread, except {@link #rep} which only reads what was counted.
 */
@Singleton
public class SupplyCounter {
	private final Client client;
	private final ItemManager itemManager;

	/** The item that stands for a key: its id, dose (1 for undosed items) and name. */
	static final class Rep {
		final int id;
		final int dose;
		final String name;

		Rep(int id, int dose, String name) {
			this.id = id;
			this.dose = dose;
			this.name = name;
		}
	}

	private final Map<String, Rep> reps = new ConcurrentHashMap<>();

	@Inject
	public SupplyCounter(Client client, ItemManager itemManager) {
		this.client = client;
		this.itemManager = itemManager;
	}

	/** The key of an item (noted and placeholder versions count as the item). */
	String key(int itemId) {
		int id = itemManager.canonicalize(itemId);
		String name = nameOf(id);
		String key = TripSupplies.key(ItemVariationMapping.map(id), name);
		TripSupplies.Dosed d = TripSupplies.dosed(name);
		int dose = d == null ? 1 : d.dose;
		Rep rep = reps.get(key);
		if (rep == null || dose > rep.dose) {
			reps.put(key, new Rep(id, dose, name == null ? "Item " + id : name));
		}
		return key;
	}

	/** Adds a stack to a count. */
	void add(Map<String, Long> counts, int itemId, long quantity) {
		if (itemId <= 0 || quantity <= 0) {
			return;
		}
		String key = key(itemId);
		counts.merge(key, TripSupplies.units(nameOf(itemManager.canonicalize(itemId)), quantity), Long::sum);
	}

	/** Inventory, worn items and the runes in a carried rune pouch. */
	Map<String, Long> carried() {
		Map<String, Long> out = new HashMap<>();
		boolean pouch = false;
		for (int container : new int[]{InventoryID.INV, InventoryID.WORN}) {
			ItemContainer c = client.getItemContainer(container);
			if (c == null) {
				continue;
			}
			for (Item item : c.getItems()) {
				add(out, item.getId(), item.getQuantity());
				pouch |= container == InventoryID.INV && FarmRunManager.RUNE_POUCHES.contains(item.getId());
			}
		}
		if (pouch) {
			for (Map.Entry<Integer, Integer> rune : RunePouchMonitor.read(client).entrySet()) {
				add(out, rune.getKey(), rune.getValue());
			}
		}
		return out;
	}

	/** The open bank's items, or null when it is not open. */
	Map<String, Long> bank() {
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null) {
			return null;
		}
		Map<String, Long> out = new HashMap<>();
		for (Item item : bank.getItems()) {
			add(out, item.getId(), item.getQuantity());
		}
		return out;
	}

	/**
	 * Whether an item gets used up on a trip: stackable (runes, ammo, tablets, seeds), eaten or drunk, or
	 * compost. Gear switches and teleport jewellery are not.
	 */
	boolean consumable(int itemId) {
		ItemComposition comp = itemManager.getItemComposition(itemManager.canonicalize(itemId));
		if (comp == null) {
			return false;
		}
		if (comp.isStackable()) {
			return true;
		}
		String name = comp.getName() == null ? "" : comp.getName().toLowerCase(Locale.ROOT);
		if (name.contains("compost") && !name.contains("bottomless")) {
			return true;
		}
		String[] actions = comp.getInventoryActions();
		if (actions != null) {
			for (String a : actions) {
				if ("Eat".equalsIgnoreCase(a) || "Drink".equalsIgnoreCase(a)) {
					return true;
				}
			}
		}
		return false;
	}

	/** The item standing for a key, or null when it was never counted. */
	Rep rep(String key) {
		return reps.get(key);
	}

	private String nameOf(int itemId) {
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp == null ? null : comp.getName();
	}
}
