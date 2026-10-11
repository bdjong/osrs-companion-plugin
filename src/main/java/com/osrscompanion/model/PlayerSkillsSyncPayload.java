package com.osrscompanion.model;

import lombok.Getter;
import com.google.gson.Gson;
import java.util.Map;

/**
 * Payload for syncing player skills to the OSRS Companion API.
 * Matches the format expected by POST /api/runelite/skills/
 */
public class PlayerSkillsSyncPayload {
	@Getter
	private final String player;
	@Getter
	private final Map<String, SkillData> skills;
	private final int total_level;
	private final int combat_level;
	/**
	 * The account type: the value of varbit IRONMAN (1777): 1 ironman, 2 ultimate, 3 hardcore, 4 group,
	 * 5 hardcore group, 6 unranked group. Null (left out of the JSON) for a normal account or when not read;
	 * the website uses it for the Group Ironman helmet.
	 */
	private final Integer account_type;

	public PlayerSkillsSyncPayload(String player, Map<String, SkillData> skills, int totalLevel, int combatLevel) {
		this(player, skills, totalLevel, combatLevel, null);
	}

	public PlayerSkillsSyncPayload(String player, Map<String, SkillData> skills, int totalLevel, int combatLevel, Integer accountType) {
		this.player = player;
		this.skills = skills;
		this.total_level = totalLevel;
		this.combat_level = combatLevel;
		this.account_type = accountType;
	}

	/** The varbit value to send: only a known iron type (1-6); 0 is a normal account or a varbit not loaded yet. */
	public static Integer accountTypeOf(int varbitValue) {
		return varbitValue >= 1 && varbitValue <= 6 ? varbitValue : null;
	}

	public int getTotalLevel() {
		return total_level;
	}

	public int getCombatLevel() {
		return combat_level;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}

	/**
	 * Represents skill level and experience data
	 */
	public static class SkillData {
		@Getter
		private final int level;
		private final int current_xp;

		public SkillData(int level, int currentXp) {
			this.level = level;
			this.current_xp = currentXp;
		}

		public int getCurrentXp() {
			return current_xp;
		}
	}
}
