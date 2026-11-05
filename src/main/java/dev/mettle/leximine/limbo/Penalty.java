package dev.mettle.leximine.limbo;

import dev.mettle.leximine.config.ItemTiers;

import java.util.Locale;

/**
 * The lotto table: five possible penalties per tier, named.
 *
 * <p>Lives in the common source set on purpose. The server rolls and executes,
 * the client spins a roulette through the five names for that tier — and if
 * the names were declared twice they would drift, which is the one bug that
 * would make the wheel lie about what it landed on.
 *
 * <p><b>Order is part of the wire format.</b> The server sends the index
 * within a tier, not the name, so inserting a penalty in the middle of a tier
 * renames every later one on an out-of-date client. Append within a tier.
 */
public enum Penalty {
	// --- Tier 1: wood and stone. Nuisances, nothing that costs a life. ---
	T1_SABOTAGE(ItemTiers.NOUN, "The Sabotage"),
	T1_HUNGER_PANGS(ItemTiers.NOUN, "Hunger Pangs"),
	T1_BUTTERFINGERS(ItemTiers.NOUN, "Butterfingers"),
	T1_FRIGHT_NIGHT(ItemTiers.NOUN, "Fright Night"),
	T1_THE_ITCH(ItemTiers.NOUN, "The Itch"),

	// --- Tier 2: iron and gold. Things that can actually kill you. ---
	T2_THE_CLASSIC(ItemTiers.FULL_NAME, "The Classic"),
	T2_SEMANTIC_SABOTAGE(ItemTiers.FULL_NAME, "Semantic Sabotage"),
	T2_THE_KNOCKBACK(ItemTiers.FULL_NAME, "The Knockback"),
	T2_THE_DISARM(ItemTiers.FULL_NAME, "The Disarm"),
	T2_THE_SWARM(ItemTiers.FULL_NAME, "The Swarm"),

	// --- Tier 3: diamond and netherite. Things that usually do. ---
	T3_THE_HORDE(ItemTiers.SENTENCE, "The Horde"),
	T3_THE_PANIC_ROOM(ItemTiers.SENTENCE, "The Panic Room"),
	T3_THE_IRON_JAIL(ItemTiers.SENTENCE, "The Iron Jail"),
	T3_SABOTAGE_ADVANCED(ItemTiers.SENTENCE, "The Sabotage"),
	T3_SENSORY_DEPRIVATION(ItemTiers.SENTENCE, "Sensory Deprivation"),

	// --- Tier 4: the once-in-a-world items. Run-enders. ---
	T4_THE_BOSS(ItemTiers.LEGENDARY, "The Boss"),
	T4_DIMENSION_BAN(ItemTiers.LEGENDARY, "Dimension Ban"),
	T4_EQUIPMENT_CURSE(ItemTiers.LEGENDARY, "Equipment Curse"),
	T4_THE_EVICTION(ItemTiers.LEGENDARY, "The Eviction"),
	T4_THE_BLACK_HOLE(ItemTiers.LEGENDARY, "The Black Hole");

	private final int tier;
	private final String displayName;

	Penalty(int tier, String displayName) {
		this.tier = tier;
		this.displayName = displayName;
	}

	public int tier() {
		return tier;
	}

	/** Shown on the roulette, exactly as written in the design matrix. */
	public String displayName() {
		return displayName;
	}

	/** The name as the roulette locks it in: "THE ITCH". */
	public String lockedName() {
		return displayName.toUpperCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------
	private static final Penalty[] ALL = values();

	/** Every penalty for a tier, in wire order. Empty if the tier has no pool. */
	public static Penalty[] forTier(int tier) {
		int count = 0;
		for (Penalty penalty : ALL) {
			if (penalty.tier == tier) {
				count++;
			}
		}
		Penalty[] pool = new Penalty[count];
		int index = 0;
		for (Penalty penalty : ALL) {
			if (penalty.tier == tier) {
				pool[index++] = penalty;
			}
		}
		return pool;
	}

	/**
	 * The penalty at {@code index} within {@code tier}, or null.
	 *
	 * <p>Null rather than an exception: the index arrives over the network,
	 * and a client one version out of date should show nothing rather than
	 * crash someone's game.
	 */
	public static Penalty byIndex(int tier, int index) {
		Penalty[] pool = forTier(tier);
		return index >= 0 && index < pool.length ? pool[index] : null;
	}

	public static int indexOf(Penalty penalty) {
		Penalty[] pool = forTier(penalty.tier);
		for (int index = 0; index < pool.length; index++) {
			if (pool[index] == penalty) {
				return index;
			}
		}
		return -1;
	}
}
