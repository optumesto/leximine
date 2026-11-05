package dev.mettle.leximine.client;

import dev.mettle.leximine.Leximine;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * "This sentence is wrong" — a way for the people who speak the language to
 * say so.
 *
 * <p>The sentences the game asks for at tiers 3 and 4 are built from frames
 * written per language, and outside Spanish they are the work of someone who
 * does not speak that language. They have been checked mechanically and by a
 * model, which catches a wrong particle and cannot catch a sentence that is
 * merely odd. The people who can are the ones playing in that language.
 *
 * <p>So this writes the exact prompt to a file, prints it in chat so it can
 * be screenshotted, and asks for it to be passed on. It is deliberately
 * dumber than it could be: no network, no telemetry, nothing sent anywhere.
 * A line of text in a file the player owns, which they choose to share or
 * not.
 */
public final class PromptReport {
	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/**
	 * Where a report should be sent. Blank until there is a repository, and
	 * the message below adapts rather than printing a dead link — a URL that
	 * 404s is worse than no URL, because the player stops looking.
	 *
	 * <p>One constant on purpose: when the repo exists this is the only edit
	 * needed to close the loop between the N key and someone who can actually
	 * read the sentence.
	 */
	private static final String REPORT_URL = "";

	/** Remembered past the end of the challenge: you judge it after reading it. */
	private static volatile String lastLang = "";
	private static volatile String lastPrompt = "";
	private static volatile String lastEnglish = "";
	private static volatile String lastItem = "";
	private static volatile int lastTier;

	private PromptReport() {
	}

	/** Called whenever a prompt is shown, so there is something to report. */
	public static void remember(ClientChallenge challenge) {
		if (challenge == null || challenge.targetWord().isBlank()) {
			return;
		}
		lastLang = challenge.lang();
		lastPrompt = challenge.targetWord();
		lastEnglish = challenge.englishWord();
		lastItem = challenge.itemName();
		lastTier = challenge.tier();
	}

	public static void reportLast() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (lastPrompt.isBlank()) {
			notify(client, Text.literal("Leximine: nothing to report yet.")
					.formatted(Formatting.GRAY));
			return;
		}

		String line = String.format("%s\t%s\ttier %d\t%s\t%s\t%s",
				LocalDateTime.now().format(STAMP), lastLang, lastTier,
				lastItem, lastPrompt, lastEnglish);

		Path path = FabricLoader.getInstance().getConfigDir()
				.resolve("leximine-reports.txt");
		try {
			if (!Files.exists(path)) {
				Files.writeString(path,
						"# Sentences reported as wrong, newest last.\n"
								+ "# when\tlanguage\ttier\titem\tprompt\tmeaning\n",
						StandardCharsets.UTF_8, StandardOpenOption.CREATE);
			}
			Files.writeString(path, line + System.lineSeparator(),
					StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		} catch (IOException e) {
			// Not fatal, and not worth a scary message: the line is printed
			// below either way, which is the half that actually reaches
			// anyone.
			Leximine.LOGGER.warn("could not write the report file", e);
		}

		Leximine.LOGGER.info("reported as wrong: [{}] {}", lastLang, lastPrompt);
		notify(client, Text.literal("Reported: ").formatted(Formatting.YELLOW)
				.append(Text.literal(lastPrompt).formatted(Formatting.WHITE)));
		String where = REPORT_URL.isBlank()
				? "please send it to the mod author so it can be fixed."
				: "please report it at " + REPORT_URL;
		notify(client, Text.literal("Saved to config/leximine-reports.txt — " + where)
				.formatted(Formatting.GRAY));
	}

	private static void notify(MinecraftClient client, Text text) {
		if (client != null && client.player != null) {
			client.player.sendMessage(text, false);
		}
	}
}
