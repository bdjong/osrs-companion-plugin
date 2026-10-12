package com.osrscompanion;

import lombok.RequiredArgsConstructor;
import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.*;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.api.widgets.Widget;
import net.runelite.api.ItemContainer;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.events.*;
import net.runelite.api.Skill;
import net.runelite.api.ObjectComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.Item;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * The game events the website works the boss trips and the boss history out from (POST /api/runelite/events): the
 * bank opening and closing, kill count and duration messages, loot, deaths, the NPC you attack (and its death),
 * where you are (a region change or a far jump), what you carry while a trip can be going, logging out, and a
 * heartbeat while you are bossing; and for the harvest stats, Pick / Harvest / Clear on a farming patch, each tick
 * with Farming XP (and what came into the inventory with it), what RuneLite's Time Tracking stores and walking away
 * from the patch; and for the storage items (fish barrel, log basket, gem bag, coal bag, herb sack, whose contents the
 * game never sends) the inventory and worn items, game messages, Cooking / Firemaking / Farming XP, their Fill / Empty /
 * Check clicks and Check text, the deposit box and the Charges plugin's storages. In order, each with its tick and
 * time; sent with the other uploads every 30 seconds, and at once after a kill, a drop, a death, the bank or logging
 * out.
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
public class GameEvents {
	static final String GUARD_FEATURE = "game-events";

	/** The game's messages the website reads: kill counts and fight durations. */
	private static final Pattern WANTED_CHAT = Pattern.compile("(?i)count is|duration|completion time|raid is complete");
	private static final int LOGIN_GRACE_TICKS = 5;
	private static final int HEARTBEAT_TICKS = 20;
	/** Heartbeats go on this long after the last sign of a boss (a session closes after 20 to 30 minutes). */
	private static final long BOSSING_MS = 35 * 60_000L;
	private static final int JUMP_TILES = 64;
	private static final long FLUSH_MS = 30_000L;

	private final Client client;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final ItemManager itemManager;
	private final SupplyCounter counter;
	private final Gson gson;
	private final FarmRunManager farmRun;
	private final ConfigManager configManager;
	private final ClientThread clientThread;

	private List<Map<String, Object>> buffer = new ArrayList<>();
	private long lastFlushMs;
	private long seq;
	/** From the bank closing (or a kill count) until the bank opens, you die or log out: what you carry counts. */
	private boolean armed;
	private boolean carriedDirty;
	private final Set<String> repsSent = new HashSet<>();
	private int ticksLoggedIn;
	private int region = -1;
	private int rawRegion = -1;
	private boolean instance;
	private WorldPoint lastPoint;
	private int lastBeat;
	private long bossingSinceMs;
	private int target = -1;
	/** NPCs you attacked: their death matters. */
	private final Set<Integer> targeted = new HashSet<>();
	// Harvests: Farming XP this tick, what came into the inventory with it, and the patch clicked.
	private int farmXp = -1;
	private boolean farmXpThisTick;
	private Map<Integer, Integer> lastInventory;
	private final Map<Integer, Integer> gained = new HashMap<>();
	private WorldPoint clicked;
	/** The Kandarin and Kourend & Kebos diaries' completed varbits, easy to elite (they raise two herb patches' yields). */
	private static final int[] KANDARIN_DIARY = {4515, 4516, 4517, 4518};
	private static final int[] KOUREND_DIARY = {7929, 7930, 7931, 7932};
	/** The Charges Improved plugin's config group: its exact storage contents ("..._storage" keys). */
	private static final String CHARGES = "tictac7x-charges";
	/** The message box "Check" writes a storage item's contents to. */
	private static final int MESSAGEBOX = 193;
	private final Map<Skill, Integer> lastXp = new HashMap<>();
	private GameState gameState = GameState.UNKNOWN;

	private boolean on() {
		return (config.bossTrips() || config.bossHistory() || config.syncFarmHarvests() || config.syncSlayer() || config.syncBankMemory())
			&& SyncGuard.hasAppKey(config)
			&& !GameModeUtil.isSpecialGameMode(client);
	}

