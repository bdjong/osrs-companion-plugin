package com.osrscompanion;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.util.ImageUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * In-memory cache of goal artwork (OSRS Wiki images for bosses, combat tasks
 * and the like), scaled to overlay icon size. Only images on the hardcoded
 * {@link #WIKI_HOST} are fetched; any other URL the server sends is ignored,
 * so the plugin never contacts a host taken from a server response. Images are
 * fetched once per URL, asynchronously; until one arrives
 * {@link #get} returns null and the overlay simply draws no icon. Failed URLs
 * are not retried for the rest of the session.
 */
@Singleton
public class GoalImageCache {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "goal-images";

	/** Icons are scaled to fit this square, matching RuneLite's item sprites. */
	static final int ICON_SIZE = 32;
	private static final int FETCH_TIMEOUT_SECONDS = 10;
	/** At most this many icons (and failed URLs) are kept, so a long session's memory stays bounded. */
	static final int MAX_IMAGES = 512;
	/** At most this many fetches at once. */
	static final int MAX_PENDING = 16;
	/** The only host goal images are fetched from. */
	static final String WIKI_HOST = "oldschool.runescape.wiki";
	private static final String WIKI_IMAGE_PATH = "/images/";

	private final OkHttpClient httpClient;

	private final Map<String, BufferedImage> images = new ConcurrentHashMap<>();
	private final Set<String> pending = ConcurrentHashMap.newKeySet();
	private final Set<String> failed = ConcurrentHashMap.newKeySet();

	@Inject
	public GoalImageCache(OkHttpClient okHttpClient) {
		this.httpClient = okHttpClient.newBuilder()
				.connectTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.build();
	}

	/**
	 * The scaled image for a URL if it has been loaded; otherwise starts loading
	 * it (once) and returns null. Safe to call every render frame.
	 */
	public BufferedImage get(String url) {
		if (!isAllowed(url)) {
			return null;
		}
		BufferedImage cached = images.get(url);
		if (cached != null) {
			return cached;
		}
		if (images.size() >= MAX_IMAGES || failed.size() >= MAX_IMAGES || pending.size() >= MAX_PENDING) {
			return null;
		}
		if (!failed.contains(url) && pending.add(url)) {
			fetch(url);
		}
		return null;
	}

	/** True when the URL is an https image on the OSRS Wiki. */
	static boolean isAllowed(String url) {
		HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
		return parsed != null
				&& parsed.isHttps()
				&& WIKI_HOST.equals(parsed.host())
				&& parsed.port() == 443
				&& parsed.encodedPath().startsWith(WIKI_IMAGE_PATH);
	}

	public void clear() {
		images.clear();
		pending.clear();
		failed.clear();
	}

	/**
	 * Fetches asynchronously (OkHttp's own threads): never blocks the caller, which may be the client
	 * thread rendering, nor RuneLite's shared executor (one thread for every plugin).
	 */
	private void fetch(String url) {
		Request request;
		try {
			request = new Request.Builder().url(url).get().build();
		} catch (RuntimeException e) {
			failed.add(url);
			pending.remove(url);
			return;
		}
		httpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				failed.add(url);
				pending.remove(url);
			}

			@Override
			public void onResponse(Call call, Response response) {
				Guard.run(GUARD_FEATURE, () -> read(url, response));
			}
		});
	}

	private void read(String url, Response response) {
		try (Response r = response) {
			if (!r.isSuccessful() || r.body() == null) {
				failed.add(url);
				return;
			}
			try (InputStream in = r.body().byteStream()) {
				BufferedImage image = ImageIO.read(in);
				if (image == null) {
					failed.add(url);
					return;
				}
				images.put(url, fit(image));
			}
		} catch (IOException | RuntimeException e) {
			failed.add(url);
		} finally {
			pending.remove(url);
		}
	}

	/** Scales the image down to fit the icon square, keeping its aspect ratio. */
	static BufferedImage fit(BufferedImage image) {
		int w = image.getWidth();
		int h = image.getHeight();
		if (w <= ICON_SIZE && h <= ICON_SIZE) {
			return image;
		}
		double scale = Math.min((double) ICON_SIZE / w, (double) ICON_SIZE / h);
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		return ImageUtil.resizeImage(image, nw, nh);
	}
}
