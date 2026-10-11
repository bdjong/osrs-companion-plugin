package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class FarmRouteReadyTest {
	@Test
	public void readyOnceNoPatchIsGrowing() {
		assertFalse(FarmRouteReady.isReady(Arrays.asList(false, true, false), 0, 100));
		assertTrue(FarmRouteReady.isReady(Arrays.asList(false, false), 0, 100));
		// A patch that can't be read doesn't hold it back...
		assertTrue(FarmRouteReady.isReady(Arrays.asList(false, null), 0, 100));
		assertFalse(FarmRouteReady.isReady(Arrays.asList(true, null), 0, 100));
	}

	@Test
	public void noPatchReadableGoesByTheExpectedTime() {
		assertFalse(FarmRouteReady.isReady(Arrays.asList(null, null), 0, 100));
		assertFalse(FarmRouteReady.isReady(Collections.singletonList(null), 200, 100));
		assertTrue(FarmRouteReady.isReady(Collections.singletonList(null), 200, 200));
	}

	@Test
	public void message() {
		assertEquals("Farm route Herb run is ready again: all 8 patches are grown.", FarmRouteReady.message("Herb run", 8));
		assertEquals("Farm route is ready again: its patch is grown.", FarmRouteReady.message(null, 1));
	}

	@Test
	public void survivesSaving() {
		Gson gson = new Gson();
		FarmRouteReady run = new FarmRouteReady("Trees", Collections.singletonList(new FarmRouteReady.PatchRef("k", "Lumbridge", 4771, "TREE")), 1234);
		run.markNotified();
		FarmRouteReady back = gson.fromJson(gson.toJson(run), FarmRouteReady.class);
		assertEquals("Trees", back.getRoute());
		assertEquals(1, back.getPatches().size());
		assertEquals(4771, back.getPatches().get(0).getVarbit());
		assertEquals(1234, back.getReadyAt());
		assertTrue(back.isNotified());
	}
}
