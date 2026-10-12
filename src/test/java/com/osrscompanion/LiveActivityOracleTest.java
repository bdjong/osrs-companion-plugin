package com.osrscompanion;

import com.google.gson.Gson;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

/**
 * The website writes the live activity's text and picture now: this runs the plugin's old choice (with its texts)
 * and the new one (kind, subject and parts) on random signals and writes both to build/oracle/activity-oracle.json.gz,
 * which the website's test composes from the parts and compares with the old texts.
 */
public class LiveActivityOracleTest {
	private static final String[] SPELLS = {
		"Varrock Teleport", "Lumbridge Teleport", "Watchtower Teleport", "Ape Atoll Teleport", "Ape Atoll Teleport (Arceuus)",
		"Teleport to House", "Kharyrll Teleport", "Ghorrock Teleport", "Moonclan Teleport", "Tele Group Waterbirth",
		"Tele Group Moonclan", "Tele Group Barbarian", "Lumbridge Home Teleport", "Civitas illa Fortis Home Teleport",
		"Teleport to Target", "Teleport to Ape Atoll", "Salve Graveyard Teleport", "Respawn Teleport", " Teleport",
		"High Level Alchemy", "Low Level Alchemy", "Fire Strike", "Ice Barrage", "Crumble Undead", "Some New Spell",
		"Superheat Item", "Lvl-1 Enchant", "Humidify", "Plank Make", "Monster Examine", "Dark Lure", "Spin Flax"};
	private static final String[] TABLETS = {"Varrock teleport", "Kourend castle teleport", "Teleport to house", "Watchtower teleport",
		"Ape atoll teleport", "Salve graveyard teleport", "Arceuus library teleport"};
	private static final String[] NAMES = {"Rune platebody", "Iron dagger", "Hill Giant", "Abyssal demon", "Raw shark", "Gold ring",
		"Ranarr weed", " Snapdragon ", "Blue dragon"};
	private static final String[] TASKS = {"abyssal demons", "blue dragons", "ABERRANT SPECTRES", "Kalphite", " cave krakens", "x"};
	private static final String[] SKILLS = {"fishing", "FISHING", "Fishing", "magic", "MAGIC", "Magic", "woodcutting", " runecraft", "AgIlity"};
	private static final String[] STRINGS = {null, "", "  ", "Chambers of Xeric", "Wintertodt", "Vorkath", "Herb run", "Hill Giant"};

	private final Random r = new Random(20261011);

	private <T> T pick(T[] a) {
		return a[r.nextInt(a.length)];
	}

	private String maybe(String[] a) {
		int k = r.nextInt(10);
		return k == 0 ? null : k == 1 ? "" : k == 2 ? " " : pick(a);
	}

	private Object[] casts() {
		if (r.nextInt(4) == 0) {
			return null;
		}
		if (r.nextInt(5) == 0) {
			String t = pick(TABLETS);
			return new Object[]{MagicSpells.fromMenu("Break", t, false), OldMagicSpells.fromMenu("Break", t, false)};
		}
		String target = pick(SPELLS) + (r.nextBoolean() ? " -> " + pick(NAMES) : "");
		boolean onNpc = r.nextBoolean();
		return new Object[]{MagicSpells.fromMenu("Cast", target, onNpc), OldMagicSpells.fromMenu("Cast", target, onNpc)};
	}

	@Test
	public void writeOracle() throws Exception {
		Gson gson = new Gson();
		List<Map<String, Object>> cases = new ArrayList<>();
		for (int n = 0; n < 20000; n++) {
			LiveActivity.Signals s = new LiveActivity.Signals();
			// Each signal is set only now and then, so every branch wins often.
			int from = r.nextInt(11);
			s.raid = from <= 0 && r.nextInt(3) == 0 ? pick(STRINGS) : null;
			s.minigame = from <= 1 && r.nextInt(3) == 0 ? pick(STRINGS) : null;
			s.idle = from <= 2 && r.nextInt(4) == 0;
			s.boss = from <= 3 && r.nextInt(3) == 0 ? pick(STRINGS) : null;
			s.bossId = r.nextInt(4) == 0 ? null : r.nextInt(3) - 1 + r.nextInt(2) * 8000;
			s.slayerTask = from <= 4 ? maybe(TASKS) : null;
			s.slaying = r.nextBoolean();
			s.slayerLeft = r.nextInt(3) == 0 ? 0 : r.nextInt(200);
			s.slayerNpc = maybe(NAMES);
			s.slayerNpcId = r.nextInt(4) == 0 ? null : r.nextInt(3) - 1 + r.nextInt(2) * 400;
			s.farmRoute = from <= 5 && r.nextInt(2) == 0 ? pick(STRINGS) : null;
			s.farmDone = r.nextInt(10);
			s.farmTotal = r.nextInt(3) == 0 ? 0 : r.nextInt(12);
			s.farmItem = maybe(NAMES);
			s.combatNpc = from <= 6 && r.nextInt(2) == 0 ? maybe(NAMES) : null;
			s.combatNpcId = r.nextInt(4) == 0 ? null : r.nextInt(3) - 1 + r.nextInt(2) * 2000;
			s.combatMagic = r.nextBoolean();
			s.combatSpell = maybe(SPELLS);
			Object[] tele = from <= 7 && r.nextInt(3) == 0 ? casts() : null;
			Object[] cast = casts();
			s.skill = from <= 8 ? maybe(SKILLS) : null;
			s.skillItem = maybe(NAMES);
			s.alchs = r.nextInt(3) == 0 ? 0 : r.nextInt(3) == 0 ? 1 : r.nextInt(500);
			s.bankOpen = from <= 9 && r.nextBoolean();
			s.teleport = tele == null ? null : (MagicSpells.Cast) tele[0];
			s.magicCast = cast == null ? null : (MagicSpells.Cast) cast[0];

			OldLiveActivity.Signals o = new OldLiveActivity.Signals();
			for (Field f : LiveActivity.Signals.class.getDeclaredFields()) {
				f.setAccessible(true);
				Field of = OldLiveActivity.Signals.class.getDeclaredField(f.getName());
				of.setAccessible(true);
				if (f.getType() != MagicSpells.Cast.class) {
					of.set(o, f.get(s));
				}
			}
			o.teleport = tele == null ? null : (OldMagicSpells.Cast) tele[1];
			o.magicCast = cast == null ? null : (OldMagicSpells.Cast) cast[1];

			LiveActivity.Choice c = LiveActivity.choose(s);
			OldLiveActivity.Choice old = OldLiveActivity.choose(o);
			if (!c.kind.wire.equals(old.kind.wire) || !String.valueOf(c.npcId).equals(String.valueOf(old.npcId))) {
				throw new AssertionError("kind/npc differ: " + old);
			}
			Map<String, Object> sent = new LinkedHashMap<>();
			sent.put("activity", c.kind.wire);
			sent.put("subject", c.subject);
			sent.put("npc_id", c.npcId);
			sent.put("parts", c.parts);
			Map<String, Object> expected = new LinkedHashMap<>();
			expected.put("subject", old.subject);
			expected.put("detail", old.detail);
			expected.put("icon", old.icon);
			Map<String, Object> one = new LinkedHashMap<>();
			// As the plugin sends it (Gson), and what the old plugin sent.
			one.put("sent", gson.fromJson(gson.toJson(sent), Map.class));
			one.put("expected", expected);
			cases.add(one);
		}
		Path out = Paths.get("build", "oracle", "activity-oracle.json.gz");
		Files.createDirectories(out.getParent());
		try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8)) {
			new Gson().newBuilder().serializeNulls().create().toJson(cases, w);
		}
	}
}
