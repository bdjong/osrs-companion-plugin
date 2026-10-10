package com.osrscompanion;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;

/**
 * The boss history: one {@link BossSession} per visit to a boss, with its trips, kills, fight times, deaths,
 * bank visits, drops and supplies, sent to the website (POST /api/runelite/boss-sessions) for the Boss
 * history tab.
 *
 * <p>It has no events of its own: {@link BossTripTracker} (which records the trips for the trip planner and
 * the supplies check) hands it the bank, chat, loot, death, logout and tick events, and the supplies each of
 * its trips used. Where you are is the map region (the template region in an instance) against
 * {@link BossAreas} (the website's lairs once {@link BossAreasSync} has them, else the built-in ones); a kill
 * count message somewhere else marks that region as the boss's lair (kept per profile). Each kill notes the
 * region it happened in (the session's kill_regions), so the website can report kills outside the known
 * lairs. Client thread only; the work per tick is a region lookup and a few field reads.
 */
@Slf4j
@Singleton
public class BossHistory {
	static final String GUARD_FEATURE = "boss-history";
	static final String SESSION_KEY = "bossHistorySession";
	static final String UNSENT_KEY = "bossHistoryUnsent";
	static final String LAIRS_KEY = "bossHistoryLairs";
	/** An open session is re-sent at most this often (a kill, a drop or logging out: at once). */
	static final long SYNC_INTERVAL_MS = 30_000L;
	static final long PERSIST_INTERVAL_MS = 10_000L;
	/** A jump this far (tiles) in one tick is a teleport. */
	static final int TELEPORT_TILES = 64;
	static final int MAX_UNSENT = 20;
	static final int MAX_LEARNED_PER_BOSS = 6;
	/** A fight with no death for this long is forgotten. */
	static final int FIGHT_FORGET_TICKS = 1_000;
	private static final int LOGIN_GRACE_TICKS = 5;
	/** Kill count messages that are no kill (the Moons' chest is opened after the kills). */
	private static final String LUNAR_CHEST = "lunar chest";
	private static final Pattern DURATION = Pattern.compile(
		"(?i)(fight duration|completion time|duration):\\s*([0-9]+(?::[0-9]{1,2}){1,2}(?:\\.[0-9]+)?)");

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final ConfigManager configManager;
	private final OSRSCompanionApiClient apiClient;
	private final Gson gson;
	private final BossAreasSync areas;

	private BossSession session;
	/** Another boss's lair you walked into while the session (with data) runs: it starts on proof (a kill, a fight). */
	private BossAreas.Boss candidate;
	private long candidateSinceMs;
	private final BossKillMatcher matcher = new BossKillMatcher();
	/** NPC index -> the tick you first attacked it (boss NPCs only). */
	private final Map<Integer, Integer> fights = new HashMap<>();
	/** Learned lairs: boss key -> regions (where its kill count message came). */
	private Map<String, List<Integer>> learned = new HashMap<>();
	private List<BossSession> unsent = new ArrayList<>();
	private boolean restored;
	private int ticksLoggedIn;
	private int region = -1;
	/** The region you stand in (inside an instance: the instance's own one; {@link #region} is the template's). */
	private int rawRegion = -1;
	private boolean instance;
	private WorldPoint lastPoint;
	private long lastSyncMs;
	private long lastSentUpdate = -1;
	private long lastPersistMs;
	private long lastPersistedUpdate = -1;

