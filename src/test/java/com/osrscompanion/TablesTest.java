package com.osrscompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The fixed tables read from tables.json (see {@link Tables}): every table the classes build from it is the same
 * as before it moved there (tables-expected.json, recorded from the code when the tables were still in it), and
 * every id written [id, "Class.NAME"] is RuneLite's constant of that name.
 */
public class TablesTest {
	/** Class.FIELD of every table read from tables.json. */
	static final String[] FIELDS = {
		"SlayerMonitor.MASTER_BLOCK_VARBITS", "SlayerMonitor.BLOCK_LIST_VARPS", "SlayerMonitor.WATCHED_VARPS", "SlayerMonitor.WATCHED_VARBITS",
		"SlayerBosses.BOSSES", "LiveActivity.RAID_REGIONS", "LiveActivity.MINIGAME_REGIONS", "LiveActivity.BOSSES", "MagicSpells.OFFENSIVE",
		"VaultMonitor.VAULT_SOURCES", "BossGearManager.WORN_KEYS", "BankLayouts.EQUIPMENT_POS", "PlankSackMonitor.PLANK_VARBITS",
		"LoadoutMonitor.EQUIPMENT_SLOT_MAP", "FarmRunWindow.STORAGE", "TripSupplies.BOSS_ALIASES", "TeleportLeftClick.NOT_TELEPORTS",
		"TeleportLeftClick.TELEPORT_CAPES", "RunePouchMonitor.TYPE_VARBITS", "RunePouchMonitor.QUANTITY_VARBITS",
	};
	private static final Path EXPECTED = Paths.get("src", "test", "resources", "com", "osrscompanion", "tables-expected.json");
	private static final Class<?>[] ID_CLASSES = {VarbitID.class, VarPlayerID.class, InventoryID.class, ItemID.class};

	/** A table as plain JSON: sets and map keys sorted (their order never mattered), the rest as it is. */
	static JsonElement canonical(Object v) {
		Gson gson = new Gson();
		if (v == null) {
			return gson.toJsonTree(null);
		}
		if (v instanceof Map) {
			Map<String, JsonElement> m = new TreeMap<>();
			((Map<?, ?>) v).forEach((k, x) -> m.put(String.valueOf(k), canonical(x)));
			JsonObject o = new JsonObject();
			m.forEach(o::add);
			return o;
		}
		if (v instanceof java.util.Set) {
			List<String> sorted = new ArrayList<>();
			((Collection<?>) v).forEach(x -> sorted.add(String.valueOf(x)));
			sorted.sort(null);
			return gson.toJsonTree(sorted);
		}
		if (v instanceof Collection || v.getClass().isArray()) {
			JsonArray a = new JsonArray();
			if (v instanceof Collection) {
				((Collection<?>) v).forEach(x -> a.add(canonical(x)));
			} else {
				for (int i = 0; i < Array.getLength(v); i++) {
					a.add(canonical(Array.get(v, i)));
				}
			}
			return a;
		}
		return gson.toJsonTree(v);
	}

	static JsonObject snapshot() throws Exception {
		JsonObject out = new JsonObject();
		for (String f : FIELDS) {
			String[] parts = f.split("\\.");
			Field field = Class.forName("com.osrscompanion." + parts[0]).getDeclaredField(parts[1]);
			field.setAccessible(true);
			out.add(f, canonical(field.get(null)));
		}
		return out;
	}

	@Test
	public void tablesAreAsTheyWere() throws Exception {
		JsonObject now = snapshot();
		if (!Files.exists(EXPECTED)) {
			Files.createDirectories(EXPECTED.getParent());
			Files.write(EXPECTED, new GsonBuilder().setPrettyPrinting().create().toJson(now).getBytes(StandardCharsets.UTF_8));
			return;
		}
		JsonObject expected = new JsonParser().parse(new String(Files.readAllBytes(EXPECTED), StandardCharsets.UTF_8)).getAsJsonObject();
		for (String f : FIELDS) {
			assertEquals(f, expected.get(f), now.get(f));
		}
	}

	@Test
	public void idsAreRuneLitesConstants() throws Exception {
		String json = new String(Files.readAllBytes(Paths.get("src", "main", "resources", "com", "osrscompanion", "tables.json")), StandardCharsets.UTF_8);
		int checked = 0;
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*\"(\\w+)\\.(\\w+)\"\\s*]").matcher(json);
		while (m.find()) {
			Class<?> c = null;
			for (Class<?> k : ID_CLASSES) {
				if (k.getSimpleName().equals(m.group(2))) {
					c = k;
				}
			}
			assertTrue("unknown class " + m.group(2), c != null);
			assertEquals(m.group(0), c.getField(m.group(3)).getInt(null), Integer.parseInt(m.group(1)));
			checked++;
		}
		assertTrue(checked > 80);
	}

	/** The constant names for an id, per class (for writing tables.json). */
	static Map<Integer, String> names(Class<?> c) throws Exception {
		Map<Integer, String> out = new LinkedHashMap<>();
		for (Field f : c.getFields()) {
			if (f.getType() == int.class) {
				out.putIfAbsent(f.getInt(null), c.getSimpleName() + "." + f.getName());
			}
		}
		return out;
	}
}
