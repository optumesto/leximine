package dev.mettle.leximine.mixin;

import dev.mettle.leximine.limbo.LimboManager;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Takes an item away before it reaches the inventory, so the player has to
 * earn it by pronouncing its name.
 *
 * <p>Vanilla {@code onPlayerCollision} reads, in order:
 *
 * <pre>
 * if (this.pickupDelay == 0
 *         &amp;&amp; (this.owner == null || this.owner.equals(player.getUuid()))
 *         &amp;&amp; player.getInventory().insertStack(itemStack)) { ... }
 * </pre>
 *
 * <p>Injecting at HEAD runs <em>before</em> those two guards, so they are
 * re-checked here against shadowed fields. Skipping them would mean grabbing
 * items during their post-drop pickup delay — every block you mine, snatched
 * out of the air before it was ever collectable — and stealing items reserved
 * for another player.
 *
 * <p>The alternative, redirecting the {@code insertStack} call, would let
 * vanilla evaluate the guards for us but then requires emptying the stack
 * in place to stop vanilla re-colliding with an entity it thinks is still
 * full. That is more coupled to the exact shape of the method body than
 * re-checking two conditions.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	@Shadow
	private int pickupDelay;

	@Shadow
	@Nullable
	private UUID owner;

	@Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
	private void leximine$holdForPronunciation(PlayerEntity player, CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;

		if (self.getWorld().isClient || self.isRemoved()) {
			return;
		}
		if (!(player instanceof ServerPlayerEntity serverPlayer)) {
			return;
		}
		if (this.pickupDelay != 0) {
			return;
		}
		if (this.owner != null && !this.owner.equals(player.getUuid())) {
			return;
		}

		ItemStack stack = self.getStack();
		LimboManager limbo = LimboManager.get();

		if (limbo.bypasses(serverPlayer)) {
			return;                      // creative: vanilla collects it
		}
		if (limbo.isBusy(serverPlayer)) {
			// A challenge is open (or the retry gap is running). Leave the item
			// on the floor rather than letting vanilla insert it: returning
			// here WITHOUT cancelling is what used to hand over every other
			// item untested while one word was being answered. It re-collides
			// next tick, which is a map lookup.
			ci.cancel();
			return;
		}
		if (!limbo.shouldChallenge(serverPlayer, stack)) {
			return;                      // not due: vanilla collects it
		}

		int count = stack.getCount();
		if (!limbo.begin(serverPlayer, stack.copy(), "pickup")) {
			return;
		}

		// Vanilla's pickup presentation without the inventory insert: the item
		// visibly flies into the player and the entity goes away, but the
		// stack is in limbo rather than in a slot.
		serverPlayer.sendPickup(self, count);
		self.discard();
		ci.cancel();
	}
}
