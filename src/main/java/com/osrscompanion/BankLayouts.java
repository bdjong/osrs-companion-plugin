package com.osrscompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bank positions for a loadout, 8 items per bank row: the worn equipment in the game's doll shape on
 * the left (columns 0-2), the 28 inventory slots as the 4 x 7 inventory on the right (columns 4-7),
 * the rune pouch under the doll, and anything past 28 items below it all.
 *
 * <p>The positions follow the Inventory Setups plugin's "preset" bank layout (dillydill123/inventory-setups,
 * InventorySetupLayoutUtilities#getPresetLayout, BSD 2-Clause), so the bank looks the same either way.
 */
final class BankLayouts {
	private BankLayouts() {
	}

	static final int ROW = 8;
	/** Where the special attack weapon goes: right of the helmet, where the doll has no slot. */
	static final int SPECIAL_POS = 2;
	/** The rune pouch's runes: under the doll (row 5, columns 0-3). */
	static final int POUCH_START = 40;
	/** Items past the 28 inventory slots: from row 7 on. */
	static final int EXTRA_START = 56;

	static final Map<String, Integer> EQUIPMENT_POS;

	static {
		Map<String, Integer> m = new HashMap<>();
		m.put("head", 1);
		m.put("cape", 8);
		m.put("neck", 9);
		m.put("ammo", 10);
		m.put("weapon", 16);
		m.put("body", 17);
		m.put("shield", 18);
		m.put("legs", 25);
		m.put("hands", 32);
		m.put("feet", 33);
		m.put("ring", 34);
		EQUIPMENT_POS = Collections.unmodifiableMap(m);
	}

	/** Inventory slot i (0-27) in the 4 x 7 grid on the right; later items run on below. */
	static int inventoryPos(int i) {
		return i < 28 ? (i / 4) * ROW + 4 + i % 4 : EXTRA_START + (i - 28);
	}

	/**
	 * The layout as bank positions holding item ids (-1: empty).
	 *
	 * @param worn      item per worn slot name (head, cape, ...); unknown slots are left out
	 * @param inventory item per inventory slot in order; null or -1 = empty slot
	 * @param pouch     the rune pouch's runes (up to 4 used)
	 * @param special   the special attack weapon, or -1
	 */
	static int[] build(Map<String, Integer> worn, List<Integer> inventory, List<Integer> pouch, int special) {
		int last = POUCH_START + 3;
		if (inventory.size() > 28) {
			last = Math.max(last, inventoryPos(inventory.size() - 1));
		}
		int size = (last / ROW + 1) * ROW;
		int[] out = new int[size];
		Arrays.fill(out, -1);
		for (Map.Entry<String, Integer> e : worn.entrySet()) {
			Integer pos = EQUIPMENT_POS.get(e.getKey());
			if (pos != null && e.getValue() != null && e.getValue() > 0) {
				out[pos] = e.getValue();
			}
		}
		if (special > 0) {
			out[SPECIAL_POS] = special;
		}
		for (int i = 0; i < inventory.size(); i++) {
			Integer id = inventory.get(i);
			if (id != null && id > 0) {
				out[inventoryPos(i)] = id;
			}
		}
		for (int i = 0; i < pouch.size() && i < 4; i++) {
			Integer id = pouch.get(i);
			if (id != null && id > 0) {
				out[POUCH_START + i] = id;
			}
		}
		// No empty rows at the end.
		int end = out.length;
		while (end > 0 && out[end - 1] == -1) {
			end--;
		}
		return Arrays.copyOf(out, Math.max(ROW, (end + ROW - 1) / ROW * ROW));
	}
}
