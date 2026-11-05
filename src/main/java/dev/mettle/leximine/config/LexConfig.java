package dev.mettle.leximine.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.mettle.leximine.Leximine;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Settings, read from {@code config/leximine.json} and editable in game
 * with {@code /leximine set}.
 *
 * <p>Every value can also be overridden at launch with
 * {@code -Dleximine.<name>=}, which wins over the file. That is what the
 * end-to-end tests use to drive a client without editing anyone's config.
 *
 * <p>The file is the single source of truth for language: the server reads it,
 * decides the word, and sends the language along with each challenge, so the
 * client never has to agree with it separately.
 */
public final class LexConfig {
	// disableHtmlEscaping because this file is meant to be read and edited by
	// a person: without it the help text shows "/leximine set <option>".
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();
	private static final String FILE_NAME = "leximine.json";

	private static LexConfig instance;
	private static Path path;

	// --- persisted fields (names are the json keys) ----------------------
	@SuppressWarnings("unused")
	private List<String> _readme = List.of(
			"Leximine settings. Edit here, or change them in game with",
			"/leximine set <option> <value> -- the in-game name is in [brackets]",
			"below where it is shorter than the key. Tab completes all of them.",
			"",
			"language   - a Minecraft language code, e.g. es_es, fr_fr, de_de, ja_jp, ru_ru.",
			"             The backend pulls it from your own Minecraft files the first time.",
			"frequency  - how long a word stays known after you say it RIGHT:",
			"             always, 5m, 10m, 30m, 1h, 6h, 1d, once",
			"difficulty - lenient, normal or strict: how close your pronunciation",
			"             has to be. Start at normal and move if it feels wrong.",
			"askAgainUntilCorrect [untilCorrect] - true means a wrong answer never",
			"             starts that timer, so you keep being asked until you get it right.",
			"timeoutSeconds [timeout] - how long you get to say it.",
			"challengeCrafting [crafting] - also ask when you take something out of",
			"             a crafting grid.",
			"tiers      - scale what you have to say with how valuable the item is:",
			"             wood/stone just the noun, iron/gold the full name,",
			"             diamond/netherite a whole sentence. false = always the noun.",
			"pushToTalk - hold V to speak instead of the mic opening by itself.",
			"playPronunciation [pronunciation] - after a miss, hear the word said properly.",
			"punishment - on or none. A miss always costs the item; on top of",
			"             that the game rolls a 1-in-5 chance of one of five",
			"             penalties drawn from that item's tier, so any single",
			"             penalty is a 4% event. The wheel is shown on screen",
			"             before it lands. 'none' turns the whole lotto off;",
			"             the old values (effects/mob/tnt/both) all read as on.",
			"reviewOnBlocks [review] - doors and chests quiz you on a word you keep missing.",
			"reviewCooldownSeconds [reviewCooldown] - gap between those, so every door",
			"             is not a test.",
			"negotiateTrades [negotiate] - ask a villager for something, in a sentence,",
			"             before they will trade. Judged by a local language model.",
			"endGate [endgate] - every eye of ender must be argued into its frame, at",
			"             tier 4. A miss blocks the placement and rolls the tier-4",
			"             punishment, but never costs the eye.",
			"netherGate [nethergate] - striking obsidian with flint and steel asks",
			"             for one sentence, composed by you and judged by the local",
			"             model: that you are going to the Nether, or why. Overworld",
			"             only -- coming home is never gated.",
			"streak - escalate | off. The counter and the personal best are always",
			"             kept; this is whether breaking a run of 10 or more makes the",
			"             miss roll one tier higher than the item deserved.",
			"streamMode [stream] - draw the live transcript big enough to read in a",
			"             downscaled video. Right for an audience, too loud for a player.");

