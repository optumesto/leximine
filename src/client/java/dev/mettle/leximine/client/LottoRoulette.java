package dev.mettle.leximine.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.limbo.Penalty;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

/**
 * The slot machine that decides what a miss cost.
 *
 * <p>Purely a presentation layer. The server has already rolled and already
 * scheduled the payload for the moment this wheel stops; nothing here can
 * change the outcome, and if the player closes the game mid-spin the
 * punishment still lands. What this buys is the two seconds of suspense
 * between "you got it wrong" and finding out how badly.
 *
 * <p>The player is deliberately <b>not</b> frozen while it spins. Running
 * somewhere safer is a reasonable reaction to a wheel that might land on a
 * horde, and the payload reads their position when it fires rather than when
 * it was rolled — so running actually helps.
 */
public final class LottoRoulette {
	private static final LottoRoulette INSTANCE = new LottoRoulette();

	/** Must match {@code Punishments.ROLL_TICKS}: the payload fires as this ends. */
	private static final int ROLL_TICKS = 40;

	/** How long the result stays readable once locked, for the player and the room. */
	private static final int HOLD_TICKS = 40;

	/** Ticks the held text spends fading, taken from the end of HOLD_TICKS. */
	private static final int FADE_TICKS = 12;

	/** Slow enough to read a name, fast enough to feel like a machine. */
	private static final int CYCLE_EVERY_TICKS = 3;

	/**
	 * Heartbeat spacing, in ticks, at the start and end of the spin.
	 *
	 * <p>It closes from one beat every two thirds of a second to one every
	 * fifth, which is what makes the two seconds feel like they are running
	 * out rather than merely passing.
	 */
	private static final int BEAT_SLOWEST_TICKS = 13;
	private static final int BEAT_FASTEST_TICKS = 4;

	/** Dark red, kept off pure red so it reads as blood rather than UI. */
	private static final int VIGNETTE_RGB = 0x78_08_08;

	/** How far in from each edge the darkness reaches, as a fraction. */
	private static final int VIGNETTE_DIVISOR = 5;

	/** Keeps text off the window edges at any GUI scale. */
	private static final int EDGE_MARGIN = 20;

	private static final int SAFE_COLOUR = 0x55E06A;
	private static final int HIT_COLOUR = 0xE05550;
	private static final int LABEL_COLOUR = 0x9A9AA8;
	/** The transcript caption: grey, deliberately quieter than the result. */
	private static final int HEARD_COLOUR = 0x8A8A96;

	private enum State { IDLE, ROLLING, LOCKED }

	private final Random random = new Random();

	private State state = State.IDLE;
	private int ticksLeft;
	/** Counts up across the spin; drives the pulse and the beat spacing. */
	private int ticksElapsed;
	private int ticksToNextBeat;
	private String shown = "";
	private Penalty[] pool = new Penalty[0];
	private Penalty result;

	/**
	 * What the speech model thought it heard, which is why any of this is
	 * happening.
	 *
	 * <p>It used to sit on the verdict flash as {@code heard "X"}. It lives
	 * here instead so there is exactly one place on screen that answers "but
	 * what did I actually say", and so it stays readable next to the
	 * punishment it earned rather than scrolling away above the crosshair.
	 */
	private String heard = "";

	private LottoRoulette() {
	}

	public static LottoRoulette get() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------
	/**
	 * Start spinning on an outcome the server has already chosen.
	 *
	 * @param penaltyIndex index within the tier, or -1 for a safe roll
	 */
	public void start(int tier, int penaltyIndex, String heard) {
		this.heard = heard == null ? "" : heard.trim();
		start(tier, penaltyIndex);
	}

	public void start(int tier, int penaltyIndex) {
		pool = Penalty.forTier(tier);
		if (pool.length == 0) {
			// A tier with no lotto table. Nothing to spin through, so stay out
			// of the way rather than showing an empty wheel.
			state = State.IDLE;
			return;
		}
		result = penaltyIndex < 0 ? null : Penalty.byIndex(tier, penaltyIndex);
		state = State.ROLLING;
		ticksLeft = ROLL_TICKS;
		ticksElapsed = 0;
		ticksToNextBeat = 0;
		shown = pool[random.nextInt(pool.length)].displayName();
		Leximine.LOGGER.info("lotto wheel: spinning tier {} over {} name(s)",
				tier, pool.length);
	}

