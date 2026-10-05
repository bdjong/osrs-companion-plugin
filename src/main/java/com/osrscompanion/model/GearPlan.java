package com.osrscompanion.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * The player's gear progression, as returned by GET /api/runelite/gear/: tiers of
 * steps, where the items of one step are on the same level (any order).
 */
public class GearPlan {
	@SerializedName("title")
	private String title;

	@SerializedName("done")
	private int done;

	@SerializedName("total")
	private int total;

	@SerializedName("next_tier")
	private Integer nextTier;

	@SerializedName("tiers")
	private List<Tier> tiers;

	public String getTitle() {
		return title == null ? "Gear progression" : title;
	}

	public int getDone() {
		return done;
	}

	public int getTotal() {
		return total;
	}

	/** Tier holding the next open step; null when everything is done. */
	public Integer getNextTier() {
		return nextTier;
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
		@SerializedName("tier")
		private int tier;

		@SerializedName("name")
		private String name;

		@SerializedName("done")
		private int done;

		@SerializedName("total")
		private int total;

		@SerializedName("steps")
		private List<Step> steps;

		public int getTier() {
			return tier;
		}

		public String getName() {
			return name == null ? "" : name;
		}

		public int getDone() {
			return done;
		}

		public int getTotal() {
			return total;
		}

		public List<Step> getSteps() {
			return steps == null ? Collections.emptyList() : steps;
		}
	}

	public static class Step {
		@SerializedName("step")
		private int step;

		@SerializedName("items")
		private List<Item> items;

		public int getStep() {
			return step;
		}

		public List<Item> getItems() {
			return items == null ? Collections.emptyList() : items;
		}

		/** True when every item of the step is done. */
		public boolean isDone() {
			for (Item i : getItems()) {
				if (!i.isDone()) {
					return false;
				}
			}
			return true;
		}

		/** True when this is the next open step. */
		public boolean isNext() {
			for (Item i : getItems()) {
				if (i.isNext()) {
					return true;
				}
			}
			return false;
		}
	}

	public static class Item {
		@SerializedName("id")
		private int id;

		@SerializedName("name")
		private String name;

		@SerializedName("kind")
		private String kind;

		@SerializedName("item_id")
		private Integer itemId;

		@SerializedName("image_url")
		private String imageUrl;

		@SerializedName("done")
		private boolean done;

		@SerializedName("auto")
		private boolean auto;

		@SerializedName("manual")
		private boolean manual;

		@SerializedName("next")
		private boolean next;

		@SerializedName("how")
		private String how;

		public int getId() {
			return id;
		}

		public String getName() {
			return name == null ? "" : name;
		}

		/** "item", "prayer", "spell", "skill", "slayer" or "poh". */
		public String getKind() {
			return kind == null ? "" : kind;
		}

		/** OSRS item id for item steps (drawn with the game's own sprite); null otherwise. */
		public Integer getItemId() {
			return itemId;
		}

		/** OSRS Wiki image, used for steps that are not items. */
		public String getImageUrl() {
			return imageUrl;
		}

		public boolean isDone() {
			return done;
		}

		/** Done because synced data shows it (bank, gear, levels...). */
		public boolean isAuto() {
			return auto;
		}

		/** Done because the player ticked it. */
		public boolean isManual() {
			return manual;
		}

		public boolean isNext() {
			return next;
		}

		public String getHow() {
			return how == null ? "" : how;
		}
	}

	/** Response wrapper: { "plan": … } where plan is null until loaded on the website. */
	public static class Response {
		@SerializedName("plan")
		private GearPlan plan;

		public GearPlan getPlan() {
			return plan;
		}
	}

	/** Response of the set-as-goal call. */
	public static class GoalResult {
		@SerializedName("ok")
		private boolean ok;

		@SerializedName("message")
		private String message;

		public boolean isOk() {
			return ok;
		}

		public String getMessage() {
			return message == null ? "" : message;
		}
	}
}
