package com.osrscompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MagicSpellsTest {
	private static MagicSpells.Cast cast(String target) {
		return MagicSpells.fromMenu("Cast", target, false);
	}

	@Test
	public void teleportSpellsAreTeleports() {
		assertEquals(MagicSpells.Type.TELEPORT, cast("Varrock Teleport").type);
	}

	@Test
	public void tabletsTeleportToo() {
		MagicSpells.Cast tab = MagicSpells.fromMenu("Break", "<col=ff9040>Varrock teleport</col>", false);
		assertEquals(MagicSpells.Type.TELEPORT, tab.type);
		assertTrue(tab.tablet);
		assertNull(MagicSpells.fromMenu("Break", "Clay pot", false));
	}

	@Test
	public void notTeleports() {
		assertFalse(MagicSpells.isTeleport("Tele Block"));
		assertFalse(MagicSpells.isTeleport("Teleport Other Varrock"));
		assertFalse(MagicSpells.isTeleport("Telekinetic Grab"));
		assertEquals(MagicSpells.Type.OFFENSIVE, MagicSpells.typeOf("Tele Block", true));
	}

	@Test
	public void alchemyNamesTheItem() {
		MagicSpells.Cast hi = cast("<col=00ff00>High Level Alchemy</col><col=ffffff> -> <col=ff9040>Rune platebody</col>");
		assertEquals(MagicSpells.Type.HIGH_ALCH, hi.type);
		assertEquals("High Level Alchemy", hi.spell);
		MagicSpells.Cast lo = cast("Low Level Alchemy -> Iron dagger");
		// The spell selected but not used yet: no item.
		MagicSpells.Cast selected = cast("<col=00ff00>High Level Alchemy</col>");
	}

	@Test
	public void offensiveSpellsTrainMagic() {
		MagicSpells.Cast c = MagicSpells.fromMenu("Cast", "<col=00ff00>Fire Strike</col><col=ffffff> -> <col=ffff00>Hill Giant<col=ff00>  (level-28)", true);
		assertEquals(MagicSpells.Type.OFFENSIVE, c.type);
		assertEquals("Hill Giant", c.target);
		assertEquals(MagicSpells.Type.OFFENSIVE, MagicSpells.typeOf("Ice Barrage", false));
		assertEquals(MagicSpells.Type.OFFENSIVE, MagicSpells.typeOf("Water Surge", false));
		assertEquals(MagicSpells.Type.OFFENSIVE, MagicSpells.typeOf("Crumble Undead", false));
		assertEquals(MagicSpells.Type.OFFENSIVE, MagicSpells.typeOf("Some New Spell", true));
		assertEquals(MagicSpells.Type.UTILITY, MagicSpells.typeOf("Monster Examine", true));
	}

	@Test
	public void utilitySpellsAreCasting() {
		assertEquals(MagicSpells.Type.UTILITY, cast("Spin Flax").type);
	}


	@Test
	public void otherClicksAreNoCast() {
		assertNull(MagicSpells.fromMenu("Attack", "Hill Giant", true));
		assertNull(MagicSpells.fromMenu("Cast", "", false));
		assertNull(MagicSpells.fromMenu(null, "Varrock Teleport", false));
	}
}
