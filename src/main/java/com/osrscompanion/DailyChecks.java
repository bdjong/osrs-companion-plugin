package com.osrscompanion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Which dailies are open, done or locked today, read from the game the way RuneLite's Daily Task
 * Indicators plugin does it: the diary tier sets the amount, a "claimed" varbit says it was
 * collected. The ids are the website's (src/data/dailies.json). Dailies the game does not track
 * (the kingdom without a visit, Tears of Guthix, bird houses) come back UNKNOWN: the website's
 * state (timers) or a tick by hand decides them. Pure (vars come in through {@link Vars}), so it
 * is unit-tested without a client.
 */
final class DailyChecks {
	enum State {
		DONE, OPEN, LOCKED, UNKNOWN;

		/** The website's name for it ("done"), null for UNKNOWN (not sent). */
		String wire() {
			return this == UNKNOWN ? null : name().toLowerCase();
		}

		static State fromWire(String s) {
			if (s == null) {
				return UNKNOWN;
			}
			switch (s) {
				case "done":
					return DONE;
				case "open":
					return OPEN;
				case "locked":
					return LOCKED;
				default:
					return UNKNOWN;
			}
		}
	}

	static final class Result {
		final String id;
		final State state;
		/** How many today (or still to claim); 0: not known or none. */
		final int amount;
		final String note;

		Result(String id, State state, int amount, String note) {
			this.id = id;
			this.state = state;
			this.amount = amount;
			this.note = note;
		}
	}

	/** The game's values, on the client thread (or a test's map). */
	interface Vars {
		int varbit(int id);

		int varp(int id);

		boolean questDone(Quest quest);
	}

	/** Every daily in the website's order, with the side panel's short name (used until the guide has loaded). */
	static final String[][] DAILIES = {
		{"zaff", "Zaff's battlestaves"}, {"cromperty", "Cromperty's essence"}, {"bert", "Bert's sand"},
		{"flax", "Flax to bow strings"}, {"rantz", "Rantz's arrows"}, {"robin", "Robin's bonemeal"},
		{"thirus", "Thirus's dynamite"}, {"lundail", "Lundail's runes"}, {"herb_boxes", "NMZ herb boxes"},
		{"kingdom", "Miscellania"}, {"tog", "Tears of Guthix"}, {"birdhouses", "Bird houses"},
	};

	/** After 00:00 UTC the claimed varbits keep yesterday's value until you log in again or hop. */
	static final String RELOG_NOTE = "New day: hop worlds or relog to collect today's";
	/** Herb boxes: 9,500 Nightmare Zone points each, 15 a day. */
	static final int HERB_BOX_COST = 9500;
	static final int HERB_BOX_MAX = 15;
	/** The Hand in the Sand is finished at this quest value (RuneLite's SAND_QUEST_COMPLETE). */
	static final int SAND_QUEST_COMPLETE = 160;
	/** Robin: 13 bones a day per Morytania tier from medium. */
	static final int BONES_PER_TIER = 13;
	/** IRONMAN varbit: 0 a normal account, 2 an ultimate ironman. */
	static final int ULTIMATE = 2;

	private static final int[] VARROCK = {VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
		VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE};
	private static final int[] ARDOUGNE = {VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE,
		VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE};
	private static final int[] KANDARIN = {VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
		VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE};
	private static final int[] WESTERN = {VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
		VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE};
	private static final int[] MORYTANIA = {VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE,
		VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE};
	private static final int[] KOUREND = {VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE,
		VarbitID.KOUREND_DIARY_ELITE_COMPLETE};
	private static final int[] WILDERNESS = {VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
		VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE};

