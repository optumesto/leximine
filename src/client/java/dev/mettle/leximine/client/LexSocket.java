package dev.mettle.leximine.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mettle.leximine.Leximine;
import net.minecraft.client.MinecraftClient;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Long-lived connection to the Python speech backend.
 *
 * <p>One socket for the whole session, opened at client start. A socket per
 * item would spend the TCP and websocket handshake inside the player's
 * five-second window, which is exactly where it cannot be afforded.
 *
 * <p>Every callback is handed back on the client thread, so listeners never
 * have to think about the websocket's reader thread.
 */
public final class LexSocket {
	private static final LexSocket INSTANCE = new LexSocket();

	private static final long[] RETRY_BACKOFF_SECONDS = {2, 5, 15, 30};
	private static final int CONNECT_TIMEOUT_MS = 4000;

	public interface Listener {
		/**
		 * The backend knows this item; here is what to say.
		 *
		 * @param target   the prompt, which at tier 3 is a whole sentence
		 * @param itemName the plain item name, which is what the vocabulary
		 *                 list records regardless of tier
		 * @param reading  how to say it in Latin letters, for scripts an
		 *                 English reader cannot sound out. Blank whenever the
		 *                 written form is already the instruction, and blank
		 *                 for Japanese containing kanji, where a reading is a
		 *                 dictionary lookup rather than a rule.
		 * @param level    which rung of the sentence pool this was drawn
		 *                 from, 0-3, decided by the backend from how much the
		 *                 player has already said correctly
		 */
		void onChallengeReady(int challengeId, String target, String itemName, String english,
				String reading, int level);

		/**
		 * Running transcript while the player talks.
		 *
		 * @param attempt which try this text belongs to. Carried because the
		 *                client otherwise keeps labelling live text with the
		 *                number of the attempt that already finished, which
		 *                puts try two's words under "#1"
		 * @param stable  the leading words successive passes agree on; the
		 *                rest of {@code transcript} is still being revised
		 */
		void onPartial(int challengeId, int attempt, String transcript, String stable,
				int confidence);

		/**
		 * One complete try came up short, and the next one starts fresh.
		 *
		 * <p>Distinct from {@code onPartial}: a partial is the same attempt
		 * still being spoken, while this says that attempt is finished and
		 * scored. Without it the player cannot tell whether a second try was
		 * heard separately or merged into the first.
		 *
		 * @param note in trade mode, the model's own words on why that
		 *             sentence was turned down; blank otherwise, because a
		 *             word match has nothing to say beyond the score
		 */
		void onAttempt(int challengeId, int attempt, String transcript, int confidence,
				String note);

		/**
		 * Terminal verdict.
		 *
		 * @param note as above: the judge's reason on a trade, blank on a word
		 */
		void onResult(int challengeId, boolean success, String transcript,
				int confidence, String reason, String note);

