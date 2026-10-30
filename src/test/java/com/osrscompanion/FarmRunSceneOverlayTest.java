package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.FarmRoute;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.Test;

public class FarmRunSceneOverlayTest {
	private static FarmRoute.Travel travel(String name, String how) {
		return new Gson().fromJson("{\"name\":\"" + name + "\",\"how\":\"" + how + "\"}", FarmRoute.Travel.class);
	}

	@Test
	public void jewelleryBoxWayLooksForTheBoxOnly() {
		// The run after the box may name a portal; the house object to use is still the box.
		FarmRoute.Travel box = travel("House teleport tab, then the jewellery box (Ring of dueling)",
			"Use your House teleport tab, then use the Basic jewellery box in your POH (Ring of dueling teleports). Then the Castle Wars portal.");
		assertEquals(Collections.singletonList("jewellery box"), FarmRunSceneOverlay.houseObjectNames(box));
	}

	@Test
	public void housePortalWayLooksForThePortal() {
		FarmRoute.Travel portal = travel("House teleport tab, then the Troll Stronghold portal",
			"Take it in your POH.");
		assertEquals(Collections.singletonList("troll stronghold"), FarmRunSceneOverlay.houseObjectNames(portal));
	}

	@Test
	public void noHouseNoObject() {
		assertEquals(Collections.emptyList(), FarmRunSceneOverlay.houseObjectNames(travel("Amulet of glory", "Edgeville, then the trapdoor.")));
	}

	@Test
	public void outOfViewTheArrowGoesToTheNearestPointOfTheWay() {
		// The Monastery's fairy ring, then a far stop's tile; nothing loaded (an empty scene).
		FarmRoute.Travel way = new Gson().fromJson("{\"name\":\"Fairy ring CIQ\",\"way\":[[2658,3230,0],[1700,3500,0]]}",
			FarmRoute.Travel.class);
		Client client = mock(Client.class);
		Player me = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(me);
		WorldView view = mock(WorldView.class);
		// Just landed at the Monastery (about 50 tiles from the ring): the ring.
		when(me.getWorldLocation()).thenReturn(new WorldPoint(2606, 3222, 0));
		assertEquals(new WorldPoint(2658, 3230, 0), FarmRunSceneOverlay.furthestOnWay(client, view, way));
		// Through the ring, near the stop: the ring is far behind.
		when(me.getWorldLocation()).thenReturn(new WorldPoint(1720, 3480, 0));
		assertEquals(new WorldPoint(1700, 3500, 0), FarmRunSceneOverlay.furthestOnWay(client, view, way));
		// Far from every point: none.
		when(me.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
		assertNull(FarmRunSceneOverlay.furthestOnWay(client, view, way));
	}
}
