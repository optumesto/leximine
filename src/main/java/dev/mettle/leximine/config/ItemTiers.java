package dev.mettle.leximine.config;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterials;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolItem;
import net.minecraft.item.ToolMaterials;
import net.minecraft.registry.Registries;
import net.minecraft.util.Rarity;

import java.util.Set;

/**
 * How much the player has to say for a given item, and how much a miss costs.
 *
 * <ul>
 *   <li><b>1</b> the bare noun — "Pico"</li>
 *   <li><b>2</b> the full item name — "Espada de hierro"</li>
 *   <li><b>3</b> a sentence — "He encontrado una espada de diamante"</li>
 *   <li><b>4</b> a harder sentence, for the things a world yields once —
 *       "Después de mucho tiempo, he conseguido una élitra"</li>
 * </ul>
 *
 * <p>Tools and armour carry their material in the type system, so those are
 * read directly. Everything else is matched on the item id, and only then
 * falls back to {@link Rarity} — rarity alone would put a diamond and a lump
 * of dirt in the same bucket, since almost every ordinary item is COMMON.
 *
 * <p>This is the single place that decides stakes. {@code Punishments} reads
 * the same number, so what you are asked and what it costs can never drift
 * apart.
 */
public final class ItemTiers {
	public static final int NOUN = 1;
	public static final int FULL_NAME = 2;
	public static final int SENTENCE = 3;
	public static final int LEGENDARY = 4;

	/**
	 * The handful of things a world gives up once, if at all.
	 *
	 * <p>Checked before the tier-3 list, because "dragon_egg" would otherwise
	 * never be reached and an elytra would be scored as ordinary loot.
	 */
	private static final Set<String> LEGENDARY_MATERIALS = Set.of(
			"nether_star", "dragon_egg", "dragon_head", "elytra", "beacon",
			"totem", "heart_of_the_sea", "echo_shard", "enchanted_golden",
			"end_crystal", "conduit");

	/** Materials that make an ordinary item late-game regardless of its type. */
	private static final Set<String> SENTENCE_MATERIALS = Set.of(
			"netherite", "diamond", "ancient_debris", "shulker", "trident",
			"wither_skeleton_skull");

	private static final Set<String> FULL_NAME_MATERIALS = Set.of(
			"iron", "gold", "golden", "emerald", "copper", "amethyst",
			"quartz", "lapis", "obsidian", "ender", "blaze", "prismarine");

	private ItemTiers() {
	}

	public static int of(ItemStack stack) {
		if (stack.isEmpty()) {
			return NOUN;
		}

		if (stack.getItem() instanceof ToolItem tool
				&& tool.getMaterial() instanceof ToolMaterials material) {
			return switch (material) {
				case WOOD, STONE -> NOUN;
				case IRON, GOLD -> FULL_NAME;
				case DIAMOND, NETHERITE -> SENTENCE;
			};
		}

		if (stack.getItem() instanceof ArmorItem armor
				&& armor.getMaterial() instanceof ArmorMaterials material) {
			return switch (material) {
				case LEATHER, CHAIN, TURTLE -> NOUN;
				case IRON, GOLD -> FULL_NAME;
				case DIAMOND, NETHERITE -> SENTENCE;
			};
		}

		String id = Registries.ITEM.getId(stack.getItem()).getPath();
		// Legendary first: "dragon_egg" would otherwise never be reached.
		if (containsAny(id, LEGENDARY_MATERIALS)) {
			return LEGENDARY;
		}
		if (containsAny(id, SENTENCE_MATERIALS)) {
			return SENTENCE;
		}
		if (containsAny(id, FULL_NAME_MATERIALS)) {
			return FULL_NAME;
		}

		// Last resort. Catches modded and unusual items without needing them
		// listed above.
		return switch (stack.getRarity()) {
			case EPIC -> LEGENDARY;
			case RARE -> SENTENCE;
			case UNCOMMON -> FULL_NAME;
			default -> NOUN;
		};
	}

	private static boolean containsAny(String id, Set<String> needles) {
		for (String needle : needles) {
			if (id.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Longer prompts need longer to say.
	 *
	 * <p>A five-second window is generous for "Pico" and unreasonable for a
	 * whole sentence; leaving it fixed would make tier 3 fail on the clock
	 * rather than on pronunciation.
	 */
	public static int scaleTimeout(int baseMs, int tier) {
		return switch (tier) {
			case LEGENDARY -> Math.round(baseMs * 2.5f);
			case SENTENCE -> Math.round(baseMs * 2.0f);
			case FULL_NAME -> Math.round(baseMs * 1.3f);
			default -> baseMs;
		};
	}

	/** For logs and messages: "a stick" vs "a nether star". */
	public static String name(int tier) {
		return switch (tier) {
			case LEGENDARY -> "legendary";
			case SENTENCE -> "sentence";
			case FULL_NAME -> "full name";
			default -> "noun";
		};
	}
}
