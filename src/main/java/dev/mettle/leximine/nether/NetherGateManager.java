package dev.mettle.leximine.nether;

import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.config.ItemTiers;
import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.limbo.LimboManager;
import dev.mettle.leximine.limbo.Punishments;
import dev.mettle.leximine.net.LexPackets;
import dev.mettle.leximine.progress.Streaks;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * You have to say where you are going before the way opens.
 *
 * <p>The mod's two big thresholds are now gated in two different ways, which
 * is the point of having two. The End is twelve <em>word</em> prompts at the
 * top tier, spread across a room you cannot leave. The Nether is <strong>one
 * sentence you compose yourself</strong>, judged by the language model rather
 * than matched against anything: say, in the target language, that you are
 * going and why.
 *
 * <p>That difference is deliberate. Twelve eyes is an endurance test of
 * vocabulary; a portal is a single sentence under pressure, which is the
 * harder and more interesting thing to do and has no single right answer to
 * learn by rote. It also gives a run an act structure for free -- two
 * thresholds, two kinds of hard.
 *
 * <h2>Lighting it, not walking through it</h2>
 *
 * The gate is on <em>striking the obsidian</em>, not on entering the portal.
 * Gating entry means a mixin into the portal tick and a prompt that can fire
 * while the player is falling, mid-fight, or being chased into the portal they
 * built to escape. Lighting it is a single, chosen, stationary moment, and it
 * is the one that actually reads as a threshold.
 *
 * <h2>Overworld only</h2>
 *
 * Deliberate, and the most important rule here: <strong>never gate an action
 * that could strand someone.</strong> Obsidian struck in the Nether is nearly
 * always a portal being repaired or rebuilt by a player trying to get home,
 * and a speech check that fails there is not a punishment, it is being stuck
 * in the Nether until the microphone cooperates. Going is a choice; coming
 * back is not.
 */
public final class NetherGateManager {
	private static final NetherGateManager INSTANCE = new NetherGateManager();

	/** After a pass, strike freely for a moment so the replay gets through. */
	private static final long UNLOCK_GRACE_MILLIS = 5_000L;

	/** A sentence plus a model call needs longer than naming one word. */
	private static final float TIMEOUT_SCALE = 2.0f;

	/**
	 * What the model is told to judge. English, and never shown to the player.
	 *
	 * <p>Phrased as "or why", not "and why": requiring both turns one sentence
	 * into two clauses and a subordinator, which is a tier-4 ask at a tier-3
	 * gate. "Voy al Nether" should be enough to get through a door you are
	 * walking into of your own accord.
	 */
	private static final String TASK =
			"say, in one sentence, that they are going to the Nether, "
					+ "or why they want to go there";

	private record Pending(int id, BlockPos pos, Hand hand, BlockHitResult hit,
			long deadline) { }

	private final Map<UUID, Pending> pending = new HashMap<>();
	private final Map<UUID, Long> unlockedUntil = new HashMap<>();

	private NetherGateManager() {
	}

	public static NetherGateManager get() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------
	public ActionResult onBlockUse(ServerPlayerEntity player, World world, Hand hand,
			BlockHitResult hit) {
		LexConfig config = LexConfig.get();
		if (!config.netherGate()) {
			return ActionResult.PASS;
		}
		if (config.skipCreative() && player.isCreative()) {
			return ActionResult.PASS;
		}
		if (!player.getStackInHand(hand).isOf(Items.FLINT_AND_STEEL)) {
			return ActionResult.PASS;
		}

		BlockPos pos = hit.getBlockPos();
		if (!world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) {
			return ActionResult.PASS;
		}
		// See the class note. Coming home is never gated.
		if (world.getRegistryKey() != World.OVERWORLD) {
			return ActionResult.PASS;
		}

		// Every obsidian strike, rather than only the ones that would really
		// light a portal. Asking vanilla whether this frame is valid means
		// reaching into the portal-shape helper, which is exactly the kind of
		// internal that moves between versions and fails by crashing at load.
		// The cost of the loose check is gating someone lighting a decorative
		// fire on an obsidian block, which is rare and harmless; the cost of
		// the tight one is the mod not loading on 1.20.5.
		UUID uuid = player.getUuid();
		long now = System.currentTimeMillis();
		if (now < unlockedUntil.getOrDefault(uuid, 0L)) {
			return ActionResult.PASS;          // just earned it
		}
		if (pending.containsKey(uuid) || LimboManager.get().hasActiveChallenge(player)) {
			// FAIL, not PASS: standing aside here hands the strike to vanilla
			// and lights the portal for free. The same mistake shipped in both
			// other gates -- see LimboManager.isBusy.
			Leximine.LOGGER.info("nether gate withheld at {}: already answering something",
					pos.toShortString());
			player.sendMessage(Text.literal("Finish what you are saying first.")
					.formatted(Formatting.GRAY), true);
			return ActionResult.FAIL;
		}

		int id = LimboManager.get().nextChallengeId();
		int timeoutMs = Math.round(config.timeoutMs() * TIMEOUT_SCALE);
		pending.put(uuid, new Pending(id, pos, hand, hit, now + timeoutMs));

		Leximine.LOGGER.info("nether gate {} for {} at {} (tier {})",
				id, player.getGameProfile().getName(), pos.toShortString(),
				ItemTiers.SENTENCE);
		LexPackets.sendSentenceStart(player, id, config.language(), config.difficulty(),
				"hud.leximine.nether_gate", TASK, timeoutMs);

		world.playSound(null, pos, SoundEvents.BLOCK_PORTAL_TRIGGER,
				SoundCategory.BLOCKS, 0.4f, 1.6f);
		return ActionResult.FAIL;
	}

