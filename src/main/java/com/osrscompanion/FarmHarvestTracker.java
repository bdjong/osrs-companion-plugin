package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.DiaryTierResult;
import com.osrscompanion.model.FarmRoute;
import com.osrscompanion.runelite.farming.CompostState;
import com.osrscompanion.runelite.farming.CompostTracker;
import com.osrscompanion.runelite.farming.CropState;
import com.osrscompanion.runelite.farming.FarmingPatch;
import com.osrscompanion.runelite.farming.FarmingTracker;
import com.osrscompanion.runelite.farming.FarmingWorld;
import com.osrscompanion.runelite.farming.PatchImplementation;
import com.osrscompanion.runelite.farming.PatchPrediction;
import com.osrscompanion.runelite.farming.Produce;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * Records harvest yields for the website's Harvest stats: every patch you pick clean, at any
 * known patch (on a guided farm run or not), is sent with how much it gave and what may have
 * changed that (Farming cape, magic secateurs, compost, Farming level).
 *
 * <p>How a harvest is counted: clicking Pick/Harvest on a patch opens it. Every pick gives
 * Farming XP, so each tick with a Farming XP drop is one pick, worth the produce the inventory
 * gained that tick (at least one: herbs going straight into an open herb sack add nothing to the
 * inventory). Inventory gains without XP (notes from the tool leprechaun) are ignored. The patch
 * is done when RuneLite's farming data stops calling it harvestable; walking away, logging out
 * or several idle minutes drop an unfinished one, so only whole harvests are sent.
 *
 * <p>Compost: RuneLite's compost tracking forgets a patch's compost once the crop is grown, so
 * the compost seen while the crop was still growing is remembered here (per RuneLite profile)
 * and sent with the harvest. A crop seen diseased (and cured) is remembered the same way.
 *
 * <p>A dead crop is sent too, with nothing picked, once you clear it: the stats page can then
 * show how often a patch is lost. Also sent: the anima plant growing at the Farming Guild
 * (attas raises yields) and the Kandarin and Kourend &amp; Kebos diary tiers (their diaries
 * raise the Catherby and Hosidius herb yields).
 */
@Slf4j
@Singleton
public class FarmHarvestTracker {
	private static final String CONFIG_GROUP = OSRSCompanionConfig.CONFIG_GROUP;
	private static final String COMPOST_KEY = "harvestCompost.";
	private static final String DISEASE_KEY = "harvestDiseased.";
	/** Diaries whose rewards raise herb yields at one patch (Catherby, Hosidius), as the diary reader names them. */
	private static final String KANDARIN = "Kandarin";
	private static final String KOUREND = "Kourend & Kebos";
	private static final String[] DIARY_TIERS = {"Easy", "Medium", "Hard", "Elite"};
	/** Ticks without a pick before an unfinished harvest is dropped (about three minutes). */
	private static final int IDLE_TICKS = 300;
	/** Tiles from the patch before an unfinished harvest is dropped. */
	private static final int MAX_DISTANCE = 20;
	/** How often (ticks) the compost on nearby growing patches is noted. */
	private static final int COMPOST_SCAN_TICKS = 5;
	/** Patches that are not crops (compost bins) or give nothing to pick. */
	private static final Set<PatchImplementation> SKIPPED = java.util.EnumSet.of(
		PatchImplementation.COMPOST, PatchImplementation.BIG_COMPOST, PatchImplementation.TREE, PatchImplementation.HARDWOOD_TREE,
		PatchImplementation.REDWOOD, PatchImplementation.SPIRIT_TREE, PatchImplementation.ANIMA, PatchImplementation.HESPORI,
		PatchImplementation.CRYSTAL_TREE);

	private final Client client;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final ConfigManager configManager;
	private final FarmRunManager farmRunManager;
	private final AchievementDiariesReader diaryReader;
	private final Gson gson;

	private volatile FarmingWorld farmingWorld;
	private volatile FarmingTracker farmingTracker;
	private volatile CompostTracker compostTracker;

	/** The patch being picked, or null. */
	private Harvest active;
	private int lastXp = -1;
	private boolean xpThisTick;
	private Map<Integer, Integer> lastInventory;
	private final Map<Integer, Integer> gainedThisTick = new HashMap<>();
	/** Compost last seen per patch (config key), as stored; "none" for none. Also holds the disease marks. */
	private final Map<String, String> compostSeen = new HashMap<>();

