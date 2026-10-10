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
	public void teleportSpellsSayWhere() {
		assertEquals("Teleported to Varrock", cast("<col=00ff00>Varrock Teleport</col>").text());
		assertEquals("Teleported to Lumbridge", cast("<col=00ff00>Lumbridge Teleport</col>").text());
		assertEquals("Teleported to Falador", cast("Falador Teleport").text());
		assertEquals("Teleported to Camelot", cast("Camelot Teleport").text());
		assertEquals("Teleported to Ardougne", cast("Ardougne Teleport").text());
		assertEquals("Teleported to the Watchtower", cast("Watchtower Teleport").text());
		assertEquals("Teleported to Trollheim", cast("Trollheim Teleport").text());
		assertEquals("Teleported to Ape Atoll", cast("Ape Atoll Teleport").text());
		assertEquals("Teleported to Kourend Castle", cast("Kourend Castle Teleport").text());
		assertEquals("Teleported to Civitas illa Fortis", cast("Civitas illa Fortis Teleport").text());
		assertEquals("Teleported to your house", cast("Teleport to House").text());
		assertEquals("Teleported to Kharyrll (Canifis)", cast("Kharyrll Teleport").text());
		assertEquals("Teleported to Ghorrock (Frozen Waste Plateau)", cast("Ghorrock Teleport").text());
		assertEquals("Teleported to Moonclan (Lunar Isle)", cast("Moonclan Teleport").text());
		assertEquals("Teleported to Waterbirth Island", cast("Tele Group Waterbirth").text());
		assertEquals("Teleported to Catherby", cast("Catherby Teleport").text());
		assertEquals("Teleported to Lumbridge", cast("Lumbridge Home Teleport").text());
		assertEquals("Teleported to Salve Graveyard", cast("Salve Graveyard Teleport").text());
		assertEquals(MagicSpells.Type.TELEPORT, cast("Varrock Teleport").type);
	}

	@Test
	public void tabletsTeleportToo() {
		MagicSpells.Cast tab = MagicSpells.fromMenu("Break", "<col=ff9040>Varrock teleport</col>", false);
		assertEquals(MagicSpells.Type.TELEPORT, tab.type);
		assertTrue(tab.tablet);
		assertEquals("Teleported to Varrock", tab.text());
		assertEquals("Varrock teleport (tablet).png", tab.icon());
		assertEquals("Teleported to Kourend castle", MagicSpells.fromMenu("Break", "Kourend castle teleport", false).text());
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
		assertEquals("High alching Rune platebody", hi.text());
		assertEquals("Rune platebody.png", hi.icon());
		MagicSpells.Cast lo = cast("Low Level Alchemy -> Iron dagger");
		assertEquals("Low alching Iron dagger", lo.text());
		// The spell selected but not used yet: no item.
		MagicSpells.Cast selected = cast("<col=00ff00>High Level Alchemy</col>");
		assertEquals("High alching", selected.text());
		assertEquals("High Level Alchemy.png", selected.icon());
	}

	@Test
	public void offensiveSpellsTrainMagic() {
		MagicSpells.Cast c = MagicSpells.fromMenu("Cast", "<col=00ff00>Fire Strike</col><col=ffffff> -> <col=ffff00>Hill Giant<col=ff00>  (level-28)", true);
		assertEquals(MagicSpells.Type.OFFENSIVE, c.type);
		assertEquals("Hill Giant", c.target);
		assertEquals("Training Magic on Hill Giant", c.text());
		assertEquals("Fire Strike.png", c.icon());
		assertEquals(MagicSpells.Type.OFFENSIVE, MagicSpells.typeOf("Ice Barrage", false));
		assertEquals(MagicSpells.Type.OFFENSIVE, MagicSpells.typeOf("Water Surge", false));
		assertEquals(MagicSpells.Type.OFFENSIVE, MagicSpells.typeOf("Crumble Undead", false));
		assertEquals(MagicSpells.Type.OFFENSIVE, MagicSpells.typeOf("Some New Spell", true));
		assertEquals(MagicSpells.Type.UTILITY, MagicSpells.typeOf("Monster Examine", true));
	}

	@Test
	public void utilitySpellsAreCasting() {
		assertEquals("Casting Superheat Item", cast("Superheat Item -> Iron ore").text());
		assertEquals("Casting Lvl-1 Enchant", cast("Lvl-1 Enchant -> Sapphire ring").text());
		assertEquals("Casting Humidify", cast("Humidify").text());
		assertEquals("Casting Plank Make", cast("Plank Make -> Oak logs").text());
		assertEquals(MagicSpells.Type.UTILITY, cast("Spin Flax").type);
	}

	@Test
	public void iconsAreWikiFiles() {
		assertEquals("Varrock Teleport.png", MagicSpells.spellIcon("Varrock Teleport"));
		assertEquals("Ape Atoll Teleport (standard).png", MagicSpells.spellIcon("Ape Atoll Teleport"));
		assertEquals("Tele Group Moonclan.png", MagicSpells.spellIcon("Tele Group Moonclan"));
		assertEquals("Magic icon.png", MagicSpells.spellIcon(null));
	}

	@Test
	public void otherClicksAreNoCast() {
		assertNull(MagicSpells.fromMenu("Attack", "Hill Giant", true));
		assertNull(MagicSpells.fromMenu("Cast", "", false));
		assertNull(MagicSpells.fromMenu(null, "Varrock Teleport", false));
	}
}
