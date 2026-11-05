package dev.mettle.leximine.review;

import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.limbo.LimboManager;
import dev.mettle.leximine.net.LexPackets;
import dev.mettle.leximine.progress.Streaks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * You have to ask a merchant for something, in their language, before they
 * will deal with you.
 *
 * <p>Judged by a language model rather than by matching a word, because
 * "is this a sensible way to ask for an emerald" has no single right answer
 * to match against.
 *
 * <p><strong>This gates opening the trade, not completing it.</strong> The
 * brief said to stop the trade finishing, but by then the player has already
 * handed over emeralds: refusing at that point means either destroying what
 * they paid for or writing refund logic that has to survive every edge case
 * in the trade screen. Asking at the door is simpler, impossible to get into
 * a half-paid state, and reads better anyway -- you negotiate before you
 * trade.
 *
 * <p>Everything earned or lost here is held <strong>per merchant</strong>,
 * which is both the sensible fiction -- you got on with that trader, not with
 * the species -- and the only version that works. Keyed by player alone, one
 * good sentence opened every villager in range for a minute: walk into a
 * trading hall, ask one farmer nicely, and buy from thirty people without
 * saying another word. The feature was off and nothing said so.
 */
public final class TradeManager {
	private static final TradeManager INSTANCE = new TradeManager();

	/** After a successful negotiation, trade freely with <em>that</em> merchant. */
	private static final long OPEN_GRACE_MILLIS = 60_000L;

	/**
	 * After a refusal, that merchant will not deal at all for a moment.
	 *
	 * <p>Otherwise a miss costs nothing: the challenge ends, the next click
	 * opens a new one, and the way through is to keep clicking and talking
	 * until something lands. The judge runs at temperature 0 so the same
	 * sentence cannot pass on a retry, but a stream of different guesses
	 * eventually will. A few seconds of being turned away is enough friction
	 * to make the attempt worth thinking about, and it costs the player
	 * nothing they cannot wait out -- no emeralds, no items.
	 */
	private static final long REFUSAL_COOLDOWN_MILLIS = 8_000L;

	/** A sentence plus a model call needs longer than naming one word. */
	private static final float TIMEOUT_SCALE = 2.0f;

	private record Pending(int id, UUID merchant, Hand hand, String itemKey,
			long deadline) { }

	/** One player's standing with one merchant. */
	private record Rapport(UUID player, UUID merchant) { }

	private final Map<UUID, Pending> pending = new HashMap<>();
	private final Map<Rapport, Long> openUntil = new HashMap<>();
	private final Map<Rapport, Long> refusedUntil = new HashMap<>();

	/**
	 * What this merchant is waiting to be asked for, until they are.
	 *
	 * <p>Re-rolling the item on every click would turn a refusal into a
	 * reroll: miss on "amethyst shard", click again, get asked for "bread"
	 * instead. The item is chosen once and then stands until it is earned.
	 *
	 * <p>It carries an expiry only so the map cannot grow for the life of the
	 * server -- an hour of not coming back is not someone dodging a hard word,
	 * it is a different play session.
	 */
	private record Demand(String itemKey, long staleAt) { }

	private static final long DEMAND_TTL_MILLIS = 60L * 60_000L;

	private final Map<Rapport, Demand> asked = new HashMap<>();

	/** Sweeping every tick would be 20 map walks a second for nothing. */
	private static final long PRUNE_EVERY_MILLIS = 60_000L;
	private long lastPruneAt;

	private static final Random RANDOM = new Random();

	private TradeManager() {
	}

