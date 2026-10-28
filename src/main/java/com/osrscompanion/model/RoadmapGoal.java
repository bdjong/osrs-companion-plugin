package com.osrscompanion.model;

import lombok.Getter;
import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * A single goal within a roadmap, as returned by the roadmap detail/recompute
 * endpoints. Only the fields the plugin renders are mapped.
 */
public class RoadmapGoal {
	@Getter
	@SerializedName("id")
	private int id;

	@Getter
	@SerializedName("goal_type")
	private String goalType;

	@Getter
	@SerializedName("sort_order")
	private int sortOrder;

	@Getter
	@SerializedName("name")
	private String name;

	/** Artwork the server links for this goal (a wiki image), or null. */
	@Getter
	@SerializedName("image_url")
	private String imageUrl;

	@Getter
	@SerializedName("current")
	private int current;

	@Getter
	@SerializedName("target")
	private int target;

	@Getter
	@SerializedName("progress_percent")
	private Integer progressPercent;

	@Getter
	@SerializedName("is_complete")
	private boolean complete;

	@SerializedName("completed_at")
	private String completedAt;



	@SerializedName("meta")
	private Meta meta;

	/** OSRS item id for an icon, when this goal targets an item (else null).
	 *
	 * <p>Item/drop/clog goals expose it as {@code item_id}; farming goals carry it
	 * as {@code osrs_item_id} in their params, so fall back to that. */
	public Integer getItemId() {
		if (meta == null) {
			return null;
		}
		return meta.itemId != null ? meta.itemId : meta.osrsItemId;
	}

	/** Skill name (e.g. "Slayer") for a skill goal, used to pick its icon; null
	 * otherwise. Only skill-level / skill-xp goals carry it. */
	public String getSkillName() {
		return meta == null ? null : meta.skill;
	}

	/** OSRS NPC id for a kill-count goal; null for every other type. */
	public Integer getNpcId() {
		return meta == null ? null : meta.npcId;
	}

	/** The canonical NPC name the server matches kills against for a
	 * kill-count goal (older servers omit it); null otherwise. */
	public String getNpcName() {
		return meta == null ? null : meta.npcName;
	}

	/**
	 * For an unfinished skill goal: the skill's XP that completes it (the XP when it was added plus the XP it needs;
	 * current and target are XP gained and needed). Null otherwise, or for goals from before start_xp was kept.
	 */
	public Long getCompletingXp() {
		return complete || !getType().isSkill() || meta == null || meta.startXp == null ? null : (long) meta.startXp + target;
	}

	/** Diary task id (OSRS Companion pk) or combat achievement in-game task id; null otherwise. */
	public Integer getTaskId() {
		return meta == null ? null : meta.taskId;
	}

	/** Catalog metadata block; only the fields the plugin needs are mapped. */
	private static class Meta {
		@SerializedName("item_id")
		private Integer itemId;

		@SerializedName("osrs_item_id")
		private Integer osrsItemId;

		@SerializedName("skill")
		private String skill;

		@SerializedName("npc_id")
		private Integer npcId;

		@SerializedName("npc_name")
		private String npcName;

		@SerializedName("task_id")
		private Integer taskId;

		@SerializedName("start_xp")
		private Integer startXp;
	}

	/** The parsed goal type; {@link GoalType#UNKNOWN} for unrecognised values. */
	public GoalType getType() {
		return GoalType.fromWire(goalType);
	}

	/**
	 * When the server recorded this goal complete (ISO-8601 on the wire), or null
	 * when the goal is incomplete, the field is absent, or the value cannot be
	 * parsed. Never throws.
	 */
	public Instant getCompletedAt() {
		return parseInstant(completedAt);
	}

	private static Instant parseInstant(String iso) {
		if (iso == null || iso.isEmpty()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(iso).toInstant();
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/**
	 * Human-readable progress suffix for measurable goals (e.g. "63%"), or empty
	 * when the goal is binary (no percent).
	 */
	public String progressLabel() {
		if (progressPercent == null) {
			return "";
		}
		return progressPercent + "%";
	}
}
