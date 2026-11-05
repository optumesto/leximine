package dev.mettle.leximine.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mettle.leximine.Leximine;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Asks the backend which word is worth being asked again.
 *
 * <p>The choice comes from the backend's own record of every verdict it has
 * issued, so it reflects what the player actually keeps getting wrong rather
 * than a random pick. The client does the asking because the client is the
 * only side that talks to the backend.
 */
public final class ReviewLookup {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(2))
			.build();

	/** One review word: what to say, and what it is. */
	public record Word(String key, String target, String itemName, String english,
					   int successCount, int failCount) {
	}

	private ReviewLookup() {
	}

	/**
	 * @return empty when there is no history yet, which is not a failure --
	 *         the caller should simply let the block open
	 */
	public static CompletableFuture<Optional<Word>> pick(String lang) {
		URI uri = URI.create(LexClientConfig.httpBase()
				+ "/review?lang=" + URLEncoder.encode(lang, StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(4))
				.GET()
				.build();

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(ReviewLookup::parse)
				.exceptionally(error -> {
					Leximine.LOGGER.debug("review lookup failed", error);
					return Optional.empty();
				});
	}

	private static Optional<Word> parse(HttpResponse<String> response) {
		if (response.statusCode() == 404) {
			// Nothing attempted yet in this language. Expected on a fresh
			// world, and not worth a warning.
			return Optional.empty();
		}
		if (response.statusCode() != 200) {
			Leximine.LOGGER.warn("review -> HTTP {}", response.statusCode());
			return Optional.empty();
		}
		try {
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			return Optional.of(new Word(
					json.get("key").getAsString(),
					json.get("target").getAsString(),
					json.has("item_name") ? json.get("item_name").getAsString() : "",
					json.has("english") ? json.get("english").getAsString() : "",
					json.has("success_count") ? json.get("success_count").getAsInt() : 0,
					json.has("fail_count") ? json.get("fail_count").getAsInt() : 0));
		} catch (RuntimeException e) {
			Leximine.LOGGER.warn("Unreadable /review body: {}", response.body(), e);
			return Optional.empty();
		}
	}
}
