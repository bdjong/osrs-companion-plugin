package com.osrscompanion;

import static org.junit.Assert.assertEquals;

import com.osrscompanion.BankLayoutPrep.HubConflict;
import org.junit.Test;

/** When the "Bank Tag Layouts" hub plugin would take our bank layout over (we only read its settings). */
public class BankLayoutPrepHubTest {
	@Test
	public void leftAloneWhenItsLayoutIsOffForTheTagOrNotByDefault() {
		assertEquals(HubConflict.NONE, BankLayoutPrep.hubConflict("DISABLED", "true"));
		assertEquals(HubConflict.NONE, BankLayoutPrep.hubConflict(null, "false"));
		assertEquals(HubConflict.NONE, BankLayoutPrep.hubConflict(null, null));
		assertEquals(HubConflict.NONE, BankLayoutPrep.hubConflict("", null));
	}

	@Test
	public void layoutByDefaultTakesTheTagOver() {
		assertEquals(HubConflict.BY_DEFAULT, BankLayoutPrep.hubConflict(null, "true"));
	}

	@Test
	public void itsOwnSavedLayoutWinsWhateverTheDefault() {
		assertEquals(HubConflict.SAVED, BankLayoutPrep.hubConflict("25189:0,13652:1", "false"));
		assertEquals(HubConflict.SAVED, BankLayoutPrep.hubConflict("25189:0,13652:1", "true"));
	}
}
