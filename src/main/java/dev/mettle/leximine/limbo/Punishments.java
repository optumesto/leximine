package dev.mettle.leximine.limbo;

import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.config.ItemTiers;
import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.net.LexPackets;
import dev.mettle.leximine.progress.Streaks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.WorldEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The lotto: what happens, beyond losing the item, when a word is missed.
 *
 * <p>Losing the item is unconditional. On top of that the server rolls — by
 * default a 50% chance of one of five penalties drawn from the item's tier,
 * so any given penalty is a 10% event and a miss is a coin flip.
 *
 * <p>This was 20% (a 4% chance of any given payload), chosen so the rarity
 * made a punishment a threat rather than a tax. Raised to 50% on 2026-11-02
 * after the first recorded episode: the odds were tuned for play and are wrong
 * for filming. Mason set the system up on camera three times, deliberately
 * failed a word to demonstrate it, rolled safe, and ended up explaining to the
 * audience why nothing had happened. A mechanic the video cannot show is not a
 * mechanic the viewer believes in.
 *
 * <h2>Why the roll happens here and not on the client</h2>
 *
 * The client owns the microphone and judges the speech, so it is tempting to
 * roll there too. It cannot: every payload below spawns entities, moves the
 * player or edits their inventory, all of which are server-authoritative.
 * More to the point, a client that disconnects the instant it hears "fail"
 * would dodge the lotto entirely. So the server rolls, tells the client what
 * the wheel is going to land on, and schedules the payload itself.
 *
 * <p>The client's roulette is therefore <em>decoration over a decided
 * outcome</em>. If no client is listening, the punishment still lands on
 * time.
 *
 * <p>Nothing here fires unless a challenge was genuinely failed. An item
 * granted because the backend was down, or had no translation, is not a miss,
 * and punishing it would make an outage look like the player's fault.
 */
public final class Punishments {
	/** How long the client's wheel spins before it locks. Must match LottoRoulette. */
	public static final int ROLL_TICKS = 40;

	/** Default odds that a miss costs more than the item. 50 = a coin flip. */
	private static final int DEFAULT_CHANCE_PERCENT = 50;

	/**
	 * The tier a miss actually rolls on, given the streak it ended.
	 *
	 * <p>Separate and static so the rule can be read, and tested, without
	 * spawning a wither. Capped at {@link ItemTiers#LEGENDARY}: there is no
	 * tier 5 table, and {@code apply} already logs a warning and grants a free
	 * pass for a tier it has no penalties for, so an uncapped bump would turn
	 * the best run of the session into the one miss that costs nothing.
	 *
	 * <p>The threshold and the on/off switch live in {@link Streaks}, so the
	 * dependency runs one way — the lotto prices a streak, a streak knows
	 * nothing about the lotto. They pointed at each other for one compile and
	 * Java allowed it, which is exactly how that stops being noticed.
	 */
	public static int escalate(int tier, int brokenStreak) {
		if (!Streaks.stakeOn() || brokenStreak < Streaks.ESCALATE_AT) {
			return tier;
		}
		return Math.min(ItemTiers.LEGENDARY, tier + 1);
	}

	/**
	 * Asked for explicitly, and harmless at one roll per miss.
	 *
	 * <p>A plain {@link Random} would do — nobody is predicting punishment
	 * rolls — but a CSPRNG costs microseconds here and the call site runs at
	 * most a few times a minute.
	 */
	private static final Random RANDOM = new SecureRandom();

	/** How long the iron jail holds before it starts coming apart. */
	private static final int JAIL_DECAY_DELAY_TICKS = 5 * 20;

	/** And how fast it goes after that: one bar a second. */
	private static final int JAIL_DECAY_INTERVAL_TICKS = 20;

	/** Minecraft's clock: 24000 ticks to a day, dusk at 13000, midnight at 18000. */
	private static final long DAY_LENGTH_TICKS = 24_000L;
	private static final long DUSK_TICKS = 13_000L;
	private static final long MIDNIGHT_TICKS = 18_000L;

	private record Pending(UUID player, Penalty penalty, long executeAtTick) { }

	/** One block of a cage, and when it falls away. */
	private record Decaying(RegistryKey<World> world, BlockPos pos, long atTick) { }

