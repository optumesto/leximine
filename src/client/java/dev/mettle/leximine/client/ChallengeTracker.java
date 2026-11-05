package dev.mettle.leximine.client;

import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.config.LexConfig;
import dev.mettle.leximine.net.LexPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Client-side state machine for the item currently in limbo, and the bridge
 * between the Minecraft server, the microphone and the speech backend.
 *
 * <p>Three parties have to agree on one item:
 *
 * <ul>
 *   <li>the Minecraft server owns the stack and the authoritative deadline,
 *   <li>the speech backend owns the word and the verdict,
 *   <li>this class owns the microphone and tells the server what happened.
 * </ul>
 *
 * <p>The guiding rule for every failure path is <em>fail open</em>: if the
 * backend is down, has no translation, or there is no working microphone, the
 * player gets the item. A language tool that eats your diamonds because a
 * Python process was not running is worse than one that occasionally gives a
 * word away.
 */
public final class ChallengeTracker implements LexSocket.Listener {
	private static final ChallengeTracker INSTANCE = new ChallengeTracker();

	/** How long the pass/fail flash stays on screen after a verdict. */
	private static final long FLASH_MILLIS = 900L;

	/**
	 * Longer when the correct pronunciation is being played, so the word stays
	 * on screen while the player hears it rather than vanishing mid-syllable.
	 */
	private static final long FLASH_WITH_AUDIO_MILLIS = 2_800L;

	/**
	 * Long enough to actually read a sentence the judge wrote.
	 *
	 * <p>900 ms is fine for "Nope" and a word. A merchant's reason runs to a
	 * line or two, and showing someone an explanation for under a second is
	 * the same as not showing it.
	 */
	private static final long FLASH_WITH_NOTE_MILLIS = 3_600L;

	/**
	 * Grace after the local clock expires, waiting for the backend's final
	 * pass to come back before giving up on it.
	 */
	private static final long FINAL_PASS_GRACE_MILLIS = 1_500L;

	private final AudioCapture audio = new AudioCapture();

	private volatile ClientChallenge current;
	private long flashUntilMillis;
	private boolean flashSuccess;
	private String flashTranscript = "";
	private String flashWord = "";
	private String flashNote = "";

	private long endSentAtMillis;
	private boolean warnedAboutMicrophone;

	private ChallengeTracker() {
	}

	public static ChallengeTracker get() {
		return INSTANCE;
	}

	public ClientChallenge current() {
		return current;
	}

	public boolean isFlashing() {
		return System.currentTimeMillis() < flashUntilMillis;
	}

	public boolean flashWasSuccess() {
		return flashSuccess;
	}

	public String flashTranscript() {
		return flashTranscript;
	}

	/** The word being spoken back after a miss, or blank. */
	public String flashWord() {
		return flashWord;
	}

	/**
	 * Why a merchant refused, kept past the end of the challenge.
	 *
	 * <p>The panel is gone by the time the player has read anything -- the
	 * verdict closes it -- so the reason has to survive into the flash or it
	 * is never seen at all.
	 */
	public String flashNote() {
		return flashNote;
	}

	// ------------------------------------------------------------------
	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(LexPackets.CHALLENGE_START,
				(client, handler, buf, responseSender) -> {
					// Read off the buffer on the network thread; it is recycled
					// as soon as this returns.
					int id = buf.readVarInt();
					ItemStack stack = buf.readItemStack();
					String key = buf.readString();
					String lang = buf.readString();
					String difficulty = buf.readString();
					int tier = buf.readVarInt();
					String context = buf.readString();
					int timeoutMs = buf.readVarInt();
					client.execute(() -> INSTANCE.onChallengeStart(
							id, stack, key, lang, difficulty, tier, context, timeoutMs));
				});

		ClientPlayNetworking.registerGlobalReceiver(LexPackets.REVIEW_START,
				(client, handler, buf, responseSender) -> {
					int id = buf.readVarInt();
					String lang = buf.readString();
					String difficulty = buf.readString();
					String blockKey = buf.readString();
					int timeoutMs = buf.readVarInt();
					client.execute(() ->
							INSTANCE.onReviewStart(id, lang, difficulty, blockKey, timeoutMs));
				});

