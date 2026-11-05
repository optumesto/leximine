package dev.mettle.leximine.client;

import net.minecraft.item.ItemStack;

/**
 * The client's view of an item in limbo: what to draw, and how long is left.
 *
 * <p>The authoritative stack and deadline live on the server; this is the copy
 * the HUD reads every frame. The word arrives slightly later than the rest,
 * because it comes from a separate call to the backend.
 */
public final class ClientChallenge {
	public enum Status {
		/** Waiting for the word to come back from the backend. */
		LOADING,
		/** Word on screen, clock running. */
		ASKING,
		/** Verdict sent, waiting for the server to confirm. */
		ANSWERED
	}

	private final int id;
	private final ItemStack stack;
	/** Not final: a review challenge learns its word from the backend. */
	private volatile String translationKey;
	/** Chosen by the server, so both sides ask and answer in the same one. */
	private final String lang;
	private final int timeoutMs;

	private volatile Status status = Status.LOADING;
	private volatile String targetWord = "";
	private volatile String itemName = "";
	private volatile String englishWord = "";
	private volatile int tier = 1;
	private volatile int level = 0;
	private volatile boolean review = false;
	private volatile boolean trade = false;

	/**
	 * Translation key of the block that asked, for review challenges only.
	 * Empty for everything else.
	 */
	private volatile String blockKey = "";

	/** What the backend currently thinks it heard, and how close that is. */
	private volatile String heardText = "";
	private volatile String stableText = "";
	private volatile int heardScore = 0;
	private volatile long lastLiveAtMillis = 0L;

	/** Set when the verdict lands, so the transcript stops moving. */
	private volatile boolean locked = false;
	private volatile boolean lockedSuccess = false;

	/** Which try this is. Each pause ends one and starts the next. */
	private volatile int attempt = 0;
	private volatile long lastAttemptAtMillis = 0L;

	/**
	 * Why a merchant turned the last sentence down, in the judge's own words.
	 *
	 * <p>Only trades have one. A word challenge is scored against a string, so
	 * the percentage already says everything there is to say; a sentence is
	 * judged on whether it was a request at all, and "38%" explains none of
	 * that.
	 */
	private volatile String judgeNote = "";
	/** Which try the note is about, which is not always the one on screen. */
	private volatile int judgeNoteAttempt = 0;

	/** Microphone level, smoothed, for the on-screen meter. */
	private volatile float micLevel = 0f;
	private volatile boolean recording = false;

	/**
	 * Set when the word reaches the screen, not at construction. The server
	 * allows a grace margin past its own deadline precisely so this clock can
	 * start late without the two fighting.
	 */
	private volatile long askingSinceMillis = 0L;

	public ClientChallenge(int id, ItemStack stack, String translationKey, String lang,
			int timeoutMs) {
		this.id = id;
		this.stack = stack;
		this.translationKey = translationKey;
		this.lang = lang;
		this.timeoutMs = timeoutMs;
	}

	public String lang() {
		return lang;
	}

	public int id() {
		return id;
	}

	public ItemStack stack() {
		return stack;
	}

	public String translationKey() {
		return translationKey;
	}

	public Status status() {
		return status;
	}

	public String targetWord() {
		return targetWord;
	}

	public String englishWord() {
		return englishWord;
	}

	public void onWordLoaded(String target, String itemName, String english) {
		this.targetWord = target;
		this.itemName = itemName == null || itemName.isBlank() ? target : itemName;
		this.englishWord = english;
		if (this.status == Status.LOADING) {
			this.askingSinceMillis = System.currentTimeMillis();
			this.status = Status.ASKING;
		}
	}

	/**
	 * The plain item name. At tier 3 {@link #targetWord()} is a whole
	 * sentence, and recording that in the vocabulary list would be useless.
	 */
	public String itemName() {
		return itemName;
	}

	public int tier() {
		return tier;
	}

	/**
	 * How far along the player is, 0-3, as the backend sees it.
	 *
	 * <p>Decided from how much they have already said correctly, and it is
	 * what widens the pool of sentence frames. Shown on the overlay so the
	 * asking visibly getting harder is something the player can point at
	 * rather than only feel.
	 */
	public int level() {
		return level;
	}

	public void setLevel(int level) {
		this.level = Math.max(0, level);
	}

	public void setTranslationKey(String key) {
		this.translationKey = key;
	}

	/** A door or chest asking, rather than an item at stake. */
	public boolean isReview() {
		return review;
	}

	public void setReview(boolean review) {
		this.review = review;
	}

	/** Translation key of the block that asked; empty if not a review. */
	public String blockKey() {
		return blockKey;
	}

	public void setBlockKey(String blockKey) {
		this.blockKey = blockKey == null ? "" : blockKey;
	}

	/** Negotiating with a merchant: say a sentence, not a word. */
	public boolean isTrade() {
		return trade;
	}

	public void setTrade(boolean trade) {
		this.trade = trade;
	}

	/**
	 * How to say the target, in Latin letters.
	 *
	 * <p>Blank for Latin-script languages, where the written word already is
	 * the instruction, and blank for Japanese containing kanji, where the
	 * reading cannot be derived by rule. The overlay shows it only when there
	 * is something to show.
	 */
	private volatile String reading = "";

