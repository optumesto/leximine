package dev.mettle.leximine.limbo;

import net.minecraft.item.ItemStack;

/**
 * An item the server has taken but not yet given, while the player tries to
 * pronounce its name.
 *
 * <p>The stack lives here and nowhere else for the duration: it has already
 * been removed from the world and has not been added to any inventory, so
 * every exit path in {@link LimboManager} must either insert it or drop it.
 * Losing track of it means eating the player's item.
 */
public final class LimboChallenge {
	private final int id;
	private final ItemStack stack;
	private final String translationKey;
	private final String lang;
	private final int tier;
	private final long deadlineMillis;

	/**
	 * The word the player was actually shown. Only the client knows it -- the
	 * dictionary lives in the backend -- so it arrives after the fact, purely
	 * so the vocabulary list can show words rather than translation keys.
	 */
	private String displayWord = "";

	public LimboChallenge(int id, ItemStack stack, String translationKey, String lang,
			int tier, long deadlineMillis) {
		this.id = id;
		this.stack = stack;
		this.translationKey = translationKey;
		this.lang = lang;
		this.tier = tier;
		this.deadlineMillis = deadlineMillis;
	}

	/** Drives both how much had to be said and what a miss costs. */
	public int tier() {
		return tier;
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

	public String lang() {
		return lang;
	}

	public String displayWord() {
		return displayWord;
	}

	public void setDisplayWord(String word) {
		if (word != null && !word.isBlank()) {
			this.displayWord = word;
		}
	}

	public boolean isExpired(long nowMillis) {
		return nowMillis > deadlineMillis + LimboManager.VERDICT_GRACE_MILLIS;
	}
}
