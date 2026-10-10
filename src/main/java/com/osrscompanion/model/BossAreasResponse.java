package com.osrscompanion.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/runelite/boss-areas/?version=: the website's boss lairs (src/lib/bossAreas.ts with the admins'
 * fixes), per boss key its map regions (template regions inside instances). When the plugin's version is
 * still current the answer is just {@code { version, unchanged: true }}.
 */
public class BossAreasResponse {
	@SerializedName("version")
	private String version;

	@SerializedName("unchanged")
	private boolean unchanged;

	@SerializedName("bosses")
	private List<Boss> bosses;

	public static class Boss {
		@SerializedName("key")
		private String key;

		@SerializedName("regions")
		private List<Integer> regions;

		public String getKey() {
			return key;
		}

		public List<Integer> getRegions() {
			return regions == null ? Collections.emptyList() : regions;
		}
	}

	public String getVersion() {
		return version;
	}

	public boolean isUnchanged() {
		return unchanged;
	}

	/** Boss key -> regions, in the website's order. */
	public Map<String, List<Integer>> lairs() {
		Map<String, List<Integer>> out = new LinkedHashMap<>();
		if (bosses != null) {
			for (Boss b : bosses) {
				if (b != null && b.getKey() != null) {
					out.put(b.getKey(), b.getRegions());
				}
			}
		}
		return out;
	}
}
