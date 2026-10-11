package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.FarmRoute;

/** Example website data for the UI golden tests. */
final class UiSamples {
	private UiSamples() {
	}

	static final Gson GSON = new Gson();

	/** A three-stop herb and tree run, like the website builds. */
	static final String ROUTE_JSON = "{\"id\":7,\"name\":\"Herbs + trees\",\"summary\":{\"patches\":6,\"xp\":24500,\"activeSec\":540,\"gp\":180000},"
		+ "\"stops\":["
		+ "{\"key\":\"falador\",\"name\":\"Falador (south of the city)\",\"x\":3058,\"y\":3307,\"plane\":0,"
		+ " \"travel\":{\"name\":\"Explorer's ring 4\",\"how\":\"Operate it and choose Teleport; lands next to the cabbage patch.\",\"items\":[13128]},"
		+ " \"patches\":["
		+ "  {\"key\":\"falador-herb\",\"type\":\"Herb\",\"region\":\"Falador\",\"varbit\":4774,\"impl\":\"HERB\",\"crop\":\"Ranarr\",\"seed\":5295,\"seed_name\":\"Ranarr seed\",\"seed_qty\":1,\"protect\":[]},"
		+ "  {\"key\":\"falador-flower\",\"type\":\"Flower\",\"region\":\"Falador\",\"varbit\":4773,\"impl\":\"FLOWER\",\"crop\":\"Limpwurt\",\"seed\":5100,\"seed_name\":\"Limpwurt seed\",\"seed_qty\":1,\"protect\":[]},"
		+ "  {\"key\":\"falador-allotment\",\"type\":\"Allotment\",\"region\":\"Falador\",\"varbit\":4771,\"impl\":\"ALLOTMENT\",\"crop\":\"Snape grass\",\"seed\":22879,\"seed_name\":\"Snape grass seed\",\"seed_qty\":3,"
		+ "   \"protect\":[{\"item\":5986,\"qty\":10,\"name\":\"Sweetcorn\"}]}"
		+ " ]},"
		+ "{\"key\":\"gnome\",\"name\":\"Tree Gnome Stronghold\",\"x\":2436,\"y\":3415,\"plane\":0,"
		+ " \"travel\":{\"name\":\"Spirit tree\",\"how\":\"Any spirit tree to the Gnome Stronghold.\",\"items\":[],\"walk\":true,"
		+ "  \"fallback\":{\"name\":\"Royal seed pod\",\"how\":\"Commune to the Grand Tree.\",\"items\":[19564]}},"
		+ " \"note\":\"Pay Bolongo for the fruit tree too.\","
		+ " \"patches\":["
		+ "  {\"key\":\"gnome-tree\",\"type\":\"Tree\",\"region\":\"Tree Gnome Stronghold\",\"varbit\":4771,\"impl\":\"TREE\",\"crop\":\"Magic tree\",\"seed\":5374,\"seed_name\":\"Magic sapling\",\"seed_qty\":1,"
		+ "   \"protect\":[{\"item\":5988,\"qty\":25,\"name\":\"Coconut\"}]}"
		+ " ]},"
		+ "{\"key\":\"farming-guild\",\"name\":\"Farming Guild\",\"x\":1249,\"y\":3725,\"plane\":0,"
		+ " \"travel\":{\"name\":\"Farming cape\",\"how\":\"Teleport with the cape.\",\"items\":[9810],\"minigame\":false},"
		+ " \"patches\":["
		+ "  {\"key\":\"guild-herb\",\"type\":\"Herb\",\"region\":\"Farming Guild\",\"varbit\":4775,\"impl\":\"HERB\",\"crop\":\"Torstol\",\"seed\":5304,\"seed_name\":\"Torstol seed\",\"seed_qty\":1,\"protect\":[]},"
		+ "  {\"key\":\"guild-bin\",\"type\":\"Compost bin\",\"region\":\"Farming Guild\",\"varbit\":4775,\"impl\":\"BIG_COMPOST\",\"protect\":[]}"
		+ " ]}"
		+ "],"
		+ "\"inventory\":[{\"item\":5341,\"qty\":1,\"name\":\"Rake\",\"why\":\"Weeds\"},{\"item\":952,\"qty\":1,\"name\":\"Spade\",\"why\":\"Harvest\"},"
		+ " {\"item\":5295,\"qty\":2,\"name\":\"Ranarr seed\",\"why\":\"Plant\"},{\"item\":21483,\"qty\":1,\"name\":\"Ultracompost\",\"why\":\"Compost\"}],"
		+ "\"equipment\":[{\"item\":13128,\"qty\":1,\"name\":\"Explorer's ring 4\",\"slot\":\"ring\"}],"
		+ "\"runes\":[],\"spellbook\":\"standard\",\"review_changes\":0,\"ready\":true}";

