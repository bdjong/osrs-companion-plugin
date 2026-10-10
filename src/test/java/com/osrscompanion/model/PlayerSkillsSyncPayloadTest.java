package com.osrscompanion.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class PlayerSkillsSyncPayloadTest {
	@Test
	public void testPayloadCreation() {
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		skills.put("Attack", new PlayerSkillsSyncPayload.SkillData(75, 1200000));
		skills.put("Defence", new PlayerSkillsSyncPayload.SkillData(70, 800000));
		skills.put("Strength", new PlayerSkillsSyncPayload.SkillData(80, 2000000));

		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("TestPlayer", skills, 225, 95);

		assertEquals("TestPlayer", payload.getPlayer());
		assertEquals(3, payload.getSkills().size());
		assertEquals(225, payload.getTotalLevel());
		assertEquals(95, payload.getCombatLevel());
		assertEquals(75, payload.getSkills().get("Attack").getLevel());
		assertEquals(1200000, payload.getSkills().get("Attack").getCurrentXp());
	}

	@Test
	public void testJsonSerialization() {
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		skills.put("Attack", new PlayerSkillsSyncPayload.SkillData(75, 1200000));
		skills.put("Defence", new PlayerSkillsSyncPayload.SkillData(70, 800000));

		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("TestPlayer", skills, 145, 85);
		Gson gson = new Gson();
		String json = payload.toJson(gson);

		assertNotNull(json);
		assertTrue(json.contains("TestPlayer"));
		assertTrue(json.contains("Attack"));
		assertTrue(json.contains("Defence"));
		assertTrue(json.contains("total_level"));
		assertTrue(json.contains("combat_level"));
		assertTrue(json.contains("current_xp"));
		JsonObject jsonObject = gson.fromJson(json, JsonObject.class);
		assertEquals("TestPlayer", jsonObject.get("player").getAsString());
		assertEquals(145, jsonObject.get("total_level").getAsInt());
		assertEquals(85, jsonObject.get("combat_level").getAsInt());
		assertTrue(jsonObject.has("skills"));
		JsonObject skillsObject = jsonObject.getAsJsonObject("skills");
		JsonObject attackSkill = skillsObject.getAsJsonObject("Attack");
		assertEquals(75, attackSkill.get("level").getAsInt());
		assertEquals(1200000, attackSkill.get("current_xp").getAsInt());
	}

	@Test
	public void testEmptySkills() {
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("EmptyPlayer", skills, 0, 3);

		assertEquals("EmptyPlayer", payload.getPlayer());
		assertTrue(payload.getSkills().isEmpty());
		assertEquals(0, payload.getTotalLevel());
		assertEquals(3, payload.getCombatLevel());
	}

	@Test
	public void testSkillDataCreation() {
		PlayerSkillsSyncPayload.SkillData skillData = new PlayerSkillsSyncPayload.SkillData(99, 13034431);

		assertEquals(99, skillData.getLevel());
		assertEquals(13034431, skillData.getCurrentXp());
	}

	@Test
	public void accountTypeFromTheIronmanVarbit() {
		assertNull(PlayerSkillsSyncPayload.accountTypeOf(0)); // normal, or not loaded yet
		for (int v = 1; v <= 6; v++) {
			assertEquals(Integer.valueOf(v), PlayerSkillsSyncPayload.accountTypeOf(v));
		}
		assertNull(PlayerSkillsSyncPayload.accountTypeOf(7));
		assertNull(PlayerSkillsSyncPayload.accountTypeOf(-1));
	}

	@Test
	public void accountTypeIsSentOnlyWhenKnown() {
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		Gson gson = new Gson();
		JsonObject gim = gson.fromJson(new PlayerSkillsSyncPayload("Gim", skills, 32, 3, 5).toJson(gson), JsonObject.class);
		assertEquals(5, gim.get("account_type").getAsInt());
		JsonObject normal = gson.fromJson(new PlayerSkillsSyncPayload("Main", skills, 32, 3).toJson(gson), JsonObject.class);
		assertFalse(normal.has("account_type"));
	}
}
