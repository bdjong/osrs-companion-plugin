package com.osrscompanion.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * GET /api/runelite/slayer/gear/: the website's Gear &amp; DPS calculator's best owned setup per combat
 * style (and the burst / barrage one on a burst task) against the current task's monster, best first.
 */
public class SlayerGear {
	@SerializedName("task")
	private String task;

	@SerializedName("monster")
	private String monster;

	@SerializedName("sets")
	private List<Set> sets;

	public String getTask() {
		return task;
	}

	public String getMonster() {
		return monster;
	}

	public List<Set> getSets() {
		return sets == null ? Collections.emptyList() : sets;
	}

	/** One setup: its style, numbers, worn items and what else it counts on (potion, prayer, spell, darts). */
	public static class Set {
		@SerializedName("key")
		private String key;

		@SerializedName("label")
		private String label;

		@SerializedName("max_hit")
		private int maxHit;

		@SerializedName("dps")
		private double dps;

		@SerializedName("kills_per_hour")
		private double killsPerHour;

		@SerializedName("spell")
		private String spell;

		@SerializedName("equipment")
		private List<Piece> equipment;

		@SerializedName("extras")
		private List<Extra> extras;

		public String getKey() {
			return key;
		}

		public String getLabel() {
			return label == null ? "Setup" : label;
		}

		public int getMaxHit() {
			return maxHit;
		}

		public double getDps() {
			return dps;
		}

		public double getKillsPerHour() {
			return killsPerHour;
		}

		public String getSpell() {
			return spell;
		}

		public List<Piece> getEquipment() {
			return equipment == null ? Collections.emptyList() : equipment;
		}

		public List<Extra> getExtras() {
			return extras == null ? Collections.emptyList() : extras;
		}

		/** The item for a worn slot (head, cape, ..., weapon), or null. */
		public Piece slot(String name) {
			for (Piece p : getEquipment()) {
				if (name.equals(p.slot)) {
					return p;
				}
			}
			return null;
		}
	}

	public static class Piece {
		@SerializedName("slot")
		private String slot;

		@SerializedName("item")
		private int item;

		@SerializedName("name")
		private String name;

		public String getSlot() {
			return slot;
		}

		public int getItem() {
			return item;
		}

		public String getName() {
			return name == null ? "Item" : name;
		}
	}

	public static class Extra {
		@SerializedName("label")
		private String label;

		@SerializedName("name")
		private String name;

		public String getLabel() {
			return label == null ? "" : label;
		}

		public String getName() {
			return name == null ? "" : name;
		}
	}
}