	static FarmRoute route() {
		return GSON.fromJson(ROUTE_JSON, FarmRoute.class);
	}

	/** A roadmap with goals of every kind. */
	static final String ROADMAP_JSON = "{\"collection_id\":3,\"title\":\"Ironman early game\",\"goal_count\":7,\"goals\":["
		+ "{\"id\":1,\"goal_type\":\"quest\",\"sort_order\":1,\"name\":\"Complete Waterfall Quest\",\"current\":1,\"target\":1,\"is_complete\":true,\"completed_at\":\"2026-09-01T10:00:00Z\"},"
		+ "{\"id\":2,\"goal_type\":\"skill_level\",\"sort_order\":2,\"name\":\"Reach level 43 Prayer\",\"current\":50339,\"target\":50339,\"is_complete\":true,\"meta\":{\"skill\":\"Prayer\"}},"
		+ "{\"id\":3,\"goal_type\":\"item_owned\",\"sort_order\":3,\"name\":\"Get a Dragon defender\",\"current\":0,\"target\":1,\"meta\":{\"item_id\":12954}},"
		+ "{\"id\":4,\"goal_type\":\"skill_xp\",\"sort_order\":4,\"name\":\"Reach 1M Slayer XP\",\"current\":612000,\"target\":1000000,\"progress_percent\":61,\"meta\":{\"skill\":\"Slayer\"},\"dependency_ids\":[3]},"
		+ "{\"id\":5,\"goal_type\":\"kc\",\"sort_order\":5,\"name\":\"Kill Zulrah 50 times for the Tanzanite fang and Magic fang (and a long name that wraps)\",\"current\":12,\"target\":50,\"progress_percent\":24,"
		+ " \"image_url\":\"https://oldschool.runescape.wiki/images/Zulrah.png\"},"
		+ "{\"id\":6,\"goal_type\":\"clog_item\",\"sort_order\":6,\"name\":\"Barrows gloves\",\"current\":0,\"target\":1,\"meta\":{\"item_id\":7462}},"
		+ "{\"id\":7,\"goal_type\":\"custom\",\"sort_order\":7,\"name\":\"Do the Fight Caves\",\"current\":0,\"target\":1}"
		+ "]}";

	/** The slayer gear the website sends for a task. */
	static final String SLAYER_GEAR_JSON = "{\"task\":\"Abyssal demons\",\"monster\":\"Abyssal demon\",\"sets\":["
		+ "{\"key\":\"melee\",\"label\":\"Melee\",\"max_hit\":44,\"dps\":6.81,\"kills_per_hour\":121.4,"
		+ " \"equipment\":[{\"slot\":\"head\",\"item\":11865,\"name\":\"Slayer helmet (i)\"},{\"slot\":\"weapon\",\"item\":12006,\"name\":\"Abyssal tentacle\"},"
		+ "  {\"slot\":\"body\",\"item\":10551,\"name\":\"Fighter torso\"},{\"slot\":\"shield\",\"item\":12954,\"name\":\"Dragon defender\"},{\"slot\":\"neck\",\"item\":6585,\"name\":\"Amulet of fury\"},"
		+ "  {\"slot\":\"legs\",\"item\":4087,\"name\":\"Dragon platelegs\"},{\"slot\":\"hands\",\"item\":7462,\"name\":\"Barrows gloves\"},{\"slot\":\"feet\",\"item\":11840,\"name\":\"Dragon boots\"},"
		+ "  {\"slot\":\"ring\",\"item\":11773,\"name\":\"Berserker ring (i)\"},{\"slot\":\"cape\",\"item\":6570,\"name\":\"Fire cape\"}],"
		+ " \"extras\":[{\"label\":\"Prayer\",\"name\":\"Piety\"},{\"label\":\"Boost\",\"name\":\"Super combat potion\"}],"
		+ " \"bring\":[{\"kind\":\"food\",\"name\":\"Shark\",\"items\":[385]}]},"
		+ "{\"key\":\"burst\",\"label\":\"Ice Burst (3 targets)\",\"max_hit\":22,\"dps\":9.4,\"kills_per_hour\":168.0,\"spell\":\"Ice Burst\","
		+ " \"equipment\":[{\"slot\":\"head\",\"item\":11865,\"name\":\"Slayer helmet (i)\"},{\"slot\":\"weapon\",\"item\":12899,\"name\":\"Trident of the swamp\"}],"
		+ " \"extras\":[],\"supplies\":{\"kind\":\"runes\",\"options\":[{\"name\":\"Ice Burst\",\"items\":[560,565]},{\"name\":\"Ice Blitz\",\"items\":[565,555]}]},\"bring\":[]}"
		+ "]}";
}
