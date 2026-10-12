package com.osrscompanion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The plugin's fixed tables (tables.json next to this class), read once. An id is written [id, "Class.NAME"]
 * (the name of RuneLite's constant, checked by TablesTest); only the number is read.
 */
final class Tables {
	private static final JsonObject ROOT = new JsonParser().parse(new InputStreamReader(
		Tables.class.getResourceAsStream("tables.json"), StandardCharsets.UTF_8)).getAsJsonObject();

	private Tables() {
	}

	/** A table's entries in file order, its "_comment" left out. */
	static Map<String, JsonElement> map(String table) {
		Map<String, JsonElement> out = new LinkedHashMap<>();
		ROOT.getAsJsonObject(table).entrySet().forEach(e -> {
			if (!e.getKey().startsWith("_")) {
				out.put(e.getKey(), e.getValue());
			}
		});
		return out;
	}

	/** A table that is a list (its "values"). */
	static JsonArray list(String table) {
		return ROOT.getAsJsonObject(table).getAsJsonArray("values");
	}

	static int id(JsonElement e) {
		return (e.isJsonArray() ? e.getAsJsonArray().get(0) : e).getAsInt();
	}

	static int[] ids(JsonElement e) {
		JsonArray a = e.getAsJsonArray();
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = id(a.get(i));
		}
		return out;
	}

	static Set<Integer> idSet(String table) {
		Set<Integer> out = new HashSet<>();
		list(table).forEach(e -> out.add(id(e)));
		return out;
	}

	static List<String> strings(JsonElement e) {
		List<String> out = new ArrayList<>();
		e.getAsJsonArray().forEach(s -> out.add(s.getAsString()));
		return out;
	}

	static Set<String> stringSet(String table) {
		return new HashSet<>(strings(list(table)));
	}
}