		/**
		 * No verdict is coming: backend down, model not loaded, or no
		 * translation for this item. The caller decides what is fair, which
		 * for this mod means granting the item.
		 */
		void onUnavailable(int challengeId, String reason);
	}

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
			runnable -> {
				Thread thread = new Thread(runnable, "leximine-socket");
				thread.setDaemon(true);
				return thread;
			});

	private final AtomicInteger retryIndex = new AtomicInteger();

	private volatile Socket socket;
	private volatile boolean handshakeComplete;
	private volatile boolean modelReady;
	private volatile String backendDescription = "not connected";
	private volatile int activeChallengeId = -1;
	private volatile Listener listener;

	private LexSocket() {
	}

	public static LexSocket get() {
		return INSTANCE;
	}

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	/** True when a challenge can actually be judged right now. */
	public boolean isUsable() {
		Socket current = socket;
		return current != null && current.isOpen() && handshakeComplete && modelReady;
	}

	public String describeBackend() {
		return backendDescription;
	}

	// ------------------------------------------------------------------
	public void connect() {
		Socket current = socket;
		if (current != null) {
			ReadyState state = current.getReadyState();
			// NOT_YET_CONNECTED covers an attempt still in flight; every
			// instance here has connect() called immediately after creation.
			if (state == ReadyState.OPEN || state == ReadyState.NOT_YET_CONNECTED) {
				return;
			}
		}
		URI uri = LexClientConfig.backendUri();
		Leximine.LOGGER.info("Connecting to speech backend at {}", uri);
		Socket fresh = new Socket(uri);
		socket = fresh;
		handshakeComplete = false;
		modelReady = false;
		// A WebSocketClient instance cannot be reused once closed, so each
		// attempt gets a new one rather than calling reconnect().
		fresh.connect();
	}

	private void scheduleReconnect() {
		int index = Math.min(retryIndex.getAndIncrement(), RETRY_BACKOFF_SECONDS.length - 1);
		long delay = RETRY_BACKOFF_SECONDS[index];
		scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
	}

	public void shutdown() {
		Socket current = socket;
		socket = null;
		if (current != null) {
			current.close();
		}
		scheduler.shutdownNow();
	}

	// ------------------------------------------------------------------
	/**
	 * Ask the backend to open a challenge.
	 *
	 * @return false if there is no usable connection, in which case no
	 *         callback will ever arrive and the caller should fail open
	 */
	public boolean startChallenge(int challengeId, String translationKey, String lang,
			String difficulty, int tier, String context, int timeoutMs) {
		if (!isUsable()) {
			connect();
			return false;
		}
		activeChallengeId = challengeId;

		JsonObject message = new JsonObject();
		message.addProperty("type", "challenge");
		message.addProperty("id", Integer.toString(challengeId));
		message.addProperty("translation_key", translationKey);
		// Sent per challenge rather than configured on the backend, so the
		// language can be changed in game without restarting anything.
		message.addProperty("lang", lang);
		message.addProperty("difficulty", difficulty);
		// How much has to be said scales with how valuable the item is; the
		// backend turns the tier into the actual prompt.
		message.addProperty("tier", tier);
		message.addProperty("context", context);
		message.addProperty("timeout_ms", timeoutMs);
		return send(message);
	}

	/**
	 * Open a challenge judged by a language model rather than a word match.
	 *
	 * <p>Used for merchants: "is this a sensible way to ask for an emerald"
	 * has no single right answer to compare against.
	 */
	public boolean startTradeChallenge(int challengeId, String translationKey, String lang,
			String difficulty, int timeoutMs) {
		if (!isUsable()) {
			connect();
			return false;
		}
		activeChallengeId = challengeId;

		JsonObject message = new JsonObject();
		message.addProperty("type", "challenge");
		message.addProperty("id", Integer.toString(challengeId));
		message.addProperty("translation_key", translationKey);
		message.addProperty("lang", lang);
		message.addProperty("difficulty", difficulty);
		message.addProperty("mode", "trade");
		message.addProperty("tier", 2);
		message.addProperty("context", "trade");
		message.addProperty("timeout_ms", timeoutMs);
		return send(message);
	}

	/**
	 * The same model-judged challenge, but for a sentence that is not a trade.
	 *
	 * <p>{@code task} replaces the merchant framing in the backend's prompt --
	 * "say why you are going to the Nether" rather than "ask for this item".
	 * A {@code translation_key} is still sent because the backend looks an
	 * entry up by it before it will open any challenge at all; for a task the
	 * key only decides what the overlay has to show, not what is judged.
	 */
	public boolean startSentenceChallenge(int challengeId, String translationKey, String lang,
			String difficulty, String task, int timeoutMs) {
		if (!isUsable()) {
			connect();
			return false;
		}
		activeChallengeId = challengeId;

		JsonObject message = new JsonObject();
		message.addProperty("type", "challenge");
		message.addProperty("id", Integer.toString(challengeId));
		message.addProperty("translation_key", translationKey);
		message.addProperty("lang", lang);
		message.addProperty("difficulty", difficulty);
		message.addProperty("mode", "trade");
		message.addProperty("task", task);
		message.addProperty("tier", 3);
		message.addProperty("context", "gate");
		message.addProperty("timeout_ms", timeoutMs);
		return send(message);
	}

	/** Raw 16 kHz mono signed-16-bit little-endian PCM, as PROTOCOL.md requires. */
	public void sendAudio(byte[] data, int length) {
		Socket current = socket;
		if (current == null || !current.isOpen() || activeChallengeId < 0) {
			return;
		}
		try {
			current.send(ByteBuffer.wrap(data, 0, length));
		} catch (RuntimeException e) {
			// A closed socket mid-utterance is normal if the backend restarts;
			// the challenge will fall through to its timeout.
			Leximine.LOGGER.debug("audio send failed", e);
		}
	}

	/** "Stop listening and decide now." */
	public void endChallenge(int challengeId) {
		JsonObject message = new JsonObject();
		message.addProperty("type", "end");
		message.addProperty("id", Integer.toString(challengeId));
		send(message);
	}

	public void cancelChallenge(int challengeId) {
		if (activeChallengeId == challengeId) {
			activeChallengeId = -1;
		}
		JsonObject message = new JsonObject();
		message.addProperty("type", "cancel");
		message.addProperty("id", Integer.toString(challengeId));
		send(message);
	}

	private boolean send(JsonObject message) {
		Socket current = socket;
		if (current == null || !current.isOpen()) {
			return false;
		}
		try {
			current.send(message.toString());
			return true;
		} catch (RuntimeException e) {
			Leximine.LOGGER.warn("Failed to send {}", message, e);
			return false;
		}
	}

	// ------------------------------------------------------------------
	private void onClientThread(Runnable action) {
		MinecraftClient.getInstance().execute(action);
	}

	private void handleMessage(String raw) {
		JsonObject json;
		try {
			json = JsonParser.parseString(raw).getAsJsonObject();
		} catch (RuntimeException e) {
			Leximine.LOGGER.warn("Unparseable backend frame: {}", raw);
			return;
		}

		String type = optString(json, "type", "");
		switch (type) {
			case "hello" -> {
				handshakeComplete = true;
				modelReady = json.has("ready") && json.get("ready").getAsBoolean();
				backendDescription = optString(json, "model", "?") + " on "
						+ optString(json, "device", "?") + ", "
						+ optString(json, "target_lang", "?");
				retryIndex.set(0);
				Leximine.LOGGER.info("Speech backend ready={} ({})",
						modelReady, backendDescription);
				if (!modelReady) {
					// The model loads for a few seconds at backend startup.
					scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
				}
			}
			case "challenge_ready" -> {
				int id = parseId(json);
				String target = optString(json, "target", "");
				String itemName = optString(json, "item_name", target);
				String english = optString(json, "english", "");
				// Blank for Spanish and the rest of the Latin-script
				// languages, where the spelling is already the instruction.
				String reading = optString(json, "reading", "");
				int level = json.has("level") ? json.get("level").getAsInt() : 0;
				Listener current = listener;
				if (current != null) {
					onClientThread(() -> current.onChallengeReady(
							id, target, itemName, english, reading, level));
				}
			}
			case "partial" -> {
				int id = parseId(json);
				int attempt = json.has("attempt") ? json.get("attempt").getAsInt() : 0;
				String transcript = optString(json, "transcript", "");
				String stable = optString(json, "stable", "");
				int confidence = json.has("confidence") ? json.get("confidence").getAsInt() : 0;
				Listener current = listener;
				if (current != null) {
					onClientThread(() ->
							current.onPartial(id, attempt, transcript, stable, confidence));
				}
			}
			case "attempt" -> {
				int id = parseId(json);
				int attempt = json.has("attempt") ? json.get("attempt").getAsInt() : 0;
				String transcript = optString(json, "transcript", "");
				int confidence = json.has("confidence") ? json.get("confidence").getAsInt() : 0;
				String note = optString(json, "note", "");
				Listener current = listener;
				if (current != null) {
					onClientThread(() ->
							current.onAttempt(id, attempt, transcript, confidence, note));
				}
			}
			case "result" -> {
				int id = parseId(json);
				boolean success = "success".equals(optString(json, "status", ""));
				String transcript = optString(json, "transcript", "");
				String reason = optString(json, "reason", "");
				int confidence = json.has("confidence") ? json.get("confidence").getAsInt() : 0;
				String note = optString(json, "note", "");
				if (activeChallengeId == id) {
					activeChallengeId = -1;
				}
				Listener current = listener;
				if (current != null) {
					onClientThread(() -> current.onResult(id, success, transcript,
							confidence, reason, note));
				}
			}
			case "error" -> {
				int id = parseId(json);
				String code = optString(json, "code", "error");
				Leximine.LOGGER.info("Backend declined challenge {}: {}", id, code);
				if (activeChallengeId == id) {
					activeChallengeId = -1;
				}
				Listener current = listener;
				if (current != null) {
					onClientThread(() -> current.onUnavailable(id, code));
				}
			}
			case "pong" -> {
			}
			default -> Leximine.LOGGER.debug("Unhandled backend frame type {}", type);
		}
	}

	private int parseId(JsonObject json) {
		try {
			return Integer.parseInt(optString(json, "id", "-1"));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String optString(JsonObject json, String key, String fallback) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
	}

	// ------------------------------------------------------------------
	private final class Socket extends WebSocketClient {
		private Socket(URI uri) {
			// The 4-argument constructor is the only one that takes a connect
			// timeout; without it a host that silently drops packets leaves
			// the connecting thread parked until the OS gives up.
			super(uri, new Draft_6455(), null, CONNECT_TIMEOUT_MS);
			setConnectionLostTimeout(30);
		}

		@Override
		public void onOpen(ServerHandshake handshake) {
			Leximine.LOGGER.info("Speech backend connected (HTTP {})", handshake.getHttpStatus());
		}

		@Override
		public void onMessage(String message) {
			handleMessage(message);
		}

		@Override
		public void onMessage(ByteBuffer bytes) {
			// The backend never sends binary.
		}

		@Override
		public void onClose(int code, String reason, boolean remote) {
			handshakeComplete = false;
			modelReady = false;
			backendDescription = "disconnected";
			int pending = activeChallengeId;
			activeChallengeId = -1;
			Leximine.LOGGER.info("Speech backend disconnected (code {}{}){}",
					code, remote ? ", by server" : "",
					pending >= 0 ? " with challenge " + pending + " open" : "");

			if (pending >= 0) {
				Listener current = listener;
				if (current != null) {
					onClientThread(() -> current.onUnavailable(pending, "disconnected"));
				}
			}
			if (socket == this) {
				scheduleReconnect();
			}
		}

		@Override
		public void onError(Exception exception) {
			// onClose always follows, which is where reconnection is handled.
			Leximine.LOGGER.debug("Speech backend socket error", exception);
		}
	}
}
