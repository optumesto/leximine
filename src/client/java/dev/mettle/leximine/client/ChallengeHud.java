package dev.mettle.leximine.client;

import dev.mettle.leximine.config.LexConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The overlay: which word to say, and how long is left.
 *
 * <p>Sits in the upper third of the screen rather than the centre, to stay
 * clear of the crosshair, and well above the hotbar.
 */
public final class ChallengeHud {
	private static final int PANEL_BACKGROUND = 0xC8_0A_0A_0F;
	private static final int PANEL_BORDER = 0x40_FF_FF_FF;
	private static final int LABEL_COLOUR = 0xFF_9A_9A_A8;
	private static final int WORD_COLOUR = 0xFF_FF_FF_FF;
	private static final int ENGLISH_COLOUR = 0xFF_78_78_86;
	private static final int HINT_COLOUR = 0xFF_5E_5E_6A;
	/** The progress rung: present but not competing with the word. */
	private static final int LEVEL_COLOUR = 0xFF_6E_8A_7E;

	/**
	 * Review panels are teal rather than grey. Nothing is at stake in a
	 * review -- it gates a door, it cannot eat an item -- so it should not
	 * look like the panel that can.
	 */
	private static final int REVIEW_BORDER = 0xFF_3E_8C_8C;
	private static final int REVIEW_LABEL_COLOUR = 0xFF_7A_D0_D0;

	private static final int BAR_PLENTY = 0xFF_55_D0_55;
	private static final int BAR_SOME = 0xFF_E8_C0_40;
	private static final int BAR_LITTLE = 0xFF_E0_50_40;
	private static final int BAR_TRACK = 0x60_00_00_00;

	private static final int SUCCESS_COLOUR = 0xFF_55_E0_6A;
	private static final int FAIL_COLOUR = 0xFF_E0_55_50;
	private static final int HEARD_COLOUR = 0xFF_C0_B0_70;
	/** The judge talking, not the game: dimmer than a prompt, warmer than a hint. */
	private static final int NOTE_COLOUR = 0xFF_B0_8A_C8;
	/**
	 * The romanized reading. Brighter than the gloss and cooler than the
	 * word: it is an instruction, not a translation, and the eye should be
	 * able to find it without reading the panel top to bottom.
	 */
	private static final int READING_COLOUR = 0xFF_8F_D0_FF;

	/** Words the model has stopped revising. */
	private static final int STABLE_COLOUR = 0xFF_E8_DC_A0;
	/** The tail it is still changing its mind about. */
	private static final int MOVING_COLOUR = 0xFF_8A_80_60;
	private static final int REC_COLOUR = 0xFF_E0_55_50;

	private static final int WORD_SCALE = 2;
	/** Keeps a tier-3 sentence from spanning the whole screen. */
	private static final int MAX_PANEL_WIDTH = 440;
	/** Below this the panel stops shrinking, even on a tiny window. */
	private static final int MIN_PANEL_WIDTH = 190;
	/** The item icon drawn in the header's left corner. */
	private static final int ICON_SIZE = 16;
	/** Wider in stream mode so a double-size transcript is not trimmed away. */
	private static final int MAX_PANEL_WIDTH_STREAM = 640;
	private static final int MAX_WORD_LINES = 3;
	/** The translation gets two lines; past that the panel is the screen again. */
	private static final int MAX_GLOSS_LINES = 2;
	/**
	 * A model asked for one line sometimes writes three. Two is enough to say
	 * what was wrong and keeps the panel from doubling in height mid-challenge.
	 */
	private static final int MAX_NOTE_LINES = 2;
	/**
	 * The flash draws without a panel behind it, so it has no width to be
	 * sized from -- and an unwrapped model sentence would run off both edges
	 * of the screen, which is what the first live transcript did.
	 */
	private static final int FLASH_MAX_WIDTH = 380;

	/** Recording indicator. Escaped so no editor or shell can mangle it. */
	private static final String RECORD_DOT = "\u25CF";

