package com.osrscompanion;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.osrscompanion.model.FarmRoute;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetItem;
import org.junit.Test;

/** Golden renders of the farm run's inventory and bank item highlights (see {@link UiKit}). */
public class FarmRunItemsUiGoldenTest {
	private final FarmRunManager manager = mock(FarmRunManager.class);
	private final FarmRoute route = UiSamples.route();

	/** Item ids in an inventory (group 149) or the bank (group 12), in 4 columns of 42 x 36. */
	private UiKit.Rendered draw(int group, int... items) {
		return UiKit.guarded(() -> drawUnguarded(group, items));
	}

	private UiKit.Rendered drawUnguarded(int group, int... items) {
		FarmRunItemOverlay overlay = new FarmRunItemOverlay(new TestOSRSCompanionConfig(), manager);
		BufferedImage img = new BufferedImage(200, 300, BufferedImage.TYPE_INT_ARGB);
		List<String> log = new ArrayList<>();
		Graphics2D g = new RecordingGraphics(img.createGraphics(), log, i -> "img");
		Widget container = mock(Widget.class);
		when(container.getId()).thenReturn(group << 16);
		for (int i = 0; i < items.length; i++) {
			WidgetItem wi = mock(WidgetItem.class);
			when(wi.getWidget()).thenReturn(container);
			when(wi.getCanvasBounds()).thenReturn(new Rectangle(8 + (i % 4) * 42, 8 + (i / 4) * 36, 36, 32));
			log.add("item " + items[i]);
			overlay.renderItemOverlay(g, items[i], wi);
		}
		return new UiKit.Rendered(log, img);
	}

	private void running(boolean complete) {
		when(manager.isRunning()).thenReturn(true);
		when(manager.isComplete()).thenReturn(complete);
		Map<Integer, FarmRunManager.Highlight> h = new HashMap<>();
		h.put(13128, FarmRunManager.Highlight.TELEPORT);
		h.put(5295, FarmRunManager.Highlight.SEED);
		h.put(5986, FarmRunManager.Highlight.PAYMENT);
		h.put(21483, FarmRunManager.Highlight.COMPOST);
		when(manager.currentItems()).thenReturn(h);
		when(manager.loadout()).thenReturn(route.getInventory());
	}

	@Test
	public void inventoryDuringTheRun() {
		running(false);
		UiKit.golden("farm-items-inventory", draw(149, 13128, 5295, 5986, 21483, 952, 1));
	}

	@Test
	public void bankShowsWhatToBringStill() {
		running(false);
		when(manager.neededStack(anyInt(), anyList())).thenReturn(null);
		FarmRoute.Stack spade = route.getInventory().get(1);
		FarmRoute.Stack rake = route.getInventory().get(0);
		when(manager.neededStack(952, route.getInventory())).thenReturn(spade);
		when(manager.neededStack(5341, route.getInventory())).thenReturn(rake);
		when(manager.carried(952)).thenReturn(0);
		when(manager.carried(5341)).thenReturn(1);
		UiKit.golden("farm-items-bank", draw(12, 952, 5341, 5295, 1, 13128));
	}

	@Test
	public void completeRunOnlyHighlightsCompost() {
		running(true);
		UiKit.golden("farm-items-complete", draw(149, 13128, 5295, 21483));
	}
}
