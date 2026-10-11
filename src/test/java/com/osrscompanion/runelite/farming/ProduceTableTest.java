package com.osrscompanion.runelite.farming;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * The plugin's produce table ({@code produce.txt}) against RuneLite's own Produce, kept here as
 * {@link RuneLiteProduce}: the table is generated from it and every produce has the same values. A missing table
 * is written by this test (after {@code node update.js} copied a new RuneLite version, delete it and run the tests).
 */
public class ProduceTableTest {
	static final Path TABLE = Paths.get("src", "main", "resources", "com", "osrscompanion", "runelite", "farming", Produce.TABLE);

	static String generate() {
		StringBuilder sb = new StringBuilder("# Generated from RuneLite's Produce by ProduceTableTest: NAME, name, contract name, patch type,"
			+ " item, tick rate, stages, regrow tick rate, harvest stages (tab-separated)\n");
		for (RuneLiteProduce p : RuneLiteProduce.values()) {
			sb.append(p.name()).append('\t').append(p.getName()).append('\t').append(p.getContractName()).append('\t')
				.append(p.getPatchImplementation() == null ? "-" : p.getPatchImplementation().name()).append('\t')
				.append(p.getItemID()).append('\t').append(p.getTickrate()).append('\t').append(p.getStages()).append('\t')
				.append(p.getRegrowTickrate()).append('\t').append(p.getHarvestStages()).append('\n');
		}
		return sb.toString();
	}

	@Test
	public void theTableIsGeneratedFromRuneLitesCode() throws IOException {
		String generated = generate();
		if (!Files.exists(TABLE)) {
			Files.createDirectories(TABLE.getParent());
			Files.write(TABLE, generated.getBytes(StandardCharsets.UTF_8));
		}
		String stored = new String(Files.readAllBytes(TABLE), StandardCharsets.UTF_8).replace("\r\n", "\n");
		assertEquals("produce.txt is out of date: delete it and run the tests to generate it again", generated, stored);
	}

	@Test
	public void everyProduceHasRuneLitesValues() {
		assertEquals(RuneLiteProduce.values().length, Produce.values().length);
		for (RuneLiteProduce ref : RuneLiteProduce.values()) {
			Produce p = Produce.valueOf(ref.name());
			String n = ref.name();
			assertEquals(n, ref.ordinal(), p.ordinal());
			assertEquals(n, ref.getName(), p.getName());
			assertEquals(n, ref.getContractName(), p.getContractName());
			assertEquals(n, ref.getPatchImplementation(), p.getPatchImplementation());
			assertEquals(n, ref.getItemID(), p.getItemID());
			assertEquals(n, ref.getTickrate(), p.getTickrate());
			assertEquals(n, ref.getStages(), p.getStages());
			assertEquals(n, ref.getRegrowTickrate(), p.getRegrowTickrate());
			assertEquals(n, ref.getHarvestStages(), p.getHarvestStages());
		}
	}

	@Test
	public void lookupsMatchRuneLite() {
		for (RuneLiteProduce ref : RuneLiteProduce.values()) {
			RuneLiteProduce byItem = RuneLiteProduce.getByItemID(ref.getItemID());
			Produce mine = Produce.getByItemID(ref.getItemID());
			assertEquals(ref.name(), byItem == null ? null : byItem.name(), mine == null ? null : mine.name());
			RuneLiteProduce byContract = RuneLiteProduce.getByContractName(ref.getContractName());
			Produce mineContract = Produce.getByContractName(ref.getContractName());
			assertEquals(ref.name(), byContract == null ? null : byContract.name(), mineContract == null ? null : mineContract.name());
		}
	}
}
