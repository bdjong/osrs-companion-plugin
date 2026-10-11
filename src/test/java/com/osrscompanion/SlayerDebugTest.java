package com.osrscompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SlayerDebugTest {
	@Test
	public void onlyForTheDevelopersCharacters() {
		assertTrue(SlayerHelperManager.debugAllowed("Zeekr"));
		assertTrue(SlayerHelperManager.debugAllowed("iron zeekr 2"));
		assertFalse(SlayerHelperManager.debugAllowed("Zezima"));
		assertFalse(SlayerHelperManager.debugAllowed(null));
	}
}
