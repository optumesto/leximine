package dev.mettle.leximine.end;

import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.config.ItemTiers;
import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.limbo.LimboManager;
import dev.mettle.leximine.limbo.Punishments;
import dev.mettle.leximine.net.LexPackets;
import dev.mettle.leximine.progress.Streaks;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.item.ItemStack;
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
 * The End has a toll: every eye of ender has to be argued into its frame.
 *
 * <p>Twelve eyes, twelve prompts, spread across the stronghold rather than
 * concentrated into one gate at the portal. That was a deliberate choice over
 * the alternatives:
 *
 * <ul>
 *   <li>Gating the <em>portal block</em> is one moment. Pass it and the whole
 *       feature is over; fail it and you stand on the edge repeating yourself.
 *   <li>A guardian <em>entity</em> beside the frame has to be spawned, can be
 *       killed, and can be walked around.
 *   <li>Gating the <em>eyes</em> uses a thing the player already does twelve
 *       times, in a room they cannot leave early, and it paces itself.
 * </ul>
 *
 * <p>At tier 4 these are the hardest prompts the mod produces, and a miss
 * rolls the tier-4 lottery — the wither, the eviction, the black hole — in a
 * sealed stone room. The eye is <strong>not</strong> consumed on a failure:
 * eyes are finite and shatter on their own often enough, and burning one for a
 * mispronunciation turns a language game into an inventory-management problem.
 * Being blocked, and whatever the lottery does about it, is the cost.
 */
public final class EndGateManager {
	private static final EndGateManager INSTANCE = new EndGateManager();

	/**
	 * After a correct answer, place eyes freely for a moment.
	 *
	 * <p>Doing double duty: it re-opens the interaction so the success can be
	 * replayed through vanilla (see {@link #resolve}), and it stops the
	 * replay re-entering this gate and asking again forever.
	 */
	private static final long UNLOCK_GRACE_MILLIS = 3_000L;

	private record Pending(int id, BlockPos pos, Hand hand, BlockHitResult hit,
			long deadline) { }

	private final Map<UUID, Pending> pending = new HashMap<>();
	private final Map<UUID, Long> unlockedUntil = new HashMap<>();

	private EndGateManager() {
	}

	public static EndGateManager get() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------
	public ActionResult onBlockUse(ServerPlayerEntity player, World world, Hand hand,
			BlockHitResult hit) {
		LexConfig config = LexConfig.get();
		if (!config.endGate()) {
			return ActionResult.PASS;
		}
		if (config.skipCreative() && player.isCreative()) {
			return ActionResult.PASS;
		}
		if (!player.getStackInHand(hand).isOf(Items.ENDER_EYE)) {
			return ActionResult.PASS;
		}

		BlockPos pos = hit.getBlockPos();
		BlockState state = world.getBlockState(pos);
		if (!state.isOf(Blocks.END_PORTAL_FRAME) || state.get(EndPortalFrameBlock.EYE)) {
			// Not a frame, or this one is already filled: nothing to gate.
			return ActionResult.PASS;
		}

		UUID uuid = player.getUuid();
		long now = System.currentTimeMillis();
		if (now < unlockedUntil.getOrDefault(uuid, 0L)) {
			return ActionResult.PASS;          // just earned it; let it through
		}
		if (pending.containsKey(uuid) || LimboManager.get().hasActiveChallenge(player)) {
			// FAIL, not PASS. Standing aside here hands the eye to vanilla and
			// places it for free: open any challenge at all, leave it hanging,
			// and all twelve frames fill untested. Exactly the bug the pickup
			// mixin shipped with -- see LimboManager.isBusy -- where "not now"
			// and "go ahead" were the same answer.
			Leximine.LOGGER.info("end gate withheld at {}: already answering something",
					pos.toShortString());
			player.sendMessage(Text.literal("Finish what you are saying first.")
					.formatted(Formatting.GRAY), true);
			return ActionResult.FAIL;
		}

		int id = LimboManager.get().nextChallengeId();
		int timeoutMs = ItemTiers.scaleTimeout(config.timeoutMs(), ItemTiers.LEGENDARY);
		pending.put(uuid, new Pending(id, pos, hand, hit, now + timeoutMs));

		ItemStack eye = new ItemStack(Items.ENDER_EYE);
		String key = eye.getItem().getTranslationKey();
		Leximine.LOGGER.info("end gate {} for {} at {}: {} (tier {})",
				id, player.getGameProfile().getName(), pos.toShortString(),
				key, ItemTiers.LEGENDARY);

		// The ordinary word path, at the top tier -- the client already knows
		// how to ask for a tier-4 sentence, so this needs no new packet.
		LexPackets.sendChallengeStart(player, id, eye, key, config.language(),
				config.difficulty(), ItemTiers.LEGENDARY, "end_gate", timeoutMs);

		world.playSound(null, pos, SoundEvents.BLOCK_END_PORTAL_FRAME_FILL,
				SoundCategory.BLOCKS, 0.7f, 0.6f);
		return ActionResult.FAIL;
	}

