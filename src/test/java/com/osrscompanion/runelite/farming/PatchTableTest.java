package com.osrscompanion.runelite.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * The plugin's patch table ({@code patch-states.txt}) against RuneLite's own PatchImplementation, kept here
 * as {@link RuneLitePatchImplementation}: the table is generated from it, and every patch type decodes every
 * varbit value exactly the same. A missing table is written by this test (after {@code node update.js}
 * copied a new RuneLite version, delete the table and run the tests).
 */
public class PatchTableTest {
	static final Path TABLE = Paths.get("src", "test", "resources", "com", "osrscompanion", "runelite", "farming", PatchImplementation.TABLE);

	/** The table from RuneLite's code: runs of values with the same produce and crop state and a stage moving by a fixed step. */
	static String generate() {
		StringBuilder sb = new StringBuilder("# Generated from RuneLite's PatchImplementation by PatchTableTest: TYPE FROM TO PRODUCE CROP_STATE STAGE STEP\n");
		for (RuneLitePatchImplementation impl : RuneLitePatchImplementation.values()) {
			int v = 0;
			while (v < PatchImplementation.VALUES) {
				PatchState first = impl.forVarbitValue(v);
				if (first == null) {
					v++;
					continue;
				}
				int from = v;
				int step = 0;
				PatchState second = v + 1 < PatchImplementation.VALUES ? impl.forVarbitValue(v + 1) : null;
				if (sameKind(first, second)) {
					step = second.getStage() - first.getStage();
				}
				int to = v;
				while (to + 1 < PatchImplementation.VALUES) {
					PatchState next = impl.forVarbitValue(to + 1);
					if (!sameKind(first, next) || next.getStage() != first.getStage() + step * (to + 1 - from)) {
						break;
					}
					to++;
				}
				sb.append(impl.name()).append(' ').append(from).append(' ').append(to).append(' ').append(first.getProduce().name()).append(' ')
					.append(first.getCropState().name()).append(' ').append(first.getStage()).append(' ').append(step).append('\n');
				v = to + 1;
			}
		}
		return sb.toString();
	}

	private static boolean sameKind(PatchState a, PatchState b) {
		return b != null && a.getProduce() == b.getProduce() && a.getCropState() == b.getCropState();
	}

	@Test
	public void theTableIsGeneratedFromRuneLitesCode() throws IOException {
		String generated = generate();
		if (!Files.exists(TABLE)) {
			Files.createDirectories(TABLE.getParent());
			Files.write(TABLE, generated.getBytes(StandardCharsets.UTF_8));
		}
		String stored = new String(Files.readAllBytes(TABLE), StandardCharsets.UTF_8).replace("\r\n", "\n");
		assertEquals("patch-states.txt is out of date: delete it and run the tests to generate it again", generated, stored);
	}

	@Test
	public void everyPatchTypeDecodesEveryValueLikeRuneLite() {
		for (RuneLitePatchImplementation reference : RuneLitePatchImplementation.values()) {
			PatchImplementation impl = PatchImplementation.valueOf(reference.name());
			for (int v = -5; v <= 1100; v++) {
				assertEquals(reference.name() + " value " + v, reference.forVarbitValue(v), impl.forVarbitValue(v));
			}
		}
	}

	@Test
	public void runeLiteHasNothingOutsideTheTablesRange() {
		for (RuneLitePatchImplementation reference : RuneLitePatchImplementation.values()) {
			for (int v = PatchImplementation.VALUES; v <= 70_000; v += v < 1100 ? 1 : 97) {
				assertNull(reference.name() + " value " + v, reference.forVarbitValue(v));
			}
		}
	}

	@Test
	public void samePatchTypesAndProperties() {
		assertEquals(RuneLitePatchImplementation.values().length, PatchImplementation.values().length);
		for (RuneLitePatchImplementation reference : RuneLitePatchImplementation.values()) {
			PatchImplementation impl = PatchImplementation.valueOf(reference.name());
			assertEquals(reference.name(), reference.ordinal(), impl.ordinal());
			assertEquals(reference.name(), reference.getTab(), impl.getTab());
			assertEquals(reference.name(), reference.getName(), impl.getName());
			assertEquals(reference.name(), reference.isHealthCheckRequired(), impl.isHealthCheckRequired());
		}
	}
}