	/** The streak's "a miss costs more now" mark. Escaped for the same reason. */
	private static final String STREAK_HOT_MARK = "\u25B2";

	private ChallengeHud() {
	}

	public static void render(DrawContext context, MinecraftClient client) {
		if (client.options.hudHidden || client.player == null) {
			return;
		}

		ChallengeTracker tracker = ChallengeTracker.get();
		ClientChallenge challenge = tracker.current();

		// Outside the panel and before the early return, so the number is on
		// screen while the wither is arriving. A streak that is only visible
		// during a prompt is invisible for the part worth watching.
		renderStreak(context, client, tracker);

		if (challenge == null) {
			if (tracker.isFlashing()) {
				renderFlash(context, client, tracker.flashWasSuccess());
			}
			return;
		}
		renderChallenge(context, client, challenge);
	}

	/** Below this the counter stays hidden: "1 in a row" is not a streak. */
	private static final int STREAK_SHOW_FROM = 3;

	/** Gold once a miss would cost a tier more than the item is worth. */
	private static final int STREAK_COLOUR = 0xFF_C8_D8_C0;
	private static final int STREAK_HOT_COLOUR = 0xFF_FF_C4_40;

	/**
	 * Above the challenge panel, which starts at {@code scaledHeight / 6}.
	 *
	 * <p>Both of this number's wrong values came from reasoning instead of
	 * looking. At 34 it landed inside the panel header. It was then moved to
	 * clear a wither's boss bar, which turns out not to be a conflict at all:
	 * a punishment only fires on a miss, a miss zeroes the streak, and the
	 * counter does not draw below {@link #STREAK_SHOW_FROM} -- so the pill and
	 * a punishment's boss bar can never be on screen together.
	 *
	 * <p>4 rather than 6 because stream mode draws this at double size, and at
	 * 6 the taller box grazed the panel's top border.
	 */
	private static final int STREAK_TOP = 4;

	/**
	 * A small run counter, top-centre.
	 *
	 * <p>Top-centre rather than a corner: the corners are where Minecraft's own
	 * HUD and most recording overlays live, and the clip pipeline crops
	 * gameplay to a 9:16 column down the middle of the frame -- anything in a
	 * corner is cropped straight out of every Short.
	 */
	private static void renderStreak(DrawContext context, MinecraftClient client,
			ChallengeTracker tracker) {
		int run = tracker.streak();
		if (run < STREAK_SHOW_FROM) {
			return;
		}
		TextRenderer font = client.textRenderer;
		boolean hot = tracker.streakIsHot();
		// English, like the rest of the chrome. The Spanish belongs to the
		// words being learned; putting it in the furniture as well makes the
		// one place the player is supposed to read carefully less distinct.
		// The arrow carries the hot state for anyone who cannot tell gold from
		// grey -- on a downscaled Short, most people.
		String text = run + " in a row" + (hot ? " " + STREAK_HOT_MARK : "");
		int scale = LexConfig.get().streamMode() ? 2 : 1;
		int width = font.getWidth(text) * scale;
		int x = (context.getScaledWindowWidth() - width) / 2;
		int y = STREAK_TOP;

		context.fill(x - 5, y - 3, x + width + 5, y + 9 * scale + 2, 0x90_10_14_10);
		drawBorder(context, x - 5, y - 3, width + 10, 9 * scale + 5,
				hot ? STREAK_HOT_COLOUR : STREAK_COLOUR);
		if (scale == 1) {
			context.drawTextWithShadow(font, text, x, y,
					hot ? STREAK_HOT_COLOUR : STREAK_COLOUR);
		} else {
			drawScaled(context, font, text, x, y, scale,
					hot ? STREAK_HOT_COLOUR : STREAK_COLOUR);
		}
	}

