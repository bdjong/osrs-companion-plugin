package com.osrscompanion;

import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * What the tool leprechaun is storing, from the farming-tools varbits (readable
 * anywhere while logged in). Tools count 1 when stored; compost counts are the
 * stored amount as far as the varbits tell (the "extra" varbits only add more).
 */
final class ToolLeprechaun {
	private ToolLeprechaun() {
	}

	/** Item id to quantity stored. Client thread. */
	static Map<Integer, Integer> read(Client client) {
		Map<Integer, Integer> out = new HashMap<>();
		put(out, ItemID.RAKE, client.getVarbitValue(VarbitID.FARMING_TOOLS_RAKE) + client.getVarbitValue(VarbitID.FARMING_TOOLS_EXTRARAKES));
		put(out, ItemID.DIBBER, client.getVarbitValue(VarbitID.FARMING_TOOLS_DIBBER) + client.getVarbitValue(VarbitID.FARMING_TOOLS_EXTRADIBBERS));
		put(out, ItemID.SPADE, client.getVarbitValue(VarbitID.FARMING_TOOLS_SPADE) + client.getVarbitValue(VarbitID.FARMING_TOOLS_EXTRASPADES));
		put(out, ItemID.GARDENING_TROWEL, client.getVarbitValue(VarbitID.FARMING_TOOLS_TROWEL) + client.getVarbitValue(VarbitID.FARMING_TOOLS_EXTRATROWELS));
		int secateurs = client.getVarbitValue(VarbitID.FARMING_TOOLS_SECATEURS) + client.getVarbitValue(VarbitID.FARMING_TOOLS_EXTRASECATEURS);
		// The stored secateurs are the magic ones when this flag is set.
		put(out, client.getVarbitValue(VarbitID.FARMING_TOOLS_FAIRYSECATEURS) > 0 ? ItemID.FAIRY_ENCHANTED_SECATEURS : ItemID.SECATEURS, secateurs);
		put(out, ItemID.BUCKET_COMPOST, client.getVarbitValue(VarbitID.FARMING_TOOLS_COMPOST) + client.getVarbitValue(VarbitID.FARMING_TOOLS_EXTRACOMPOST));
		put(out, ItemID.BUCKET_SUPERCOMPOST, client.getVarbitValue(VarbitID.FARMING_TOOLS_SUPERCOMPOST) + client.getVarbitValue(VarbitID.FARMING_TOOLS_EXTRASUPERCOMPOST));
		put(out, ItemID.BUCKET_ULTRACOMPOST, client.getVarbitValue(VarbitID.FARMING_TOOLS_ULTRACOMPOST));
		put(out, ItemID.PLANT_CURE, client.getVarbitValue(VarbitID.FARMING_TOOLS_PLANTCURE));
		if (client.getVarbitValue(VarbitID.FARMING_TOOLS_BOTTOMLESS_BUCKET_TYPE) > 0) {
			// Either bucket id (empty or filled) counts as the stored bucket.
			out.put(ItemID.BOTTOMLESS_COMPOST_BUCKET, 1);
			out.put(ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED, 1);
		}
		return out;
	}

	private static void put(Map<Integer, Integer> out, int itemId, int qty) {
		if (qty > 0) {
			out.put(itemId, qty);
		}
	}
}
