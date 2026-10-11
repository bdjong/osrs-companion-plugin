package com.osrscompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.osrscompanion.api.OSRSCompanionApiClient;
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
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The website works out how slayer tasks ended (and the time spent on them) now. This plays random slayer
 * play (assignments, kills, completions with and without the chat line, skips, blocks, streak resets,
 * loading screens and logouts) through the plugin's old SlayerMonitor and the new one side by side, and
 * writes what the new one records (the game events) and what the old one sent (its task events and timing)
 * to build/oracle/slayer-events.json.gz, which the website's processor must turn into the same.
 */
public class SlayerEventsOracleTest {
	private static final int[] TASKS = {25, 39, 51, 73, 80};
	private static final int[] BOSSES = {41, 72};

	/** The game as both monitors see it. */
	private final Map<Integer, Integer> varps = new HashMap<>();
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private int tick = 1000;
	private long ms = 1_790_000_000_000L;
	private GameState state = GameState.LOGIN_SCREEN;
	private final List<Object[]> scheduled = new ArrayList<>();
	private final List<Map<String, Object>> recorded = new ArrayList<>();
	private final List<OldSlayerTaskEvent> oldEvents = new ArrayList<>();
	private Random r;

	private Client client() {
		Client c = mock(Client.class);
		when(c.getVarpValue(anyInt())).thenAnswer(i -> varps.getOrDefault((Integer) i.getArgument(0), 0));
		when(c.getVarbitValue(anyInt())).thenAnswer(i -> varbits.getOrDefault((Integer) i.getArgument(0), 0));
		when(c.getTickCount()).thenAnswer(i -> tick);
		when(c.getGameState()).thenAnswer(i -> state);
		Player me = mock(Player.class);
		when(me.getName()).thenReturn("Tester");
		when(c.getLocalPlayer()).thenReturn(me);
		when(c.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(c.getRealSkillLevel(Skill.SLAYER)).thenReturn(90);
		when(c.getSkillExperience(Skill.SLAYER)).thenReturn(5_400_000);
		return c;
	}

	private ConfigManager configManager() {
		Map<String, String> store = new HashMap<>();
		ConfigManager cm = mock(ConfigManager.class);
		when(cm.getRSProfileConfiguration(anyString(), anyString())).thenAnswer(i -> store.get((String) i.getArgument(1)));
		doAnswer(i -> store.put(i.getArgument(1), String.valueOf((Object) i.getArgument(2))))
			.when(cm).setRSProfileConfiguration(anyString(), anyString(), any(Object.class));
		doAnswer(i -> store.remove((String) i.getArgument(1))).when(cm).unsetRSProfileConfiguration(anyString(), anyString());
		return cm;
	}

	private ScheduledExecutorService executor() {
		ScheduledExecutorService ex = mock(ScheduledExecutorService.class);
		doAnswer(i -> {
			scheduled.add(new Object[]{ms + ((TimeUnit) i.getArgument(2)).toMillis(i.getArgument(1)), i.getArgument(0)});
			return null;
		}).when(ex).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
		return ex;
	}

	private ClientThread clientThread() {
		ClientThread ct = mock(ClientThread.class);
		doAnswer(i -> {
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(any(Runnable.class));
		return ct;
	}

	private OldSlayerMonitor old;
	private SlayerMonitor now;

	private void setUp() {
		TestOSRSCompanionConfig config = new TestOSRSCompanionConfig();
		config.setOsrsCompanionAppKey("key");
		OSRSCompanionApiClient api = mock(OSRSCompanionApiClient.class);
		OldSlayerMonitor.clock = () -> ms;
		OldSlayerMonitor.sink = p -> oldEvents.addAll(p.getEvents());
		old = new OldSlayerMonitor(client(), clientThread(), config, api, executor(), new Gson(), configManager());
		GameEvents events = new GameEvents(null, null, null, null, null, null, null, null) {
			@Override
			Map<String, Object> add(String type, Object... kv) {
				Map<String, Object> e = new LinkedHashMap<>();
				e.put("t", tick);
				e.put("ms", ms);
				e.put("e", type);
				for (int i = 0; i < kv.length; i += 2) {
					e.put((String) kv[i], kv[i + 1]);
				}
				recorded.add(e);
				return e;
			}
		};
		now = new SlayerMonitor(client(), clientThread(), config, api, executor(), new Gson(), configManager(), events);
	}

	// ---- What happens in the game, to both monitors ----

	private void varp(int id, int value) {
		if (varps.getOrDefault(id, 0) == value) {
			return;
		}
		varps.put(id, value);
		VarbitChanged e = new VarbitChanged();
		e.setVarpId(id);
		e.setValue(value);
		old.onVarbitChanged(e);
		now.onVarbitChanged(e);
	}

	private void varbit(int id, int value) {
		if (varbits.getOrDefault(id, 0) == value) {
			return;
		}
		varbits.put(id, value);
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(id);
		e.setValue(value);
		old.onVarbitChanged(e);
		now.onVarbitChanged(e);
	}

	private void chat(String text) {
		ChatMessage m = new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", text, null, 0);
		old.onChatMessage(m);
		now.onChatMessage(m);
	}

	private void gameState(GameState s) {
		state = s;
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		old.onGameStateChanged(e);
		now.onGameStateChanged(e);
	}

	/** One game tick (and what was scheduled for it). */
	private void tick() {
		if (state == GameState.LOGGED_IN) {
			old.onGameTick(new GameTick());
			now.onGameTick(new GameTick());
		}
		tick++;
		ms += 600 + r.nextInt(3) - 1;
		for (Object[] s : new ArrayList<>(scheduled)) {
			if ((long) s[0] <= ms) {
				scheduled.remove(s);
				((Runnable) s[1]).run();
			}
		}
	}

	private void ticks(int n) {
		for (int i = 0; i < n; i++) {
			// Now and then a loading screen (a teleport) or a logout in between.
			if (state == GameState.LOGGED_IN && r.nextInt(400) == 0) {
				gameState(GameState.LOADING);
				tick();
				gameState(GameState.LOGGED_IN);
			} else if (state == GameState.LOGGED_IN && r.nextInt(1500) == 0) {
				gameState(GameState.LOGIN_SCREEN);
				for (int k = r.nextInt(200); k > 0; k--) {
					tick();
				}
				gameState(GameState.LOGGED_IN);
			}
			tick();
		}
	}

	private int pick(int[] a) {
		return a[r.nextInt(a.length)];
	}

	private void assign() {
		int task = r.nextInt(6) == 0 ? 98 : pick(TASKS);
		int amount = 5 + r.nextInt(60);
		// The varps can land over two ticks.
		varp(VarPlayerID.SLAYER_TARGET, task);
		varbit(VarbitID.SLAYER_TARGET_BOSSID, task == 98 ? pick(BOSSES) : 0);
		varbit(VarbitID.SLAYER_MASTER, 1 + r.nextInt(9));
		varp(VarPlayerID.SLAYER_AREA, r.nextInt(3) == 0 ? 1 + r.nextInt(20) : 0);
		if (r.nextInt(4) == 0) {
			ticks(1);
		}
		// Mortimer's quantity modifier: more kills than the size the varp keeps.
		varp(VarPlayerID.SLAYER_COUNT_ORIGINAL, r.nextInt(5) == 0 ? Math.max(1, amount - r.nextInt(10)) : amount);
		varp(VarPlayerID.SLAYER_COUNT, amount);
	}

	private void points(int delta) {
		varbit(VarbitID.SLAYER_POINTS, Math.max(0, varbits.getOrDefault(VarbitID.SLAYER_POINTS, 0) + delta));
	}

	private void clearTask() {
		varp(VarPlayerID.SLAYER_COUNT, 0);
		if (r.nextBoolean()) {
			varp(VarPlayerID.SLAYER_TARGET, 0);
		}
	}

	private void complete() {
		int chatAt = r.nextInt(4) == 0 ? 99 : r.nextInt(9) - 3;
		if (chatAt < 0) {
			chat("You've completed " + (1 + varbits.getOrDefault(VarbitID.SLAYER_TASKS_COMPLETED, 0)) + " tasks in a row and received 15 points.");
			ticks(-chatAt);
		}
		varp(VarPlayerID.SLAYER_COUNT, 0);
		int how = r.nextInt(5);
		if (how <= 2) {
			varbit(VarbitID.SLAYER_TASKS_COMPLETED, varbits.getOrDefault(VarbitID.SLAYER_TASKS_COMPLETED, 0) + 1);
		} else if (how == 3) {
			varbit(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED, varbits.getOrDefault(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED, 0) + 1);
		}
		if (r.nextInt(4) != 0) {
			points(r.nextInt(3) == 0 ? 0 : 15);
		}
		if (chatAt >= 0 && chatAt != 99) {
			ticks(chatAt);
			chat("You have completed your task! You've completed " + varbits.getOrDefault(VarbitID.SLAYER_TASKS_COMPLETED, 0) + " tasks in a row.");
		}
	}

	private void block() {
		int task = varps.getOrDefault(VarPlayerID.SLAYER_TARGET, 0);
		if (r.nextBoolean()) {
			int slot = VarbitID.SLAYER_BLOCKED_NIEVE_1;
			varbit(slot, task);
		} else {
			int v = varps.getOrDefault(VarPlayerID.SLAYER_REWARDS_BLOCKED_3, 0);
			varp(VarPlayerID.SLAYER_REWARDS_BLOCKED_3, (v << 8 | task) & 0x7FFFFFFF);
		}
		points(-100);
		clearTask();
	}

	private void play() {
		gameState(GameState.LOGGED_IN);
		ticks(10);
		for (int step = 0; step < 40; step++) {
			boolean hasTask = varps.getOrDefault(VarPlayerID.SLAYER_COUNT, 0) > 0 && varps.getOrDefault(VarPlayerID.SLAYER_TARGET, 0) != 0;
			if (!hasTask) {
				ticks(r.nextInt(30));
				assign();
				ticks(1 + r.nextInt(5));
				continue;
			}
			int what = r.nextInt(20);
			if (what < 12) {
				// Kills: short gaps, now and then a break longer than five minutes.
				int kills = 1 + r.nextInt(8);
				for (int k = 0; k < kills && varps.get(VarPlayerID.SLAYER_COUNT) > 1; k++) {
					ticks(r.nextInt(30) == 0 ? 500 + r.nextInt(400) : 2 + r.nextInt(40));
					varp(VarPlayerID.SLAYER_COUNT, varps.get(VarPlayerID.SLAYER_COUNT) - 1);
				}
				if (varps.get(VarPlayerID.SLAYER_COUNT) == 1 && r.nextBoolean()) {
					ticks(2 + r.nextInt(40));
					complete();
				}
			} else if (what < 15) {
				// A skip: points spent, the next task (sometimes the same monster from another master).
				points(-30);
				if (r.nextBoolean()) {
					varbit(VarbitID.SLAYER_MASTER, 1 + r.nextInt(9));
					varp(VarPlayerID.SLAYER_COUNT, 5 + r.nextInt(60));
				} else {
					assign();
				}
			} else if (what < 17) {
				block();
			} else if (what < 18) {
				// The streak lost (a task from Turael), or the task cancelled with nothing else.
				varbit(VarbitID.SLAYER_TASKS_COMPLETED, 0);
				assign();
			} else if (what < 19) {
				clearTask();
			} else {
				chat("You've completed 12 tasks in a row.");
			}
			ticks(1 + r.nextInt(6));
		}
		ticks(20);
		gameState(GameState.LOGIN_SCREEN);
		ticks(3);
	}

	private static Object field(Object o, String name) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	@Test
	public void writeOracle() throws Exception {
		Gson gson = new Gson();
		List<Map<String, Object>> cases = new ArrayList<>();
		for (int n = 0; n < 150; n++) {
			r = new Random(20261012L + n);
			varps.clear();
			varbits.clear();
			recorded.clear();
			oldEvents.clear();
			scheduled.clear();
			state = GameState.LOGIN_SCREEN;
			setUp();
			// Some characters start with a streak and points.
			varbits.put(VarbitID.SLAYER_TASKS_COMPLETED, r.nextInt(50));
			varbits.put(VarbitID.SLAYER_POINTS, r.nextInt(800));
			play();
			Map<String, Object> one = new LinkedHashMap<>();
			// As GameEvents sends them (Gson leaves out nulls).
			one.put("events", gson.fromJson(gson.toJson(recorded), List.class));
			List<Object> expected = new ArrayList<>();
			for (OldSlayerTaskEvent e : oldEvents) {
				Map<?, ?> m = gson.fromJson(gson.toJson(e), Map.class);
				m.remove("event_uuid");
				expected.add(m);
			}
			one.put("expected", expected);
			Map<String, Object> timing = new LinkedHashMap<>();
			timing.put("first", field(old, "firstKillMs"));
			timing.put("last", field(old, "lastKillMs"));
			timing.put("active", field(old, "activeMs"));
			one.put("timing", timing);
			cases.add(one);
		}
		Path out = Paths.get("build", "oracle", "slayer-events.json.gz");
		Files.createDirectories(out.getParent());
		try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8)) {
			new GsonBuilder().serializeNulls().create().toJson(cases, w);
		}
		long events = cases.stream().mapToLong(c -> ((List<?>) c.get("expected")).size()).sum();
		System.out.println("slayer oracle: " + cases.size() + " sequences, " + events + " task events");
	}
}
