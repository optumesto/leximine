package dev.mettle.leximine.review;

import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.limbo.LimboManager;
import dev.mettle.leximine.net.LexPackets;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.registry.tag.BlockTags;
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
 * Doors and chests ask you to prove a word before they open.
 *
 * <p>The word is not random: the backend picks one the player keeps getting
 * wrong, so an ordinary door becomes a spot quiz on their own weak spots.
 *
 * <p>Unlike a pickup challenge there is no item at stake — failing just means
 * the block stays shut. That is the point: it is a drill, not a punishment,
 * and it costs nothing to get wrong twice.
 */
public final class ReviewManager {
	private static final ReviewManager INSTANCE = new ReviewManager();

	/**
	 * After a correct answer the player gets a moment where nothing asks
	 * again. Replaying the interaction is attempted too, but a grace window
	 * means that failing leaves them able to simply click the door rather
	 * than stuck outside it.
	 */
	private static final long UNLOCK_GRACE_MILLIS = 12_000L;

	private record Pending(int id, BlockPos pos, Hand hand, BlockHitResult hit,
			long deadline) { }

	private final Map<UUID, Pending> pending = new HashMap<>();
	private final Map<UUID, Long> unlockedUntil = new HashMap<>();
	private final Map<UUID, Long> nextReviewAt = new HashMap<>();

	private ReviewManager() {
	}

	public static ReviewManager get() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------
	/** True for the everyday blocks worth gating: doors, chests, barrels. */
	public static boolean isReviewable(World world, BlockPos pos, BlockState state) {
		if (state.isIn(BlockTags.DOORS) || state.isIn(BlockTags.TRAPDOORS)
				|| state.isIn(BlockTags.FENCE_GATES)) {
			return true;
		}
		return world.getBlockEntity(pos) instanceof LockableContainerBlockEntity;
	}

	public ActionResult onBlockUse(ServerPlayerEntity player, World world, Hand hand,
			BlockHitResult hit) {
		LexConfig config = LexConfig.get();
		if (!config.reviewOnBlocks()) {
			return ActionResult.PASS;
		}
		if (config.skipCreative() && player.isCreative()) {
			return ActionResult.PASS;
		}

		BlockPos pos = hit.getBlockPos();
		if (!isReviewable(world, pos, world.getBlockState(pos))) {
			return ActionResult.PASS;
		}

		UUID uuid = player.getUuid();
		long now = System.currentTimeMillis();
		if (now < unlockedUntil.getOrDefault(uuid, 0L)) {
			return ActionResult.PASS;
		}
		if (now < nextReviewAt.getOrDefault(uuid, 0L)) {
			return ActionResult.PASS;
		}
		if (pending.containsKey(uuid) || LimboManager.get().hasActiveChallenge(player)) {
			// Already answering something. Let the block alone rather than
			// stacking a second prompt on top of the first.
			//
			// PASS is right HERE and wrong in the other two gates, so do not
			// "fix" it to match them. A review has nothing at stake: the door
			// opens, which is the same thing that happens when the backend is
			// down. Standing aside in the end gate or a negotiation instead
			// hands over an eye placement or a trade screen for free, so those
			// return FAIL.
			return ActionResult.PASS;
		}

		int id = LimboManager.get().nextChallengeId();
		int timeoutMs = config.timeoutMs();
		pending.put(uuid, new Pending(id, pos, hand, hit, now + timeoutMs));
		// Cooldown starts now, not on success: a failed review must not turn
		// into an interrogation every time they touch the handle.
		nextReviewAt.put(uuid, now + config.reviewCooldownSeconds() * 1000L);

		// Name the block on the overlay. A review has no item and no icon, so
		// without this it is indistinguishable from a pickup challenge -- on
		// the first recorded episode a furnace asked for "Arena" and it was
		// narrated as "I don't know when I picked up sand".
		String blockKey = player.getWorld().getBlockState(pos).getBlock().getTranslationKey();

		Leximine.LOGGER.info("review {} for {} at {} ({})",
				id, player.getGameProfile().getName(), pos.toShortString(), blockKey);
		LexPackets.sendReviewStart(player, id, config.language(), config.difficulty(),
				blockKey, timeoutMs);

		player.getWorld().playSound(null, pos, SoundEvents.BLOCK_CHEST_LOCKED,
				SoundCategory.BLOCKS, 0.5f, 1.4f);
		return ActionResult.FAIL;
	}

	// ------------------------------------------------------------------
	public boolean isPending(ServerPlayerEntity player, int id) {
		Pending open = pending.get(player.getUuid());
		return open != null && open.id() == id;
	}

	/**
	 * Note what is <em>not</em> here: a review does not touch the streak,
	 * either way.
	 *
	 * <p>Nothing is at stake in a review — the door opens regardless — so a
	 * correct one has not been earned against anything, and counting it would
	 * make the counter farmable by standing at a chest clicking. Equally a
	 * missed review must not end a run, because the player did not choose to
	 * be asked: the prompt arrives when the backend decides a word is due, and
	 * losing a fifteen-streak to a question you never opted into is the kind of
	 * thing that gets a mod switched off.
	 */
	public void resolve(ServerPlayerEntity player, int id, boolean success) {
		UUID uuid = player.getUuid();
		Pending open = pending.get(uuid);
		if (open == null || open.id() != id) {
			return;
		}
		pending.remove(uuid);

		if (!success) {
			player.sendMessage(Text.literal("Still locked. Try again in a moment.")
					.formatted(Formatting.RED), true);
			player.getWorld().playSound(null, open.pos(), SoundEvents.BLOCK_CHEST_LOCKED,
					SoundCategory.BLOCKS, 0.6f, 0.8f);
			LexPackets.sendChallengeEnd(player, id, false);
			return;
		}

		unlockedUntil.put(uuid, System.currentTimeMillis() + UNLOCK_GRACE_MILLIS);
		player.getWorld().playSound(null, open.pos(), SoundEvents.ENTITY_PLAYER_LEVELUP,
				SoundCategory.PLAYERS, 0.4f, 1.6f);
		LexPackets.sendChallengeEnd(player, id, true);

		// Finish what they were doing, so a correct answer opens the door
		// rather than merely permitting a second click.
		try {
			BlockState state = player.getWorld().getBlockState(open.pos());
			state.onUse(player.getWorld(), player, open.hand(), open.hit());
		} catch (RuntimeException e) {
			// The grace window above still lets them open it by hand.
			Leximine.LOGGER.debug("could not replay the block interaction", e);
		}
	}

	/**
	 * Server tick: give up on a review whose verdict never came.
	 *
	 * <p>Without this the entry sits in {@code pending} for the rest of the
	 * server's life, and because an open review makes every later one stand
	 * aside, doors and chests would quietly stop asking that player anything
	 * at all. Nothing breaks and nothing is logged, which is the worst way for
	 * a feature to die.
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
			Leximine.LOGGER.info("review {} timed out with no verdict", open.id());
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
			if (player != null) {
				// Closes the overlay if the client somehow still has it, but
				// no "still locked" message and no sound: this is not a
				// failed answer, it is an answer that never came, and the
				// player has long since gone back to whatever they were
				// doing.
				LexPackets.sendChallengeEnd(player, open.id(), false);
			}
		}
	}

	public void onDisconnect(ServerPlayerEntity player) {
		pending.remove(player.getUuid());
	}
}
