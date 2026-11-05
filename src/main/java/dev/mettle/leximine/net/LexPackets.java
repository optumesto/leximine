package dev.mettle.leximine.net;

import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.end.EndGateManager;
import dev.mettle.leximine.limbo.LimboManager;
import dev.mettle.leximine.nether.NetherGateManager;
import dev.mettle.leximine.review.ReviewManager;
import dev.mettle.leximine.review.TradeManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Client/server messages.
 *
 * <p>Uses the Identifier + {@link PacketByteBuf} networking API rather than
 * the {@code CustomPayload} codec style, because the latter only arrived in
 * 1.20.5 and this mod targets 1.20.4.
 *
 * <p>Note where authority sits: the <em>server</em> owns the item, the
 * language and the timeout, but the <em>client</em> owns the microphone, so
 * the client is the one that judges pronunciation and reports a verdict. In
 * single player that is all one process. On a real server a modified client
 * could lie about passing -- acceptable for a learning tool, and worth
 * knowing before anyone runs it competitively.
 */
public final class LexPackets {
	/** Server -> client: an item is in limbo, start the timer and show the word. */
	public static final Identifier CHALLENGE_START = Leximine.id("challenge_start");

	/** Client -> server: the player passed or failed, and what they were asked. */
	public static final Identifier CHALLENGE_RESOLVE = Leximine.id("challenge_resolve");

	/**
	 * Server -> client: prove a word before this door or chest opens.
	 *
	 * <p>Carries no translation key: which word to review is chosen by the
	 * backend from what the player keeps getting wrong, and only the client
	 * talks to the backend. The verdict comes back on
	 * {@link #CHALLENGE_RESOLVE} like any other.
	 */
	public static final Identifier REVIEW_START = Leximine.id("review_start");

	/**
	 * Server -> client: ask this merchant for something, in their language.
	 *
	 * <p>Judged by a language model rather than against a target word, so the
	 * client opens the challenge in trade mode and sends the whole sentence.
	 */
	public static final Identifier TRADE_START = Leximine.id("trade_start");

	/**
	 * Server -> client: this challenge is over, clear the overlay.
	 *
	 * <p>Sent on every outcome, including ones the client already knows about.
	 * The one that matters is the server-side timeout: if a client never
	 * answers, its HUD would otherwise sit on screen forever.
	 */
	public static final Identifier CHALLENGE_END = Leximine.id("challenge_end");

	private LexPackets() {
	}

