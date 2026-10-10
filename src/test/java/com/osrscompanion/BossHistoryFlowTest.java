package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The boss history end to end: game ticks, chat, NPC deaths and loot through {@link BossTripTracker} into
 * {@link BossHistory}, against a fake client (proxies), down to what is sent to the website. The report this
 * reproduces: three visits to the Alchemical Hydra, the first two ended by a teleport out with nothing killed,
 * the third with two kills and two drops, and the website showing 0 kills.
 */
public class BossHistoryFlowTest {
	/** The Alchemical Hydra's lair (region 5536) and somewhere far away (a teleport out). */
	private static final int[] LAIR = {1364, 10265};
	private static final int[] AWAY = {3150, 3635};

	private final int[] tick = {100};
	private final int[] pos = {LAIR[0], LAIR[1]};
	private final Object[] target = {null};
	private final List<Map<String, Object>> sent = new ArrayList<>();
	private NPC hydra;
	private BossHistory history;
	private BossTripTracker tracker;

	@Before
	public void setUp() throws Exception {
		WorldView view = proxy(WorldView.class, (name, args) -> {
			switch (name) {
				case "getBaseX":
					return pos[0] - 1;
				case "getBaseY":
					return pos[1] - 1;
				default:
					return NONE;
			}
		});
		hydra = proxy(NPC.class, (name, args) -> {
			switch (name) {
				case "getName":
					return "Alchemical Hydra";
				case "getIndex":
					return 7;
				default:
					return NONE;
			}
		});
		Player me = proxy(Player.class, (name, args) -> {
			switch (name) {
				// One tile into the scene: the world point is the view's base plus one.
				case "getLocalLocation":
					return new LocalPoint(192, 192, -1);
				case "getWorldLocation":
					return new WorldPoint(pos[0], pos[1], 0);
				case "getInteracting":
					return target[0];
				case "getName":
					return "Tester";
				default:
					return NONE;
			}
		});
		Client client = proxy(Client.class, (name, args) -> {
			switch (name) {
				case "getTickCount":
					return tick[0];
				case "getGameState":
					return GameState.LOGGED_IN;
				case "getWorldType":
					return EnumSet.noneOf(WorldType.class);
				case "getLocalPlayer":
					return me;
				case "getWorldView":
				case "getTopLevelWorldView":
					return view;
				default:
					return NONE;
			}
		});
		TestOSRSCompanionConfig config = new TestOSRSCompanionConfig() {
			@Override
			public String osrsCompanionAppKey() {
				return "test-app-key-1234";
			}

			@Override
			public boolean bossTrips() {
				// The trip planner's half needs the supply counter; this test is about the boss history.
				return false;
			}
		};
		OSRSCompanionApiClient api = new OSRSCompanionApiClient(config, new Gson(), new okhttp3.OkHttpClient()) {
			@Override
			public void sendBossSession(Map<String, Object> body, Runnable onSuccess, boolean urgent) {
				sent.add(body);
			}
		};
		// No profile (nothing stored or restored) and no website lairs: the built-in ones.
		ConfigManager configManager = allocate(ConfigManager.class);
		BossAreasSync areas = allocate(BossAreasSync.class);
		set(areas, "loaded", true);
		set(areas, "inFlight", true);
		BossAreas.setServer(null, null);
		history = new BossHistory(client, null, config, configManager, api, new Gson(), areas);
		set(history, "restored", true);
		tracker = new BossTripTracker(client, config, api, configManager, null, null, new Gson(), history);
	}

	@Test
	public void killCountMessagesInFilteredChatCount() {
		threeVisits(ChatMessageType.SPAM, "Your Alchemical Hydra kill count is: <col=ff0000>%d</col>.");
		assertTwoKillsTwoDrops();
	}

	@Test
	public void killCountMessagesWithTheGamesColourCodesCount() {
		threeVisits(ChatMessageType.GAMEMESSAGE, "Your Alchemical Hydra kill count is: @mes_hl_red@%d</col>.");
		assertTwoKillsTwoDrops();
		assertEquals(124, history.session().killCount);
	}

	@Test
	public void theBossesOwnDropIsAKillWhenNoKillCountMessageCame() {
		threeVisits(null, null);
		assertTwoKillsTwoDrops();
	}

