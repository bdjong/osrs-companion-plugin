package com.osrscompanion;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * The Plugin Hub only reviews plugins whose src/main/java is under 200,000 tokens, counted with the
 * comments stripped (tests and resources do not count). This estimates that count, so a change that
 * would make the plugin too big to review fails here first.
 *
 * <p>The estimate counts BPE-like pieces (words, camelCase parts, short number groups, symbols,
 * whitespace runs), scaled by {@link #TOKENS_PER_PIECE}: measured on version 1.0.0 with every
 * feature (497,964 pieces; the hub counted 351,624 tokens). Run it alone to see the number:
 * {@code ./gradlew test --tests com.osrscompanion.PluginSizeTest -i}.
 */
public class PluginSizeTest {
	static final int HUB_LIMIT = 200_000;
	/** Room kept below the limit for the estimate's error. */
	static final int MAX_ESTIMATE = 197_500;
	/**
	 * Off while the plugin is reworked (logic moves to the website; the full plugin is about 352k): the size
	 * is printed but does not fail the build. Turn it on again before any Plugin Hub pull request.
	 */
	static final boolean ENFORCE = true;
	static final double TOKENS_PER_PIECE = 351_624.0 / 497_964.0;

	private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
	private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(?<![:\"])//.*$");
	private static final Pattern PIECE = Pattern.compile("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\\d{1,3}|\\s+|[^\\w\\s]");

	@Test
	public void staysReviewableOnThePluginHub() throws IOException {
		long estimate = estimateTokens(Paths.get("src", "main", "java"));
		System.out.println("Plugin Hub review size: about " + estimate + " of " + HUB_LIMIT + " tokens");
		if (!ENFORCE) {
			return;
		}
		assertTrue("src/main/java is about " + estimate + " tokens; the Plugin Hub reviews at most " + HUB_LIMIT
				+ " (this test fails above " + MAX_ESTIMATE + ")", estimate <= MAX_ESTIMATE);
	}

	static long estimateTokens(Path root) throws IOException {
		long pieces = 0;
		List<Path> files;
		try (Stream<Path> walk = Files.walk(root)) {
			files = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
		}
		for (Path file : files) {
			pieces += pieces(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
		}
		return Math.round(pieces * TOKENS_PER_PIECE);
	}

	static long pieces(String source) {
		String code = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
		Matcher m = PIECE.matcher(code);
		long n = 0;
		while (m.find()) {
			n++;
		}
		return n;
	}
}
