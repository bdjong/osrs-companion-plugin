package com.osrscompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.GZIPOutputStream;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The website keeps the storage items' contents now (fish barrel, log basket, gem bag, coal bag, herb sack). This
 * plays random gathering (catches with the item open or closed, full and empty messages, Fill / Empty / Check,
 * the bank and deposit box, the Charges plugin's state, loading screens and logouts) through the plugin's old
 * StorageItemMonitor and the new GameEvents side by side, and writes what GameEvents records and what the old
 * monitor uploaded (each changed tick, and what went into a deposit box) to build/oracle/storage-events.json.gz.
 * It also writes the specs (build/oracle/storage-specs.json) from the same run, so the website's data has the
 * exact item order the plugin iterated.
 */
public class StorageEventsOracleTest {
	private static final String CHARGES = "tictac7x-charges";

	private Map<Integer, String> names;
	private List<OldStorageItemSpec> specs;
	private Random r;
	private int tick;
	private GameState state;
	private final Map<Integer, Integer> inv = new LinkedHashMap<>();
	private final Map<Integer, Integer> worn = new LinkedHashMap<>();
	private final Map<String, String> charges = new HashMap<>();
	private String messageBox;
	private int selectedItem = -1;
	private final List<Object> recorded = new ArrayList<>();
	private final List<Object> uploads = new ArrayList<>();
	private final List<Object> deposits = new ArrayList<>();
	private OldStorageItemMonitor old;
	private GameEvents now;
	private Gson gson = new Gson();

	private ItemContainer container(Map<Integer, Integer> items) {
		ItemContainer c = mock(ItemContainer.class);
		when(c.getItems()).thenAnswer(i -> items.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue())).toArray(Item[]::new));
		return c;
	}

	private Client client() {
		Client c = mock(Client.class);
		when(c.getTickCount()).thenAnswer(i -> tick);
		when(c.getGameState()).thenAnswer(i -> state);
		Player me = mock(Player.class);
		when(me.getName()).thenReturn("Tester");
		when(c.getLocalPlayer()).thenReturn(me);
		when(c.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(c.getItemContainer(anyInt())).thenAnswer(i -> {
			int id = i.getArgument(0);
			return id == InventoryID.INV ? container(inv) : id == InventoryID.WORN ? container(worn) : null;
		});
		Widget box = mock(Widget.class);
		when(box.getText()).thenAnswer(i -> messageBox);
		when(c.getWidget(anyInt())).thenAnswer(i -> (int) i.getArgument(0) == (193 << 16 | 2) ? box : null);
		when(c.getWidget(193, 2)).thenReturn(box);
		Widget selected = mock(Widget.class);
		when(selected.getItemId()).thenAnswer(i -> selectedItem);
		when(c.getSelectedWidget()).thenReturn(selected);
		return c;
	}

	private ItemManager itemManager() {
		ItemManager im = mock(ItemManager.class);
		when(im.canonicalize(anyInt())).thenAnswer(i -> i.getArgument(0));
		when(im.getItemComposition(anyInt())).thenAnswer(i -> {
			ItemComposition comp = mock(ItemComposition.class);
			int id = i.getArgument(0);
			when(comp.getName()).thenReturn(names.getOrDefault(id, "Item " + id));
			return comp;
		});
		return im;
	}

	private ConfigManager configManager() {
		ConfigManager cm = mock(ConfigManager.class);
		when(cm.getConfiguration(eq(CHARGES), anyString())).thenAnswer(i -> charges.get((String) i.getArgument(1)));
		when(cm.getConfigurationKeys(anyString())).thenAnswer(i -> {
			List<String> keys = new ArrayList<>();
			for (String k : charges.keySet()) {
				keys.add(CHARGES + "." + k);
			}
			return keys;
		});
		return cm;
	}

	private ClientThread clientThread() {
		ClientThread ct = mock(ClientThread.class);
		doAnswer(i -> {
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(any(Runnable.class));
		return ct;
	}

	private void setUp() {
		TestOSRSCompanionConfig noKey = new TestOSRSCompanionConfig();
		old = new OldStorageItemMonitor(client(), clientThread(), noKey, mock(OSRSCompanionApiClient.class), itemManager(), gson,
			mock(ScheduledExecutorService.class), configManager());
		OldStorageItemMonitor.sink = (source, contents) -> uploads.add(entry("t", tick, "source", source, "contents", contents));
		old.setEmptiedToDepositBoxListener(contents -> deposits.add(entry("t", tick, "contents", contents)));
		TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
		config.setOsrsCompanionAppKey("key");
		OSRSCompanionApiClient api = mock(OSRSCompanionApiClient.class);
		doAnswer(i -> {
			Map<?, ?> body = gson.fromJson((String) i.getArgument(0), Map.class);
			recorded.addAll((List<?>) body.get("events"));
			return null;
		}).when(api).sendEvents(anyString(), anyBoolean());
		SupplyCounter counter = mock(SupplyCounter.class);
		when(counter.carried()).thenReturn(new HashMap<>());
		now = new GameEvents(client(), config, api, itemManager(), counter, gson, null, configManager(), clientThread());
	}

	private static Map<String, Object> entry(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	// ---- The game, to both ----

	private void inventoryChanged() {
		ItemContainerChanged e = new ItemContainerChanged(InventoryID.INV, container(inv));
		old.onItemContainerChanged(e);
		now.onItemContainerChanged(e);
	}

	private void wornChanged() {
		ItemContainerChanged e = new ItemContainerChanged(InventoryID.WORN, container(worn));
		old.onItemContainerChanged(e);
		now.onItemContainerChanged(e);
	}

	private void chat(ChatMessageType type, String text) {
		ChatMessage m = new ChatMessage(null, type, "", text, null, 0);
		old.onChatMessage(m);
		now.onChatMessage(m);
	}

	private final Map<Skill, Integer> xp = new HashMap<>();

	private void xp(Skill skill, int gained) {
		int v = xp.getOrDefault(skill, 1_000_000) + gained;
		xp.put(skill, v);
		StatChanged e = new StatChanged(skill, v, 99, 99);
		old.onStatChanged(e);
		now.onStatChanged(e);
	}

	private void click(String option, MenuAction action, int widgetItem, String target) {
		MenuOptionClicked e = mock(MenuOptionClicked.class);
		when(e.getMenuOption()).thenReturn(option);
		when(e.getMenuAction()).thenReturn(action);
		when(e.getMenuTarget()).thenReturn(target);
		Widget w = mock(Widget.class);
		when(w.getItemId()).thenReturn(widgetItem);
		when(e.getWidget()).thenReturn(w);
		old.onMenuOptionClicked(e);
		now.onMenuOptionClicked(e);
	}

	private void widget(int group, boolean open) {
		if (open) {
			WidgetLoaded e = new WidgetLoaded();
			e.setGroupId(group);
			old.onWidgetLoaded(e);
			now.onWidgetLoaded(e);
		} else {
			WidgetClosed e = new WidgetClosed(group, 0, true);
			old.onWidgetClosed(e);
			now.onWidgetClosed(e);
		}
	}

	private void config(String key, String value) {
		if (value == null) {
			charges.remove(key);
		} else {
			charges.put(key, value);
		}
		ConfigChanged e = new ConfigChanged();
		e.setGroup(CHARGES);
		e.setKey(key);
		e.setNewValue(value);
		old.onConfigChanged(e);
		now.onConfigChanged(e);
	}

	private void gameState(GameState s) {
		state = s;
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		old.onGameStateChanged(e);
		now.onGameStateChanged(e);
	}

	private void tick() {
		if (state == GameState.LOGGED_IN) {
			old.onGameTick(new GameTick());
			// Now and then the plugin's timed flush: the end of this tick.
			if (r.nextInt(20) == 0) {
				now.add("gt");
			}
		}
		tick++;
	}

	// ---- What a player does ----

	private int pick(List<Integer> a) {
		return a.get(r.nextInt(a.size()));
	}

	private String chatName(OldStorageItemSpec s, int id) {
		return s.chatName(names.get(id));
	}

	private void give(int id, int q) {
		inv.merge(id, q, Integer::sum);
		if (inv.get(id) <= 0) {
			inv.remove(id);
		}
	}

	private String gatherLine(OldStorageItemSpec s, int id) {
		String n = chatName(s, id);
		switch (s.source) {
			case "fish_barrel":
				return n.endsWith("s") && r.nextBoolean() ? "You catch some " + n + "." : "You catch a " + n + "!";
			case "log_basket":
				return r.nextInt(5) == 0 ? "You get some " + n + " and give them to Leela." : "You get some " + n + ".";
			case "gem_bag":
				return r.nextInt(4) == 0 ? "You steal an uncut " + n + " and add it to your gem bag." : "You just " + (r.nextBoolean() ? "mined" : "found") + " an uncut " + n + "!";
			case "coal_bag":
				return "You manage to mine some coal.";
			default:
				return "You put the Grimy " + n + " herb into your herb sack.";
		}
	}

	private String listing(OldStorageItemSpec s, Map<Integer, Integer> what) {
		StringBuilder b = new StringBuilder(s.checkPrefix);
		if (s.source.equals("coal_bag")) {
			int n = what.values().stream().mapToInt(Integer::intValue).sum();
			return "The coal bag contains " + (n == 1 ? "one piece" : n + " pieces") + " of coal.";
		}
		if (s.source.equals("gem_bag")) {
			List<String> parts = new ArrayList<>();
			what.forEach((id, q) -> {
				String n = chatName(s, id);
				parts.add((n.endsWith("y") ? n.substring(0, n.length() - 1) + "ies" : n.endsWith("z") ? n + "es" : n + "s") + ": " + q);
			});
			return String.join(" / ", parts);
		}
		List<String> parts = new ArrayList<>();
		what.forEach((id, q) -> parts.add(q + " x " + names.get(id)));
		return b.append(' ').append(String.join(", ", parts)).toString();
	}

	private void play() {
		gameState(GameState.LOGGED_IN);
		for (int step = 0; step < 250; step++) {
			OldStorageItemSpec s = specs.get(r.nextInt(specs.size()));
			List<Integer> closed = new ArrayList<>(new java.util.TreeSet<>(s.closedIds));
			List<Integer> open = new ArrayList<>(new java.util.TreeSet<>(s.openIds));
			List<Integer> accepts = new ArrayList<>(s.acceptedIds);
			int what = r.nextInt(30);
			if (what < 3) {
				// Pick up, swap open / closed or drop the container.
				for (int id : s.closedIds) {
					inv.remove(id);
					worn.remove(id);
				}
				for (int id : s.openIds) {
					inv.remove(id);
					worn.remove(id);
				}
				if (r.nextInt(5) != 0) {
					int id = r.nextBoolean() ? pick(open) : pick(closed);
					if (r.nextInt(6) == 0) {
						worn.put(id, 1);
					} else {
						inv.put(id, 1);
					}
				}
				inventoryChanged();
				wornChanged();
			} else if (what < 15) {
				// Gathering: the item goes in (open, room) or lands in the inventory.
				int id = pick(accepts.subList(0, Math.min(accepts.size(), 6)));
				int n = 1 + r.nextInt(4);
				for (int k = 0; k < n; k++) {
					chat(s.source.equals("herb_sack") || r.nextInt(4) == 0 ? ChatMessageType.GAMEMESSAGE : ChatMessageType.SPAM, gatherLine(s, id));
					if (r.nextInt(8) == 0 && !s.extraGatherMessages.isEmpty()) {
						chat(ChatMessageType.SPAM, s.extraGatherMessages.iterator().next());
					}
					if (r.nextInt(5) == 0) {
						give(id, 1);
						inventoryChanged();
					}
					if (s.consumeSkill != null && r.nextInt(6) == 0) {
						xp(s.consumeSkill, 50);
					}
					if (s.xpCreditSkill != null) {
						if (r.nextBoolean()) {
							click("Pick", MenuAction.GAME_OBJECT_SECOND_OPTION, -1, "<col=ffff>Herbs");
						}
						xp(s.xpCreditSkill, (int) Math.round(s.xpPerItem.getOrDefault(id, 30.0)) + r.nextInt(2));
					}
					if (r.nextInt(25) == 0 && !s.fullMessages.isEmpty()) {
						chat(ChatMessageType.GAMEMESSAGE, s.fullMessages.iterator().next());
					}
					tick();
				}
			} else if (what < 18) {
				// Fill: the accepted items leave the inventory.
				int id = pick(accepts.subList(0, Math.min(accepts.size(), 4)));
				give(id, 1 + r.nextInt(10));
				inventoryChanged();
				tick();
				int c = r.nextBoolean() ? pick(open) : pick(closed);
				if (r.nextBoolean()) {
					click("Fill", MenuAction.CC_OP, c, "");
				} else {
					selectedItem = id;
					click("Use", MenuAction.WIDGET_TARGET_ON_WIDGET, c, "");
				}
				tick();
				if (r.nextInt(4) != 0) {
					inv.remove(id);
					inventoryChanged();
				}
			} else if (what < 21) {
				// Empty: into the inventory, the bank or a deposit box.
				int c = r.nextBoolean() ? pick(open) : pick(closed);
				int place = r.nextInt(3);
				if (place == 1) {
					widget(InterfaceID.BANKMAIN, true);
				} else if (place == 2) {
					widget(InterfaceID.BANK_DEPOSITBOX, true);
				}
				click(r.nextBoolean() ? "Empty" : "Empty-to-bank", MenuAction.CC_OP, c, "");
				tick();
				if (place == 0) {
					give(pick(accepts.subList(0, Math.min(accepts.size(), 4))), 1 + r.nextInt(6));
					inventoryChanged();
					if (r.nextBoolean() && !s.emptiedMessages.isEmpty()) {
						chat(ChatMessageType.GAMEMESSAGE, new java.util.TreeSet<>(s.emptiedMessages).first());
					}
				} else if (r.nextBoolean()) {
					chat(ChatMessageType.GAMEMESSAGE, "You empty all of your containers into the bank.");
				}
				tick();
				widget(place == 2 ? InterfaceID.BANK_DEPOSITBOX : InterfaceID.BANKMAIN, false);
			} else if (what < 24) {
				// Check: the listing in the message box, or chat lines (the herb sack), or empty.
				int c = r.nextBoolean() ? pick(open) : pick(closed);
				click("Check", MenuAction.CC_OP, c, "");
				Map<Integer, Integer> listing = new LinkedHashMap<>();
				for (int k = r.nextInt(3); k >= 0; k--) {
					listing.merge(pick(accepts.subList(0, Math.min(accepts.size(), 5))), 1 + r.nextInt(20), Integer::sum);
				}
				if (r.nextInt(6) == 0) {
					chat(ChatMessageType.GAMEMESSAGE, new java.util.TreeSet<>(s.checkEmptyMessages).first());
				} else if (s.source.equals("herb_sack")) {
					chat(ChatMessageType.GAMEMESSAGE, s.checkPrefix);
					listing.forEach((id, q) -> chat(ChatMessageType.GAMEMESSAGE, q + " x " + names.get(id)));
				} else if (r.nextBoolean()) {
					messageBox = listing(s, listing);
					widget(193, true);
				} else {
					chat(ChatMessageType.GAMEMESSAGE, listing(s, listing));
				}
				tick();
			} else if (what < 25) {
				// The Charges plugin wrote its exact state.
				String key = s.source + "_storage";
				StringBuilder json = new StringBuilder("[");
				for (int k = r.nextInt(3); k > 0; k--) {
					json.append(json.length() > 1 ? "," : "").append("{\"itemId\":").append(pick(accepts)).append(",\"quantity\":").append(r.nextInt(30)).append('}');
				}
				config(key, r.nextInt(8) == 0 ? "not json" : json.append(']').toString());
			} else if (what < 26) {
				gameState(GameState.LOADING);
				tick();
				gameState(GameState.LOGGED_IN);
			} else if (what < 27) {
				gameState(GameState.LOGIN_SCREEN);
				tick();
				tick();
				gameState(GameState.LOGGED_IN);
			} else {
				for (int k = 1 + r.nextInt(5); k > 0; k--) {
					tick();
				}
			}
			tick();
		}
		gameState(GameState.LOGIN_SCREEN);
	}

	@Test
	public void writeOracle() throws Exception {
		names = new HashMap<>();
		Map<String, String> raw = gson.fromJson(new InputStreamReader(getClass().getResourceAsStream("storage-names.json"), StandardCharsets.UTF_8),
			new TypeToken<Map<String, String>>() { }.getType());
		raw.forEach((k, v) -> names.put(Integer.parseInt(k), v));
		specs = OldStorageItemSpec.all();
		new StorageSpecExportTest().writeSpecs();

		List<Map<String, Object>> cases = new ArrayList<>();
		for (int n = 0; n < 60; n++) {
			r = new Random(20261012L + n);
			tick = 5000;
			state = GameState.LOGIN_SCREEN;
			inv.clear();
			worn.clear();
			charges.clear();
			xp.clear();
			recorded.clear();
			uploads.clear();
			deposits.clear();
			setUp();
			play();
			// What GameEvents still holds (not flushed yet).
			Field f = GameEvents.class.getDeclaredField("buffer");
			f.setAccessible(true);
			recorded.addAll(gson.fromJson(gson.toJson(f.get(now)), List.class));
			Field t = OldStorageItemMonitor.class.getDeclaredField("tracked");
			t.setAccessible(true);
			Map<String, Object> finalContents = new LinkedHashMap<>();
			for (Object tracked : (List<?>) t.get(old)) {
				Field l = tracked.getClass().getDeclaredField("ledger");
				l.setAccessible(true);
				OldStorageItemLedger ledger = (OldStorageItemLedger) l.get(tracked);
				finalContents.put(ledger.spec.source, ledger.contents());
			}
			cases.add(entry("events", new ArrayList<>(recorded), "uploads", gson.fromJson(gson.toJson(uploads), List.class),
				"deposits", gson.fromJson(gson.toJson(deposits), List.class), "final", gson.fromJson(gson.toJson(finalContents), Map.class)));
		}
		Path out = Paths.get("build", "oracle", "storage-events.json.gz");
		Files.createDirectories(out.getParent());
		try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8)) {
			new GsonBuilder().serializeNulls().create().toJson(cases, w);
		}
		long ups = cases.stream().mapToLong(c -> ((List<?>) c.get("uploads")).size()).sum();
		long deps = cases.stream().mapToLong(c -> ((List<?>) c.get("deposits")).size()).sum();
		System.out.println("storage oracle: " + cases.size() + " sessions, " + ups + " uploads, " + deps + " deposit-box empties");
	}
}