	// ------------------------------------------------------------------
	public boolean isPending(ServerPlayerEntity player, int id) {
		Pending open = pending.get(player.getUuid());
		return open != null && open.id() == id;
	}

	/** The open gate's id, or -1. For the dev test seam only. */
	public int pendingId(ServerPlayerEntity player) {
		Pending open = pending.get(player.getUuid());
		return open == null ? -1 : open.id();
	}

	public void resolve(ServerPlayerEntity player, int id, boolean success) {
		UUID uuid = player.getUuid();
		Pending open = pending.get(uuid);
		if (open == null || open.id() != id) {
			return;
		}
		pending.remove(uuid);

		if (!success) {
			Leximine.LOGGER.info("nether gate {} refused at {}", id,
					open.pos().toShortString());
			player.sendMessage(Text.literal("The way stays shut.")
					.formatted(Formatting.DARK_PURPLE), true);
			player.getWorld().playSound(null, open.pos(),
					SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.9f, 0.7f);
			LexPackets.sendChallengeEnd(player, id, false);
			// Tier 3, under the End's tier 4: this is the earlier threshold and
			// the sentence is shorter. The flint and steel is kept, for the
			// same reason the eye is -- the cost is being stopped, not being
			// made to go and mine again.
			Punishments.apply(player, ItemTiers.SENTENCE, Streaks.broke(player));
			return;
		}

		Streaks.award(player);
		// Opened before the replay, which both lets the replay through this
		// gate and stops it being asked again on the way.
		unlockedUntil.put(uuid, System.currentTimeMillis() + UNLOCK_GRACE_MILLIS);
		LexPackets.sendChallengeEnd(player, id, true);
		Leximine.LOGGER.info("nether gate {} passed at {}", id, open.pos().toShortString());

		// Replayed through the real interaction so vanilla decides whether
		// this frame is actually a portal. Lighting the fire here instead
		// would be a second copy of the portal-shape rule, and the copy is the
		// one that goes stale.
		try {
			player.interactionManager.interactBlock(player, player.getWorld(),
					player.getStackInHand(open.hand()), open.hand(), open.hit());
		} catch (RuntimeException e) {
			// The grace window still lets them strike it by hand.
			Leximine.LOGGER.debug("could not replay the strike", e);
		}
	}

	/**
	 * Server tick: give up on a gate whose verdict never arrived.
	 *
	 * <p>Without it one stranded entry makes every later strike stand aside,
	 * so the gate is silently off for the rest of the server's life with
	 * nothing logged. This mod has shipped that bug three times.
	 */
	public void tick(MinecraftServer server) {
		if (pending.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		List<UUID> expired = null;
		for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
			if (now > entry.getValue().deadline() + LimboManager.VERDICT_GRACE_MILLIS) {
				(expired == null ? expired = new ArrayList<>() : expired).add(entry.getKey());
			}
		}
		if (expired == null) {
			return;
		}
		for (UUID uuid : expired) {
			Pending open = pending.remove(uuid);
			Leximine.LOGGER.info("nether gate {} timed out with no verdict", open.id());
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
			if (player != null) {
				// Nothing heard, so nothing got wrong: no punishment, and the
				// streak survives.
				LexPackets.sendChallengeEnd(player, open.id(), false);
			}
		}
	}

	public void onDisconnect(ServerPlayerEntity player) {
		pending.remove(player.getUuid());
		unlockedUntil.remove(player.getUuid());
	}
}
