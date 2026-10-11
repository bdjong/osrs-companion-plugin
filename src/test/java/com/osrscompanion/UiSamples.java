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
}