	private String language = "es_es";
	private String difficulty = "normal";
	private String frequency = "10m";
	private boolean askAgainUntilCorrect = true;
	private int timeoutSeconds = 6;
	private boolean skipCreative = true;
	private boolean grantUnknownItems = true;
	private boolean challengeCrafting = true;
	private boolean tiers = true;
	private String punishment = "mob";
	private boolean reviewOnBlocks = true;
	private int reviewCooldownSeconds = 300;
	private boolean negotiateTrades = true;
	private boolean endGate = true;
	private boolean netherGate = true;
	private String streak = "escalate";
	private boolean pushToTalk = false;
	private boolean playPronunciation = true;
	private boolean streamMode = false;
	private String backendUrl = "ws://127.0.0.1:8760/ws";
	private String microphoneDevice = "";

	// ------------------------------------------------------------------
	public static synchronized LexConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	public static synchronized void load() {
		path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		LexConfig loaded = null;
		if (Files.isRegularFile(path)) {
			try {
				String json = Files.readString(path, StandardCharsets.UTF_8);
				loaded = GSON.fromJson(json, LexConfig.class);
			} catch (IOException | JsonSyntaxException e) {
				Leximine.LOGGER.error("Could not read {}; using defaults", path, e);
			}
		}
		instance = loaded == null ? new LexConfig() : loaded;
		instance.clampToSomethingSensible();
		instance.save();
	}

	private void clampToSomethingSensible() {
		timeoutSeconds = Math.max(2, Math.min(timeoutSeconds, 60));
		if (language == null || language.isBlank()) {
			language = "es_es";
		}
		if (backendUrl == null || backendUrl.isBlank()) {
			backendUrl = "ws://127.0.0.1:8760/ws";
		}
		frequency = Frequency.fromId(frequency, Frequency.TEN_MINUTES).id;
	}

	public synchronized void save() {
		if (path == null) {
			return;
		}
		try {
			Files.createDirectories(path.getParent());
			// Rewrite the help text every save so it tracks the options that
			// actually exist rather than whatever shipped first.
			_readme = new LexConfig()._readme;
			Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Leximine.LOGGER.error("Could not write {}", path, e);
		}
	}

	public static Path file() {
		return path;
	}

	// ------------------------------------------------------------------
	// Reads go through these so a -D override always wins over the file.
	// ------------------------------------------------------------------
	public String language() {
		return property("language", language);
	}

	public Frequency frequency() {
		return Frequency.fromId(property("frequency", frequency), Frequency.TEN_MINUTES);
	}

	/** lenient | normal | strict -- how close the backend needs you to be. */
	public String difficulty() {
		String value = property("difficulty", difficulty).toLowerCase(java.util.Locale.ROOT);
		return switch (value) {
			case "lenient", "strict" -> value;
			default -> "normal";
		};
	}

	public boolean askAgainUntilCorrect() {
		return flag("askAgainUntilCorrect", askAgainUntilCorrect);
	}

	public int timeoutMs() {
		String raw = System.getProperty("leximine.timeoutMs");
		if (raw != null && !raw.isBlank()) {
			try {
				return Integer.parseInt(raw.trim());
			} catch (NumberFormatException ignored) {
				// fall through to the configured value
			}
		}
		return timeoutSeconds * 1000;
	}

	public int timeoutSeconds() {
		return timeoutSeconds;
	}

	public boolean skipCreative() {
		return flag("skipCreative", skipCreative);
	}

	public boolean grantUnknownItems() {
		return flag("grantUnknownItems", grantUnknownItems);
	}

	public boolean challengeCrafting() {
		return flag("challengeCrafting", challengeCrafting);
	}

	/** Scale what has to be said with how valuable the item is. */
	public boolean tiersEnabled() {
		return flag("tiers", tiers);
	}

	/** none | effects | mob | tnt | both — what a miss costs beyond the item. */
	public String punishment() {
		return property("punishment", punishment);
	}

	/** Make doors and chests quiz you on a word you keep missing. */
	public boolean reviewOnBlocks() {
		return flag("reviewOnBlocks", reviewOnBlocks);
	}

	/** Ask a merchant for something, in their language, before they deal. */
	public boolean negotiateTrades() {
		return flag("negotiateTrades", negotiateTrades);
	}

	/** Every eye of ender has to be argued into its frame, at tier 4. */
	public boolean endGate() {
		return flag("endGate", endGate);
	}

	/** One composed sentence before a nether portal will light. Overworld only. */
	public boolean netherGate() {
		return flag("netherGate", netherGate);
	}