	Map<String, Object> add(String type, Object... kv) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("t", client.getTickCount());
		e.put("ms", System.currentTimeMillis());
		e.put("e", type);
		for (int i = 0; i < kv.length; i += 2) {
			e.put((String) kv[i], kv[i + 1]);
		}
		if (on()) {
			buffer.add(e);
		}
		return e;
	}

	/** What you carry now (the supply counter's keys), and the item picturing each key the website has not had yet. */
	private void carried(Map<String, Object> e) {
		Map<String, Long> c = counter.carried();
		Map<String, int[]> reps = new HashMap<>();
		for (String k : c.keySet()) {
			SupplyCounter.Rep rep = counter.rep(k);
			if (rep != null && repsSent.add(k + ":" + rep.id)) {
				reps.put(k, new int[]{rep.id, rep.dose});
			}
		}
		e.put("c", c);
		if (!reps.isEmpty()) {
			e.put("r", reps);
		}
		carriedDirty = false;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == InterfaceID.BANKMAIN) {
				add("bank_open");
				armed = false;
				flush(true);
			} else if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX) {
				add("dbox", "o", true);
			} else if (event.getGroupId() == MESSAGEBOX) {
				// A storage item's Check listing; its text is set after the load.
				Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
					Widget w = client.getWidget(MESSAGEBOX, 2);
					if (w != null && w.getText() != null) {
						add("st_check", "text", w.getText());
					}
				});
			}
		});
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == InterfaceID.BANKMAIN) {
				carried(add("bank_close"));
				armed = true;
			} else if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX) {
				add("dbox", "o", false);
			}
		});
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		Guard.run(GUARD_FEATURE, () -> {
			ChatMessageType type = event.getType();
			if (type == ChatMessageType.GAMEMESSAGE || type == ChatMessageType.SPAM) {
				add("msg", "s", type == ChatMessageType.SPAM, "m", event.getMessage());
			}
			String text = event.getMessage() == null ? null
				: Text.removeTags(event.getMessage()).replaceAll("@[a-z_]+@", "").replace(' ', ' ').trim();
			if ((type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM) || text == null || !WANTED_CHAT.matcher(text).find()) {
				return;
			}
			carried(add("chat", "text", text));
			if (text.contains("count is")) {
				armed = true;
				bossingSinceMs = System.currentTimeMillis();
			}
			flush(true);
		});
	}

	@Subscribe
	public void onLootReceived(LootReceived event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getType() != LootRecordType.NPC && event.getType() != LootRecordType.EVENT) {
				return;
			}
			List<long[]> items = new ArrayList<>();
			for (ItemStack stack : event.getItems()) {
				ItemComposition comp = itemManager.getItemComposition(stack.getId());
				int id = comp != null && comp.getNote() != -1 ? comp.getLinkedNoteId() : stack.getId();
				items.add(new long[]{id, stack.getQuantity(), (long) itemManager.getItemPrice(id) * stack.getQuantity()});
			}
			add("loot", "name", event.getName(), "npc", event.getType() == LootRecordType.NPC, "items", items);
			flush(true);
		});
	}

	@Subscribe
	public void onActorDeath(ActorDeath event) {
		Guard.run(GUARD_FEATURE, () -> {
			Actor actor = event.getActor();
			if (actor instanceof NPC && targeted.remove(((NPC) actor).getIndex())) {
				add("npc_death", "i", ((NPC) actor).getIndex(), "name", actor.getName());
			} else if (actor == client.getLocalPlayer()) {
				add("death");
				armed = false;
				flush(true);
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGameState() == GameState.LOGGED_IN && gameState != GameState.LOGGED_IN) {
				// Logged in (or back from a loading screen): what the storage items start from.
				storage(InventoryID.INV);
				storage(InventoryID.WORN);
				charges(null);
			} else if (SyncGuard.isLogout(gameState, event.getGameState())) {
				add("st_out");
			}
			gameState = event.getGameState();
			if (event.getGameState() == GameState.LOGIN_SCREEN) {
				if (ticksLoggedIn > 0) {
					add("logout");
					flush(true);
				}
				reset();
			}
		});
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		if (event.getContainerId() == InventoryID.INV || event.getContainerId() == InventoryID.WORN) {
			carriedDirty = true;
			Guard.run(GUARD_FEATURE, () -> storage(event.getContainerId()));
		}
		if (event.getContainerId() == InventoryID.INV) {
			Map<Integer, Integer> now = new HashMap<>();
			for (Item item : event.getItemContainer() == null ? new Item[0] : event.getItemContainer().getItems()) {
				if (item.getId() > 0) {
					now.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
			if (lastInventory != null) {
				now.forEach((id, q) -> {
					int more = q - lastInventory.getOrDefault(id, 0);
					if (more > 0) {
						gained.merge(id, more, Integer::sum);
					}
				});
			}
			lastInventory = now;
		}
	}

	/** The inventory (canonical ids) or worn items, for the storage items. */
	private void storage(int containerId) {
		ItemContainer c = client.getItemContainer(containerId);
		Map<Integer, Integer> q = new HashMap<>();
		for (Item item : c == null ? new Item[0] : c.getItems()) {
			if (item != null && item.getId() > 0 && item.getQuantity() > 0) {
				q.merge(containerId == InventoryID.INV ? itemManager.canonicalize(item.getId()) : item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		add(containerId == InventoryID.INV ? "inv" : "worn", "q", q);
	}

	/** The Charges plugin's storage contents (its raw values), on login or when {@code changed} (a key) changed. */
	private void charges(String changed) {
		Map<String, String> v = new HashMap<>();
		for (String k : configManager.getConfigurationKeys(CHARGES + ".")) {
			String key = k.substring(CHARGES.length() + 1);
			if (key.endsWith("_storage")) {
				v.put(key, configManager.getConfiguration(CHARGES, key));
			}
		}
		add("charges", "k", changed, "v", v);
	}

	@Subscribe
	public void onStatChanged(StatChanged event) {
		Skill skill = event.getSkill();
		int gained = event.getXp() - lastXp.getOrDefault(skill, event.getXp());
		lastXp.put(skill, event.getXp());
		// The storage items: XP that eats a catch (infernal tools) or is a herb going into the herb sack.
		if (gained > 0 && (skill == Skill.COOKING || skill == Skill.FIREMAKING || skill == Skill.FARMING)) {
			Guard.run(GUARD_FEATURE, () -> add("xp", "s", skill.getName(), "g", gained));
		}
		if (event.getSkill() == Skill.FARMING) {
			farmXpThisTick |= farmXp >= 0 && event.getXp() > farmXp;
			farmXp = event.getXp();
		}
	}

	/** Pick / Harvest / Clear on a farming patch. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		Guard.run(GUARD_FEATURE, () -> {
			String option = event.getMenuOption() == null ? "" : event.getMenuOption().toLowerCase();
			MenuAction action = event.getMenuAction();
			// The storage items: an item used on one (a fill), and their Fill / Empty / Check (and Pick: the herb).
			if (action == MenuAction.WIDGET_TARGET_ON_WIDGET) {
				add("st_use", "a", itemOf(event.getWidget()), "b", itemOf(client.getSelectedWidget()));
				return;
			}
			if (option.equals("fill") || option.equals("empty") || option.equals("empty-to-bank") || option.equals("check") || option.equals("pick")) {
				add("st_op", "o", option, "i", action == MenuAction.CC_OP || action == MenuAction.CC_OP_LOW_PRIORITY ? itemOf(event.getWidget()) : -1,
					"tgt", event.getMenuTarget());
			}
			if (event.getMenuAction().getId() < MenuAction.GAME_OBJECT_FIRST_OPTION.getId() || event.getMenuAction().getId() > MenuAction.GAME_OBJECT_FIFTH_OPTION.getId()
				|| !(option.startsWith("pick") || option.startsWith("harvest") || option.startsWith("clear"))) {
				return;
			}
			ObjectComposition def = client.getObjectDefinition(event.getId());
			if (def == null || def.getVarbitId() <= 0) {
				return;
			}
			clicked = WorldPoint.fromScene(client, event.getParam0(), event.getParam1(), client.getPlane());
			add("farm_click", "opt", option, "varbit", def.getVarbitId(), "region", clicked.getRegionID(), "cape", farmRun.isFarmingCapeWorn(),
				"sec", farmRun.carried(ItemID.FAIRY_ENCHANTED_SECATEURS) > 0, "kd", tier(KANDARIN_DIARY), "kk", tier(KOUREND_DIARY),
				"lvl", client.getRealSkillLevel(Skill.FARMING), "blvl", client.getBoostedSkillLevel(Skill.FARMING));
		});
	}

	private static int itemOf(Widget w) {
		return w == null ? -1 : w.getItemId();
	}

	/** Highest tier of the diary done in order: 0 none, 1 easy, 2 medium, 3 hard, 4 elite. */
	private int tier(int[] varbits) {
		int done = 0;
		while (done < varbits.length && client.getVarbitValue(varbits[done]) == 1) {
			done++;
		}
		return done;
	}

	/** What RuneLite's Time Tracking stores (patches, compost) as it changes, for the harvests. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (CHARGES.equals(event.getGroup()) && event.getKey() != null && event.getKey().endsWith("_storage")) {
			Guard.run(GUARD_FEATURE, () -> charges(event.getKey()));
		}
		if (TimeTrackingConfig.CONFIG_GROUP.equals(event.getGroup()) && event.getProfile() != null && event.getProfile().equals(configManager.getRSProfileKey())) {
			Guard.run(GUARD_FEATURE, () -> add("tt", "k", event.getKey(), "v", event.getNewValue()));
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (RunePouchMonitor.isPouchVarbit(event.getVarbitId())) {
			carriedDirty = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		Guard.run(GUARD_FEATURE, () -> {
			Player me = client.getLocalPlayer();
			if (client.getGameState() != GameState.LOGGED_IN || me == null || me.getLocalLocation() == null || ++ticksLoggedIn < LOGIN_GRACE_TICKS) {
				return;
			}
			long now = System.currentTimeMillis();
			if (ticksLoggedIn == LOGIN_GRACE_TICKS && config.syncFarmHarvests()) {
				add("tt_all", "p", TimerMonitor.timeTracking(configManager));
			}
			// Where you are: the template region inside an instance (what the lairs list), on a change or a far jump.
			WorldPoint here = WorldPoint.fromLocalInstance(client, me.getLocalLocation());
			int raw = me.getWorldLocation() == null ? here.getRegionID() : me.getWorldLocation().getRegionID();
			boolean inst = client.getTopLevelWorldView() != null && client.getTopLevelWorldView().isInstance();
			boolean jumped = lastPoint != null && Math.max(Math.abs(here.getX() - lastPoint.getX()), Math.abs(here.getY() - lastPoint.getY())) > JUMP_TILES;
			if (here.getRegionID() != region || raw != rawRegion || inst != instance || jumped) {
				region = here.getRegionID();
				rawRegion = raw;
				instance = inst;
				add("pos", "region", region, "raw", raw, "inst", inst, "x", here.getX(), "y", here.getY());
			}
			lastPoint = here;
			// The NPC you attack.
			Actor t = me.getInteracting();
			int index = t instanceof NPC ? ((NPC) t).getIndex() : -1;
			if (index != target) {
				target = index;
				add("target", "i", index, "name", index < 0 ? null : t.getName());
				if (index >= 0) {
					targeted.add(index);
					bossingSinceMs = now;
				}
			}
			if (farmXpThisTick) {
				add("farm_xp", "g", new HashMap<>(gained), "cape", farmRun.isFarmingCapeWorn(), "sec", farmRun.carried(ItemID.FAIRY_ENCHANTED_SECATEURS) > 0);
			}
			farmXpThisTick = false;
			gained.clear();
			if (clicked != null && (clicked.getPlane() != me.getWorldLocation().getPlane() || clicked.distanceTo2D(me.getWorldLocation()) > 20)) {
				clicked = null;
				add("far");
			}
			if (carriedDirty && armed) {
				carried(add("carried"));
			}
			carriedDirty = false;
			int tickCount = client.getTickCount();
			if (now - bossingSinceMs < BOSSING_MS && tickCount - lastBeat >= HEARTBEAT_TICKS) {
				lastBeat = tickCount;
				add("tick");
			}
			flush(false);
		});
	}

	/** Sends what was recorded: every {@link #FLUSH_MS}, or now. */
	private void flush(boolean now) {
		long t = System.currentTimeMillis();
		String player = SyncGuard.getPlayerUsername(client);
		if (buffer.isEmpty() || player == null || !now && t - lastFlushMs < FLUSH_MS) {
			return;
		}
		if (!now) {
			// The end of a game tick (the website closes the tick's storage item changes).
			add("gt");
		}
		lastFlushMs = t;
		seq = Math.max(seq + 1, t);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", player);
		body.put("seq", seq);
		body.put("trips", config.bossTrips());
		body.put("history", config.bossHistory());
		body.put("harvests", config.syncFarmHarvests());
		body.put("bank", config.syncBankMemory());
		body.put("events", buffer);
		buffer = new ArrayList<>();
		apiClient.sendEvents(gson.toJson(body), now);
	}

	private void reset() {
		ticksLoggedIn = 0;
		region = -1;
		rawRegion = -1;
		lastPoint = null;
		armed = false;
		target = -1;
		targeted.clear();
		repsSent.clear();
		farmXp = -1;
		lastInventory = null;
		clicked = null;
	}

	public void stop() {
		Guard.run(GUARD_FEATURE, () -> flush(true));
		buffer = new ArrayList<>();
		reset();
	}
}
