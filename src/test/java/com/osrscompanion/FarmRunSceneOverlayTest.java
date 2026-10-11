package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.FarmRoute;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
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
}
