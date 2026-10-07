package com.osrscompanion.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * The dailies guide as returned by GET /api/runelite/dailies/: every daily with the website's
 * state (timers, kingdom visit, ticks made on the website), the way there with your unlocks, the
 * NPC and option, what to bring and the steps.
 */
public class Dailies {
	@SerializedName("reset_at")
	private String resetAt;

	@SerializedName("dailies")
	private List<Daily> dailies;

	public String getResetAt() {
		return resetAt;
	}

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

		@SerializedName("icon")
		private int icon;

		@SerializedName("reward")
		private String reward;

		@SerializedName("amount")
		private Integer amount;

		/** GE value of the reward; null when it has none (XP, time saved). */
		@SerializedName("value")
		private Long value;

		/** The website's state: done, open or locked. */
		@SerializedName("state")
		private String state;

		@SerializedName("note")
		private String note;

		/** Where the website's state comes from: plugin, manual, timer, visit or requirements. */
		@SerializedName("source")
		private String source;

		@SerializedName("npc")
		private String npc;

		@SerializedName("option")
		private String option;

		@SerializedName("area")
		private String area;

		@SerializedName("x")
		private int x;

		@SerializedName("y")
		private int y;

		@SerializedName("plane")
		private int plane;

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

		public int getIcon() {
			return icon;
		}

		public String getReward() {
			return reward == null ? "" : reward;
		}

		public Integer getAmount() {
			return amount;
		}

		public Long getValue() {
			return value;
		}

		public String getState() {
			return state;
		}

		public String getNote() {
			return note;
		}

		public String getSource() {
			return source;
		}

		public String getNpc() {
			return npc;
		}

		public String getOption() {
			return option == null ? "" : option;
		}

		public String getArea() {
			return area == null ? "" : area;
		}

		public int getX() {
			return x;
		}

		public int getY() {
			return y;
		}

		public int getPlane() {
			return plane;
		}

		public FarmRoute.Travel getTravel() {
			return travel;
		}

		public List<Bring> getBring() {
			return bring == null ? Collections.emptyList() : bring;
		}

		public List<String> getSteps() {
			return steps == null ? Collections.emptyList() : steps;
		}
	}

	public static class Bring {
		@SerializedName("item")
		private int item;

		@SerializedName("qty")
		private int qty;

		@SerializedName("name")
		private String name;

		@SerializedName("text")
		private String text;

		public int getItem() {
			return item;
		}

		public int getQty() {
			return qty;
		}

		public String getName() {
			return name == null ? "Item" : name;
		}

		public String getText() {
			return text == null ? getName() : text;
		}
	}
}
