package dev.mettle.leximine.client;

import dev.mettle.leximine.Leximine;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.nio.file.Path;

/**
 * Microphone capture, in exactly the format the backend's PROTOCOL.md asks
 * for: 16 kHz, mono, signed 16-bit little-endian, raw, no WAV header.
 *
 * <p>The line is only ever opened while a challenge is on screen and is closed
 * the moment one resolves. The mod does not hold the microphone open in the
 * background.
 */
public final class AudioCapture {
	public static final AudioFormat FORMAT =
			new AudioFormat(16_000f, 16, 1, true, false);

	/** 50 ms per frame, the chunk size the backend recommends. */
	private static final int CHUNK_MS = 50;
	private static final int CHUNK_BYTES = (int) (FORMAT.getSampleRate()
			* FORMAT.getFrameSize() * CHUNK_MS / 1000);

	public interface Sink {
		/**
		 * @param data a buffer owned by the sink; the capture thread will not
		 *             touch it again
		 */
		void onAudio(byte[] data, int length);

		/** Root-mean-square level, 0..1, for the on-screen meter. */
		void onLevel(float level);

		/** The source ran out (only happens for the file-backed test source). */
		void onFinished();
	}

	private volatile Thread thread;
	private volatile boolean running;

	// ------------------------------------------------------------------
	public synchronized boolean start(Sink sink) {
		stop();

		Path fake = LexClientConfig.fakeMicPath();
		Runnable loop;
		if (fake != null) {
			loop = () -> streamFile(fake, sink);
		} else {
			TargetDataLine line = openLine();
			if (line == null) {
				return false;
			}
			loop = () -> streamLine(line, sink);
		}

		running = true;
		Thread worker = new Thread(loop, "leximine-audio");
		worker.setDaemon(true);
		thread = worker;
		worker.start();
		return true;
	}

	public synchronized void stop() {
		running = false;
		Thread worker = thread;
		thread = null;
		if (worker != null) {
			worker.interrupt();
		}
	}

	public boolean isRunning() {
		return running;
	}

	// ------------------------------------------------------------------
	private void streamLine(TargetDataLine line, Sink sink) {
		try {
			line.start();
			while (running) {
				byte[] buffer = new byte[CHUNK_BYTES];
				int read = line.read(buffer, 0, buffer.length);
				if (read <= 0) {
					break;
				}
				sink.onLevel(rms(buffer, read));
				sink.onAudio(buffer, read);
			}
		} catch (RuntimeException e) {
			Leximine.LOGGER.warn("Microphone capture stopped", e);
		} finally {
			try {
				line.stop();
				line.close();
			} catch (RuntimeException ignored) {
				// Closing a line that is already gone is not interesting.
			}
		}
	}

	/**
	 * Streams a wav file as if it were the microphone, paced in real time.
	 *
	 * <p>This is the seam that makes the whole speech path testable without a
	 * person in the room: the audio goes through the same socket, the same
	 * backend and the same matcher. Enabled with
	 * {@code -Dleximine.fakeMic=<path to wav>}.
	 */
	private void streamFile(Path path, Sink sink) {
		File file = path.toFile();
		if (!file.isFile()) {
			Leximine.LOGGER.error("fakeMic file not found: {}", path);
			sink.onFinished();
			return;
		}
		try (AudioInputStream source = AudioSystem.getAudioInputStream(file);
				AudioInputStream pcm = AudioSystem.getAudioInputStream(FORMAT, source)) {
			Leximine.LOGGER.info("fakeMic streaming {} ({} -> {})",
					path.getFileName(), source.getFormat(), FORMAT);
			long nextFrameAt = System.nanoTime();
			while (running) {
				byte[] buffer = new byte[CHUNK_BYTES];
				int read = pcm.read(buffer, 0, buffer.length);
				if (read <= 0) {
					break;
				}
				sink.onLevel(rms(buffer, read));
				sink.onAudio(buffer, read);

				// Real-time pacing, so the backend's speculative passes see
				// the same arrival pattern a live microphone would produce.
				nextFrameAt += CHUNK_MS * 1_000_000L;
				long sleep = nextFrameAt - System.nanoTime();
				if (sleep > 0) {
					Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		} catch (Exception e) {
			Leximine.LOGGER.error("fakeMic playback failed", e);
		}
		if (running) {
			sink.onFinished();
		}
	}

	// ------------------------------------------------------------------
	private static TargetDataLine openLine() {
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
		String wanted = LexClientConfig.micDeviceFilter();

		try {
			TargetDataLine line = null;
			if (wanted != null) {
				line = openNamedLine(info, wanted);
				if (line == null) {
					Leximine.LOGGER.warn(
							"No input device matching \"{}\"; using the system default", wanted);
				}
			}
			if (line == null) {
				if (!AudioSystem.isLineSupported(info)) {
					Leximine.LOGGER.error(
							"No microphone supports 16 kHz mono 16-bit capture on this machine");
					return null;
				}
				line = (TargetDataLine) AudioSystem.getLine(info);
			}
			// Buffer a few chunks so a scheduling hiccup does not drop audio.
			line.open(FORMAT, CHUNK_BYTES * 8);
			return line;
		} catch (LineUnavailableException e) {
			Leximine.LOGGER.error("Microphone is unavailable (in use by another app?)", e);
			return null;
		} catch (SecurityException e) {
			Leximine.LOGGER.error("Microphone access was denied", e);
			return null;
		}
	}

	private static TargetDataLine openNamedLine(DataLine.Info info, String wanted) {
		String needle = wanted.toLowerCase();
		for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
			if (!mixerInfo.getName().toLowerCase().contains(needle)
					&& !mixerInfo.getDescription().toLowerCase().contains(needle)) {
				continue;
			}
			Mixer mixer = AudioSystem.getMixer(mixerInfo);
			if (!mixer.isLineSupported(info)) {
				continue;
			}
			try {
				Leximine.LOGGER.info("Using input device \"{}\"", mixerInfo.getName());
				return (TargetDataLine) mixer.getLine(info);
			} catch (LineUnavailableException e) {
				Leximine.LOGGER.warn("Input device \"{}\" is busy", mixerInfo.getName());
			}
		}
		return null;
	}

	/** Logged once at startup; the usual complaint is "it picked the wrong mic". */
	public static void logInputDevices() {
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
		StringBuilder found = new StringBuilder();
		for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
			if (AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
				found.append("\n    ").append(mixerInfo.getName());
			}
		}
		if (found.isEmpty()) {
			Leximine.LOGGER.warn("No usable microphone found. Challenges will be granted"
					+ " rather than failed.");
		} else {
			Leximine.LOGGER.info("Microphones available (choose with"
					+ " -Dleximine.micDevice=<name substring>):{}", found);
		}
	}

	public static boolean isMicrophoneAvailable() {
		return LexClientConfig.fakeMicPath() != null
				|| AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMAT));
	}

	private static float rms(byte[] data, int length) {
		long sumOfSquares = 0;
		int count = 0;
		for (int i = 0; i + 1 < length; i += 2) {
			int sample = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
			sumOfSquares += (long) sample * sample;
			count++;
		}
		if (count == 0) {
			return 0f;
		}
		return (float) (Math.sqrt((double) sumOfSquares / count) / 32768.0);
	}
}
