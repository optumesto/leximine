package dev.mettle.leximine.client;

import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.config.LexConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

/**
 * Client-only settings derived from the shared config file.
 *
 * <p>Gameplay settings -- language, frequency, timeout -- are decided by the
 * server and arrive with each challenge, so nothing here has an opinion about
 * them. What is left is how to reach the backend and how to listen.
 */
public final class LexClientConfig {
	private LexClientConfig() {
	}

	public static URI backendUri() {
		String raw = LexConfig.get().backendUrl();
		try {
			return new URI(raw);
		} catch (URISyntaxException e) {
			Leximine.LOGGER.error("Invalid backend url {}; using the default", raw);
			try {
				return new URI("ws://127.0.0.1:8760/ws");
			} catch (URISyntaxException impossible) {
				throw new IllegalStateException(impossible);
			}
		}
	}

	/**
	 * Same host and port as the websocket, over plain HTTP, for the REST
	 * helpers. Derived rather than configured twice so the two cannot drift.
	 */
	public static String httpBase() {
		URI ws = backendUri();
		String scheme = "wss".equalsIgnoreCase(ws.getScheme()) ? "https" : "http";
		int port = ws.getPort();
		return scheme + "://" + ws.getHost() + (port == -1 ? "" : ":" + port);
	}

	/** Substring of the input device name to prefer, or null for the default. */
	public static String micDeviceFilter() {
		String raw = LexConfig.get().microphoneDevice();
		return raw == null || raw.isBlank() ? null : raw.trim();
	}

	/**
	 * Path to a wav file to stream instead of opening the microphone.
	 *
	 * <p>Exists so the speech path can be tested end to end without a person
	 * talking; see scripts/e2e_test.py. Never set in normal play, and
	 * deliberately launch-only rather than a config field.
	 */
	public static Path fakeMicPath() {
		String raw = System.getProperty("leximine.fakeMic", "");
		return raw.isBlank() ? null : Path.of(raw.trim());
	}

	public static void logSettings() {
		LexConfig config = LexConfig.get();
		Leximine.LOGGER.info("backend={} pushToTalk={} micDevice={} fakeMic={}",
				backendUri(), config.pushToTalk(),
				micDeviceFilter() == null ? "(default)" : micDeviceFilter(),
				fakeMicPath() == null ? "(off)" : fakeMicPath());
	}
}
