package com.osrscompanion.model;

import lombok.Getter;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The player's gear progression, as returned by GET /api/runelite/gear/: tiers of
 * steps, where the items of one step are on the same level (any order).
 */
public class GearPlan {
	@SerializedName("title")
	private String title;

	@Getter
	@SerializedName("done")
	private int done;

	@Getter
	@SerializedName("total")
	private int total;

	/** Tier holding the next open step; null when everything is done. */
	@Getter
	@SerializedName("next_tier")
	private Integer nextTier;

	@SerializedName("tiers")
	private List<Tier> tiers;

	public String getTitle() {
		return title == null ? "Gear progression" : title;
	}

	public List<Tier> getTiers() {
		return tiers == null ? Collections.emptyList() : tiers;
	}

	/** The item with this id, or null. */
	public Item findItem(int id) {
		for (Tier t : getTiers()) {
			for (Step s : t.getSteps()) {
				for (Item i : s.getItems()) {
					if (i.getId() == id) {
						return i;
					}
				}
			}
		}
		return null;
	}

	public static class Tier {
		@Getter
		@SerializedName("tier")
		private int tier;

		@SerializedName("name")
		private String name;

		@Getter
		@SerializedName("done")
		private int done;

		@Getter
		@SerializedName("total")
		private int total;

		@SerializedName("steps")
		private List<Step> steps;

		public String getName() {
			return name == null ? "" : name;
		}

		public List<Step> getSteps() {
			return steps == null ? Collections.emptyList() : steps;
		}

		/** The steps that still show something once hidden items are left out. */
		public List<Step> getVisibleSteps() {
			List<Step> visible = new ArrayList<>();
			for (Step s : getSteps()) {
				if (!s.getVisibleItems().isEmpty()) {
					visible.add(s);
				}
			}
			return visible;
		}
	}

	public static class Step {
		@Getter
		@SerializedName("step")
		private int step;

		@SerializedName("items")
		private List<Item> items;

		public List<Item> getItems() {
			return items == null ? Collections.emptyList() : items;
		}

		/** The items the player has not hidden. */
		public List<Item> getVisibleItems() {
			List<Item> visible = new ArrayList<>();
			for (Item i : getItems()) {
				if (!i.isHidden()) {
					visible.add(i);
				}
			}
			return visible;
		}

		/** True when every visible item of the step is done. */
		public boolean isDone() {
			for (Item i : getVisibleItems()) {
				if (!i.isDone()) {
					return false;
				}
			}
			return true;
		}

		/** True when this is the next open step. */
		public boolean isNext() {
			for (Item i : getVisibleItems()) {
				if (i.isNext()) {
					return true;
				}
			}
			return false;
		}
	}

	public static class Item {
		@Getter
		@SerializedName("id")
		private int id;

		@SerializedName("name")
		private String name;

		@SerializedName("kind")
		private String kind;

		/** OSRS item id for item steps (drawn with the game's own sprite); null otherwise. */
		@Getter
		@SerializedName("item_id")
		private Integer itemId;

		/** OSRS Wiki image, used for steps that are not items. */
		@Getter
		@SerializedName("image_url")
		private String imageUrl;

		@Getter
		@SerializedName("done")
		private boolean done;

		/** Done because synced data shows it (bank, gear, levels...). */
		@Getter
		@SerializedName("auto")
		private boolean auto;

		/** Done because the player ticked it. */
		@Getter
		@SerializedName("manual")
		private boolean manual;

		@Getter
		@SerializedName("next")
		private boolean next;

		/** Hidden by the player: not shown in game and not counted. */
		@Getter
		@SerializedName("hidden")
		private boolean hidden;

		@SerializedName("how")
		private String how;

		public String getName() {
			return name == null ? "" : name;
		}

		/** "item", "prayer", "spell", "skill", "slayer" or "poh". */
		public String getKind() {
			return kind == null ? "" : kind;
		}

		public String getHow() {
			return how == null ? "" : how;
		}
	}

	/**
	 * Response wrapper: { "plan": …, "plans": […] }. plan is the active roadmap (null until one
	 * is created on the website); plans lists every roadmap of the character to switch between.
	 */
	public static class Response {
		@Getter
		@SerializedName("plan")
		private GearPlan plan;

		@SerializedName("plans")
		private List<Summary> plans;

		public List<Summary> getPlans() {
			return plans == null ? Collections.emptyList() : plans;
		}
	}

	/** One of the character's roadmaps, for the switcher. */
	public static class Summary {
		@Getter
		@SerializedName("id")
		private int id;

		@SerializedName("name")
		private String name;

		@Getter
		@SerializedName("active")
		private boolean active;

		public String getName() {
			return name == null ? "" : name;
		}

	}

	/** Response of the set-as-goal call. */
	public static class GoalResult {
		@Getter
		@SerializedName("ok")
		private boolean ok;

		@SerializedName("message")
		private String message;

		public String getMessage() {
			return message == null ? "" : message;
		}
	}
}
