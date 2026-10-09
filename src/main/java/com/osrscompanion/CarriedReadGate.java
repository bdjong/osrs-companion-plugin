package com.osrscompanion;

import net.runelite.api.GameState;
import net.runelite.api.gameval.InventoryID;

/**
 * When the inventory + worn gear (bank-memory source {@code inventory}) and the bank may be read for
 * an upload. The website replaces a whole source per upload, so a read taken while the client is not
 * really in game (logging in, hopping, loading, a dead client thread) would wipe what the player owns.
 *
 * <ul>
 *   <li>Nothing is read unless the game state is {@link GameState#LOGGED_IN} and the local player is
 *       loaded (has a name).</li>
 *   <li>The carried items need both containers present (a null INV or WORN is "not known", never
 *       "empty"), and both must have arrived ({@code ItemContainerChanged}) since the last login or
 *       world hop. So the first inventory upload of a session never goes up before the game sent
 *       both containers.</li>
 *   <li>The bank needs its container present (it is only filled once the bank was opened).</li>
 * </ul>
 *
 * Starts "arrived": a plugin started while logged in finds both containers already there. Pure, for
 * unit tests; client thread only.
 */
final class CarriedReadGate {
	private boolean invArrived = true;
	private boolean wornArrived = true;

	/** A login, a world hop or the login screen: the containers have to arrive again. */
	void onGameState(GameState state) {
		if (state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR
			|| state == GameState.LOGGING_IN || state == GameState.HOPPING) {
			invArrived = false;
			wornArrived = false;
		}
	}

	/** An {@code ItemContainerChanged}; {@code present} is whether the client returns that container now. */
	void onContainerChanged(int containerId, boolean present) {
		if (!present) {
			return;
		}
		if (containerId == InventoryID.INV) {
			invArrived = true;
		} else if (containerId == InventoryID.WORN) {
			wornArrived = true;
		}
	}

	/** In game with the player loaded: the only time anything is read. */
	static boolean inGame(GameState state, boolean playerLoaded) {
		return state == GameState.LOGGED_IN && playerLoaded;
	}

	/** Whether the inventory + worn gear can be read as the complete {@code inventory} source. */
	boolean canReadCarried(GameState state, boolean playerLoaded, boolean invPresent, boolean wornPresent) {
		return inGame(state, playerLoaded) && invPresent && wornPresent && invArrived && wornArrived;
	}

	/** Whether the bank container can be read as the {@code bank} source. */
	boolean canReadBank(GameState state, boolean playerLoaded, boolean bankPresent) {
		return inGame(state, playerLoaded) && bankPresent;
	}

	boolean bothArrived() {
		return invArrived && wornArrived;
	}
}
