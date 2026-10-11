package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.osrscompanion.DailyChecks.Result;
import com.osrscompanion.DailyChecks.State;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;

/** Dailies read from varbits (as RuneLite's Daily Task Indicators does), the merge with hand ticks and the run order. */
public class DailyChecksTest {
	private Map<Integer, Integer> varbits;
	private Map<Integer, Integer> varps;
	private Set<Quest> quests;

	@Before
	public void setUp() {
		varbits = new HashMap<>();
		varps = new HashMap<>();
		quests = EnumSet.noneOf(Quest.class);
	}

	private Map<String, Result> read(boolean newDay, boolean kingdomVisited) {
		return DailyChecks.read(new DailyChecks.Vars() {
			@Override
			public int varbit(int id) {
				return varbits.getOrDefault(id, 0);
			}

			@Override
			public int varp(int id) {
				return varps.getOrDefault(id, 0);
			}

			@Override
			public boolean questDone(Quest quest) {
				return quests.contains(quest);
			}
		}, newDay, kingdomVisited);
	}

	/** Every daily the website lists (src/data/dailies.json): id and short name. */
	private static final String[][] DAILIES = {
		{"zaff", "Zaff's battlestaves"}, {"cromperty", "Cromperty's essence"}, {"bert", "Bert's sand"},
		{"flax", "Flax to bow strings"}, {"rantz", "Rantz's arrows"}, {"robin", "Robin's bonemeal"},
		{"thirus", "Thirus's dynamite"}, {"lundail", "Lundail's runes"}, {"herb_boxes", "NMZ herb boxes"},
		{"kingdom", "Miscellania"}, {"tog", "Tears of Guthix"}, {"birdhouses", "Bird houses"},
	};

	@Test
	public void everyDailyIsRead() {
		Map<String, Result> r = read(false, false);
		assertEquals(DAILIES.length, r.size());
		for (String[] d : DAILIES) {
			assertTrue(d[0], r.containsKey(d[0]));
		}
	}

	@Test
	public void withoutTheDiaryADailyIsLocked() {
		Result zaff = read(false, false).get("zaff");
		assertEquals(State.LOCKED, zaff.state);
		assertEquals("Needs Varrock Easy", zaff.note);
		assertEquals("Needs Ardougne Medium", read(false, false).get("cromperty").note);
	}

	@Test
	public void theHighestDiaryTierSetsTheAmount() {
		varbits.put(VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1);
		varbits.put(VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, 1);
		varbits.put(VarbitID.VARROCK_DIARY_HARD_COMPLETE, 1);
		Result zaff = read(false, false).get("zaff");
		assertEquals(State.OPEN, zaff.state);
		assertEquals(60, zaff.amount);
		varbits.put(VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE, 1);
		assertEquals(250, read(false, false).get("cromperty").amount);
	}

	@Test
	public void theClaimedVarbitMeansDone() {
		varbits.put(VarbitID.KANDARIN_DIARY_EASY_COMPLETE, 1);
		assertEquals(State.OPEN, read(false, false).get("flax").state);
		varbits.put(VarbitID.SEERS_FREE_FLAX, 1);
		assertEquals(State.DONE, read(false, false).get("flax").state);
	}

	@Test
	public void afterMidnightAClaimNeedsARelog() {
		varbits.put(VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, 1);
		varbits.put(VarbitID.LUNDAIL_LAST_CLAIMED, 1);
		Result lundail = read(true, false).get("lundail");
		assertEquals(State.OPEN, lundail.state);
		assertEquals(DailyChecks.RELOG_NOTE, lundail.note);
	}

	@Test
	public void robinCountsTheBonesExchanged() {
		varbits.put(VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, 1);
		varbits.put(VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, 1);
		varbits.put(VarbitID.MORYTANIA_SLIME_CLAIMED, 13);
		Result robin = read(false, false).get("robin");
		assertEquals(State.OPEN, robin.state);
		assertEquals(13, robin.amount);
		assertEquals("13 of 26 bones done", robin.note);
		varbits.put(VarbitID.MORYTANIA_SLIME_CLAIMED, 26);
		assertEquals(State.DONE, read(false, false).get("robin").state);
	}

	@Test
	public void bertNeedsTheQuestAndNoUltimateIronman() {
		assertEquals("Needs The Hand in the Sand", read(false, false).get("bert").note);
		varbits.put(VarbitID.HANDSAND_QUEST, DailyChecks.SAND_QUEST_COMPLETE);
		assertEquals(State.OPEN, read(false, false).get("bert").state);
		assertEquals(84, read(false, false).get("bert").amount);
		varbits.put(VarbitID.IRONMAN, DailyChecks.ULTIMATE);
		assertEquals(State.LOCKED, read(false, false).get("bert").state);
	}

	@Test
	public void herbBoxesNeedPointsAndNoIronman() {
		assertEquals(State.LOCKED, read(false, false).get("herb_boxes").state);
		varps.put(VarPlayerID.NZONE_REWARDPOINTS, 5 * DailyChecks.HERB_BOX_COST + 10);
		Result r = read(false, false).get("herb_boxes");
		assertEquals(State.OPEN, r.state);
		assertEquals(5, r.amount);
		varbits.put(VarbitID.NZONE_HERBBOXES_PURCHASED, 12);
		assertEquals(3, read(false, false).get("herb_boxes").amount);
		varbits.put(VarbitID.NZONE_HERBBOXES_PURCHASED, 15);
		assertEquals(State.DONE, read(false, false).get("herb_boxes").state);
		varbits.put(VarbitID.IRONMAN, 1);
		assertEquals("Not for ironmen", read(false, false).get("herb_boxes").note);
	}

	@Test
	public void untrackedDailiesAreUnknownOnceUnlocked() {
		assertEquals(State.LOCKED, read(false, false).get("tog").state);
		quests.add(Quest.TEARS_OF_GUTHIX);
		quests.add(Quest.THRONE_OF_MISCELLANIA);
		assertEquals(State.UNKNOWN, read(false, false).get("tog").state);
		assertEquals(State.UNKNOWN, read(false, false).get("kingdom").state);
		assertEquals(State.DONE, read(false, true).get("kingdom").state);
	}

}
