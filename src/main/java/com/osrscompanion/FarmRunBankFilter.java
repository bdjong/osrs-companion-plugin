package com.osrscompanion;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.bank.BankSearch;

/**
 * Opening the bank during a farm run searches it for "farm run": only what the route needs
 * shows (like an inventory setup). Clearing the search shows everything again.
 */
@Singleton
public class FarmRunBankFilter {
	static final String SEARCH = "farm run";

	private final Client client;
	private final ClientThread clientThread;
	private final BankSearch bankSearch;
	private final ItemManager itemManager;
	private final FarmRunManager manager;
	private final OSRSCompanionConfig config;

	@Inject
	public FarmRunBankFilter(Client client, ClientThread clientThread, BankSearch bankSearch, ItemManager itemManager,
			FarmRunManager manager, OSRSCompanionConfig config) {
		this.client = client;
		this.clientThread = clientThread;
		this.bankSearch = bankSearch;
		this.itemManager = itemManager;
		this.manager = manager;
		this.config = config;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() != InterfaceID.BANKMAIN || !config.farmRunBankFilter() || manager.getRoute() == null) {
			return;
		}
		if (!manager.isOpen() && !(manager.isRunning() && !manager.isComplete())) {
			return;
		}
		clientThread.invokeLater(() -> {
			bankSearch.initSearch();
			client.setVarcStrValue(VarClientID.MESLAYERINPUT, SEARCH);
			bankSearch.layoutBank();
		});
	}

	/** After the game's and Bank Tags' own matching: for our search, only the route's items match. */
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
		if (intSize < 2 || !(search instanceof String) || !SEARCH.equalsIgnoreCase(((String) search).trim())) {
			return;
		}
		int itemId = itemManager.canonicalize(ints[intSize - 1]);
		ints[intSize - 2] = manager.needs(itemId) ? 1 : 0;
	}
}