	@Test
	public void aKillCountMessageAndTheDropAreOneKill() {
		threeVisits(ChatMessageType.GAMEMESSAGE, "Your Alchemical Hydra kill count is: <col=ff0000>%d</col>.");
		assertTwoKillsTwoDrops();
		assertEquals(124, history.session().killCount);
		// The fight times come from the boss's deaths.
		assertEquals(2, history.session().killTimeCount);
	}

	@Test
	public void visitsWithNothingKilledAreNeverSent() {
		for (int v = 0; v < 2; v++) {
			at(LAIR, 20);
			at(AWAY, 20);
		}
		BossSession s = history.session();
		assertEquals(2, s.tripCount);
		assertEquals(0, s.kills);
		assertTrue(sent.isEmpty());
		// Away long enough: the session closes, and with nothing in it, it is dropped.
		set(s, "leftMs", System.currentTimeMillis() - BossSession.AWAY_CLOSE_MS - 1_000);
		at(AWAY, 1);
		assertNull(history.session());
		assertTrue(sent.isEmpty());
	}

	/**
	 * Visits 1 and 2: in the lair and teleported out. Visit 3: two kills (with this kill count message, or
	 * none) and two drops, then a teleport out.
	 */
	private void threeVisits(ChatMessageType type, String killMessage) {
		for (int v = 0; v < 2; v++) {
			at(LAIR, 20);
			at(AWAY, 20);
		}
		at(LAIR, 5);
		for (int k = 0; k < 2; k++) {
			target[0] = hydra;
			at(LAIR, 60);
			target[0] = null;
			if (killMessage != null) {
				tracker.onChatMessage(new ChatMessage(null, type, "", String.format(killMessage, 123 + k), null, 0));
			}
			history.onNpcDeath(hydra);
			at(LAIR, 4);
			history.onLoot("Alchemical Hydra", Collections.singletonList(new BossSession.Stack(22966, 1, 5_000_000, 0)), true);
			at(LAIR, 20);
		}
		at(AWAY, 20);
	}

	private void assertTwoKillsTwoDrops() {
		BossSession s = history.session();
		assertEquals("alchemical-hydra", s.bossKey);
		assertEquals(3, s.tripCount);
		assertEquals(2, s.drops.size());
		assertEquals(2, s.kills);
		Map<String, Object> last = sent.get(sent.size() - 1);
		assertEquals(2, last.get("kills"));
		assertEquals(2, ((List<?>) last.get("drops")).size());
	}

	/** {@code ticks} game ticks standing at this point. */
	private void at(int[] point, int ticks) {
		pos[0] = point[0];
		pos[1] = point[1];
		for (int i = 0; i < ticks; i++) {
			tick[0]++;
			history.onTick();
		}
	}

	// ---- Fakes ------------------------------------------------------------------------------

	private static final Object NONE = new Object();

	private interface Answer {
		Object answer(String method, Object[] args);
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Answer answer) {
		return (T) Proxy.newProxyInstance(BossHistoryFlowTest.class.getClassLoader(), new Class[]{type}, (p, m, args) -> {
			switch (m.getName()) {
				case "hashCode":
					return System.identityHashCode(p);
				case "equals":
					return p == args[0];
				case "toString":
					return type.getSimpleName();
				default:
					break;
			}
			Object o = answer.answer(m.getName(), args);
			if (o != NONE) {
				return o;
			}
			Class<?> r = m.getReturnType();
			if (r == boolean.class) {
				return false;
			}
			if (r == int.class) {
				return 0;
			}
			if (r == long.class) {
				return 0L;
			}
			if (r == float.class) {
				return 0f;
			}
			if (r == double.class) {
				return 0d;
			}
			return null;
		});
	}

	/** An instance without running its constructor (the client's config manager needs the whole client). */
	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) throws Exception {
		Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		f.setAccessible(true);
		Object unsafe = f.get(null);
		return (T) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
	}

	private static void set(Object o, String field, Object value) {
		try {
			Field f = o.getClass().getDeclaredField(field);
			f.setAccessible(true);
			f.set(o, value);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}
}
