package com.osrscompanion.model;

import lombok.Getter;
import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * The dailies guide as returned by GET /api/runelite/dailies/: every daily with the website's
 * state (timers, kingdom visit, ticks made on the website), the way there with your unlocks, the
 * NPC and option, what to bring and the steps.
 */
public class Dailies {
	@Getter
	@SerializedName("reset_at")
	private String resetAt;

	@SerializedName("dailies")
	private List<Daily> dailies;

	public List<Daily> getDailies() {
		return dailies == null ? Collections.emptyList() : dailies;
	}

	public Daily find(String id) {
		for (Daily d : getDailies()) {
			if (d.getId().equals(id)) {
				return d;
			}
		}
		return null;
	}

	public static class Daily {
		@SerializedName("id")
		private String id;

		@SerializedName("name")
		private String name;

		@SerializedName("short")
		private String shortName;

		/** daily, weekly or timer. */
		@SerializedName("reset")
		private String reset;

		/** varbit (the plugin reads it), timer, visit or manual. */
		@SerializedName("tracking")
		private String tracking;

		@Getter
		@SerializedName("icon")
		private int icon;

		@SerializedName("reward")
		private String reward;

		@Getter
		@SerializedName("amount")
		private Integer amount;

		/** GE value of the reward; null when it has none (XP, time saved). */
		@Getter
		@SerializedName("value")
		private Long value;

		/** The website's state: done, open or locked. */
		@Getter
		@SerializedName("state")
		private String state;

		@Getter
		@SerializedName("note")
		private String note;

		/** Where the website's state comes from: plugin, manual, timer, visit or requirements. */
		@Getter
		@SerializedName("source")
		private String source;

		@Getter
		@SerializedName("npc")
		private String npc;

		@SerializedName("option")
		private String option;

		@SerializedName("area")
		private String area;

		@Getter
		@SerializedName("x")
		private int x;

		@Getter
		@SerializedName("y")
		private int y;

		@Getter
		@SerializedName("plane")
		private int plane;

		@Getter
		@SerializedName("travel")
		private FarmRoute.Travel travel;

		@SerializedName("bring")
		private List<Bring> bring;

		@SerializedName("steps")
		private List<String> steps;

		public String getId() {
			return id == null ? "" : id;
		}

		public String getName() {
			return name == null ? getId() : name;
		}

		public String getShortName() {
			return shortName == null ? getName() : shortName;
		}

		public String getReset() {
			return reset == null ? "daily" : reset;
		}

		public String getTracking() {
			return tracking == null ? "manual" : tracking;
		}

		public String getReward() {
			return reward == null ? "" : reward;
		}

		public String getOption() {
			return option == null ? "" : option;
		}

		public String getArea() {
			return area == null ? "" : area;
		}

		public List<Bring> getBring() {
			return bring == null ? Collections.emptyList() : bring;
		}

		public List<String> getSteps() {
			return steps == null ? Collections.emptyList() : steps;
		}
	}

	public static class Bring {
		@Getter
		@SerializedName("item")
		private int item;

		@Getter
		@SerializedName("qty")
		private int qty;

		@SerializedName("name")
		private String name;

		@SerializedName("text")
		private String text;

		public String getName() {
			return name == null ? "Item" : name;
		}

		public String getText() {
			return text == null ? getName() : text;
		}
	}
}
