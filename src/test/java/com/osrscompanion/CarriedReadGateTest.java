package com.osrscompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.runelite.api.GameState;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;

/** The bank sync's read guard: an "inventory" source is only read when it is complete and reliable. */
public class CarriedReadGateTest {
	@Test
	public void readsWhenLoggedInLoadedAndBothContainersPresent() {
		CarriedReadGate gate = new CarriedReadGate();
		assertTrue(gate.canReadCarried(GameState.LOGGED_IN, true, true, true));
	}

	@Test
	public void neverReadsWhileNotLoggedIn() {
		CarriedReadGate gate = new CarriedReadGate();
		for (GameState s : new GameState[]{GameState.LOADING, GameState.HOPPING, GameState.LOGGING_IN,
			GameState.LOGIN_SCREEN, GameState.CONNECTION_LOST, GameState.UNKNOWN}) {
			assertFalse(s.name(), gate.canReadCarried(s, true, true, true));
			assertFalse(s.name(), gate.canReadBank(s, true, true));
		}
	}

	@Test
	public void neverReadsWithoutThePlayerLoaded() {
		CarriedReadGate gate = new CarriedReadGate();
		assertFalse(gate.canReadCarried(GameState.LOGGED_IN, false, true, true));
		assertFalse(gate.canReadBank(GameState.LOGGED_IN, false, true));
	}

	@Test
	public void aNullContainerIsUnknownNotEmpty() {
		CarriedReadGate gate = new CarriedReadGate();
		assertFalse("WORN not there", gate.canReadCarried(GameState.LOGGED_IN, true, true, false));
		assertFalse("INV not there", gate.canReadCarried(GameState.LOGGED_IN, true, false, true));
		assertFalse("no bank container", gate.canReadBank(GameState.LOGGED_IN, true, false));
	}

	@Test
	public void afterLoginBothContainersMustArriveFirst() {
		CarriedReadGate gate = new CarriedReadGate();
		gate.onGameState(GameState.LOGIN_SCREEN);
		gate.onGameState(GameState.LOGGING_IN);
		gate.onGameState(GameState.LOADING);
		gate.onGameState(GameState.LOGGED_IN);
		// The client may still hold old (or empty) containers: not before the game sent them again.
		assertFalse(gate.canReadCarried(GameState.LOGGED_IN, true, true, true));
		gate.onContainerChanged(InventoryID.INV, true);
		assertFalse("only INV arrived: an inventory without the worn gear would wipe the worn items",
			gate.canReadCarried(GameState.LOGGED_IN, true, true, true));
		gate.onContainerChanged(InventoryID.WORN, false);
		assertFalse("a WORN event while it still reads null does not count",
			gate.canReadCarried(GameState.LOGGED_IN, true, true, true));
		gate.onContainerChanged(InventoryID.WORN, true);
		assertTrue(gate.canReadCarried(GameState.LOGGED_IN, true, true, true));
	}

	@Test
	public void aWorldHopWaitsForTheContainersAgainButARegionLoadDoesNot() {
		CarriedReadGate gate = new CarriedReadGate();
		gate.onGameState(GameState.LOADING);
		gate.onGameState(GameState.LOGGED_IN);
		assertTrue("a region load (Ferox Enclave) keeps the containers", gate.canReadCarried(GameState.LOGGED_IN, true, true, true));
		gate.onGameState(GameState.HOPPING);
		gate.onGameState(GameState.LOGGED_IN);
		assertFalse(gate.canReadCarried(GameState.LOGGED_IN, true, true, true));
		gate.onContainerChanged(InventoryID.WORN, true);
		gate.onContainerChanged(InventoryID.INV, true);
		assertTrue(gate.canReadCarried(GameState.LOGGED_IN, true, true, true));
	}

	@Test
	public void theBankDoesNotWaitForTheCarriedContainers() {
		CarriedReadGate gate = new CarriedReadGate();
		gate.onGameState(GameState.LOGGING_IN);
		assertTrue(gate.canReadBank(GameState.LOGGED_IN, true, true));
	}

	@Test
	public void groupStorageNeedsTheInterfaceOpenAndTheContainer() {
		CarriedReadGate gate = new CarriedReadGate();
		assertTrue(gate.canReadGroupStorage(GameState.LOGGED_IN, true, true, true));
		assertFalse("interface closed", gate.canReadGroupStorage(GameState.LOGGED_IN, true, true, false));
		assertFalse("no container", gate.canReadGroupStorage(GameState.LOGGED_IN, true, false, true));
		assertFalse("player not loaded", gate.canReadGroupStorage(GameState.LOGGED_IN, false, true, true));
		assertFalse("hopping", gate.canReadGroupStorage(GameState.HOPPING, true, true, true));
	}

	@Test
	public void nothingEquippedReadsAsEmptyWornAfterAFewTicks() {
		CarriedReadGate gate = new CarriedReadGate();
		gate.onGameState(GameState.LOGGING_IN);
		gate.onGameState(GameState.LOGGED_IN);
		// The game never sends WORN when nothing is equipped.
		gate.onContainerChanged(InventoryID.INV, true, 100);
		assertFalse("right after the inventory arrived WORN may still come",
			gate.canReadCarried(GameState.LOGGED_IN, true, true, false, 101));
		assertTrue(gate.canReadCarried(GameState.LOGGED_IN, true, true, false, 100 + CarriedReadGate.NO_WORN_TICKS));
		assertTrue(gate.nothingWorn(false, 100 + CarriedReadGate.NO_WORN_TICKS));
		assertFalse("without a tick a null WORN stays unknown", gate.canReadCarried(GameState.LOGGED_IN, true, true, false));
	}

	@Test
	public void nothingEquippedStillNeedsTheInventory() {
		CarriedReadGate gate = new CarriedReadGate();
		gate.onGameState(GameState.HOPPING);
		gate.onGameState(GameState.LOGGED_IN);
		assertFalse(gate.canReadCarried(GameState.LOGGED_IN, true, true, false, 500));
		assertFalse(gate.canReadCarried(GameState.LOGGED_IN, true, false, false, 500));
	}

	@Test
	public void startedWhileLoggedInWithNothingEquipped() {
		CarriedReadGate gate = new CarriedReadGate();
		assertTrue(gate.canReadCarried(GameState.LOGGED_IN, true, true, false, 7));
	}

	@Test
	public void aWornContainerThatArrivesIsStillWaitedFor() {
		CarriedReadGate gate = new CarriedReadGate();
		gate.onGameState(GameState.LOGGING_IN);
		gate.onGameState(GameState.LOGGED_IN);
		gate.onContainerChanged(InventoryID.INV, true, 10);
		// WORN present but not arrived yet (an old copy): not read, however long it takes.
		assertFalse(gate.canReadCarried(GameState.LOGGED_IN, true, true, true, 50));
		gate.onContainerChanged(InventoryID.WORN, true, 51);
		assertTrue(gate.canReadCarried(GameState.LOGGED_IN, true, true, true, 51));
	}
}