	/** Varbits whose change can change a daily: a re-read follows a few ticks later. */
	static final Set<Integer> WATCHED = Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(
		VarbitID.ZAFF_LAST_CLAIMED, VarbitID.ARDOUGNE_FREE_ESSENCE, VarbitID.YANILLE_SAND_CLAIMED, VarbitID.SEERS_FREE_FLAX,
		VarbitID.WESTERN_RANTZ_ARROWS, VarbitID.MORYTANIA_SLIME_CLAIMED, VarbitID.KOUREND_FREE_DYNAMITE, VarbitID.LUNDAIL_LAST_CLAIMED,
		VarbitID.NZONE_HERBBOXES_PURCHASED, VarbitID.HANDSAND_QUEST, VarbitID.IRONMAN,
		VARROCK[0], VARROCK[1], VARROCK[2], VARROCK[3], ARDOUGNE[0], ARDOUGNE[1], ARDOUGNE[2],
		KANDARIN[0], KANDARIN[1], KANDARIN[2], KANDARIN[3], WESTERN[0], WESTERN[1], WESTERN[2], WESTERN[3],
		MORYTANIA[0], MORYTANIA[1], MORYTANIA[2], KOUREND[0], KOUREND[1], KOUREND[2],
		WILDERNESS[0], WILDERNESS[1], WILDERNESS[2], WILDERNESS[3])));
	/** Varps likewise: the Nightmare Zone points (herb boxes). */
	static final int NZONE_POINTS_VARP = VarPlayerID.NZONE_REWARDPOINTS;

	private DailyChecks() {
	}

	/**
	 * Reads every daily. {@code newDay}: 00:00 UTC passed since login, so a claimed flag may still be
	 * yesterday's; {@code kingdomVisitedToday}: you were on Miscellania or Etceteria today.
	 */
	static Map<String, Result> read(Vars v, boolean newDay, boolean kingdomVisitedToday) {
		Map<String, Result> out = new LinkedHashMap<>();
		put(out, tiered("zaff", v, VARROCK, new int[]{15, 30, 60, 120}, "Needs Varrock Easy", VarbitID.ZAFF_LAST_CLAIMED, newDay));
		put(out, tiered("cromperty", v, ARDOUGNE, new int[]{100, 150, 250}, "Needs Ardougne Medium", VarbitID.ARDOUGNE_FREE_ESSENCE, newDay));
		put(out, bert(v, newDay));
		put(out, tiered("flax", v, KANDARIN, new int[]{30, 60, 120, 250}, "Needs Kandarin Easy", VarbitID.SEERS_FREE_FLAX, newDay));
		put(out, tiered("rantz", v, WESTERN, new int[]{25, 50, 100, 150}, "Needs Western Provinces Easy", VarbitID.WESTERN_RANTZ_ARROWS, newDay));
		put(out, robin(v, newDay));
		put(out, tiered("thirus", v, KOUREND, new int[]{20, 40, 80}, "Needs Kourend & Kebos Medium", VarbitID.KOUREND_FREE_DYNAMITE, newDay));
		put(out, tiered("lundail", v, WILDERNESS, new int[]{40, 80, 120, 200}, "Needs Wilderness Easy", VarbitID.LUNDAIL_LAST_CLAIMED, newDay));
		put(out, herbBoxes(v, newDay));
		put(out, v.questDone(Quest.THRONE_OF_MISCELLANIA)
			? new Result("kingdom", kingdomVisitedToday ? State.DONE : State.UNKNOWN, 0, null)
			: new Result("kingdom", State.LOCKED, 0, "Needs Throne of Miscellania"));
		put(out, needsQuest("tog", v, Quest.TEARS_OF_GUTHIX, "Needs Tears of Guthix"));
		put(out, needsQuest("birdhouses", v, Quest.BONE_VOYAGE, "Needs Bone Voyage"));
		return out;
	}

	private static void put(Map<String, Result> out, Result r) {
		out.put(r.id, r);
	}

	/** The amount of the highest diary tier done (amounts lowest tier first), 0 when none. */
	static int tierAmount(Vars v, int[] tierVarbits, int[] amounts) {
		int amount = 0;
		for (int i = 0; i < tierVarbits.length; i++) {
			if (v.varbit(tierVarbits[i]) == 1) {
				amount = amounts[i];
			}
		}
		return amount;
	}

	private static Result tiered(String id, Vars v, int[] tiers, int[] amounts, String lockedNote, int claimedVarbit, boolean newDay) {
		int amount = tierAmount(v, tiers, amounts);
		if (amount == 0) {
			return new Result(id, State.LOCKED, 0, lockedNote);
		}
		return claimed(id, v.varbit(claimedVarbit) != 0, amount, newDay);
	}

	private static Result claimed(String id, boolean claimed, int amount, boolean newDay) {
		if (!claimed) {
			return new Result(id, State.OPEN, amount, null);
		}
		return newDay ? new Result(id, State.OPEN, amount, RELOG_NOTE) : new Result(id, State.DONE, amount, null);
	}

	private static Result bert(Vars v, boolean newDay) {
		if (v.varbit(VarbitID.HANDSAND_QUEST) < SAND_QUEST_COMPLETE) {
			return new Result("bert", State.LOCKED, 0, "Needs The Hand in the Sand");
		}
		if (v.varbit(VarbitID.IRONMAN) == ULTIMATE) {
			return new Result("bert", State.LOCKED, 0, "Not for ultimate ironmen");
		}
		return claimed("bert", v.varbit(VarbitID.YANILLE_SAND_CLAIMED) != 0, 84, newDay);
	}

	/** Robin counts the bones exchanged today: done once all of today's are. */
	private static Result robin(Vars v, boolean newDay) {
		int amount = tierAmount(v, MORYTANIA, new int[]{BONES_PER_TIER, 2 * BONES_PER_TIER, 3 * BONES_PER_TIER});
		if (amount == 0) {
			return new Result("robin", State.LOCKED, 0, "Needs Morytania Medium");
		}
		int used = v.varbit(VarbitID.MORYTANIA_SLIME_CLAIMED);
		if (used >= amount) {
			return claimed("robin", true, amount, newDay);
		}
		return new Result("robin", State.OPEN, amount - used, used > 0 ? used + " of " + amount + " bones done" : null);
	}

	/** Herb boxes: not for ironmen; done after 15; locked while the points buy none. */
	private static Result herbBoxes(Vars v, boolean newDay) {
		if (v.varbit(VarbitID.IRONMAN) != 0) {
			return new Result("herb_boxes", State.LOCKED, 0, "Not for ironmen");
		}
		int bought = v.varbit(VarbitID.NZONE_HERBBOXES_PURCHASED);
		if (bought >= HERB_BOX_MAX) {
			return claimed("herb_boxes", true, HERB_BOX_MAX, newDay);
		}
		int affordable = Math.min(HERB_BOX_MAX - bought, v.varp(NZONE_POINTS_VARP) / HERB_BOX_COST);
		if (affordable <= 0) {
			return new Result("herb_boxes", State.LOCKED, 0, "Needs 9,500 Nightmare Zone points a box");
		}
		return new Result("herb_boxes", State.OPEN, affordable, bought > 0 ? bought + " of 15 bought" : null);
	}

	private static Result needsQuest(String id, Vars v, Quest quest, String lockedNote) {
		return v.questDone(quest) ? new Result(id, State.UNKNOWN, 0, null) : new Result(id, State.LOCKED, 0, lockedNote);
	}

	/**
	 * A daily's state in the side panel and the guide: your tick by hand first, then what the game
	 * says (when it knows), then the website's state (timers, visits, ticks made on the website),
	 * and open when nothing knows (so it can be ticked by hand).
	 */
	static State merged(boolean ticked, Result local, String serverState, String serverSource) {
		if (ticked) {
			return State.DONE;
		}
		State server = State.fromWire(serverState);
		// A tick made on the website counts like one made here.
		if (server == State.DONE && "manual".equals(serverSource)) {
			return State.DONE;
		}
		if (local != null && local.state != State.UNKNOWN) {
			return local.state;
		}
		return server == State.UNKNOWN ? State.OPEN : server;
	}

	/**
	 * The daily the guided run goes to next: the first open one after {@code current} (in the list's
	 * order, wrapping round), not skipped this run; null when none is left.
	 */
	static String nextOpen(List<String> order, Map<String, State> states, String current, Set<String> skipped) {
		int start = current == null ? -1 : order.indexOf(current);
		for (int i = 1; i <= order.size(); i++) {
			String id = order.get(Math.floorMod(start + i, order.size()));
			if (states.get(id) == State.OPEN && !skipped.contains(id)) {
				return id;
			}
		}
		return null;
	}
}