	// ------------------------------------------------------------------
	public boolean isPending(ServerPlayerEntity player, int id) {
		Pending open = pending.get(player.getUuid());
		return open != null && open.id() == id;
	}

	/**
	 * The open gate's id, or -1. For the dev test command only.
	 *
	 * <p>The speech path is covered elsewhere; what is unique here is what
	 * happens AFTER a verdict — the replay that places the eye and lets
	 * vanilla decide whether the portal lights. Driving that needs a way to
	 * answer a gate without a microphone and without a tier-4 sentence
	 * synthesised for whatever word the backend picked that run.
	 */
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
			Leximine.LOGGER.info("end gate {} refused at {}", id, open.pos().toShortString());
			player.sendMessage(Text.literal("The frame rejects it.")
					.formatted(Formatting.LIGHT_PURPLE), true);
			player.getWorld().playSound(null, open.pos(),
					SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.BLOCKS, 0.8f, 1.4f);
			LexPackets.sendChallengeEnd(player, id, false);
			// The hardest prompt in the game, so the hardest table answers for
			// it. The eye itself is kept -- see the class note. A broken streak
			// cannot make this any worse, since tier 4 is the top of the
			// ladder, but it is still passed and still reset: the run ends here
			// like anywhere else.
			Punishments.apply(player, ItemTiers.LEGENDARY, Streaks.broke(player));
			return;
		}

		Streaks.award(player);
		// Opened BEFORE the replay, for both of its reasons: the replay has to
		// get through this gate, and it must not be asked again on the way.
		unlockedUntil.put(uuid, System.currentTimeMillis() + UNLOCK_GRACE_MILLIS);
		LexPackets.sendChallengeEnd(player, id, true);
		Leximine.LOGGER.info("end gate {} passed at {}", id, open.pos().toShortString());

		// Replayed through the real interaction rather than setting EYE=true
		// here: vanilla's own use path is what notices the twelfth eye, checks
		// the frame pattern and lights the portal. Reimplementing that would
		// mean a second copy of the completion rule, and the copy would be the
		// one that goes stale.
		try {
			player.interactionManager.interactBlock(player, player.getWorld(),
					player.getStackInHand(open.hand()), open.hand(), open.hit());
		} catch (RuntimeException e) {
			// The grace window still lets them place it by hand.
			Leximine.LOGGER.debug("could not replay the eye placement", e);
		}
	}

	/**
	 * Server tick: give up on a gate whose verdict never arrived.
	 *
	 * <p>Same failure this mod has shipped twice before: an entry left in
	 * {@code pending} makes every later eye stand aside, so the gate would be
	 * silently off for the rest of the server's life with nothing logged.
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
			Leximine.LOGGER.info("end gate {} timed out with no verdict", open.id());
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
			if (player != null) {
				// No punishment on a timeout: nothing was heard, so nothing was
				// got wrong. Only a real verdict costs anything.
				LexPackets.sendChallengeEnd(player, open.id(), false);
			}
		}
	}

	public void onDisconnect(ServerPlayerEntity player) {
		pending.remove(player.getUuid());
		unlockedUntil.remove(player.getUuid());
	}
}
