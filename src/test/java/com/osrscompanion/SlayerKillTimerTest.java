package com.osrscompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SlayerKillTimerTest {
	@Test
	public void barrageTimesEveryMonsterOnItsOwn() {
		SlayerKillTimer timer = new SlayerKillTimer();
		// One barrage hits three monsters at once; a fourth is only splashed by the next cast.
		timer.hit(1, 0);
		timer.hit(2, 0);
		timer.hit(3, 0);
		timer.hit(4, 2_400);
		// Later casts land on the ones already being timed: their start stays the first hit.
		timer.hit(1, 2_400);
		timer.hit(2, 2_400);
		assertEquals(Long.valueOf(6_000), timer.died(1, 6_000));
		assertEquals(Long.valueOf(9_000), timer.died(2, 9_000));
		assertEquals(Long.valueOf(9_600), timer.died(4, 12_000));
		assertEquals(Long.valueOf(13_000), timer.died(3, 13_000));
		assertEquals(0, timer.fighting());
	}

	@Test
	public void startingTheAttackStartsTheTimerBeforeTheFirstHit() {
		SlayerKillTimer timer = new SlayerKillTimer();
		timer.attacked(7, 1_000);
		timer.hit(7, 3_000);
		assertEquals(Long.valueOf(9_000), timer.died(7, 10_000));
	}

	@Test
	public void monstersNeverHitDontCount() {
		SlayerKillTimer timer = new SlayerKillTimer();
		// Clicked on, but someone else killed it before your first hit.
		timer.attacked(5, 0);
		assertNull(timer.died(5, 4_000));
		// Never touched at all.
		assertNull(timer.died(6, 4_000));
	}

	@Test
	public void aDeathCountsOnce() {
		SlayerKillTimer timer = new SlayerKillTimer();
		timer.hit(3, 0);
		// ActorDeath, then NpcDespawned for the same monster.
		assertEquals(Long.valueOf(5_000), timer.died(3, 5_000));
		assertNull(timer.died(3, 5_600));
	}

	@Test
	public void aMonsterThatWalkedOffIsForgotten() {
		SlayerKillTimer timer = new SlayerKillTimer();
		timer.hit(9, 0);
		timer.forget(9);
		// The index comes back as a new monster: its own timer.
		timer.hit(9, 60_000);
		assertEquals(Long.valueOf(4_000), timer.died(9, 64_000));
	}

	@Test
	public void fightsThatNeverEndAreDropped() {
		SlayerKillTimer timer = new SlayerKillTimer();
		timer.hit(1, 0);
		timer.hit(2, SlayerKillTimer.STALE_MS);
		timer.prune(SlayerKillTimer.STALE_MS + 1);
		assertEquals(1, timer.fighting());
		assertNull(timer.died(1, SlayerKillTimer.STALE_MS + 2));
	}
}
