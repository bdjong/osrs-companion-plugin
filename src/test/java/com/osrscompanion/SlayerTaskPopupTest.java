package com.osrscompanion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SlayerTaskPopupTest {
	@Test
	public void bodyListsKillsAndSlayerXp() {
		assertEquals("<col=ffffff>Abyssal demons: 190 kills</col><br><col=ffffff>32,456 Slayer XP</col>",
			SlayerHelperManager.finishedBody("Abyssal demons", 190, 32456));
	}

	@Test
	public void bodyLeavesOutXpWhenTheStartWasNotSeen() {
		assertEquals("<col=ffffff>Trolls: 1 kill</col>", SlayerHelperManager.finishedBody("Trolls", 1, -1));
	}
}
