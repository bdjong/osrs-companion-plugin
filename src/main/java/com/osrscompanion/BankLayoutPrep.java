package com.osrscompanion;

import com.osrscompanion.model.BossGear;
import com.osrscompanion.model.FarmRoute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.bank.BankSearch;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;

/**
 * Gets the bank ready for the Boss gear window or the farm run: while the bank is open with the Boss
 * gear window open (or the Farm run window open, or a run going) it shows only what is needed, laid out
 * like the worn equipment (doll shape, left) and the inventory (4 x 7, right), with the rune pouch under
 * the doll. Items you do not have in the bank show as faded placeholders in their spot.
 *
 * <p>The layout goes through the core Bank Tags plugin's public API (the same as Inventory Setups):
 * {@link TagManager#registerTag} adds a tag whose items are the loadout's (variants included), a
 * {@link Layout} for it is saved with {@link LayoutManager#saveLayout} and the tag opens with
 * {@link BankTagsService#openBankTag}. When the Bank Tags plugin is off, it falls back to a bank search
 * ("boss gear" / "farm run") that only filters, without positions.
 *
 * <p>Only one drives the bank at a time: the Boss gear window first, then the farm run.
 */
@Slf4j
@Singleton
public class BankLayoutPrep {
	static final String BOSS_TAG = "osrs companion boss gear";
	static final String FARM_TAG = "osrs companion farm run";
	static final String BOSS_SEARCH = "boss gear";
	static final String FARM_SEARCH = "farm run";

	private final Client client;
	private final ClientThread clientThread;
	private final BankSearch bankSearch;
	private final ItemManager itemManager;
	private final FarmRunManager farmRun;
	private final BossGearManager bossGear;
	private final OSRSCompanionConfig config;
	private final BankTagsService bankTags;
	private final TagManager tagManager;
	private final LayoutManager layoutManager;
	private final PluginManager pluginManager;

	/** What was put in the open bank ("boss:..." / "farm:..."), so it is redone only when that changes. Client thread. */
	private String applied;
	/** The fallback search text we typed, when Bank Tags is off. */
	private String searched;

	enum Driver {
		BOSS, FARM
	}

	@Inject
	public BankLayoutPrep(Client client, ClientThread clientThread, BankSearch bankSearch, ItemManager itemManager, FarmRunManager farmRun,
		BossGearManager bossGear, OSRSCompanionConfig config, BankTagsService bankTags, TagManager tagManager, LayoutManager layoutManager,
		PluginManager pluginManager) {
		this.client = client;
		this.clientThread = clientThread;
		this.bankSearch = bankSearch;
		this.itemManager = itemManager;
		this.farmRun = farmRun;
		this.bossGear = bossGear;
		this.config = config;
		this.bankTags = bankTags;
		this.tagManager = tagManager;
		this.layoutManager = layoutManager;
		this.pluginManager = pluginManager;
	}

	/** Registers the two tags; their items follow the active set / route live. */
	public void startUp() {
		// Bank Tags reads its tags on the client thread.
		clientThread.invoke(() -> {
			tagManager.registerTag(BOSS_TAG, bossGear::needs);
			tagManager.registerTag(FARM_TAG, farmRun::needs);
		});
	}

	public void shutDown() {
		clientThread.invoke(() -> {
			if (isOurTagOpen()) {
				bankTags.closeBankTag();
			}
			tagManager.unregisterTag(BOSS_TAG);
			tagManager.unregisterTag(FARM_TAG);
			// The layouts are rebuilt on every bank open: none left in the Bank Tags config.
			layoutManager.removeLayout(BOSS_TAG);
			layoutManager.removeLayout(FARM_TAG);
			applied = null;
		});
	}

	// ---- When ---------------------------------------------------------------------

