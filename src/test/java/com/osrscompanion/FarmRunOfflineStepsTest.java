package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.FarmPatchProgress.Status;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.FarmRoute;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The farm run guide moves on in the plugin: a patch planted is read from the game, with the values the website sent. */
public class FarmRunOfflineStepsTest {
	private static final int CATHERBY = 11062;
	private static final int ARDOUGNE = 10548;
	// Herb patch values: 4-7 a growing ranarr (8 grown), as the website's planted ranges say.
	private static final String PLANTED = "[[4,7]]";

	private final Gson gson = new Gson();
	private final List<Map<?, ?>> sent = new ArrayList<>();
	private Client client;
	private FarmRunManager farmRun;
	private int[] regions = {CATHERBY};
	private int herbValue = 3;
	private int flowerValue = 3;

	@Before
	public void setUp() throws Exception {
		client = mock(Client.class);
		when(client.getMapRegions()).thenAnswer(i -> regions);
		when(client.getVarbitValue(anyInt())).thenAnswer(i -> (int) i.getArgument(0) == 4774 ? herbValue : flowerValue);
		OSRSCompanionApiClient api = mock(OSRSCompanionApiClient.class);
		doAnswer(i -> {
			sent.add(gson.fromJson((String) i.getArgument(0), Map.class));
			// The website unreachable: the guide moves on anyway.
			((OSRSCompanionApiClient.RoadmapCallback<?>) i.getArgument(2)).onError("offline");
			return null;
		}).when(api).postFarmRun(anyString(), eq(FarmRunManager.State.class), any());
		RoadmapManager roadmap = mock(RoadmapManager.class);
		when(roadmap.getPlayerUsername()).thenReturn("Tester");
		farmRun = new FarmRunManager(client, api, roadmap, new TestOSRSCompanionConfig(), null, null, null, mock(ConfigManager.class), gson);
		FarmRoute route = gson.fromJson("{\"id\":7,\"name\":\"Herb run\",\"stops\":["
			+ "{\"name\":\"Catherby\",\"patches\":[{\"key\":\"cat-herb\",\"varbit\":4774,\"impl\":\"HERB\"},{\"key\":\"cat-flower\",\"varbit\":4773,\"impl\":\"FLOWER\"}]},"
			+ "{\"name\":\"Ardougne\",\"patches\":[{\"key\":\"ard-herb\",\"varbit\":4774,\"impl\":\"HERB\"}]}]}", FarmRoute.class);
		FarmRunManager.State state = gson.fromJson("{\"route_id\":7,\"running\":true,\"stop_index\":0,\"patches\":{"
			+ "\"cat-herb\":{\"status\":\"TODO\",\"planted\":" + PLANTED + ",\"region\":" + CATHERBY + "},"
			+ "\"cat-flower\":{\"status\":\"TODO\",\"planted\":[[8,11]],\"region\":" + CATHERBY + "},"
			+ "\"ard-herb\":{\"status\":\"TODO\",\"planted\":" + PLANTED + ",\"region\":" + ARDOUGNE + "}}}", FarmRunManager.State.class);
		set("route", route);
		set("state", state);
		set("running", true);
	}

	private void set(String name, Object value) throws Exception {
		Field f = FarmRunManager.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(farmRun, value);
	}

	private void tick() throws Exception {
		Method m = FarmRunManager.class.getDeclaredMethod("checkPlanted");
		m.setAccessible(true);
		m.invoke(farmRun);
	}

	private Status status(int stop, int patch) {
		return farmRun.statusOf(farmRun.getRoute().getStops().get(stop).getPatches().get(patch));
	}

	@Test
	public void movesOnOnceTheStopIsPlantedAndTellsTheWebsite() throws Exception {
		tick();
		assertEquals(Status.TODO, status(0, 0));
		assertTrue(sent.isEmpty());

		// The herb planted: done at once, the flower still to do, so the guide stays.
		herbValue = 5;
		tick();
		assertEquals(Status.DONE, status(0, 0));
		assertEquals(0, farmRun.getStopIndex());
		assertEquals(List.of("cat-herb"), sent.get(0).get("done"));

		// The flower planted: on to Ardougne, without an answer from the website.
		flowerValue = 9;
		tick();
		assertEquals(1, farmRun.getStopIndex());
		assertEquals(Set.of("cat-herb", "cat-flower"), new HashSet<>((List<?>) sent.get(sent.size() - 1).get("done")));
	}

	@Test
	public void onlyWhereThePatchIsAndOnlyAPlantedValue() throws Exception {
		// Grown (8) is not planted; Ardougne's herb is not read in Catherby, even with the same varbit.
		herbValue = 8;
		tick();
		assertEquals(Status.TODO, status(0, 0));
		herbValue = 5;
		regions = new int[]{12345};
		tick();
		assertEquals(Status.TODO, status(0, 0));
		regions = new int[]{CATHERBY};
		tick();
		assertEquals(Status.DONE, status(0, 0));
		assertEquals(Status.TODO, status(1, 0));
		assertFalse(farmRun.isComplete());
	}
}
