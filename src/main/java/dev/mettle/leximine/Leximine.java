package dev.mettle.leximine;

import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.end.EndGateManager;
import dev.mettle.leximine.limbo.LimboManager;
import dev.mettle.leximine.limbo.Punishments;
import dev.mettle.leximine.nether.NetherGateManager;
import dev.mettle.leximine.net.LexPackets;
import dev.mettle.leximine.progress.LearnedWords;
import dev.mettle.leximine.progress.Streaks;
import dev.mettle.leximine.review.ReviewManager;
import dev.mettle.leximine.review.TradeManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Loaded on both the client and a dedicated server.
 *
 * <p>Item pickup and crafting are both server-authoritative, so the
 * interception lives here rather than in the client source set. Anything that
 * touches the screen or the microphone stays in {@code src/client}.
 */
public class Leximine implements ModInitializer {
	public static final String MOD_ID = "leximine";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		LexConfig.load();
		LearnedWords.get().load();
		Streaks.get().load();

		LexPackets.registerServerReceivers();
		LexCommand.register();

		// Safety net for a client that never answers. Without it a lost
		// connection or a crashed backend leaves the item in limbo and the
		// player unable to pick anything else up -- and, for the other two,
		// leaves an open challenge that makes every later one stand aside, so
		// doors and villagers quietly stop asking anything at all.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			LimboManager.get().tick(server);
			ReviewManager.get().tick(server);
			TradeManager.get().tick(server);
			EndGateManager.get().tick(server);
			NetherGateManager.get().tick(server);
			// Fires lotto payloads two seconds after the roll, which is when
			// the client's wheel stops. Runs whether or not anyone is
			// watching the animation.
			Punishments.tick(server);
		});

		// Hand the item back rather than eating it.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			LimboManager.get().onDisconnect(handler.getPlayer());
			ReviewManager.get().onDisconnect(handler.getPlayer());
			TradeManager.get().onDisconnect(handler.getPlayer());
			EndGateManager.get().onDisconnect(handler.getPlayer());
			NetherGateManager.get().onDisconnect(handler.getPlayer());
			Punishments.onDisconnect(handler.getPlayer());
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(
				server -> LearnedWords.get().saveIfDirty());

		// Doors and chests ask you to prove a word the backend has seen you
		// struggle with. Server-side only: the client fires its own use event
		// as a prediction, and acting on that would double every prompt.
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer)) {
				return ActionResult.PASS;
			}
			// The end gate goes first: putting an eye in a frame is a block use
			// too, and it is the far more specific claim on this interaction.
			// Reviews would otherwise get there first and ask about a stick
			// while the player is standing at the portal.
			ActionResult gated = EndGateManager.get().onBlockUse(serverPlayer, world, hand, hit);
			if (gated != ActionResult.PASS) {
				return gated;
			}
			// Same reasoning, same order: striking obsidian is a block use too.
			gated = NetherGateManager.get().onBlockUse(serverPlayer, world, hand, hit);
			if (gated != ActionResult.PASS) {
				return gated;
			}
			return ReviewManager.get().onBlockUse(serverPlayer, world, hand, hit);
		});

		// Merchants want to be asked properly before they will deal.
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer)) {
				return ActionResult.PASS;
			}
			return TradeManager.get().onEntityUse(serverPlayer, entity, hand);
		});

		LexConfig config = LexConfig.get();
		LOGGER.info("Leximine {} loaded (common): language {}, {} ({}), timeout {}s, crafting {}",
				LeximineMeta.version(), config.language(), config.frequency().id,
				config.askAgainUntilCorrect() ? "ask until correct" : "timer on every attempt",
				config.timeoutSeconds(),
				config.challengeCrafting() ? "on" : "off");
	}
}