	/** Re-reads the active boss setup / farm route when the bank opens, at most this often (one just picked on the website). */
	private static final long AUTO_REFRESH_MS = 60_000;
	private long lastBossRefresh;

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() == InterfaceID.BANKMAIN) {
			applied = null;
			searched = null;
			// An active setup / route lays the bank out even with its window closed: make sure it is loaded and current.
			long now = System.currentTimeMillis();
			if (config.bossGearBankLayout() && !bossGear.isOpen() && !bossGear.isLoading()
					&& (bossGear.getSet() == null || now - lastBossRefresh > AUTO_REFRESH_MS)) {
				lastBossRefresh = now;
				bossGear.refresh();
			}
			if (config.farmRunBankFilter() && !farmRun.isOpen()) {
				farmRun.refreshIfStale();
			}
			clientThread.invokeLater(this::update);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		if (event.getGroupId() == InterfaceID.BANKMAIN) {
			applied = null;
			searched = null;
		}
	}

	/** A window opened or closed, or a set / route (re)loaded, while the bank is open. */
	@Subscribe
	public void onGameTick(GameTick tick) {
		if (client.getWidget(InterfaceID.Bankmain.UNIVERSE) != null) {
			update();
		}
	}

	/**
	 * Which feature drives the bank now. Each is "active in the bank" by its own setting; when both
	 * are, an open window wins, then a farm run in progress, then the boss setup, then the route.
	 */
	Driver driver() {
		boolean boss = bossGear.getSet() != null && config.bossGearBankLayout();
		boolean farm = farmRun.getRoute() != null && config.farmRunBankFilter();
		if (boss && bossGear.isOpen()) {
			return Driver.BOSS;
		}
		if (farm && (farmRun.isOpen() || (farmRun.isRunning() && !farmRun.isComplete()))) {
			return Driver.FARM;
		}
		if (boss) {
			return Driver.BOSS;
		}
		return farm ? Driver.FARM : null;
	}

	/** Changes with the driver and whenever its data is reloaded (a new object), so a switched set is laid out again. */
	private String key(Driver d) {
		if (d == Driver.BOSS) {
			BossGear s = bossGear.getSet();
			return "boss:" + s.getId() + ":" + System.identityHashCode(s) + ":" + config.bankLayoutPositions();
		}
		FarmRoute r = farmRun.getRoute();
		return "farm:" + r.getId() + ":" + System.identityHashCode(r) + ":" + farmRun.inventory().size() + ":" + config.bankLayoutPositions();
	}

	private void update() {
		if (client.getWidget(InterfaceID.Bankmain.UNIVERSE) == null) {
			return;
		}
		Driver d = driver();
		String k = d == null ? null : key(d);
		if (k == null) {
			if (applied != null || isOurTagOpen()) {
				release();
			}
			applied = null;
			return;
		}
		if (k.equals(applied)) {
			return;
		}
		applied = k;
		try {
			apply(d);
		} catch (RuntimeException e) {
			log.warn("Could not lay out the bank", e);
		}
	}

	// ---- How ----------------------------------------------------------------------------

	private boolean bankTagsOn() {
		return bankTags instanceof Plugin && pluginManager.isPluginActive((Plugin) bankTags);
	}

	private boolean isOurTagOpen() {
		String tag = bankTagsOn() ? bankTags.getActiveTag() : null;
		return BOSS_TAG.equals(tag) || FARM_TAG.equals(tag);
	}

	private void apply(Driver d) {
		String tag = d == Driver.BOSS ? BOSS_TAG : FARM_TAG;
		if (!bankTagsOn()) {
			// Fallback: a bank search that only shows the needed items (see onScriptCallbackEvent).
			searched = d == Driver.BOSS ? BOSS_SEARCH : FARM_SEARCH;
			bankSearch.initSearch();
			client.setVarcStrValue(VarClientID.MESLAYERINPUT, searched);
			bankSearch.layoutBank();
			return;
		}
		BankItems bank = new BankItems();
		int[] positions = d == Driver.BOSS ? bossLayout(bossGear.getSet(), bank) : farmLayout(bank);
		layoutManager.saveLayout(new Layout(tag, positions));
		int options = BankTagsService.OPTION_HIDE_TAG_NAME | BankTagsService.OPTION_ITEMS_NOT_IN_LAYOUT_AT_BOTTOM
			| (config.bankLayoutPositions() ? 0 : BankTagsService.OPTION_NO_LAYOUT);
		bankTags.openBankTag(tag, options);
	}

	/** Back to the normal bank: our tag closes, or our fallback search clears. */
	private void release() {
		if (isOurTagOpen()) {
			bankTags.closeBankTag();
		}
		if (searched != null) {
			searched = null;
			bankSearch.reset(true);
		}
	}

	private int[] bossLayout(BossGear set, BankItems bank) {
		Map<String, Integer> worn = new LinkedHashMap<>();
		for (BossGear.Slot s : set.getEquipment()) {
			if (!"special".equals(s.getSlot())) {
				worn.put(s.getSlot(), bank.resolve(s.getIds()));
			}
		}
		List<Integer> inv = new ArrayList<>();
		for (BossGear.Item it : set.getInventory()) {
			inv.add(it == null ? -1 : bank.resolve(it.getIds()));
		}
		List<Integer> pouch = new ArrayList<>();
		for (BossGear.Item it : set.getPouch()) {
			pouch.add(bank.resolve(it.getIds()));
		}
		BossGear.Slot special = set.slot("special");
		return BankLayouts.build(worn, inv, pouch, special == null ? -1 : bank.resolve(special.getIds()));
	}

	private int[] farmLayout(BankItems bank) {
		FarmRoute r = farmRun.getRoute();
		Map<String, Integer> worn = new LinkedHashMap<>();
		for (FarmRoute.Stack s : r.getEquipment()) {
			if (s.getSlot() != null) {
				worn.putIfAbsent(s.getSlot(), bank.resolve(Collections.singletonList(s.getItem())));
			}
		}
		// The farm run window draws the route's inventory one stack per slot, in order: the bank does the same.
		List<Integer> inv = new ArrayList<>();
		for (FarmRoute.Stack s : farmRun.inventory()) {
			inv.add(bank.resolve(Collections.singletonList(s.getItem())));
		}
		return BankLayouts.build(worn, inv, Collections.emptyList(), -1);
	}

	/**
	 * The bank's items, to put the version you actually have in the layout (a charged, coloured or
	 * same-named one); otherwise the item itself, which Bank Tags shows faded. Client thread.
	 */
	private final class BankItems {
		private final java.util.Set<Integer> ids = new java.util.HashSet<>();
		private final Map<Integer, Integer> byBase = new HashMap<>();
		private Map<String, Integer> byName;

		BankItems() {
			ItemContainer bank = client.getItemContainer(InventoryID.BANK);
			if (bank == null) {
				return;
			}
			for (Item item : bank.getItems()) {
				int id = item.getId();
				if (id <= 0 || item.getQuantity() <= 0) {
					continue;
				}
				ids.add(id);
				byBase.putIfAbsent(ItemVariationMapping.map(id), id);
			}
		}

		int resolve(List<Integer> candidates) {
			if (candidates.isEmpty()) {
				return -1;
			}
			for (int c : candidates) {
				int id = itemManager.canonicalize(c);
				if (ids.contains(id)) {
					return id;
				}
			}
			for (int c : candidates) {
				Integer id = byBase.get(ItemVariationMapping.map(itemManager.canonicalize(c)));
				if (id != null) {
					return id;
				}
			}
			String name = nameOf(itemManager.canonicalize(candidates.get(0)));
			Integer byNameId = name == null ? null : names().get(name);
			return byNameId != null ? byNameId : itemManager.canonicalize(candidates.get(0));
		}

		private Map<String, Integer> names() {
			if (byName == null) {
				byName = new HashMap<>();
				for (int id : ids) {
					String n = nameOf(id);
					if (n != null) {
						byName.putIfAbsent(n, id);
					}
				}
			}
			return byName;
		}
	}

	private String nameOf(int itemId) {
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp == null || comp.getName() == null ? null : comp.getName().toLowerCase();
	}

	// ---- Fallback search ------------------------------------------------------------------

	/** After the game's and Bank Tags' own matching: for our search, only the loadout's items match. */
	@Subscribe(priority = -10)
	public void onScriptCallbackEvent(ScriptCallbackEvent event) {
		if (!"bankSearchFilter".equals(event.getEventName())) {
			return;
		}
		int[] ints = client.getIntStack();
		int intSize = client.getIntStackSize();
		Object[] objects = client.getObjectStack();
		int objectSize = client.getObjectStackSize();
		Object search = objectSize < 1 ? null : objects[objectSize - 1];
		if (intSize < 2 || !(search instanceof String)) {
			return;
		}
		String text = ((String) search).trim();
		boolean boss = BOSS_SEARCH.equalsIgnoreCase(text);
		if (!boss && !FARM_SEARCH.equalsIgnoreCase(text)) {
			return;
		}
		int itemId = itemManager.canonicalize(ints[intSize - 1]);
		ints[intSize - 2] = (boss ? bossGear.needs(itemId) : farmRun.needs(itemId)) ? 1 : 0;
	}
}