	// ------------------------------------------------------------------
	private static void renderChallenge(DrawContext context, MinecraftClient client,
			ClientChallenge challenge) {
		TextRenderer font = client.textRenderer;
		int screenWidth = client.getWindow().getScaledWidth();
		int centreX = screenWidth / 2;

		boolean loading = challenge.status() == ClientChallenge.Status.LOADING;
		String word = loading ? "..." : challenge.targetWord();

		// A tier-3 prompt is a sentence, not a word. Shrink and wrap rather
		// than letting it run off both sides of the panel: the layout has to
		// cope with "Pico" and "Estoy fabricando una espada de diamante".
		// Stream mode draws the transcript at double size, which needs both a
		// wider panel and a taller row -- at the normal width a six-word line
		// is trimmed away before the settled/moving split can be seen.
		int transcriptScale = LexConfig.get().streamMode() ? 2 : 1;
		// Capped as a *share* of the screen, not merely kept off the edges.
		// `screenWidth - 40` only means "do not overflow", and at a tier-3
		// sentence that let the panel reach nine tenths of the width -- the
		// prompt stopped being an overlay and became the screen. A player
		// still has to see what is about to eat them.
		int share = Math.max(MIN_PANEL_WIDTH,
				screenWidth * (transcriptScale > 1 ? 4 : 3) / 5);
		int maxPanelWidth = Math.min(share,
				transcriptScale > 1 ? MAX_PANEL_WIDTH_STREAM : MAX_PANEL_WIDTH);
		int maxTextWidth = maxPanelWidth - 72;
		int scale = WORD_SCALE;
		List<String> lines = wrapToWidth(font, word, maxTextWidth / scale);
		if (lines.size() > 2) {
			scale = 1;
			lines = wrapToWidth(font, word, maxTextWidth);
		}
		if (lines.size() > MAX_WORD_LINES) {
			lines = lines.subList(0, MAX_WORD_LINES);
		}

		Text prompt;
		if (loading) {
			prompt = Text.translatable("hud.leximine.loading");
		} else if (challenge.isTrade() && !challenge.instruction().isBlank()) {
			// A gate asking for a sentence of the player's own: there is no
			// item to build it around, so the instruction IS the prompt.
			prompt = Text.translatable(challenge.instruction());
		} else if (challenge.isTrade()) {
			// The word on screen is the item; the player has to build a
			// sentence around it themselves.
			prompt = Text.translatable("hud.leximine.trade");
		} else if (challenge.isReview()) {
			// Says plainly that nothing is at stake here, only the door -- and
			// NAMES the block, because a review arrives with nothing in hand
			// and no item icon. Unnamed, it reads as a pickup challenge for an
			// item the player never collected, which is exactly how a furnace
			// asking for "Arena" got narrated as "I don't know when I picked
			// up sand" in the first recorded episode.
			String key = challenge.blockKey();
			prompt = key.isEmpty()
					? Text.translatable("hud.leximine.review")
					: Text.translatable("hud.leximine.review_block",
							Text.translatable(key));
		} else {
			prompt = Text.translatable("hud.leximine.say_it");
		}
		String rung = loading ? "" : "lvl " + challenge.level();
		int rungWidth = rung.isEmpty() ? 0 : font.getWidth(rung) + 6;

		int widest = 0;
		for (String line : lines) {
			widest = Math.max(widest, font.getWidth(line) * scale);
		}
		// The header counts towards the width too. Sizing only to the word
		// made the panel for "Pan" narrower than its own label -- "Ask them
		// for this, in a sentence" came out as "Ask them for this, in a s...".
		// Both corners of the header are spoken for -- the item icon on the
		// left, the level badge on the right -- so the label has to fit
		// between them, and the panel has to be wide enough for all three.
		int headerSides = 2 * Math.max(rungWidth, ICON_SIZE + 4);
		widest = Math.max(widest, font.getWidth(prompt.getString()) + headerSides - 72 + 16);
		// Fixed width in stream mode. Sizing to the longest word leaves the
		// double-size transcript trimmed, and sizing to the transcript would
		// make the panel breathe on every update -- distracting on video.
		int panelWidth = transcriptScale > 1
				? maxPanelWidth
				: Math.max(MIN_PANEL_WIDTH, Math.min(maxPanelWidth, widest + 72));
		int left = centreX - panelWidth / 2;
		int top = client.getWindow().getScaledHeight() / 6;

		// Why a merchant turned the last sentence down. Laid out before the
		// panel is sized, because the panel has to be tall enough to hold it.
		List<String> noteLines = List.of();
		if (challenge.isTrade() && !challenge.judgeNote().isBlank()) {
			// Numbered only when it is about a different try than the
			// transcript above it. A model takes a second or two to answer,
			// by which time the player is often already saying the next
			// thing, and an unlabelled reason then sits under a transcript it
			// was never about -- "this is a statement, not a request" printed
			// under a perfectly good request. When they match, the number
			// would only be repeating what the line above already says.
			int about = challenge.judgeNoteAttempt();
			String text = (about > 0 && about != challenge.attempt() ? "#" + about + " " : "")
					+ challenge.judgeNote();
			noteLines = wrapToWidth(font, text, maxPanelWidth - 24);
			if (noteLines.size() > MAX_NOTE_LINES) {
				noteLines = noteLines.subList(0, MAX_NOTE_LINES);
			}
		}

		// The gloss wraps rather than being cut short. It is the only thing
		// telling the player what they are about to say, so "I was going to
		// find some e..." is the one place a clip costs real meaning.
		List<String> glossLines = List.of();
		if (!loading && !challenge.englishWord().isEmpty()) {
			glossLines = wrapToWidth(font, "(" + challenge.englishWord() + ")",
					panelWidth - 16);
			if (glossLines.size() > MAX_GLOSS_LINES) {
				glossLines = glossLines.subList(0, MAX_GLOSS_LINES);
			}
		}

		// How to say it, for a script you cannot sound out. Sits between the
		// word and its meaning because that is the order you need them in:
		// what it looks like, how to say it, what it means. Blank for
		// Spanish and the rest of the Latin-script languages, where the
		// spelling is already the instruction and a second line repeating it
		// would be clutter.
		String readingText = loading ? "" : challenge.reading();
		if (!readingText.isEmpty()) {
			readingText = fitToPanel(font, readingText, panelWidth - 16);
		}

		int lineHeight = 10 * scale;
		int wordTop = top + 20;
		int readingY = wordTop + lines.size() * lineHeight + 2;
		int readingHeight = readingText.isEmpty() ? 0 : 10;
		int glossY = readingY + readingHeight + 1;
		int transcriptY = glossY + 1 + Math.max(1, glossLines.size()) * 10;
		int noteY = transcriptY + 2 + 10 * transcriptScale;
		int meterY = noteY + noteLines.size() * 10;
		int barY = meterY + 10;
		int panelHeight = barY + 3 - top + 5;

		context.fill(left, top, left + panelWidth, top + panelHeight, PANEL_BACKGROUND);
		// A review borrows nothing and risks nothing, so it gets its own
		// border and a brighter header. The wording alone was not enough:
		// "Say it to open this" is the dimmest line on a panel whose loudest
		// element is a word, and it was read as a pickup. Colour is seen
		// before anything is read.
		boolean reviewing = !loading && challenge.isReview();
		drawBorder(context, left, top, panelWidth, panelHeight,
				reviewing ? REVIEW_BORDER : PANEL_BORDER);

		// Still clipped as a backstop: at a small GUI scale the panel hits
		// its maximum width before the header fits.
		context.drawCenteredTextWithShadow(font,
				clipKeepingStart(font, prompt.getString(), panelWidth - headerSides - 8),
				centreX, top + 7, reviewing ? REVIEW_LABEL_COLOUR : LABEL_COLOUR);

		if (!rung.isEmpty()) {
			context.drawTextWithShadow(font, rung,
					left + panelWidth - 6 - font.getWidth(rung), top + 7, LEVEL_COLOUR);
		}

		// drawCenteredTextWithShadow measures in unscaled units, so the
		// translate happens in screen space and the draw at the origin.
		for (int index = 0; index < lines.size(); index++) {
			context.getMatrices().push();
			context.getMatrices().translate(centreX, wordTop + index * lineHeight, 0);
			context.getMatrices().scale(scale, scale, 1f);
			context.drawCenteredTextWithShadow(font, lines.get(index), 0, 0, WORD_COLOUR);
			context.getMatrices().pop();
		}

		// The item being held hostage, in the header row rather than floating
		// in the middle of the panel. Centred vertically it landed on top of
		// the English gloss once that grew to two lines; up here it balances
		// the level badge on the other side and nothing else wants the space.
		context.drawItem(challenge.stack(), left + 5, top + 2);

		if (!readingText.isEmpty()) {
			context.drawCenteredTextWithShadow(font, readingText, centreX, readingY,
					READING_COLOUR);
		}

		// English gloss, so an unfamiliar word is still learnable. At tiers 3
		// and 4 this is a whole sentence, wrapped above.
		for (int index = 0; index < glossLines.size(); index++) {
			context.drawCenteredTextWithShadow(font, glossLines.get(index),
					centreX, glossY + index * 10, ENGLISH_COLOUR);
		}

		// What the backend currently thinks it heard. The single most useful
		// thing on screen when a correct-sounding attempt keeps failing.
		renderLiveTranscript(context, font, challenge, centreX, transcriptY,
				panelWidth - 16, transcriptScale);

		// The judge's own words. A sentence is not scored against a string, so
		// a percentage explains nothing about it -- "the sentence requests a
		// sandwich, not bread" is the only feedback that teaches anything.
		for (int index = 0; index < noteLines.size(); index++) {
			context.drawCenteredTextWithShadow(font, noteLines.get(index),
					centreX, noteY + index * 10, NOTE_COLOUR);
		}

		renderMicMeter(context, font, challenge, left + 8, meterY);
		renderTimerBar(context, challenge, left + 8, barY, panelWidth - 16);

		context.drawCenteredTextWithShadow(font, hintFor(challenge),
				centreX, top + panelHeight + 4, HINT_COLOUR);
	}

