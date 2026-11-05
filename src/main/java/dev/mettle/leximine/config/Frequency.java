package dev.mettle.leximine.config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * How long a word stays "known" after you say it correctly.
 *
 * <p>The clock only ever starts on a correct answer, so getting one wrong
 * means being asked again next time regardless of which setting is chosen.
 */
public enum Frequency {
	ALWAYS("always", 0L, "every time, even after you get it right"),
	FIVE_MINUTES("5m", 5 * 60L, "ask again 5 minutes after a correct answer"),
	TEN_MINUTES("10m", 10 * 60L, "ask again 10 minutes after a correct answer"),
	THIRTY_MINUTES("30m", 30 * 60L, "ask again 30 minutes after a correct answer"),
	ONE_HOUR("1h", 60 * 60L, "ask again an hour after a correct answer"),
	SIX_HOURS("6h", 6 * 60 * 60L, "ask again six hours after a correct answer"),
	ONE_DAY("1d", 24 * 60 * 60L, "ask again a day after a correct answer"),
	ONCE("once", -1L, "learn each word once, then never again");

	public final String id;
	/** Seconds after a correct answer before asking again; -1 means never. */
	public final long seconds;
	public final String description;

	Frequency(String id, long seconds, String description) {
		this.id = id;
		this.seconds = seconds;
		this.description = description;
	}

	public boolean asksAgain() {
		return seconds >= 0;
	}

	public static Frequency fromId(String raw, Frequency fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		String needle = raw.trim().toLowerCase(Locale.ROOT);
		for (Frequency value : values()) {
			if (value.id.equals(needle) || value.name().toLowerCase(Locale.ROOT).equals(needle)) {
				return value;
			}
		}
		return fallback;
	}

	public static List<String> ids() {
		return Arrays.stream(values()).map(value -> value.id).toList();
	}
}
