package com.osrscompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live activity, the pure part: which activity the player is doing (from signals the
 * {@link LiveActivityMonitor} reads each tick), when they count as idle, and the region tables for raids
 * and minigames. No game access, so it is unit tested.
 *
 * <p>Priority, first match wins: raid or minigame, boss fight, slayer task, guided farm run, combat with
 * an NPC, skilling (an XP drop in the last minute), banking, then "other" (playing, nothing of the above).
 * Idle (no movement, XP or animation for five minutes) wins over everything but a raid or minigame: a
 * guide or a bank left open while away is not an activity.
 */
final class LiveActivity {
	private LiveActivity() {
	}

	/** No movement, XP or animation for this long: idle. */
	static final long IDLE_MS = 5 * 60_000L;
	/** A skill whose XP went up this recently is what you are training. */
	static final long SKILLING_WINDOW_MS = 60_000L;
	/** Still "in combat" this long after the last attack (between kills, a missed click...). */
	static final long COMBAT_GRACE_MS = 20_000L;
	/** Still at the boss this long after the last attack on it, while in the same map region (between kills). */
	static final long BOSS_GRACE_MS = 90_000L;
	/** A teleport stays the detail this long (a short event: no segment of its own on the website). */
	static final long TELEPORT_SHOW_MS = 60_000L;

	/** The activity kinds the website knows (CharacterActivity.activity). */
	enum Kind {
		RAID("raid"),
		MINIGAME("minigame"),
		BOSS("boss"),
		SLAYER("slayer"),
		FARM_RUN("farm_run"),
		COMBAT("combat"),
		SKILLING("skilling"),
		BANKING("banking"),
		/** Logged in and active, but none of the above (walking, talking, questing...). */
		OTHER("other"),
		IDLE("idle");

		final String wire;

		Kind(String wire) {
			this.wire = wire;
		}
	}

	/** What the monitor saw this tick; null / 0 / false where it saw nothing. */
	static final class Signals {
		String raid;
		String minigame;
		String boss;
		/** The boss NPC's id (the transformed composition's for NPCs that transform), or null. */
		Integer bossId;
		String slayerTask;
		int slayerLeft;
		/** At a task location or fighting the task's monster. */
		boolean slaying;
		String farmRoute;
		int farmDone;
		int farmTotal;
		String combatNpc;
		/** The id of the NPC being fought (the transformed composition's for NPCs that transform), or null. */
		Integer combatNpcId;
		/** Magic XP came with the Hitpoints XP lately: the fight is trained with Magic. */
		boolean combatMagic;
		/** The offensive spell last cast, when known (not with autocast). */
		String combatSpell;
		/** The slayer monster being fought ("Abyssal demon"), for its picture. */
		String slayerNpc;
		/** The id of {@link #slayerNpc}, or null. */
		Integer slayerNpcId;
		/** The farm run's picture: the route's most planted crop ("Ranarr weed"). */
		String farmItem;
		String skill;
		/** The item the skill last gave ("Raw shark", "Gold ring"), when known. */
		String skillItem;
		/** The Magic cast behind the latest Magic XP (an alch, Superheat...), when the skill is Magic. */
		MagicSpells.Cast magicCast;
		/** High / Low Level Alchemy casts this login. */
		int alchs;
		/** A teleport in the last {@link #TELEPORT_SHOW_MS}, newer than other skilling. */
		MagicSpells.Cast teleport;
		boolean bankOpen;
		boolean idle;
	}

	/**
	 * The chosen activity: its kind, what it is about (same subject = same activity), the text shown and
	 * a picture hint: the wiki file of the thing involved ("Raw shark.png", "Varrock Teleport.png"), or null.
	 * For combat, slayer and boss activities also the id of the NPC fought (for its exact picture), or null.
	 * The NPC id is not part of the key: another version of the same monster is the same activity.
	 */
	static final class Choice {
		final Kind kind;
		final String subject;
		final String detail;
		final String icon;
		final Integer npcId;

		Choice(Kind kind, String subject, String detail) {
			this(kind, subject, detail, null);
		}

		Choice(Kind kind, String subject, String detail, String icon) {
			this(kind, subject, detail, icon, null);
		}

		Choice(Kind kind, String subject, String detail, String icon, Integer npcId) {
			this.kind = kind;
			this.subject = subject;
			this.detail = detail;
			this.icon = icon;
			this.npcId = npcId;
		}

		/** Two choices with the same key are the same activity (only counts like "12 left" differ). */
		String key() {
			return kind.wire + "|" + (subject == null ? "" : subject);
		}

		@Override
		public String toString() {
			return key() + " (" + detail + ")";
		}
	}