		ClientPlayNetworking.registerGlobalReceiver(LexPackets.TRADE_START,
				(client, handler, buf, responseSender) -> {
					int id = buf.readVarInt();
					String lang = buf.readString();
					String difficulty = buf.readString();
					String itemKey = buf.readString();
					int timeoutMs = buf.readVarInt();
					client.execute(() ->
							INSTANCE.onTradeStart(id, lang, difficulty, itemKey, timeoutMs));
				});

		ClientPlayNetworking.registerGlobalReceiver(LexPackets.SENTENCE_START,
				(client, handler, buf, responseSender) -> {
					int id = buf.readVarInt();
					String lang = buf.readString();
					String difficulty = buf.readString();
					String promptKey = buf.readString();
					String task = buf.readString(512);
					int timeoutMs = buf.readVarInt();
					client.execute(() -> INSTANCE.onSentenceStart(
							id, lang, difficulty, promptKey, task, timeoutMs));
				});

		ClientPlayNetworking.registerGlobalReceiver(LexPackets.STREAK,
				(client, handler, buf, responseSender) -> {
					int current = buf.readVarInt();
					int best = buf.readVarInt();
					client.execute(() -> INSTANCE.onStreak(current, best));
				});

		ClientPlayNetworking.registerGlobalReceiver(LexPackets.LOTTO_START,
				(client, handler, buf, responseSender) -> {
					int tier = buf.readVarInt();
					int penaltyIndex = buf.readInt();
					client.execute(() -> {
						// The transcript never leaves the client, so the wheel
						// takes it from the challenge that just failed rather
						// than the server having to send it back.
						ClientChallenge failed = INSTANCE.current;
						LottoRoulette.get().start(tier, penaltyIndex,
								failed == null ? INSTANCE.flashTranscript
										: failed.heardText());
					});
				});

