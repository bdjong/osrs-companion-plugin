package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.BankSyncPayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.ScriptID;
import net.runelite.api.GameState;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors potion storage contents and syncs them to the OSRS Companion API.
 * Potion storage is not a regular container — doses are tracked in VarPlayers
 * and must be read via client scripts.
 */
@Slf4j
@Singleton
public class PotionStorageMonitor {
	private static final String SOURCE = "potion_storage";
	private static final int MAX_DOSES = 4;

	private final Client client;
	private final OSRSCompanionConfig config;
	private final SourceSyncer syncer;

	private Set<Integer> potionStoreVarps;
	private boolean needsRebuild;
	/** Re-read on every game tick up to this one (the doses can arrive a tick or two after the bank opens). */
	private int readUntilTick = -1;
	private GameState previousGameState = GameState.UNKNOWN;

	@Inject
	public PotionStorageMonitor(
			Client client,
			OSRSCompanionConfig config,
			OSRSCompanionApiClient apiClient,
			Gson gson,
			ScheduledExecutorService executor) {
		this.client = client;
		this.config = config;
		this.syncer = new SourceSyncer(SOURCE, gson, executor,
				() -> config.syncBankMemory() && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client),
				() -> SyncGuard.getPlayerUsername(client),
				apiClient::sendBankSync);
	}

	/** Item ids active owned-item goals are counting (short debounce for them); set by the plugin. */
	public void setGoalItems(Supplier<Set<Integer>> goalItems) {
		syncer.setGoalItems(goalItems);
	}

	public void stop() {
		syncer.stop();
		potionStoreVarps = null;
		needsRebuild = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (SyncGuard.isLogout(previousGameState, newState)) {
			syncer.flushPending();
		}
		previousGameState = newState;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() == InterfaceID.BANKMAIN) {
			needsRebuild = true;
			readUntilTick = client.getTickCount() + 3;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (readUntilTick >= 0 && client.getTickCount() <= readUntilTick && client.getWidget(InterfaceID.Bankmain.UNIVERSE) != null) {
			needsRebuild = true;
		} else {
			readUntilTick = -1;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (potionStoreVarps != null && potionStoreVarps.contains(event.getVarpId())) {
			needsRebuild = true;
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		if (event.getGroupId() == InterfaceID.BANKMAIN && event.isUnload()) {
			potionStoreVarps = null;
		}
	}

	/**
	 * On client tick, if a potion store varp changed, rebuild potion data and sync.
	 * We process on ClientTick (like RuneLite's PotionStorage) so the script runs
	 * on the client thread before the CS2 VM updates widgets.
	 */
	@Subscribe
	public void onClientTick(ClientTick event) {
		if (!needsRebuild) {
			return;
		}
		needsRebuild = false;

		if (!config.syncBankMemory() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}

		// The varps that change with the storage (to re-read on a change), from its widget once the bank
		// has built it. The storage is read either way: it needn't have been opened.
		if (potionStoreVarps == null) {
			Widget w = client.getWidget(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
			int[] triggers = w == null ? null : w.getVarTransmitTrigger();
			if (triggers != null) {
				potionStoreVarps = new HashSet<>();
				Arrays.stream(triggers).forEach(potionStoreVarps::add);
			}
		}

		Map<Integer, Integer> quantities = new LinkedHashMap<>();
		for (BankSyncPayload.BankItem item : collectPotionItems()) {
			quantities.merge(item.getItemId(), item.getQuantity(), Integer::sum);
		}
		// Nothing read before the storage's varps are known may only mean they haven't arrived yet:
		// never clear what the website has on that.
		if (quantities.isEmpty() && potionStoreVarps == null) {
			return;
		}
		syncer.submit(quantities, false);
	}

	/**
	 * Reads all potions from potion storage using game enums and client scripts.
	 * Each potion is stored as its 4-dose item ID (or highest available dose for mixes).
	 */
	private List<BankSyncPayload.BankItem> collectPotionItems() {
		List<BankSyncPayload.BankItem> items = new ArrayList<>();

		EnumComposition potions = client.getEnum(EnumID.POTIONSTORE_POTIONS);
		EnumComposition unfinished = client.getEnum(EnumID.POTIONSTORE_UNFINISHED_POTIONS);

		for (EnumComposition enumComp : new EnumComposition[]{potions, unfinished}) {
			for (int potionEnumId : enumComp.getIntVals()) {
				EnumComposition potionEnum = client.getEnum(potionEnumId);

				client.runScript(ScriptID.POTIONSTORE_DOSES, potionEnumId);
				int totalDoses = client.getIntStack()[0];

				if (totalDoses <= 0) {
					continue;
				}

				// Find the highest dose variant available for this potion
				int maxDose = findMaxDose(potionEnum);
				int itemId = potionEnum.getIntValue(maxDose);
				int quantity = totalDoses / maxDose;
				int remainderDoses = totalDoses % maxDose;

				if (quantity > 0) {
					items.add(new BankSyncPayload.BankItem(itemId, quantity));
				}

				// Add remainder as lower-dose item if applicable
				if (remainderDoses > 0) {
					int remainderItemId = potionEnum.getIntValue(remainderDoses);
					items.add(new BankSyncPayload.BankItem(remainderItemId, 1));
				}
			}
		}

		int vials = client.getVarpValue(VarPlayerID.POTIONSTORE_VIALS);
		if (vials > 0) {
			items.add(new BankSyncPayload.BankItem(ItemID.VIAL_EMPTY, vials));
		}

		return items;
	}

	/** Finds the highest dose key (4, 3, 2, or 1) that has a valid item ID in the enum. */
	private int findMaxDose(EnumComposition potionEnum) {
		for (int dose = MAX_DOSES; dose >= 1; dose--) {
			int itemId = potionEnum.getIntValue(dose);
			if (itemId > 0) {
				return dose;
			}
		}
		return 1;
	}
}
