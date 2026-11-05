package dev.mettle.leximine;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.mettle.leximine.config.Frequency;
import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.config.ItemTiers;
import dev.mettle.leximine.end.EndGateManager;
import dev.mettle.leximine.limbo.LimboManager;
import dev.mettle.leximine.limbo.Penalty;
import dev.mettle.leximine.limbo.Punishments;
import dev.mettle.leximine.nether.NetherGateManager;
import dev.mettle.leximine.net.LexPackets;
import dev.mettle.leximine.progress.LearnedWords;
import dev.mettle.leximine.progress.Streaks;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * {@code /leximine} -- settings, vocabulary list, and a way to raise a
 * challenge on demand.
 *
 * <p>Only {@code test} needs permission level 2, because a passed challenge
 * grants the item and the command would otherwise be an item duplicator.
 * Reading your own word list and changing your own difficulty are not cheats,
 * so they work in an ordinary world with cheats off.
 */
public final class LexCommand {
	private static final int WORDS_PER_PAGE = 12;

	private LexCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			com.mojang.brigadier.tree.LiteralCommandNode<ServerCommandSource> root =
					dispatcher.register(CommandManager.literal("leximine")
						.executes(LexCommand::settings)

						.then(CommandManager.literal("settings")
								.executes(LexCommand::settings))

						.then(CommandManager.literal("words")
								.executes(context -> words(context, 1, false))
								.then(CommandManager.argument("page", IntegerArgumentType.integer(1))
										.executes(context -> words(context,
												IntegerArgumentType.getInteger(context, "page"), false)))
								.then(CommandManager.literal("all")
										.executes(context -> words(context, 1, true))
										.then(CommandManager.argument("page", IntegerArgumentType.integer(1))
												.executes(context -> words(context,
														IntegerArgumentType.getInteger(context, "page"), true)))))

						.then(CommandManager.literal("set")
								.then(CommandManager.literal("language")
										.then(CommandManager.argument("code", StringArgumentType.word())
												.suggests((context, builder) -> CommandSource.suggestMatching(
														List.of("es_es", "fr_fr", "de_de", "it_it", "pt_br",
																"ja_jp", "ko_kr", "ru_ru", "zh_cn", "nl_nl"),
														builder))
												.executes(LexCommand::setLanguage)))
								.then(CommandManager.literal("difficulty")
										.then(CommandManager.argument("level", StringArgumentType.word())
												.suggests((context, builder) -> CommandSource.suggestMatching(
														List.of("lenient", "normal", "strict"), builder))
												.executes(LexCommand::setDifficulty)))
								.then(CommandManager.literal("frequency")
										.then(CommandManager.argument("preset", StringArgumentType.word())
												.suggests((context, builder) -> CommandSource.suggestMatching(
														Frequency.ids(), builder))
												.executes(LexCommand::setFrequency)))
								.then(CommandManager.literal("timeout")
										.then(CommandManager.argument("seconds", IntegerArgumentType.integer(2, 60))
												.executes(LexCommand::setTimeout)))
								.then(CommandManager.literal("punishment")
										.then(CommandManager.argument("kind", StringArgumentType.word())
												// on/off now. The old values are still
												// accepted so an existing config and
												// anyone's muscle memory keep working.
												.suggests((context, builder) -> CommandSource.suggestMatching(
														List.of("on", "none"), builder))
												.executes(LexCommand::setPunishment)))
								.then(CommandManager.literal("tiers")
										.then(CommandManager.argument("enabled", BoolArgumentType.bool())
												.executes(LexCommand::setTiers)))
								.then(CommandManager.literal("crafting")
										.then(CommandManager.argument("enabled", BoolArgumentType.bool())
												.executes(LexCommand::setCrafting)))
								.then(CommandManager.literal("untilCorrect")
										.then(CommandManager.argument("enabled", BoolArgumentType.bool())
												.executes(LexCommand::setUntilCorrect)))
								.then(CommandManager.literal("pushToTalk")
										.then(CommandManager.argument("enabled", BoolArgumentType.bool())
												.executes(LexCommand::setPushToTalk)))
								.then(CommandManager.literal("stream")
										.then(CommandManager.argument("enabled", BoolArgumentType.bool())
												.executes(LexCommand::setStreamMode)))
								.then(CommandManager.literal("pronunciation")
										.then(CommandManager.argument("enabled", BoolArgumentType.bool())
												.executes(LexCommand::setPronunciation)))
								.then(CommandManager.literal("review")
										.then(CommandManager.argument("enabled", BoolArgumentType.bool())
												.executes(LexCommand::setReview)))
								.then(CommandManager.literal("reviewCooldown")
										.then(CommandManager.argument("seconds",
														IntegerArgumentType.integer(0, 86400))
												.executes(LexCommand::setReviewCooldown)))
								.then(CommandManager.literal("negotiate")
										.then(CommandManager.argument("enabled", BoolArgumentType.bool())
												.executes(LexCommand::setNegotiate)))
								.then(CommandManager.literal("endgate")
										.then(CommandManager.argument("enabled", BoolArgumentType.bool())
												.executes(LexCommand::setEndGate)))
								.then(CommandManager.literal("nethergate")
										.then(CommandManager.argument("enabled", BoolArgumentType.bool())
												.executes(LexCommand::setNetherGate)))
								.then(CommandManager.literal("streak")
										.then(CommandManager.argument("mode", StringArgumentType.word())
												.suggests((context, builder) -> CommandSource.suggestMatching(
														List.of("escalate", "off"), builder))
												.executes(LexCommand::setStreakMode))))

						.then(CommandManager.literal("forget")
								.then(CommandManager.literal("all")
										.executes(LexCommand::forgetAll)))

						// Development only: drives the same take-it-back path
						// crafting uses, which a click cannot be scripted into.
						// Never registered in a shipped game.
						.then(CommandManager.literal("confiscate")
								.requires(source -> source.hasPermissionLevel(2)
										&& FabricLoader.getInstance().isDevelopmentEnvironment())
								.then(CommandManager.argument("item",
												ItemStackArgumentType.itemStack(registryAccess))
										.then(CommandManager.argument("count",
														IntegerArgumentType.integer(1, 64))
												.executes(LexCommand::confiscate))))

						// Development only: performs a real slot click on the
						// crafting result, which is the only way to exercise
						// the crafting mixin without a human at a mouse.
						.then(CommandManager.literal("testcraft")
								.requires(source -> source.hasPermissionLevel(2)
										&& FabricLoader.getInstance().isDevelopmentEnvironment())
								.then(CommandManager.argument("mode", StringArgumentType.word())
										.suggests((context, builder) -> CommandSource.suggestMatching(
												List.of("quick", "pickup"), builder))
										.executes(LexCommand::testCraft)))

						// Development only: opens a real smithing table and
						// takes a real netherite upgrade out of it, which is
						// the only way to exercise the smithing mixin without
						// a human at a mouse.
						.then(CommandManager.literal("testsmith")
								.requires(source -> source.hasPermissionLevel(2)
										&& FabricLoader.getInstance().isDevelopmentEnvironment())
								.then(CommandManager.argument("mode", StringArgumentType.word())
										.suggests((context, builder) -> CommandSource.suggestMatching(
												List.of("quick", "pickup", "trim"), builder))
										.executes(LexCommand::testSmith)))

						// Development only: builds an end portal frame and
						// right-clicks it with an eye, so the gate is reached
						// through the same UseBlockCallback a real placement
						// raises. Finding a stronghold is not scriptable.
						// Development only: put the run counter where a test
						// needs it. Getting to 10 in a row honestly means ten
						// spoken answers per assertion.
						.then(CommandManager.literal("setstreak")
								.requires(source -> source.hasPermissionLevel(2)
										&& FabricLoader.getInstance().isDevelopmentEnvironment())
								.then(CommandManager.argument("count",
												IntegerArgumentType.integer(0, 999))
										.executes(LexCommand::setStreakCount)))

						// Development only: strike an obsidian block, through
						// the same UseBlockCallback a real strike raises.
						.then(CommandManager.literal("testnethergate")
								.requires(source -> source.hasPermissionLevel(2)
										&& FabricLoader.getInstance().isDevelopmentEnvironment())
								.executes(LexCommand::testNetherGate)
								.then(CommandManager.argument("answer", StringArgumentType.word())
										.suggests((context, builder) -> CommandSource.suggestMatching(
												List.of("offer", "pass", "fail"), builder))
										.executes(LexCommand::testNetherGate)))

						.then(CommandManager.literal("testendgate")
								.requires(source -> source.hasPermissionLevel(2)
										&& FabricLoader.getInstance().isDevelopmentEnvironment())
								.executes(LexCommand::testEndGate)
								.then(CommandManager.argument("answer", StringArgumentType.word())
										.suggests((context, builder) -> CommandSource.suggestMatching(
												List.of("offer", "pass", "fail"), builder))
										.executes(LexCommand::testEndGate)))

						// Development only: places a chest and genuinely uses
						// it, so the review path is exercised through the same
						// event a right-click would raise.
						.then(CommandManager.literal("testreview")
								.requires(source -> source.hasPermissionLevel(2)
										&& FabricLoader.getInstance().isDevelopmentEnvironment())
								.executes(LexCommand::testReview))

						// Development only: right-clicks the nearest merchant
						// for the player, through the packet handler a real
						// click lands on.
						.then(CommandManager.literal("testtrade")
								.requires(source -> source.hasPermissionLevel(2)
										&& FabricLoader.getInstance().isDevelopmentEnvironment())
								.executes(context -> testTrade(context, false, false))
								// `new` always spawns another merchant and clicks
								// THAT one. Standing with a merchant is held per
								// merchant, so the thing most worth testing is
								// whether a second trader is still gated after
								// the first one has been won over -- and the
								// plain form, which takes the closest, cannot
								// address a second villager standing next to the
								// first.
								.then(CommandManager.literal("new")
										.executes(context -> testTrade(context, true, false)))
								// Three things on sale, so "which item it asks
								// for" is observable at all. With a one-offer
								// merchant the rule that a refusal does not
								// reroll the demand cannot fail a test.
								.then(CommandManager.literal("multi")
										.executes(context -> testTrade(context, true, true))))

						// Puppet the lotto. Cheats-only rather than dev-only:
						// the point of this is recording footage in a real
						// installed profile, and a 10% payload is not something
						// you can wait for with a capture card running.
						.then(CommandManager.literal("force")
								.requires(source -> source.hasPermissionLevel(2))
								.then(CommandManager.argument("tier",
												IntegerArgumentType.integer(1, 4))
										.then(CommandManager.argument("id",
														IntegerArgumentType.integer(1, 5))
												.executes(LexCommand::forcePenalty))))

						// Load the next genuine miss and then get out of the way.
						// Cheats-only rather than dev-only for the same reason
						// as force: this is for recording in a real profile.
						.then(CommandManager.literal("rig")
								.requires(source -> source.hasPermissionLevel(2))
								.executes(LexCommand::rigStatus)
								.then(CommandManager.argument("penalty", StringArgumentType.word())
										.suggests((context, builder) -> {
											for (Penalty penalty : Penalty.values()) {
												builder.suggest(penalty.name());
											}
											builder.suggest("SAFE");
											builder.suggest("off");
											return builder.buildFuture();
										})
										.executes(LexCommand::rig)))

						// The same thing by name, for when you know what you
						// want: /leximine testlotto T3_THE_PANIC_ROOM
						.then(CommandManager.literal("testlotto")
								.requires(source -> source.hasPermissionLevel(2))
								.then(CommandManager.argument("penalty", StringArgumentType.word())
										.suggests((context, builder) -> {
											for (Penalty penalty : Penalty.values()) {
												builder.suggest(penalty.name());
											}
											builder.suggest("SAFE");
											return builder.buildFuture();
										})
										.executes(LexCommand::testLotto)))

						.then(CommandManager.literal("test")
								.requires(source -> source.hasPermissionLevel(2))
								.executes(context -> test(context, new ItemStack(Items.DIAMOND)))
								.then(CommandManager.argument("item",
												ItemStackArgumentType.itemStack(registryAccess))
										.executes(context -> test(context,
												ItemStackArgumentType.getItemStackArgument(context, "item")
														.createStack(1, false))))));

