package com.osrscompanion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.reflect.TypeToken;
import com.osrscompanion.model.BossGear;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import org.junit.Before;
import org.junit.Test;

/** Golden renders of the boss gear window, with the mouse on it (see {@link UiKit}, {@link WindowDriver}). */
public class BossGearWindowGoldenTest {
	private static final int W = 1000;
	private static final int H = 700;

	static final String SET_JSON = "{\"id\":31,\"boss\":\"vorkath\",\"boss_name\":\"Vorkath\",\"style\":\"Ranged (Dragon hunter crossbow)\","
		+ "\"inventory_label\":\"Inventory (wiki, owned items)\",\"synced\":true,\"url\":\"https://oldschool.runescape.wiki/w/Vorkath/Strategies\",\"two_handed\":false,"
		+ "\"equipment\":["
		+ " {\"slot\":\"head\",\"item\":11664,\"name\":\"Void ranger helm\",\"owned\":true,\"status\":\"bis\"},"
		+ " {\"slot\":\"cape\",\"item\":22109,\"name\":\"Ava's assembler\",\"owned\":false,\"status\":\"missing\"},"
		+ " {\"slot\":\"neck\",\"item\":19547,\"name\":\"Necklace of anguish\",\"owned\":true,\"status\":\"partial\",\"rank\":2},"
		+ " {\"slot\":\"ammo\",\"item\":21944,\"name\":\"Ruby dragon bolts (e)\",\"owned\":true,\"qty\":180,\"status\":\"bis\"},"
		+ " {\"slot\":\"weapon\",\"item\":21012,\"name\":\"Dragon hunter crossbow\",\"owned\":true,\"status\":\"bis\",\"need\":\"Vorkath is a dragon: +30% accuracy and damage.\"},"
		+ " {\"slot\":\"body\",\"item\":8839,\"name\":\"Elite void top\",\"owned\":true,\"status\":\"alt\",\"alt_for\":\"Armadyl chestplate\"},"
		+ " {\"slot\":\"shield\",\"item\":22002,\"name\":\"Dragonfire ward\",\"owned\":true,\"status\":\"bis\",\"need\":\"Dragonfire protection.\"},"
		+ " {\"slot\":\"legs\",\"item\":8840,\"name\":\"Elite void robe\",\"owned\":true,\"status\":\"bis\"},"
		+ " {\"slot\":\"hands\",\"item\":8842,\"name\":\"Void knight gloves\",\"owned\":true,\"status\":\"bis\"},"
		+ " {\"slot\":\"feet\",\"item\":19921,\"name\":\"Pegasian boots\",\"owned\":false,\"status\":\"unknown\"},"
		+ " {\"slot\":\"ring\",\"item\":11771,\"name\":\"Archers ring (i)\",\"owned\":true,\"status\":\"bis\"}"
		+ "],"
		+ "\"inventory\":["
		+ " {\"item\":12791,\"name\":\"Rune pouch\",\"owned\":true},"
		+ " {\"item\":2444,\"name\":\"Ranging potion(4)\",\"owned\":true,\"qty\":1},"
		+ " {\"item\":22461,\"name\":\"Extended antifire(4)\",\"owned\":true,\"qty\":2},"
		+ " {\"item\":2434,\"name\":\"Prayer potion(4)\",\"owned\":true,\"qty\":3},"
		+ " {\"item\":385,\"name\":\"Shark\",\"owned\":true,\"qty\":16},"
		+ " {\"item\":11905,\"name\":\"Trident of the seas\",\"owned\":true,\"status\":\"alt\",\"alt_for\":\"Salve amulet\"}"
		+ "],"
		+ "\"pouch\":[{\"item\":560,\"name\":\"Death rune\",\"owned\":true,\"qty\":500},{\"item\":565,\"name\":\"Blood rune\",\"owned\":true,\"qty\":1000},{\"item\":566,\"name\":\"Soul rune\",\"owned\":false,\"qty\":200}],"
		+ "\"spellbook\":\"arceuus\","
		+ "\"supplies\":{\"kind\":\"ammo\",\"options\":[{\"name\":\"Ruby dragon bolts (e)\",\"items\":[21944]},{\"name\":\"Diamond dragon bolts (e)\",\"items\":[21946]}]},"
		+ "\"bring\":[{\"kind\":\"teleport\",\"name\":\"Fremennik boots / Rellekka teleport\",\"items\":[13132]}]}";

	private final UiKit kit = new UiKit();
	private final Client client = mock(Client.class);
	private final BossGearManager manager = mock(BossGearManager.class);
	private final SuppliesCheck supplies = mock(SuppliesCheck.class);
	private BossGearWindow window;

	static BossGear set() {
		return UiSamples.GSON.fromJson(SET_JSON, BossGear.class);
	}

