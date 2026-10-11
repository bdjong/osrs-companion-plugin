package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.BossGear;
import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.model.SlayerGuide;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Live activity: while you are logged in (and "Share live activity" is on, with an App Key), tells the
 * website whether you are online or idle, what you are doing ({@link LiveActivity} picks it), your world
 * and the area. Shown on your home page and the admin page's Activity tab.
 *
 * <ul>
 *   <li>Each tick the status is worked out from what the client already has (a few varbits, your tile,
 *       target and animation, the bank widget, the slayer helper and farm run state). Cheap: no scans.</li>
 *   <li>A change of activity (or idle on / off) goes out as a priority upload once it held for
 *       {@link #DEBOUNCE_MS}, at most every {@link #MIN_CHANGE_GAP_MS}.</li>
 *   <li>Otherwise the status rides along with every outbox batch, and the {@link WebVersionWatcher} sends
 *       it as a heartbeat when nothing reached the website for {@link #HEARTBEAT_MS}; the website shows you
 *       as offline after about 35 s without one.</li>
 *   <li>Logging out (or stopping the plugin) sends "offline" at once. Switching the setting off sends
 *       nothing more: the website then shows you as offline after its timeout.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class LiveActivityMonitor {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "live-activity";

	/** A new activity is sent once it held this long (no flapping between two). */
	static final long DEBOUNCE_MS = 3_000;
	/** At most one change upload this often (the heartbeat still carries the latest). */
	static final long MIN_CHANGE_GAP_MS = 5_000;
	/** The website must hear from the plugin at least this often while you are logged in. */
	static final long HEARTBEAT_MS = 15_000;
	/** A cast counts when Magic XP comes within this many ticks of the click. */
	static final int CAST_XP_TICKS = 5;
	/** A teleport counts when you moved far within this many ticks of the click. */
	static final int TELEPORT_TICKS = 12;
	/** Moving at least this many tiles at once (or to another plane) is a teleport. */
	static final int TELEPORT_TILES = 16;

	/** One status, as sent. Immutable: built on the client thread, read by the outbox and the watcher. */
	static final class Status {
		final String player;
		final String state;
		final LiveActivity.Choice choice;
		final long since;
		final int world;
		final String area;
		final int regionId;

		Status(String player, String state, LiveActivity.Choice choice, long since, int world, String area, int regionId) {
			this.player = player;
			this.state = state;
			this.choice = choice;
			this.since = since;
			this.world = world;
			this.area = area;
			this.regionId = regionId;
		}

		/** Same key: the same activity (counts, world and area changes ride on the heartbeat). */
		String key() {
			return state + "|" + choice.key();
		}

		Map<String, Object> body() {
			Map<String, Object> b = new LinkedHashMap<>();
			b.put("player_username", player);
			b.put("state", state);
			b.put("activity", choice.kind.wire);
			b.put("subject", choice.subject);
			b.put("npc_id", choice.npcId);
			// The website writes the text and picture from these.
			b.put("parts", choice.parts);
			b.put("since", since);
			b.put("world", world);
			b.put("area", area);
			b.put("region_id", regionId);
			b.put("plugin_version", PluginVersion.VERSION);
			return b;
		}
	}

	private final Client client;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final Gson gson;
	private final SlayerHelperManager slayerHelper;
	private final FarmRunManager farmRun;
	private final BossGearManager bossGear;

	// Read by the outbox and the watcher (other threads).
	private volatile Status current;
	private volatile boolean sharing;

	// Client thread only.
	private final LiveActivity.IdleTracker idle = new LiveActivity.IdleTracker();
	private final Map<String, Integer> lastXp = new HashMap<>();
	private final Map<String, Long> lastSkillingXpMs = new HashMap<>();
	/** XP gains not yet sorted into skilling or combat: skill, tick. */
	private final List<Object[]> pendingXp = new ArrayList<>();
	private int lastHitpointsXpTick = -1;
	private boolean xpGainedSinceTick;
	private String combatNpc;
	private Integer combatNpcId;
	private long combatAtMs;
	private String bossNpc;
	private Integer bossNpcId;
	private int bossRegion;
	private long bossAtMs;
	private String activityKey;
	private long activitySinceMs;
	private List<BossGear.CatalogBoss> catalogSeen;
	private Set<String> catalogBosses = Collections.emptySet();
	// Magic: the last "Cast" / "Break" click, the cast confirmed by Magic XP and the last teleport.
	private MagicSpells.Cast pendingCast;
	private int pendingCastTick;
	private MagicSpells.Cast lastCast;
	private long lastCastMs;
	private MagicSpells.Cast teleport;
	private long teleportMs;
	private int alchs;
	private long combatMagicMs;
	private WorldPoint lastAt;
	// Skilling items: inventory counts, and the item a skill last gave.
	private Map<Integer, Integer> lastInventory;
	private int gainedItem = -1;
	private int gainedTick = -1;
	private final Map<String, String> skillItem = new HashMap<>();
	private FarmRoute farmItemRoute;
	private String farmItem;

	// Sending (client thread, the outbox's and the watcher's).
	private String lastSentKey;
	private String pendingKey;
	private long pendingSinceMs;
	private long lastChangeSentMs;
	/** The player a status was sent for this login; an offline status goes out for them at logout. */
	private String sentFor;

	@Inject
	public LiveActivityMonitor(Client client, OSRSCompanionConfig config, OSRSCompanionApiClient apiClient, Gson gson,
			SlayerHelperManager slayerHelper, FarmRunManager farmRun, BossGearManager bossGear) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.gson = gson;
		this.slayerHelper = slayerHelper;
		this.farmRun = farmRun;
		this.bossGear = bossGear;
	}

	/** Every outbox batch carries the current status from now on. */
	public void start() {
		apiClient.setActivitySource(() -> Guard.call(GUARD_FEATURE, this::statusForBatch, null));
	}

	/** Plugin stop: "offline" for the player (queued before the outbox's last flush), and no more statuses. */
	public void stop() {
		apiClient.setActivitySource(null);
		Guard.run(GUARD_FEATURE, this::sendOffline);
		current = null;
	}

	// ---- Events ---------------------------------------------------------------------------------

	@Subscribe
	public void onGameTick(GameTick e) {
		Guard.run(GUARD_FEATURE, this::tick);
	}

	@Subscribe
	public void onStatChanged(StatChanged e) {
		Guard.run(GUARD_FEATURE, () -> {
			String skill = e.getSkill().getName().toLowerCase(Locale.ROOT);
			Integer previous = lastXp.put(skill, e.getXp());
			// The first value after login is the server's replay, not a gain.
			if (previous == null || e.getXp() <= previous) {
				return;
			}
			xpGainedSinceTick = true;
			int tick = client.getTickCount();
			if ("hitpoints".equals(skill)) {
				lastHitpointsXpTick = tick;
			}
			pendingXp.add(new Object[]{skill, tick});
		});
	}

	/** A "Cast" (spell widget, or a selected spell used on an item / NPC) or a tablet's "Break": remembered until XP or a jump confirms it. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e) {
		Guard.run(GUARD_FEATURE, () -> {
			String option = e.getMenuOption();
			if (option == null || !(option.contains("Cast") || option.contains("Break"))) {
				return;
			}
			MagicSpells.Cast cast = MagicSpells.fromMenu(option, e.getMenuTarget(), e.getMenuAction() == MenuAction.WIDGET_TARGET_ON_NPC);
			if (cast != null) {
				pendingCast = cast;
				pendingCastTick = client.getTickCount();
			}
		});
	}

	/** The inventory item that went up (what a skill gave), noted items as themselves. Not while banking. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (e.getContainerId() != InventoryID.INV) {
				return;
			}
			ItemContainer inv = e.getItemContainer();
			Map<Integer, Integer> counts = new HashMap<>();
			if (inv != null) {
				for (Item item : inv.getItems()) {
					if (item != null && item.getId() > 0) {
						counts.merge(item.getId(), item.getQuantity(), Integer::sum);
					}
				}
			}
			Map<Integer, Integer> before = lastInventory;
			lastInventory = counts;
			if (before == null || client.getWidget(InterfaceID.Bankmain.UNIVERSE) != null) {
				return;
			}
			for (Map.Entry<Integer, Integer> c : counts.entrySet()) {
				if (c.getValue() > before.getOrDefault(c.getKey(), 0)) {
					gainedItem = c.getKey();
					gainedTick = client.getTickCount();
					return;
				}
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.LOGIN_SCREEN_AUTHENTICATOR) {
				sendOffline();
				reset();
			}
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e) {
		Guard.run(GUARD_FEATURE, () -> {
			if (OSRSCompanionConfig.CONFIG_GROUP.equals(e.getGroup()) && OSRSCompanionConfig.SHARE_LIVE_ACTIVITY_KEY.equals(e.getKey())) {
				// Switched back on: the next tick sends the status at once.
				synchronized (this) {
					lastSentKey = null;
				}
			}
		});
	}

	// ---- The status -----------------------------------------------------------------------------

	private void tick() {
		Player local = client.getLocalPlayer();
		String player = SyncGuard.getPlayerUsername(client);
		if (client.getGameState() != GameState.LOGGED_IN || local == null || player == null) {
			return;
		}
		long now = System.currentTimeMillis();
		int tick = client.getTickCount();
		WorldPoint at = local.getLocalLocation() == null ? local.getWorldLocation() : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		int region = at == null ? -1 : at.getRegionID();

		sortXp(now, tick);
		checkTeleport(at, now, tick);
		idle.tick(now, at == null ? 0 : at.getX(), at == null ? 0 : at.getY(), at == null ? 0 : at.getPlane(),
			local.getAnimation() != -1, xpGainedSinceTick);
		xpGainedSinceTick = false;

		LiveActivity.Signals s = new LiveActivity.Signals();
		readRaid(s, region);
		readTarget(s, local, now, region);
		readSlayer(s);
		readFarmRun(s);
		s.skill = LiveActivity.skillingSkill(lastSkillingXpMs, now);
		readMagic(s, now);
		s.bankOpen = client.getWidget(InterfaceID.Bankmain.UNIVERSE) != null;
		s.idle = idle.isIdle(now);
		LiveActivity.Choice choice = LiveActivity.choose(s);

		String key = choice.key();
		if (!key.equals(activityKey)) {
			activityKey = key;
			activitySinceMs = choice.kind == LiveActivity.Kind.IDLE && idle.lastActiveMs() > 0 ? idle.lastActiveMs() : now;
		}
		String state = s.idle ? "idle" : "online";
		String area = s.raid != null ? s.raid : s.minigame != null ? s.minigame : areaName(at, region);
		Status status = new Status(player, state, choice, activitySinceMs, client.getWorld(), area, region);
		current = status;
		sharing = config.shareLiveActivity() && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client);
		if (sharing) {
			maybeSendChange(status, now);
		}
	}

	/**
	 * XP gains since the last tick: skilling ones are remembered per skill (see {@link LiveActivity#isSkillingXp}),
	 * with the item that came with them; Magic XP confirms the last cast (and counts an alch).
	 */
	private void sortXp(long now, int tick) {
		for (Object[] gain : pendingXp) {
			String skill = (String) gain[0];
			int at = (int) gain[1];
			boolean withHitpoints = at == lastHitpointsXpTick;
			if ("magic".equals(skill)) {
				if (withHitpoints) {
					combatMagicMs = now;
				}
				MagicSpells.Cast cast = pendingCast;
				if (cast != null && cast.type != MagicSpells.Type.TELEPORT && at - pendingCastTick <= CAST_XP_TICKS) {
					if (lastCast != cast && (cast.type == MagicSpells.Type.HIGH_ALCH || cast.type == MagicSpells.Type.LOW_ALCH)) {
						alchs++;
					}
					lastCast = cast;
					lastCastMs = now;
				}
			}
			if (LiveActivity.isSkillingXp(skill, withHitpoints)) {
				lastSkillingXpMs.put(skill, now);
				if (!"magic".equals(skill) && gainedItem > 0 && Math.abs(at - gainedTick) <= 1) {
					String name = itemName(gainedItem);
					if (name != null) {
						skillItem.put(skill, name);
					}
				}
			}
		}
		pendingXp.clear();
	}

	/** A teleport click followed by a long jump (or a plane change) within {@link #TELEPORT_TICKS}: you teleported. */
	private void checkTeleport(WorldPoint at, long now, int tick) {
		WorldPoint before = lastAt;
		lastAt = at;
		MagicSpells.Cast cast = pendingCast;
		if (cast == null || cast.type != MagicSpells.Type.TELEPORT || at == null || before == null) {
			return;
		}
		if (tick - pendingCastTick > TELEPORT_TICKS) {
			pendingCast = null;
			return;
		}
		if (before.getPlane() != at.getPlane() || Math.max(Math.abs(before.getX() - at.getX()), Math.abs(before.getY() - at.getY())) >= TELEPORT_TILES) {
			teleport = cast;
			teleportMs = now;
			pendingCast = null;
		}
	}

	/** Magic signals: training Magic in a fight, the cast behind skilling Magic, a teleport shown for a minute. */
	private void readMagic(LiveActivity.Signals s, long now) {
		s.combatMagic = s.combatNpc != null && now - combatMagicMs <= LiveActivity.COMBAT_GRACE_MS;
		if (s.combatMagic && lastCast != null && lastCast.type == MagicSpells.Type.OFFENSIVE && now - lastCastMs <= LiveActivity.COMBAT_GRACE_MS) {
			s.combatSpell = lastCast.spell;
		}
		s.alchs = alchs;
		if (s.skill != null) {
			s.skillItem = skillItem.get(s.skill);
		}
		if (lastCast != null && now - lastCastMs <= LiveActivity.SKILLING_WINDOW_MS) {
			s.magicCast = lastCast;
		}
		if (teleport != null && now - teleportMs <= LiveActivity.TELEPORT_SHOW_MS) {
			// Only while nothing else was trained since (a skill's XP after the teleport wins).
			Long other = s.skill == null || "magic".equals(s.skill) ? null : lastSkillingXpMs.get(s.skill);
			if (other == null || other <= teleportMs) {
				s.teleport = teleport;
			}
		} else {
			teleport = null;
		}
	}

	/** An item's name (a noted item as the item), or null. Client thread. */
	private String itemName(int id) {
		ItemComposition c = client.getItemDefinition(id);
		if (c != null && c.getNote() != -1) {
			c = client.getItemDefinition(c.getLinkedNoteId());
		}
		String name = c == null ? null : c.getName();
		return name == null || name.isEmpty() || "null".equalsIgnoreCase(name) ? null : name;
	}

	private void readRaid(LiveActivity.Signals s, int region) {
		if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1) {
			s.raid = "Chambers of Xeric";
		} else if (client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS) >= 2) {
			s.raid = "Theatre of Blood";
		} else {
			s.raid = LiveActivity.RAID_REGIONS.get(region);
		}
		if (s.raid == null) {
			s.minigame = LiveActivity.MINIGAME_REGIONS.get(region);
			if (s.minigame == null && client.getVarbitValue(VarbitID.PLAYER_IN_GAUNTLET) == 1) {
				s.minigame = "The Gauntlet";
			}
		}
	}

	/** Combat and boss fights: who you attack, kept for a short grace between kills. */
	private void readTarget(LiveActivity.Signals s, Player local, long now, int region) {
		Actor target = local.getInteracting();
		if (target instanceof NPC && ((NPC) target).getCombatLevel() > 0 && target.getName() != null) {
			String name = target.getName();
			Integer id = npcId((NPC) target);
			combatNpc = name;
			combatNpcId = id;
			combatAtMs = now;
			if (LiveActivity.isBoss(name, catalogBosses())) {
				bossNpc = name;
				bossNpcId = id;
				bossAtMs = now;
				bossRegion = region;
			}
		}
		if (bossNpc != null && now - bossAtMs <= LiveActivity.BOSS_GRACE_MS && region == bossRegion) {
			s.boss = bossNpc;
			s.bossId = bossNpcId;
		} else {
			bossNpc = null;
			bossNpcId = null;
		}
		if (combatNpc != null && now - combatAtMs <= LiveActivity.COMBAT_GRACE_MS) {
			s.combatNpc = combatNpc;
			s.combatNpcId = combatNpcId;
		} else {
			combatNpc = null;
			combatNpcId = null;
		}
	}

	/** The NPC's id: the transformed composition's for NPCs that transform, else its own. */
	private static Integer npcId(NPC npc) {
		NPCComposition t = npc.getTransformedComposition();
		return LiveActivity.npcId(npc.getId(), t == null ? null : t.getId());
	}

	private void readSlayer(LiveActivity.Signals s) {
		SlayerHelperManager.Task task = slayerHelper.getTask();
		if (task == null || task.remaining <= 0) {
			return;
		}
		s.slayerTask = task.displayName();
		s.slayerLeft = task.remaining;
		boolean onTarget = s.combatNpc != null && slayerHelper.isTaskTarget(s.combatNpc);
		s.slaying = slayerHelper.getHere() != null || onTarget;
		if (onTarget) {
			s.slayerNpc = s.combatNpc;
			s.slayerNpcId = s.combatNpcId;
		}
	}

	private void readFarmRun(LiveActivity.Signals s) {
		FarmRoute route = farmRun.isRunning() ? farmRun.getRoute() : null;
		if (route == null) {
			return;
		}
		s.farmRoute = LiveActivity.notBlank(route.getName()) ? route.getName() : "Farm run";
		int[] progress = farmRun.progress();
		s.farmDone = progress[0];
		s.farmTotal = progress[1];
		if (route != farmItemRoute) {
			farmItemRoute = route;
			Integer item = route.pictureItem();
			farmItem = item == null ? null : itemName(item);
		}
		s.farmItem = farmItem;
	}

	/** The area's name from the slayer guide's locations; null when unknown (the website names the region). */
	private String areaName(WorldPoint at, int region) {
		if (at == null) {
			return null;
		}
		SlayerGuide.Location here = slayerHelper.getHere();
		if (here != null && LiveActivity.notBlank(here.getName())) {
			return here.getName();
		}
		for (SlayerGuide.Location l : slayerHelper.guideLocations()) {
			if (l != null && LiveActivity.notBlank(l.getName()) && l.contains(at.getX(), at.getY(), at.getPlane(), region)) {
				return l.getName();
			}
		}
		return null;
	}

	/** The boss gear catalog's boss names (lower case), rebuilt when the catalog changes. */
	private Set<String> catalogBosses() {
		List<BossGear.CatalogBoss> catalog = bossGear.getCatalog();
		if (catalog != catalogSeen) {
			catalogSeen = catalog;
			Set<String> names = new HashSet<>();
			if (catalog != null) {
				for (BossGear.CatalogBoss b : catalog) {
					if (b != null && b.getName() != null) {
						names.add(b.getName().trim().toLowerCase(Locale.ROOT));
					}
				}
			}
			catalogBosses = names;
		}
		return catalogBosses;
	}

	// ---- Sending ----------------------------------------------------------------------------------

	/** Whether a change goes out now: it held {@link #DEBOUNCE_MS} and the last one is {@link #MIN_CHANGE_GAP_MS} ago. */
	static boolean shouldSendChange(String key, String lastSentKey, String pendingKey, long pendingSinceMs,
			long lastChangeSentMs, long nowMs) {
		if (key.equals(lastSentKey)) {
			return false;
		}
		if (lastSentKey == null) {
			return true;
		}
		return key.equals(pendingKey) && nowMs - pendingSinceMs >= DEBOUNCE_MS && nowMs - lastChangeSentMs >= MIN_CHANGE_GAP_MS;
	}

	private synchronized void maybeSendChange(Status status, long now) {
		String key = status.key();
		if (key.equals(lastSentKey)) {
			pendingKey = null;
			return;
		}
		if (!key.equals(pendingKey)) {
			pendingKey = key;
			pendingSinceMs = now;
		}
		if (shouldSendChange(key, lastSentKey, pendingKey, pendingSinceMs, lastChangeSentMs, now)) {
			lastChangeSentMs = now;
			send(status, true);
		}
	}

	/** True while there is a status to share: logged in, the setting on, an App Key, a normal world. */
	public boolean isSharing() {
		return sharing && current != null && client.getGameState() != GameState.LOGIN_SCREEN;
	}

	/** The heartbeat (the watcher, when nothing reached the website lately): the current status, at once. */
	public void heartbeat() {
		Guard.run(GUARD_FEATURE, () -> {
			Status s = current;
			if (s != null && isSharing()) {
				send(s, true);
			}
		});
	}

	/** The status for a batch about to go out (it then counts as sent), or null. Any thread. */
	private synchronized String statusForBatch() {
		Status s = current;
		if (s == null || !isSharing()) {
			return null;
		}
		lastSentKey = s.key();
		sentFor = s.player;
		return gson.toJson(s.body());
	}

	private synchronized void send(Status s, boolean priority) {
		lastSentKey = s.key();
		sentFor = s.player;
		apiClient.sendActivity(s.player, gson.toJson(s.body()), priority);
	}

	/** Logout or plugin stop: "offline" for the player a status was sent for, at once. */
	private synchronized void sendOffline() {
		String player = sentFor;
		sentFor = null;
		lastSentKey = null;
		current = null;
		if (player == null || !config.shareLiveActivity() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("player_username", player);
		b.put("state", "offline");
		b.put("activity", "offline");
		b.put("since", System.currentTimeMillis());
		b.put("plugin_version", PluginVersion.VERSION);
		apiClient.sendActivity(player, gson.toJson(b), true);
	}

	private void reset() {
		idle.reset();
		lastXp.clear();
		lastSkillingXpMs.clear();
		pendingXp.clear();
		lastHitpointsXpTick = -1;
		xpGainedSinceTick = false;
		combatNpc = null;
		combatNpcId = null;
		bossNpc = null;
		bossNpcId = null;
		pendingCast = null;
		lastCast = null;
		teleport = null;
		alchs = 0;
		combatMagicMs = 0;
		lastAt = null;
		lastInventory = null;
		gainedItem = -1;
		skillItem.clear();
		farmItemRoute = null;
		farmItem = null;
		activityKey = null;
		sharing = false;
		synchronized (this) {
			pendingKey = null;
			lastChangeSentMs = 0;
		}
	}
}
