package com.osrscompanion;

import lombok.RequiredArgsConstructor;
import com.osrscompanion.model.FarmRoute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.util.Text;

/**
 * "Auto set teleport left click": makes the teleport a guide's way there uses the left click of its
 * item, worn or in the inventory. Capes (Max cape, Construction cape, Farming cape, Ardougne cloak)
 * get the named teleport; any other teleport item (Xeric's talisman, Kharedst's memoirs, jewellery...)
 * the option the travel text names, else its teleport option. Shared by the farm run and the
 * Slayer helper; the caller decides when it applies. Client thread (from PostMenuSort).
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
public class TeleportLeftClick {
	/** Capes with teleports a route uses: Max capes, Construction capes, Farming capes, Ardougne cloaks. */
	private static final Set<Integer> TELEPORT_CAPES = new HashSet<>(Arrays.asList(
		13280, 13342, 9789, 9790, FarmRunManager.FARMING_CAPE, FarmRunManager.FARMING_CAPE_T, 13121, 13122, 13123, 13124));
	/** "Max cape (Hosidius house portal)": the cape's teleport to a town's house portal. */
	private static final Pattern HOUSE_PORTAL = Pattern.compile("\\(([a-z' ]+) house portal\\)");
	/** Item options that never teleport: not made the left click. */
	private static final Set<String> NOT_TELEPORTS = new HashSet<>(Arrays.asList(
		"wear", "wield", "remove", "drop", "use", "examine", "check", "cancel", "walk here", "destroy", "read", "inspect", "features",
		"spellbook", "check charges", "uncharge", "empty"));

	private final Client client;
	/** Item names (lower case), read once on the client thread. */
	private final Map<Integer, String> names = new ConcurrentHashMap<>();
	/** The last menu choice written to the log, so a hovered item is logged once, not every frame. */
	private String lastLog;

	/**
	 * Makes the travel's teleport the left click of the menu about to show. {@code where} names the
	 * guide's step for the log ("farm step 3", "slayer"). Client thread.
	 */
	public void apply(FarmRoute.Travel travel, String where) {
		apply(travel, where, m -> false);
	}

	/**
	 * As {@link #apply(FarmRoute.Travel, String)}, but entries {@code keep} accepts are never made the left
	 * click (the Slayer helper keeps "Wear" on a teleport item the suggested setup wears). Client thread.
	 */
	public void apply(FarmRoute.Travel travel, String where, Predicate<MenuEntry> keep) {
		if (travel == null) {
			return;
		}
		Predicate<MenuEntry> cape = m -> isTeleportCape(m) && !keep.test(m);
		MenuEntry[] entries = client.getMenuEntries();
		StringBuilder capes = new StringBuilder();
		for (MenuEntry m : entries) {
			if (cape.test(m)) {
				capes.append('\'').append(Text.removeTags(m.getOption())).append("' ");
			}
		}
		if (capes.length() == 0) {
			travelItemLeftClick(entries, travel, where, keep);
			return;
		}
		List<String> wanted = capeOptions(travel);
		int best = capeEntry(entries, wanted, cape);
		MenuEntry fromSub = null;
		if (best < 0) {
			// Not on the cape's own menu (the Max cape in the inventory): its "Teleports" sub menu has it.
			List<String> subWanted = capeSubOptions(travel);
			for (MenuEntry m : entries) {
				if (fromSub != null || subWanted.isEmpty() || !cape.test(m) || m.getSubMenu() == null) {
					continue;
				}
				for (MenuEntry sub : m.getSubMenu().getMenuEntries()) {
					String option = Text.removeTags(sub.getOption()).toLowerCase();
					if (subWanted.stream().anyMatch(option::equals)) {
						fromSub = sub;
						break;
					}
				}
			}
		}
		if (best < 0 && fromSub == null) {
			// Only then a generic option that opens the cape's teleport list (the Farming cape's Teleport).
			best = capeEntry(entries, capeFallbackOptions(travel), cape);
		}
		logOnce("CAPE TELEPORT " + where + ", travel '" + travel.getName() + "' (" + travel.getHow() + "), looking for " + wanted
			+ ", cape options " + capes + "-> left click " + (best >= 0 ? "'" + Text.removeTags(entries[best].getOption()) + "'"
			: fromSub != null ? "'" + Text.removeTags(fromSub.getOption()) + "' (from the Teleports menu)" : "unchanged"));
		if (best < 0 && fromSub != null) {
			copyToTop(fromSub);
			return;
		}
		moveToLeftClick(entries, best);
	}

	/**
	 * Any other teleport item the step uses (Kharedst's memoirs, Book of the dead, Xeric's talisman, a
	 * necklace...): its option (or sub menu option) named in the step's travel text becomes the left click,
	 * else its teleport option (Rub, Reminisce, Teleport...).
	 */
	private void travelItemLeftClick(MenuEntry[] entries, FarmRoute.Travel travel, String where, Predicate<MenuEntry> keep) {
		if (travel.getItems().isEmpty()) {
			return;
		}
		Set<String> wantedNames = new HashSet<>();
		Set<Integer> ids = new HashSet<>();
		for (int id : travel.getItems()) {
			ids.add(ItemVariationMapping.map(id));
			String n = nameOf(id);
			if (n != null) {
				wantedNames.add(baseName(n));
			}
		}
		String text = travelText(travel);
		MenuEntry best = null;
		boolean bestFromSub = false;
		int bestScore = 0;
		StringBuilder seen = new StringBuilder();
		for (MenuEntry m : entries) {
			boolean ours = (m.getItemId() > 0 && ids.contains(ItemVariationMapping.map(m.getItemId()))) || wantedNames.contains(baseName(m.getTarget()));
			if (!ours || keep.test(m)) {
				continue;
			}
			List<MenuEntry> options = new ArrayList<>();
			options.add(m);
			if (m.getSubMenu() != null) {
				options.addAll(Arrays.asList(m.getSubMenu().getMenuEntries()));
			}
			for (MenuEntry o : options) {
				String option = Text.removeTags(o.getOption()).toLowerCase().trim();
				seen.append('\'').append(option).append("' ");
				int score = optionScore(option, text);
				if (score > bestScore) {
					best = o;
					bestFromSub = o != m;
					bestScore = score;
				}
			}
		}
		if (seen.length() == 0) {
			return;
		}
		logOnce("TELEPORT LEFT CLICK " + where + ", travel '" + travel.getName() + "', item options " + seen
			+ "-> " + (best == null ? "unchanged" : "'" + Text.removeTags(best.getOption()) + "'" + (bestFromSub ? " (from its sub menu)" : "")));
		if (best == null) {
			return;
		}
		if (bestFromSub) {
			copyToTop(best);
			return;
		}
		moveToLeftClick(entries, Arrays.asList(entries).indexOf(best));
	}

	/**
	 * How good an item option is as the teleport: named in the travel text (longest name wins), else a
	 * generic teleport option (Rub, Reminisce, Teleport...); 0 for options that never teleport.
	 */
	static int optionScore(String option, String travelText) {
		if (option.isEmpty() || NOT_TELEPORTS.contains(option)) {
			return 0;
		}
		if (travelText.contains(option)) {
			return 100 + option.length();
		}
		return option.contains("teleport") || option.equals("rub") || option.equals("reminisce") || option.equals("commune")
			|| option.equals("break") || option.equals("operate") ? 10 : 0;
	}

	/** The cape entry whose option contains the best-ranked of {@code wanted}, -1 when none does. */
	private static int capeEntry(MenuEntry[] entries, List<String> wanted, Predicate<MenuEntry> cape) {
		int best = -1;
		int bestRank = Integer.MAX_VALUE;
		for (int i = 0; i < entries.length; i++) {
			if (!cape.test(entries[i])) {
				continue;
			}
			String option = Text.removeTags(entries[i].getOption()).toLowerCase();
			for (int rank = 0; rank < wanted.size() && rank < bestRank; rank++) {
				if (option.contains(wanted.get(rank))) {
					best = i;
					bestRank = rank;
				}
			}
		}
		return best;
	}

	/** The last entry is the left click: moves this one there. */
	private void moveToLeftClick(MenuEntry[] entries, int index) {
		int last = entries.length - 1;
		if (index < 0 || index == last) {
			return;
		}
		MenuEntry pick = entries[index];
		// A low priority (deprioritized) entry on top makes a left click open the menu instead (the
		// Ardougne cloak's teleports are): raise it, as Menu Entry Swapper does.
		if (pick.getType() == MenuAction.CC_OP_LOW_PRIORITY) {
			pick.setType(MenuAction.CC_OP);
		}
		pick.setDeprioritized(false);
		System.arraycopy(entries, index + 1, entries, index, last - index);
		entries[last] = pick;
		client.setMenuEntries(entries);
	}

	/**
	 * A copy of a sub menu entry on top, the left click (added last), as Menu Entry Swapper does for its
	 * sub menu swaps: same action, and a low priority op raised so it isn't sorted below the others.
	 */
	private void copyToTop(MenuEntry from) {
		client.createMenuEntry(-1)
			.setDeprioritized(false)
			.setOption(from.getOption())
			.setTarget(from.getTarget())
			.setIdentifier(from.getIdentifier())
			.setType(from.getType() == MenuAction.CC_OP_LOW_PRIORITY ? MenuAction.CC_OP : from.getType())
			.setItemId(from.getItemId())
			.setParam0(from.getParam0())
			.setParam1(from.getParam1())
			.onClick(from.onClick());
	}

	private void logOnce(String line) {
		if (!line.equals(lastLog)) {
			lastLog = line;
		}
	}

	private static String travelText(FarmRoute.Travel travel) {
		return ((travel.getName() == null ? "" : travel.getName()) + " " + (travel.getHow() == null ? "" : travel.getHow())).toLowerCase();
	}

	/**
	 * The cape options for a way there, best first: Tele to POH, a town's POH Portals, the Farming
	 * Guild, the Ardougne cloak's farm or Monastery teleport. Empty when it isn't a cape teleport.
	 * Only options that teleport straight there: a generic one is {@link #capeFallbackOptions}.
	 */
	static List<String> capeOptions(FarmRoute.Travel travel) {
		if (travel == null) {
			return Collections.emptyList();
		}
		String text = travelText(travel);
		if (text.contains("tele to poh")) {
			return Collections.singletonList("tele to poh");
		}
		if (HOUSE_PORTAL.matcher(text).find()) {
			// Worn: the town is in its "POH Portals" list.
			return Collections.singletonList("poh portals");
		}
		if (text.contains("farming guild") || text.contains("farming cape")) {
			return Collections.singletonList("farming guild");
		}
		if (text.contains("ardougne cloak")) {
			return text.contains("monastery") ? Arrays.asList("kandarin monastery", "monastery")
				: Arrays.asList("ardougne farm", "farm");
		}
		return Collections.emptyList();
	}

	/**
	 * When neither the cape's menu nor a sub menu has the teleport itself: the option that opens its
	 * teleport list or teleports there by default (the Farming cape's "Teleport", the Max cape's "Other
	 * Teleports"). Never before the sub menus, or the inventory Max cape's "Teleports" would win.
	 */
	static List<String> capeFallbackOptions(FarmRoute.Travel travel) {
		String text = travel == null ? "" : travelText(travel);
		return text.contains("farming guild") || text.contains("farming cape")
			? Arrays.asList("other teleports", "teleport") : Collections.emptyList();
	}

	/**
	 * In the inventory the Max cape keeps its teleports in the "Teleports" sub menu: the entry there for
	 * the way there (Home for Tele to POH, a town, Farming Guild). Empty when there is none.
	 */
	static List<String> capeSubOptions(FarmRoute.Travel travel) {
		if (travel == null) {
			return Collections.emptyList();
		}
		String text = travelText(travel);
		if (text.contains("tele to poh")) {
			return Collections.singletonList("home");
		}
		Matcher portal = HOUSE_PORTAL.matcher(text);
		if (portal.find()) {
			return Collections.singletonList(portal.group(1));
		}
		if (text.contains("farming guild")) {
			return Collections.singletonList("farming guild");
		}
		return Collections.emptyList();
	}

	/** Teleport capes by name (the menu's target), as worn and inventory entries don't always carry the item id. */
	private static boolean isTeleportCape(MenuEntry m) {
		if (TELEPORT_CAPES.contains(m.getItemId())) {
			return true;
		}
		String target = Text.removeTags(m.getTarget() == null ? "" : m.getTarget()).toLowerCase();
		return target.contains("max cape") || target.contains("construct. cape") || target.contains("construction cape")
			|| target.contains("farming cape") || target.contains("ardougne cloak");
	}

	/** The item name without its charges or dose, to match "Ring of dueling(5)" with "Ring of dueling(8)". */
	static String baseName(String name) {
		return Text.removeTags(name == null ? "" : name).toLowerCase().replaceAll("\\s*\\(\\d+\\)$", "").trim();
	}

	/** The item's name in lower case. Client thread. */
	private String nameOf(int itemId) {
		String cached = names.get(itemId);
		if (cached != null || !client.isClientThread()) {
			return cached;
		}
		ItemComposition comp = client.getItemDefinition(itemId);
		String name = comp == null || comp.getName() == null ? null : comp.getName().toLowerCase();
		if (name != null) {
			names.put(itemId, name);
		}
		return name;
	}
}