	/** The activity for these signals (see the class comment for the order). */
	static Choice choose(Signals s) {
		if (notBlank(s.raid)) {
			return new Choice(Kind.RAID, s.raid, s.raid);
		}
		if (notBlank(s.minigame)) {
			return new Choice(Kind.MINIGAME, s.minigame, s.minigame);
		}
		if (s.idle) {
			return new Choice(Kind.IDLE, null, "Idle");
		}
		if (notBlank(s.boss)) {
			return new Choice(Kind.BOSS, s.boss, s.boss, null, positive(s.bossId));
		}
		if (s.slaying && notBlank(s.slayerTask)) {
			String task = capitalize(s.slayerTask);
			return new Choice(Kind.SLAYER, task, task + (s.slayerLeft > 0 ? " · " + s.slayerLeft + " left" : ""),
				notBlank(s.slayerNpc) ? MagicSpells.wikiFile(s.slayerNpc) : null,
				notBlank(s.slayerNpc) ? positive(s.slayerNpcId) : null);
		}
		if (notBlank(s.farmRoute)) {
			return new Choice(Kind.FARM_RUN, s.farmRoute,
				s.farmRoute + (s.farmTotal > 0 ? " · " + s.farmDone + "/" + s.farmTotal + " patches" : ""),
				notBlank(s.farmItem) ? MagicSpells.wikiFile(s.farmItem) : null);
		}
		if (notBlank(s.combatNpc)) {
			if (s.combatMagic) {
				return new Choice(Kind.COMBAT, s.combatNpc,
					"Training Magic on " + s.combatNpc + (notBlank(s.combatSpell) ? " · " + s.combatSpell : ""),
					MagicSpells.spellIcon(s.combatSpell), positive(s.combatNpcId));
			}
			return new Choice(Kind.COMBAT, s.combatNpc, s.combatNpc, null, positive(s.combatNpcId));
		}
		if (s.teleport != null) {
			// The Magic skill as subject: teleport after teleport stays one stretch of Magic on the website.
			return new Choice(Kind.SKILLING, "Magic", s.teleport.text(), s.teleport.icon());
		}
		if (notBlank(s.skill)) {
			String skill = capitalize(s.skill);
			if (s.magicCast != null && "magic".equalsIgnoreCase(s.skill)) {
				return magicChoice(s.magicCast, s.alchs);
			}
			return notBlank(s.skillItem)
				? new Choice(Kind.SKILLING, skill, skill + " · " + s.skillItem, MagicSpells.wikiFile(s.skillItem))
				: new Choice(Kind.SKILLING, skill, skill);
		}
		if (s.bankOpen) {
			return new Choice(Kind.BANKING, null, "Banking");
		}
		return new Choice(Kind.OTHER, null, "Playing");
	}

	/** Skilling Magic with a known cast: the spell as subject, "High alching Rune platebody · 37 alchs" as detail. */
	static Choice magicChoice(MagicSpells.Cast cast, int alchs) {
		boolean alch = cast.type == MagicSpells.Type.HIGH_ALCH || cast.type == MagicSpells.Type.LOW_ALCH;
		String text = cast.text() + (alch && alchs > 0 ? " · " + alchs + (alchs == 1 ? " alch" : " alchs") : "");
		return new Choice(Kind.SKILLING, cast.spell, text, cast.icon());
	}

	// ---- Idle ---------------------------------------------------------------------------------

	/**
	 * Idle: the local player's tile, XP and animation unchanged for {@link #IDLE_MS}. Someone fishing in
	 * place still gains XP (and animates), so they are not idle. Client thread only.
	 */
	static final class IdleTracker {
		private long lastActiveMs = -1;
		private int x = Integer.MIN_VALUE;
		private int y;
		private int plane;

		/** One tick: where the player stands, whether they animate and whether any XP went up. */
		void tick(long nowMs, int x, int y, int plane, boolean animating, boolean xpGained) {
			boolean moved = x != this.x || y != this.y || plane != this.plane;
			this.x = x;
			this.y = y;
			this.plane = plane;
			if (lastActiveMs < 0 || moved || animating || xpGained) {
				lastActiveMs = nowMs;
			}
		}

		boolean isIdle(long nowMs) {
			return lastActiveMs >= 0 && nowMs - lastActiveMs >= IDLE_MS;
		}

		/** When the player last did something (moved, animated, gained XP); -1 before the first tick. */
		long lastActiveMs() {
			return lastActiveMs;
		}

		void reset() {
			lastActiveMs = -1;
			x = Integer.MIN_VALUE;
		}
	}

	// ---- Skilling -----------------------------------------------------------------------------

	/** Skills whose XP comes from fighting: never "skilling" (combat is detected on its own). */
	private static final Set<String> COMBAT_ONLY = new HashSet<>(Arrays.asList("hitpoints", "slayer"));
	/** Skills that count as skilling only when no Hitpoints XP came in the same tick (alching, chinning...). */
	private static final Set<String> COMBAT_STYLE = new HashSet<>(Arrays.asList("attack", "strength", "defence", "ranged", "magic"));

	/**
	 * Whether an XP drop in this skill is skilling: not Hitpoints or Slayer, and a combat style only when
	 * no Hitpoints XP came with it (a spell cast on a monster gives both).
	 */
	static boolean isSkillingXp(String skill, boolean hitpointsXpSameTick) {
		String k = skill == null ? "" : skill.toLowerCase(Locale.ROOT);
		if (k.isEmpty() || COMBAT_ONLY.contains(k)) {
			return false;
		}
		return !COMBAT_STYLE.contains(k) || !hitpointsXpSameTick;
	}