	@Before
	public void setUp() {
		when(client.getCanvasWidth()).thenReturn(W);
		when(client.getCanvasHeight()).thenReturn(H);
		when(manager.isOpen()).thenReturn(true);
		when(manager.isLoaded()).thenReturn(true);
		when(manager.getSet()).thenReturn(set());
		when(manager.isSetActive()).thenReturn(true);
		when(manager.getMode()).thenReturn(BossGear.MODE_OWNED);
		List<BossGear.SetSummary> sets = UiSamples.GSON.fromJson("[{\"id\":31,\"boss_name\":\"Vorkath\",\"style\":\"Ranged\",\"active\":true},"
			+ "{\"id\":44,\"boss_name\":\"Zulrah\",\"style\":\"Magic + ranged\",\"custom\":true,\"name\":\"My Zulrah\"}]",
			new TypeToken<List<BossGear.SetSummary>>() { }.getType());
		when(manager.getSets()).thenReturn(sets);
		List<BossGear.CatalogBoss> catalog = UiSamples.GSON.fromJson("[{\"key\":\"vorkath\",\"name\":\"Vorkath\",\"setups\":[\"Ranged\",\"Ranged (Dragon hunter crossbow)\"]},"
			+ "{\"key\":\"zulrah\",\"name\":\"Zulrah\",\"setups\":[\"Magic + ranged\"]},{\"key\":\"cerberus\",\"name\":\"Cerberus\",\"setups\":[\"Melee\"]}]",
			new TypeToken<List<BossGear.CatalogBoss>>() { }.getType());
		when(manager.getCatalog()).thenReturn(catalog);
		when(manager.carried(any(BossGear.Item.class))).thenAnswer(a -> ((BossGear.Item) a.getArgument(0)).getItem() % 3 == 0 ? 0 : 1);
		Map<Integer, Integer> needs = new HashMap<>();
		needs.put(385, 16);
		needs.put(2434, 3);
		when(manager.inventoryNeeds()).thenReturn(needs);
		when(manager.slotTooltip(any(BossGear.Item.class), anyInt(), anyBoolean())).thenAnswer(a -> {
			BossGear.Item it = a.getArgument(0);
			return it.getName() + " x" + a.getArgument(1) + (Boolean.TRUE.equals(a.getArgument(2)) ? "" : " (bank not synced)");
		});
		window = new BossGearWindow(client, manager, kit.itemManager(), supplies, mock(ChatboxPanelManager.class), new ClientThread());
	}

	private UiKit.Rendered render() {
		kit.render(window, W, H);
		return kit.render(window, W, H);
	}

	@Test
	public void activeSetup() {
		UiKit.golden("boss-window-active", render());
	}

	@Test
	public void hoverOnAWornSlot() {
		render();
		WindowDriver.move(window, WindowDriver.area(window, "Dragon hunter crossbow").centre());
		UiKit.golden("boss-window-slot-tooltip", kit.render(window, W, H));
	}

	@Test
	public void inactiveSetupBestInSlotMode() {
		when(manager.isSetActive()).thenReturn(false);
		when(manager.getMode()).thenReturn(BossGear.MODE_BIS);
		UiKit.golden("boss-window-inactive-bis", render());
	}

	@Test
	public void twoHandedWeaponNotSynced() {
		BossGear twoHanded = UiSamples.GSON.fromJson(SET_JSON.replace("\"two_handed\":false", "\"two_handed\":true").replace("\"synced\":true", "\"synced\":false"), BossGear.class);
		when(manager.getSet()).thenReturn(twoHanded);
		when(manager.getMode()).thenReturn(BossGear.MODE_ALTERNATIVES);
		UiKit.golden("boss-window-two-handed", render());
	}

	@Test
	public void buildSwitcherOpen() {
		render();
		WindowDriver.click(window, WindowDriver.area(window, "Load a wiki preset").centre());
		UiKit.golden("boss-window-switcher", kit.render(window, W, H));
	}

	@Test
	public void switcherWikiPresetsWithABossOpen() {
		render();
		WindowDriver.click(window, WindowDriver.area(window, "Load a wiki preset").centre());
		WindowDriver.set(window, "tab", WindowDriver.get(window, "TAB_WIKI"));
		WindowDriver.set(window, "expandedBoss", "vorkath");
		UiKit.golden("boss-window-switcher-wiki", kit.render(window, W, H));
	}

	@Test
	public void switcherCustomBuilds() {
		render();
		WindowDriver.click(window, WindowDriver.area(window, "Load a wiki preset").centre());
		WindowDriver.set(window, "tab", WindowDriver.get(window, "TAB_CUSTOM"));
		UiKit.golden("boss-window-switcher-custom", kit.render(window, W, H));
	}

	@Test
	public void escapeClosesTheSwitcher() {
		render();
		WindowDriver.click(window, WindowDriver.area(window, "Load a wiki preset").centre());
		WindowDriver.key(window, KeyEvent.VK_ESCAPE);
		UiKit.golden("boss-window-active-after-escape", kit.render(window, W, H));
	}

	@Test
	public void noBuildYetPickOne() {
		when(manager.getSet()).thenReturn(null);
		UiKit.golden("boss-window-pick", render());
	}

	@Test
	public void noBuildPickerOpen() {
		when(manager.getSet()).thenReturn(null);
		render();
		WindowDriver.click(window, WindowDriver.area(window, "Load a wiki preset").centre());
		UiKit.golden("boss-window-pick-open", kit.render(window, W, H));
	}

	@Test
	public void loading() {
		when(manager.getSet()).thenReturn(null);
		when(manager.getCatalog()).thenReturn(Collections.emptyList());
		when(manager.isLoaded()).thenReturn(false);
		when(manager.isLoading()).thenReturn(true);
		UiKit.golden("boss-window-loading", render());
	}

	@Test
	public void error() {
		when(manager.getSet()).thenReturn(null);
		when(manager.getCatalog()).thenReturn(Collections.emptyList());
		when(manager.getError()).thenReturn("Could not reach OSRS Companion (timeout)");
		UiKit.golden("boss-window-error", render());
	}

	@Test
	public void smallScreen() {
		when(client.getCanvasWidth()).thenReturn(765);
		when(client.getCanvasHeight()).thenReturn(503);
		kit.render(window, 765, 503);
		UiKit.golden("boss-window-small", kit.render(window, 765, 503));
	}
}