	/**
	 * An outcome loaded into one player's next miss.
	 *
	 * <p>A record around a nullable penalty rather than a bare map value,
	 * because "armed, and the answer is a safe roll" and "not armed at all"
	 * are different states and one nullable value cannot hold both.
	 */
	private record Rig(Penalty penalty) {
		boolean safe() {
			return penalty == null;
		}
	}

	private static final List<Pending> PENDING = new ArrayList<>();
	private static final List<Decaying> DECAYING = new ArrayList<>();

	/**
	 * One-shot rigs, by player.
	 *
	 * <p>Per player rather than one global slot, so a second person on the
	 * server cannot walk off with the outcome queued up for a take.
	 *
	 * <p>Deliberately not dropped on disconnect, unlike {@link #PENDING}: a rig
	 * is an intention about the next miss, and a relog between takes should not
	 * quietly discard it. Equally deliberately not persisted, so it can never
	 * outlive the session that armed it and surprise someone next week.
	 */
	private static final Map<UUID, Rig> RIGGED = new HashMap<>();

	private Punishments() {
	}

	// ------------------------------------------------------------------
	/**
	 * Roll for this miss, tell the client, and schedule the payload.
	 *
	 * @param tier the item's tier, as used for the prompt
	 */
	public static void apply(ServerPlayerEntity player, int tier) {
		apply(player, tier, 0);
	}

	/**
	 * As above, but priced off the streak the miss just ended.
	 *
	 * <p>Past {@link #STREAK_ESCALATE_AT} the roll moves up a tier, so a long
	 * run makes the next slip worse rather than safer. That is the opposite of
	 * an insurance mechanic and it is the deliberate choice: a streak that
	 * protects you drains the tension out of exactly the stretch that has the
	 * most of it, while a streak that raises the stakes means "I am on
	 * fourteen" is a sentence with fear in it.
	 *
	 * <p>Passed in rather than read from {@link dev.mettle.leximine.progress.Streaks}
	 * here, because the streak has to be reset by the same code that prices the
	 * punishment from it — reading it in this method would depend on the caller
	 * not having reset it yet, which is an ordering rule nothing enforces and
	 * the sort of thing that breaks silently a month later.
	 *
	 * @param brokenStreak how many in a row the player had going, or 0
	 */
	public static void apply(ServerPlayerEntity player, int tier, int brokenStreak) {
		if (!enabled()) {
			return;
		}
		int effective = escalate(tier, brokenStreak);
		if (effective != tier) {
			Leximine.LOGGER.info("lotto: streak of {} broken, rolling tier {} instead of {}",
					brokenStreak, effective, tier);
			player.sendMessage(Text.literal("A streak of " + brokenStreak + ", gone.")
					.formatted(Formatting.GOLD), true);
			tier = effective;
		}
		Penalty[] pool = Penalty.forTier(tier);
		if (pool.length == 0) {
			// Every tier has a table now, so this means an item classified
			// outside 1-4 — worth a line in the log rather than a silent
			// free pass.
			Leximine.LOGGER.warn("lotto: no penalties for tier {}", tier);
			return;
		}

		Penalty rolled = roll(player, pool);
		Leximine.LOGGER.info("lotto: tier {} ({}) -> {}", tier, ItemTiers.name(tier),
				rolled == null ? "SAFE" : rolled.name());
		// The spin is told the penalty's own tier, not the item's. For an
		// honest roll these are the same, because the pool was built from the
		// item's tier. A rigged or forced outcome can cross tiers, and the
		// client resolves the name it locks onto from (tier, index) -- so
		// passing the item's tier there would spin the wrong five names and
		// lock onto the wrong one while the server fired the right payload.
		// A wheel that lies is worse than no wheel.
		spin(player, rolled == null ? tier : rolled.tier(), rolled);
	}

	/**
	 * Show the wheel and schedule the outcome. The roll is already decided.
	 *
	 * <p>Shared with the dev command so a test drives exactly the path a real
	 * miss does — packet, two-second delay and payload — with only the dice
	 * taken out of it.
	 */
	public static void spin(ServerPlayerEntity player, int tier, Penalty rolled) {
		LexPackets.sendLottoStart(player, tier,
				rolled == null ? -1 : Penalty.indexOf(rolled));
		if (rolled != null) {
			long at = player.getServer() == null
					? 0L : player.getServer().getTicks() + ROLL_TICKS;
			PENDING.add(new Pending(player.getUuid(), rolled, at));
		}
	}

