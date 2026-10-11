package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Syncs the bank proper, the inventory and the worn gear as bank-memory
 * sources {@code bank}, {@code inventory} (the INV container only) and
 * {@code worn} (the WORN container). All go through one
 * {@link SourceSyncer}: inventory changes while skilling are collapsed on
 * the default cadence, changes to an item an owned-item goal is counting go
 * up on the short one, and logout always flushes. The bank container only
 * updates while the bank is open, so its last-seen contents are cached and
 * re-sent with the inventory (the dedupe makes that free).
 *
 * <p>The website replaces each source it receives, so a source is only read when the read is
 * reliable ({@link CarriedReadGate}): logged in, the player loaded, the containers present, and for
 * the inventory and worn gear both INV and WORN arrived since the last login or hop. The two are read
 * together and always uploaded together ({@link SourceSyncer#linkSources}): the website reads an
 * {@code inventory} without {@code worn} as an older plugin's inventory + worn gear. Uploading never depends on
 * this feature's breaker: the syncer's timer, the logout flush and the bank-close flush all run as
 * {@link Guard#SYNC_FEATURE}, which is never turned off, so a change already read is not lost when
 * this feature is turned off.
 *
 * <p>Group Ironman: the shared group storage ({@code InventoryID.INV_GROUP_TEMP}) is synced as the source
 * {@code group_storage} while its interface ({@code InterfaceID.SHARED_BANK}) is open, through the same
 * syncer, and flushed when it closes.
 */
@Slf4j
@Singleton
public class BankMemoryMonitor {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "bank-sync";

	static final String SOURCE_BANK = "bank";
	/** The INV container (older plugins sent INV + WORN merged under this source). */
	static final String SOURCE_INVENTORY = "inventory";
	/** The WORN container: the equipment the player wears. */
	static final String SOURCE_WORN = "worn";
	/** The Group Ironman shared group storage: the group's, synced by whoever opens it (the website shows the newest copy). */
	static final String SOURCE_GROUP_STORAGE = "group_storage";

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final SourceSyncer syncer;
	/** Deposits made where the bank container is never sent (deposit box, bank boat). */
	private final BankDepositLedger deposits;

	private GameState previousGameState = GameState.UNKNOWN;
	/** The group storage interface is open (its container is only read then). */
	private boolean groupStorageOpen;
	/** When INV / WORN / the bank may be read (see the class comment). */
	private final CarriedReadGate gate = new CarriedReadGate();
	/** Inventory + gear as last read, for deposit diffs. */
	private Map<Integer, Integer> lastCarried = new LinkedHashMap<>();

	@Inject
	public BankMemoryMonitor(
			Client client,
			ClientThread clientThread,
			OSRSCompanionConfig config,
			OSRSCompanionApiClient apiClient,
			ItemManager itemManager,
			Gson gson,
			ScheduledExecutorService executor) {
		executor = GuardedExecutor.wrap(GUARD_FEATURE, executor);
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.syncer = new SourceSyncer("bank memory", gson, executor,
				() -> config.syncBankMemory() && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client),
				() -> SyncGuard.getPlayerUsername(client),
				apiClient::sendBankSync);
		this.syncer.setPriorityFlush(apiClient::requestOutboxFlush);
		this.syncer.linkSources(SOURCE_INVENTORY, SOURCE_WORN);
		this.deposits = new BankDepositLedger(() -> Map.of());
	}

	/** Items reached the bank without a bank container being sent (a storage item emptied into a
	 * deposit box, a crew member banking the hold): count them as deposited and push. */
	public void onItemsBankedUnseen(Map<Integer, Integer> contents) {
		deposits.onContainerEmptied(contents);
		Guard.invokeLater(clientThread, GUARD_FEATURE, () -> push(true, false));
	}

	/** Deposit boxes (and the bank boat) open this interface instead of the bank. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX) {
				deposits.onDepositInterface(true);
			}
			if (event.getGroupId() == InterfaceID.SHARED_BANK) {
				groupStorageOpen = true;
				push(true, false);
			}
		});
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX) {
				deposits.onDepositInterface(false);
			}
			if (event.getGroupId() == InterfaceID.SHARED_BANK) {
				groupStorageOpen = false;
			}
		});
		if (event.getGroupId() == InterfaceID.BANKMAIN || event.getGroupId() == InterfaceID.BANK_DEPOSITBOX
			|| event.getGroupId() == InterfaceID.SHARED_BANK) {
			// Leaving the bank: what moved between the bank and the inventory goes up now, not after the
			// debounce, so a client killed (or crashed) a few seconds later cannot lose it. A lost bank
			// upload is what makes the next session's inventory upload look like the items are gone.
			flushPending();
		}
	}

	/**
	 * Sends what is pending now (already read while it was reliable). Used when leaving the bank and
	 * on logout. Runs as {@link Guard#SYNC_FEATURE}, never skipped.
	 */
	public void flushPending() {
		Guard.run(Guard.SYNC_FEATURE, syncer::flushPending);
	}

	/**
	 * Re-reads the bank/inventory/equipment on the client thread and re-pushes
	 * it immediately, even if unchanged. Used by the roadmap panel's
	 * "Sync &amp; refresh" button. The bank container is only populated while
	 * the bank is (or was) open this session; if it is null we simply skip.
	 */
	public void forceSync() {
		Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
			syncer.invalidate();
			push(false, true);
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (SyncGuard.isLogout(previousGameState, newState)) {
			// A final sync, always (even when this feature was turned off): whatever changed since
			// the last upload was captured while logged in. Do not re-read the client here: the
			// player name is already gone and the containers may read empty.
			flushPending();
		}
		Guard.run(GUARD_FEATURE, () -> {
			gate.onGameState(newState);
			if (SyncGuard.isLogout(previousGameState, newState)) {
				groupStorageOpen = false;
				deposits.resetSession();
				lastCarried = new LinkedHashMap<>();
			}
			previousGameState = newState;
			if (newState == GameState.LOGGED_IN) {
				// The containers may have arrived while still loading (nothing was read then): read them
				// now that the player is in game. Unchanged contents are not re-sent.
				push(true, false);
			}
		});
	}

	public void stop() {
		syncer.stop();
	}

	/** Bank, inventory and worn-gear changes all feed the same debounced upload. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			int id = event.getContainerId();
			if (id != InventoryID.BANK && id != InventoryID.INV && id != InventoryID.WORN && id != InventoryID.INV_GROUP_TEMP) {
				return;
			}
			gate.onContainerChanged(id, client.getItemContainer(id) != null);
			push(true, false);
		});
	}

	/**
	 * Reads bank + inventory + equipment, tells the goal tracker, and submits
	 * to the syncer. Must run on the client thread.
	 *
	 * @param inventoryOnlyOk when the bank has not been opened this session,
	 *                        still push just inventory + gear (otherwise a
	 *                        missing bank pushes nothing)
	 * @param immediate       skip the debounce
	 */
	private void push(boolean inventoryOnlyOk, boolean immediate) {
		Map<String, Map<Integer, Integer>> bySource = readSources(inventoryOnlyOk);
		if (bySource == null) {
			return;
		}
		trackDeposits(bySource);
		syncer.submitSources(bySource, immediate);
	}

	/**
	 * Items that left the inventory or gear in a deposit context are banked
	 * even though no bank container was sent. Once the bank proper is read it
	 * holds everything, so the deposits go back to empty alongside it.
	 */
	private void trackDeposits(Map<String, Map<Integer, Integer>> bySource) {
		Map<Integer, Integer> carried = carried(bySource);
		Map<Integer, Integer> removed = new LinkedHashMap<>();
		if (carried != null) {
			// Only a reliable read of the carried items is diffed (no inventory: nothing to compare).
			for (Map.Entry<Integer, Integer> e : lastCarried.entrySet()) {
				int delta = e.getValue() - carried.getOrDefault(e.getKey(), 0);
				if (delta > 0) {
					removed.put(e.getKey(), delta);
				}
			}
			lastCarried = new LinkedHashMap<>(carried);
		}
		boolean changed = false;
		if (bySource.containsKey(SOURCE_BANK)) {
			changed = deposits.onBankRead();
			bySource.put(BankDepositLedger.SOURCE, deposits.contents());
		} else if (deposits.onItemsRemoved(removed, client.getTickCount())) {
			changed = true;
		}
		if (changed || (!deposits.contents().isEmpty() && !bySource.containsKey(BankDepositLedger.SOURCE))) {
			bySource.put(BankDepositLedger.SOURCE, deposits.contents());
		}
	}

	/**
	 * Everything carried (inventory + worn gear summed per item) when both were read, else null. The
	 * deposit ledger diffs this: unequipping and banking a piece of gear is a deposit too. Pure.
	 */
	static Map<Integer, Integer> carried(Map<String, Map<Integer, Integer>> bySource) {
		Map<Integer, Integer> inv = bySource.get(SOURCE_INVENTORY);
		Map<Integer, Integer> worn = bySource.get(SOURCE_WORN);
		if (inv == null || worn == null) {
			return null;
		}
		Map<Integer, Integer> carried = new LinkedHashMap<>(inv);
		worn.forEach((id, q) -> carried.merge(id, q, Integer::sum));
		return carried;
	}

	private Map<String, Map<Integer, Integer>> readSources(boolean inventoryOnlyOk) {
		if (GameModeUtil.isSpecialGameMode(client)) {
			log.debug("Bank sync skipped: special game mode detected");
			return null;
		}
		GameState state = client.getGameState();
		boolean playerLoaded = SyncGuard.getPlayerUsername(client) != null;
		if (!CarriedReadGate.inGame(state, playerLoaded)) {
			// Logging in, hopping, loading or the login screen: the containers may read null or empty.
			return null;
		}
		ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
		if (bankContainer == null && !inventoryOnlyOk) {
			return null;
		}
		Map<String, Map<Integer, Integer>> bySource = new LinkedHashMap<>();
		if (gate.canReadBank(state, playerLoaded, bankContainer != null)) {
			Map<Integer, Integer> bankQuantities = new LinkedHashMap<>();
			ItemCollector.collectBankItems(bankContainer, itemManager, bankQuantities);
			bySource.put(SOURCE_BANK, bankQuantities);
		}
		ItemContainer groupStorage = client.getItemContainer(InventoryID.INV_GROUP_TEMP);
		if (gate.canReadGroupStorage(state, playerLoaded, groupStorage != null, groupStorageOpen)) {
			Map<Integer, Integer> groupQuantities = new LinkedHashMap<>();
			ItemCollector.collectBankItems(groupStorage, itemManager, groupQuantities);
			bySource.put(SOURCE_GROUP_STORAGE, groupQuantities);
		}
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (gate.canReadCarried(state, playerLoaded, inv != null, worn != null)) {
			// Read together, sent together: never one without the other.
			Map<Integer, Integer> invQuantities = new LinkedHashMap<>();
			ItemCollector.collectItems(inv, itemManager, invQuantities);
			Map<Integer, Integer> wornQuantities = new LinkedHashMap<>();
			ItemCollector.collectItems(worn, itemManager, wornQuantities);
			bySource.put(SOURCE_INVENTORY, invQuantities);
			bySource.put(SOURCE_WORN, wornQuantities);
		} else {
			// Not known (yet): leave the server's inventory as it is rather than send a partial one.
			log.debug("Bank sync: inventory/worn not read (containers not both loaded yet)");
		}
		return bySource.isEmpty() ? null : bySource;
	}

}
