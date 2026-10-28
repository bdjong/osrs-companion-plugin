package com.osrscompanion;

import net.runelite.api.GameState;
import net.runelite.api.gameval.InventoryID;

/**
 * When the inventory and worn gear (bank-memory sources {@code inventory} and {@code worn}, always read
 * and sent together) and the bank may be read for
 * an upload. The website replaces a whole source per upload, so a read taken while the client is not
 * really in game (logging in, hopping, loading, a dead client thread) would wipe what the player owns.
 *
 * <ul>
 *   <li>Nothing is read unless the game state is {@link GameState#LOGGED_IN} and the local player is
 *       loaded (has a name).</li>
 *   <li>The carried items need both containers present (a null INV or WORN is "not known", never
 *       "empty"), and both must have arrived ({@code ItemContainerChanged}) since the last login or
 *       world hop. So the first inventory upload of a session never goes up before the game sent
 *       both containers. One exception: with nothing equipped the game never sends WORN, so once INV
 *       arrived and {@link #NO_WORN_TICKS} game ticks passed without a WORN container, the worn gear
 *       reads as empty (else such a player's inventory never syncs, and an item taken out of the bank
 *       is gone).</li>
 *   <li>The bank needs its container present (it is only filled once the bank was opened).</li>
 *   <li>The GIM group storage needs its container present and its interface open.</li>
 * </ul>
 *
 * Starts "arrived": a plugin started while logged in finds both containers already there. Pure, for
 * unit tests; client thread only.
 */
final class CarriedReadGate {
	/** Game ticks after the inventory arrived without a WORN container before nothing equipped is assumed. */
	static final int NO_WORN_TICKS = 5;
	/** No tick known (the 4-argument checks): a missing WORN container stays "not known". */
	static final int NO_TICK = Integer.MIN_VALUE;

	private boolean invArrived = true;
	private boolean wornArrived = true;
	/** The tick the inventory arrived at; NO_TICK when it was already there (the plugin started while logged in). */
	private int invArrivedTick = NO_TICK;

	/** A login, a world hop or the login screen: the containers have to arrive again. */
	void onGameState(GameState state) {
		if (state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR
			|| state == GameState.LOGGING_IN || state == GameState.HOPPING) {
			invArrived = false;
			wornArrived = false;
			invArrivedTick = NO_TICK;
		}
	}

	/** An {@code ItemContainerChanged}; {@code present} is whether the client returns that container now. */
	void onContainerChanged(int containerId, boolean present) {
		onContainerChanged(containerId, present, NO_TICK);
	}

	/** As {@link #onContainerChanged(int, boolean)}, at game tick {@code tick}. */
	void onContainerChanged(int containerId, boolean present, int tick) {
		if (!present) {
			return;
		}
		if (containerId == InventoryID.INV) {
			if (!invArrived) {
				invArrivedTick = tick;
			}
			invArrived = true;
		} else if (containerId == InventoryID.WORN) {
			wornArrived = true;
		}
	}

	/**
	 * Whether nothing is equipped: no WORN container, while the inventory arrived at least
	 * {@link #NO_WORN_TICKS} ticks ago (or was already there when the plugin started). The worn gear then
	 * reads as empty. Never at {@link #NO_TICK}.
	 */
	boolean nothingWorn(boolean wornPresent, int tick) {
		if (wornPresent || !invArrived || tick == NO_TICK) {
			return false;
		}
		return invArrivedTick == NO_TICK || tick - invArrivedTick >= NO_WORN_TICKS;
	}

	/** In game with the player loaded: the only time anything is read. */
	static boolean inGame(GameState state, boolean playerLoaded) {
		return state == GameState.LOGGED_IN && playerLoaded;
	}

	/** Whether the inventory and worn gear can be read as the complete {@code inventory} and {@code worn} sources (both or neither). */
	boolean canReadCarried(GameState state, boolean playerLoaded, boolean invPresent, boolean wornPresent) {
		return canReadCarried(state, playerLoaded, invPresent, wornPresent, NO_TICK);
	}

	/** As above at game tick {@code tick}: a missing WORN container also passes when {@link #nothingWorn} (read as empty). */
	boolean canReadCarried(GameState state, boolean playerLoaded, boolean invPresent, boolean wornPresent, int tick) {
		if (!inGame(state, playerLoaded) || !invPresent || !invArrived) {
			return false;
		}
		return wornPresent ? wornArrived : nothingWorn(false, tick);
	}

	/** Whether the bank container can be read as the {@code bank} source. */
	boolean canReadBank(GameState state, boolean playerLoaded, boolean bankPresent) {
		return inGame(state, playerLoaded) && bankPresent;
	}

	/**
	 * Whether the GIM group storage container ({@code INV_GROUP_TEMP}) can be read as the {@code group_storage}
	 * source: in game, the container present and the group storage interface open (outside it the container is
	 * stale or gone, and an empty read would wipe the stored copy).
	 */
	boolean canReadGroupStorage(GameState state, boolean playerLoaded, boolean containerPresent, boolean interfaceOpen) {
		return inGame(state, playerLoaded) && containerPresent && interfaceOpen;
	}

}
