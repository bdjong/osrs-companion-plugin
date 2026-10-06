package com.osrscompanion;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The OSRS Companion side panel: one tile per tool, like the website's tool banners. Each opens
 * its window over the game (Roadmap, Gear progression, Farm run, Boss gear); everything else
 * happens in those windows.
 */
public class RoadmapPanel extends PluginPanel {
	public RoadmapPanel(Runnable openRoadmap, Runnable openGearProgression, Runnable openFarmRun, Runnable openBossGear, ItemManager itemManager) {
		super(false);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel tiles = new JPanel();
		tiles.setLayout(new BoxLayout(tiles, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("OSRS Companion");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		tiles.add(title);
		tiles.add(Box.createVerticalStrut(10));

		FeatureTile roadmapTile = FeatureTile.banner("ROAD", "MAP", "Your goals, tracked as you play",
			new Color(0x60A5FA), new Color(0x0D1530), new Color(0x1E40AF), itemManager.getImage(ItemID.SKILLCAPE_QP), openRoadmap);
		roadmapTile.setToolTipText("Open or close your roadmap in the game window: goals, progress, complete or delete (Escape closes it).");
		addTile(tiles, roadmapTile);

		FeatureTile gearTile = FeatureTile.banner("GEAR", "PROGRESSION", "Gear order, auto-ticked",
			new Color(0xFB923C), new Color(0x1C0D06), new Color(0x9A3412), itemManager.getImage(ItemID.HUNDRED_GAUNTLETS_LEVEL_10), openGearProgression);
		gearTile.setToolTipText("Open or close your gear progression in the game window (Escape closes it).");
		addTile(tiles, gearTile);

		FeatureTile farmTile = FeatureTile.banner("FARM", "RUN", "Route & guided run",
			new Color(0x4ADE80), new Color(0x08130C), new Color(0x166534), itemManager.getImage(ItemID.WATERING_CAN_8), openFarmRun);
		farmTile.setToolTipText("Open or close your farm route in the game window: stops, what to bring and the guided run (Escape closes it).");
		addTile(tiles, farmTile);

		FeatureTile bossTile = FeatureTile.banner("BOSS", "GEAR", "Setups + bank layout",
			new Color(0xA78BFA), new Color(0x120A24), new Color(0x5B21B6), itemManager.getImage(ItemID.VORKATH_HEAD), openBossGear);
		bossTile.setToolTipText("Open or close your boss gear in the game window: switch boss or setup there; the bank lays it out when you open it (Escape closes it).");
		addTile(tiles, bossTile);

		add(tiles, BorderLayout.NORTH);

		JLabel note = new JLabel("<html><body style='width:170px'><i>Progress updates as you play and "
			+ "syncs with the website in the background.</i></body></html>");
		note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		note.setFont(FontManager.getRunescapeSmallFont());
		note.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		add(note, BorderLayout.SOUTH);
	}

	private static void addTile(JPanel tiles, FeatureTile tile) {
		tile.setAlignmentX(Component.LEFT_ALIGNMENT);
		tiles.add(tile);
		tiles.add(Box.createVerticalStrut(8));
	}
}