	public String reading() {
		return reading;
	}

	public void setReading(String reading) {
		this.reading = reading == null ? "" : reading;
	}

	/**
	 * For a gate that wants a composed sentence: the translation key of the
	 * instruction to show in place of "ask them for this".
	 *
	 * <p>Blank for an ordinary trade, where the item name is the prompt and
	 * what to do with it is obvious.
	 */
	private volatile String instruction = "";

	public String instruction() {
		return instruction;
	}

	public void setInstruction(String instruction) {
		this.instruction = instruction == null ? "" : instruction;
	}

	public void setTier(int tier) {
		this.tier = tier;
	}

	public void markAnswered() {
		this.status = Status.ANSWERED;
		this.recording = false;
	}

	public String heardText() {
		return heardText;
	}

	public int heardScore() {
		return heardScore;
	}

	public void onPartial(int attempt, String transcript, String stable, int score) {
		if (locked) {
			// A verdict has landed. Late passes must not reanimate the line
			// the viewer is reading.
			return;
		}
		if (attempt > 0) {
			this.attempt = attempt;
		}
		this.heardText = transcript;
		this.stableText = stable == null ? "" : stable;
		this.heardScore = score;
		this.lastLiveAtMillis = System.currentTimeMillis();
	}

	/** The settled part of the live transcript. */
	public String stableText() {
		return stableText;
	}

	/** True once the verdict has frozen the transcript. */
	public boolean isLocked() {
		return locked;
	}

	public void lockTranscript(String finalText, int score, boolean success) {
		if (finalText != null && !finalText.isBlank()) {
			this.heardText = finalText;
		}
		this.stableText = this.heardText;
		this.heardScore = score;
		this.locked = true;
		this.lockedSuccess = success;
	}

	public boolean lockedSuccess() {
		return lockedSuccess;
	}

	/** Recently updated, so the overlay can show it as actively moving. */
	public boolean isLive() {
		return !locked && lastLiveAtMillis > 0
				&& System.currentTimeMillis() - lastLiveAtMillis < 900L;
	}

	/** A complete try was scored and came up short; the next starts clean. */
	public void onAttemptScored(int attempt, String transcript, int score, String note) {
		if (locked) {
			// Same rule as onPartial, and it has to be here too. The backend
			// can close an attempt and judge it after the verdict has already
			// gone out -- the final pass and the last utterance race. Letting
			// that through rewrote the frozen line, so the flash said "heard
			// X" above a reason that was written about Y.
			return;
		}
		this.attempt = attempt;
		this.heardText = transcript;
		this.heardScore = score;
		this.lastAttemptAtMillis = System.currentTimeMillis();
		setJudgeNote(note, attempt);
	}

	/** The judge's reason for the last refusal, or blank. */
	public String judgeNote() {
		return judgeNote;
	}

	/**
	 * The try that reason was written about, or 0.
	 *
	 * <p>Worth showing next to it. By the time a model has answered, the
	 * player is usually already saying the next thing, so an unlabelled
	 * reason sits under a transcript it was not about — and "this is a
	 * statement, not a request" printed under a perfectly good request is
	 * worse than saying nothing.
	 */
	public int judgeNoteAttempt() {
		return judgeNoteAttempt;
	}

	public void setJudgeNote(String note, int attempt) {
		// Only ever replaced by another reason, never blanked: the final
		// verdict on a timeout carries no note of its own, and wiping the one
		// the player was reading would leave them with nothing.
		if (note != null && !note.isBlank()) {
			this.judgeNote = note;
			this.judgeNoteAttempt = attempt;
		}
	}

	public int attempt() {
		return attempt;
	}

	/**
	 * True shortly after a try was scored, so the overlay can say "try again"
	 * rather than leaving a stale transcript that looks like live feedback.
	 */
	public boolean justFinishedAttempt() {
		return lastAttemptAtMillis > 0
				&& System.currentTimeMillis() - lastAttemptAtMillis < 1_400L;
	}

	public float micLevel() {
		return micLevel;
	}

	public boolean isRecording() {
		return recording;
	}

	public void setRecording(boolean recording) {
		this.recording = recording;
		if (!recording) {
			this.micLevel = 0f;
		}
	}

	public void onMicLevel(float level) {
		// Smoothed, otherwise the meter is unreadable strobing at 20 Hz.
		this.micLevel = this.micLevel * 0.6f + level * 0.4f;
	}

	public long remainingMillis() {
		// Full time while loading: being charged for the backend's lookup
		// latency, before the word is even visible, would be unfair.
		if (status == Status.LOADING || askingSinceMillis == 0L) {
			return timeoutMs;
		}
		return Math.max(0L, askingSinceMillis + timeoutMs - System.currentTimeMillis());
	}

	/** 1.0 at the start, 0.0 when out of time. */
	public float remainingFraction() {
		return timeoutMs <= 0 ? 0f : Math.min(1f, (float) remainingMillis() / timeoutMs);
	}

	public boolean isOutOfTime() {
		return status == Status.ASKING && remainingMillis() <= 0L;
	}
}
