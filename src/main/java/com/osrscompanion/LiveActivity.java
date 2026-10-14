package com.osrscompanion;

import java.util.*;

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
	 * The chosen activity: its kind, what it is about (same subject = same activity), the NPC fought (for its picture)
	 * and the parts the website writes the text and picture from (a slayer task's kills left, the farm run's patches,
	 * the spell, the item...). The NPC id is not part of the key: another version of the same monster is the same
	 * activity.
	 */
	static final class Choice {
		final Kind kind;
		final String subject;
		final Integer npcId;
		final Map<String, Object> parts;

		Choice(Kind kind, String subject, Integer npcId, Map<String, Object> parts) {
			this.kind = kind;
			this.subject = subject;
			this.npcId = npcId;
			// Always sent (also empty): it tells the website to write the text itself.
			// Not Collections.emptyMap(): RuneLite's Gson cannot write it on Java 17+ (it tries to open its private
			// constructor), which turned live activity off for players running the plugin on a newer Java.
			this.parts = parts == null ? new LinkedHashMap<>() : parts;
		}

		/** Two choices with the same key are the same activity (only counts like "12 left" differ). */
		String key() {
			return kind.wire + "|" + (subject == null ? "" : subject);
		}
	}

	private static Map<String, Object> parts(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			if (kv[i + 1] != null) {
				m.put((String) kv[i], kv[i + 1]);
			}
		}
		return m;
	}

	/** The activity for these signals (see the class comment for the order). */
	static Choice choose(Signals s) {
		if (notBlank(s.raid)) {
			return new Choice(Kind.RAID, s.raid, null, null);
		}
		if (notBlank(s.minigame)) {
			return new Choice(Kind.MINIGAME, s.minigame, null, null);
		}
		if (s.idle) {
			return new Choice(Kind.IDLE, null, null, null);
		}
		if (notBlank(s.boss)) {
			return new Choice(Kind.BOSS, s.boss, positive(s.bossId), null);
		}
		if (s.slaying && notBlank(s.slayerTask)) {
			boolean npc = notBlank(s.slayerNpc);
			return new Choice(Kind.SLAYER, s.slayerTask, npc ? positive(s.slayerNpcId) : null,
				parts("left", s.slayerLeft, "npc", npc ? s.slayerNpc : null));
		}
		if (notBlank(s.farmRoute)) {
			return new Choice(Kind.FARM_RUN, s.farmRoute, null,
				parts("done", s.farmDone, "total", s.farmTotal, "item", notBlank(s.farmItem) ? s.farmItem : null));
		}
		if (notBlank(s.combatNpc)) {
			return new Choice(Kind.COMBAT, s.combatNpc, positive(s.combatNpcId),
				s.combatMagic ? parts("magic", true, "spell", notBlank(s.combatSpell) ? s.combatSpell : null) : null);
		}
		if (s.teleport != null) {
			// The Magic skill as subject: teleport after teleport stays one stretch of Magic on the website.
			return new Choice(Kind.SKILLING, "Magic", null, parts("cast", s.teleport));
		}
		if (notBlank(s.skill)) {
			if (s.magicCast != null && "magic".equalsIgnoreCase(s.skill)) {
				return new Choice(Kind.SKILLING, s.magicCast.spell, null, parts("cast", s.magicCast, "alchs", s.alchs));
			}
			return new Choice(Kind.SKILLING, s.skill, null, parts("skill", true, "item", notBlank(s.skillItem) ? s.skillItem : null));
		}
		if (s.bankOpen) {
			return new Choice(Kind.BANKING, null, null, null);
		}
		return new Choice(Kind.OTHER, null, null, null);
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
	static final Map<Integer, String> RAID_REGIONS = regions("raidRegions");
	/** Map region -> minigame name. The Gauntlet goes by varbit. */
	static final Map<Integer, String> MINIGAME_REGIONS = regions("minigameRegions");

	private static Map<Integer, String> regions(String table) {
		Map<Integer, String> m = new HashMap<>();
		Tables.map(table).forEach((name, regions) -> {
			for (int r : Tables.ids(regions)) {
				m.put(r, name);
			}
		});
		return m;
	}

	/** Boss NPC names (lower case), besides the slayer boss variants and the boss gear catalog. */
	private static final Set<String> BOSSES = Tables.stringSet("bossNames");

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
}
