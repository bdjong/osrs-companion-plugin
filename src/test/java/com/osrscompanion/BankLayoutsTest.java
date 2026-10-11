package com.osrscompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BankLayoutsTest {
	@Test
	public void inventoryFillsTheRightHalfFourByFour() {
		assertEquals(4, BankLayouts.inventoryPos(0));
		assertEquals(7, BankLayouts.inventoryPos(3));
		assertEquals(12, BankLayouts.inventoryPos(4));
		assertEquals(55, BankLayouts.inventoryPos(27));
		assertEquals(56, BankLayouts.inventoryPos(28));
	}

	@Test
	public void gearSitsInTheDollShapeWithPouchAndSpecialAroundIt() {
		Map<String, Integer> worn = new HashMap<>();
		worn.put("head", 100);
		worn.put("weapon", 200);
		worn.put("ring", 300);
		worn.put("2h", 999); // not a worn slot: left out
		List<Integer> inv = Arrays.asList(1, null, 3, -1, 5);
		int[] out = BankLayouts.build(worn, inv, Arrays.asList(554, 555, 556, 557, 558), 400);
		assertEquals(48, out.length);
		assertEquals(100, out[1]);
		assertEquals(400, out[2]);
		assertEquals(200, out[16]);
		assertEquals(300, out[34]);
		assertEquals(1, out[4]);
		assertEquals(-1, out[5]);
		assertEquals(3, out[6]);
		assertEquals(-1, out[7]);
		assertEquals(5, out[12]);
		assertEquals(554, out[40]);
		assertEquals(557, out[43]);
		assertEquals(-1, out[44]);
		for (int v : out) {
			if (v == 999) {
				throw new AssertionError("2h placed");
			}
		}
	}

	@Test
	public void alternateWeaponSitsNextToTheSpecialAttackWeapon() {
		Map<String, Integer> worn = new HashMap<>();
		worn.put("weapon", 20997);
		worn.put("switch", 12926);
		worn.put("switch_shield", 21000);
		int[] out = BankLayouts.build(worn, Collections.emptyList(), Collections.emptyList(), 26374);
		assertEquals(26374, out[BankLayouts.SPECIAL_POS]);
		assertEquals(12926, out[3]);
		assertEquals(21000, out[11]);
		assertEquals(20997, out[16]);
		assertEquals(-1, out[18]);
	}

	@Test
	public void trailingEmptyRowsAreTrimmedAndExtrasRunBelow() {
		int[] small = BankLayouts.build(Collections.singletonMap("head", 10), Collections.emptyList(), Collections.emptyList(), -1);
		assertEquals(8, small.length);
		Integer[] many = new Integer[30];
		Arrays.fill(many, 7);
		int[] big = BankLayouts.build(Collections.emptyMap(), Arrays.asList(many), Collections.emptyList(), -1);
		assertEquals(64, big.length);
		assertEquals(7, big[56]);
		assertEquals(7, big[57]);
	}

	@Test
	public void fullInventoryReachesTheLastInventoryRow() {
		// 28 items: slot 27 sits at position 55 (row 6, column 7), below the rune pouch row.
		List<Integer> inventory = new java.util.ArrayList<>(Collections.nCopies(28, 385));
		int[] out = BankLayouts.build(new HashMap<>(), inventory, Arrays.asList(554, 555, 556), -1);
		assertEquals(56, out.length);
		assertEquals(385, out[55]);
		assertEquals(554, out[BankLayouts.POUCH_START]);
	}
}