	/**
	 * escalate | off — whether a long run makes the next miss worse.
	 *
	 * <p>A string rather than a boolean so there is somewhere to put a second
	 * rule later without another setting: "shield" (a streak absorbs the roll)
	 * is the obvious alternative and the argument between them is not settled.
	 */
	public String streak() {
		return property("streak", streak);
	}

	/** Gap between review prompts, so every door is not an interrogation. */
	public int reviewCooldownSeconds() {
		String raw = System.getProperty("leximine.reviewCooldown");
		if (raw != null && !raw.isBlank()) {
			try {
				return Math.max(0, Integer.parseInt(raw.trim()));
			} catch (NumberFormatException ignored) {
				// fall through to the configured value
			}
		}
		return Math.max(0, reviewCooldownSeconds);
	}

	public boolean pushToTalk() {
		return flag("pushToTalk", pushToTalk);
	}

	public boolean playPronunciation() {
		return flag("playPronunciation", playPronunciation);
	}

	/**
	 * Draw the live transcript large enough to read in a downscaled video.
	 *
	 * <p>Off by default: at this size it dominates the overlay, which is
	 * right for an audience and wrong for a player.
	 */
	public boolean streamMode() {
		return flag("streamMode", streamMode);
	}

	public String backendUrl() {
		return property("backend", backendUrl);
	}

	public String microphoneDevice() {
		return property("micDevice", microphoneDevice);
	}

	// ------------------------------------------------------------------
	public void setLanguage(String value) {
		language = value.trim().toLowerCase(java.util.Locale.ROOT);
		save();
	}

	public void setFrequency(Frequency value) {
		frequency = value.id;
		save();
	}

	public void setDifficulty(String value) {
		difficulty = value.trim().toLowerCase(java.util.Locale.ROOT);
		save();
	}

	public void setTimeoutSeconds(int value) {
		timeoutSeconds = Math.max(2, Math.min(value, 60));
		save();
	}

	public void setAskAgainUntilCorrect(boolean value) {
		askAgainUntilCorrect = value;
		save();
	}

	public void setChallengeCrafting(boolean value) {
		challengeCrafting = value;
		save();
	}

	public void setTiers(boolean value) {
		tiers = value;
		save();
	}

	public void setPunishment(String value) {
		punishment = value.trim().toLowerCase(java.util.Locale.ROOT);
		save();
	}

	public void setReviewOnBlocks(boolean value) {
		reviewOnBlocks = value;
		save();
	}

	public void setNegotiateTrades(boolean value) {
		negotiateTrades = value;
		save();
	}

	public void setEndGate(boolean value) {
		endGate = value;
		save();
	}

	public void setNetherGate(boolean value) {
		netherGate = value;
		save();
	}

	public void setStreak(String value) {
		streak = value;
		save();
	}

	public void setReviewCooldownSeconds(int value) {
		reviewCooldownSeconds = Math.max(0, value);
		save();
	}

	public void setPushToTalk(boolean value) {
		pushToTalk = value;
		save();
	}

	public void setPlayPronunciation(boolean value) {
		playPronunciation = value;
		save();
	}

	public void setStreamMode(boolean value) {
		streamMode = value;
		save();
	}

	// ------------------------------------------------------------------
	/**
	 * True when a {@code -Dleximine.<name>} launch argument is overriding
	 * the file.
	 *
	 * <p>Worth surfacing: an override makes {@code /leximine set} look
	 * broken. The value is written to the file and then ignored, with nothing
	 * on screen to explain why.
	 */
	public static boolean isOverridden(String name) {
		String raw = System.getProperty("leximine." + name);
		return raw != null && !raw.isBlank();
	}

	private static String property(String name, String fallback) {
		String raw = System.getProperty("leximine." + name);
		return raw == null || raw.isBlank() ? fallback : raw.trim();
	}

	private static boolean flag(String name, boolean fallback) {
		String raw = System.getProperty("leximine." + name);
		return raw == null || raw.isBlank() ? fallback : Boolean.parseBoolean(raw.trim());
	}
}
