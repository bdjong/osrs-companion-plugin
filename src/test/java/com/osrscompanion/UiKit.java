package com.osrscompanion;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The UI golden tests' kit: fake item icons and names, rendering an overlay or a Swing panel through
 * {@link RecordingGraphics}, and comparing the drawing log with the stored one in
 * {@code src/test/resources/golden/<name>.txt} (a PNG next to it is for looking at).
 *
 * <p>A missing golden file is written on the first run: delete one to record it again after an intended UI
 * change. A difference fails the test and writes the new log and picture to {@code build/golden-diff/}.
 */
public final class UiKit {
	public static final Path GOLDEN = Paths.get("src", "test", "resources", "golden");
	public static final Path DIFF = Paths.get("build", "golden-diff");

	private final Map<Image, String> names = new IdentityHashMap<>();
	private final ClientThread clientThread = new ClientThread();

	/** An item manager with a fixed, distinct picture per item (and quantity), names "Item <id>", price id * 100. */
	public ItemManager itemManager() {
		ItemManager im = mock(ItemManager.class);
		when(im.getImage(anyInt())).thenAnswer(a -> itemImage(a.getArgument(0), 1, false));
		when(im.getImage(anyInt(), anyInt(), anyBoolean())).thenAnswer(a -> itemImage(a.getArgument(0), a.getArgument(1), a.getArgument(2)));
		when(im.getItemComposition(anyInt())).thenAnswer(a -> composition(a.getArgument(0)));
		when(im.canonicalize(anyInt())).thenAnswer(a -> a.getArgument(0));
		when(im.getItemPrice(anyInt())).thenAnswer(a -> ((Integer) a.getArgument(0)) * 100L);
		return im;
	}

	public static ItemComposition composition(int id) {
		ItemComposition c = mock(ItemComposition.class);
		when(c.getId()).thenReturn(id);
		when(c.getName()).thenReturn("Item " + id);
		when(c.getMembersName()).thenReturn("Item " + id);
		when(c.getNote()).thenReturn(-1);
		when(c.getLinkedNoteId()).thenReturn(-1);
		when(c.isTradeable()).thenReturn(true);
		when(c.getPrice()).thenReturn(id);
		return c;
	}

	/** A 36x32 item picture whose colours come from the item id, named in the drawing log. */
	public AsyncBufferedImage itemImage(int id, int quantity, boolean stackable) {
		AsyncBufferedImage img = new AsyncBufferedImage(clientThread, 36, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(Color.HSBtoRGB((id * 0.618034f) % 1f, 0.6f, 0.9f)));
		g.fillRect(4, 2, 28, 28);
		g.setColor(Color.BLACK);
		g.drawRect(4, 2, 27, 27);
		g.dispose();
		img.loaded();
		names.put(img, "item:" + id + (quantity != 1 ? "x" + quantity : "") + (stackable ? "s" : ""));
		return img;
	}

	/** Names an image a test made itself (a sprite, a skill icon...). */
	public <T extends Image> T named(T image, String name) {
		names.put(image, name);
		return image;
	}