	/**
	 * Clip a running transcript to the panel, keeping the end.
	 *
	 * <p>Whisper happily returns a whole sentence. Drawn centred and
	 * untrimmed, that spills out past both panel edges and across the screen,
	 * which is exactly what the first phase 4 screenshot caught. The tail is
	 * the part worth keeping: it is the most recent thing the player said.
	 */
	/**
	 * Greedy word wrap, in unscaled font units.
	 *
	 * <p>A single word longer than the line is left alone rather than broken
	 * mid-word: the caller drops to a smaller scale instead, and a prompt
	 * split across a syllable boundary would be harder to read than a small
	 * one.
	 */
	private static List<String> wrapToWidth(TextRenderer font, String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.isEmpty()) {
				line.append(word);
				continue;
			}
			if (font.getWidth(line + " " + word) <= maxWidth) {
				line.append(' ').append(word);
			} else {
				lines.add(line.toString());
				line = new StringBuilder(word);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines.isEmpty() ? List.of(text) : lines;
	}

	/**
	 * Trim to a width, keeping the start.
	 *
	 * <p>The opposite of {@link #fitToPanel}, which keeps the tail because a
	 * live transcript's newest words matter most. A translation reads from
	 * the beginning.
	 */
	private static String clipKeepingStart(TextRenderer font, String text, int maxWidth) {
		if (maxWidth <= 8 || font.getWidth(text) <= maxWidth) {
			return text;
		}
		return font.trimToWidth(text, maxWidth - font.getWidth("...")) + "...";
	}

