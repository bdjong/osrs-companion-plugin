package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.FarmRoute;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TeleportLeftClickTest {
	private static FarmRoute.Travel travel(String name, String how) {
		return new Gson().fromJson("{\"name\":\"" + name + "\",\"how\":\"" + how + "\"}", FarmRoute.Travel.class);
	}

	@Test
	public void farmingGuildIsNamedBeforeAGenericTeleportOption() {
		// The inventory Max cape's "Teleports" opens its list: only after its sub menu has no Farming Guild.
		FarmRoute.Travel guild = travel("Max cape (Farming Guild)", "Teleport to the Farming Guild.");
		assertEquals(Collections.singletonList("farming guild"), TeleportLeftClick.capeOptions(guild));
		assertEquals(Collections.singletonList("farming guild"), TeleportLeftClick.capeSubOptions(guild));
		assertEquals(Arrays.asList("other teleports", "teleport"), TeleportLeftClick.capeFallbackOptions(guild));
	}

	@Test
	public void homeHasNoGenericFallback() {
		FarmRoute.Travel home = travel("Max cape (Tele to POH)", "");
		assertEquals(Collections.singletonList("tele to poh"), TeleportLeftClick.capeOptions(home));
		assertEquals(Collections.singletonList("home"), TeleportLeftClick.capeSubOptions(home));
		assertEquals(Collections.emptyList(), TeleportLeftClick.capeFallbackOptions(home));
	}

	@Test
	public void jewelleryBoxWayTeleportsHomeFirst() {
		// The Slayer guide's "then the jewellery box": the cape's left click is Tele to POH, a house tab's Break.
		FarmRoute.Travel box = travel("Construction cape (Tele to POH), then the jewellery box (Amulet of glory)",
			"Use your Construction cape (Tele to POH), then use the Ornate jewellery box in your POH (Amulet of glory teleports). Edgeville, then the trapdoor south of the bank.");
		assertEquals(Collections.singletonList("tele to poh"), TeleportLeftClick.capeOptions(box));
		assertEquals(Collections.singletonList("home"), TeleportLeftClick.capeSubOptions(box));
		FarmRoute.Travel tab = travel("House teleport tab, then the jewellery box (Amulet of glory)", "Use your House teleport tab, then use the Ornate jewellery box in your POH.");
		assertEquals(Collections.emptyList(), TeleportLeftClick.capeOptions(tab));
		assertEquals(10, TeleportLeftClick.optionScore("break", "house teleport tab, then the jewellery box"));
	}
}
