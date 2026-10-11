package com.osrscompanion;

import lombok.RequiredArgsConstructor;
import lombok.Getter;
import com.osrscompanion.model.BossGear;
import com.osrscompanion.model.FarmRoute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

/**
 * How many trips your bank supports, worked out while the bank is open: for the active boss setup
 * ("Enough for ~6 trips, runs out of: Prayer potion(4)") and for the farm route ("~4 farm runs").
 * Per trip it takes what your last logged trips at that boss used on average, or else the setup's
 * inventory and rune pouch (only what gets used up: food, potions by doses, runes, ammo, teleports).
 * Have = bank + what you carry, potions by doses and variants together. The results stay after the
 * bank closes, for the Boss gear window ("at your last bank visit").
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
public class SuppliesCheck {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "supplies-check";

	private final Client client;
	private final OSRSCompanionConfig config;
	private final BossGearManager bossGear;
	private final FarmRunManager farmRun;
	private final SupplyCounter counter;

	/** One supply in a result: its name and item, how many a trip takes, how many you have and how many trips that lasts. */
	static final class Row {
		final String name;
		final int itemId;
		final String perTrip;
		final long have;
		final int trips;

		Row(String name, int itemId, String perTrip, long have, int trips) {
			this.name = name;
			this.itemId = itemId;
			this.perTrip = perTrip;
			this.have = have;
			this.trips = trips;
		}

		boolean isShort() {
			return trips < 1;
		}
	}

	/** One check: for which setup / route, how many trips, what runs out first, where the need comes from and every supply. */
	static final class Result {
		final int forId;
		final String title;
		final int trips;
		final String unit;
		final String runsOut;
		final String source;
		final List<Row> rows;

		Result(int forId, String title, int trips, String unit, String runsOut, String source, List<Row> rows) {
			this.forId = forId;
			this.title = title;
			this.trips = trips;
			this.unit = unit;
			this.runsOut = runsOut;
			this.source = source;
			this.rows = rows;
		}

		/** "Enough for ~6 trips" / "Not enough for one trip". */
		String headline() {
			if (trips < 1) {
				return "Not enough for one " + unit;
			}
			return "Enough for ~" + trips + " " + unit + (trips == 1 ? "" : "s");
		}
	}

	@Getter
	private volatile Result boss;
	@Getter
	private volatile Result farm;
	private boolean dirty;
	private Object lastSet;
	private Object lastRoute;

	public void clear() {
		boss = null;
		farm = null;
		lastSet = null;
		lastRoute = null;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGroupId() == InterfaceID.BANKMAIN) {
				dirty = true;
			}
		});
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			int id = event.getContainerId();
			if (id == InventoryID.BANK || id == InventoryID.INV || id == InventoryID.WORN) {
				dirty = true;
			}
		});
	}

	/** A trip planner switch changed: check again. */
	@Subscribe
	public void onConfigChanged(net.runelite.client.events.ConfigChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (OSRSCompanionConfig.CONFIG_GROUP.equals(event.getGroup()) && event.getKey().startsWith("tripPlanner")) {
				dirty = true;
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (event.getGameState() == GameState.LOGIN_SCREEN) {
				clear();
			}
		});
	}

	/** At most once a tick, and only while the bank is open and something changed (the bank, what you carry, the setup or route). */
	@Subscribe
	public void onGameTick(GameTick tick) {
		Guard.run(GUARD_FEATURE, () -> {
			if (!(config.tripPlannerBoss() || config.tripPlannerFarm()) || client.getWidget(InterfaceID.Bankmain.UNIVERSE) == null) {
				return;
			}
			// For the tool whose trip planner switch is on and that is active (no bank layout needed): the active
			// boss setup, the farm route of a run that is going.
			BossGear set = config.tripPlannerBoss() && bossGear.isSetActive() ? bossGear.getSet() : null;
			FarmRoute route = config.tripPlannerFarm() && farmRun.isRunning() ? farmRun.getRoute() : null;
			if (!dirty && set == lastSet && route == lastRoute) {
				return;
			}
			Map<String, Long> have = counter.bank();
			if (have == null) {
				return;
			}
			dirty = false;
			lastSet = set;
			lastRoute = route;
			for (Map.Entry<String, Long> e : counter.carried().entrySet()) {
				have.merge(e.getKey(), e.getValue(), Long::sum);
			}
			boss = set == null ? null : checkBoss(set, have);
			farm = route == null ? null : checkFarm(route, have);
		});
	}

	private Result checkBoss(BossGear set, Map<String, Long> have) {
		// What your last trips at this boss used, as the website worked them out from the game events.
		List<Map<String, Long>> past = set.getTripSupplies();
		Map<String, Double> need;
		String source;
		if (!past.isEmpty()) {
			need = TripSupplies.averagePerTrip(past);
			source = "Per trip: average of your last " + (past.size() == 1 ? "trip" : past.size() + " trips");
		} else {
			need = new LinkedHashMap<>();
			List<BossGear.Item> items = new ArrayList<>();
			for (BossGear.Item it : set.getInventory()) {
				if (it != null) {
					items.add(it);
				}
			}
			items.addAll(set.getPouch());
			for (BossGear.Item it : items) {
				addNeed(need, it.getItem(), it.getQty());
			}
			source = "Per trip: the setup's inventory";
		}
		return result(set.getId(), set.getBossName(), "trip", source, need, have);
	}

	private Result checkFarm(FarmRoute route, Map<String, Long> have) {
		Map<String, Double> need = new LinkedHashMap<>();
		for (FarmRoute.Stack s : farmRun.inventory()) {
			addNeed(need, s.getItem(), s.getQty());
		}
		return result(route.getId(), route.getName(), "farm run", "Per run: the route's seeds, compost and teleports", need, have);
	}

	private void addNeed(Map<String, Double> need, int itemId, int qty) {
		if (itemId <= 0 || qty <= 0 || !counter.consumable(itemId)) {
			return;
		}
		Map<String, Long> one = new LinkedHashMap<>();
		counter.add(one, itemId, qty);
		for (Map.Entry<String, Long> e : one.entrySet()) {
			need.merge(e.getKey(), e.getValue().doubleValue(), Double::sum);
		}
	}

	private Result result(int forId, String title, String unit, String source, Map<String, Double> need, Map<String, Long> have) {
		if (!config.tripPlannerRunes()) {
			// "Count runes" off: runes (and combination runes) don't limit the trips.
			need = new LinkedHashMap<>(need);
			need.keySet().removeIf(k -> {
				SupplyCounter.Rep rep = counter.rep(k);
				String name = rep == null ? k.substring(2) : rep.name;
				return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(" rune");
			});
		}
		if (need.isEmpty()) {
			return null;
		}
		TripSupplies.Check check = TripSupplies.tripsSupported(need, have);
		List<Row> rows = new ArrayList<>();
		for (TripSupplies.Need n : check.needs) {
			SupplyCounter.Rep rep = counter.rep(n.key);
			String name = rep == null ? n.key.substring(2) : rep.name;
			int dose = rep == null ? 1 : rep.dose;
			// Dosed supplies show as items of the dose pictured ("1.8" Prayer potion(4)), the rest as they are.
			double per = n.perTrip / dose;
			String perTrip = per == Math.rint(per) ? String.valueOf((long) per) : String.format("%.1f", per);
			rows.add(new Row(name, rep == null ? -1 : rep.id, perTrip, n.have / dose, n.trips));
		}
		TripSupplies.Need limiting = check.limiting();
		String runsOut = limiting == null || rows.isEmpty() ? null : rows.get(0).name;
		return new Result(forId, title, check.trips, unit, runsOut, source, Collections.unmodifiableList(rows));
	}
}
