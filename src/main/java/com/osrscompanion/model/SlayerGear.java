package com.osrscompanion.model;

import lombok.Getter;
import com.google.gson.annotations.SerializedName;
import java.util.*;

/**
 * GET /api/runelite/slayer/gear/: the website's Gear &amp; DPS calculator's best owned setup per combat
 * style (and the burst / barrage one on a burst task) against the current task's monster, best first.
 */
public class SlayerGear {
	@Getter
	@SerializedName("task")
	private String task;

	@Getter
	@SerializedName("monster")
	private String monster;

	@SerializedName("sets")
	private List<Set> sets;

	public List<Set> getSets() {
		return sets == null ? Collections.emptyList() : sets;
	}

	/** One setup: its style, numbers, worn items and what else it counts on (potion, prayer, spell, darts). */
	public static class Set {
		@Getter
		@SerializedName("key")
		private String key;

		@SerializedName("label")
		private String label;

		@Getter
		@SerializedName("max_hit")
		private int maxHit;

		@Getter
		@SerializedName("dps")
		private double dps;

		@Getter
		@SerializedName("kills_per_hour")
		private double killsPerHour;

		@Getter
		@SerializedName("spell")
		private String spell;

		@SerializedName("equipment")
		private List<Piece> equipment;

		@SerializedName("extras")
		private List<Extra> extras;

		/** The ammo / spell options, best first: the setup counts on the first, the bank shows a few. */
		@Getter
		@SerializedName("supplies")
		private Supplies supplies;

		/** The boost the numbers count on (potion, heart) and what recharges the charged items. */
		@SerializedName("bring")
		private List<Bring> bring;

		public String getLabel() {
			return label == null ? "Setup" : label;
		}

		public List<Piece> getEquipment() {
			return equipment == null ? Collections.emptyList() : equipment;
		}

		public List<Extra> getExtras() {
			return extras == null ? Collections.emptyList() : extras;
		}

		public List<Bring> getBring() {
			return bring == null ? Collections.emptyList() : bring;
		}

		/** The items of the first {@code n} ammo / spell options (runes once each), best first. */
		public List<Integer> supplyItems(int n) {
			java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
			if (supplies != null) {
				for (Supplies.Option o : supplies.getOptions().subList(0, Math.min(Math.max(0, n), supplies.getOptions().size()))) {
					out.addAll(o.getItems());
				}
			}
			return new java.util.ArrayList<>(out);
		}

		/** "Dragon dart > Amethyst dart > Rune dart": the first {@code n} options, or null when there are none. */
		public String supplyText(int n) {
			if (supplies == null || supplies.getOptions().isEmpty() || n <= 0) {
				return null;
			}
			StringBuilder b = new StringBuilder();
			for (Supplies.Option o : supplies.getOptions().subList(0, Math.min(n, supplies.getOptions().size()))) {
				b.append(b.length() == 0 ? "" : " > ").append(o.getName());
			}
			return b.toString();
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
		@Getter
		@SerializedName("slot")
		private String slot;

		@Getter
		@SerializedName("item")
		private int item;

		@SerializedName("name")
		private String name;

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

	/** Ammo or spell options: kind "darts", "ammo" or "spell" (its runes), best first. */
	/** One thing to take along: a boost or a charge material, as candidate items (the bank shows the first it has). */
	public static class Bring {
		@SerializedName("kind")
		private String kind;

		@SerializedName("name")
		private String name;

		@SerializedName("items")
		private List<Integer> items;

		public String getKind() {
			return kind == null ? "" : kind;
		}

		public String getName() {
			return name == null ? "" : name;
		}

		public List<Integer> getItems() {
			return items == null ? Collections.emptyList() : items;
		}
	}

	public static class Supplies {
		@SerializedName("kind")
		private String kind;

		@SerializedName("options")
		private List<Option> options;

		public String getKind() {
			return kind == null ? "" : kind;
		}

		public List<Option> getOptions() {
			return options == null ? Collections.emptyList() : options;
		}

		public static class Option {
			@SerializedName("name")
			private String name;

			@SerializedName("items")
			private List<Integer> items;

			public String getName() {
				return name == null ? "" : name;
			}

			public List<Integer> getItems() {
				return items == null ? Collections.emptyList() : items;
			}
		}
	}
}
