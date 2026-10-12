package com.osrscompanion;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Magic for live activity, the pure part: what a "Cast" (or a tablet's "Break") menu click was (the website
 * writes its text and picture: "Teleported to Varrock", "High alching Rune platebody"). No game access, so it
 * is unit tested.
 */
final class MagicSpells {
	private MagicSpells() {
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
	}

	private static final Pattern TAGS = Pattern.compile("<[^>]*>");
	/** "Hill Giant  (level-28)" -> "Hill Giant". */
	private static final Pattern LEVEL = Pattern.compile("\\s*\\((level|skill)-\\d+\\)\\s*$", Pattern.CASE_INSENSITIVE);

	/** Offensive spells by exact name (lower case), besides the elemental and Ancient patterns below. */
	private static final Set<String> OFFENSIVE = Tables.stringSet("offensiveSpells");
	private static final Pattern ELEMENTAL = Pattern.compile("^(wind|water|earth|fire) (strike|bolt|blast|wave|surge)$");
	private static final Pattern ANCIENT = Pattern.compile("^(smoke|shadow|blood|ice) (rush|burst|blitz|barrage)$");

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
}
