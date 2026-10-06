package com.osrscompanion;

import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.model.FarmRoute;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel for the guided farm run, like Quest Helper's: pick the route, start it,
 * check what to bring, and follow the stops; the current one is highlighted and
 * ticks off as you plant.
 */
public class FarmRunPanel extends PluginPanel {
	private static final Color ORANGE = new Color(0xF2, 0x8C, 0x28);
	private static final Color GREEN = new Color(0x22, 0xC5, 0x5E);
	private static final Color RED = new Color(0xEF, 0x44, 0x44);
	private static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;
	private static final String HTML = "<html><body style='width:%dpx'>%s</body></html>";

	private final FarmRunManager manager;
	private final JComboBox<FarmRoute.RouteSummary> selector = new JComboBox<>();
	private final JButton startButton = new JButton("Start run");
	private final JButton refreshButton = new JButton("Refresh");
	private final JLabel status = new JLabel();
	private final JPanel content = new JPanel();
	private boolean suppressSelector;

	public FarmRunPanel(FarmRunManager manager) {
		super(false);
		this.manager = manager;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		JLabel title = new JLabel("Farm run");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(title);
		header.add(Box.createVerticalStrut(8));

		selector.setAlignmentX(Component.LEFT_ALIGNMENT);
		selector.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		selector.setToolTipText("Your farm routes from the website; the one picked here is guided.");
		selector.addActionListener(e -> {
			if (!suppressSelector) {
				manager.activate((FarmRoute.RouteSummary) selector.getSelectedItem());
			}
		});
		header.add(selector);
		header.add(Box.createVerticalStrut(8));

		JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		startButton.addActionListener(e -> {
			if (manager.isRunning()) {
				manager.stop();
			} else {
				manager.start();
			}
		});
		refreshButton.setToolTipText("Reload the route and its ready times from the website.");
		refreshButton.addActionListener(e -> manager.refresh());
		buttons.add(startButton);
		buttons.add(refreshButton);
		header.add(buttons);
		header.add(Box.createVerticalStrut(6));
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		status.setForeground(MUTED);
		header.add(status);
		add(header, BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JScrollPane scroll = new JScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		manager.setChangeListener(() -> SwingUtilities.invokeLater(this::rebuild));
		rebuild();
	}

	@Override
	public void onActivate() {
		manager.refreshIfStale();
	}

	private void rebuild() {
		FarmRoute route = manager.getRoute();
		suppressSelector = true;
		selector.removeAllItems();
		for (FarmRoute.RouteSummary r : manager.getRoutes()) {
			selector.addItem(r);
			if (r.isActive()) {
				selector.setSelectedItem(r);
			}
		}
		selector.setVisible(manager.getRoutes().size() > 1);
		suppressSelector = false;

		startButton.setEnabled(route != null && !route.getStops().isEmpty());
		startButton.setText(manager.isRunning() ? "Stop run" : "Start run");
		String text;
		if (manager.isLoading()) {
			text = "Loading...";
		} else if (manager.getError() != null) {
			text = manager.getError();
		} else if (route == null) {
			text = manager.isLoaded() ? "No farm route yet. Build one on the website under Tools - Farm Runs." : "Press Refresh to load your farm route.";
		} else if (manager.isRunning()) {
			int[] p = manager.progress();
			text = manager.isComplete() ? "Run complete!" : p[0] + " of " + p[1] + " patches done";
		} else {
			text = route.getName() + ": " + route.getStops().size() + " stops. Press Start run when you are ready.";
		}
		status.setText(String.format(HTML, 180, text));

		content.removeAll();
		if (route != null) {
			content.add(bringSection(route));
			content.add(Box.createVerticalStrut(8));
			List<FarmRoute.Stop> stops = route.getStops();
			for (int i = 0; i < stops.size(); i++) {
				content.add(stopCard(stops.get(i), i));
				content.add(Box.createVerticalStrut(6));
			}
		}
		content.revalidate();
		content.repaint();
	}

	/** What to take: green when carried (inventory or worn), red with what is missing otherwise. */
	private JPanel bringSection(FarmRoute route) {
		JPanel p = section();
		p.add(label("Bring", Color.WHITE, true));
		for (FarmRoute.Stack s : manager.loadout()) {
			int have = manager.carried(s.getItem());
			boolean ok = have >= s.getQty();
			String line = (ok ? "&#10003; " : "&#10007; ") + s.getName() + (s.getQty() > 1 ? " x " + s.getQty() : "")
				+ (ok || have == 0 ? "" : " (have " + have + ")");
			p.add(label(line, ok ? GREEN : RED, false));
		}
		if (!route.getRunes().isEmpty()) {
			p.add(label("Runes for: " + String.join(", ", route.getRunes()), MUTED, false));
		}
		return p;
	}

	private JPanel stopCard(FarmRoute.Stop stop, int index) {
		boolean current = manager.isRunning() && index == manager.getStopIndex() && !manager.isComplete();
		boolean done = manager.isRunning() && stop.getPatches().stream().noneMatch(pt -> manager.statusOf(pt).isOpen());
		JPanel card = section();
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(current ? 2 : 0, current ? 2 : 3, current ? 2 : 0, current ? 2 : 0, current ? ORANGE : done ? GREEN : ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		card.add(label((index + 1) + ". " + stop.getName() + (done ? " &#10003;" : ""), current ? ORANGE : done ? GREEN : Color.WHITE, true));
		FarmRoute.Travel t = stop.getTravel();
		card.add(label((t.isWalk() ? "" : t.isSpell() ? "Cast " : "Use ") + t.getName() + (current && !t.getHow().isEmpty() ? " - " + t.getHow() : ""), MUTED, false));
		for (FarmRoute.Patch p : stop.getPatches()) {
			Status s = manager.isRunning() ? manager.statusOf(p) : null;
			String state = s == null ? readyText(p) : s == Status.DONE ? "done" : s == Status.NOT_READY ? "not ready" : s == Status.SKIPPED ? "skipped" : "to do";
			Color c = s == Status.DONE ? GREEN : s == Status.TODO ? Color.WHITE : s == null && "ready".equals(p.getReady()) ? GREEN : MUTED;
			card.add(label("&bull; " + p.getType() + (p.getCrop() != null ? ": " + p.getCrop() : "") + " - " + state, c, false));
			if (current && s == Status.TODO) {
				for (String step : FarmRunOverlay.stepsFor(p)) {
					card.add(label("&nbsp;&nbsp;" + step, MUTED, false));
				}
			}
		}
		if (current) {
			JPanel row = new JPanel(new GridLayout(1, 2, 6, 0));
			row.setOpaque(false);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
			JButton back = new JButton("Back");
			back.setEnabled(index > 0);
			back.addActionListener(e -> manager.previous());
			JButton skip = new JButton("Skip stop");
			skip.setToolTipText("Leave this stop's patches for next time and go on.");
			skip.addActionListener(e -> manager.skipStop());
			row.add(back);
			row.add(skip);
			card.add(Box.createVerticalStrut(4));
			card.add(row);
		} else if (manager.isRunning()) {
			card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			card.setToolTipText("Go to this stop");
			card.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					manager.goTo(index);
				}
			});
		}
		return card;
	}

	private static String readyText(FarmRoute.Patch p) {
		switch (p.getReady()) {
			case "ready":
				return "ready";
			case "growing":
				return "growing";
			case "diseased":
				return "diseased";
			case "dead":
				return "dead";
			default:
				return "not checked";
		}
	}

	private static JPanel section() {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private static JLabel label(String text, Color color, boolean bold) {
		JLabel l = new JLabel(String.format(HTML, 170, text));
		l.setForeground(color);
		l.setFont(bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}
}
