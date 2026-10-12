package com.osrscompanion;

import com.google.gson.GsonBuilder;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The storage items' specs as the plugin had them (ids, capacities, chat patterns and messages), written to
 * build/oracle/storage-specs.json for the website's src/data/runelite/storageItems.json, so nothing is copied by hand.
 */
public class StorageSpecExportTest {
	@Test
	public void writeSpecs() throws Exception {
		Map<Integer, String> constants = new LinkedHashMap<>();
		for (Field f : ItemID.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
				constants.putIfAbsent(f.getInt(null), f.getName());
			}
		}
		List<Map<String, Object>> specs = new ArrayList<>();
		Map<Integer, String> ids = new LinkedHashMap<>();
		for (OldStorageItemSpec s : OldStorageItemSpec.all()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("source", s.source);
			m.put("closed", new TreeSet<>(s.closedIds));
			m.put("open", new TreeSet<>(s.openIds));
			m.put("accepts", new ArrayList<>(s.acceptedIds));
			m.put("capacity", s.capacity);
			m.put("perItem", s.perItemCapacity);
			List<String> patterns = new ArrayList<>();
			for (Pattern p : s.gatherPatterns) {
				patterns.add(p.pattern());
			}
			m.put("gather", patterns);
			m.put("extraGather", new TreeSet<>(s.extraGatherMessages));
			m.put("emptied", new TreeSet<>(s.emptiedMessages));
			m.put("emptiedPrefixes", new TreeSet<>(s.emptiedPrefixes));
			m.put("full", new TreeSet<>(s.fullMessages));
			m.put("checkPrefix", s.checkPrefix);
			m.put("checkEmpty", new TreeSet<>(s.checkEmptyMessages));
			m.put("consumeSkill", s.consumeSkill == null ? null : s.consumeSkill.getName());
			m.put("xpCreditSkill", s.xpCreditSkill == null ? null : s.xpCreditSkill.getName());
			m.put("xpPerItem", s.xpPerItem);
			m.put("stripPrefix", s.namePrefixToStrip);
			m.put("aliases", s.nameAliases);
			specs.add(m);
			for (int id : s.acceptedIds) {
				ids.put(id, constants.get(id));
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("specs", specs);
		out.put("ids", ids);
		Path p = Paths.get("build", "oracle", "storage-specs.json");
		Files.createDirectories(p.getParent());
		Files.write(p, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out).getBytes(StandardCharsets.UTF_8));
	}
}