			// /lex does everything /leximine does. Twelve characters is
			// a lot to type between takes.
			dispatcher.register(CommandManager.literal("lex")
					.executes(LexCommand::settings)
					.redirect(root));
		});
	}

	// ------------------------------------------------------------------
	private static int settings(CommandContext<ServerCommandSource> context) {
		LexConfig config = LexConfig.get();
		ServerCommandSource source = context.getSource();

		source.sendFeedback(() -> Text.literal("Leximine settings")
				.formatted(Formatting.AQUA, Formatting.BOLD), false);
		line(source, "language", config.language());
		line(source, "difficulty", config.difficulty());
		line(source, "frequency", config.frequency().id + "  (" + config.frequency().description + ")");
		line(source, "ask until correct", Boolean.toString(config.askAgainUntilCorrect()));
		line(source, "timeout", config.timeoutSeconds() + "s");
		line(source, "punishment", Punishments.describe());
		line(source, "streak", Streaks.describe(
				source.getEntity() instanceof ServerPlayerEntity who ? who : null));
		line(source, "tiers", config.tiersEnabled()
				? "on (noun / full name / sentence by item)" : "off (always the noun)");
		line(source, "challenge crafting", Boolean.toString(config.challengeCrafting()));
		line(source, "review on doors and chests", config.reviewOnBlocks()
				? "on (at most one every " + config.reviewCooldownSeconds() + "s)" : "off");
		line(source, "negotiate with villagers", Boolean.toString(config.negotiateTrades()));
		line(source, "eyes of ender gated", config.endGate()
				? "on (tier 4, miss rolls the tier-4 punishment)" : "off");
		line(source, "nether portals gated", config.netherGate()
				? "on (one composed sentence, tier 3, overworld only)" : "off");
		line(source, "push to talk", Boolean.toString(config.pushToTalk()));
		line(source, "spoken example after a miss", Boolean.toString(config.playPronunciation()));
		line(source, "backend", config.backendUrl());

		ServerPlayerEntity player = source.getPlayer();
		if (player != null) {
			int learned = LearnedWords.get().learned(player.getUuid(), config.language()).size();
			line(source, "words learned", learned + " in " + config.language());

			// Only when armed, so the normal output is unchanged. A rig is easy
			// to set and then forget between takes, and an outcome you loaded an
			// hour ago reads as the lotto being broken when it finally lands.
			String rigged = Punishments.describeRig(player);
			if (rigged != null) {
				source.sendFeedback(() -> Text.literal("  next miss rigged to: ")
						.formatted(Formatting.YELLOW)
						.append(Text.literal(rigged).formatted(Formatting.WHITE))
						.append(Text.literal("  (/lex rig off)")
								.formatted(Formatting.DARK_GRAY)), false);
			}
		}
		source.sendFeedback(() -> Text.literal("Change with /leximine set <option> <value>")
				.formatted(Formatting.DARK_GRAY), false);
		return 1;
	}

	private static void line(ServerCommandSource source, String label, String value) {
		source.sendFeedback(() -> Text.literal("  " + label + ": ").formatted(Formatting.GRAY)
				.append(Text.literal(value).formatted(Formatting.WHITE)), false);
	}

	/**
	 * A launch argument beats the config file, which makes {@code set} look
	 * broken: the value is saved and then ignored. Say so rather than letting
	 * the player wonder.
	 */
	/**
	 * Options the client renders or records with, rather than the server.
	 *
	 * <p>In single player both halves share one config file so setting them
	 * here just works. On a dedicated server they do not, and the value
	 * written here would never reach the player's client.
	 */
	private static final List<String> CLIENT_SIDE_OPTIONS =
			List.of("streamMode", "pushToTalk", "playPronunciation");

	private static void warnIfClientSide(ServerCommandSource source, String option) {
		if (!CLIENT_SIDE_OPTIONS.contains(option)) {
			return;
		}
		if (source.getServer() == null || !source.getServer().isDedicated()) {
			return;
		}
		source.sendFeedback(() -> Text.literal(
						"  This one is drawn by your client, so set it in that client's"
								+ " config/leximine.json — a dedicated server cannot"
								+ " change it for you.")
				.formatted(Formatting.YELLOW), false);
	}

	private static void warnIfOverridden(ServerCommandSource source, String option) {
		warnIfClientSide(source, option);
		if (!LexConfig.isOverridden(option)) {
			return;
		}
		source.sendFeedback(() -> Text.literal(
						"  Saved, but -Dleximine." + option + " was set at launch and wins."
								+ " Remove it for this to take effect.")
				.formatted(Formatting.YELLOW), false);
	}

	// ------------------------------------------------------------------
	private static int words(CommandContext<ServerCommandSource> context, int page, boolean all)
			throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		ServerPlayerEntity player = source.getPlayerOrThrow();
		String lang = LexConfig.get().language();

		List<LearnedWords.Listed> rows = all
				? LearnedWords.get().attempted(player.getUuid(), lang)
				: LearnedWords.get().learned(player.getUuid(), lang);

		if (rows.isEmpty()) {
			source.sendFeedback(() -> Text.literal(all
					? "No words attempted yet in " + lang + "."
					: "No words learned yet in " + lang + ". Pick something up and say its name.")
					.formatted(Formatting.GRAY), false);
			return 0;
		}

		int pages = (rows.size() + WORDS_PER_PAGE - 1) / WORDS_PER_PAGE;
		int shown = Math.min(page, pages);
		int from = (shown - 1) * WORDS_PER_PAGE;
		int to = Math.min(from + WORDS_PER_PAGE, rows.size());

		final int finalShown = shown;
		source.sendFeedback(() -> Text.literal(
						(all ? "Words attempted" : "Words learned") + " in " + lang
								+ "  (" + rows.size() + ")"
								+ (pages > 1 ? "  page " + finalShown + "/" + pages : ""))
				.formatted(Formatting.AQUA, Formatting.BOLD), false);

		for (LearnedWords.Listed row : rows.subList(from, to)) {
			LearnedWords.Record record = row.record();
			String word = record.word.isBlank() ? prettyKey(row.key()) : record.word;
			String detail = record.isLearned()
					? "right " + record.correct + (record.wrong > 0 ? ", missed " + record.wrong : "")
					+ "  -  " + ago(record.lastCorrect)
					: "missed " + record.wrong + ", not yet";
			source.sendFeedback(() -> Text.literal("  " + (record.isLearned() ? "+ " : "- "))
					.formatted(record.isLearned() ? Formatting.GREEN : Formatting.RED)
					.append(Text.literal(word).formatted(Formatting.WHITE))
					.append(Text.literal("  " + detail).formatted(Formatting.DARK_GRAY)), false);
		}

		if (pages > 1 && shown < pages) {
			source.sendFeedback(() -> Text.literal("  /leximine words " + (finalShown + 1)
					+ " for more").formatted(Formatting.DARK_GRAY), false);
		}
		return rows.size();
	}

    private static String prettyKey(String key) {
		return key.substring(key.lastIndexOf('.') + 1).replace('_', ' ');
	}

	private static String ago(long epochMillis) {
		if (epochMillis <= 0) {
			return "never";
		}
		Duration since = Duration.ofMillis(System.currentTimeMillis() - epochMillis);
		if (since.toMinutes() < 1) {
			return "just now";
		}
		if (since.toHours() < 1) {
			return since.toMinutes() + "m ago";
		}
		if (since.toDays() < 1) {
			return since.toHours() + "h ago";
		}
		return since.toDays() + "d ago";
	}

	// ------------------------------------------------------------------
	private static int setLanguage(CommandContext<ServerCommandSource> context) {
		String code = StringArgumentType.getString(context, "code").toLowerCase();
		if (!code.matches("[a-z]{2,3}_[a-z0-9]{2,8}")) {
			context.getSource().sendError(Text.literal(
					"That is not a Minecraft language code. Try es_es, fr_fr, ja_jp..."));
			return 0;
		}
		LexConfig.get().setLanguage(code);
		warnIfOverridden(context.getSource(), "language");
		context.getSource().sendFeedback(() -> Text.literal("Language is now " + code)
				.formatted(Formatting.GREEN)
				.append(Text.literal("\n  The backend pulls the words from your Minecraft files"
								+ " the first time, which takes a moment.")
						.formatted(Formatting.DARK_GRAY))
				.append(Text.literal("\n  Your word list is tracked per language.")
						.formatted(Formatting.DARK_GRAY)), false);
		return 1;
	}

	private static int setDifficulty(CommandContext<ServerCommandSource> context) {
		String level = StringArgumentType.getString(context, "level").toLowerCase();
		if (!List.of("lenient", "normal", "strict").contains(level)) {
			context.getSource().sendError(
					Text.literal("Pick lenient, normal or strict."));
			return 0;
		}
		LexConfig.get().setDifficulty(level);
		warnIfOverridden(context.getSource(), "difficulty");
		context.getSource().sendFeedback(() -> Text.literal("Difficulty: " + level)
				.formatted(Formatting.GREEN)
				.append(Text.literal("\n  lenient if correct answers keep being rejected,"
								+ " strict if sloppy ones get through.")
						.formatted(Formatting.DARK_GRAY)), false);
		return 1;
	}

	private static int setFrequency(CommandContext<ServerCommandSource> context) {
		String raw = StringArgumentType.getString(context, "preset");
		Frequency chosen = Frequency.fromId(raw, null);
		if (chosen == null) {
			context.getSource().sendError(Text.literal(
					"Unknown setting. Options: " + String.join(", ", Frequency.ids())));
			return 0;
		}
		LexConfig.get().setFrequency(chosen);
		warnIfOverridden(context.getSource(), "frequency");
		context.getSource().sendFeedback(() -> Text.literal(
						"Frequency: " + chosen.id + " - " + chosen.description)
				.formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int setTimeout(CommandContext<ServerCommandSource> context) {
		int seconds = IntegerArgumentType.getInteger(context, "seconds");
		LexConfig.get().setTimeoutSeconds(seconds);
		warnIfOverridden(context.getSource(), "timeoutMs");
		context.getSource().sendFeedback(() -> Text.literal("You now get " + seconds
				+ "s to say the word.").formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int setPunishment(CommandContext<ServerCommandSource> context) {
		String kind = StringArgumentType.getString(context, "kind").toLowerCase();
		// The old five-value menu still parses: anything that is not an "off"
		// word turns the lotto on, so nobody's existing config breaks.
		if (!List.of("on", "off", "none", "false", "true",
				"effects", "mob", "tnt", "both").contains(kind)) {
			context.getSource().sendError(Text.literal("Pick on or none."));
			return 0;
		}
		boolean off = List.of("none", "off", "false").contains(kind);
		LexConfig.get().setPunishment(off ? "none" : "on");
		warnIfOverridden(context.getSource(), "punishment");
		context.getSource().sendFeedback(() -> Text.literal(off
				? "Punishments off. A miss costs you the item and nothing else."
				: "Punishments on. A miss is a coin flip for something worse.")
				.formatted(Formatting.GREEN), false);
		if (!off) {
			context.getSource().sendFeedback(() -> Text.literal(
							"  Some rolls damage terrain. Missing a word near your base"
									+ " can take a bite out of it.")
					.formatted(Formatting.RED), false);
		}
		return 1;
	}

	private static int setTiers(CommandContext<ServerCommandSource> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		LexConfig.get().setTiers(enabled);
		warnIfOverridden(context.getSource(), "tiers");
		context.getSource().sendFeedback(() -> Text.literal(enabled
				? "What you have to say now scales with the item: noun, full name, or a sentence."
				: "Every item now asks for the plain noun.")
				.formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int setCrafting(CommandContext<ServerCommandSource> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		LexConfig.get().setChallengeCrafting(enabled);
		warnIfOverridden(context.getSource(), "challengeCrafting");
		context.getSource().sendFeedback(() -> Text.literal(enabled
				? "Crafted items will be challenged too."
				: "Crafting no longer raises challenges.").formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int setUntilCorrect(CommandContext<ServerCommandSource> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		LexConfig.get().setAskAgainUntilCorrect(enabled);
		warnIfOverridden(context.getSource(), "askAgainUntilCorrect");
		context.getSource().sendFeedback(() -> Text.literal(enabled
				? "You will keep being asked a word until you get it right."
				: "A missed word now waits for the frequency timer like a correct one.")
				.formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int setPushToTalk(CommandContext<ServerCommandSource> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		LexConfig.get().setPushToTalk(enabled);
		warnIfOverridden(context.getSource(), "pushToTalk");
		context.getSource().sendFeedback(() -> Text.literal(enabled
				? "Hold V to speak. Takes effect on the next challenge."
				: "The microphone opens by itself for each challenge.")
				.formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int setStreamMode(CommandContext<ServerCommandSource> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		LexConfig.get().setStreamMode(enabled);
		warnIfOverridden(context.getSource(), "streamMode");
		context.getSource().sendFeedback(() -> Text.literal(enabled
				? "Live transcript enlarged for viewers."
				: "Live transcript back to normal size.").formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int setPronunciation(CommandContext<ServerCommandSource> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		LexConfig.get().setPlayPronunciation(enabled);
		warnIfOverridden(context.getSource(), "playPronunciation");
		context.getSource().sendFeedback(() -> Text.literal(enabled
				? "After a miss you will hear the word said properly."
				: "No spoken examples after a miss.").formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int setReview(CommandContext<ServerCommandSource> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		LexConfig.get().setReviewOnBlocks(enabled);
		warnIfOverridden(context.getSource(), "reviewOnBlocks");
		context.getSource().sendFeedback(() -> Text.literal(enabled
				? "Doors and chests will quiz you on words you keep missing."
				: "Doors and chests will leave you alone.").formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int setReviewCooldown(CommandContext<ServerCommandSource> context) {
		int seconds = IntegerArgumentType.getInteger(context, "seconds");
		LexConfig.get().setReviewCooldownSeconds(seconds);
		warnIfOverridden(context.getSource(), "reviewCooldown");
		context.getSource().sendFeedback(() -> Text.literal(seconds == 0
				? "Every door and chest can now ask. That is a lot of doors."
				: "At most one review every " + seconds + "s.")
				.formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int setNetherGate(CommandContext<ServerCommandSource> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		LexConfig.get().setNetherGate(enabled);
		warnIfOverridden(context.getSource(), "netherGate");
		context.getSource().sendFeedback(() -> Text.literal(enabled
				? "A nether portal must now be talked open."
				: "Nether portals light without a word.")
				.formatted(Formatting.GREEN)
				.append(Text.literal(enabled
								? "\n  One sentence you compose yourself, judged by the model."
										+ " Overworld only -- coming home is never gated."
								: "")
						.formatted(Formatting.DARK_GRAY)), false);
		return 1;
	}

	private static int setStreakCount(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		int count = IntegerArgumentType.getInteger(context, "count");
		Streaks.get().set(player.getUuid(), count);
		// Pushed, or the overlay keeps showing whatever it last heard and a
		// screenshot taken after this would be of the old number.
		LexPackets.sendStreak(player, count, Streaks.get().best(player.getUuid()));
		Leximine.LOGGER.info("streak for {} set to {}",
				player.getGameProfile().getName(), count);
		context.getSource().sendFeedback(
				() -> Text.literal("Streak set to " + count + "."), false);
		return 1;
	}

	private static int setStreakMode(CommandContext<ServerCommandSource> context) {
		String mode = StringArgumentType.getString(context, "mode").trim().toLowerCase(Locale.ROOT);
		if (!mode.equals("escalate") && !mode.equals("off")) {
			context.getSource().sendError(Text.literal(
					"streak is 'escalate' or 'off', not '" + mode + "'."));
			return 0;
		}
		LexConfig.get().setStreak(mode);
		warnIfOverridden(context.getSource(), "streak");
		boolean on = mode.equals("escalate");
		context.getSource().sendFeedback(() -> Text.literal(on
				? "A broken run of " + Streaks.ESCALATE_AT
						+ " or more now rolls a tier higher."
				: "Streaks are counted but cost nothing.")
				.formatted(Formatting.GREEN)
				.append(Text.literal(on
								? "\n  The counter and your personal best are kept either way."
								: "")
						.formatted(Formatting.DARK_GRAY)), false);
		return 1;
	}

	private static int setEndGate(CommandContext<ServerCommandSource> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		LexConfig.get().setEndGate(enabled);
		warnIfOverridden(context.getSource(), "endGate");
		context.getSource().sendFeedback(() -> Text.literal(enabled
				? "Every eye of ender must now be argued into its frame."
				: "Eyes of ender go in without a word.")
				.formatted(Formatting.GREEN)
				.append(Text.literal(enabled
								? "\n  Tier 4, and a miss rolls the tier-4 punishment -- in a sealed"
										+ " stone room. The eye itself is never lost."
								: "")
						.formatted(Formatting.DARK_GRAY)), false);
		return 1;
	}

	private static int setNegotiate(CommandContext<ServerCommandSource> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		LexConfig.get().setNegotiateTrades(enabled);
		warnIfOverridden(context.getSource(), "negotiateTrades");
		context.getSource().sendFeedback(() -> Text.literal(enabled
				? "Villagers will want to be asked, in a sentence, before they trade."
				: "Villagers will trade without being asked.")
				.formatted(Formatting.GREEN), false);
		return 1;
	}

	// ------------------------------------------------------------------
	private static int forgetAll(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		String lang = LexConfig.get().language();
		int removed = LearnedWords.get().forget(player.getUuid(), lang, null);
		context.getSource().sendFeedback(() -> Text.literal(
						"Forgot " + removed + " " + lang + " word(s). They will be asked again.")
				.formatted(Formatting.YELLOW), false);
		return removed;
	}

	private static int confiscate(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		ItemStack like = ItemStackArgumentType.getItemStackArgument(context, "item")
				.createStack(1, false);
		int count = IntegerArgumentType.getInteger(context, "count");

		if (!LimboManager.get().challengeAfterAcquiring(player, like, count)) {
			context.getSource().sendError(Text.literal(
					"No challenge raised (already one open, not due, or nothing to take)."));
			return 0;
		}
		return 1;
	}

	/**
	 * Craft oak planks in the player's own 2x2 grid and take the result with a
	 * genuine slot click.
	 *
	 * <p>Exists because the two take routes differ completely and only one of
	 * them was covered before: a test that called the confiscation helper
	 * directly passed while shift-click crafting was never challenged at all.
	 * This goes through {@code onSlotClick}, the same entry point the client's
	 * click packet lands on.
	 */
	/**
	 * Put a chest next to the player and right-click it for them.
	 *
	 * <p>Goes through {@code interactBlock}, which is where a real click from
	 * the client lands, so the {@code UseBlockCallback} wiring is exercised
	 * rather than just the manager behind it.
	 */
	/**
	 * Interact with a merchant on the player's behalf, making one first if
	 * there is none to hand.
	 *
	 * <p>Routed through {@code onPlayerInteractEntity} rather than
	 * {@code Entity.interact}, because that packet handler is where Fabric
	 * raises {@code UseEntityCallback} — calling the entity directly would
	 * skip the very wiring this is meant to prove.
	 *
	 * <p>It makes its own villager rather than leaving that to the test
	 * harness. Summoning one over RCON looked like it worked — the server
	 * answers "Summoned new Farmer" — and then no selector could ever find
	 * it again, which surfaced only as "no merchant within 24 blocks" and
	 * read like a bug in this command. Spawning it here uses the same call
	 * the game itself uses and can be checked on the spot.
	 */
	private static int testTrade(CommandContext<ServerCommandSource> context,
			boolean spawnFresh, boolean multiOffer)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		MerchantEntity merchant = spawnFresh ? null : nearestMerchant(player);

		if (merchant == null) {
			merchant = spawnTestMerchant(player, multiOffer);
			if (merchant == null) {
				context.getSource().sendError(
						Text.literal("Could not spawn a merchant to trade with."));
				return 0;
			}
			final MerchantEntity spawned = merchant;
			// The candidate count is here because its absence cost an hour:
			// the lookup silently returned null with a villager one block
			// away, every call spawned another merchant, and the harness
			// cheerfully asserted against whichever one it got.
			final int seen = spawnFresh ? -1 : merchantsNear(player).size() - 1;
			context.getSource().sendFeedback(
					() -> Text.literal("Spawned a merchant with "
							+ spawned.getOffers().size() + " offer(s), removed="
							+ spawned.isRemoved()
							+ (seen < 0 ? " (fresh by request)"
									: ", merchants already in range: " + seen)
							+ String.format(", player at %.1f %.1f %.1f",
									player.getX(), player.getY(), player.getZ())), false);
		}
		final MerchantEntity target = merchant;
		player.networkHandler.onPlayerInteractEntity(
				PlayerInteractEntityC2SPacket.interact(target, false, Hand.MAIN_HAND));
		context.getSource().sendFeedback(
				() -> Text.literal("Interacted with " + target.getType().getUntranslatedName()
						+ " (" + target.getOffers().size() + " offers)"), false);
		return 1;
	}

	/**
	 * The closest merchant worth talking to, or null.
	 *
	 * <p>Written by hand rather than with {@code getClosestEntity} and a
	 * {@code TargetPredicate}, which returned null with a villager standing one
	 * block away. That had gone unnoticed because every caller killed the
	 * villagers first and so always took the spawn path -- the branch was dead
	 * code that had never once returned anything, and the symptom when it
	 * finally mattered was a test spawning a second merchant and quietly
	 * asserting against the wrong one.
	 */
	private static MerchantEntity nearestMerchant(ServerPlayerEntity player) {
		MerchantEntity closest = null;
		double best = Double.MAX_VALUE;
		for (Entity candidate : merchantsNear(player)) {
			double distance = candidate.squaredDistanceTo(player);
			if (distance < best) {
				best = distance;
				closest = (MerchantEntity) candidate;
			}
		}
		return closest;
	}

	/**
	 * Every merchant within 24 blocks.
	 *
	 * <p>{@code getOtherEntities} with a plain predicate, which is what
	 * vanilla itself uses, rather than a class or type filter -- the filtered
	 * forms returned nothing here for a villager standing one block away.
	 */
	private static List<Entity> merchantsNear(ServerPlayerEntity player) {
		// Box.of around the player's live position, rather than
		// player.getBoundingBox().expand(...) -- the latter found nothing with
		// a villager one block away and the player demonstrably standing
		// still, so whatever it was describing was not where the player was.
		return player.getServerWorld().getOtherEntities(player,
				Box.of(player.getPos(), 48.0, 48.0, 48.0),
				entity -> entity instanceof MerchantEntity merchant
						&& merchant.isAlive() && !merchant.isBaby());
	}

	/**
	 * A farmer standing next to the player with one thing to sell.
	 *
	 * <p>The offer is written in by hand: a villager with no profession has
	 * nothing on sale, so the trade challenge would never fire and the test
	 * would pass by asserting nothing. Persistent, invulnerable and without
	 * AI so that it is still there, and still in the same place, when the
	 * assertions run.
	 */
	private static MerchantEntity spawnTestMerchant(ServerPlayerEntity player,
			boolean multiOffer) {
		ServerWorld world = player.getServerWorld();
		VillagerEntity villager = EntityType.VILLAGER.create(world);
		if (villager == null) {
			return null;
		}
		// Offset per merchant already standing there, so a second one does not
		// spawn inside the first -- AI is off, so they never push apart, and
		// two villagers in one block make "the closest" meaningless.
		int standing = merchantsNear(player).size();
		villager.refreshPositionAndAngles(player.getX() + 1.0 + standing * 2.0,
				player.getY(), player.getZ(), player.getYaw(), 0.0f);
		villager.setVillagerData(villager.getVillagerData()
				.withProfession(VillagerProfession.FARMER)
				.withType(VillagerType.PLAINS)
				.withLevel(2));
		villager.setPersistent();
		villager.setAiDisabled(true);
		villager.setInvulnerable(true);
		// And it must not FALL, which is not the same thing twice. Disabling
		// the AI stops a mob steering, not dropping, and `setInvulnerable`
		// does not cover the void -- out-of-world damage bypasses
		// invulnerability by design. In a flat test world a merchant spawned
		// a block to the side of the player landed in open air, fell past
		// y=-64 and was dead within a second: `spawnEntity` returned true,
		// the interaction in that same command worked off the direct
		// reference, and every later lookup reported nothing there. The
		// existing trade test never saw it because it only ever used the
		// merchant it had just made.
		villager.setNoGravity(true);
		// Giving it a profession fills in that profession's own recipes, and a
		// farmer's first one sells emeralds -- so the challenge would ask for
		// an emerald while the test says bread. The profession is still worth
		// setting, because a villager without one refuses to trade at all.
		villager.getOffers().clear();
		villager.getOffers().add(new TradeOffer(
				new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD, 6), 99, 0, 0.0f));
		if (multiOffer) {
			// Three items the backend definitely has words for, so whichever
			// one gets picked the negotiation can still run.
			villager.getOffers().add(new TradeOffer(
					new ItemStack(Items.EMERALD), new ItemStack(Items.CARROT, 8), 99, 0, 0.0f));
			villager.getOffers().add(new TradeOffer(
					new ItemStack(Items.EMERALD), new ItemStack(Items.APPLE, 4), 99, 0, 0.0f));
		}
		return world.spawnEntity(villager) ? villager : null;
	}

	/**
	 * Spin the punishment wheel on a chosen outcome.
	 *
	 * <p>Goes through {@code Punishments.spin}, the same call a real miss
	 * makes, so this exercises the packet, the two-second delay and the
	 * payload. Only the dice are removed. {@code SAFE} drives the 50% case,
	 * which is the one a player sees most and therefore the one most worth
	 * being sure looks right.
	 */
	/**
	 * {@code /lex force <tier> <id>} — puppet the whole thing for footage.
	 *
	 * <p>Skips the speech check, takes what is in the player's hand the way a
	 * real miss takes the item, spins the wheel and lands on the penalty you
	 * asked for. {@code id} is 1-5 within the tier, in the order of the design
	 * matrix, so {@code /lex force 3 2} is The Panic Room.
	 */
	private static int forcePenalty(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		int tier = IntegerArgumentType.getInteger(context, "tier");
		int id = IntegerArgumentType.getInteger(context, "id");

		Penalty penalty = Penalty.byIndex(tier, id - 1);
		if (penalty == null) {
			context.getSource().sendError(Text.literal(
					"Tier " + tier + " has no penalty " + id + "."));
			return 0;
		}

		// A real miss costs you the item. This should look identical on
		// camera, so it takes the held stack the same way.
		ItemStack taken = player.getMainHandStack().copy();
		if (!taken.isEmpty()) {
			player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
		}

		Punishments.spin(player, tier, penalty);
		context.getSource().sendFeedback(() -> Text.literal(
				"Forcing " + penalty.displayName() + " (tier " + tier + ", #" + id + ")"
						+ (taken.isEmpty() ? "" : ", took " + taken.getCount() + "x "
						+ taken.getItem().getName().getString()))
				.formatted(Formatting.GRAY), false);
		return 1;
	}

	/**
	 * {@code /lex rig <penalty>} — load the next miss, leave the rest alone.
	 *
	 * <p>The difference from {@code force} and {@code testlotto}, which is the
	 * whole reason this exists: those two fake the miss, so there is no
	 * challenge, no microphone and no verdict on camera. This fakes nothing.
	 * You play the challenge for real and genuinely fumble the word; it only
	 * decides what the wheel lands on when you do, once.
	 *
	 * <p>{@code SAFE} loads the 50% case, {@code off} clears without firing.
	 */
	private static int rig(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		String raw = StringArgumentType.getString(context, "penalty").trim();
		String key = raw.toLowerCase(java.util.Locale.ROOT);

		if (List.of("off", "clear", "cancel").contains(key)) {
			boolean armed = Punishments.clearRig(player);
			context.getSource().sendFeedback(() -> Text.literal(armed
					? "Rig cleared. Your next miss rolls honestly."
					: "Nothing was rigged.").formatted(Formatting.GRAY), false);
			return armed ? 1 : 0;
		}

		// "none" means a safe roll here, as it does in testlotto, rather than
		// meaning "off" as it does in /lex set punishment. Worth the
		// inconsistency: these two sit next to each other between takes.
		if (List.of("safe", "none").contains(key)) {
			Punishments.rig(player, null);
			context.getSource().sendFeedback(() -> Text.literal(
							"Rigged: your next miss will come up safe.")
					.formatted(Formatting.GREEN)
					.append(Text.literal("\n  Fires once, then the dice are back.")
							.formatted(Formatting.DARK_GRAY)), false);
			warnIfLottoOff(context.getSource());
			return 1;
		}

		Penalty penalty;
		try {
			penalty = Penalty.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			context.getSource().sendError(Text.literal("No such penalty: " + raw));
			return 0;
		}

		Punishments.rig(player, penalty);
		context.getSource().sendFeedback(() -> Text.literal(
						"Rigged: your next miss lands on " + penalty.displayName()
								+ " (tier " + penalty.tier() + ").")
				.formatted(Formatting.GREEN)
				.append(Text.literal("\n  Say a word wrong and it fires -- whatever the item,"
								+ " whatever its tier. Fires once, then the dice are back.")
						.formatted(Formatting.DARK_GRAY)), false);
		warnIfLottoOff(context.getSource());
		return 1;
	}

	/** What is loaded, if anything. {@code /lex rig} with no argument. */
	private static int rigStatus(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		String rigged = Punishments.describeRig(player);
		context.getSource().sendFeedback(() -> Text.literal(rigged == null
				? "Nothing rigged. Your next miss rolls honestly."
				: "Next miss is rigged to " + rigged + ".")
				.formatted(rigged == null ? Formatting.GRAY : Formatting.YELLOW)
				.append(Text.literal("\n  /lex rig <penalty>|SAFE|off")
						.formatted(Formatting.DARK_GRAY)), false);
		return rigged == null ? 0 : 1;
	}

	/**
	 * A rig only fires if the lotto is switched on at all.
	 *
	 * <p>Without this the failure is silent and looks like the rig did not
	 * work: you set it, miss the word on camera, and nothing happens because
	 * punishments were turned off some session ago.
	 */
	private static void warnIfLottoOff(ServerCommandSource source) {
		if (Punishments.enabled()) {
			return;
		}
		source.sendFeedback(() -> Text.literal(
						"  Punishments are off, so this will not fire."
								+ " Turn them on with /lex set punishment on")
				.formatted(Formatting.RED), false);
	}

	private static int testLotto(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		String raw = StringArgumentType.getString(context, "penalty").trim();

		if ("SAFE".equalsIgnoreCase(raw) || "none".equalsIgnoreCase(raw)) {
			Punishments.spin(player, ItemTiers.NOUN, null);
			context.getSource().sendFeedback(
					() -> Text.literal("Spinning for a safe roll."), false);
			return 1;
		}

		Penalty penalty;
		try {
			penalty = Penalty.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			context.getSource().sendError(Text.literal("No such penalty: " + raw));
			return 0;
		}
		Punishments.spin(player, penalty.tier(), penalty);
		context.getSource().sendFeedback(
				() -> Text.literal("Spinning for " + penalty.name()
						+ " (tier " + penalty.tier() + ")"), false);
		return 1;
	}

	/**
	 * Put an empty end portal frame next to the player and offer it an eye.
	 *
	 * <p>Goes through {@code interactBlock}, which is where a real right-click
	 * lands, so the {@code UseBlockCallback} ordering is exercised rather than
	 * just the manager behind it — the gate has to beat the review check to
	 * this interaction, and only the real event proves that.
	 */
	/**
	 * Strike an obsidian block and report what the gate did with it.
	 *
	 * <p>Asserts on {@code fire placed}, not on the gate's own log line: the
	 * whole risk in this feature is standing aside and letting vanilla light
	 * the portal for nothing, and a log line can be right while the block in
	 * front of the player is wrong.
	 */
	private static int testNetherGate(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		ServerWorld world = player.getServerWorld();

		BlockPos pos = player.getBlockPos().add(2, 0, 0);
		world.setBlockState(pos, Blocks.OBSIDIAN.getDefaultState());
		// Air above it, or the strike has nowhere to put fire and "nothing
		// happened" would be the terrain's doing rather than the gate's.
		world.setBlockState(pos.up(), Blocks.AIR.getDefaultState());
		player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));

		BlockHitResult hit = new BlockHitResult(
				Vec3d.ofCenter(pos), Direction.UP, pos, false);
		ActionResult result = player.interactionManager.interactBlock(
				player, world, player.getMainHandStack(), Hand.MAIN_HAND, hit);

		String answer = "offer";
		try {
			answer = StringArgumentType.getString(context, "answer");
		} catch (IllegalArgumentException ignored) {
			// no argument given
		}
		if (!"offer".equalsIgnoreCase(answer)) {
			int id = NetherGateManager.get().pendingId(player);
			if (id < 0) {
				context.getSource().sendError(Text.literal("No open nether gate to answer."));
				return 0;
			}
			NetherGateManager.get().resolve(player, id, "pass".equalsIgnoreCase(answer));
		}

		boolean lit = world.getBlockState(pos.up()).isOf(Blocks.FIRE);
		final String what = answer;
		context.getSource().sendFeedback(() -> Text.literal(
				"Struck the obsidian at " + pos.toShortString()
						+ " (" + what + ") -> " + result + ", fire placed: " + lit), false);
		if ("pass".equalsIgnoreCase(answer)) {
			return lit ? 1 : 0;
		}
		return result == ActionResult.FAIL ? 1 : 0;
	}

	private static int testEndGate(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		ServerWorld world = player.getServerWorld();

		BlockPos pos = player.getBlockPos().add(1, 0, 0);
		world.setBlockState(pos, Blocks.END_PORTAL_FRAME.getDefaultState());
		player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.ENDER_EYE, 12));

		BlockHitResult hit = new BlockHitResult(
				Vec3d.ofCenter(pos), Direction.UP, pos, false);
		ActionResult result = player.interactionManager.interactBlock(
				player, world, player.getMainHandStack(), Hand.MAIN_HAND, hit);

		// Optionally answer it, so the replay-and-place half can be driven
		// without a microphone. "offer" leaves it open for a real verdict.
		String answer = "offer";
		try {
			answer = StringArgumentType.getString(context, "answer");
		} catch (IllegalArgumentException ignored) {
			// no argument given
		}
		if (!"offer".equalsIgnoreCase(answer)) {
			int id = EndGateManager.get().pendingId(player);
			if (id < 0) {
				context.getSource().sendError(Text.literal("No open end gate to answer."));
				return 0;
			}
			EndGateManager.get().resolve(player, id, "pass".equalsIgnoreCase(answer));
		}

		boolean filled = world.getBlockState(pos)
				.getOrEmpty(net.minecraft.block.EndPortalFrameBlock.EYE).orElse(false);
		final String what = answer;
		context.getSource().sendFeedback(() -> Text.literal(
				"Offered an eye to the frame at " + pos.toShortString()
						+ " (" + what + ") -> " + result + ", eye in frame: " + filled), false);
		// The gate intercepting is the pass condition for "offer"; for an
		// answered gate it is whether the frame ended up matching the verdict.
		if ("pass".equalsIgnoreCase(answer)) {
			return filled ? 1 : 0;
		}
		return result == ActionResult.FAIL ? 1 : 0;
	}

	private static int testReview(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		ServerWorld world = player.getServerWorld();

		BlockPos pos = player.getBlockPos().add(1, 0, 0);
		world.setBlockState(pos, Blocks.CHEST.getDefaultState());

		BlockHitResult hit = new BlockHitResult(
				Vec3d.ofCenter(pos), Direction.UP, pos, false);
		ActionResult result = player.interactionManager.interactBlock(
				player, world, ItemStack.EMPTY, Hand.MAIN_HAND, hit);

		context.getSource().sendFeedback(
				() -> Text.literal("Used a chest at " + pos.toShortString() + " -> " + result),
				false);
		return result == ActionResult.FAIL ? 1 : 0;
	}

	/**
	 * Upgrade a diamond pickaxe to netherite at a real smithing table.
	 *
	 * <p>Goes through {@code onSlotClick} on a genuine
	 * {@link net.minecraft.screen.SmithingScreenHandler}, so the mixin is
	 * reached by the same route a player's click takes — including the
	 * shift-click path, which empties the stack before {@code onTakeOutput}
	 * sees it and is exactly where this is most likely to break.
	 */
	private static int testSmith(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		ServerWorld world = player.getServerWorld();
		String mode = StringArgumentType.getString(context, "mode");
		SlotActionType action = "pickup".equalsIgnoreCase(mode)
				? SlotActionType.PICKUP
				: SlotActionType.QUICK_MOVE;
		// "trim" is the negative case: the same table, the same take, but the
		// result is the item that went in. Nothing should be challenged.
		boolean trim = "trim".equalsIgnoreCase(mode);

		// A real block, so the handler's own canUse check has something to
		// agree with rather than being bypassed.
		BlockPos pos = player.getBlockPos().add(2, 0, 0);
		world.setBlockState(pos, Blocks.SMITHING_TABLE.getDefaultState());

		player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
				(syncId, inventory, p) -> new SmithingScreenHandler(syncId, inventory,
						ScreenHandlerContext.create(world, pos)),
				Text.literal("Leximine test")));

		if (!(player.currentScreenHandler instanceof SmithingScreenHandler handler)) {
			context.getSource().sendError(Text.literal("Could not open a smithing table."));
			return 0;
		}

		if (trim) {
			handler.getSlot(SmithingScreenHandler.TEMPLATE_ID)
					.setStack(new ItemStack(Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE));
			handler.getSlot(SmithingScreenHandler.EQUIPMENT_ID)
					.setStack(new ItemStack(Items.NETHERITE_CHESTPLATE));
			handler.getSlot(SmithingScreenHandler.MATERIAL_ID)
					.setStack(new ItemStack(Items.REDSTONE));
		} else {
			handler.getSlot(SmithingScreenHandler.TEMPLATE_ID)
					.setStack(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
			handler.getSlot(SmithingScreenHandler.EQUIPMENT_ID)
					.setStack(new ItemStack(Items.DIAMOND_PICKAXE));
			handler.getSlot(SmithingScreenHandler.MATERIAL_ID)
					.setStack(new ItemStack(Items.NETHERITE_INGOT));
		}
		handler.updateResult();

		ItemStack result = handler.getSlot(SmithingScreenHandler.OUTPUT_ID).getStack();
		if (result.isEmpty()) {
			context.getSource().sendError(Text.literal(
					"No upgrade result; the recipe did not resolve."));
			return 0;
		}
		String what = result.getItem().getTranslationKey();

		handler.onSlotClick(SmithingScreenHandler.OUTPUT_ID, 0, action, player);
		handler.syncState();

		context.getSource().sendFeedback(
				() -> Text.literal("Upgraded to " + what + " via " + action), false);
		return 1;
	}

	private static int testCraft(CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		String mode = StringArgumentType.getString(context, "mode");
		SlotActionType action = "pickup".equalsIgnoreCase(mode)
				? SlotActionType.PICKUP
				: SlotActionType.QUICK_MOVE;

		ScreenHandler handler = player.playerScreenHandler;
		player.currentScreenHandler = handler;

		// Slot 0 is the result, 1..4 the 2x2 input grid.
		for (int slot = 1; slot <= 4; slot++) {
			handler.getSlot(slot).setStack(ItemStack.EMPTY);
		}
		handler.getSlot(1).setStack(new ItemStack(Items.OAK_LOG));
		handler.onContentChanged(handler.getSlot(1).inventory);

		if (!handler.getSlot(0).hasStack()) {
			context.getSource().sendError(Text.literal("No recipe result; nothing to craft."));
			return 0;
		}
		String result = handler.getSlot(0).getStack().getCount()
				+ " x " + handler.getSlot(0).getStack().getItem().getTranslationKey();

		handler.onSlotClick(0, 0, action, player);
		handler.syncState();

		context.getSource().sendFeedback(
				() -> Text.literal("Crafted " + result + " via " + action), false);
		return 1;
	}

	private static int test(CommandContext<ServerCommandSource> context, ItemStack stack)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		if (LimboManager.get().hasActiveChallenge(player)) {
			context.getSource().sendError(Text.literal("You already have an item in limbo."));
			return 0;
		}
		// Bypasses shouldChallenge on purpose: the cooldowns exist to keep
		// normal play bearable, and a test command should not be silenced by
		// having just learned the same word.
		if (!LimboManager.get().begin(player, stack)) {
			context.getSource().sendError(Text.literal("Could not start a challenge."));
			return 0;
		}
		return 1;
	}
}