	@Inject
	public BossHistory(Client client, ClientThread clientThread, OSRSCompanionConfig config, ConfigManager configManager,
		OSRSCompanionApiClient apiClient, Gson gson, BossAreasSync areas) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.configManager = configManager;
		this.apiClient = apiClient;
		this.gson = gson;
		this.areas = areas;
	}

	/** Plugin stop: the open session is stored (it goes on after a restart) and everything is forgotten. */
	void stop() {
		persist(true);
		reset();
	}

	private void reset() {
		session = null;
		candidate = null;
		matcher.clear();
		fights.clear();
		learned = new HashMap<>();
		unsent = new ArrayList<>();
		restored = false;
		ticksLoggedIn = 0;
		region = -1;
		rawRegion = -1;
		instance = false;
		lastPoint = null;
		lastSentUpdate = -1;
		lastPersistedUpdate = -1;
	}

	private boolean on() {
		return config.bossHistory();
	}

	// ---- Events (from BossTripTracker) -------------------------------------------------------

	void onTick() {
		if (!on()) {
			return;
		}
		if (++ticksLoggedIn < LOGIN_GRACE_TICKS) {
			return;
		}
		if (!restored) {
			if (configManager.getRSProfileKey() == null) {
				return;
			}
			restored = true;
			restore();
		}
		long now = System.currentTimeMillis();
		areas.tick(now);
		int tick = client.getTickCount();
		WorldPoint here = position();
		region = here == null ? -1 : here.getRegionID();
		Player me = client.getLocalPlayer();
		WorldPoint raw = me == null ? null : me.getWorldLocation();
		rawRegion = raw == null ? region : raw.getRegionID();
		WorldView view = client.getTopLevelWorldView();
		instance = view != null && view.isInstance();

		BossAreas.Boss lair = region < 0 ? null : BossAreas.lairAt(region, boss(), learned);
		if (lair != null && (session == null || !lair.key.equals(session.bossKey))) {
			if (session == null || !session.hasData()) {
				// Nothing recorded yet (walked through): the new lair's session replaces it.
				session = null;
				startSession(lair, now);
			} else if (candidate != lair) {
				candidate = lair;
				candidateSinceMs = now;
			}
		} else if (lair == null || session != null && lair.key.equals(session.bossKey)) {
			candidate = null;
		}

		trackFight(tick, now);

		BossSession s = session;
		if (s != null) {
			boolean inside = here != null && BossAreas.isLair(boss(), region, learned);
			if (s.tick(inside, now)) {
				log.debug("Boss history: in the lair of {} (trip {})", s.bossName, s.tripCount);
				persist(true);
			}
			if (here != null && lastPoint != null && !inside
				&& Math.max(Math.abs(here.getX() - lastPoint.getX()), Math.abs(here.getY() - lastPoint.getY())) > TELEPORT_TILES) {
				s.teleportedOut(now);
			}
			settle(matcher.tick(tick), now);
			long closeAt = s.closeAt(now);
			if (closeAt > 0) {
				closeSession(s.inside ? "idle" : "left", closeAt);
			}
		}
		lastPoint = here;
		sync(false, false);
		persist(false);
	}

	/** A bank interface opened. */
	void onBankOpened() {
		if (on() && session != null) {
			session.bankOpened(System.currentTimeMillis());
			persist(true);
		}
	}

	/** A game message (tags removed): kill counts and the game's fight durations. */
	void onChatMessage(String message) {
		if (!on() || message == null) {
			return;
		}
		String[] kc = BossTripTracker.killCount(message);
		if (kc != null) {
			onKillMessage(kc[0], parseCount(kc[1]));
			return;
		}
		Matcher m = DURATION.matcher(message);
		if (m.find()) {
			long ms = BossKillMatcher.parseDuration(m.group(2));
			String what = m.group(1).toLowerCase(Locale.ROOT);
			boolean total = what.startsWith("completion") || message.toLowerCase(Locale.ROOT).contains("raid is complete");
			matcher.duration(client.getTickCount(), ms, total);
		}
	}

	private void onKillMessage(String name, int killCount) {
		if (LUNAR_CHEST.equalsIgnoreCase(name.trim())) {
			return;
		}
		BossAreas.Boss b = BossAreas.byName(name);
		if (b == null) {
			return;
		}
		long now = System.currentTimeMillis();
		BossSession s = ensureSession(b, now);
		learn(b);
		s.addKill(killCount, now);
		noteKillRegion(s, b, 1);
		matcher.killMessage(client.getTickCount());
		// A kill goes to the website at once.
		sync(true, true);
		persist(true);
	}

	/** A boss NPC died. */
	void onNpcDeath(NPC npc) {
		if (!on() || npc == null) {
			return;
		}
		Integer start = fights.remove(npc.getIndex());
		BossAreas.Boss b = BossAreas.byName(npc.getName());
		if (start == null || b == null || session == null || !b.key.equals(session.bossKey)) {
			return;
		}
		int tick = client.getTickCount();
		long fightMs = (tick - start + 1) * BossKillMatcher.TICK_MS;
		matcher.died(tick, fightMs, b.deathIsKill);
	}

	/** You died. */
	void onLocalDeath() {
		if (on() && session != null) {
			session.died(System.currentTimeMillis());
			sync(true, true);
			persist(true);
		}
	}

	/**
	 * Loot from the loot tracker (NPC or event): the session's when it comes from its boss (or a boss, which
	 * then starts its session), or while you are in its lair (the bodyguards' drops, a raid's chest).
	 *
	 * @param items {itemId, quantity, gp} per stack
	 */
	void onLoot(String source, List<BossSession.Stack> items) {
		if (!on() || items == null || items.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		BossAreas.Boss b = lootBoss(source, boss(), session != null && session.inside);
		if (b == null) {
			return;
		}
		ensureSession(b, now).addDrop(source, items, now);
		sync(true, true);
		persist(true);
	}

	/**
	 * Which boss a drop goes to: the boss it comes from (by the loot tracker's name), else the open session's
	 * boss while you are in its lair (a bodyguard, a raid's chest under another name); null: no boss's.
	 */
	static BossAreas.Boss lootBoss(String source, BossAreas.Boss sessionBoss, boolean inLair) {
		BossAreas.Boss b = BossAreas.byName(source);
		if (b != null) {
			return b;
		}
		return inLair ? sessionBoss : null;
	}

	/** The supplies a boss trip of {@link BossTripTracker} used ({@code boss}: its kill count name). */
	void onTripSupplies(String boss, List<BossSession.Stack> supplies) {
		BossAreas.Boss b = BossAreas.byName(boss);
		BossSession s = session;
		if (!on() || s == null || b == null || !b.key.equals(s.bossKey)) {
			return;
		}
		s.addSupplies(supplies, System.currentTimeMillis());
		persist(true);
	}

	/** Logged out: the trip ends and the session is stored and sent at once; the next login loads it again. */
	void onLogout() {
		if (session != null) {
			settle(matcher.flush(client.getTickCount()), System.currentTimeMillis());
			session.loggedOut(System.currentTimeMillis());
			if (on()) {
				sync(true, true);
			}
			persist(true);
		}
		reset();
	}

	// ---- Sessions ---------------------------------------------------------------------------

	private BossAreas.Boss boss() {
		BossSession s = session;
		return s == null ? null : BossAreas.byKey(s.bossKey);
	}

	/** The session of this boss: the open one, relabelled (same lair), or a new one after closing the other. */
	private BossSession ensureSession(BossAreas.Boss b, long now) {
		BossSession s = session;
		if (s != null && !b.key.equals(s.bossKey) && !s.relabel(b, now)) {
			closeSession("next boss", now);
			s = null;
		}
		if (s == null) {
			long since = candidate == b ? candidateSinceMs : now;
			startSession(b, since);
			s = session;
		}
		candidate = null;
		return s;
	}

	private void startSession(BossAreas.Boss b, long now) {
		session = BossSession.start(b, now);
		session.player = SyncGuard.getPlayerUsername(client);
		matcher.clear();
		lastSentUpdate = -1;
		log.debug("Boss history: session started at {}", b.name);
	}

	private void closeSession(String reason, long atMs) {
		BossSession s = session;
		if (s == null) {
			return;
		}
		settle(matcher.flush(client.getTickCount()), atMs);
		s.close(reason, atMs);
		session = null;
		candidate = null;
		fights.clear();
		configManager.unsetRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, SESSION_KEY);
		lastPersistedUpdate = -1;
		if (s.hasData()) {
			queue(s);
			saveUnsent();
			sendUnsent();
		}
		log.debug("Boss history: session at {} closed ({}), {} kills", s.bossName, reason, s.kills);
	}

	/** Kills and fight times the matcher settled. */
	private void settle(BossKillMatcher.Result r, long now) {
		BossSession s = session;
		if (s == null || !r.any()) {
			return;
		}
		for (int i = 0; i < r.deathKills; i++) {
			s.addKill(0, now);
		}
		noteKillRegion(s, boss(), r.deathKills);
		for (long ms : r.fightMs) {
			s.addKillTime(ms, now);
		}
		if (r.deathKills > 0) {
			sync(true, true);
		}
	}

	/** The boss NPC you attack: the fight's first tick, and proof you are at that boss. */
	private void trackFight(int tick, long now) {
		Player me = client.getLocalPlayer();
		Actor target = me == null ? null : me.getInteracting();
		if (target instanceof NPC) {
			NPC npc = (NPC) target;
			BossAreas.Boss b = BossAreas.byName(npc.getName());
			if (b != null) {
				if (session == null || !b.key.equals(session.bossKey)) {
					ensureSession(b, now);
				}
				fights.putIfAbsent(npc.getIndex(), tick);
			}
		}
		if (!fights.isEmpty()) {
			for (Iterator<Map.Entry<Integer, Integer>> it = fights.entrySet().iterator(); it.hasNext(); ) {
				if (tick - it.next().getValue() > FIGHT_FORGET_TICKS) {
					it.remove();
				}
			}
		}
	}

	/** Where {@code n} kills just happened, for the website's boss location reports. */
	private void noteKillRegion(BossSession s, BossAreas.Boss b, int n) {
		if (b == null || region < 0) {
			return;
		}
		noteKillRegion(s, b, region, rawRegion, instance, n, learned);
	}

	/** {@link #noteKillRegion}: {@code region} is the one the lairs are matched on (the template's in an instance). */
	static void noteKillRegion(BossSession s, BossAreas.Boss b, int region, int rawRegion, boolean instance, int n,
		Map<String, ? extends java.util.Collection<Integer>> learned) {
		java.util.Collection<Integer> mine = learned == null ? null : learned.get(b.key);
		s.addKillRegion(rawRegion, region, instance, BossAreas.isKnownLair(b, region), BossAreas.learnedHas(learned, b.key, region), n,
			BossAreas.regionsOf(b), mine, BossAreas.fromServer() ? "server" : "built-in");
	}

	/** A kill count message outside every known lair: this region is the boss's lair from now on. */
	private void learn(BossAreas.Boss b) {
		int r = region;
		if (r < 0 || BossAreas.isLair(b, r, learned) || BossAreas.lairAt(r, null, learned) != null) {
			return;
		}
		List<Integer> list = new ArrayList<>(learned.getOrDefault(b.key, new ArrayList<>()));
		list.add(r);
		while (list.size() > MAX_LEARNED_PER_BOSS) {
			list.remove(0);
		}
		learned.put(b.key, list);
		log.debug("Boss history: region {} is a lair of {}", r, b.name);
		try {
			configManager.setRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, LAIRS_KEY, gson.toJson(learned));
		} catch (RuntimeException e) {
			log.debug("Could not store the learned boss lairs", e);
		}
	}

	// ---- Sync and storage -------------------------------------------------------------------

	private boolean canSync() {
		return on() && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client);
	}

	/** Sends the open session when it has data and changed: every {@link #SYNC_INTERVAL_MS}, or now. */
	private void sync(boolean now, boolean urgent) {
		BossSession s = session;
		if (s == null || !s.hasData() || s.updatedMs == lastSentUpdate || !canSync()) {
			return;
		}
		String name = s.player != null ? s.player : SyncGuard.getPlayerUsername(client);
		if (name == null) {
			return;
		}
		s.player = name;
		long t = System.currentTimeMillis();
		if (!now && t - lastSyncMs < SYNC_INTERVAL_MS) {
			return;
		}
		lastSyncMs = t;
		lastSentUpdate = s.updatedMs;
		apiClient.sendBossSession(s.payload(name), null, urgent);
	}

	private void queue(BossSession s) {
		if (s.player == null) {
			s.player = SyncGuard.getPlayerUsername(client);
		}
		if (s.player != null && canSync()) {
			unsent.add(s);
			while (unsent.size() > MAX_UNSENT) {
				unsent.remove(0);
			}
		}
	}

	/** Finished sessions wait until the website has them; each is dropped once it does. */
	private void sendUnsent() {
		if (!canSync()) {
			return;
		}
		for (BossSession s : new ArrayList<>(unsent)) {
			apiClient.sendBossSession(s.payload(s.player), () -> Guard.invokeLater(clientThread, GUARD_FEATURE, () -> {
				unsent.remove(s);
				saveUnsent();
			}), true);
		}
	}

	private void persist(boolean now) {
		BossSession s = session;
		if (s == null || s.updatedMs == lastPersistedUpdate || configManager.getRSProfileKey() == null) {
			return;
		}
		long t = System.currentTimeMillis();
		if (!now && t - lastPersistMs < PERSIST_INTERVAL_MS) {
			return;
		}
		lastPersistMs = t;
		lastPersistedUpdate = s.updatedMs;
		configManager.setRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, SESSION_KEY, gson.toJson(s));
	}

	private void saveUnsent() {
		if (unsent.isEmpty()) {
			configManager.unsetRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, UNSENT_KEY);
		} else {
			configManager.setRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, UNSENT_KEY, gson.toJson(unsent));
		}
	}

	/** First ticks of a login: the stored session, the unsent ones and the learned lairs. */
	private void restore() {
		try {
			String json = configManager.getRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, LAIRS_KEY);
			Map<String, List<Integer>> m = json == null ? null : gson.fromJson(json, new TypeToken<Map<String, List<Integer>>>() { }.getType());
			learned = m == null ? new HashMap<>() : new LinkedHashMap<>(m);
		} catch (RuntimeException e) {
			log.debug("Dropping unreadable learned boss lairs", e);
			learned = new HashMap<>();
		}
		unsent = new ArrayList<>();
		try {
			String json = configManager.getRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, UNSENT_KEY);
			List<BossSession> list = json == null ? null : gson.fromJson(json, new TypeToken<List<BossSession>>() { }.getType());
			if (list != null) {
				for (BossSession s : list) {
					if (s != null && s.uuid != null && s.player != null) {
						s.resumed();
						unsent.add(s);
					}
				}
			}
		} catch (RuntimeException e) {
			log.debug("Dropping unreadable unsent boss sessions", e);
			configManager.unsetRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, UNSENT_KEY);
		}
		if (session == null) {
			BossSession stored = null;
			try {
				String json = configManager.getRSProfileConfiguration(OSRSCompanionConfig.CONFIG_GROUP, SESSION_KEY);
				stored = json == null ? null : gson.fromJson(json, BossSession.class);
			} catch (RuntimeException e) {
				log.debug("Dropping an unreadable boss session", e);
			}
			if (stored != null && stored.uuid != null && stored.isOpen() && BossAreas.byKey(stored.bossKey) != null) {
				stored.resumed();
				session = stored;
				lastPersistedUpdate = stored.updatedMs;
				lastSentUpdate = -1;
				log.debug("Boss history: picked up the session at {} ({} kills)", stored.bossName, stored.kills);
			}
		}
		sendUnsent();
	}

	private WorldPoint position() {
		Player p = client.getLocalPlayer();
		return p == null || p.getLocalLocation() == null ? null : WorldPoint.fromLocalInstance(client, p.getLocalLocation());
	}

	static int parseCount(String s) {
		try {
			return s == null ? 0 : Integer.parseInt(s.replace(",", "").trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** For tests: the open session. */
	BossSession session() {
		return session;
	}
}