	private static String fitToPanel(TextRenderer font, String text, int maxWidth) {
		if (font.getWidth(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		return ellipsis + font.trimToWidth(text, maxWidth - font.getWidth(ellipsis), true);
	}

	private static Text hintFor(ClientChallenge challenge) {
		if (challenge.justFinishedAttempt()) {
			// Says plainly that the last try is over and the next is separate.
			return Text.translatable("hud.leximine.try_again");
		}
		if (LexConfig.get().pushToTalk() && !challenge.isRecording()) {
			return Text.translatable("hud.leximine.hold_to_talk");
		}
		return Text.translatable("hud.leximine.speak_now");
	}

	/**
	 * The live transcript: what the backend thinks it is hearing, right now.
	 *
	 * <p>Drawn in two colours. Each pass re-reads the whole attempt, so
	 * whisper revises itself constantly ("dia" to "diamante" to "el
	 * diamante"); rendering that as one flat line is an unreadable flicker.
	 * The words successive passes agree on are drawn bright and the tail that
	 * is still moving is dimmed, which makes the model's second-guessing
	 * legible instead of noisy -- the point of putting it on screen at all.
	 *
	 * <p>When the verdict lands the line locks and takes the pass/fail
	 * colour, so a viewer can read what was actually judged.
	 */
	private static void renderLiveTranscript(DrawContext context, TextRenderer font,
			ClientChallenge challenge, int centreX, int y, int maxWidth, int scale) {
		String full = challenge.heardText();
		if (full.isEmpty()) {
			return;
		}

		// Numbered from the second try on, so a later attempt visibly replaces
		// the first rather than looking bolted onto it. Not on the first:
		// most challenges only ever have one, and "#1" in front of every
		// transcript is a label that never distinguishes anything.
		String prefix = challenge.attempt() > 1 ? "#" + challenge.attempt() + " " : "";
		String suffix = "  " + challenge.heardScore() + "%";

		String stable = challenge.stableText();
		if (!full.startsWith(stable)) {
			stable = "";
		}
		String moving = full.substring(stable.length());

		String whole = prefix + "\"" + full + "\"" + suffix;
		if (font.getWidth(whole) * scale > maxWidth) {
			// Too long to split meaningfully: keep the most recent words and
			// draw them as one settled line.
			String trimmed = fitToPanel(font, whole, maxWidth / scale);
			drawScaled(context, font, trimmed, centreX, y, scale,
					lockedColour(challenge, HEARD_COLOUR));
			return;
		}

		int settledColour = lockedColour(challenge, STABLE_COLOUR);
		int movingColour = challenge.isLocked() ? settledColour : MOVING_COLOUR;

		context.getMatrices().push();
		context.getMatrices().translate(centreX, y, 0);
		context.getMatrices().scale(scale, scale, 1f);
		int x = -font.getWidth(whole) / 2;
		x += drawRun(context, font, prefix + "\"", x, settledColour);
		x += drawRun(context, font, stable, x, settledColour);
		x += drawRun(context, font, moving, x, movingColour);
		drawRun(context, font, "\"" + suffix, x, settledColour);
		context.getMatrices().pop();
	}

	private static int drawRun(DrawContext context, TextRenderer font, String text,
			int x, int colour) {
		if (text.isEmpty()) {
			return 0;
		}
		context.drawTextWithShadow(font, text, x, 0, colour);
		return font.getWidth(text);
	}

	private static void drawScaled(DrawContext context, TextRenderer font, String text,
			int centreX, int y, int scale, int colour) {
		context.getMatrices().push();
		context.getMatrices().translate(centreX, y, 0);
		context.getMatrices().scale(scale, scale, 1f);
		context.drawCenteredTextWithShadow(font, text, 0, 0, colour);
		context.getMatrices().pop();
	}

	private static int lockedColour(ClientChallenge challenge, int unlocked) {
		if (!challenge.isLocked()) {
			return unlocked;
		}
		return challenge.lockedSuccess() ? SUCCESS_COLOUR : FAIL_COLOUR;
	}

	/**
	 * A level meter, not decoration: "is it even hearing me" is the first
	 * question when a challenge fails, and silence looks identical to a wrong
	 * answer without it.
	 */
	private static void renderMicMeter(DrawContext context, TextRenderer font,
			ClientChallenge challenge, int x, int y) {
		if (!challenge.isRecording()) {
			return;
		}
		context.drawTextWithShadow(font, RECORD_DOT, x, y - 1, REC_COLOUR);

		int meterLeft = x + 10;
		int meterWidth = 40;
		context.fill(meterLeft, y + 1, meterLeft + meterWidth, y + 5, BAR_TRACK);

		// Mic levels are tiny in linear terms; a square root keeps normal
		// speech in the middle of the meter instead of hugging the left edge.
		float scaled = (float) Math.sqrt(Math.min(1f, challenge.micLevel() * 6f));
		int filled = Math.round(meterWidth * scaled);
		if (filled > 0) {
			context.fill(meterLeft, y + 1, meterLeft + filled, y + 5, REC_COLOUR);
		}
	}

	private static void renderTimerBar(DrawContext context, ClientChallenge challenge,
			int x, int y, int width) {
		float fraction = challenge.remainingFraction();
		int filled = Math.round(width * fraction);

		context.fill(x, y, x + width, y + 3, BAR_TRACK);
		if (filled > 0) {
			int colour = fraction > 0.5f ? BAR_PLENTY : fraction > 0.25f ? BAR_SOME : BAR_LITTLE;
			context.fill(x, y, x + filled, y + 3, colour);
		}
	}

	private static void renderFlash(DrawContext context, MinecraftClient client, boolean success) {
		TextRenderer font = client.textRenderer;
		int centreX = client.getWindow().getScaledWidth() / 2;
		int top = client.getWindow().getScaledHeight() / 6 + 20;

		Text text = Text.translatable(success
				? "hud.leximine.correct"
				: "hud.leximine.wrong");

		context.getMatrices().push();
		context.getMatrices().translate(centreX, top, 0);
		context.getMatrices().scale(WORD_SCALE, WORD_SCALE, 1f);
		context.drawCenteredTextWithShadow(font, text, 0, 0,
				success ? SUCCESS_COLOUR : FAIL_COLOUR);
		context.getMatrices().pop();

		if (success) {
			return;
		}

		// What was actually heard is no longer shown here. It moved under the
		// lotto wheel, which is on screen at the same moment and sits with
		// the punishment it caused -- printing it in both places put the same
		// string on screen twice.
		int y = top + 22;

		// On a refused trade, why. This is the only place it gets read:
		// the panel that carried it closed when the verdict landed.
		String note = ChallengeTracker.get().flashNote();
		if (!note.isEmpty()) {
			List<String> lines = wrapToWidth(font, note, FLASH_MAX_WIDTH);
			if (lines.size() > MAX_NOTE_LINES) {
				lines = lines.subList(0, MAX_NOTE_LINES);
			}
			for (String line : lines) {
				context.drawCenteredTextWithShadow(font, line, centreX, y, NOTE_COLOUR);
				y += 10;
			}
			y += 2;
		}

		// The correct pronunciation is playing; name the word being spoken so
		// the sound has something to attach to.
		String word = ChallengeTracker.get().flashWord();
		if (!word.isEmpty()) {
			context.drawCenteredTextWithShadow(font,
					Text.translatable("hud.leximine.listen", word),
					centreX, y, LABEL_COLOUR);
		}
	}

	private static void drawBorder(DrawContext context, int left, int top, int width, int height) {
		drawBorder(context, left, top, width, height, PANEL_BORDER);
	}

	private static void drawBorder(DrawContext context, int left, int top, int width,
			int height, int colour) {
		context.fill(left, top, left + width, top + 1, colour);
		context.fill(left, top + height - 1, left + width, top + height, colour);
		context.fill(left, top, left + 1, top + height, colour);
		context.fill(left + width - 1, top, left + width, top + height, colour);
	}
}