	/** The skill with the latest skilling XP drop within {@link #SKILLING_WINDOW_MS}, or null. */
	static String skillingSkill(Map<String, Long> lastSkillingXpMs, long nowMs) {
		String best = null;
		long bestAt = Long.MIN_VALUE;
		for (Map.Entry<String, Long> e : lastSkillingXpMs.entrySet()) {
			long at = e.getValue();
			if (nowMs - at <= SKILLING_WINDOW_MS && at > bestAt) {
				best = e.getKey();
				bestAt = at;
			}
		}
		return best;
	}

	// ---- Raids, minigames and bosses ------------------------------------------------------------

	/** Map region (the template region inside instances) -> raid name. Chambers of Xeric and the Theatre also go by varbit. */
	static final Map<Integer, String> RAID_REGIONS;
	/** Map region -> minigame name. The Gauntlet goes by varbit. */
	static final Map<Integer, String> MINIGAME_REGIONS;

	static {
		Map<Integer, String> raids = new HashMap<>();
		for (int r : new int[]{14160, 15698, 15700, 14162, 14164, 14674, 14676, 15186, 15188, 15184, 15696, 14672}) {
			raids.put(r, "Tombs of Amascut");
		}
		for (int r : new int[]{12613, 13125, 13122, 13123, 13379, 12611}) {
			raids.put(r, "Theatre of Blood");
		}
		RAID_REGIONS = Collections.unmodifiableMap(raids);

		Map<Integer, String> games = new HashMap<>();
		games.put(9043, "Inferno");
		games.put(9551, "Fight Caves");
		games.put(7216, "Fortis Colosseum");
		games.put(14484, "Guardians of the Rift");
		games.put(12078, "Tempoross");
		games.put(6462, "Wintertodt");
		games.put(12126, "Zalcano");
		games.put(9033, "Nightmare Zone");
		games.put(10536, "Pest Control");
		games.put(13491, "Giants' Foundry");
		games.put(14231, "Barrows");
		games.put(7512, "The Gauntlet");
		games.put(7768, "The Corrupted Gauntlet");
		MINIGAME_REGIONS = Collections.unmodifiableMap(games);
	}

	/** Boss NPC names (lower case), besides the slayer boss variants and the boss gear catalog. */
	private static final Set<String> BOSSES = new HashSet<>(Arrays.asList(
		"abyssal sire", "alchemical hydra", "amoxliatl", "araxxor", "artio", "branda the fire queen", "bryophyta",
		"callisto", "calvar'ion", "cerberus", "chaos elemental", "chaos fanatic", "commander zilyana", "corporeal beast",
		"crazy archaeologist", "dagannoth prime", "dagannoth rex", "dagannoth supreme", "dawn", "deranged archaeologist",
		"doom of mokhaiotl", "duke sucellus", "dusk", "eldric the ice king", "general graardor", "giant mole", "hespori",
		"k'ril tsutsaroth", "kalphite queen", "king black dragon", "kraken", "kree'arra", "nex", "obor",
		"phantom muspah", "phosani's nightmare", "sarachnis", "scorpia", "scurrius", "shellbane gryphon", "skotizo",
		"sol heredit", "spindel", "the hueycoatl", "the leviathan", "the nightmare", "the whisperer",
		"thermonuclear smoke devil", "tztok-jad", "tzkal-zuk", "vardorvis", "venenatis", "vet'ion", "vorkath", "yama",
		"zulrah"));

	static {
		for (java.util.List<String> names : SlayerBosses.BOSSES.values()) {
			for (String n : names) {
				BOSSES.add(n.toLowerCase(Locale.ROOT));
			}
		}
	}

	/** Whether an NPC is a boss: the list above (with the slayer boss variants) or one of {@code extra} (lower case). */
	static boolean isBoss(String npcName, Set<String> extra) {
		if (npcName == null) {
			return false;
		}
		String n = npcName.trim().toLowerCase(Locale.ROOT);
		return !n.isEmpty() && (BOSSES.contains(n) || extra != null && extra.contains(n));
	}

	// ---- Helpers ------------------------------------------------------------------------------

	/**
	 * The id of the NPC being fought: the transformed composition's id when the NPC transforms (a
	 * multiloc NPC), else its own id. Null when neither is a real id.
	 */
	static Integer npcId(int id, Integer transformedId) {
		if (transformedId != null && transformedId > 0) {
			return transformedId;
		}
		return id > 0 ? id : null;
	}

	private static Integer positive(Integer id) {
		return id != null && id > 0 ? id : null;
	}

	static boolean notBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}

	/** "abyssal demons" -> "Abyssal demons"; "FISHING" stays as the game names skills ("Fishing"). */
	static String capitalize(String s) {
		if (s == null || s.isEmpty()) {
			return s;
		}
		String t = s.trim();
		if (t.equals(t.toUpperCase(Locale.ROOT))) {
			t = t.toLowerCase(Locale.ROOT);
		}
		return Character.toUpperCase(t.charAt(0)) + t.substring(1);
	}
}
