package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for BankMemoryMonitor config toggle behavior.
 */
public class BankMemoryMonitorTest {
	private TestOSRSCompanionConfig config;

	@Before
	public void setUp() {
		config = new TestOSRSCompanionConfig();
	}

	@Test
	public void testConfigSyncBankMemoryDefaultIsTrue() {
		assertEquals(true, config.syncBankMemory());
	}

	@Test
	public void testConfigSyncBankMemoryCanBeDisabled() {
		config.setSyncBankMemory(false);
		assertEquals(false, config.syncBankMemory());
	}

	@Test
	public void testConfigSyncBankMemoryCanBeReEnabled() {
		config.setSyncBankMemory(false);
		assertFalse(config.syncBankMemory());

		config.setSyncBankMemory(true);
		assertTrue(config.syncBankMemory());
	}

	@Test
	public void carriedIsInventoryPlusWornAndNeedsBoth() {
		Map<String, Map<Integer, Integer>> bySource = new HashMap<>();
		Map<Integer, Integer> inv = new HashMap<>();
		inv.put(1511, 5);
		inv.put(4151, 1);
		bySource.put(BankMemoryMonitor.SOURCE_INVENTORY, inv);
		assertEquals("no worn read: not known", null, BankMemoryMonitor.carried(bySource));
		Map<Integer, Integer> worn = new HashMap<>();
		worn.put(4151, 1);
		worn.put(20997, 1);
		bySource.put(BankMemoryMonitor.SOURCE_WORN, worn);
		Map<Integer, Integer> carried = BankMemoryMonitor.carried(bySource);
		assertEquals(Integer.valueOf(5), carried.get(1511));
		assertEquals(Integer.valueOf(2), carried.get(4151));
		assertEquals(Integer.valueOf(1), carried.get(20997));
		// The sources themselves stay apart.
		assertEquals(Integer.valueOf(1), bySource.get(BankMemoryMonitor.SOURCE_INVENTORY).get(4151));
	}

	@Test
	public void sourceNames() {
		assertEquals("inventory", BankMemoryMonitor.SOURCE_INVENTORY);
		assertEquals("worn", BankMemoryMonitor.SOURCE_WORN);
	}
}
