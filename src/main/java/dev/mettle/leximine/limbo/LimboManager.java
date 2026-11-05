package dev.mettle.leximine.limbo;

import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.config.ItemTiers;
import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.net.LexPackets;
import dev.mettle.leximine.progress.LearnedWords;
import dev.mettle.leximine.progress.Streaks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side owner of every item currently held in limbo.
 *
 * <p>Single instance, only ever touched from the server thread. The one
 * invariant that matters: a stack handed to {@link #begin} has been removed
 * from the world and belongs to no inventory, so every path out of here must
 * either give it back or deliberately destroy it. There is no third option
 * that isn't an item-eating bug.
 *
 * <p>Whether to ask at all is decided by {@link LearnedWords}, not by a
 * cooldown kept here: the timer starts when a word is said <em>correctly</em>,
 * so a miss means being asked again on the next pickup.
 */
public final class LimboManager {
	private static final LimboManager INSTANCE = new LimboManager();

	/**
	 * Shortest gap between two challenges, regardless of settings. Mining
	 * drops a pile of items at once; without this, failing one word would
	 * fire a new challenge on the very next tick as the next item lands.
	 */
	private static final long RETRY_GAP_MILLIS = 2_500L;

	/**
	 * How long past the client's own countdown the server waits before giving
	 * up on a verdict.
	 *
	 * <p>The client is the one with the microphone, so its answer has to be
	 * allowed to arrive rather than being timed out from under it. Shared with
	 * the review and trade managers so all three give up on the same schedule.
	 */
	public static final long VERDICT_GRACE_MILLIS = 1_500L;

	private final Map<UUID, LimboChallenge> active = new HashMap<>();
	private final Map<UUID, Long> nextAllowed = new HashMap<>();
	private int nextId = 1;

	private LimboManager() {
	}

	public static LimboManager get() {
		return INSTANCE;
	}

	public boolean hasActiveChallenge(ServerPlayerEntity player) {
		return active.containsKey(player.getUuid());
	}

	/**
	 * Hands out the next challenge id.
	 *
	 * <p>Shared with the review flow so the two can never issue the same id,
	 * which is what lets one resolve packet serve both.
	 */
	public int nextChallengeId() {
		return nextId++;
	}

	// ------------------------------------------------------------------
	/**
	 * Does this player bypass the mod entirely right now?
	 *
	 * <p>Creative only. Separate from the two checks below because it means
	 * "let vanilla have it", where they mean "hold it back".
	 */
	public boolean bypasses(ServerPlayerEntity player) {
		return LexConfig.get().skipCreative() && player.isCreative();
	}

	/**
	 * Is a challenge in flight, so nothing else may be collected?
	 *
	 * <p>Split out of {@link #shouldChallenge} on 2026-11-02 because the two
	 * questions have opposite consequences and the caller could not tell them
	 * apart. "Not due" means the item should be picked up free; "busy" means it
	 * must not be picked up at all — and both returned false, so the pickup
	 * mixin let vanilla collect in both cases.
	 *
	 * <p>The effect was that <em>every item touched during an open challenge
	 * was granted untested</em>: mine a vein, answer for the first ore, pocket
	 * the rest for nothing. The comment here used to claim the opposite ("other
	 * items simply stay where they are"), which is now actually true.
	 *
	 * <p>Includes the retry gap, so a pile of drops cannot machine-gun
	 * challenges the moment one resolves — they wait on the floor instead.
	 */
	public boolean isBusy(ServerPlayerEntity player) {
		UUID uuid = player.getUuid();
		if (active.containsKey(uuid)) {
			return true;
		}
		Long allowedAt = nextAllowed.get(uuid);
		return allowedAt != null && System.currentTimeMillis() < allowedAt;
	}

	/** Is this word due to be asked? Call only when not {@link #isBusy}. */
	public boolean shouldChallenge(ServerPlayerEntity player, ItemStack stack) {
		if (stack.isEmpty() || bypasses(player) || isBusy(player)) {
			return false;
		}
		LexConfig config = LexConfig.get();
		return LearnedWords.get().isDue(player.getUuid(), config.language(),
				stack.getItem().getTranslationKey(),
				config.frequency(), config.askAgainUntilCorrect());
	}

	/**
	 * Takes the stack into limbo and tells the client to start asking.
	 *
	 * @param stack a stack the caller has already detached from the world
	 * @return true if limbo accepted it; the caller must not keep using it
	 */
	public boolean begin(ServerPlayerEntity player, ItemStack stack) {
		return begin(player, stack, "default");
	}

	/**
	 * @param context {@code pickup} or {@code craft}; chooses the sentence
	 *                frame at tier 3, so a crafted sword is "I am making..."
	 *                and a found one is "I have found..."
	 */
	public boolean begin(ServerPlayerEntity player, ItemStack stack, String context) {
		UUID uuid = player.getUuid();
		if (active.containsKey(uuid) || stack.isEmpty()) {
			return false;
		}

		LexConfig config = LexConfig.get();
		int id = nextChallengeId();
		String translationKey = stack.getItem().getTranslationKey();
		String lang = config.language();
		int tier = config.tiersEnabled() ? ItemTiers.of(stack) : ItemTiers.NOUN;
		int timeoutMs = ItemTiers.scaleTimeout(config.timeoutMs(), tier);
		long deadline = System.currentTimeMillis() + timeoutMs;
		active.put(uuid, new LimboChallenge(id, stack, translationKey, lang, tier, deadline));

		Leximine.LOGGER.info("challenge {} begun for {}: {} x{} ({}, tier {}, {}, {})",
				id, player.getGameProfile().getName(), translationKey, stack.getCount(),
				lang, tier, context, config.difficulty());
		LexPackets.sendChallengeStart(player, id, stack, translationKey, lang,
				config.difficulty(), tier, context, timeoutMs);
		return true;
	}

	/**
	 * Challenge something the player has <em>already</em> been given, taking it
	 * back first.
	 *
	 * <p>Crafting works this way because it cannot be gated beforehand: block
	 * the click and the ingredients go unspent, which turns a failed challenge
	 * into a free recipe and a passed one into duplication. So the craft
	 * happens, and this undoes it.
	 *
	 * @return true if a challenge was raised and the items were taken
	 */
	public boolean challengeAfterAcquiring(ServerPlayerEntity player, ItemStack acquired,
			int count) {
		if (acquired.isEmpty() || count <= 0) {
			return false;
		}
		ItemStack probe = acquired.copy();
		probe.setCount(count);
		if (!shouldChallenge(player, probe)) {
			return false;
		}

		int taken = confiscate(player, acquired, count);
		if (taken <= 0) {
			return false;
		}

		ItemStack held = acquired.copy();
		held.setCount(taken);
		if (!begin(player, held, "craft")) {
			// Limbo refused it (a challenge was already open); hand it straight
			// back rather than deleting what the player just earned.
			grant(player, held);
			return false;
		}
		player.currentScreenHandler.syncState();
		return true;
	}

	/**
	 * Remove up to {@code wanted} of an item from the player, cursor first.
	 *
	 * <p>Cursor first matters: a plain click leaves a crafted stack there, and
	 * only a shift-click puts it in the inventory.
	 *
	 * @return how many were actually taken
	 */
	public int confiscate(ServerPlayerEntity player, ItemStack like, int wanted) {
		int remaining = wanted;
		ScreenHandler handler = player.currentScreenHandler;

		ItemStack cursor = handler.getCursorStack();
		if (!cursor.isEmpty() && ItemStack.canCombine(cursor, like)) {
			int take = Math.min(remaining, cursor.getCount());
			cursor.decrement(take);
			remaining -= take;
			if (cursor.isEmpty()) {
				handler.setCursorStack(ItemStack.EMPTY);
			}
		}

		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
			ItemStack inSlot = inventory.getStack(slot);
			if (inSlot.isEmpty() || !ItemStack.canCombine(inSlot, like)) {
				continue;
			}
			int take = Math.min(remaining, inSlot.getCount());
			inSlot.decrement(take);
			remaining -= take;
			if (inSlot.isEmpty()) {
				inventory.setStack(slot, ItemStack.EMPTY);
			}
		}
		return wanted - remaining;
	}

	// ------------------------------------------------------------------
	/** Verdict from the client, or an internal timeout. Ignores stale ids. */
	public void resolve(ServerPlayerEntity player, int challengeId, boolean success) {
		resolve(player, challengeId, success, null);
	}

	public void resolve(ServerPlayerEntity player, int challengeId, boolean success, String word) {
		UUID uuid = player.getUuid();
		LimboChallenge challenge = active.get(uuid);
		if (challenge == null || challenge.id() != challengeId) {
			// Late verdict for a challenge already timed out, or a duplicate.
			return;
		}
		challenge.setDisplayWord(word);
		active.remove(uuid);
		nextAllowed.put(uuid, System.currentTimeMillis() + RETRY_GAP_MILLIS);

		// Recorded either way: the vocabulary list needs the misses too, and
		// this is what the "ask until correct" rule reads.
		LearnedWords.get().record(uuid, challenge.lang(), challenge.translationKey(),
				challenge.displayWord(), success);

		if (success) {
			grant(player, challenge.stack());
			Streaks.award(player);
		} else {
			// On failure the stack is deliberately dropped on the floor of
			// the universe: it was never in an inventory, so nothing else is
			// needed to destroy it. The consequence on top scales with how
			// much was at stake -- and with how much was going, which is what
			// the broken streak prices.
			Punishments.apply(player, challenge.tier(), Streaks.broke(player));
		}
		playSound(player, success);

		Leximine.LOGGER.info("challenge {} resolved: {}", challengeId,
				success ? "success" : "fail");
		LexPackets.sendChallengeEnd(player, challengeId, success);
	}

	private void grant(ServerPlayerEntity player, ItemStack stack) {
		if (!player.getInventory().insertStack(stack) && !stack.isEmpty()) {
			player.dropItem(stack, false);
		}
	}

	private void playSound(ServerPlayerEntity player, boolean success) {
		player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
				success ? SoundEvents.ENTITY_PLAYER_LEVELUP : SoundEvents.BLOCK_FIRE_EXTINGUISH,
				SoundCategory.PLAYERS,
				success ? 0.6f : 0.8f,
				success ? 1.2f : 1.1f);
	}

	// ------------------------------------------------------------------
	/** Server tick: fail anything whose clock ran out without a verdict. */
	public void tick(MinecraftServer server) {
		if (active.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		List<UUID> expired = null;
		for (Map.Entry<UUID, LimboChallenge> entry : active.entrySet()) {
			if (entry.getValue().isExpired(now)) {
				(expired == null ? expired = new ArrayList<>() : expired).add(entry.getKey());
			}
		}
		if (expired == null) {
			return;
		}
		for (UUID uuid : expired) {
			LimboChallenge challenge = active.get(uuid);
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
			if (player == null) {
				// Player vanished without a disconnect event firing; the stack
				// cannot be returned to anyone, so let it go rather than leak.
				active.remove(uuid);
				continue;
			}
			resolve(player, challenge.id(), false);
		}
	}

	/**
	 * Player left mid-challenge. The item is given back rather than destroyed:
	 * losing a diamond to a dropped connection is not part of the game.
	 */
	public void onDisconnect(ServerPlayerEntity player) {
		LimboChallenge challenge = active.remove(player.getUuid());
		if (challenge == null || challenge.stack().isEmpty()) {
			return;
		}
		// Dropped into the world rather than inserted: an inventory written at
		// disconnect time is not reliably saved, a dropped entity is.
		player.dropItem(challenge.stack(), false);
		Leximine.LOGGER.info("returned limbo item to {} on disconnect",
				player.getGameProfile().getName());
	}
}
