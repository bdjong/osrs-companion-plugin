package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.BossAreasResponse;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Keeps the boss lairs in step with the website (GET /api/runelite/boss-areas/): its list (the same data as
 * {@link BossAreas} with the admins' fixes) replaces the built-in regions of the bosses it lists, so a wrong
 * or missing lair region is fixed without a plugin release. Asked at most every {@link #REFRESH_MS} with the
 * version in use (the website answers "unchanged" while it is current), and kept in the config so the next
 * start uses it before the website answers. Without an answer the built-in list stays.
 */
@Singleton
public class BossAreasSync {
	static final String CACHE_KEY = "bossAreasServer";
	static final long REFRESH_MS = 6 * 3_600_000L;
	/** After a failed request. */
	static final long RETRY_MS = 15 * 60_000L;

	private final OSRSCompanionConfig config;
	private final ConfigManager configManager;
	private final OSRSCompanionApiClient apiClient;
	private final Gson gson;

	private boolean loaded;
	private volatile boolean inFlight;
	private volatile long nextFetchMs;

	@Inject
	public BossAreasSync(OSRSCompanionConfig config, ConfigManager configManager, OSRSCompanionApiClient apiClient, Gson gson) {
		this.config = config;
		this.configManager = configManager;
		this.apiClient = apiClient;
		this.gson = gson;
	}

	/** Called on the boss history's ticks: the stored list once, then a request when one is due. */
	void tick(long nowMs) {
		if (!loaded) {
			loaded = true;
			loadCached();
		}
		if (inFlight || nowMs < nextFetchMs || !SyncGuard.hasAppKey(config)) {
			return;
		}
		inFlight = true;
		apiClient.getBossAreas(BossAreas.serverVersion(), new OSRSCompanionApiClient.RoadmapCallback<BossAreasResponse>() {
			@Override
			public void onSuccess(BossAreasResponse r) {
				inFlight = false;
				nextFetchMs = System.currentTimeMillis() + REFRESH_MS;
				if (r.isUnchanged()) {
					return;
				}
				if (apply(r)) {
					store(r);
				}
			}

			@Override
			public void onError(String message) {
				inFlight = false;
				nextFetchMs = System.currentTimeMillis() + RETRY_MS;
			}
		});
	}

	/** Uses a website answer's lairs; false when it has none (the current list stays). */
	static boolean apply(BossAreasResponse r) {
		if (r == null || r.isUnchanged() || r.lairs().isEmpty()) {
			return false;
		}
		BossAreas.setServer(r.getVersion(), r.lairs());
		return BossAreas.fromServer();
	}

	private void loadCached() {
		try {
			String json = configManager.getConfiguration(OSRSCompanionConfig.CONFIG_GROUP, CACHE_KEY);
			if (json != null && !BossAreas.fromServer()) {
				apply(gson.fromJson(json, BossAreasResponse.class));
			}
		} catch (RuntimeException e) {
			configManager.unsetConfiguration(OSRSCompanionConfig.CONFIG_GROUP, CACHE_KEY);
		}
	}

	private void store(BossAreasResponse r) {
		try {
			configManager.setConfiguration(OSRSCompanionConfig.CONFIG_GROUP, CACHE_KEY, gson.toJson(r));
		} catch (RuntimeException e) {
		}
	}
}