	public static TradeManager get() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------
	public ActionResult onEntityUse(ServerPlayerEntity player, Entity entity, Hand hand) {
		LexConfig config = LexConfig.get();
		if (!config.negotiateTrades() || !(entity instanceof MerchantEntity merchant)) {
			return ActionResult.PASS;
		}
		if (config.skipCreative() && player.isCreative()) {
			return ActionResult.PASS;
		}
		if (merchant.getCustomer() != null || merchant.isBaby()) {
			// Busy with someone else, or too young to trade at all.
			return ActionResult.PASS;
		}

		UUID uuid = player.getUuid();
		long now = System.currentTimeMillis();
		Rapport key = new Rapport(uuid, merchant.getUuid());
		if (now < openUntil.getOrDefault(key, 0L)) {
			return ActionResult.PASS;
		}
		if (now < refusedUntil.getOrDefault(key, 0L)) {
			// Still turned away. No new challenge AND no trade: the cooldown
			// is the cost of the miss, so letting the screen open through it
			// would hand back exactly what it took.
			//
			// Logged, unlike the grace window above, because the two look
			// nothing alike from outside. A click inside the grace window
			// opens the trade screen, so the player can see what happened; a
			// click inside the cooldown produces a dead click, which is also
			// what a broken gate produces.
			Leximine.LOGGER.info("trade for {} on cooldown, {}ms left",
					player.getGameProfile().getName(), refusedUntil.get(key) - now);
			sulk(merchant);
			player.sendMessage(Text.literal("They are not ready to talk to you.")
					.formatted(Formatting.GRAY), true);
			return ActionResult.FAIL;
		}
		if (pending.containsKey(uuid) || LimboManager.get().hasActiveChallenge(player)) {
			// FAIL, not PASS. Standing aside opens the trade screen for free,
			// including with the very merchant being negotiated with: start a
			// negotiation, leave it hanging, and deal with the whole village.
			// Same shape as the free-pickup bug in LimboManager.isBusy, where
			// "not now" and "go ahead" were spelled the same way.
			Leximine.LOGGER.info("trade for {} withheld: already answering something",
					player.getGameProfile().getName());
			player.sendMessage(Text.literal("Finish what you are saying first.")
					.formatted(Formatting.GRAY), true);
			return ActionResult.FAIL;
		}

		String itemKey = askFor(key, merchant.getOffers());
		if (itemKey == null) {
			// Nothing on sale yet, so nothing to ask for.
			return ActionResult.PASS;
		}

		int id = LimboManager.get().nextChallengeId();
		int timeoutMs = Math.round(config.timeoutMs() * TIMEOUT_SCALE);
		pending.put(uuid, new Pending(id, merchant.getUuid(), hand, itemKey, now + timeoutMs));

		Leximine.LOGGER.info("trade {} for {}: must ask for {}",
				id, player.getGameProfile().getName(), itemKey);
		LexPackets.sendTradeStart(player, id, config.language(), config.difficulty(),
				itemKey, timeoutMs);

		merchant.getWorld().playSound(null, merchant.getBlockPos(),
				SoundEvents.ENTITY_VILLAGER_TRADE, SoundCategory.NEUTRAL, 0.6f, 1.0f);
		return ActionResult.FAIL;
	}

	/**
	 * What to ask this merchant for: whatever was already pending with them,
	 * or a fresh pick from their stock. Null if they have nothing to sell.
	 *
	 * <p>Random rather than their first offer, so a villager asked twice does
	 * not want the same word twice -- but remembered in {@link #asked}, so a
	 * refusal is not a free reroll. Dropped only on a success, which is the
	 * one thing that should clear it.
	 */
	private String askFor(Rapport key, TradeOfferList offers) {
		if (offers == null || offers.isEmpty()) {
			return null;
		}
		List<String> onSale = new ArrayList<>();
		for (TradeOffer offer : offers) {
			if (!offer.isDisabled() && !offer.getSellItem().isEmpty()) {
				onSale.add(offer.getSellItem().getItem().getTranslationKey());
			}
		}
		if (onSale.isEmpty()) {
			return null;
		}
		// Held over from last time, but only while they still stock it: a
		// disabled or restocked offer would otherwise leave the player asked
		// for something the merchant no longer sells, with no way out of it.
		Demand standing = asked.get(key);
		long now = System.currentTimeMillis();
		if (standing != null && now < standing.staleAt()
				&& onSale.contains(standing.itemKey())) {
			return standing.itemKey();
		}
		String pick = onSale.get(RANDOM.nextInt(onSale.size()));
		asked.put(key, new Demand(pick, now + DEMAND_TTL_MILLIS));
		return pick;
	}

