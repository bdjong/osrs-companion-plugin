package com.osrscompanion;

import com.osrscompanion.model.BossGear;
import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.model.SlayerGear;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import net.runelite.client.util.Text;

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
 * <p>The Slayer helper's picked setup (Slayer gear window) is laid out the same way, its worn gear only.
 *
 * <p>Only one drives the bank at a time: an open Boss gear / Slayer gear window first, then the farm
 * run, then the boss setup, then the slayer setup.
 *
 * <p>The "Bank Tag Layouts" hub plugin lays out any open tag itself when its "Enable layout by
 * default" is on: right after Bank Tags draws our layout it packs every item from the top again, and
 * may save that as its own layout for the tag. A plugin may not change another plugin's settings, so we
 * only read them ({@link #hubLayoutConflict}): our tag still opens (the bank shows only the needed
 * items, in its order) and a chat message once tells the player which of its settings gives our layout.
 */
@Slf4j
@Singleton
public class BankLayoutPrep {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "bank-layout";

	static final String BOSS_TAG = "osrs companion boss gear";
	static final String FARM_TAG = "osrs companion farm run";
	static final String SLAYER_TAG = "osrs companion slayer gear";

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final FarmRunManager farmRun;
	private final BossGearManager bossGear;
	private final SlayerHelperManager slayer;
	private final OSRSCompanionConfig config;
	private final BankTagsService bankTags;
	private final TagManager tagManager;
	private final LayoutManager layoutManager;
	private final PluginManager pluginManager;
	private final ConfigManager configManager;

	/**
	 * The "Bank Tag Layouts" hub plugin (read only): its class, its config group, a tag's own layout under
	 * "layout_" + tag ("DISABLED" = it leaves the tag alone) and its "Enable layout by default" switch.
	 */
	static final String HUB_LAYOUTS_PLUGIN = "com.banktaglayouts.BankTagLayoutsPlugin";
	private static final String HUB_LAYOUTS_GROUP = "banktaglayouts";
	private static final String HUB_LAYOUT_DISABLED = "DISABLED";
	private static final String HUB_LAYOUT_BY_DEFAULT = "layoutEnabledByDefault";

	/** Whether the "turn Bank Tags on" hint was shown this session (once is enough). */
	private boolean bankTagsHintShown;
	/** Whether the "Bank Tag Layouts takes the layout over" hint was shown this session. */
	private boolean hubLayoutsHintShown;

	/** What was put in the open bank ("boss:..." / "farm:..."), so it is redone only when that changes. Client thread. */
	private String applied;

	enum Driver {
		BOSS, FARM, SLAYER
	}

	/**
	 * The "active in the bank" settings: only one may be on at a time, turning one on turns the others
	 * off. The first wins when more than one is on at start up. Add a new bank layout's key here.
	 */
	private final Map<String, java.util.function.BooleanSupplier> bankLayoutSettings = new LinkedHashMap<>();

	/**
	 * Set when the player clicks another bank tab / view (a vanilla tab, View all items, a Bank Tags tag
	 * tab, the search, the potion store) while the bank is open: the bank is then left alone, no layout
	 * or retry, until it is closed and opened again. Client thread.
	 */
	private boolean userLeft;

	@Inject
	public BankLayoutPrep(Client client, ClientThread clientThread, ItemManager itemManager, FarmRunManager farmRun,
		BossGearManager bossGear, SlayerHelperManager slayer, OSRSCompanionConfig config, BankTagsService bankTags, TagManager tagManager,
		LayoutManager layoutManager, PluginManager pluginManager, ConfigManager configManager) {
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.farmRun = farmRun;
		this.bossGear = bossGear;
		this.slayer = slayer;
		this.config = config;
		this.bankTags = bankTags;
		this.tagManager = tagManager;
		this.layoutManager = layoutManager;
		this.pluginManager = pluginManager;
		this.configManager = configManager;
		bankLayoutSettings.put("bossGearBankLayout", config::bossGearBankLayout);
		bankLayoutSettings.put("farmRunBankFilter", config::farmRunBankFilter);
		bankLayoutSettings.put("slayerGearBankLayout", config::slayerGearBankLayout);
	}

	/** Registers the tags; their items follow the active set / route / slayer setup live. */
	public void startUp() {
		// Bank Tags reads its tags on the client thread.
		Guard.invoke(clientThread, GUARD_FEATURE, () -> {
			tagManager.registerTag(BOSS_TAG, bossGear::needs);
			tagManager.registerTag(FARM_TAG, farmRun::needs);
			tagManager.registerTag(SLAYER_TAG, slayer::needs);
		});
		// Both used to be on by default: keep only the first one that is on.
		String first = null;
		for (Map.Entry<String, java.util.function.BooleanSupplier> e : bankLayoutSettings.entrySet()) {
			if (e.getValue().getAsBoolean()) {
				if (first == null) {
					first = e.getKey();
				} else {
					log.debug("Bank layout: '{}' and '{}' are both on, only one may be: turning '{}' off", first, e.getKey(), e.getKey());
					configManager.setConfiguration(OSRSCompanionConfig.CONFIG_GROUP, e.getKey(), false);
				}
			}
		}
	}

	/** Turning one bank layout setting on (side panel or config panel) turns the others off. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (!OSRSCompanionConfig.CONFIG_GROUP.equals(event.getGroup()) || !bankLayoutSettings.containsKey(event.getKey())
					|| !"true".equals(event.getNewValue())) {
				return;
			}
			for (Map.Entry<String, java.util.function.BooleanSupplier> e : bankLayoutSettings.entrySet()) {
				if (!e.getKey().equals(event.getKey()) && e.getValue().getAsBoolean()) {
					log.debug("Bank layout: '{}' turned on, turning '{}' off (only one bank layout at a time)", event.getKey(), e.getKey());
					configManager.setConfiguration(OSRSCompanionConfig.CONFIG_GROUP, e.getKey(), false);
				}
			}
		});
	}

	/** A click on another bank tab / view: the bank is left as the player chose until it is reopened. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (userLeft || (event.getParam1() >>> 16) != InterfaceID.BANKMAIN || client.getWidget(InterfaceID.Bankmain.UNIVERSE) == null) {
				return;
			}
			String option = event.getMenuOption() == null ? "" : Text.removeTags(event.getMenuOption());
			boolean tabClick = option.startsWith("View tab") || option.equals("View all items") || option.startsWith("View tag tab")
				|| event.getParam1() == InterfaceID.Bankmain.SEARCH || event.getParam1() == InterfaceID.Bankmain.POTIONSTORE_BUTTON;
			if (tabClick) {
				userLeft = true;
				waitingForBuild = false;
				log.debug("Bank layout: you clicked '{}' in the bank, leaving the bank alone until it is reopened (our layout applied: {})",
					option, applied != null);
			}
		});
	}

	/** What the "Bank Tag Layouts" hub plugin would do to our tag. */
	enum HubConflict {
		/** Nothing: not installed / off, or it leaves the tag alone. */
		NONE,
		/** Its "Enable layout by default" is on: it would pack the tag and save that as its own layout. */
		BY_DEFAULT,
		/** It already saved its own layout for the tag (an earlier session), which it keeps using. */
		SAVED
	}

	/** Whether the "Bank Tag Layouts" hub plugin would lay out (pack) our tag over the Bank Tags layout. Reads its settings only. */
	HubConflict hubLayoutConflict(String tag) {
		if (!hubLayoutsPluginActive()) {
			return HubConflict.NONE;
		}
		return hubConflict(configManager.getConfiguration(HUB_LAYOUTS_GROUP, "layout_" + tag),
			configManager.getConfiguration(HUB_LAYOUTS_GROUP, HUB_LAYOUT_BY_DEFAULT));
	}

	/** The conflict from its saved layout for our tag and its "Enable layout by default" value (pure, for tests). */
	static HubConflict hubConflict(String savedLayout, String byDefault) {
		if (HUB_LAYOUT_DISABLED.equals(savedLayout)) {
			return HubConflict.NONE;
		}
		if (savedLayout != null && !savedLayout.isEmpty()) {
			return HubConflict.SAVED;
		}
		return "true".equals(byDefault) ? HubConflict.BY_DEFAULT : HubConflict.NONE;
	}

	private boolean hubLayoutsPluginActive() {
		for (Plugin p : pluginManager.getPlugins()) {
			if (HUB_LAYOUTS_PLUGIN.equals(p.getClass().getName())) {
				return pluginManager.isPluginActive(p);
			}
		}
		return false;
	}

	/** Tells the player once per session how to stop "Bank Tag Layouts" from taking our layout over. */
	private void hubLayoutsHint(HubConflict conflict) {
		if (hubLayoutsHintShown) {
			return;
		}
		hubLayoutsHintShown = true;
		String fix = conflict == HubConflict.BY_DEFAULT
			? "switch off 'Enable layout by default' in the Bank Tag Layouts plugin's settings and open your bank again."
			: "turn the Bank Tag Layouts plugin off: it saved its own order for this view.";
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff9040>OSRS Companion: your gear is shown in the order of the Bank Tag Layouts plugin. For our neater layout (worn gear,"
				+ " rune pouch and inventory in their slots), " + fix + "</col>", null);
	}

	public void shutDown() {
		// A clean-up: runs even when the feature was turned off, so no tag stays registered.
		clientThread.invoke(Guard.wrapCleanup(GUARD_FEATURE, () -> {
			if (isOurTagOpen()) {
				bankTags.closeBankTag();
			}
			tagManager.unregisterTag(BOSS_TAG);
			tagManager.unregisterTag(FARM_TAG);
			tagManager.unregisterTag(SLAYER_TAG);
			// The layouts are rebuilt on every bank open: none left in the Bank Tags config.
			layoutManager.removeLayout(BOSS_TAG);
			layoutManager.removeLayout(FARM_TAG);
			layoutManager.removeLayout(SLAYER_TAG);
			applied = null;
			userLeft = false;
		}));
	}

	// ---- When ---------------------------------------------------------------------

	/**
	 * Opening our tag right as the bank opens does not always stick: Bank Tags and the server's tab
	 * sync can still put the whole bank back while our tag counts as open. So for a few ticks after the
	 * bank opens (and after each apply) we check what the bank actually shows, and when it shows items
	 * the set / route does not need, close and open our layout again, a few times at most. A bank tab /
	 * view the player clicks (see {@link #userLeft}) stops all of this until the bank is reopened.
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
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == InterfaceID.BANKMAIN) {
				applied = null;
				retries = 0;
				userLeft = false;
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
				if (config.slayerGearBankLayout() && slayer.getTask() != null) {
					slayer.refreshIfStale();
				}
				verifyUntilTick = client.getTickCount() + VERIFY_TICKS;
				// Not yet: opening our tag before the bank has been built leaves Bank Tags filtering without
				// drawing the layout. The first build (or two ticks, whichever comes first) lays it out.
				waitingForBuild = true;
				bankOpenedTick = client.getTickCount();
			}
		});
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getScriptId() == ScriptID.BANKMAIN_BUILD && waitingForBuild && !userLeft) {
				waitingForBuild = false;
				log.debug("Bank layout: bank built, laying it out");
				Guard.invokeLater(clientThread, GUARD_FEATURE, this::update);
			}
		});
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == InterfaceID.BANKMAIN) {
				applied = null;
				if (userLeft) {
					log.debug("Bank layout: bank closed, the next bank open is laid out again");
				}
				userLeft = false;
			}
		});
	}

	/** A window opened or closed, or a set / route (re)loaded, while the bank is open. */
	@Subscribe
	public void onGameTick(GameTick tick) {
		Guard.run(GUARD_FEATURE, () -> {
			preloadBossGear();
			if (client.getWidget(InterfaceID.Bankmain.UNIVERSE) == null || userLeft) {
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
		});
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
			if (!needs(d, item)) {
				log.debug("Bank layout: the bank shows item {} the {} does not need", item, d);
				return false;
			}
		}
		return true;
	}

	private boolean needs(Driver d, int item) {
		switch (d) {
			case BOSS:
				return bossGear.needs(item);
			case SLAYER:
				return slayer.needs(item);
			default:
				return farmRun.needs(item);
		}
	}

	/** Everything that decides the layout, for the debug log. */
	private String state() {
		BossGear set = bossGear.getSet();
		return "boss: set " + (set == null ? "none" : set.getId() + " " + set.getBossName()) + ", active " + config.bossGearActive()
			+ ", bank layout setting " + config.bossGearBankLayout() + ", loading " + bossGear.isLoading() + ", window open " + bossGear.isOpen()
			+ " | farm: route " + (farmRun.getRoute() == null ? "none" : farmRun.getRoute().getId()) + ", setting " + config.farmRunBankFilter()
			+ ", running " + farmRun.isRunning() + ", complete " + farmRun.isComplete()
			+ " | slayer: setup " + (slayer.getGearSet() == null ? "none" : slayer.getGearSet().getKey()) + ", setting " + config.slayerGearBankLayout()
			+ ", window open " + slayer.isGearOpen()
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
	 * route only while a farm run is going (started and not complete), the slayer setup only with a
	 * task. When more are, an open boss gear / slayer gear window wins, then the farm run, then the
	 * boss setup, then the slayer setup.
	 */
	Driver driver() {
		boolean boss = bossGear.isSetActive() && config.bossGearBankLayout();
		boolean farm = farmRun.getRoute() != null && config.farmRunBankFilter() && farmRun.isRunning() && !farmRun.isComplete();
		boolean slayerGear = config.slayerGearBankLayout() && slayer.getTask() != null && slayer.getGearSet() != null;
		return pick(boss, bossGear.isOpen(), farm, slayerGear, slayer.isGearOpen());
	}

	/** Which one drives the bank, given which are on and which windows are open (see {@link #driver()}). */
	static Driver pick(boolean boss, boolean bossOpen, boolean farm, boolean slayerGear, boolean slayerOpen) {
		if (boss && bossOpen) {
			return Driver.BOSS;
		}
		if (slayerGear && slayerOpen) {
			return Driver.SLAYER;
		}
		if (farm) {
			return Driver.FARM;
		}
		if (boss) {
			return Driver.BOSS;
		}
		return slayerGear ? Driver.SLAYER : null;
	}

	/** Changes with the driver and whenever its data is reloaded (a new object), so a switched set is laid out again. */
	private String key(Driver d) {
		if (d == Driver.BOSS) {
			BossGear s = bossGear.getSet();
			return "boss:" + s.getId() + ":" + System.identityHashCode(s) + ":" + config.bankLayoutPositions();
		}
		if (d == Driver.SLAYER) {
			SlayerGear.Set s = slayer.getGearSet();
			return "slayer:" + s.getKey() + ":" + System.identityHashCode(s) + ":" + config.bankLayoutPositions();
		}
		FarmRoute r = farmRun.getRoute();
		return "farm:" + r.getId() + ":" + System.identityHashCode(r) + ":" + farmRun.inventory().size() + ":" + config.bankLayoutPositions();
	}

	private void update() {
		if (client.getWidget(InterfaceID.Bankmain.UNIVERSE) == null) {
			return;
		}
		if (userLeft) {
			// A reload (new set / route object) or a setting change must not take the bank back from the tab the player picked.
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
		return BOSS_TAG.equals(tag) || FARM_TAG.equals(tag) || SLAYER_TAG.equals(tag);
	}

	private void apply(Driver d) {
		String tag = d == Driver.BOSS ? BOSS_TAG : d == Driver.SLAYER ? SLAYER_TAG : FARM_TAG;
		laidOut = null;
		if (!bankTagsOn()) {
			// The layout goes through Bank Tags; without it the bank is left alone (no typing into the bank search).
			log.debug("Bank layout: the Bank Tags plugin is off, the bank is left alone");
			if (!bankTagsHintShown) {
				bankTagsHintShown = true;
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff9040>OSRS Companion: turn on RuneLite's Bank Tags plugin to have your "
					+ (d == Driver.BOSS ? "boss gear" : d == Driver.SLAYER ? "slayer gear" : "farm run") + " laid out in the bank.</col>", null);
			}
			return;
		}
		HubConflict conflict = hubLayoutConflict(tag);
		if (conflict != HubConflict.NONE) {
			// "Bank Tag Layouts" re-sorts our tag into its own order: the bank still shows only the needed
			// items, just not in our layout. Tell the player once which of its settings gives ours.
			log.debug("Bank layout: Bank Tag Layouts re-sorts '{}' ({}), the items show in its order", tag, conflict);
			hubLayoutsHint(conflict);
		}
		BankItems bank = new BankItems();
		int[] positions = d == Driver.BOSS ? bossLayout(bossGear.getSet(), bank) : d == Driver.SLAYER ? slayerLayout(slayer.getGearSet(), bank)
			: farmLayout(bank);
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

	/**
	 * The boss set's worn gear, inventory and rune pouch in their places; an item you don't own (red in the
	 * window) leaves its place empty. Below the inventory: the weapon's ammo / spell options (as many as the
	 * setting says) and what recharges the charged items.
	 */
	private int[] bossLayout(BossGear set, BankItems bank) {
		// Before your items are synced nothing counts as owned: everything shows then.
		boolean known = set.isSynced();
		Map<String, Integer> worn = new LinkedHashMap<>();
		for (BossGear.Slot s : set.getEquipment()) {
			if (!"special".equals(s.getSlot()) && (!known || s.isOwned())) {
				worn.put(s.getSlot(), bank.resolve(s.getIds()));
			}
		}
		List<Integer> inv = new ArrayList<>();
		for (BossGear.Item it : set.getInventory()) {
			inv.add(it == null || known && !it.isOwned() ? -1 : bank.resolve(it.getIds()));
		}
		List<Integer> pouch = new ArrayList<>();
		for (BossGear.Item it : set.getPouch()) {
			pouch.add(!known || it.isOwned() ? bank.resolve(it.getIds()) : -1);
		}
		// The extras start on the row under the inventory.
		while (inv.size() < 28) {
			inv.add(-1);
		}
		Set<Integer> placed = new HashSet<>();
		for (int id : inv) {
			addBase(placed, id);
		}
		for (int id : worn.values()) {
			addBase(placed, id);
		}
		List<Integer> extras = new ArrayList<>();
		for (int id : set.supplyItems(config.slayerGearSupplyOptions())) {
			extras.add(bank.resolveOne(id));
		}
		for (com.osrscompanion.model.SlayerGear.Bring b : set.getBring()) {
			extras.add(bank.resolve(b.getItems()));
		}
		for (int id : extras) {
			if (id > 0 && placed.add(base(id))) {
				inv.add(id);
			}
		}
		BossGear.Slot special = set.slot("special");
		int spec = special == null || known && !special.isOwned() ? -1 : bank.resolve(special.getIds());
		// One item is brought once: the Alternate weapon is left out when the inventory or the weapon
		// slot has it, the special attack weapon when any of those has it (one blowpipe does all three).
		Set<Integer> brought = new HashSet<>();
		for (int id : inv) {
			addBase(brought, id);
		}
		addBase(brought, worn.getOrDefault("weapon", -1));
		Integer sw = worn.get("switch");
		if (sw != null && sw > 0 && brought.contains(base(sw))) {
			worn.remove("switch");
			worn.remove("switch_shield");
			sw = null;
		}
		addBase(brought, sw == null ? -1 : sw);
		if (spec > 0 && brought.contains(base(spec))) {
			spec = -1;
		}
		return BankLayouts.build(worn, inv, pouch, spec);
	}

	/** The item without its charges / ornament (two versions of one item count as the same). */
	private static int base(int itemId) {
		return ItemVariationMapping.map(itemId);
	}

	private static void addBase(Set<Integer> into, int itemId) {
		if (itemId > 0) {
			into.add(base(itemId));
		}
	}

	/**
	 * The slayer setup's worn gear in the doll's places, and in the inventory's places its ammo / spell
	 * options (darts, arrows or bolts, the spells' runes), best first, as many as the setting says, then
	 * its boost (potion, heart) and what recharges its charged items (runes, scales, ether...).
	 */
	private int[] slayerLayout(SlayerGear.Set set, BankItems bank) {
		List<Integer> supplies = new ArrayList<>();
		for (int id : set.supplyItems(config.slayerGearSupplyOptions())) {
			supplies.add(bank.resolveOne(id));
		}
		for (SlayerGear.Bring b : set.getBring()) {
			int id = bank.resolve(b.getItems());
			if (id > 0 && !supplies.contains(id)) {
				supplies.add(id);
			}
		}
		return BankLayouts.build(slayerWorn(set, bank::resolveOne), supplies, Collections.emptyList(), -1);
	}

	/** Worn slot name to item (resolved to the version in the bank), the first piece per slot; pieces without an item are left out. */
	static Map<String, Integer> slayerWorn(SlayerGear.Set set, java.util.function.IntUnaryOperator resolve) {
		Map<String, Integer> worn = new LinkedHashMap<>();
		for (SlayerGear.Piece p : set.getEquipment()) {
			if (p.getSlot() != null && p.getItem() > 0) {
				worn.putIfAbsent(p.getSlot(), resolve.applyAsInt(p.getItem()));
			}
		}
		return worn;
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

		int resolveOne(int itemId) {
			return resolve(Collections.singletonList(itemId));
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