	private static Penalty roll(ServerPlayerEntity player, Penalty[] pool) {
		// A rig beats the launch argument. It was typed seconds ago for this
		// take; the launch argument was set once, hours back, and is the
		// standing default of the two.
		Rig rig = RIGGED.remove(player.getUuid());
		if (rig != null) {
			Leximine.LOGGER.info("lotto: rigged to {} (one shot, now spent)",
					rig.safe() ? "SAFE" : rig.penalty().name());
			return rig.penalty();
		}

		String forced = System.getProperty("leximine.forcePenalty");
		if (forced != null && !forced.isBlank()) {
			// Test seam. At 4% per penalty there is otherwise no way to
			// exercise a specific payload without rolling for an hour.
			if ("none".equalsIgnoreCase(forced.trim())) {
				return null;
			}
			// Matched across every tier rather than only this item's pool.
			// Pool-only matching meant pinning a tier 4 name and then missing a
			// stone pickaxe fell silently through to an ordinary random roll --
			// the one failure mode of a test seam that makes a rigged run look
			// like a broken one. Safe to widen now that the wheel follows the
			// penalty's own tier.
			for (Penalty penalty : Penalty.values()) {
				if (penalty.name().equalsIgnoreCase(forced.trim())) {
					Leximine.LOGGER.info("lotto: forced to {} by launch argument",
							penalty.name());
					return penalty;
				}
			}
			Leximine.LOGGER.warn("lotto: -Dleximine.forcePenalty={} names no penalty;"
					+ " rolling normally", forced.trim());
		}
		return RANDOM.nextInt(100) < chancePercent() ? pool[RANDOM.nextInt(pool.length)] : null;
	}

	// ------------------------------------------------------------------
	/**
	 * Decide one player's next miss in advance, once.
	 *
	 * <p>For recording. The lotto is a 20% roll across five payloads, so a
	 * named one is a 4% event — not something you can wait for with a capture
	 * card running. This does not fake the miss: the challenge, the microphone
	 * and the verdict are all real, and the item is still lost. It only decides
	 * what the wheel lands on when the miss happens.
	 *
	 * <p>Spent the moment it fires, so the take after it rolls honestly again.
	 * Tier-independent: rig The Black Hole and fumble a dirt block, and the
	 * wheel will spin tier 4 names and the world will open up anyway.
	 *
	 * @param penalty the outcome to land on, or null for a guaranteed safe roll
	 */
	public static void rig(ServerPlayerEntity player, Penalty penalty) {
		RIGGED.put(player.getUuid(), new Rig(penalty));
	}

	/** @return true if a rig was actually armed and has now been dropped */
	public static boolean clearRig(ServerPlayerEntity player) {
		return RIGGED.remove(player.getUuid()) != null;
	}

	/** What is loaded for this player, or null if nothing is. */
	public static String describeRig(ServerPlayerEntity player) {
		Rig rig = RIGGED.get(player.getUuid());
		if (rig == null) {
			return null;
		}
		return rig.safe() ? "a safe roll" : rig.penalty().displayName();
	}

	/** 0-100. Overridable so a test can make every miss land. */
	private static int chancePercent() {
		String raw = System.getProperty("leximine.lottoChance");
		if (raw != null && !raw.isBlank()) {
			try {
				return Math.max(0, Math.min(100, Integer.parseInt(raw.trim())));
			} catch (NumberFormatException ignored) {
				// fall through
			}
		}
		return DEFAULT_CHANCE_PERCENT;
	}

	/**
	 * The setting is on/off now, not a menu.
	 *
	 * <p>The old values (effects/mob/tnt/both) all read as "on" so an existing
	 * config keeps working; only "none" turns the lotto off, which is what it
	 * always meant.
	 */
	/** For the settings screen: "on (1 in 5)" or "off". */
	public static String describe() {
		return enabled() ? "on (" + chancePercent() + "% chance of a penalty)" : "off";
	}

	/** Public so a command can say "nothing will fire" before you waste a take. */
	public static boolean enabled() {
		String raw = LexConfig.get().punishment();
		String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		return !(value.equals("none") || value.equals("off") || value.equals("false"));
	}

