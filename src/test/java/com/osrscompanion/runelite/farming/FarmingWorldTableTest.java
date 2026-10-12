package com.osrscompanion.runelite.farming;

import static org.junit.Assert.assertEquals;

import com.osrscompanion.runelite.Tab;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * The plugin's patch list ({@code farming-patches.txt}) against RuneLite's own FarmingWorld, kept here as
 * {@link RuneLiteFarmingWorld}: the table is generated from it, and every tab lists the same patches (region,
 * region id, name, varbit, type) in the same order. A missing table is written by this test.
 */
public class FarmingWorldTableTest {
	static final Path TABLE = Paths.get("src", "test", "resources", "com", "osrscompanion", "runelite", "farming", FarmingWorld.TABLE);

	static String generate() {
		StringBuilder sb = new StringBuilder("# Generated from RuneLite's FarmingWorld by FarmingWorldTableTest: REGION|REGION_ID|PATCH|VARBIT|TYPE\n");
		for (RuneLiteFarmingWorld.Region r : new RuneLiteFarmingWorld().all) {
			for (RuneLiteFarmingWorld.Patch p : r.getPatches()) {
				sb.append(r.getName()).append('|').append(r.getRegionID()).append('|').append(p.getName()).append('|')
					.append(p.getVarbit()).append('|').append(p.getImplementation().name()).append('\n');
			}
		}
		return sb.toString();
	}

	@Test
	public void theTableIsGeneratedFromRuneLitesCode() throws IOException {
		String generated = generate();
		if (!Files.exists(TABLE)) {
			Files.write(TABLE, generated.getBytes(StandardCharsets.UTF_8));
		}
		String stored = new String(Files.readAllBytes(TABLE), StandardCharsets.UTF_8).replace("\r\n", "\n");
		assertEquals("farming-patches.txt is out of date: delete it and run the tests to generate it again", generated, stored);
	}

	@Test
	public void everyTabListsTheSamePatchesAsRuneLite() {
		Map<Tab, Set<RuneLiteFarmingWorld.Patch>> reference = new RuneLiteFarmingWorld().getTabs();
		Map<Tab, Set<FarmingPatch>> tabs = new FarmingWorld().getTabs();
		assertEquals(reference.keySet(), tabs.keySet());
		for (Tab tab : reference.keySet()) {
			List<String> expected = new ArrayList<>();
			for (RuneLiteFarmingWorld.Patch p : reference.get(tab)) {
				expected.add(p.getRegion().getName() + "|" + p.getRegion().getRegionID() + "|" + p.getName() + "|" + p.getVarbit() + "|" + p.getImplementation());
			}
			List<String> actual = new ArrayList<>();
			for (FarmingPatch p : tabs.get(tab)) {
				actual.add(p.getRegion().getName() + "|" + p.getRegion().getRegionID() + "|" + p.getName() + "|" + p.getVarbit() + "|" + p.getImplementation());
				assertEquals(p.getRegion().getRegionID() + "." + p.getVarbit(), p.configKey());
			}
			assertEquals(tab.name(), expected, actual);
		}
	}
}