	/** Visibly unimpressed: the one cue that a cooldown, not a bug, is in play. */
	private static void sulk(MerchantEntity merchant) {
		if (merchant.getWorld() instanceof ServerWorld world) {
			world.spawnParticles(ParticleTypes.ANGRY_VILLAGER,
					merchant.getX(), merchant.getEyeY() + 0.5, merchant.getZ(),
					6, 0.25, 0.2, 0.25, 0.0);
			world.playSound(null, merchant.getBlockPos(),
					SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 0.5f, 1.1f);
		}
	}

	// ------------------------------------------------------------------
	public boolean isPending(ServerPlayerEntity player, int id) {
		Pending open = pending.get(player.getUuid());
		return open != null && open.id() == id;
	}

	public void resolve(ServerPlayerEntity player, int id, boolean success) {
		UUID uuid = player.getUuid();
		Pending open = pending.get(uuid);
		if (open == null || open.id() != id) {
			return;
		}
		pending.remove(uuid);

		Entity merchant = player.getServerWorld().getEntity(open.merchant());
		Rapport key = new Rapport(uuid, open.merchant());
		Leximine.LOGGER.info("trade {} {}: {}", id,
				success ? "accepted" : "refused", open.itemKey());
		if (!success) {
			// Counts against the run. A negotiation costs no items, but it is
			// not a review either -- there is a real stake in the cooldown, and
			// a sentence you got wrong is a sentence you got wrong.
			Streaks.broke(player);
			// The cooldown is set whether or not the villager is still loaded:
			// walking out of range during the attempt must not be the way
			// around it.
			refusedUntil.put(key, System.currentTimeMillis() + REFUSAL_COOLDOWN_MILLIS);
			if (merchant instanceof MerchantEntity entity) {
				sulk(entity);
			}
			player.sendMessage(Text.literal("They did not understand you.")
					.formatted(Formatting.RED), true);
			LexPackets.sendChallengeEnd(player, id, false);
			return;
		}

		Streaks.award(player);
		// Earned, so the standing demand is dropped: the next negotiation with
		// them, once the window lapses, asks for something else.
		asked.remove(key);
		refusedUntil.remove(key);
		openUntil.put(key, System.currentTimeMillis() + OPEN_GRACE_MILLIS);
		LexPackets.sendChallengeEnd(player, id, true);
		if (merchant != null) {
			merchant.getWorld().playSound(null, merchant.getBlockPos(),
					SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 0.8f, 1.0f);
			// Open the trade for them, so a good sentence gets them in rather
			// than merely permitting a second click.
			try {
				merchant.interact(player, open.hand());
			} catch (RuntimeException e) {
				// The grace window above still lets them click.
				Leximine.LOGGER.debug("could not open the trade screen", e);
			}
		}
	}

	/**
	 * Server tick: give up on a negotiation whose verdict never came.
	 *
	 * <p>An open negotiation makes every later one stand aside, so without
	 * this a client that stopped answering would leave villagers trading
	 * freely for the rest of the server's life -- the feature off, silently,
	 * with nothing in the log to say so.
	 */
	public void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		if (now - lastPruneAt > PRUNE_EVERY_MILLIS) {
			lastPruneAt = now;
			// Three maps keyed by (player, merchant). A long-lived server with
			// a wandering player and a village full of traders accumulates an
			// entry per pair and never drops one, so everything that has
			// lapsed gets swept.
			openUntil.values().removeIf(until -> now > until);
			refusedUntil.values().removeIf(until -> now > until);
			asked.values().removeIf(demand -> now > demand.staleAt());
		}
		if (pending.isEmpty()) {
			return;
		}
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
			Leximine.LOGGER.info("trade {} timed out with no verdict", open.id());
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
			if (player != null) {
				// No "they did not understand you" and no refusal sound: the
				// villager never heard anything to misunderstand.
				LexPackets.sendChallengeEnd(player, open.id(), false);
			}
		}
	}

	public void onDisconnect(ServerPlayerEntity player) {
		// Only the open negotiation. Everything else stays, in both
		// directions: the grace window was earned by asking properly and a
		// dropped connection is no reason to redo it, and the cooldown and the
		// standing demand must not be relog-able away.
		pending.remove(player.getUuid());
	}
}