	/** A plain test image of the given size and colour, named in the drawing log. */
	public BufferedImage image(String name, int w, int h, Color color) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, w, h);
		g.dispose();
		return named(img, name);
	}

	String name(Image image) {
		String n = names.get(image);
		if (n != null) {
			return n;
		}
		if (image instanceof BufferedImage) {
			BufferedImage b = (BufferedImage) image;
			CRC32 crc = new CRC32();
			for (int y = 0; y < b.getHeight(); y++) {
				for (int x = 0; x < b.getWidth(); x++) {
					int p = b.getRGB(x, y);
					crc.update(p >>> 24);
					crc.update(p >>> 16);
					crc.update(p >>> 8);
					crc.update(p);
				}
			}
			return "img:" + b.getWidth() + "x" + b.getHeight() + ":" + Long.toHexString(crc.getValue());
		}
		return "img:" + image.getClass().getSimpleName();
	}

	/** What one render drew: the log, and the picture. */
	public static final class Rendered {
		public final List<String> log;
		public final BufferedImage image;

		public Rendered(List<String> log, BufferedImage image) {
			this.log = log;
			this.image = image;
		}
	}

	private Graphics2D canvas(BufferedImage img) {
		Graphics2D g = img.createGraphics();
		// What RuneLite's overlay renderer gives an overlay: the RuneScape font.
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(Color.WHITE);
		return g;
	}

	/**
	 * Renders an overlay on a w x h canvas, as the overlay renderer calls it. The first frame is drawn and
	 * thrown away: panels size their background from the frame before, as in the running client.
	 */
	public Rendered render(Overlay overlay, int w, int h) {
		return guarded(() -> {
			Graphics2D warm = canvas(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB));
			overlay.render(warm);
			warm.dispose();
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			List<String> log = new ArrayList<>();
			Graphics2D g = canvas(img);
			RecordingGraphics rg = new RecordingGraphics(g, log, this::name);
			Dimension d = overlay.render(rg);
			log.add("=> " + (d == null ? "null" : d.width + "x" + d.height));
			rg.dispose();
			return new Rendered(log, img);
		});
	}

	/**
	 * Runs a render with the plugin's {@link Guard} watched: it swallows errors in rendering (the UI then
	 * draws nothing), which must fail the test instead of being recorded as an empty golden.
	 */
	static <T> T guarded(java.util.function.Supplier<T> render) {
		Guard guard = Guard.shared();
		guard.resetBreakers();
		List<Throwable> failures = new ArrayList<>();
		guard.setListener(new Guard.Listener() {
			@Override
			public void onFailure(String feature, Throwable error) {
				failures.add(error);
			}

			@Override
			public void onFatal(String feature, Throwable error) {
				failures.add(error);
			}

			@Override
			public void onFreeze(String feature, long elapsedMs, StackTraceElement[] stack) {
			}

			@Override
			public void onDisabled(String feature) {
			}
		});
		try {
			T result = render.get();
			if (!failures.isEmpty()) {
				throw new AssertionError("The UI failed while rendering: " + failures.get(0), failures.get(0));
			}
			return result;
		} finally {
			guard.setListener(null);
			guard.resetBreakers();
		}
	}

	/** Lays out and paints a Swing component at the given width (height 0: its preferred height). */
	public Rendered render(JComponent component, int w, int h) {
		return guarded(() -> renderSwing(component, w, h));
	}

	private Rendered renderSwing(JComponent component, int w, int h) {
		component.setSize(w, Math.max(1, h > 0 ? h : component.getPreferredSize().height));
		layout(component);
		if (h <= 0) {
			component.setSize(w, Math.max(1, component.getPreferredSize().height));
			layout(component);
		}
		BufferedImage img = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
		List<String> log = new ArrayList<>();
		Graphics2D g = img.createGraphics();
		RecordingGraphics rg = new RecordingGraphics(g, log, this::name);
		component.paint(rg);
		log.add("=> " + component.getWidth() + "x" + component.getHeight());
		rg.dispose();
		return new Rendered(log, img);
	}

	private static void layout(Component c) {
		if (c instanceof Container) {
			Container k = (Container) c;
			k.doLayout();
			for (Component child : k.getComponents()) {
				layout(child);
			}
		}
	}

	/** Compares a render with its golden file (recording it when there is none yet). */
	public static void golden(String name, Rendered r) {
		Path txt = GOLDEN.resolve(name + ".txt");
		String actual = String.join("\n", r.log) + "\n";
		try {
			if (!Files.exists(txt)) {
				Files.createDirectories(GOLDEN);
				Files.write(txt, actual.getBytes(StandardCharsets.UTF_8));
				ImageIO.write(r.image, "png", GOLDEN.resolve(name + ".png").toFile());
				return;
			}
			String expected = new String(Files.readAllBytes(txt), StandardCharsets.UTF_8).replace("\r\n", "\n");
			if (!expected.equals(actual)) {
				Files.createDirectories(DIFF);
				Files.write(DIFF.resolve(name + ".txt"), actual.getBytes(StandardCharsets.UTF_8));
				ImageIO.write(r.image, "png", DIFF.resolve(name + ".png").toFile());
				fail("UI changed: " + name + " (" + firstDifference(expected, actual) + "); new render in " + DIFF.resolve(name + ".txt"));
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String firstDifference(String expected, String actual) {
		String[] e = expected.split("\n", -1);
		String[] a = actual.split("\n", -1);
		for (int i = 0; i < Math.max(e.length, a.length); i++) {
			String x = i < e.length ? e[i] : "<end>";
			String y = i < a.length ? a[i] : "<end>";
			if (!x.equals(y)) {
				return "line " + (i + 1) + ": expected [" + x + "] but drew [" + y + "]";
			}
		}
		return "same lines";
	}
}
