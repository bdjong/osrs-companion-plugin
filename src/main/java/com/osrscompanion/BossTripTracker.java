package com.osrscompanion;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
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
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Records boss trips for the website's Boss Gear page (Trips) and the supplies check.
 *
 * <p>The rules:
 * <ul>
 * <li>Closing the bank arms a trip: what you carry then (inventory, worn, rune pouch) is what you brought.</li>
 * <li>The first boss kill count message after that starts the trip (no bank seen yet this session: it starts
 * there, counting from that moment). It starts when the bank closed, at most an hour before that kill
 * (raids take a while; standing at the bank longer is not trip time).</li>
 * <li>Each kill count message of the same boss is a kill; another boss ends the trip and starts a new one.</li>
 * <li>The trip ends when you open a bank, die, or log out; after a far teleport (or a long walk through a
 * ladder) it ends when no kill follows within three minutes, at the teleport; after 15 minutes without a
 * kill, at the last kill.</li>
 * <li>Supplies used: whatever you brought that went down (potions by doses, food, runes, ammo, teleports).
 * Loot picked up does not count, nor do switches taken off (inventory and worn are counted together).</li>
 * <li>Loot: the RuneLite loot tracker's drops (the same events the loot sync uses) while the trip runs.</li>
 * </ul>
 * Finished trips go to /api/runelite/boss-trips, and the last ten trips' supplies per boss are kept (per
 * RuneLite profile) for the supplies check. Work per tick: a recount only when a container changed.
 *
 * <p>The same events drive the boss history ({@link BossHistory}: sessions per visit to a boss, with their
 * trips, drops and fight times): this class hands them on, with the supplies each finished trip used.
 */
@Slf4j
@Singleton
public class BossTripTracker {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "boss-trips";

	private static final Pattern KILL_COUNT = Pattern.compile(
		"^Your (?:completion count for |subdued |completed )?(.+?) (?:(kill|harvest|lap|completion) )?(?:count )?is: ?([0-9,]+)");
	/** Kill counts that are not boss trips (skilling bosses). */
	private static final Set<String> SKIPPED = new HashSet<>(java.util.Arrays.asList("wintertodt", "tempoross", "guardiansoftherift", "zalcano"));
	private static final long MAX_ARMED_MS = 60 * 60_000L;
	/** A jump this far (tiles) in one tick is a teleport. */
	private static final int TELEPORT_TILES = 64;
	private static final int TELEPORT_GRACE_TICKS = 300;
	private static final int IDLE_TICKS = 1500;
	/** Loot that came just before the kill count message belongs to the first kill. */
	private static final int LOOT_BUFFER_TICKS = 5;
	private static final int HISTORY = 10;
	private static final String HISTORY_KEY = "bossTripSupplies.";
	private static final Type HISTORY_TYPE = new TypeToken<List<Map<String, Long>>>() { }.getType();

	private final Client client;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final ConfigManager configManager;
	private final ItemManager itemManager;
	private final SupplyCounter counter;
	private final Gson gson;
	private final BossHistory bossHistory;

	enum State {
		IDLE, ARMED, ACTIVE
	}

	private State state = State.IDLE;
	private long armedAt;
	private Set<String> brought = Collections.emptySet();
	private Map<String, Long> counts = Collections.emptyMap();
	private final Map<String, Long> used = new HashMap<>();
	private boolean dirty;

	private String boss;
	private String bossKey;
	private int kills;
	private long startedAt;
	private long lastKillAt;
	private int lastKillTick;
	private final Map<Integer, Long> loot = new LinkedHashMap<>();
	private final Deque<Object[]> recentLoot = new ArrayDeque<>();

	/** After a far jump: when and on which tick, and the supplies used up to then. */
	private long leftAt;
	private int leftTick = -1;
	private Map<String, Long> usedAtLeave;
	private WorldPoint lastPoint;

	/** Supplies of the last trips per boss key, loaded from the profile config on first use. */
	private final Map<String, List<Map<String, Long>>> history = new HashMap<>();

