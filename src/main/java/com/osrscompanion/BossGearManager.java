package com.osrscompanion;

import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.BossGear;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemVariationMapping;

/**
 * The active boss gear set from the website (Tools > Boss Gear > Use in RuneLite): loaded when the
 * Boss gear window opens, switchable from the window. What you carry is counted by
 * {@link FarmRunManager#carried(int)} (inventory, worn and rune pouch, variants included).
 *
 * <p>Network work runs on OkHttp threads; fields the window and the bank layout read are volatile.
 */
@Singleton
public class BossGearManager {
	private final Client client;
	private final OSRSCompanionApiClient apiClient;
	private final RoadmapManager roadmapManager;
	private final FarmRunManager farmRunManager;

	private volatile BossGear set;
	private volatile List<BossGear.SetSummary> sets = Collections.emptyList();
	private volatile List<BossGear.CatalogBoss> catalog = Collections.emptyList();
	private volatile String error;
	private volatile boolean loading;
	private volatile boolean loaded;
	private volatile boolean open;

	@Inject
	public BossGearManager(Client client, OSRSCompanionApiClient apiClient, RoadmapManager roadmapManager, FarmRunManager farmRunManager) {
		this.client = client;
		this.apiClient = apiClient;
		this.roadmapManager = roadmapManager;
		this.farmRunManager = farmRunManager;
	}

	// ---- Loading ---------------------------------------------------------------------

	public void refresh() {
		String player = roadmapManager.getPlayerUsername();
		if (player == null) {
			error = "Log in to the game to load your boss gear.";
			return;
		}
		if (!roadmapManager.hasAppKey()) {
			error = "Add your App Key in the OSRS Companion settings first.";
			return;
		}
		loading = true;
		apiClient.getBossGear(player, callback(null));
	}

	/** Switches the active set (on the website too) and loads it. */
	public void activate(BossGear.SetSummary summary) {
		String player = roadmapManager.getPlayerUsername();
		if (summary == null || player == null || (set != null && set.getId() == summary.getId())) {
			return;
		}
		loading = true;
		apiClient.activateBossGear(summary.getId(), player, callback(summary.getName()));
	}

	/** Switches to any boss preset (on the website too) and loads it. */
	public void activatePreset(BossGear.CatalogBoss boss, int setup) {
		String player = roadmapManager.getPlayerUsername();
		if (boss == null || player == null || setup < 0 || setup >= boss.getSetups().size()) {
			return;
		}
		loading = true;
		apiClient.activateBossGearPreset(boss.getKey(), setup, player, callback(boss.getName() + " - " + boss.getSetups().get(setup)));
	}

	private OSRSCompanionApiClient.RoadmapCallback<BossGear.Response> callback(String switchedTo) {
		return new OSRSCompanionApiClient.RoadmapCallback<BossGear.Response>() {
			@Override
			public void onSuccess(BossGear.Response result) {
				set = result.getSet();
				sets = result.getSets();
				catalog = result.getCatalog();
				error = null;
				loaded = true;
				loading = false;
			}

			@Override
			public void onError(String message) {
				error = (switchedTo == null ? "Could not load your boss gear" : "Could not switch to " + switchedTo) + " (" + message + ").";
				loading = false;
			}
		};
	}

	// ---- The window --------------------------------------------------------------------

	public boolean isOpen() {
		return open;
	}

	public void toggle() {
		if (open) {
			close();
		} else {
			openWindow();
		}
	}

	/** Opens the window and always re-reads the set, so one just picked on the website shows straight away. */
	public void openWindow() {
		open = true;
		if (!loading) {
			refresh();
		}
	}

	public void close() {
		open = false;
	}

	// ---- Read by the window and the bank layout ----------------------------------------------

	public BossGear getSet() {
		return set;
	}

	public List<BossGear.SetSummary> getSets() {
		return sets;
	}

	public List<BossGear.CatalogBoss> getCatalog() {
		return catalog;
	}

	public String getError() {
		return error;
	}

	public boolean isLoading() {
		return loading;
	}

	public boolean isLoaded() {
		return loaded;
	}

	/** How many of the item you carry (inventory, worn, rune pouch), counting any of its versions. */
	public int carried(BossGear.Item item) {
		int best = 0;
		for (int id : item.getIds()) {
			best = Math.max(best, farmRunManager.carried(id));
		}
		return best;
	}

	/** How many of each item (by its shown id) the whole inventory and pouch need: one per slot, or the stack size. */
	public Map<Integer, Integer> inventoryNeeds() {
		BossGear s = set;
		Map<Integer, Integer> out = new HashMap<>();
		if (s == null) {
			return out;
		}
		for (BossGear.Item it : s.getInventory()) {
			if (it != null) {
				out.merge(it.getItem(), it.getQty(), Integer::sum);
			}
		}
		for (BossGear.Item it : s.getPouch()) {
			out.merge(it.getItem(), it.getQty(), Integer::sum);
		}
		return out;
	}

	/** Whether the set needs this item: one of an item's ids, another version of it (charges) or the same name. For the bank. */
	public boolean needs(int itemId) {
		BossGear s = set;
		if (s == null) {
			return false;
		}
		int base = ItemVariationMapping.map(itemId);
		String name = null;
		for (BossGear.Item it : items(s)) {
			for (int id : it.getIds()) {
				if (id == itemId || ItemVariationMapping.map(id) == base) {
					return true;
				}
			}
		}
		for (BossGear.Item it : items(s)) {
			if (name == null) {
				name = nameOf(itemId);
				if (name == null) {
					return false;
				}
			}
			if (name.equals(nameOf(it.getItem()))) {
				return true;
			}
		}
		return false;
	}

	/** Every item of the set: worn (and special attack), inventory and rune pouch. */
	static List<BossGear.Item> items(BossGear s) {
		List<BossGear.Item> all = new java.util.ArrayList<>(s.getEquipment());
		for (BossGear.Item it : s.getInventory()) {
			if (it != null) {
				all.add(it);
			}
		}
		all.addAll(s.getPouch());
		return all;
	}

	private String nameOf(int itemId) {
		ItemComposition comp = client.getItemDefinition(itemId);
		return comp == null || comp.getName() == null ? null : comp.getName().toLowerCase();
	}

	public void clear() {
		open = false;
		set = null;
		sets = Collections.emptyList();
		loaded = false;
		error = null;
	}
}
