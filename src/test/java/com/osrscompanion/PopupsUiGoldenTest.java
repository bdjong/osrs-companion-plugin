package com.osrscompanion;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.osrscompanion.ui.UiKit;
import java.awt.Rectangle;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

/** Golden renders of the goal completion popup's bar and the trip planner beside the bank (see {@link UiKit}). */
public class PopupsUiGoldenTest {
	private final UiKit kit = new UiKit();
	private final Client client = mock(Client.class);

	private static Widget widget(int x, int y, int w, int h) {
		Widget widget = mock(Widget.class);
		when(widget.getBounds()).thenReturn(new Rectangle(x, y, w, h));
		return widget;
	}

	// ---- goal completion popup ---------------------------------------------------------------------

	@Test
	public void completionPopupBar() {
		GoalCompletionNotifier notifier = mock(GoalCompletionNotifier.class);
		when(notifier.getActive()).thenReturn(new GoalCompletionNotifier.Popup("Goal complete!", "Barrows gloves", "Main roadmap", 7, 24));
		Widget container = widget(140, 30, 480, 90);
		when(client.getWidget(InterfaceID.NotificationDisplay.CONTAINER)).thenReturn(container);
		UiKit.golden("completion-popup-bar", kit.render(new GoalCompletionOverlay(client, notifier), 765, 160));
	}

	@Test
	public void completionPopupWithoutTheGameNotification() {
		GoalCompletionNotifier notifier = mock(GoalCompletionNotifier.class);
		when(notifier.getActive()).thenReturn(new GoalCompletionNotifier.Popup("Goal complete!", "Barrows gloves", "Main roadmap", 7, 24));
		UiKit.golden("completion-popup-none", kit.render(new GoalCompletionOverlay(client, notifier), 100, 100));
	}

	// ---- trip planner ------------------------------------------------------------------------------

	private final SuppliesCheck check = mock(SuppliesCheck.class);
	private final BossGearManager bossGear = mock(BossGearManager.class);
	private final FarmRunManager farmRun = mock(FarmRunManager.class);

	private SuppliesOverlay planner() {
		return new SuppliesOverlay(client, new TestOSRSCompanionConfig(), check, bossGear, farmRun, kit.itemManager());
	}

	static SuppliesCheck.Result bossResult() {
		return new SuppliesCheck.Result(3, "Vorkath (ranged, Ruby dragon bolts (e))", 4, "trip", "Prayer potion(4)", "Supplies used on your last 6 trips",
			Arrays.asList(
				new SuppliesCheck.Row("Prayer potion(4)", 2434, "3", 14, 4),
				new SuppliesCheck.Row("Shark", 385, "11", 140, 12),
				new SuppliesCheck.Row("Ruby dragon bolts (e)", 21944, "180", 2600, 14),
				new SuppliesCheck.Row("Divine ranging potion(4) and an extremely long name to cut", 23733, "1", 2, 2),
				new SuppliesCheck.Row("Antifire potion(4)", 2452, "1", 0, 0),
				new SuppliesCheck.Row("Rune pouch", 12791, "1", 1, Integer.MAX_VALUE),
				new SuppliesCheck.Row("Seventh row that is not shown", 1, "1", 1, 1)));
	}

	@Test
	public void plannerBesideTheBankWithRoom() {
		when(client.getCanvasWidth()).thenReturn(1200);
		Widget bank = widget(300, 40, 488, 334);
		when(client.getWidget(InterfaceID.Bankmain.UNIVERSE)).thenReturn(bank);
		when(check.getBoss()).thenReturn(bossResult());
		when(bossGear.isSetActive()).thenReturn(true);
		UiKit.golden("planner-boss-full", kit.render(planner(), 1200, 420));
	}

	@Test
	public void plannerFarmRunNoRoomBesideTheBank() {
		when(client.getCanvasWidth()).thenReturn(765);
		Widget bank = widget(20, 40, 488, 334);
		when(client.getWidget(InterfaceID.Bankmain.UNIVERSE)).thenReturn(bank);
		when(check.getFarm()).thenReturn(new SuppliesCheck.Result(7, "Herbs + trees", 0, "run", "Ultracompost", "Your farm route",
			Arrays.asList(new SuppliesCheck.Row("Ultracompost", 21483, "6", 3, 0))));
		when(farmRun.isRunning()).thenReturn(true);
		UiKit.golden("planner-farm-line", kit.render(planner(), 765, 420));
	}

	@Test
	public void plannerHiddenWithoutTheBank() {
		when(check.getBoss()).thenReturn(bossResult());
		when(bossGear.isSetActive()).thenReturn(true);
		when(client.getWidget(anyInt())).thenReturn(null);
		UiKit.golden("planner-none", kit.render(planner(), 100, 100));
	}
}
