package com.osrscompanion;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LiveActivityTest {
	private static final long NOW = 10_000_000L;

	/** Every signal at once: each step below takes away the winner and checks the next one wins. */
	private static LiveActivity.Signals all() {
		LiveActivity.Signals s = new LiveActivity.Signals();
		s.raid = "Chambers of Xeric";
		s.minigame = "Wintertodt";
		s.boss = "Vorkath";
		s.slayerTask = "blue dragons";
		s.slayerLeft = 12;
		s.slaying = true;
		s.farmRoute = "Herb run";
		s.farmDone = 3;
		s.farmTotal = 9;
		s.combatNpc = "Blue dragon";
		s.skill = "Fishing";
		s.bankOpen = true;
		return s;
	}

	@Test
	public void priorityOrder() {
		LiveActivity.Signals s = all();
		assertEquals(LiveActivity.Kind.RAID, LiveActivity.choose(s).kind);
		s.raid = null;
		assertEquals(LiveActivity.Kind.MINIGAME, LiveActivity.choose(s).kind);
		s.minigame = null;
		LiveActivity.Choice boss = LiveActivity.choose(s);
		assertEquals(LiveActivity.Kind.BOSS, boss.kind);
		assertEquals("Vorkath", boss.detail);
		s.boss = null;
		LiveActivity.Choice slayer = LiveActivity.choose(s);
		assertEquals(LiveActivity.Kind.SLAYER, slayer.kind);
		assertEquals("Blue dragons · 12 left", slayer.detail);
		assertEquals("Blue dragons", slayer.subject);
		s.slaying = false;
		LiveActivity.Choice farm = LiveActivity.choose(s);
		assertEquals(LiveActivity.Kind.FARM_RUN, farm.kind);
		assertEquals("Herb run · 3/9 patches", farm.detail);
		s.farmRoute = null;
		LiveActivity.Choice combat = LiveActivity.choose(s);
		assertEquals(LiveActivity.Kind.COMBAT, combat.kind);
		assertEquals("Blue dragon", combat.detail);
		s.combatNpc = null;
		assertEquals(LiveActivity.Kind.SKILLING, LiveActivity.choose(s).kind);
		s.skill = null;
		assertEquals(LiveActivity.Kind.BANKING, LiveActivity.choose(s).kind);
		s.bankOpen = false;
		assertEquals(LiveActivity.Kind.OTHER, LiveActivity.choose(s).kind);
	}

	@Test
	public void idleWinsOverAllButRaidsAndMinigames() {
		LiveActivity.Signals s = all();
		s.idle = true;
		assertEquals(LiveActivity.Kind.RAID, LiveActivity.choose(s).kind);
		s.raid = null;
		assertEquals(LiveActivity.Kind.MINIGAME, LiveActivity.choose(s).kind);
		s.minigame = null;
		assertEquals(LiveActivity.Kind.IDLE, LiveActivity.choose(s).kind);
	}

	@Test
	public void sameActivityKeepsItsKeyWhenOnlyCountsChange() {
		LiveActivity.Signals s = all();
		s.raid = null;
		s.minigame = null;
		s.boss = null;
		String before = LiveActivity.choose(s).key();
		s.slayerLeft = 11;
		LiveActivity.Choice after = LiveActivity.choose(s);
		assertEquals(before, after.key());
		assertEquals("Blue dragons · 11 left", after.detail);
	}

	@Test
	public void idleAfterFiveMinutesWithoutMovementXpOrAnimation() {
		LiveActivity.IdleTracker t = new LiveActivity.IdleTracker();
		assertFalse(t.isIdle(NOW));
		t.tick(NOW, 3200, 3200, 0, false, false);
		t.tick(NOW + LiveActivity.IDLE_MS - 1, 3200, 3200, 0, false, false);
		assertFalse(t.isIdle(NOW + LiveActivity.IDLE_MS - 1));
		t.tick(NOW + LiveActivity.IDLE_MS, 3200, 3200, 0, false, false);
		assertTrue(t.isIdle(NOW + LiveActivity.IDLE_MS));
		assertEquals(NOW, t.lastActiveMs());
		// Moving ends it.
		t.tick(NOW + LiveActivity.IDLE_MS + 600, 3201, 3200, 0, false, false);
		assertFalse(t.isIdle(NOW + LiveActivity.IDLE_MS + 600));
	}

	@Test
	public void fishingInPlaceIsNotIdle() {
		LiveActivity.IdleTracker t = new LiveActivity.IdleTracker();
		long at = NOW;
		for (int i = 0; i < 1000; i++, at += 600) {
			// Same tile, no animation on most ticks, but XP every 10 ticks.
			t.tick(at, 2600, 3400, 0, false, i % 10 == 0);
		}
		assertFalse(t.isIdle(at));
		LiveActivity.IdleTracker anim = new LiveActivity.IdleTracker();
		at = NOW;
		for (int i = 0; i < 1000; i++, at += 600) {
			anim.tick(at, 2600, 3400, 0, true, false);
		}
		assertFalse(anim.isIdle(at));
	}

	@Test
	public void skillingIsTheLatestNonCombatXpDropWithinAMinute() {
		Map<String, Long> last = new HashMap<>();
		assertNull(LiveActivity.skillingSkill(last, NOW));
		last.put("fishing", NOW - 30_000);
		last.put("cooking", NOW - 10_000);
		assertEquals("cooking", LiveActivity.skillingSkill(last, NOW));
		assertEquals("fishing", LiveActivity.skillingSkill(Collections.singletonMap("fishing", NOW - 60_000), NOW));
		assertNull(LiveActivity.skillingSkill(Collections.singletonMap("fishing", NOW - 60_001), NOW));
	}

	@Test
	public void combatXpIsNotSkilling() {
		assertFalse(LiveActivity.isSkillingXp("hitpoints", false));
		assertFalse(LiveActivity.isSkillingXp("Slayer", false));
		assertFalse(LiveActivity.isSkillingXp("magic", true));
		assertTrue(LiveActivity.isSkillingXp("magic", false));
		assertTrue(LiveActivity.isSkillingXp("woodcutting", true));
		assertFalse(LiveActivity.isSkillingXp(null, false));
	}

	@Test
	public void bossesFromTheListTheSlayerVariantsAndTheCatalog() {
		assertTrue(LiveActivity.isBoss("Vorkath", null));
		assertTrue(LiveActivity.isBoss("alchemical hydra", null));
		assertTrue(LiveActivity.isBoss("Branda the Fire Queen", null));
		assertFalse(LiveActivity.isBoss("Blue dragon", null));
		assertTrue(LiveActivity.isBoss("Some new boss", Collections.singleton("some new boss")));
		assertFalse(LiveActivity.isBoss(null, null));
	}

	@Test
	public void regionTables() {
		assertEquals("Inferno", LiveActivity.MINIGAME_REGIONS.get(9043));
		assertEquals("Tombs of Amascut", LiveActivity.RAID_REGIONS.get(15188));
		assertNull(LiveActivity.RAID_REGIONS.get(12850));
	}

	@Test
	public void changesAreDebouncedAndSpaced() {
		// The first status of a login goes out at once.
		assertTrue(LiveActivityMonitor.shouldSendChange("a", null, "a", NOW, 0, NOW));
		// Unchanged: nothing.
		assertFalse(LiveActivityMonitor.shouldSendChange("a", "a", null, 0, NOW, NOW));
		// Changed, but not held long enough yet.
		assertFalse(LiveActivityMonitor.shouldSendChange("b", "a", "b", NOW, 0, NOW + LiveActivityMonitor.DEBOUNCE_MS - 1));
		assertTrue(LiveActivityMonitor.shouldSendChange("b", "a", "b", NOW, 0, NOW + LiveActivityMonitor.DEBOUNCE_MS));
		// Held, but the last change went out too recently.
		long t = NOW + LiveActivityMonitor.DEBOUNCE_MS;
		assertFalse(LiveActivityMonitor.shouldSendChange("b", "a", "b", NOW, t - 1_000, t));
		assertTrue(LiveActivityMonitor.shouldSendChange("b", "a", "b", NOW, t - LiveActivityMonitor.MIN_CHANGE_GAP_MS, t));
	}

	@Test
	public void capitalize() {
		assertEquals("Abyssal demons", LiveActivity.capitalize("abyssal demons"));
		assertEquals("Fishing", LiveActivity.capitalize("FISHING"));
		assertEquals("Fishing", LiveActivity.capitalize("Fishing"));
	}

	@Test
	public void magicDetail() {
		LiveActivity.Signals s = new LiveActivity.Signals();
		s.skill = "magic";
		s.magicCast = MagicSpells.fromMenu("Cast", "High Level Alchemy -> Rune platebody", false);
		s.alchs = 37;
		LiveActivity.Choice c = LiveActivity.choose(s);
		assertEquals(LiveActivity.Kind.SKILLING, c.kind);
		assertEquals("High Level Alchemy", c.subject);
		assertEquals("High alching Rune platebody · 37 alchs", c.detail);
		assertEquals("Rune platebody.png", c.icon);

		// A teleport: Magic stays the subject (one stretch on the website), the place is the detail.
		s.teleport = MagicSpells.fromMenu("Cast", "Varrock Teleport", false);
		c = LiveActivity.choose(s);
		assertEquals("skilling|Magic", c.key());
		assertEquals("Teleported to Varrock", c.detail);
		assertEquals("Varrock Teleport.png", c.icon);
		s.teleport = MagicSpells.fromMenu("Cast", "Camelot Teleport", false);
		assertEquals("skilling|Magic", LiveActivity.choose(s).key());

		// Magic in a fight: combat on the monster, trained with Magic.
		s.combatNpc = "Hill Giant";
		s.combatMagic = true;
		s.combatSpell = "Fire Strike";
		c = LiveActivity.choose(s);
		assertEquals(LiveActivity.Kind.COMBAT, c.kind);
		assertEquals("Hill Giant", c.subject);
		assertEquals("Training Magic on Hill Giant · Fire Strike", c.detail);
		assertEquals("Fire Strike.png", c.icon);
	}

	@Test
	public void skillingItemAndPictures() {
		LiveActivity.Signals s = new LiveActivity.Signals();
		s.skill = "fishing";
		s.skillItem = "Raw shark";
		LiveActivity.Choice c = LiveActivity.choose(s);
		assertEquals("skilling|Fishing", c.key());
		assertEquals("Fishing · Raw shark", c.detail);
		assertEquals("Raw shark.png", c.icon);

		s = new LiveActivity.Signals();
		s.slayerTask = "abyssal demons";
		s.slaying = true;
		s.slayerNpc = "Abyssal demon";
		assertEquals("Abyssal demon.png", LiveActivity.choose(s).icon);
		s = new LiveActivity.Signals();
		s.farmRoute = "Herb run";
		s.farmItem = "Ranarr weed";
		assertEquals("Ranarr weed.png", LiveActivity.choose(s).icon);
	}
}