	@Inject
	public BossTripTracker(Client client, OSRSCompanionConfig config, OSRSCompanionApiClient apiClient, ConfigManager configManager,
		ItemManager itemManager, SupplyCounter counter, Gson gson, BossHistory bossHistory) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.itemManager = itemManager;
		this.counter = counter;
		this.gson = gson;
		this.bossHistory = bossHistory;
	}

	public void stop() {
		history(bossHistory::stop);
		state = State.IDLE;
		resetTrip();
		history.clear();
		recentLoot.clear();
	}

	// ---- Events ------------------------------------------------------------------------

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == InterfaceID.BANKMAIN) {
				history(bossHistory::onBankOpened);
				if (state == State.ACTIVE) {
					finish("bank", false);
				}
				state = State.IDLE;
			}
		});
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == InterfaceID.BANKMAIN && config.bossTrips()) {
				arm(System.currentTimeMillis());
			}
		});
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getType() != ChatMessageType.GAMEMESSAGE) {
				return;
			}
			String message = Text.removeTags(event.getMessage());
			history(() -> bossHistory.onChatMessage(message));
			if (!config.bossTrips()) {
				return;
			}
			String name = killCountBoss(message);
			if (name != null) {
				onKill(name);
			}
		});
	}

	/** The boss of a kill count message, or null when it is none (lap counts, skilling bosses, other messages). */
	static String killCountBoss(String message) {
		String[] kc = killCount(message);
		return kc == null || SKIPPED.contains(TripSupplies.bossKey(kc[0])) ? null : kc[0];
	}

	/** A kill count message's name and count ("Vorkath", "152"), or null (lap and harvest counts, other messages). */
	static String[] killCount(String message) {
		if (message == null) {
			return null;
		}
		Matcher m = KILL_COUNT.matcher(message.trim());
		if (!m.find() || "lap".equals(m.group(2)) || "harvest".equals(m.group(2))) {
			return null;
		}
		String name = m.group(1).trim();
		return name.isEmpty() ? null : new String[]{name, m.group(3)};
	}

	@Subscribe
	public void onLootReceived(LootReceived event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getType() != LootRecordType.NPC && event.getType() != LootRecordType.EVENT) {
				return;
			}
			List<BossSession.Stack> drop = new ArrayList<>();
			if (config.bossHistory()) {
				for (ItemStack stack : event.getItems()) {
					int id = canonical(stack.getId());
					drop.add(new BossSession.Stack(id, stack.getQuantity(), (long) itemManager.getItemPrice(id) * stack.getQuantity(), 0));
				}
			}
			history(() -> bossHistory.onLoot(event.getName(), drop));
			for (ItemStack stack : event.getItems()) {
				int id = canonical(stack.getId());
				if (state == State.ACTIVE) {
					loot.merge(id, (long) stack.getQuantity(), Long::sum);
				} else {
					recentLoot.addLast(new Object[]{client.getTickCount(), id, (long) stack.getQuantity()});
				}
			}
		});
	}

	@Subscribe
	public void onActorDeath(ActorDeath event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getActor() instanceof NPC) {
				history(() -> bossHistory.onNpcDeath((NPC) event.getActor()));
				return;
			}
			if (event.getActor() != client.getLocalPlayer()) {
				return;
			}
			history(bossHistory::onLocalDeath);
			// Ended before the items are lost, so they do not count as used.
			if (state == State.ACTIVE) {
				finish("death", true);
			}
			state = State.IDLE;
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGameState() == GameState.LOGIN_SCREEN) {
				if (state == State.ACTIVE) {
					finish("logout", false);
				}
				history(bossHistory::onLogout);
				state = State.IDLE;
				resetTrip();
			}
		});
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getContainerId() == InventoryID.INV || event.getContainerId() == InventoryID.WORN) {
				dirty = true;
			}
		});
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (RunePouchMonitor.isPouchVarbit(event.getVarbitId())) {
				dirty = true;
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		Guard.run(GUARD_FEATURE, () -> {
			if (client.getGameState() == GameState.LOGGED_IN) {
				history(bossHistory::onTick);
			}
			int now = client.getTickCount();
			while (!recentLoot.isEmpty() && now - (int) recentLoot.peekFirst()[0] > LOOT_BUFFER_TICKS) {
				recentLoot.pollFirst();
			}
			if (state == State.IDLE) {
				return;
			}
			if (dirty) {
				dirty = false;
				recount();
			}
			if (state != State.ACTIVE) {
				return;
			}
			WorldPoint here = position();
			if (here != null && lastPoint != null && leftTick < 0
				&& Math.max(Math.abs(here.getX() - lastPoint.getX()), Math.abs(here.getY() - lastPoint.getY())) > TELEPORT_TILES) {
				leftTick = now;
				leftAt = System.currentTimeMillis();
				usedAtLeave = new HashMap<>(used);
			}
			if (here != null) {
				lastPoint = here;
			}
			if (leftTick >= 0 && now - leftTick > TELEPORT_GRACE_TICKS) {
				long at = leftAt;
				used.clear();
				used.putAll(usedAtLeave);
				finishAt("teleport", false, at);
				arm(at);
			} else if (now - lastKillTick > IDLE_TICKS) {
				finishAt("idle", false, lastKillAt);
				arm(System.currentTimeMillis());
			}
		});
	}

	// ---- The trip ----------------------------------------------------------------------

	/** From now on what you carry is what you brought. */
	private void arm(long at) {
		resetTrip();
		state = State.ARMED;
		armedAt = at;
		counts = counter.carried();
		brought = new HashSet<>(counts.keySet());
	}

	private void onKill(String name) {
		String key = TripSupplies.bossKey(name);
		int tick = client.getTickCount();
		long now = System.currentTimeMillis();
		if (state == State.ACTIVE && !key.equals(bossKey)) {
			finish("next boss", false);
			arm(now);
		}
		if (state != State.ACTIVE) {
			if (state == State.IDLE) {
				arm(now);
			} else if (dirty) {
				dirty = false;
				recount();
			}
			state = State.ACTIVE;
			boss = name;
			bossKey = key;
			kills = 0;
			startedAt = Math.max(armedAt, now - MAX_ARMED_MS);
			lastPoint = position();
			for (Object[] l : recentLoot) {
				loot.merge((Integer) l[1], (Long) l[2], Long::sum);
			}
			recentLoot.clear();
		}
		kills++;
		lastKillAt = now;
		lastKillTick = tick;
		// Back for more after a teleport (a house pool, a quick restock): the same trip.
		leftTick = -1;
		usedAtLeave = null;
	}

	private void recount() {
		Map<String, Long> now = counter.carried();
		TripSupplies.addUsed(counts, now, brought, used);
		counts = now;
	}

	private void finish(String reason, boolean died) {
		if (dirty) {
			dirty = false;
			recount();
		}
		finishAt(reason, died, System.currentTimeMillis());
	}

	private void finishAt(String reason, boolean died, long endedAt) {
		if (state != State.ACTIVE || kills <= 0) {
			resetTrip();
			return;
		}
		Map<String, Long> supplies = new LinkedHashMap<>(used);
		supplies.values().removeIf(v -> v <= 0);
		log.debug("Boss trip: {} x{} ({}), {} supplies, {} loot", boss, kills, reason, supplies.size(), loot.size());
		remember(bossKey, supplies);
		history(() -> bossHistory.onTripSupplies(boss, supplyStacks(supplies)));
		send(reason, died, endedAt, supplies);
		resetTrip();
		state = State.IDLE;
	}

	private void send(String reason, boolean died, long endedAt, Map<String, Long> supplies) {
		if (!SyncGuard.hasAppKey(config) || GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String player = SyncGuard.getPlayerUsername(client);
		if (player == null) {
			return;
		}
		List<Map<String, Object>> supplyList = new ArrayList<>();
		for (BossSession.Stack st : supplyStacks(supplies)) {
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("item_id", st.itemId);
			s.put("quantity", st.quantity);
			// Dosed: the quantity is doses, and the item holds this many.
			if (st.dose > 0) {
				s.put("dose", st.dose);
			}
			supplyList.add(s);
		}
		List<Map<String, Object>> lootList = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : loot.entrySet()) {
			Map<String, Object> l = new LinkedHashMap<>();
			l.put("item_id", e.getKey());
			l.put("quantity", e.getValue());
			lootList.add(l);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", player);
		body.put("boss", boss);
		body.put("kills", kills);
		body.put("started_at", Instant.ofEpochMilli(startedAt).toString());
		body.put("ended_at", Instant.ofEpochMilli(Math.max(endedAt, startedAt)).toString());
		body.put("end_reason", reason);
		body.put("died", died);
		body.put("supplies", supplyList);
		body.put("loot", lootList);
		apiClient.sendBossTrip(gson.toJson(body));
	}

	/** The boss history's part of an event, guarded on its own: a failure there leaves the trips alone. */
	private static void history(Runnable body) {
		Guard.run(BossHistory.GUARD_FEATURE, body);
	}

	/** Supplies by counter key as item stacks (a dosed key: doses of the item that holds {@code dose}). */
	private List<BossSession.Stack> supplyStacks(Map<String, Long> supplies) {
		List<BossSession.Stack> out = new ArrayList<>();
		for (Map.Entry<String, Long> e : supplies.entrySet()) {
			SupplyCounter.Rep rep = counter.rep(e.getKey());
			if (rep != null) {
				out.add(new BossSession.Stack(rep.id, e.getValue(), 0, e.getKey().startsWith("d:") ? rep.dose : 0));
			}
		}
		return out;
	}

	private void resetTrip() {
		boss = null;
		bossKey = null;
		kills = 0;
		used.clear();
		loot.clear();
		leftTick = -1;
		usedAtLeave = null;
		lastPoint = null;
		brought = Collections.emptySet();
		counts = Collections.emptyMap();
	}

	private WorldPoint position() {
		Player p = client.getLocalPlayer();
		return p == null || p.getLocalLocation() == null ? null : WorldPoint.fromLocalInstance(client, p.getLocalLocation());
	}

	private int canonical(int itemId) {
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null && comp.getNote() != -1 ? comp.getLinkedNoteId() : itemId;
	}

	// ---- Supplies of past trips (the supplies check) --------------------------------------

	private void remember(String key, Map<String, Long> supplies) {
		List<Map<String, Long>> list = new ArrayList<>(trips(key));
		list.add(supplies);
		while (list.size() > HISTORY) {
			list.remove(0);
		}
		history.put(key, list);
		try {
			configManager.setRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, HISTORY_KEY + key, gson.toJson(list));
		} catch (RuntimeException e) {
			log.debug("Could not store the trip supplies for {}", key, e);
		}
	}

	/** The supplies of the last (up to ten) trips at a boss (by {@link TripSupplies#bossKey}), oldest first. Client thread. */
	List<Map<String, Long>> trips(String key) {
		if (key == null || key.isEmpty()) {
			return Collections.emptyList();
		}
		List<Map<String, Long>> cached = history.get(key);
		if (cached != null) {
			return cached;
		}
		List<Map<String, Long>> loaded = null;
		try {
			String json = configManager.getRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, HISTORY_KEY + key);
			loaded = json == null ? null : gson.fromJson(json, HISTORY_TYPE);
		} catch (RuntimeException e) {
			log.debug("Could not read the trip supplies for {}", key, e);
		}
		List<Map<String, Long>> out = loaded == null ? Collections.emptyList() : loaded;
		// Only cache once the profile is known (before that nothing can be read).
		if (configManager.getRSProfileKey() != null) {
			history.put(key, out);
		}
		return out;
	}
}
