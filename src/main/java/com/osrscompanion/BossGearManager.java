package com.osrscompanion;

import lombok.Getter;
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
import net.runelite.client.config.ConfigManager;
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
	private final OSRSCompanionConfig config;
	private final ConfigManager configManager;

	@Getter
	private volatile BossGear set;
	@Getter
	private volatile List<BossGear.SetSummary> sets = Collections.emptyList();
	@Getter
	private volatile List<BossGear.CatalogBoss> catalog = Collections.emptyList();
	/** The gear mode of wiki presets (BossGear.MODE_*), as stored on the website. */
	@Getter
	private volatile String mode = BossGear.MODE_OWNED;
	@Getter
	private volatile String error;
	@Getter
	private volatile boolean loading;
	@Getter
	private volatile boolean loaded;
	@Getter
	private volatile boolean open;

	@Inject
	public BossGearManager(Client client, OSRSCompanionApiClient apiClient, RoadmapManager roadmapManager, FarmRunManager farmRunManager,
			OSRSCompanionConfig config, ConfigManager configManager) {
		this.client = client;
		this.apiClient = apiClient;
		this.roadmapManager = roadmapManager;
		this.farmRunManager = farmRunManager;
		this.config = config;
		this.configManager = configManager;
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

	/**
	 * Saves what you wear, carry and have in the rune pouch as your own set for the shown set's boss (named
	 * {@code name}, on the website too) and makes it the active build. Client thread (reads the containers).
	 */
	public void saveCurrentGear(String name) {
		String player = roadmapManager.getPlayerUsername();
		BossGear shown = set;
		if (player == null || shown == null || shown.getBoss() == null || !roadmapManager.hasAppKey()) {
			error = "Pick a boss build first, then save your gear for it.";
			return;
		}
		Map<String, Object> equipment = new java.util.LinkedHashMap<>();
		net.runelite.api.ItemContainer worn = client.getItemContainer(net.runelite.api.gameval.InventoryID.WORN);
		for (net.runelite.api.EquipmentInventorySlot slot : net.runelite.api.EquipmentInventorySlot.values()) {
			String key = WORN_KEYS.get(slot);
			net.runelite.api.Item it = worn == null ? null : worn.getItem(slot.getSlotIdx());
			if (key != null && it != null && it.getId() > 0) {
				equipment.put(key, it.getId());
			}
		}
		java.util.List<Object> inventory = new java.util.ArrayList<>();
		net.runelite.api.ItemContainer inv = client.getItemContainer(net.runelite.api.gameval.InventoryID.INV);
		for (int i = 0; i < 28; i++) {
			net.runelite.api.Item it = inv == null ? null : inv.getItem(i);
			if (it == null || it.getId() <= 0) {
				inventory.add(null);
				continue;
			}
			Map<String, Object> cell = new java.util.LinkedHashMap<>();
			ItemComposition comp = client.getItemDefinition(it.getId());
			boolean noted = comp != null && comp.getNote() != -1;
			cell.put("id", noted ? comp.getLinkedNoteId() : it.getId());
			if (it.getQuantity() > 1) {
				cell.put("q", it.getQuantity());
			}
			if (noted) {
				cell.put("noted", true);
			}
			inventory.add(cell);
		}
		java.util.List<Object> pouch = new java.util.ArrayList<>();
		for (Map.Entry<Integer, Integer> e : RunePouchMonitor.read(client).entrySet()) {
			Map<String, Object> cell = new java.util.LinkedHashMap<>();
			cell.put("id", e.getKey());
			cell.put("q", e.getValue());
			pouch.add(cell);
		}
		Map<String, Object> loadout = new java.util.LinkedHashMap<>();
		loadout.put("equipment", equipment);
		loadout.put("inventory", inventory);
		loadout.put("pouch", pouch);
		// The set it was made from: the shown set's style among the boss's setups.
		int setup = 0;
		for (BossGear.CatalogBoss b : catalog) {
			if (shown.getBoss().equals(b.getKey())) {
				setup = Math.max(0, b.getSetups().indexOf(shown.getStyle()));
			}
		}
		loading = true;
		apiClient.saveBossGearCustom(shown.getBoss(), setup, name, loadout, player, callback("your new set " + name));
	}

	/** The website's slot names for the worn slots. */
	private static final Map<net.runelite.api.EquipmentInventorySlot, String> WORN_KEYS = new java.util.EnumMap<>(net.runelite.api.EquipmentInventorySlot.class);

	static {
		WORN_KEYS.put(net.runelite.api.EquipmentInventorySlot.HEAD, "head");
		WORN_KEYS.put(net.runelite.api.EquipmentInventorySlot.CAPE, "cape");
		WORN_KEYS.put(net.runelite.api.EquipmentInventorySlot.AMULET, "neck");
		WORN_KEYS.put(net.runelite.api.EquipmentInventorySlot.AMMO, "ammo");
		WORN_KEYS.put(net.runelite.api.EquipmentInventorySlot.WEAPON, "weapon");
		WORN_KEYS.put(net.runelite.api.EquipmentInventorySlot.BODY, "body");
		WORN_KEYS.put(net.runelite.api.EquipmentInventorySlot.SHIELD, "shield");
		WORN_KEYS.put(net.runelite.api.EquipmentInventorySlot.LEGS, "legs");
		WORN_KEYS.put(net.runelite.api.EquipmentInventorySlot.GLOVES, "hands");
		WORN_KEYS.put(net.runelite.api.EquipmentInventorySlot.BOOTS, "feet");
		WORN_KEYS.put(net.runelite.api.EquipmentInventorySlot.RING, "ring");
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

	/**
	 * Sets the gear mode of wiki presets (stored on the website, the Boss Gear page shows the same choice)
	 * and reloads the set: always the best in slot, the best owned item of the wiki's list, or that plus
	 * the best owned item of the setup's style where you own none of the list.
	 */
	public void setMode(String newMode) {
		String player = roadmapManager.getPlayerUsername();
		String before = mode;
		if (player == null || !roadmapManager.hasAppKey() || newMode == null || newMode.equals(before)) {
			return;
		}
		// Shown straight away; the response confirms it.
		mode = newMode;
		loading = true;
		apiClient.setBossGearMode(newMode, player, new OSRSCompanionApiClient.RoadmapCallback<BossGear.Response>() {
			@Override
			public void onSuccess(BossGear.Response result) {
				callback(null).onSuccess(result);
			}

			@Override
			public void onError(String message) {
				mode = before;
				error = "Could not change the gear mode (" + message + ").";
				loading = false;
			}
		});
	}

	/** The next gear mode (Best in slot, Best owned, Owned alternatives, ...). */
	public void cycleMode() {
		setMode(BossGear.nextMode(mode));
	}

	private OSRSCompanionApiClient.RoadmapCallback<BossGear.Response> callback(String switchedTo) {
		return new OSRSCompanionApiClient.RoadmapCallback<BossGear.Response>() {
			@Override
			public void onSuccess(BossGear.Response result) {
				BossGear before = set;
				set = result.getSet();
				// Another build was picked (here or with Use in RuneLite on the website): it is in use again.
				if (before != null && set != null && before.getId() != set.getId()) {
					setSetActive(true);
				}
				sets = result.getSets();
				catalog = result.getCatalog();
				mode = result.getMode();
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

	/**
	 * Whether the selected setup is in use: Active lays out the bank and shows the gear, Inactive keeps it
	 * selected but leaves both alone (the side panel switch, stored in this plugin's settings).
	 */
	public boolean isSetActive() {
		return set != null && config.bossGearActive();
	}

	public void setSetActive(boolean active) {
		if (config.bossGearActive() != active) {
			configManager.setConfiguration(OSRSCompanionConfig.CONFIG_GROUP, "bossGearActive", active);
		}
	}

	/** A slot's tooltip (the window and the side panel): the item, what the setup picked and how many you carry. */
	public String slotTooltip(BossGear.Item it, int need, boolean synced) {
		int carried = carried(it);
		int want = Math.max(1, need);
		StringBuilder tip = new StringBuilder(it.getName());
		if (it.getQty() > 1) {
			tip.append(" x").append(it.getQty());
		}
		if (it.isNoted()) {
			tip.append(" (noted)");
		}
		if (it instanceof BossGear.Slot) {
			BossGear.Slot s = (BossGear.Slot) it;
			String where = "special".equals(s.getSlot()) ? "Special attack: " : "switch".equals(s.getSlot()) ? "Alternate weapon: "
				: "switch_shield".equals(s.getSlot()) ? "Alternate off-hand: " : "";
			tip.insert(0, where);
			tip.append(s.isAlternative() ? " - Alternative for " + s.getAltFor() + " (not owned)"
				: "bis".equals(s.getStatus()) ? " - best in slot" : "partial".equals(s.getStatus()) ? " - best you own (rank " + s.getRank() + ")"
				: "missing".equals(s.getStatus()) ? " - best in slot, not owned" : "");
		} else if (it.isAlternative()) {
			tip.append(" - Instead of ").append(it.getAltFor()).append(" (not owned)");
		}
		if (it.getNeed() != null) {
			tip.append(" - ").append(it.getNeed());
		}
		tip.append(" - carrying ").append(carried);
		if (carried < want) {
			tip.append(it.isOwned() ? ", in your bank or storage" : synced ? ", not in your bank" : "");
		}
		return tip.toString();
	}

	/** The tooltip of an empty inventory slot whose switch is already worn. */
	static String wornTooltip(BossGear.Item it) {
		return "Empty: you already wear " + it.getName() + ", the best for this switch (instead of " + it.getAltFor() + ")";
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
		// The ammo / spell options and charge materials the bank layout adds.
		for (int id : s.supplyItems(config.slayerGearSupplyOptions())) {
			if (id == itemId || ItemVariationMapping.map(id) == base) {
				return true;
			}
		}
		for (com.osrscompanion.model.SlayerGear.Bring b : s.getBring()) {
			for (int id : b.getItems()) {
				if (id == itemId || ItemVariationMapping.map(id) == base) {
					return true;
				}
			}
		}
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

	/** Item names (lower case) read on the client thread, for the side panel which paints on Swing's thread. */
	private final Map<Integer, String> names = new java.util.concurrent.ConcurrentHashMap<>();

	/** The item's name in lower case; off the client thread only when it was read there before, else null. */
	private String nameOf(int itemId) {
		String cached = names.get(itemId);
		if (cached != null || !client.isClientThread()) {
			return cached;
		}
		ItemComposition comp = client.getItemDefinition(itemId);
		String name = comp == null || comp.getName() == null ? null : comp.getName().toLowerCase();
		if (name != null) {
			names.put(itemId, name);
		}
		return name;
	}

	public void clear() {
		open = false;
		set = null;
		sets = Collections.emptyList();
		mode = BossGear.MODE_OWNED;
		loaded = false;
		error = null;
	}
}
