package dev.mettle.leximine.mixin;

import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.limbo.LimboManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes a crafted item have to be earned too.
 *
 * <p>Taking the items back and raising the challenge is
 * {@link LimboManager#challengeAfterAcquiring}; all this does is work out
 * <em>what</em> and <em>how many</em> were just crafted. That turns out to be
 * the hard part, because the two ways of taking a crafted item share almost
 * no code:
 *
 * <ul>
 *   <li>a plain click goes {@code tryTakeStackRange} -> {@code takeStack} ->
 *       {@code onTakeItem}, with the result on the cursor;
 *   <li>a shift-click goes through {@code CraftingScreenHandler.quickMove},
 *       which merges the output into the inventory with {@code insertItem}
 *       and <strong>never calls {@code takeStack} at all</strong>, then calls
 *       {@code onQuickTransfer} and {@code onTakeItem}.
 * </ul>
 *
 * <p>An earlier version captured the count in {@code takeStack}, so
 * shift-clicking -- which is how most people craft -- was silently never
 * challenged.
 *
 * <p>The one thing both paths agree on is the slot's own {@code amount}
 * field, which every take route increments and {@code onCrafted(ItemStack)}
 * consumes and zeroes. Reading it at the head of {@code onCrafted} is
 * therefore the single place that sees every craft exactly once, with a stack
 * that is still populated: the shift-click route passes the untouched
 * <em>original</em> copy, while the argument handed to {@code onTakeItem} has
 * by then been emptied into the inventory.
 *
 * <p><strong>One challenge per craft, not per item.</strong> Shift-clicking
 * out sixty-four planks asks once, with all sixty-four at stake. Asking
 * sixty-four times would be unplayable; asking once while risking only one
 * would make crafting a way to dodge the game.
 */
@Mixin(CraftingResultSlot.class)
public abstract class CraftingResultSlotMixin {
	@Shadow
	private int amount;

	@Unique
	private ItemStack leximine$crafted = ItemStack.EMPTY;

	@Unique
	private int leximine$craftedCount;

	@Inject(method = "onCrafted(Lnet/minecraft/item/ItemStack;)V", at = @At("HEAD"))
	private void leximine$captureCraft(ItemStack stack, CallbackInfo ci) {
		// amount is zero on the second, bookkeeping-only call that the
		// shift-click route makes; ignoring it keeps the real capture.
		if (this.amount > 0 && stack != null && !stack.isEmpty()) {
			leximine$crafted = stack.copy();
			leximine$craftedCount = this.amount;
		}
	}

	@Inject(method = "onTakeItem", at = @At("TAIL"))
	private void leximine$challengeCraft(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
		ItemStack crafted = leximine$crafted;
		int count = leximine$craftedCount;
		leximine$crafted = ItemStack.EMPTY;
		leximine$craftedCount = 0;

		if (crafted.isEmpty() || count <= 0 || !(player instanceof ServerPlayerEntity serverPlayer)) {
			return;
		}
		if (!LexConfig.get().challengeCrafting()) {
			return;
		}
		LimboManager.get().challengeAfterAcquiring(serverPlayer, crafted, count);
	}
}