	// ------------------------------------------------------------------
	public static void sendChallengeStart(ServerPlayerEntity player, int challengeId,
			ItemStack stack, String translationKey, String lang, String difficulty,
			int tier, String context, int timeoutMs) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(challengeId);
		buf.writeItemStack(stack);
		buf.writeString(translationKey);
		buf.writeString(lang);
		// Language, difficulty and tier all come from the server's settings,
		// so a client cannot quietly play on easier terms than the world is
		// set to.
		buf.writeString(difficulty);
		buf.writeVarInt(tier);
		buf.writeString(context);
		buf.writeVarInt(timeoutMs);
		ServerPlayNetworking.send(player, CHALLENGE_START, buf);
	}

	/**
	 * @param blockKey translation key of the block that asked, so the overlay
	 *                 can name it. A review arrives with nothing in the
	 *                 player's hands and no item icon, which made it read as a
	 *                 pickup for an item they never collected -- naming the
	 *                 furnace is what tells them where the prompt came from.
	 *                 The KEY rather than the rendered name, so the client
	 *                 localises it like every other block.
	 */
	public static void sendReviewStart(ServerPlayerEntity player, int challengeId,
			String lang, String difficulty, String blockKey, int timeoutMs) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(challengeId);
		buf.writeString(lang);
		buf.writeString(difficulty);
		buf.writeString(blockKey == null ? "" : blockKey);
		buf.writeVarInt(timeoutMs);
		ServerPlayNetworking.send(player, REVIEW_START, buf);
	}

	public static void sendTradeStart(ServerPlayerEntity player, int challengeId,
			String lang, String difficulty, String itemKey, int timeoutMs) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(challengeId);
		buf.writeString(lang);
		buf.writeString(difficulty);
		buf.writeString(itemKey);
		buf.writeVarInt(timeoutMs);
		ServerPlayNetworking.send(player, TRADE_START, buf);
	}

	/**
	 * Server -> client: the wheel has already been spun; here is where it lands.
	 *
	 * <p>Sent the instant a miss is rolled, carrying the tier and the index of
	 * the penalty within it (or -1 for a safe roll). The client spins for two
	 * seconds and locks onto that answer — the suspense is decoration over an
	 * outcome that is already decided, and the server fires the payload on its
	 * own schedule whether anyone is watching or not.
	 *
	 * <p>The index rather than the name, because both sides share
	 * {@link dev.mettle.leximine.limbo.Penalty} and a name on the wire would
	 * be a second source of truth for the one thing that must not disagree.
	 */
	public static final Identifier LOTTO_START = Leximine.id("lotto_start");

	public static void sendLottoStart(ServerPlayerEntity player, int tier, int penaltyIndex) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(tier);
		// Signed: -1 means the roll came up safe, which is the common case.
		buf.writeInt(penaltyIndex);
		ServerPlayNetworking.send(player, LOTTO_START, buf);
	}

	/**
	 * Server -> client: say a sentence that does this, in the target language.
	 *
	 * <p>Like {@link #TRADE_START} but the judgement is described by a task
	 * rather than implied by a merchant, and the overlay shows an instruction
	 * instead of an item. Its own packet rather than another field on
	 * TRADE_START, because a trade with an empty task and a task with an empty
	 * item would both be legal on the wire and only one of them is a real
	 * message.
	 */
	public static final Identifier SENTENCE_START = Leximine.id("sentence_start");

	public static void sendSentenceStart(ServerPlayerEntity player, int challengeId,
			String lang, String difficulty, String promptKey, String task, int timeoutMs) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(challengeId);
		buf.writeString(lang);
		buf.writeString(difficulty);
		// What the player reads, as a translation key so it localises.
		buf.writeString(promptKey);
		// What the model is told, in English, never shown.
		buf.writeString(task);
		buf.writeVarInt(timeoutMs);
		ServerPlayNetworking.send(player, SENTENCE_START, buf);
	}

	/**
	 * Server -> client: how many in a row, and the best ever.
	 *
	 * <p>Its own packet rather than a field on {@link #CHALLENGE_START},
	 * because the streak changes when a challenge <em>ends</em> and the overlay
	 * has to show the new number before the next one begins. Riding the start
	 * packet would always be one answer behind.
	 */
	public static final Identifier STREAK = Leximine.id("streak");

	public static void sendStreak(ServerPlayerEntity player, int current, int best) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(current);
		buf.writeVarInt(best);
		ServerPlayNetworking.send(player, STREAK, buf);
	}

	public static void sendChallengeEnd(ServerPlayerEntity player, int challengeId, boolean success) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(challengeId);
		buf.writeBoolean(success);
		ServerPlayNetworking.send(player, CHALLENGE_END, buf);
	}

	/** Called from the common initialiser; safe on a dedicated server. */
	public static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(CHALLENGE_RESOLVE,
				(server, player, handler, buf, responseSender) -> {
					// Read on the network thread, act on the server thread.
					int challengeId = buf.readVarInt();
					boolean success = buf.readBoolean();
					// The dictionary lives in the backend, which only the
					// client talks to, so the word comes back with the result
					// for the vocabulary list to display.
					String word = buf.readString(128);
					server.execute(() -> {
						// Reviews and item challenges draw ids from the same
						// counter, so whichever is waiting on this one owns it.
						if (ReviewManager.get().isPending(player, challengeId)) {
							ReviewManager.get().resolve(player, challengeId, success);
						} else if (TradeManager.get().isPending(player, challengeId)) {
							TradeManager.get().resolve(player, challengeId, success);
						} else if (EndGateManager.get().isPending(player, challengeId)) {
							// Must be asked before LimboManager: an end-gate
							// challenge is sent on the ordinary item packet, so
							// the fallback below would treat it as an item in
							// limbo and try to grant an eye nobody took.
							EndGateManager.get().resolve(player, challengeId, success);
						} else if (NetherGateManager.get().isPending(player, challengeId)) {
							// Before LimboManager for the same reason as the
							// end gate: this challenge rides a packet the
							// fallback would read as an item in limbo.
							NetherGateManager.get().resolve(player, challengeId, success);
						} else {
							LimboManager.get().resolve(player, challengeId, success, word);
						}
					});
				});
	}
}