	public void reset() {
		state = State.IDLE;
		shown = "";
		result = null;
		heard = "";
	}

	// ------------------------------------------------------------------
	public void tick(MinecraftClient client) {
		if (state == State.IDLE) {
			return;
		}
		ticksLeft--;

		if (state == State.ROLLING) {
			ticksElapsed++;
			if (ticksLeft % CYCLE_EVERY_TICKS == 0) {
				cycle(client);
			}
			if (--ticksToNextBeat <= 0) {
				heartbeat(client);
			}
			if (ticksLeft <= 0) {
				lockIn(client);
			}
			return;
		}

		if (ticksLeft <= 0) {
			reset();
		}
	}

	private void cycle(MinecraftClient client) {
		if (pool.length > 1) {
			// Never show the same name twice in a row; a wheel that stutters
			// on one entry reads as frozen rather than spinning.
			String previous = shown;
			for (int attempt = 0; attempt < 4 && shown.equals(previous); attempt++) {
				shown = pool[random.nextInt(pool.length)].displayName();
			}
		}
		if (client.player != null) {
			client.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.6f, 2.0f);
		}
	}

	/** A warden's heart, pitched down, closing up as the clock runs out. */
	private void heartbeat(MinecraftClient client) {
		float progress = progress();
		ticksToNextBeat = Math.round(MathHelper.lerp(
				progress, BEAT_SLOWEST_TICKS, BEAT_FASTEST_TICKS));
		if (client.player != null) {
			// Already a low sound; dropped further so it sits under the
			// note-block tick instead of competing with it.
			client.player.playSound(SoundEvents.ENTITY_WARDEN_HEARTBEAT,
					0.9f, 0.55f + progress * 0.2f);
		}
	}

	/** 0 at the start of the spin, 1 as it locks. */
	private float progress() {
		return MathHelper.clamp((float) ticksElapsed / ROLL_TICKS, 0f, 1f);
	}

	private void lockIn(MinecraftClient client) {
		state = State.LOCKED;
		ticksLeft = HOLD_TICKS;
		shown = result == null ? "SAFE" : result.lockedName();
		Leximine.LOGGER.info("lotto wheel: locked on {}", shown);
		if (client.player == null) {
			return;
		}
		if (result == null) {
			client.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
		} else {
			// Low and wrong-sounding. The payload is landing on the server at
			// this exact moment.
			client.player.playSound(SoundEvents.ENTITY_WITHER_SPAWN, 0.7f, 0.5f);
		}
	}

	// ------------------------------------------------------------------
	public boolean isActive() {
		return state != State.IDLE;
	}

	public void render(DrawContext context, MinecraftClient client) {
		if (state == State.IDLE || shown.isEmpty() || client.player == null) {
			return;
		}
		TextRenderer font = client.textRenderer;
		int width = client.getWindow().getScaledWidth();
		int centreX = width / 2;
		// Below the crosshair, deliberately.
		//
		// The wheel and the verdict flash are triggered by the same miss and
		// are on screen together for about three seconds. The flash occupies
		// the upper third (height/6 + 20, four lines deep), and an earlier
		// version put the wheel at height/2 - 44, which at ordinary GUI
		// scales leaves them a handful of pixels apart and overlapping
		// outright on a short window. Splitting them across the crosshair
		// keeps each legible: the verdict above, the lotto below.
		int y = client.getWindow().getScaledHeight() / 2 + 26;

		int alpha = 255;
		if (state == State.LOCKED && ticksLeft < FADE_TICKS) {
			alpha = MathHelper.clamp(ticksLeft * 255 / FADE_TICKS, 0, 255);
		}
		if (alpha <= 4) {
			return;
		}

		if (state == State.ROLLING) {
			renderVignette(context, client);
		}

		int colour = state == State.ROLLING
				? LABEL_COLOUR
				: (result == null ? SAFE_COLOUR : HIT_COLOUR);

		context.drawCenteredTextWithShadow(font,
				state == State.ROLLING ? "rolling..." : "", centreX, y - 12,
				(alpha << 24) | LABEL_COLOUR);

		// Everything below is clipped to the window. Nothing here is a fixed
		// length: "SENSORY DEPRIVATION" is twice the width of "THE BOSS" and
		// is drawn at double size, and whisper will happily return a whole
		// sentence for the caption. Drawn centred and untrimmed either one
		// runs off both edges of the screen, which is exactly what the first
		// live transcript did.
		int limit = width - 2 * EDGE_MARGIN;

		// Double size, so it reads as an event rather than another HUD line.
		context.getMatrices().push();
		context.getMatrices().translate(centreX, y, 0);
		context.getMatrices().scale(2f, 2f, 1f);
		context.drawCenteredTextWithShadow(font, clip(font, shown, limit / 2), 0, 0,
				(alpha << 24) | colour);
		context.getMatrices().pop();

		// What the model heard, under the result. Italic and grey so it reads
		// as a caption on the punishment rather than another verdict -- and
		// it is usually the funniest thing on screen, because a panicked
		// Spanish word comes back as confident English nonsense.
		if (!heard.isEmpty()) {
			Text caption = Text.literal(clip(font, "AI heard: “" + heard + "”", limit))
					.formatted(Formatting.ITALIC);
			context.drawCenteredTextWithShadow(font, caption, centreX, y + 24,
					(alpha << 24) | HEARD_COLOUR);
		}
	}

	/**
	 * Trim to a width, keeping the start.
	 *
	 * <p>The opposite of the live transcript, which keeps its tail because
	 * the newest words matter most while someone is still speaking. This is a
	 * finished caption, so it reads from the beginning.
	 */
	private static String clip(TextRenderer font, String text, int maxWidth) {
		if (maxWidth <= 8 || font.getWidth(text) <= maxWidth) {
			return text;
		}
		return font.trimToWidth(text, maxWidth - font.getWidth("...")) + "...";
	}

	/**
	 * A dark red closing-in around the edges, pulsing with the heartbeat.
	 *
	 * <p>Built from thin strips with a squared falloff rather than a texture,
	 * which keeps it to one file and lets the depth scale with the window.
	 * Stepped two pixels at a time: at a squared falloff the difference is
	 * invisible and it halves the number of draws.
	 *
	 * <p>Blend is enabled for the duration and <b>switched off again</b>.
	 * Leaving it on leaks into everything drawn after this in the HUD pass,
	 * which surfaces later as unrelated elements being mysteriously
	 * see-through.
	 */
	private void renderVignette(DrawContext context, MinecraftClient client) {
		int width = client.getWindow().getScaledWidth();
		int height = client.getWindow().getScaledHeight();
		int depth = Math.max(16, Math.min(width, height) / VIGNETTE_DIVISOR);

		// The pulse keeps time with the beat, so the screen tightens as the
		// heartbeat closes up rather than throbbing at a fixed rate.
		float progress = progress();
		float rate = MathHelper.lerp(progress, 0.28f, 0.75f);
		float pulse = 0.62f + 0.38f * MathHelper.sin(ticksElapsed * rate);
		float strength = (0.35f + 0.65f * progress) * pulse;

		RenderSystem.enableBlend();
		for (int inset = 0; inset < depth; inset += 2) {
			float edge = 1f - (float) inset / depth;
			int alpha = (int) (edge * edge * 190f * strength);
			if (alpha <= 2) {
				continue;
			}
			int colour = (Math.min(alpha, 255) << 24) | VIGNETTE_RGB;
			context.fill(0, inset, width, inset + 2, colour);
			context.fill(0, height - inset - 2, width, height - inset, colour);
			context.fill(inset, 0, inset + 2, height, colour);
			context.fill(width - inset - 2, 0, width - inset, height, colour);
		}
		RenderSystem.disableBlend();
	}
}
