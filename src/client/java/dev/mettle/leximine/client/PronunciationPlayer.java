package dev.mettle.leximine.client;

import dev.mettle.leximine.Leximine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Plays the word back after a miss, so being wrong teaches something.
 *
 * <p>The audio comes from the backend's {@code /pronounce}, which uses a
 * native neural voice for the language and caches each word on disk. It is
 * played straight through {@code javax.sound.sampled} rather than registered
 * as a Minecraft sound event, because the clip is generated at runtime and
 * there is no resource pack entry to point at.
 *
 * <p>Playing outside Minecraft's mixer means its volume slider does not apply
 * by itself, so the samples are scaled by the master volume here. A muted game
 * stays muted.
 */
public final class PronunciationPlayer {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(2))
			.build();

	/** Long enough for a word, short enough not to stall the next challenge. */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);

	private static volatile boolean warnedUnavailable;

	private PronunciationPlayer() {
	}

	/**
	 * Fetch and play the correct pronunciation. Returns immediately; the work
	 * happens on an HTTP thread and a short-lived audio thread.
	 */
	public static void playAsync(String lang, String word) {
		if (word == null || word.isBlank()) {
			return;
		}
		float volume = masterVolume();
		if (volume <= 0f) {
			// A muted game stays muted. Logged because "I never hear the
			// example" is otherwise a mystery with a very boring cause.
			Leximine.LOGGER.debug("Master volume is 0; skipping pronunciation of {}", word);
			return;
		}
		Leximine.LOGGER.info("Playing pronunciation of \"{}\" ({})", word, lang);

		URI uri = URI.create(LexClientConfig.httpBase()
				+ "/pronounce?lang=" + URLEncoder.encode(lang, StandardCharsets.UTF_8)
				+ "&word=" + URLEncoder.encode(word, StandardCharsets.UTF_8));

		HTTP.sendAsync(HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build(),
						HttpResponse.BodyHandlers.ofByteArray())
				.thenAccept(response -> {
					if (response.statusCode() == 503) {
						if (!warnedUnavailable) {
							warnedUnavailable = true;
							Leximine.LOGGER.info(
									"No pronunciation voice available for {}; "
											+ "install edge-tts in the backend for spoken examples.",
									lang);
						}
						return;
					}
					if (response.statusCode() != 200) {
						Leximine.LOGGER.debug("pronounce -> HTTP {}", response.statusCode());
						return;
					}
					play(response.body(), volume);
				})
				.exceptionally(error -> {
					Leximine.LOGGER.debug("pronunciation fetch failed", error);
					return null;
				});
	}

	private static float masterVolume() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.options == null) {
			return 1f;
		}
		return client.options.getSoundVolume(SoundCategory.MASTER);
	}

	private static void play(byte[] wav, float volume) {
		Thread thread = new Thread(() -> {
			try (AudioInputStream stream =
						 AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
				AudioFormat format = stream.getFormat();
				DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
				if (!AudioSystem.isLineSupported(info)) {
					return;
				}
				try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
					line.open(format);
					line.start();
					byte[] buffer = new byte[4096];
					int read;
					while ((read = stream.read(buffer)) > 0) {
						scale(buffer, read, volume);
						line.write(buffer, 0, read);
					}
					line.drain();
				}
			} catch (Exception e) {  // noqa - audio devices fail in many ways
				Leximine.LOGGER.debug("could not play pronunciation", e);
			}
		}, "leximine-pronounce");
		thread.setDaemon(true);
		thread.start();
	}

	/** Apply Minecraft's master volume to signed 16-bit little-endian samples. */
	private static void scale(byte[] buffer, int length, float volume) {
		if (volume >= 0.999f) {
			return;
		}
		for (int i = 0; i + 1 < length; i += 2) {
			int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
			sample = Math.round(sample * volume);
			buffer[i] = (byte) (sample & 0xFF);
			buffer[i + 1] = (byte) ((sample >> 8) & 0xFF);
		}
	}
}
