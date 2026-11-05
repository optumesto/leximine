package dev.mettle.leximine.mixin;

import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.limbo.LimboManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterials;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolItem;
import net.minecraft.item.ToolMaterials;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Netherite has to be earned too.
 *
 * <p>A netherite upgrade is the one acquisition that skipped the whole system:
 * it is not a pickup, so {@code ItemEntityMixin} never sees it, and it is not
 * a crafting grid, so {@code CraftingResultSlotMixin} never sees it either.
 * The best gear in the game arrived without a word being said.
 *
 * <h2>Upgrades only, not trims</h2>
 *
 * The same table applies armour trims, and a trim is not an acquisition — the
 * player already owns the chestplate and is decorating it. Challenging that
 * would put their netherite armour in limbo for the sake of a pattern. So this
 * fires only when the result is netherite <em>and</em> is a different item
 * from what went in; trimming netherite returns the same item, upgrading
 * diamond does not.
 *
 * <h2>Why the result is captured in updateResult</h2>
 *
 * {@code onTakeOutput} is handed the stack that was taken, and on a
 * shift-click that stack has already been emptied into the inventory by
 * {@code ForgingScreenHandler.quickMove} — {@code insertItem} runs well before
 * {@code onTakeItem}. This is the same trap documented on
 * {@link CraftingResultSlotMixin}, and it is why the argument alone cannot be
 * trusted.
 *
 * <p>The snapshot is therefore taken in {@code updateResult}, which is the
 * last place the finished item is reliably whole. It is only overwritten when
 * the computed result is non-empty: taking the output decrements the inputs,
 * which fires {@code onContentChanged} and recomputes an <em>empty</em> result
 * before {@code onTakeOutput} ever runs, so an unconditional snapshot would
 * wipe itself a few instructions before being read.
 */
@Mixin(SmithingScreenHandler.class)
public abstract class SmithingScreenHandlerMixin {
	@Unique
	private ItemStack leximine$result = ItemStack.EMPTY;

	@Unique
	private ItemStack leximine$base = ItemStack.EMPTY;

	@Inject(method = "updateResult", at = @At("TAIL"))
	private void leximine$captureResult(CallbackInfo ci) {
		ScreenHandler self = (ScreenHandler) (Object) this;
		ItemStack result = self.getSlot(SmithingScreenHandler.OUTPUT_ID).getStack();
		if (result.isEmpty()) {
			// Keep the last real result; see the note above.
			return;
		}
		leximine$result = result.copy();
		leximine$base = self.getSlot(SmithingScreenHandler.EQUIPMENT_ID).getStack().copy();
	}

	@Inject(method = "onTakeOutput", at = @At("TAIL"))
	private void leximine$challengeUpgrade(PlayerEntity player, ItemStack stack,
			CallbackInfo ci) {
		ItemStack result = stack.isEmpty() ? leximine$result : stack.copy();
		ItemStack base = leximine$base;
		leximine$result = ItemStack.EMPTY;
		leximine$base = ItemStack.EMPTY;

		if (result.isEmpty() || !(player instanceof ServerPlayerEntity serverPlayer)) {
			return;
		}
		// Smithing is crafting as far as the player is concerned, so it answers
		// to the same switch rather than inventing a second one to forget.
		if (!LexConfig.get().challengeCrafting()) {
			return;
		}
		if (!leximine$isNetheriteUpgrade(result, base)) {
			return;
		}
		// TAIL rather than HEAD: by here a plain click has put the result on
		// the cursor and a shift-click has put it in the inventory, and
		// confiscate() looks in both -- cursor first.
		LimboManager.get().challengeAfterAcquiring(serverPlayer, result, result.getCount());
	}

	@Unique
	private static boolean leximine$isNetheriteUpgrade(ItemStack result, ItemStack base) {
		if (!leximine$isNetherite(result)) {
			return false;
		}
		// An empty base means the snapshot missed it; treat that as an upgrade
		// rather than silently letting netherite through.
		return base.isEmpty() || !base.isOf(result.getItem());
	}

	@Unique
	private static boolean leximine$isNetherite(ItemStack stack) {
		if (stack.getItem() instanceof ToolItem tool) {
			return tool.getMaterial() == ToolMaterials.NETHERITE;
		}
		if (stack.getItem() instanceof ArmorItem armor) {
			return armor.getMaterial() == ArmorMaterials.NETHERITE;
		}
		return false;
	}
}
