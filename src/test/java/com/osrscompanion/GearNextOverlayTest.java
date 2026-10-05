package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.osrscompanion.model.GearPlan;
import org.junit.Test;

public class GearNextOverlayTest {
	private static GearPlan plan(String tiers) {
		return new Gson().fromJson("{\"plan\":{\"title\":\"Main\",\"done\":1,\"total\":4,\"tiers\":" + tiers + "}}", GearPlan.Response.class).getPlan();
	}

	@Test
	public void findsTheNextStepWithItsOpenItems() {
		GearPlan p = plan("[{\"tier\":1,\"name\":\"A\",\"steps\":["
				+ "{\"step\":1,\"items\":[{\"id\":1,\"name\":\"Done one\",\"done\":true}]},"
				+ "{\"step\":2,\"items\":[{\"id\":9,\"name\":\"Hidden\",\"hidden\":true}]},"
				+ "{\"step\":3,\"items\":[{\"id\":2,\"name\":\"Rune pouch\",\"next\":true},{\"id\":3,\"name\":\"Gem bag\",\"next\":true},{\"id\":4,\"name\":\"Got it\",\"done\":true}]}]}]");
		GearNextOverlay.Next next = GearNextOverlay.findNext(p);
		assertEquals(1, next.tier);
		assertEquals("numbered like the window: the hidden-only step is skipped", 2, next.step);
		assertEquals(2, next.items.size());
		assertEquals("Gem bag", next.items.get(1).getName());
	}

	@Test
	public void nothingNextWhenEverythingIsDone() {
		GearPlan p = plan("[{\"tier\":1,\"name\":\"A\",\"steps\":[{\"step\":1,\"items\":[{\"id\":1,\"name\":\"X\",\"done\":true}]}]}]");
		assertNull(GearNextOverlay.findNext(p));
	}
}
