package dev.mettle.leximine.progress;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.config.Frequency;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What each player has said correctly, and when.
 *
 * <p>This is both the vocabulary list and the cooldown state, because they are
 * the same fact: a word is "known" exactly as long as the frequency setting
 * says a correct answer counts for. Keeping one record means the list can
 * never disagree with what the game actually asks.
 *
 * <p>Progress is per language. Switching from Spanish to French starts a fresh
 * vocabulary rather than pretending you already know the French.
 */
public final class LearnedWords {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type SHAPE =
			new TypeToken<Map<String, Map<String, Map<String, Record>>>>() { }.getType();
	private static final String FILE_NAME = "leximine-progress.json";

	private static final LearnedWords INSTANCE = new LearnedWords();

	/** One word, in one language, for one player. */
	public static final class Record {
		public String word = "";
		public int correct;
		public int wrong;
		public long firstCorrect;
		public long lastCorrect;

		public boolean isLearned() {
			return correct > 0;
		}
	}

	/** A row for the in-game list. */
	public record Listed(String key, Record record) { }

	// player uuid -> language -> translation key -> record
	private Map<String, Map<String, Map<String, Record>>> players = new HashMap<>();
	private Path path;
	private boolean dirty;

	private LearnedWords() {
	}

	public static LearnedWords get() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------
	public synchronized void load() {
		path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		if (!Files.isRegularFile(path)) {
			players = new HashMap<>();
			return;
		}
		try {
			Map<String, Map<String, Map<String, Record>>> loaded =
					GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), SHAPE);
			players = loaded == null ? new HashMap<>() : loaded;
			int words = players.values().stream()
					.flatMap(byLang -> byLang.values().stream())
					.mapToInt(Map::size).sum();
			Leximine.LOGGER.info("loaded vocabulary progress: {} words across {} player(s)",
					words, players.size());
		} catch (IOException | JsonSyntaxException e) {
			Leximine.LOGGER.error("Could not read {}; starting fresh", path, e);
			players = new HashMap<>();
		}
	}

	public synchronized void saveIfDirty() {
		if (!dirty || path == null) {
			return;
		}
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(players, SHAPE), StandardCharsets.UTF_8);
			dirty = false;
		} catch (IOException e) {
			Leximine.LOGGER.error("Could not write {}", path, e);
		}
	}

	// ------------------------------------------------------------------
	private Map<String, Record> table(UUID player, String lang) {
		return players
				.computeIfAbsent(player.toString(), key -> new HashMap<>())
				.computeIfAbsent(lang, key -> new HashMap<>());
	}

	public synchronized Record find(UUID player, String lang, String translationKey) {
		Map<String, Map<String, Record>> byLang = players.get(player.toString());
		if (byLang == null) {
			return null;
		}
		Map<String, Record> words = byLang.get(lang);
		return words == null ? null : words.get(translationKey);
	}

	/**
	 * Should this word be asked right now?
	 *
	 * <p>The whole point of the feature: a word that has never been said
	 * correctly is always due, no matter how recently it was attempted.
	 */
	public synchronized boolean isDue(UUID player, String lang, String translationKey,
			Frequency frequency, boolean askAgainUntilCorrect) {
		Record record = find(player, lang, translationKey);
		if (record == null) {
			return true;
		}
		if (!record.isLearned()) {
			// Attempted and missed. Ask again unless the player has turned
			// that off, in which case a wrong answer buys the same grace as a
			// right one.
			if (askAgainUntilCorrect) {
				return true;
			}
			return frequency.asksAgain()
					&& elapsedSeconds(record.lastCorrect) >= frequency.seconds;
		}
		if (!frequency.asksAgain()) {
			return false;  // "once": learned is learned
		}
		return elapsedSeconds(record.lastCorrect) >= frequency.seconds;
	}

	private static long elapsedSeconds(long since) {
		if (since <= 0) {
			return Long.MAX_VALUE;
		}
		return (System.currentTimeMillis() - since) / 1000L;
	}

	public synchronized void record(UUID player, String lang, String translationKey,
			String word, boolean correct) {
		Record record = table(player, lang)
				.computeIfAbsent(translationKey, key -> new Record());
		record.word = word;
		long now = System.currentTimeMillis();
		if (correct) {
			record.correct++;
			record.lastCorrect = now;
			if (record.firstCorrect == 0L) {
				record.firstCorrect = now;
			}
		} else {
			record.wrong++;
			if (!record.isLearned()) {
				// Keeps "asked recently" meaningful when
				// askAgainUntilCorrect is off.
				record.lastCorrect = now;
			}
		}
		dirty = true;
		saveIfDirty();
	}

	// ------------------------------------------------------------------
	public synchronized List<Listed> learned(UUID player, String lang) {
		return listing(player, lang, true);
	}

	public synchronized List<Listed> attempted(UUID player, String lang) {
		return listing(player, lang, false);
	}

	private List<Listed> listing(UUID player, String lang, boolean onlyLearned) {
		Map<String, Map<String, Record>> byLang = players.get(player.toString());
		if (byLang == null) {
			return List.of();
		}
		Map<String, Record> words = byLang.get(lang);
		if (words == null) {
			return List.of();
		}
		List<Listed> rows = new ArrayList<>();
		for (Map.Entry<String, Record> entry : words.entrySet()) {
			if (onlyLearned && !entry.getValue().isLearned()) {
				continue;
			}
			rows.add(new Listed(entry.getKey(), entry.getValue()));
		}
		// Most recently learned first: that is the interesting end of the list.
		rows.sort(Comparator.comparingLong((Listed row) -> row.record().lastCorrect).reversed());
		return rows;
	}

	public synchronized int forget(UUID player, String lang, String translationKey) {
		Map<String, Map<String, Record>> byLang = players.get(player.toString());
		if (byLang == null) {
			return 0;
		}
		int removed;
		if (translationKey == null) {
			Map<String, Record> words = byLang.remove(lang);
			removed = words == null ? 0 : words.size();
		} else {
			Map<String, Record> words = byLang.get(lang);
			removed = words != null && words.remove(translationKey) != null ? 1 : 0;
		}
		if (removed > 0) {
			dirty = true;
			saveIfDirty();
		}
		return removed;
	}
}
