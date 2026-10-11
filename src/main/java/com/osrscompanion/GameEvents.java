package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
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
 * heartbeat while you are bossing. In order, each with its tick and time; sent with the other uploads every 30
 * seconds, and at once after a kill, a drop, a death, the bank or logging out.
 */
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

	@Inject
	public GameEvents(Client client, OSRSCompanionConfig config, OSRSCompanionApiClient apiClient, ItemManager itemManager, SupplyCounter counter,
		Gson gson) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.itemManager = itemManager;
		this.counter = counter;
		this.gson = gson;
	}

	private boolean on() {
		return (config.bossTrips() || config.bossHistory()) && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client);
	}

	private Map<String, Object> add(String type, Object... kv) {
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
			}
		});
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == InterfaceID.BANKMAIN) {
				carried(add("bank_close"));
				armed = true;
			}
		});
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		Guard.run(GUARD_FEATURE, () -> {
			ChatMessageType type = event.getType();
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
		lastFlushMs = t;
		seq = Math.max(seq + 1, t);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", player);
		body.put("seq", seq);
		body.put("trips", config.bossTrips());
		body.put("history", config.bossHistory());
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
	}

	public void stop() {
		Guard.run(GUARD_FEATURE, () -> flush(true));
		buffer = new ArrayList<>();
		reset();
	}
}
