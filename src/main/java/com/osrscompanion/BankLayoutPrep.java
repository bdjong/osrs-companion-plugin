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
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
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
 * {@link BankTagsService#openBankTag}. When the Bank Tags plugin is off, the bank is left alone.
 *
 * <p>Only one drives the bank at a time: the Boss gear window first, then the farm run.
 */
@Slf4j
@Singleton
public class BankLayoutPrep {
	static final String BOSS_TAG = "osrs companion boss gear";
	static final String FARM_TAG = "osrs companion farm run";

	private final Client client;
	private final ClientThread clientThread;
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

	enum Driver {
		BOSS, FARM
	}

	@Inject
	public BankLayoutPrep(Client client, ClientThread clientThread, ItemManager itemManager, FarmRunManager farmRun,
		BossGearManager bossGear, OSRSCompanionConfig config, BankTagsService bankTags, TagManager tagManager, LayoutManager layoutManager,
		PluginManager pluginManager) {
		this.client = client;
		this.clientThread = clientThread;
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

	/**
	 * Opening our tag right as the bank opens does not always stick: Bank Tags and the server's tab
	 * sync can still put the whole bank back while our tag counts as open. So for a few ticks after the
	 * bank opens (and after each apply) we check what the bank actually shows, and when it shows items
	 * the set / route does not need, close and open our layout again, a few times at most. After that,
	 * another bank tab the player clicks is left alone.
	 */
	private static final int VERIFY_TICKS = 5;
	private static final int MAX_RETRIES = 4;
	private int verifyUntilTick = -1;
	private int retries;

	/** Re-reads the active boss setup / farm route when the bank opens, at most this often (one just picked on the website). */
	private static final long AUTO_REFRESH_MS = 60_000;
	private long lastBossRefresh;

	/** Set when the bank opens until its first build: the layout waits for it. */
	private boolean waitingForBuild;
	private int bankOpenedTick;
	/** The positions last laid out (-1: empty): an item showing in an empty one means the layout is not drawn. */
	private int[] laidOut;

	/** Loads the active boss setup in the background after login, at most this often, so the first bank open has it. */
	private static final int PRELOAD_EVERY_TICKS = 50;
	private int lastPreloadTick = -PRELOAD_EVERY_TICKS;

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() == InterfaceID.BANKMAIN) {
			applied = null;
			retries = 0;
			log.debug("Bank layout: bank opened. {}", state());
			// An active setup / route lays the bank out even with its window closed: make sure it is loaded and current.
			long now = System.currentTimeMillis();
			if (config.bossGearBankLayout() && config.bossGearActive() && !bossGear.isOpen() && !bossGear.isLoading()
					&& (bossGear.getSet() == null || now - lastBossRefresh > AUTO_REFRESH_MS)) {
				lastBossRefresh = now;
				log.debug("Bank layout: reloading the boss setup ({})", bossGear.getSet() == null ? "none loaded" : "older than a minute");
				bossGear.refresh();
			}
			if (config.farmRunBankFilter() && farmRun.isRunning() && !farmRun.isOpen()) {
				farmRun.refreshIfStale();
			}
			verifyUntilTick = client.getTickCount() + VERIFY_TICKS;
			// Not yet: opening our tag before the bank has been built leaves Bank Tags filtering without
			// drawing the layout. The first build (or two ticks, whichever comes first) lays it out.
			waitingForBuild = true;
			bankOpenedTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		if (event.getScriptId() == ScriptID.BANKMAIN_BUILD && waitingForBuild) {
			waitingForBuild = false;
			log.debug("Bank layout: bank built, laying it out");
			clientThread.invokeLater(this::update);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		if (event.getGroupId() == InterfaceID.BANKMAIN) {
			applied = null;
		}
	}

	/** A window opened or closed, or a set / route (re)loaded, while the bank is open. */
	@Subscribe
	public void onGameTick(GameTick tick) {
		preloadBossGear();
		if (client.getWidget(InterfaceID.Bankmain.UNIVERSE) == null) {
			return;
		}
		if (waitingForBuild) {
			if (client.getTickCount() - bankOpenedTick < 2) {
				return;
			}
			log.debug("Bank layout: no bank build seen, laying it out anyway");
			waitingForBuild = false;
		}
		if (applied != null && client.getTickCount() <= verifyUntilTick && retries < MAX_RETRIES && bankTagsOn() && !showingOurs()) {
			log.debug("Bank layout: did not stick (our tag open: {}, Bank Tags tag: {}), applying it again, retry {}/{}",
				isOurTagOpen(), bankTags.getActiveTag(), retries + 1, MAX_RETRIES);
			retries++;
			if (isOurTagOpen()) {
				bankTags.closeBankTag();
			}
			applied = null;
		}
		update();
	}

	/** Bank filler and the empty-slot object: shown in a whole bank, never items of a set. */
	private static final int BANK_FILLER = 20594;
	private static final int EMPTY_SLOT = 6512;

	/** Whether the open bank shows our tag: no item the set / route does not need is on show. Client thread. */
	private boolean showingOurs() {
		if (!isOurTagOpen()) {
			return false;
		}
		Driver d = driver();
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (d == null || items == null) {
			return true;
		}
		Widget[] children = items.getDynamicChildren();
		if (children == null) {
			return true;
		}
		int[] positions = laidOut;
		if (positions != null && config.bankLayoutPositions()) {
			// Laid out, the empty spots of the layout show nothing; filtered only, the items fill them from the top.
			for (int pos = 0; pos < positions.length && pos < children.length; pos++) {
				Widget w = children[pos];
				int id = w.getItemId();
				if (positions[pos] == -1 && !w.isHidden() && id > 0 && id != BANK_FILLER && id != EMPTY_SLOT) {
					log.debug("Bank layout: item {} shows at position {}, which the layout keeps empty: the layout is not drawn", id, pos);
					return false;
				}
			}
		}
		for (Widget w : children) {
			int id = w.getItemId();
			if (w.isHidden() || id <= 0 || id == BANK_FILLER || id == EMPTY_SLOT) {
				continue;
			}
			int item = itemManager.canonicalize(id);
			if (d == Driver.BOSS ? !bossGear.needs(item) : !farmRun.needs(item)) {
				log.debug("Bank layout: the bank shows item {} the {} does not need", item, d == Driver.BOSS ? "boss setup" : "farm route");
				return false;
			}
		}
		return true;
	}

	/** Everything that decides the layout, for the debug log. */
	private String state() {
		BossGear set = bossGear.getSet();
		return "boss: set " + (set == null ? "none" : set.getId() + " " + set.getBossName()) + ", active " + config.bossGearActive()
			+ ", bank layout setting " + config.bossGearBankLayout() + ", loading " + bossGear.isLoading() + ", window open " + bossGear.isOpen()
			+ " | farm: route " + (farmRun.getRoute() == null ? "none" : farmRun.getRoute().getId()) + ", setting " + config.farmRunBankFilter()
			+ ", running " + farmRun.isRunning() + ", complete " + farmRun.isComplete()
			+ " | Bank Tags on " + bankTagsOn() + ", driver " + driver();
	}

	/** With the boss setup Active in the bank, have it loaded before the bank opens. */
	private void preloadBossGear() {
		int now = client.getTickCount();
		if (config.bossGearBankLayout() && config.bossGearActive() && bossGear.getSet() == null && !bossGear.isLoading() && !bossGear.isLoaded()
				&& client.getGameState() == GameState.LOGGED_IN && now - lastPreloadTick >= PRELOAD_EVERY_TICKS) {
			lastPreloadTick = now;
			lastBossRefresh = System.currentTimeMillis();
			log.debug("Bank layout: loading the boss setup in the background");
			bossGear.refresh();
		}
	}

	/**
	 * Which feature drives the bank now. Each is "active in the bank" by its own setting, and the farm
	 * route only while a farm run is going (started and not complete). When both are, an open boss
	 * gear window wins, then the farm run, then the boss setup.
	 */
	Driver driver() {
		boolean boss = bossGear.isSetActive() && config.bossGearBankLayout();
		boolean farm = farmRun.getRoute() != null && config.farmRunBankFilter() && farmRun.isRunning() && !farmRun.isComplete();
		if (boss && bossGear.isOpen()) {
			return Driver.BOSS;
		}
		if (farm) {
			return Driver.FARM;
		}
		return boss ? Driver.BOSS : null;
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
				log.debug("Bank layout: nothing to lay out any more, back to the normal bank. {}", state());
				release();
			}
			applied = null;
			return;
		}
		if (k.equals(applied)) {
			return;
		}
		log.debug("Bank layout: laying out {} ({})", d, k);
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
		laidOut = null;
		if (!bankTagsOn()) {
			// The layout goes through Bank Tags; without it the bank is left alone (no typing into the bank search).
			log.debug("Bank layout: the Bank Tags plugin is off, the bank is left alone");
			return;
		}
		BankItems bank = new BankItems();
		int[] positions = d == Driver.BOSS ? bossLayout(bossGear.getSet(), bank) : farmLayout(bank);
		layoutManager.saveLayout(new Layout(tag, positions));
		laidOut = positions;
		int options = BankTagsService.OPTION_HIDE_TAG_NAME | BankTagsService.OPTION_ITEMS_NOT_IN_LAYOUT_AT_BOTTOM
			| (config.bankLayoutPositions() ? 0 : BankTagsService.OPTION_NO_LAYOUT);
		bankTags.openBankTag(tag, options);
		if (log.isDebugEnabled()) {
			Layout saved = layoutManager.loadLayout(tag);
			Object active = bankTags instanceof BankTagsPlugin ? ((BankTagsPlugin) bankTags).getActiveLayout() != null : "unknown";
			log.debug("Bank layout: opened tag '{}' ({} positions, exact layout {}, {} bank items); Bank Tags now shows '{}', "
				+ "layout saved {}, layout in use {}, positions {}", tag, positions.length, config.bankLayoutPositions(), bank.ids.size(),
				bankTags.getActiveTag(), saved != null, active, java.util.Arrays.toString(positions));
		}
		verifyUntilTick = client.getTickCount() + VERIFY_TICKS;
	}

	/** Back to the normal bank: our tag closes. */
	private void release() {
		if (isOurTagOpen()) {
			bankTags.closeBankTag();
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
}