	@Inject
	public FarmHarvestTracker(Client client, OSRSCompanionConfig config, OSRSCompanionApiClient apiClient, ConfigManager configManager,
		FarmRunManager farmRunManager, AchievementDiariesReader diaryReader, Gson gson) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.farmRunManager = farmRunManager;
		this.diaryReader = diaryReader;
		this.gson = gson;
	}

	/** The farming data the plugin builds at start-up. */
	public void setFarming(FarmingWorld world, FarmingTracker tracker, CompostTracker compost) {
		this.farmingWorld = world;
		this.farmingTracker = tracker;
		this.compostTracker = compost;
	}

	public void stop() {
		reset();
		compostSeen.clear();
	}

	private void reset() {
		active = null;
		lastXp = -1;
		xpThisTick = false;
		lastInventory = null;
		gainedThisTick.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event.getGameState() != GameState.LOGGED_IN && event.getGameState() != GameState.LOADING) {
			reset();
		}
		if (event.getGameState() == GameState.LOGIN_SCREEN) {
			// Another account may log in next: its compost is read from its own profile.
			compostSeen.clear();
		}
	}

	/** Pick / Harvest on a patch starts (or resumes) counting it; Clear on a dead crop records it as lost. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		if (!isObjectOption(event.getMenuAction()) || event.getMenuOption() == null) {
			return;
		}
		String option = event.getMenuOption().toLowerCase();
		boolean clear = option.startsWith("clear");
		if (!clear && !option.startsWith("pick") && !option.startsWith("harvest")) {
			return;
		}
		ObjectComposition def = client.getObjectDefinition(event.getId());
		if (def == null || def.getVarbitId() <= 0) {
			return;
		}
		WorldPoint at = WorldPoint.fromScene(client, event.getParam0(), event.getParam1(), client.getPlane());
		FarmingPatch patch = patchAt(def.getVarbitId(), at);
		if (patch == null || SKIPPED.contains(patch.getImplementation())) {
			return;
		}
		if (active != null && active.patch == patch) {
			active.lastTick = client.getTickCount();
			return;
		}
		PatchPrediction prediction = predict(patch);
		CropState wanted = clear ? CropState.DEAD : CropState.HARVESTABLE;
		if (prediction == null || prediction.getCropState() != wanted) {
			return;
		}
		active = new Harvest(patch, at, prediction.getProduce() == null ? null : prediction.getProduce().getName(),
			rememberedCompost(patch), clear, client.getTickCount());
		log.debug("{} started at {} ({})", clear ? "Clearing a dead crop" : "Harvest", patch, active.produce);
	}

	private static boolean isObjectOption(MenuAction action) {
		switch (action) {
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event) {
		if (event.getSkill() != Skill.FARMING) {
			return;
		}
		if (lastXp >= 0 && event.getXp() > lastXp) {
			xpThisTick = true;
		}
		lastXp = event.getXp();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		if (event.getContainerId() != InventoryID.INV) {
			return;
		}
		Map<Integer, Integer> now = new HashMap<>();
		ItemContainer inv = event.getItemContainer();
		if (inv != null) {
			for (Item item : inv.getItems()) {
				if (item.getId() > 0) {
					now.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		if (lastInventory != null) {
			for (Map.Entry<Integer, Integer> e : now.entrySet()) {
				int gained = e.getValue() - lastInventory.getOrDefault(e.getKey(), 0);
				if (gained > 0) {
					gainedThisTick.merge(e.getKey(), gained, Integer::sum);
				}
			}
		}
		lastInventory = now;
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		if (client.getTickCount() % COMPOST_SCAN_TICKS == 0) {
			noteCompost();
		}
		Harvest h = active;
		if (h != null && h.dead) {
			// A dead crop is done once the patch no longer shows it (cleared, back to weeds or empty).
			PatchPrediction now = predict(h.patch);
			if (now != null && now.getCropState() != CropState.DEAD) {
				active = null;
				send(h);
			} else if (client.getTickCount() - h.lastTick > IDLE_TICKS || tooFar(h)) {
				active = null;
			}
		} else if (h != null) {
			if (xpThisTick) {
				int items = 0;
				for (Map.Entry<Integer, Integer> e : gainedThisTick.entrySet()) {
					items += e.getValue();
					h.items.merge(e.getKey(), e.getValue(), Integer::sum);
				}
				h.amount += Math.max(1, items);
				h.picks++;
				h.lastTick = client.getTickCount();
				// Worn for most picks: the cape and secateurs act on every pick.
				if (farmRunManager.isFarmingCapeWorn()) {
					h.capePicks++;
				}
				if (farmRunManager.carried(ItemID.FAIRY_ENCHANTED_SECATEURS) > 0) {
					h.secateursPicks++;
				}
			}
			PatchPrediction now = predict(h.patch);
			if (h.amount > 0 && now != null && now.getCropState() != CropState.HARVESTABLE) {
				active = null;
				send(h);
			} else if (client.getTickCount() - h.lastTick > IDLE_TICKS || tooFar(h)) {
				log.debug("Harvest at {} dropped unfinished ({} picked)", h.patch, h.amount);
				active = null;
			}
		}
		xpThisTick = false;
		gainedThisTick.clear();
	}

	private boolean tooFar(Harvest h) {
		return client.getLocalPlayer() == null || client.getLocalPlayer().getWorldLocation().distanceTo2D(h.at) > MAX_DISTANCE;
	}

	private void send(Harvest h) {
		if (!config.syncFarmHarvests() || !SyncGuard.hasAppKey(config) || GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String player = SyncGuard.getPlayerUsername(client);
		if (player == null) {
			return;
		}
		// The item picked most (grimy herb, berries, ...); none when it all went into a herb sack.
		Integer produceItem = h.items.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		FarmRoute.Patch routePatch = farmRunManager.routePatch(h.patch);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", player);
		body.put("region", h.patch.getRegion().getName());
		body.put("varbit", h.patch.getVarbit());
		body.put("impl", h.patch.getImplementation().name());
		body.put("patch_key", routePatch == null ? null : routePatch.getKey());
		body.put("produce", h.produce);
		body.put("produce_item", produceItem);
		body.put("amount", h.amount);
		body.put("outcome", h.dead ? "dead" : "harvested");
		// A dead crop has no picks: what was worn when it was cleared.
		body.put("farming_cape", h.picks == 0 ? farmRunManager.isFarmingCapeWorn() : h.capePicks * 2 >= h.picks);
		body.put("magic_secateurs", h.picks == 0 ? farmRunManager.carried(ItemID.FAIRY_ENCHANTED_SECATEURS) > 0 : h.secateursPicks * 2 >= h.picks);
		body.put("compost", h.compost);
		body.put("diseased", h.dead || "true".equals(rememberedRaw(diseaseKey(h.patch))));
		body.put("anima", animaPlant());
		Map<String, Map<String, DiaryTierResult>> diaries = readDiaries();
		body.put("kandarin_diary", diaryTier(diaries, KANDARIN));
		body.put("kourend_diary", diaryTier(diaries, KOUREND));
		body.put("farming_level", client.getRealSkillLevel(Skill.FARMING));
		body.put("boosted_level", client.getBoostedSkillLevel(Skill.FARMING));
		body.put("on_run", farmRunManager.isRunning() && routePatch != null);
		body.put("harvested_at", Instant.now().toString());
		log.debug("Harvest at {}: {} x {}{}", h.patch, h.amount, h.produce, h.dead ? " (dead)" : "");
		apiClient.sendFarmHarvest(gson.toJson(body));
		// The crop is gone: a disease mark belonged to it, not to the next one.
		forgetDisease(h.patch);
	}

	// ---- Yield modifiers -----------------------------------------------------------

	/**
	 * The anima plant alive in the Farming Guild's anima patch ("attas", "iasor", "kronos"),
	 * "none" when the patch has none (or it died), null when RuneLite never saw the patch.
	 */
	private String animaPlant() {
		FarmingWorld world = farmingWorld;
		if (world == null) {
			return null;
		}
		for (Set<FarmingPatch> tab : world.getTabs().values()) {
			for (FarmingPatch fp : tab) {
				if (fp.getImplementation() != PatchImplementation.ANIMA) {
					continue;
				}
				PatchPrediction p = predict(fp);
				if (p == null || p.getProduce() == null) {
					return null;
				}
				Produce plant = p.getProduce();
				boolean anima = plant == Produce.ATTAS || plant == Produce.IASOR || plant == Produce.KRONOS;
				// The last stage is the dead plant, whose effect is over.
				return anima && p.getStage() < p.getStages() - 1 ? plant.getName().toLowerCase() : "none";
			}
		}
		return null;
	}

	private Map<String, Map<String, DiaryTierResult>> readDiaries() {
		try {
			return diaryReader.read(client);
		} catch (RuntimeException e) {
			log.debug("Could not read the achievement diaries", e);
			return null;
		}
	}

	/** Highest tier of the diary done in order: 0 none, 1 easy, 2 medium, 3 hard, 4 elite; null when not read. */
	private static Integer diaryTier(Map<String, Map<String, DiaryTierResult>> diaries, String region) {
		Map<String, DiaryTierResult> tiers = diaries == null ? null : diaries.get(region);
		if (tiers == null) {
			return null;
		}
		int done = 0;
		for (String tier : DIARY_TIERS) {
			DiaryTierResult r = tiers.get(tier);
			if (r == null || !r.isComplete()) {
				break;
			}
			done++;
		}
		return done;
	}

	// ---- Compost -------------------------------------------------------------------

	/**
	 * Notes the compost on nearby patches that are growing (or empty and treated, waiting
	 * for a seed). RuneLite clears it once the crop is grown, so this is what the harvest
	 * reports. Needs RuneLite's Time Tracking plugin, which does the compost tracking.
	 */
	private void noteCompost() {
		FarmingWorld world = farmingWorld;
		CompostTracker compost = compostTracker;
		if (world == null || compost == null || client.getLocalPlayer() == null || client.getGameState() != GameState.LOGGED_IN
			|| "false".equals(configManager.getConfiguration("runelite", "timetrackingplugin"))) {
			return;
		}
		int here = client.getLocalPlayer().getWorldLocation().getRegionID();
		for (Set<FarmingPatch> tab : world.getTabs().values()) {
			for (FarmingPatch fp : tab) {
				if (SKIPPED.contains(fp.getImplementation()) || regionDistance(fp.getRegion().getRegionID(), here) > 1) {
					continue;
				}
				PatchPrediction p = predict(fp);
				if (p == null) {
					continue;
				}
				noteDisease(fp, p.getCropState());
				if (p.getCropState() != CropState.GROWING && p.getCropState() != CropState.EMPTY) {
					continue;
				}
				CompostState state = compost.getCompostState(fp);
				String value = state == null ? "none" : state.name().toLowerCase();
				// An empty patch only counts once it was treated (planting comes next).
				if (p.getCropState() == CropState.EMPTY && state == null) {
					continue;
				}
				String key = compostKey(fp);
				if (!value.equals(rememberedRaw(key))) {
					compostSeen.put(key, value);
					configManager.setRSProfileConfiguration(CONFIG_GROUP, key, value);
				}
			}
		}
	}

	/** The compost noted for the patch's crop, or null when not known. */
	private String rememberedCompost(FarmingPatch fp) {
		return rememberedRaw(compostKey(fp));
	}

	private String rememberedRaw(String key) {
		if (!compostSeen.containsKey(key)) {
			compostSeen.put(key, configManager.getRSProfileConfiguration(CONFIG_GROUP, key));
		}
		return compostSeen.get(key);
	}

	private static String compostKey(FarmingPatch fp) {
		return COMPOST_KEY + fp.getRegion().getRegionID() + "." + fp.getVarbit();
	}

	/** Marks a crop seen diseased; an empty patch starts a new crop, so the mark goes. */
	private void noteDisease(FarmingPatch fp, CropState state) {
		String key = diseaseKey(fp);
		if (state == CropState.DISEASED && !"true".equals(rememberedRaw(key))) {
			compostSeen.put(key, "true");
			configManager.setRSProfileConfiguration(CONFIG_GROUP, key, "true");
		} else if (state == CropState.EMPTY && rememberedRaw(key) != null) {
			forgetDisease(fp);
		}
	}

	private void forgetDisease(FarmingPatch fp) {
		String key = diseaseKey(fp);
		compostSeen.put(key, null);
		configManager.unsetRSProfileConfiguration(CONFIG_GROUP, key);
	}

	private static String diseaseKey(FarmingPatch fp) {
		return DISEASE_KEY + fp.getRegion().getRegionID() + "." + fp.getVarbit();
	}

	// ---- Patches ---------------------------------------------------------------------

	/** The patch with this varbit in (or right next to) the clicked spot's map region. */
	private FarmingPatch patchAt(int varbit, WorldPoint at) {
		FarmingWorld world = farmingWorld;
		if (world == null || at == null) {
			return null;
		}
		FarmingPatch best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (Set<FarmingPatch> tab : world.getTabs().values()) {
			for (FarmingPatch fp : tab) {
				if (fp.getVarbit() != varbit || fp.getRegion() == null) {
					continue;
				}
				int d = regionDistance(fp.getRegion().getRegionID(), at.getRegionID());
				if (d <= 1 && d < bestDistance) {
					best = fp;
					bestDistance = d;
				}
			}
		}
		return best;
	}

	/** Map regions apart (a region id is x << 8 | y). */
	private static int regionDistance(int a, int b) {
		return Math.max(Math.abs((a >> 8) - (b >> 8)), Math.abs((a & 0xff) - (b & 0xff)));
	}

	private PatchPrediction predict(FarmingPatch fp) {
		FarmingTracker tracker = farmingTracker;
		if (tracker == null) {
			return null;
		}
		try {
			return tracker.predictPatch(fp);
		} catch (RuntimeException e) {
			log.debug("Could not read patch {}", fp, e);
			return null;
		}
	}

	private static final class Harvest {
		final FarmingPatch patch;
		final WorldPoint at;
		final String produce;
		final String compost;
		/** Clearing a dead crop: nothing is picked. */
		final boolean dead;
		final Map<Integer, Integer> items = new HashMap<>();
		int amount;
		int picks;
		int capePicks;
		int secateursPicks;
		int lastTick;

		Harvest(FarmingPatch patch, WorldPoint at, String produce, String compost, boolean dead, int tick) {
			this.patch = patch;
			this.at = at;
			this.produce = produce;
			this.compost = compost;
			this.dead = dead;
			this.lastTick = tick;
		}
	}
}