	// ------------------------------------------------------------------
	/**
	 * Server tick: fire anything whose wheel has finished spinning.
	 *
	 * <p>The payload reads the player's position <em>now</em>, not where they
	 * were when the roll happened. Movement is deliberately not frozen during
	 * the spin, so two seconds is plenty of time to be somewhere else, and a
	 * horde that arrives where the player used to be is no punishment at all.
	 */
	public static void tick(MinecraftServer server) {
		tickDecay(server);
		if (PENDING.isEmpty()) {
			return;
		}
		long now = server.getTicks();
		List<Pending> due = null;
		for (Pending entry : PENDING) {
			if (now >= entry.executeAtTick()) {
				(due == null ? due = new ArrayList<>() : due).add(entry);
			}
		}
		if (due == null) {
			return;
		}
		PENDING.removeAll(due);
		for (Pending entry : due) {
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.player());
			if (player == null || !player.isAlive()) {
				// Left, or died waiting. Either way there is nobody to punish.
				continue;
			}
			execute(player, entry.penalty());
		}
	}

	/**
	 * Take the iron jail apart, one bar at a time.
	 *
	 * <p>Deliberately not keyed to the player. A cage left standing because
	 * whoever was inside it logged out would be a permanent obstacle in
	 * someone's world, so the bars come away on their own schedule whatever
	 * happens to the person who earned them.
	 */
	private static void tickDecay(MinecraftServer server) {
		if (DECAYING.isEmpty()) {
			return;
		}
		long now = server.getTicks();
		List<Decaying> due = null;
		for (Decaying entry : DECAYING) {
			if (now >= entry.atTick()) {
				(due == null ? due = new ArrayList<>() : due).add(entry);
			}
		}
		if (due == null) {
			return;
		}
		DECAYING.removeAll(due);
		for (Decaying entry : due) {
			ServerWorld world = server.getWorld(entry.world());
			if (world == null) {
				continue;
			}
			BlockState state = world.getBlockState(entry.pos());
			// Only ever removes a bar this put there. If the player mined it,
			// or built something in its place, that is theirs now.
			if (!state.isOf(Blocks.IRON_BARS)) {
				continue;
			}
			world.setBlockState(entry.pos(), Blocks.AIR.getDefaultState());
			// The vanilla block-break effect, so a bar going is something you
			// see and hear rather than noticing after the fact.
			world.syncWorldEvent(WorldEvents.BLOCK_BROKEN, entry.pos(),
					Block.getRawIdFromState(state));
		}
	}

	public static void onDisconnect(ServerPlayerEntity player) {
		PENDING.removeIf(entry -> entry.player().equals(player.getUuid()));
	}

	// ------------------------------------------------------------------
	private static void execute(ServerPlayerEntity player, Penalty penalty) {
		if (!(player.getWorld() instanceof ServerWorld world)) {
			return;
		}
		Leximine.LOGGER.info("lotto: executing {} on {}", penalty.name(),
				player.getGameProfile().getName());
		try {
			switch (penalty) {
				case T1_SABOTAGE -> sabotage(player);
				case T1_HUNGER_PANGS -> hungerPangs(player);
				case T1_BUTTERFINGERS -> butterfingers(player, world);
				case T1_FRIGHT_NIGHT -> frightNight(player, world);
				case T1_THE_ITCH -> theItch(player, world);

				case T2_THE_CLASSIC -> theClassic(player, world);
				case T2_SEMANTIC_SABOTAGE -> semanticSabotage(player, world);
				case T2_THE_KNOCKBACK -> theKnockback(player);
				case T2_THE_DISARM -> theDisarm(player, world);
				case T2_THE_SWARM -> theSwarm(player, world);

				case T3_THE_HORDE -> theHorde(player, world);
				case T3_THE_PANIC_ROOM -> thePanicRoom(player, world);
				case T3_THE_IRON_JAIL -> theIronJail(player, world);
				case T3_SABOTAGE_ADVANCED -> sabotageAdvanced(player, world);
				case T3_SENSORY_DEPRIVATION -> sensoryDeprivation(player, world);

				case T4_THE_BOSS -> theBoss(player, world);
				case T4_DIMENSION_BAN -> dimensionBan(player, world);
				case T4_EQUIPMENT_CURSE -> equipmentCurse(player, world);
				case T4_THE_EVICTION -> theEviction(player, world);
				case T4_THE_BLACK_HOLE -> theBlackHole(player, world);
			}
		} catch (RuntimeException e) {
			// A failed payload must not take the server thread down with it.
			Leximine.LOGGER.warn("lotto payload {} failed", penalty.name(), e);
		}
		// Deliberately silent. The wheel locked onto this name a frame ago and
		// is still holding it on screen; an action-bar copy underneath just
		// says the same thing twice.
	}

	// --- Tier 1 ---------------------------------------------------------
	/** Junk, in place of the thing they just lost. */
	private static final Item[] JUNK = {
			Items.POISONOUS_POTATO, Items.DEAD_BUSH, Items.DIRT,
	};

	private static void sabotage(ServerPlayerEntity player) {
		ItemStack junk = new ItemStack(JUNK[RANDOM.nextInt(JUNK.length)]);
		if (!player.getInventory().insertStack(junk)) {
			player.dropItem(junk, false);
		}
	}

	private static void hungerPangs(ServerPlayerEntity player) {
		// Hunger II for 5s: amplifier 1, 100 ticks.
		player.addStatusEffect(new StatusEffectInstance(
				StatusEffects.HUNGER, 5 * 20, 1, false, true));
	}

	/**
	 * Knock whatever they are holding out of their hand and across the floor.
	 *
	 * <p>Falls back through the offhand and then the hotbar. An empty main
	 * hand is common — you are usually not holding the thing you just picked
	 * up — and without a fallback this would be a dud roll most of the time.
	 */
	private static void butterfingers(ServerPlayerEntity player, ServerWorld world) {
		ItemStack stack = takeSomethingHeld(player);
		if (stack.isEmpty()) {
			// Genuinely nothing to drop. Say so rather than appearing to do
			// nothing at all.
			player.sendMessage(Text.literal("Nothing to fumble.")
					.formatted(Formatting.GRAY), true);
			return;
		}
		ItemEntity dropped = player.dropItem(stack, false, false);
		if (dropped == null) {
			return;
		}
		// Thrown, not placed: a random horizontal heading at a speed that
		// carries it roughly five blocks before friction takes over.
		double heading = RANDOM.nextDouble() * Math.PI * 2;
		dropped.setVelocity(Math.cos(heading) * 0.55, 0.28, Math.sin(heading) * 0.55);
		dropped.setPickupDelay(40);
		world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP,
				SoundCategory.PLAYERS, 0.7f, 0.6f);
	}

	private static ItemStack takeSomethingHeld(ServerPlayerEntity player) {
		for (Hand hand : Hand.values()) {
			if (!player.getStackInHand(hand).isEmpty()) {
				ItemStack held = player.getStackInHand(hand).copy();
				player.setStackInHand(hand, ItemStack.EMPTY);
				return held;
			}
		}
		for (int slot = 0; slot < 9; slot++) {
			ItemStack candidate = player.getInventory().getStack(slot);
			if (!candidate.isEmpty()) {
				return player.getInventory().removeStack(slot);
			}
		}
		return ItemStack.EMPTY;
	}

	/** A noise with nothing behind it. The cheapest penalty in the game. */
	private static void frightNight(ServerPlayerEntity player, ServerWorld world) {
		SoundEvent sound = RANDOM.nextBoolean()
				? SoundEvents.ENTITY_CREEPER_PRIMED
				: SoundEvents.AMBIENT_CAVE.value();
		// Sent to this player only, at their own position, so it is
		// unmistakably aimed at them and does not startle the whole server.
		player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(
				net.minecraft.registry.entry.RegistryEntry.of(sound), SoundCategory.HOSTILE,
				player.getX(), player.getY(), player.getZ(), 1.0f, 0.9f,
				world.getRandom().nextLong()));
	}

	private static void theItch(ServerPlayerEntity player, ServerWorld world) {
		for (int i = 0; i < 3; i++) {
			spawnNear(world, player, EntityType.SILVERFISH, 1, 2);
		}
	}

	// --- Tier 2 ---------------------------------------------------------
	/** What the whole system used to do, now one option in five. */
	private static void theClassic(ServerPlayerEntity player, ServerWorld world) {
		effect(player, StatusEffects.NAUSEA, 15, 0);
		spawnNear(world, player, RANDOM.nextBoolean()
				? EntityType.ZOMBIE : EntityType.SKELETON, 2, 4);
	}

	/** Three fires at their feet. This spreads, and that is the point. */
	private static void semanticSabotage(ServerPlayerEntity player, ServerWorld world) {
		BlockPos feet = player.getBlockPos();
		int lit = 0;
		for (BlockPos candidate : new BlockPos[]{
				feet, feet.north(), feet.east(), feet.south(), feet.west(),
				feet.up(), feet.north().east(), feet.south().west()}) {
			if (lit >= 3) {
				break;
			}
			// Fire needs air to burn in and something under it to burn on.
			if (world.getBlockState(candidate).isAir()
					&& !world.getBlockState(candidate.down()).isAir()) {
				world.setBlockState(candidate, Blocks.FIRE.getDefaultState());
				lit++;
			}
		}
	}

	/**
	 * Fling them backwards and leave them slow.
	 *
	 * <p>A player's position is client-authoritative, so setting velocity on
	 * the server does nothing on its own — the change has to be pushed down
	 * explicitly or the client simply carries on walking.
	 */
	private static void theKnockback(ServerPlayerEntity player) {
		float yaw = player.getYaw() * ((float) Math.PI / 180f);
		// Backwards: the opposite of where they are looking.
		double strength = 1.6 + RANDOM.nextDouble() * 0.8;
		player.setVelocity(MathHelper.sin(yaw) * strength, 0.55,
				-MathHelper.cos(yaw) * strength);
		player.velocityModified = true;
		player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
		effect(player, StatusEffects.SLOWNESS, 10, 0);
	}

	/** Take a piece of armour off them and throw it. */
	private static void theDisarm(ServerPlayerEntity player, ServerWorld world) {
		List<EquipmentSlot> worn = new ArrayList<>();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (slot.getType() == EquipmentSlot.Type.ARMOR
					&& !player.getEquippedStack(slot).isEmpty()) {
				worn.add(slot);
			}
		}
		if (worn.isEmpty()) {
			player.sendMessage(Text.literal("Nothing to take off you.")
					.formatted(Formatting.GRAY), true);
			return;
		}
		EquipmentSlot chosen = worn.get(RANDOM.nextInt(worn.size()));
		ItemStack armour = player.getEquippedStack(chosen).copy();
		player.equipStack(chosen, ItemStack.EMPTY);

		double heading = RANDOM.nextDouble() * Math.PI * 2;
		ItemEntity thrown = new ItemEntity(world,
				player.getX() + Math.cos(heading) * 10.0,
				player.getY() + 1.0,
				player.getZ() + Math.sin(heading) * 10.0,
				armour);
		thrown.setPickupDelay(60);
		world.spawnEntity(thrown);
	}

	private static void theSwarm(ServerPlayerEntity player, ServerWorld world) {
		for (int i = 0; i < 3; i++) {
			spawnNear(world, player, EntityType.CAVE_SPIDER, 2, 4);
		}
	}

	// --- Tier 3 ---------------------------------------------------------
	private static void theHorde(ServerPlayerEntity player, ServerWorld world) {
		effect(player, StatusEffects.BLINDNESS, 15, 0);
		spawnNear(world, player, EntityType.CREEPER, 3, 6);
		for (int i = 0; i < 5; i++) {
			spawnNear(world, player, EntityType.ZOMBIE, 3, 7);
		}
		for (int i = 0; i < 5; i++) {
			spawnNear(world, player, EntityType.SKELETON, 3, 7);
		}
	}

	/** Five seconds to run, and no way to see where. */
	private static void thePanicRoom(ServerPlayerEntity player, ServerWorld world) {
		Vec3d at = player.getPos();
		TntEntity tnt = new TntEntity(world, at.x, at.y, at.z, player);
		tnt.setFuse(5 * 20);
		world.spawnEntity(tnt);
		effect(player, StatusEffects.SLOWNESS, 10, 0);
		effect(player, StatusEffects.BLINDNESS, 10, 0);
	}

	/**
	 * Bars on every side and an iron golem outside them.
	 *
	 * <p>Only air and replaceable blocks become bars: a cage is supposed to
	 * trap the player, not quietly eat the wall they were standing against.
	 *
	 * <p>It comes apart on its own — five seconds of being stuck, then one
	 * bar a second until it is open. Without that it is not a punishment but
	 * a softlock, because a player caught without a pickaxe has no way out of
	 * iron bars at all. Which bar goes first is random, so the way out is not
	 * something you can plan for while the golem works on you.
	 */
	private static void theIronJail(ServerPlayerEntity player, ServerWorld world) {
		BlockPos feet = player.getBlockPos();
		List<BlockPos> cage = new ArrayList<>();
		// The whole ring, diagonals included. Bars on only the four cardinal
		// sides look like a cage and are not one: a player is 0.6 blocks wide
		// and walks straight out through an open corner.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				for (int dy = 0; dy <= 1; dy++) {
					cage.add(feet.add(dx, dy, dz));
				}
			}
		}
		cage.add(feet.up(2));
		// And a floor, but only where there is not one already -- so they
		// cannot drop out of a cage built over a hole, without paving over
		// anything that was there.
		cage.add(feet.down());

		List<BlockPos> placed = new ArrayList<>();
		for (BlockPos pos : cage) {
			if (world.getBlockState(pos).isAir()
					|| world.getBlockState(pos).isReplaceable()) {
				world.setBlockState(pos, Blocks.IRON_BARS.getDefaultState());
				placed.add(pos.toImmutable());
			}
		}
		scheduleDecay(world, placed);

		Entity golem = EntityType.IRON_GOLEM.spawn(world, feet.north(2), SpawnReason.EVENT);
		if (golem instanceof IronGolemEntity angry) {
			angry.setTarget(player);
			angry.setAngryAt(player.getUuid());
			angry.setPersistent();
		}
	}

	private static void scheduleDecay(ServerWorld world, List<BlockPos> placed) {
		if (placed.isEmpty() || world.getServer() == null) {
			return;
		}
		// Shuffled so the opening appears somewhere unpredictable rather than
		// always unzipping in the order the cage happened to be built.
		List<BlockPos> order = new ArrayList<>(placed);
		Collections.shuffle(order, RANDOM);

		long base = world.getServer().getTicks() + JAIL_DECAY_DELAY_TICKS;
		for (int index = 0; index < order.size(); index++) {
			DECAYING.add(new Decaying(world.getRegistryKey(), order.get(index),
					base + (long) index * JAIL_DECAY_INTERVAL_TICKS));
		}
	}

	/** One lava source, at their feet. Permanent, and it burns what they drop. */
	private static void sabotageAdvanced(ServerPlayerEntity player, ServerWorld world) {
		BlockPos feet = player.getBlockPos();
		BlockPos target = world.getBlockState(feet).isAir()
				|| world.getBlockState(feet).isReplaceable() ? feet : feet.up();
		world.setBlockState(target, Blocks.LAVA.getDefaultState());
	}

	private static void sensoryDeprivation(ServerPlayerEntity player, ServerWorld world) {
		effect(player, StatusEffects.DARKNESS, 15, 0);
		// Mining Fatigue III is amplifier 2.
		effect(player, StatusEffects.MINING_FATIGUE, 30, 2);
		nightfall(world);
		for (int i = 0; i < 3; i++) {
			spawnNear(world, player, EntityType.PHANTOM, 4, 8);
		}
	}

	/**
	 * Take the rest of the day off them.
	 *
	 * <p>Not only for the mood. Phantoms burn in daylight, so the three
	 * spawned above were a real threat only if it already happened to be
	 * dark — in the middle of the afternoon this penalty was three seconds
	 * of darkness and some smoke.
	 *
	 * <p>Only ever moves forward, and only when it is actually daytime:
	 * rewinding the clock would un-advance everyone's night, and on a server
	 * this is felt by every player, not just the one who fumbled the word.
	 */
	private static void nightfall(ServerWorld world) {
		long now = world.getTimeOfDay();
		long into = Math.floorMod(now, DAY_LENGTH_TICKS);
		if (into >= DUSK_TICKS) {
			// Already night. Nothing to take.
			return;
		}
		world.setTimeOfDay(now - into + MIDNIGHT_TICKS);
	}

	// --- Tier 4 ---------------------------------------------------------
	private static void theBoss(ServerPlayerEntity player, ServerWorld world) {
		// At the player, as specified. A wither's spawn explosion is most of
		// the punishment.
		Entity wither = EntityType.WITHER.spawn(world, player.getBlockPos(),
				SpawnReason.EVENT);
		if (wither instanceof MobEntity mob) {
			mob.setTarget(player);
			mob.setPersistent();
		}
	}

	/** Straight up, and the ground is still where it was. */
	private static void dimensionBan(ServerPlayerEntity player, ServerWorld world) {
		player.teleport(world, player.getX(), player.getY() + 150.0, player.getZ(),
				player.getYaw(), player.getPitch());
		player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
	}

	/**
	 * Curse what they are wearing, mark them for a raid, and set vexes on them.
	 *
	 * <p>Bad Omen does nothing by itself — it fires when the player next walks
	 * into a village. That is deliberate here: it is the one penalty that
	 * follows them home.
	 */
	private static void equipmentCurse(ServerPlayerEntity player, ServerWorld world) {
		List<EquipmentSlot> worn = new ArrayList<>();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (slot.getType() == EquipmentSlot.Type.ARMOR
					&& !player.getEquippedStack(slot).isEmpty()) {
				worn.add(slot);
			}
		}
		if (!worn.isEmpty()) {
			ItemStack cursed = player.getEquippedStack(
					worn.get(RANDOM.nextInt(worn.size())));
			cursed.addEnchantment(Enchantments.BINDING_CURSE, 1);
			cursed.addEnchantment(Enchantments.VANISHING_CURSE, 1);
		}
		effect(player, StatusEffects.BAD_OMEN, 120, 4);
		for (int i = 0; i < 3; i++) {
			spawnNear(world, player, EntityType.VEX, 2, 5);
		}
	}

	/**
	 * Five thousand blocks in a random direction.
	 *
	 * <p>The landing Y is read from the destination's heightmap rather than
	 * kept from where they were standing. Carrying the old height over would
	 * drop them inside a mountain, which is a suffocation death rather than a
	 * relocation — the penalty is being a very long way from home, not dying
	 * on arrival.
	 */
	private static void theEviction(ServerPlayerEntity player, ServerWorld world) {
		double heading = RANDOM.nextDouble() * Math.PI * 2;
		double x = player.getX() + Math.cos(heading) * 5000.0;
		double z = player.getZ() + Math.sin(heading) * 5000.0;
		// Generates the destination chunk if it has never been visited, which
		// is why this is the slowest payload in the table.
		int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
				MathHelper.floor(x), MathHelper.floor(z));
		player.teleport(world, x, y + 1.0, z, player.getYaw(), player.getPitch());
	}

	/** Remove a sphere of world and let gravity explain the rest. */
	private static void theBlackHole(ServerPlayerEntity player, ServerWorld world) {
		BlockPos centre = player.getBlockPos();
		int radius = 3;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (dx * dx + dy * dy + dz * dz > radius * radius) {
						continue;
					}
					BlockPos pos = centre.add(dx, dy, dz);
					// Bedrock stays. Punching a hole in the bottom of the
					// world is a different kind of accident.
					if (world.getBlockState(pos).isOf(Blocks.BEDROCK)) {
						continue;
					}
					world.setBlockState(pos, Blocks.AIR.getDefaultState());
				}
			}
		}
	}

	// --- shared helpers -------------------------------------------------
	private static void effect(ServerPlayerEntity player, net.minecraft.entity.effect.StatusEffect type,
			int seconds, int amplifier) {
		player.addStatusEffect(new StatusEffectInstance(
				type, seconds * 20, amplifier, false, true));
	}

	/**
	 * Put a mob down somewhere it can actually stand.
	 *
	 * <p>Spawning at a blind offset drops mobs inside walls, where they take
	 * suffocation damage and die before the player ever sees them — a horde
	 * that quietly deletes itself. This tries a handful of spots in a ring
	 * and takes the first with air to stand in.
	 */
	private static boolean spawnNear(ServerWorld world, ServerPlayerEntity player,
			EntityType<?> type, int minRadius, int maxRadius) {
		for (int attempt = 0; attempt < 12; attempt++) {
			double heading = RANDOM.nextDouble() * Math.PI * 2;
			double distance = minRadius + RANDOM.nextDouble() * (maxRadius - minRadius);
			BlockPos pos = BlockPos.ofFloored(
					player.getX() + Math.cos(heading) * distance,
					player.getY(),
					player.getZ() + Math.sin(heading) * distance);
			for (int dy = 0; dy <= 2; dy++) {
				BlockPos candidate = pos.up(dy);
				if (world.getBlockState(candidate).isAir()
						&& world.getBlockState(candidate.up()).isAir()) {
					if (type.spawn(world, candidate, SpawnReason.EVENT) != null) {
						return true;
					}
				}
			}
		}
		// Nowhere clear: put it on the player rather than silently skipping.
		return type.spawn(world, player.getBlockPos(), SpawnReason.EVENT) != null;
	}

}