		ClientPlayNetworking.registerGlobalReceiver(LexPackets.CHALLENGE_END,
				(client, handler, buf, responseSender) -> {
					int id = buf.readVarInt();
					boolean success = buf.readBoolean();
					client.execute(() -> INSTANCE.onChallengeEnd(id, success));
				});
	}

	// ------------------------------------------------------------------
	private void onChallengeStart(int id, ItemStack stack, String translationKey, String lang,
			String difficulty, int tier, String context, int timeoutMs) {
		ClientChallenge challenge = new ClientChallenge(id, stack, translationKey, lang, timeoutMs);
		challenge.setTier(tier);
		current = challenge;
		endSentAtMillis = 0L;

		// The language comes from the server's settings rather than the
		// client's, so the two can never ask and answer in different ones.
		if (!LexSocket.get().startChallenge(
				id, translationKey, lang, difficulty, tier, context, timeoutMs)) {
			notifyPlayer(Text.literal("Leximine: speech backend offline, item granted")
					.formatted(Formatting.YELLOW));
			Leximine.LOGGER.warn("Backend unusable; granting {} unchallenged", translationKey);
			sendVerdict(challenge, true);
		}
	}

	/**
	 * A door or chest wants a word proved first.
	 *
	 * <p>Which word is the backend's call, so it is fetched here rather than
	 * arriving in the packet: the server does not know what the player keeps
	 * getting wrong, and only this side talks to the backend.
	 */
	private void onReviewStart(int id, String lang, String difficulty, String blockKey,
			int timeoutMs) {
		ClientChallenge challenge = new ClientChallenge(
				id, ItemStack.EMPTY, "", lang, timeoutMs);
		challenge.setReview(true);
		challenge.setBlockKey(blockKey);
		current = challenge;
		endSentAtMillis = 0L;

		ReviewLookup.pick(lang).whenComplete((picked, error) ->
				MinecraftClient.getInstance().execute(() -> {
					if (current != challenge) {
						return;
					}
					if (error != null || picked.isEmpty()) {
						// No history yet, or the backend is unreachable.
						// Opening the door is the fair outcome either way.
						Leximine.LOGGER.info(
								"No review word available; letting the block open");
						sendVerdict(challenge, true);
						return;
					}
					ReviewLookup.Word word = picked.get();
					challenge.setTranslationKey(word.key());
					if (!LexSocket.get().startChallenge(
							id, word.key(), lang, difficulty, 1, "review", timeoutMs)) {
						sendVerdict(challenge, true);
					}
				}));
	}

	/**
	 * A merchant wants to be asked properly before trading.
	 *
	 * <p>Opened in trade mode, so the backend judges the whole sentence with
	 * a language model instead of matching it against one word.
	 */
	private void onTradeStart(int id, String lang, String difficulty, String itemKey,
			int timeoutMs) {
		ClientChallenge challenge = new ClientChallenge(
				id, ItemStack.EMPTY, itemKey, lang, timeoutMs);
		challenge.setTrade(true);
		current = challenge;
		endSentAtMillis = 0L;

		if (!LexSocket.get().startTradeChallenge(id, itemKey, lang, difficulty, timeoutMs)) {
			notifyPlayer(Text.literal("Leximine: speech backend offline, trade allowed")
					.formatted(Formatting.YELLOW));
			sendVerdict(challenge, true);
		}
	}

	/**
	 * A threshold wants a sentence before it opens.
	 *
	 * <p>Trade mode on the wire -- it is the same model-judged path -- but the
	 * overlay shows an instruction rather than an item, and the backend is
	 * told a task instead of inferring a merchant.
	 *
	 * <p>Fails open like everything else: no backend, and the portal lights.
	 * A speech feature that can lock someone out of the Nether when their
	 * microphone stops working is a worse bug than a free portal.
	 */
	private void onSentenceStart(int id, String lang, String difficulty, String promptKey,
			String task, int timeoutMs) {
		ClientChallenge challenge = new ClientChallenge(
				id, ItemStack.EMPTY, promptKey, lang, timeoutMs);
		challenge.setTrade(true);
		challenge.setInstruction(promptKey);
		current = challenge;
		endSentAtMillis = 0L;

		if (!LexSocket.get().startSentenceChallenge(
				id, promptKey, lang, difficulty, task, timeoutMs)) {
			notifyPlayer(Text.literal("Leximine: speech backend offline, the way opens")
					.formatted(Formatting.YELLOW));
			sendVerdict(challenge, true);
		}
	}

	/**
	 * Play the word being asked for, before answering it.
	 *
	 * <p>The spoken example already existed, but only fired *after* a miss,
	 * which assumes you had some way to make a first attempt. For Spanish you
	 * do — the spelling is the instruction. For Japanese you do not: 84% of
	 * the dictionary contains a kanji, whose reading is a lookup rather than
	 * a rule, so the overlay can show the character and the meaning and still
	 * leave you with no idea what sound to make.
	 *
	 * <p>Deliberately not automatic. Hearing the answer handed to you every
	 * time turns a recall exercise into repetition-after-me, so it is a key
	 * you press when you need it.
	 */
	public void speakCurrentTarget() {
		ClientChallenge challenge = current;
		if (challenge == null || challenge.targetWord().isBlank()) {
			return;
		}
		PronunciationPlayer.playAsync(challenge.lang(), challenge.targetWord());
	}

	// ------------------------------------------------------------------
	// The run counter. Display only -- the server owns both numbers and sends
	// them on every change, so nothing here ever increments anything. A client
	// that counted for itself would drift the moment a verdict was dropped,
	// and would be trivially editable into a record that was never earned.
	private int streak;
	private int streakBest;

	private void onStreak(int current, int best) {
		streak = current;
		streakBest = best;
	}

	public int streak() {
		return streak;
	}

	public int streakBest() {
		return streakBest;
	}

	/** Is a miss now worth more than the item? Mirrors Streaks.ESCALATE_AT. */
	public boolean streakIsHot() {
		return streak >= STREAK_HOT_FROM;
	}

	/**
	 * Hardcoded rather than sent, deliberately.
	 *
	 * <p>It only changes the colour of a number. Putting it on the wire would
	 * mean a packet field that exists to tint one label, and the failure mode
	 * if the two ever disagree is a counter that goes gold a word early -- not
	 * a rule that works differently from the one being enforced, which stays
	 * entirely server-side in {@code Punishments.escalate}.
	 */
	private static final int STREAK_HOT_FROM = 10;

	private void onChallengeEnd(int id, boolean success) {
		ClientChallenge challenge = current;
		if (challenge != null && challenge.id() != id) {
			return;
		}
		stopListening(challenge);
		current = null;
		flashSuccess = success;
		flashTranscript = challenge == null ? "" : challenge.heardText();
		flashWord = "";
		// Only on a refusal. After a sentence the judge accepted there is
		// nothing to explain, and praise from a language model is not what
		// anyone is here for.
		flashNote = !success && challenge != null ? challenge.judgeNote() : "";

		boolean speak = !success
				&& challenge != null
				&& !challenge.targetWord().isBlank()
				&& LexConfig.get().playPronunciation();
		if (speak) {
			// Getting it wrong is the moment hearing it is worth something.
			flashWord = challenge.targetWord();
			PronunciationPlayer.playAsync(challenge.lang(), challenge.targetWord());
		}
		long linger = speak ? FLASH_WITH_AUDIO_MILLIS : FLASH_MILLIS;
		if (!flashNote.isEmpty()) {
			linger = Math.max(linger, FLASH_WITH_NOTE_MILLIS);
		}
		flashUntilMillis = System.currentTimeMillis() + linger;
	}

	// ------------------------------------------------------------------
	// LexSocket.Listener -- all called on the client thread
	// ------------------------------------------------------------------
	@Override
	public void onChallengeReady(int challengeId, String target, String itemName,
			String english, String reading, int level) {
		ClientChallenge challenge = current;
		if (challenge == null || challenge.id() != challengeId) {
			return;
		}
		challenge.setLevel(level);
		challenge.setReading(reading);
		challenge.onWordLoaded(target, itemName, english);
		// Kept past the end of this challenge so it can still be reported
		// afterwards -- you judge a sentence once you have read it, which is
		// usually a second after it has gone.
		PromptReport.remember(challenge);

		// Push-to-talk waits for the key; otherwise start listening the moment
		// the word is readable, which is also when the clock starts.
		if (!LexConfig.get().pushToTalk()) {
			startListening(challenge);
		}
	}

	@Override
	public void onPartial(int challengeId, int attempt, String transcript, String stable,
			int confidence) {
		ClientChallenge challenge = current;
		if (challenge != null && challenge.id() == challengeId) {
			challenge.onPartial(attempt, transcript, stable, confidence);
		}
	}

	@Override
	public void onAttempt(int challengeId, int attempt, String transcript, int confidence,
			String note) {
		ClientChallenge challenge = current;
		if (challenge != null && challenge.id() == challengeId) {
			challenge.onAttemptScored(attempt, transcript, confidence, note);
			Leximine.LOGGER.info("challenge {} attempt {}: heard \"{}\" at {}{}",
					challengeId, attempt, transcript, confidence,
					note.isBlank() ? "" : " -- " + note);
		}
	}

	@Override
	public void onResult(int challengeId, boolean success, String transcript,
			int confidence, String reason, String note) {
		ClientChallenge challenge = current;
		if (challenge == null || challenge.id() != challengeId) {
			return;
		}
		// Freeze the line the viewer is reading on the words it was actually
		// judged on, rather than letting a late pass redraw it.
		challenge.lockTranscript(transcript, confidence, success);
		challenge.setJudgeNote(note, challenge.attempt());
		stopListening(challenge);
		Leximine.LOGGER.info("Speech verdict for challenge {}: {} ({}), heard {} at {}{}",
				challengeId, success ? "success" : "fail", reason, "\"" + transcript + "\"",
				confidence, note.isBlank() ? "" : " -- " + note);
		sendVerdict(challenge, success);
	}

	@Override
	public void onUnavailable(int challengeId, String reason) {
		ClientChallenge challenge = current;
		if (challenge == null || challenge.id() != challengeId) {
			return;
		}
		stopListening(challenge);

		boolean grant = !"unknown_item".equals(reason) || LexConfig.get().grantUnknownItems();
		if ("unknown_item".equals(reason)) {
			// Said out loud rather than logged quietly: an item arriving
			// without a challenge otherwise looks exactly like the speech
			// check wrongly letting something through.
			Leximine.LOGGER.info("No {} translation for {}; granting unchallenged",
					challenge.lang(), challenge.translationKey());
			notifyPlayer(Text.literal("Leximine: no translation for that item, free pass")
					.formatted(Formatting.YELLOW));
		} else {
			notifyPlayer(Text.literal("Leximine: " + reason + ", item granted")
					.formatted(Formatting.YELLOW));
		}
		sendVerdict(challenge, grant);
	}

	// ------------------------------------------------------------------
	private void startListening(ClientChallenge challenge) {
		if (challenge.isRecording() || challenge.status() == ClientChallenge.Status.ANSWERED) {
			return;
		}
		boolean started = audio.start(new AudioCapture.Sink() {
			@Override
			public void onAudio(byte[] data, int length) {
				LexSocket.get().sendAudio(data, length);
			}

			@Override
			public void onLevel(float level) {
				challenge.onMicLevel(level);
			}

			@Override
			public void onFinished() {
				// Only the file-backed test source finishes on its own.
				MinecraftClient.getInstance().execute(() -> requestFinalVerdict(challenge));
			}
		});

		if (!started) {
			if (!warnedAboutMicrophone) {
				warnedAboutMicrophone = true;
				notifyPlayer(Text.literal("Leximine: no usable microphone, items granted")
						.formatted(Formatting.YELLOW));
			}
			LexSocket.get().cancelChallenge(challenge.id());
			sendVerdict(challenge, true);
			return;
		}
		challenge.setRecording(true);
	}

	private void stopListening(ClientChallenge challenge) {
		audio.stop();
		if (challenge != null) {
			challenge.setRecording(false);
		}
	}

	/** Stop capturing and ask the backend to decide on what it already has. */
	private void requestFinalVerdict(ClientChallenge challenge) {
		if (challenge == null || challenge.status() == ClientChallenge.Status.ANSWERED
				|| endSentAtMillis != 0L) {
			return;
		}
		stopListening(challenge);
		endSentAtMillis = System.currentTimeMillis();
		LexSocket.get().endChallenge(challenge.id());
	}

	// ------------------------------------------------------------------
	/** Called every client tick. */
	public void tick(MinecraftClient client) {
		ClientChallenge challenge = current;
		if (challenge == null || client.player == null) {
			return;
		}

		if (challenge.isOutOfTime() && endSentAtMillis == 0L) {
			requestFinalVerdict(challenge);
			return;
		}

		// The backend normally answers an `end` within a couple of hundred
		// milliseconds. If it does not, decide here rather than leaving the
		// player staring at a dead overlay.
		if (endSentAtMillis != 0L
				&& System.currentTimeMillis() - endSentAtMillis > FINAL_PASS_GRACE_MILLIS
				&& challenge.status() != ClientChallenge.Status.ANSWERED) {
			Leximine.LOGGER.warn("No final verdict from the backend; failing challenge {}",
					challenge.id());
			sendVerdict(challenge, false);
		}
	}

	public void sendVerdict(ClientChallenge challenge, boolean success) {
		if (challenge == null || challenge.status() == ClientChallenge.Status.ANSWERED) {
			return;
		}
		challenge.markAnswered();
		stopListening(challenge);

		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(challenge.id());
		buf.writeBoolean(success);
		// The word travels back with the verdict so the server's vocabulary
		// list can show "Diamante" instead of "item.minecraft.diamond". Only
		// the client ever sees it, because only the client talks to the
		// backend that holds the dictionary.
		// The item name, not the prompt: at tier 3 the prompt is a sentence,
		// and "Estoy fabricando una espada de diamante" is not a vocabulary
		// entry.
		String word = challenge.itemName();
		buf.writeString(word.length() > 120 ? word.substring(0, 120) : word);
		ClientPlayNetworking.send(LexPackets.CHALLENGE_RESOLVE, buf);
	}

	/** Debug keys, and the push-to-talk release. */
	public void answerCurrent(boolean success) {
		ClientChallenge challenge = current;
		if (challenge != null) {
			LexSocket.get().cancelChallenge(challenge.id());
		}
		sendVerdict(challenge, success);
	}

	public void onPushToTalkPressed() {
		ClientChallenge challenge = current;
		if (challenge != null && challenge.status() == ClientChallenge.Status.ASKING) {
			startListening(challenge);
		}
	}

	public void onPushToTalkReleased() {
		ClientChallenge challenge = current;
		if (challenge != null && challenge.isRecording()) {
			requestFinalVerdict(challenge);
		}
	}

	private void notifyPlayer(Text text) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			client.player.sendMessage(text, true);
		}
	}
}
