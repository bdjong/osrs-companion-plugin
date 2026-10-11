package com.osrscompanion;

// The plugin's MagicSpells before the website wrote the texts: the reference for LiveActivityOracleTest.

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Magic for live activity, the pure part: what a "Cast" (or a tablet's "Break") menu click was, and the
 * text and wiki icon the website shows for it ("Teleported to Varrock", "High alching Rune platebody",
 * "Training Magic on Hill Giant", "Casting Superheat Item"). No game access, so it is unit tested.
 */
final class OldMagicSpells {
	private OldMagicSpells() {
	}

	/** What a spell does, for the text shown. */
	enum Type {
		TELEPORT,
		HIGH_ALCH,
		LOW_ALCH,
		OFFENSIVE,
		UTILITY
	}

	/** One cast: the spell, what it was cast on (an item or NPC name, or null) and what it does. */
	static final class Cast {
		final String spell;
		final String target;
		final Type type;
		/** A teleport tablet ("Varrock teleport", option "Break"): no Magic XP comes with it. */
		final boolean tablet;

		Cast(String spell, String target, Type type, boolean tablet) {
			this.spell = spell;
			this.target = target;
			this.type = type;
			this.tablet = tablet;
		}

		/** "Teleported to Varrock", "High alching Rune platebody", "Training Magic on Hill Giant", "Casting Humidify". */
		String text() {
			switch (type) {
				case TELEPORT:
					return "Teleported to " + teleportPlace(spell);
				case HIGH_ALCH:
					return target == null ? "High alching" : "High alching " + target;
				case LOW_ALCH:
					return target == null ? "Low alching" : "Low alching " + target;
				case OFFENSIVE:
					return target == null ? "Training Magic · " + spell : "Training Magic on " + target;
				default:
					return "Casting " + spell;
			}
		}

		/** The wiki file that pictures this cast: the alched item, else the spell (or tablet). */
		String icon() {
			if ((type == Type.HIGH_ALCH || type == Type.LOW_ALCH) && target != null) {
				return wikiFile(target);
			}
			return tablet ? wikiFile(spell + " (tablet)") : spellIcon(spell);
		}
	}

	private static final Pattern TAGS = Pattern.compile("<[^>]*>");
	/** "Hill Giant  (level-28)" -> "Hill Giant". */
	private static final Pattern LEVEL = Pattern.compile("\\s*\\((level|skill)-\\d+\\)\\s*$", Pattern.CASE_INSENSITIVE);

	/** Offensive spells by exact name (lower case), besides the elemental and Ancient patterns below. */
	private static final Set<String> OFFENSIVE = new HashSet<>(Arrays.asList(
		"crumble undead", "iban blast", "magic dart", "saradomin strike", "claws of guthix", "flames of zamorak",
		"confuse", "weaken", "curse", "bind", "snare", "entangle", "vulnerability", "enfeeble", "stun", "tele block",
		"inferior demonbane", "superior demonbane", "dark demonbane", "ghostly grasp", "skeletal grasp", "undead grasp",
		"corruption", "lesser corruption", "greater corruption"));
	private static final Pattern ELEMENTAL = Pattern.compile("^(wind|water|earth|fire) (strike|bolt|blast|wave|surge)$");
	private static final Pattern ANCIENT = Pattern.compile("^(smoke|shadow|blood|ice) (rush|burst|blitz|barrage)$");

	/**
	 * Teleport destinations whose spell name doesn't simply say it (lower case spell -> place). Anything
	 * else named "X Teleport", "Teleport to X" or "Tele Group X" goes to X.
	 */
	private static final Map<String, String> PLACES;

	static {
		Map<String, String> p = new HashMap<>();
		p.put("teleport to house", "your house");
		p.put("watchtower teleport", "the Watchtower");
		p.put("ape atoll teleport", "Ape Atoll");
		p.put("ape atoll teleport (arceuus)", "Ape Atoll");
		// Ancient Magicks.
		p.put("paddewwa teleport", "Paddewwa (Edgeville Dungeon)");
		p.put("senntisten teleport", "Senntisten (Digsite)");
		p.put("kharyrll teleport", "Kharyrll (Canifis)");
		p.put("lassar teleport", "Lassar (Ice Mountain)");
		p.put("dareeyak teleport", "Dareeyak (Wilderness)");
		p.put("carrallanger teleport", "Carrallanger (Graveyard of Shadows)");
		p.put("annakarl teleport", "Annakarl (Demonic Ruins)");
		p.put("ghorrock teleport", "Ghorrock (Frozen Waste Plateau)");
		// Lunar.
		p.put("moonclan teleport", "Moonclan (Lunar Isle)");
		p.put("ourania teleport", "Ourania (ZMI altar)");
		p.put("waterbirth teleport", "Waterbirth Island");
		p.put("barbarian teleport", "the Barbarian Outpost");
		p.put("khazard teleport", "Port Khazard");
		p.put("fishing guild teleport", "the Fishing Guild");
		p.put("ice plateau teleport", "the Ice Plateau");
		// Arceuus.
		p.put("arceuus library teleport", "the Arceuus Library");
		p.put("mind altar teleport", "the Mind Altar");
		p.put("respawn teleport", "your respawn point");
		p.put("cemetery teleport", "the Forgotten Cemetery");
		p.put("barrows teleport", "Barrows");
		// Home teleports.
		p.put("lumbridge home teleport", "Lumbridge");
		p.put("edgeville home teleport", "Edgeville");
		p.put("lunar home teleport", "Lunar Isle");
		p.put("arceuus home teleport", "Arceuus");
		p.put("civitas illa fortis home teleport", "Civitas illa Fortis");
		p.put("teleport to target", "your target");
		PLACES = Collections.unmodifiableMap(p);
	}

	/** Spell icons on the wiki that aren't "<Spell>.png". */
	private static final Map<String, String> ICONS;

	static {
		Map<String, String> i = new HashMap<>();
		i.put("ape atoll teleport", "Ape Atoll Teleport (standard).png");
		ICONS = Collections.unmodifiableMap(i);
	}

	/** Menu text without colour tags and the trailing level: "<col=ff9040>Rune platebody</col>" -> "Rune platebody". */
	static String clean(String s) {
		if (s == null) {
			return null;
		}
		String t = LEVEL.matcher(TAGS.matcher(s).replaceAll("")).replaceAll("").replace(' ', ' ').trim();
		return t.isEmpty() ? null : t;
	}

	/**
	 * The cast a menu click is, or null: option "Cast" with the target "Spell" or "Spell -> item / NPC"
	 * (the spell widget's own click, or using a selected spell on something), or "Break" on a teleport tablet.
	 * {@code onNpc}: the click targets an NPC.
	 */
	static Cast fromMenu(String option, String target, boolean onNpc) {
		String opt = clean(option);
		String tgt = clean(target);
		if (opt == null || tgt == null) {
			return null;
		}
		if (opt.equalsIgnoreCase("Break") && tgt.toLowerCase(Locale.ROOT).contains("teleport")) {
			return new Cast(tgt, null, Type.TELEPORT, true);
		}
		if (!opt.equalsIgnoreCase("Cast")) {
			return null;
		}
		String spell = tgt;
		String on = null;
		int arrow = tgt.indexOf("->");
		if (arrow >= 0) {
			spell = clean(tgt.substring(0, arrow));
			on = clean(tgt.substring(arrow + 2));
		}
		if (spell == null) {
			return null;
		}
		return new Cast(spell, on, typeOf(spell, onNpc), false);
	}

	/** What a spell does: by its name; a spell not otherwise known that is cast on an NPC is offensive. */
	static Type typeOf(String spell, boolean onNpc) {
		String s = spell == null ? "" : spell.trim().toLowerCase(Locale.ROOT);
		if (s.equals("high level alchemy")) {
			return Type.HIGH_ALCH;
		}
		if (s.equals("low level alchemy")) {
			return Type.LOW_ALCH;
		}
		if (isTeleport(s)) {
			return Type.TELEPORT;
		}
		if (OFFENSIVE.contains(s) || ELEMENTAL.matcher(s).matches() || ANCIENT.matcher(s).matches()) {
			return Type.OFFENSIVE;
		}
		// Utility spells cast on monsters (Monster Examine, Dark Lure...) aren't attacks.
		if (onNpc && !s.equals("monster examine") && !s.equals("dark lure") && !s.startsWith("resurrect")) {
			return Type.OFFENSIVE;
		}
		return Type.UTILITY;
	}

	/** A spell (or tablet) that moves you: "... Teleport", "Teleport to ...", "Tele Group ..."; not Tele Block or Teleport Other. */
	static boolean isTeleport(String spellLower) {
		String s = spellLower == null ? "" : spellLower.trim().toLowerCase(Locale.ROOT);
		if (s.isEmpty() || s.equals("tele block") || s.startsWith("teleport other") || s.equals("telekinetic grab")) {
			return false;
		}
		return s.endsWith(" teleport") || s.contains(" teleport (") || s.startsWith("teleport to ") || s.startsWith("tele group ");
	}

	/** "Varrock Teleport" -> "Varrock", "Teleport to House" -> "your house", "Kharyrll Teleport" -> "Kharyrll (Canifis)". */
	static String teleportPlace(String spell) {
		String s = spell == null ? "" : spell.trim();
		String lower = s.toLowerCase(Locale.ROOT);
		if (lower.startsWith("tele group ")) {
			return teleportPlace(s.substring("tele group ".length()) + " Teleport");
		}
		String known = PLACES.get(lower);
		if (known != null) {
			return known;
		}
		String place = s;
		if (lower.startsWith("teleport to ")) {
			place = s.substring("teleport to ".length());
		} else if (lower.endsWith(" teleport")) {
			place = s.substring(0, s.length() - " teleport".length());
		}
		place = place.trim();
		if (place.isEmpty()) {
			return "somewhere";
		}
		// Tablets are named in lower case ("Kourend castle teleport"): the place keeps its first capital.
		return Character.toUpperCase(place.charAt(0)) + place.substring(1);
	}

	/** The wiki's icon file for a spell: "<Spell>.png", with the few exceptions in {@link #ICONS}. */
	static String spellIcon(String spell) {
		if (spell == null || spell.trim().isEmpty()) {
			return "Magic icon.png";
		}
		String s = spell.trim();
		if (s.toLowerCase(Locale.ROOT).startsWith("tele group ")) {
			return wikiFile(s);
		}
		String exception = ICONS.get(s.toLowerCase(Locale.ROOT));
		return exception != null ? exception : wikiFile(s);
	}

	/** "Rune platebody" -> "Rune platebody.png". */
	static String wikiFile(String name) {
		return name.trim() + ".png";
	}
}
