package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrscompanion.DailyChecks.Result;
import com.osrscompanion.DailyChecks.State;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

	@Test
	public void everyDailyIsRead() {
		Map<String, Result> r = read(false, false);
		assertEquals(DailyChecks.DAILIES.length, r.size());
		for (String[] d : DailyChecks.DAILIES) {
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

	@Test
	public void mergeOrder() {
		Result open = new Result("zaff", State.OPEN, 30, null);
		Result unknown = new Result("tog", State.UNKNOWN, 0, null);
		// A hand tick wins, then the game, then the website, else open.
		assertEquals(State.DONE, DailyChecks.merged(true, open, null, null));
		assertEquals(State.OPEN, DailyChecks.merged(false, open, "done", "plugin"));
		assertEquals(State.DONE, DailyChecks.merged(false, open, "done", "manual"));
		assertEquals(State.DONE, DailyChecks.merged(false, unknown, "done", "timer"));
		assertEquals(State.OPEN, DailyChecks.merged(false, unknown, null, null));
		assertEquals(State.LOCKED, DailyChecks.merged(false, null, "locked", "requirements"));
	}

	@Test
	public void theRunGoesToTheNextOpenDaily() {
		List<String> order = Arrays.asList("a", "b", "c", "d");
		Map<String, State> s = new LinkedHashMap<>();
		s.put("a", State.DONE);
		s.put("b", State.OPEN);
		s.put("c", State.LOCKED);
		s.put("d", State.OPEN);
		assertEquals("b", DailyChecks.nextOpen(order, s, null, Collections.emptySet()));
		assertEquals("d", DailyChecks.nextOpen(order, s, "b", Collections.emptySet()));
		// Wraps round to one still open earlier in the list.
		assertEquals("b", DailyChecks.nextOpen(order, s, "d", Collections.emptySet()));
		assertNull(DailyChecks.nextOpen(order, s, "d", new HashSet<>(Arrays.asList("b", "d"))));
	}
}
