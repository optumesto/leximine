package dev.mettle.leximine.progress;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.net.LexPackets;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * How many in a row, and the best run so far.
 *
 * <p>The easy words had no tension in them. "Palo" is a single syllable and the
 * lotto only reaches for the tier-1 table, so most of a session is spent on
 * prompts where nothing is really at stake — which is dead air to play through
 * and dead footage to cut. A streak gives those prompts something to lose: the
 * fifteenth stick in a row matters because fourteen came before it.
 *
 * <p>Kept in its own file rather than inside {@code leximine-progress.json}.
 * That file's whole shape is
 * {@code Map<player, Map<language, Map<key, Record>>>} and Gson parses it by
 * that type, so a new top-level section would have to be migrated into a
 * 6 KB vocabulary history that has already survived one rename. A second small
 * file costs nothing and cannot corrupt the first.
 *
 * <p><strong>Not per language</strong>, unlike the vocabulary. Switching from
 * Spanish to French starts a fresh vocabulary because you do not know the
 * French; it should not hand you a fresh personal best, which would make the
 * record meaningless the moment it got hard.
 */
public final class Streaks {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type SHAPE = new TypeToken<Map<String, Record>>() { }.getType();
	private static final String FILE_NAME = "leximine-streaks.json";

	private static final Streaks INSTANCE = new Streaks();

	/** One player's run. Public fields because Gson writes them directly. */
	public static final class Record {
		public int current;
		public int best;
	}

	private Map<String, Record> players = new HashMap<>();
	private Path path;

	private Streaks() {
	}

	public static Streaks get() {
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
			Map<String, Record> loaded =
					GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), SHAPE);
			players = loaded == null ? new HashMap<>() : loaded;
			Leximine.LOGGER.info("loaded streaks for {} player(s)", players.size());
		} catch (IOException | JsonSyntaxException e) {
			Leximine.LOGGER.error("Could not read {}; starting fresh", path, e);
			players = new HashMap<>();
		}
	}

	private synchronized void save() {
		if (path == null) {
			return;
		}
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(players, SHAPE), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Leximine.LOGGER.error("Could not write {}", path, e);
		}
	}

	private Record record(UUID player) {
		return players.computeIfAbsent(player.toString(), key -> new Record());
	}

	// ------------------------------------------------------------------
	public synchronized int current(UUID player) {
		Record row = players.get(player.toString());
		return row == null ? 0 : row.current;
	}

	public synchronized int best(UUID player) {
		Record row = players.get(player.toString());
		return row == null ? 0 : row.best;
	}

	/**
	 * One more in a row.
	 *
	 * @return the new streak length
	 */
	public synchronized int onCorrect(UUID player) {
		Record row = record(player);
		row.current++;
		boolean record = row.current > row.best;
		if (record) {
			row.best = row.current;
		}
		save();
		return row.current;
	}

	/**
	 * Broken.
	 *
	 * <p>Returns what it <em>was</em>, because that is the number the
	 * consequence is priced from and the number worth saying out loud. A caller
	 * that read {@code current()} afterwards would always get zero.
	 *
	 * @return the streak that has just been lost
	 */
	public synchronized int onMiss(UUID player) {
		Record row = record(player);
		int lost = row.current;
		row.current = 0;
		save();
		return lost;
	}

	/** Did that answer set a new personal best? Asked after {@link #onCorrect}. */
	public synchronized boolean isPersonalBest(UUID player) {
		Record row = players.get(player.toString());
		return row != null && row.current > 0 && row.current == row.best;
	}

	// ------------------------------------------------------------------
	// The two call sites every gate with stakes uses. Wrapped as statics so
	// counting, telling the client and telling the player are one call -- three
	// managers each remembering to do three things is three places to forget
	// the third.
	// ------------------------------------------------------------------

	/** Every fifth is worth saying out loud; the rest just move the counter. */
	private static final int SHOUT_EVERY = 5;

	/**
	 * Break a run this long or longer and the miss rolls a tier higher.
	 *
	 * <p>Ten rather than five: five in a row happens by accident on a stretch
	 * of common words, and a penalty that fires most of the time is a tax. Ten
	 * has to be gone for.
	 */
	public static final int ESCALATE_AT = 10;

	/** {@code /lex set streak off} keeps the counter and drops the stake. */
	public static boolean stakeOn() {
		String raw = LexConfig.get().streak();
		String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		return !(value.equals("off") || value.equals("none") || value.equals("false"));
	}

	/**
	 * For the settings listing: the rule, plus where this player actually is.
	 *
	 * <p>Both, because "streak: escalate" tells you the rule and not the thing
	 * you want to know, which is whether you are about to lose anything.
	 */
	public static String describe(ServerPlayerEntity player) {
		String rule = stakeOn()
				? "a run of " + ESCALATE_AT + "+ makes the next miss roll a tier higher"
				: "off (counted, but costs nothing)";
		if (player == null) {
			return rule;
		}
		return INSTANCE.current(player.getUuid()) + " now, best "
				+ INSTANCE.best(player.getUuid()) + " -- " + rule;
	}

	/**
	 * Count a correct answer.
	 *
	 * <p>Only milestones and a new record get a message. A line on every
	 * correct answer would fire on every stick, and the number is already on
	 * the overlay — this is for the moments worth reacting to.
	 */
	public static void award(ServerPlayerEntity player) {
		int run = INSTANCE.onCorrect(player.getUuid());
		LexPackets.sendStreak(player, run, INSTANCE.best(player.getUuid()));

		if (run == ESCALATE_AT && stakeOn()) {
			// Said once, on the way past, and it takes priority over the
			// milestone line it collides with. The one thing the player must
			// not discover by being hit with it: from here a slip costs more
			// than the item was worth. Guarded on the stake actually being on,
			// or turning it off would leave the game threatening something it
			// no longer does.
			say(player, run + " in a row. A miss now rolls a tier higher.",
					Formatting.GOLD);
			return;
		}
		if (run % SHOUT_EVERY != 0) {
			return;
		}
		boolean record = INSTANCE.isPersonalBest(player.getUuid());
		say(player, record ? run + " in a row, best yet." : run + " in a row.",
				record ? Formatting.AQUA : Formatting.GREEN);
	}

	/**
	 * Count a miss, and hand back the run it ended so the caller can price it.
	 *
	 * @return the streak that has just been lost
	 */
	public static int broke(ServerPlayerEntity player) {
		int lost = INSTANCE.onMiss(player.getUuid());
		LexPackets.sendStreak(player, 0, INSTANCE.best(player.getUuid()));
		return lost;
	}

	private static void say(ServerPlayerEntity player, String text, Formatting colour) {
		player.sendMessage(Text.literal(text).formatted(colour), true);
	}

	/**
	 * Put a streak where you want it. Dev command and tests only.
	 *
	 * <p>Does not touch {@code best}: a test that needs an 11-streak to check
	 * the escalation should not thereby write a personal best into the save
	 * file it is borrowing.
	 */
	public synchronized void set(UUID player, int value) {
		record(player).current = Math.max(0, value);
		save();
	}
}
