package dev.mettle.leximine.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mettle.leximine.Leximine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Which languages you can actually be asked in.
 *
 * <p>The backend already knows, and knows better than any list compiled here
 * would: {@code /languages} reports what it has dictionaries for on disk
 * (<em>ready</em>) and every language the player's own Minecraft install
 * could provide (<em>installable</em>, around 140 of them). Picking an
 * installable one is not a special case — the backend extracts it from the
 * game's assets the first time a challenge asks for it.
 *
 * <p>There is a built-in fallback for the case that matters most: the title
 * screen is exactly where the backend is most likely to be closed, and a
 * language picker that is empty until you start a server would be useless at
 * the only moment you want it.
 */
public final class LanguageCatalog {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(2))
			.build();

	/**
	 * @param ready     the backend has a dictionary for this already
	 * @param sentences tiers 3 and 4 actually produce a sentence in this
	 *                  language. Without it the difficulty ladder is flat --
	 *                  a netherite ingot asks exactly what a stick does --
	 *                  which is worth knowing before you pick, not after.
	 */
	public record Entry(String code, String name, boolean ready, boolean sentences) {
		/** "Japanese (ja_jp)", or just the code if we have no name for it. */
		public String label() {
			return name.isBlank() ? code : name + "  (" + code + ")";
		}
	}

	/**
	 * Names for the codes a player is realistically going to pick, which is
	 * also the fallback list. Minecraft's own language names live in the game
	 * assets the backend reads, but they are written in the language itself
	 * and the point here is to be readable before you have chosen.
	 */
	private static final Map<String, String> NAMES = new LinkedHashMap<>();

	static {
		NAMES.put("es_es", "Spanish");
		NAMES.put("ja_jp", "Japanese");
		NAMES.put("fr_fr", "French");
		NAMES.put("de_de", "German");
		NAMES.put("it_it", "Italian");
		NAMES.put("pt_br", "Portuguese (Brazil)");
		NAMES.put("ko_kr", "Korean");
		NAMES.put("zh_cn", "Chinese (Simplified)");
		NAMES.put("ru_ru", "Russian");
		NAMES.put("nl_nl", "Dutch");
		NAMES.put("pl_pl", "Polish");
		NAMES.put("sv_se", "Swedish");
		NAMES.put("tr_tr", "Turkish");
		NAMES.put("uk_ua", "Ukrainian");
		NAMES.put("vi_vn", "Vietnamese");
		NAMES.put("id_id", "Indonesian");
		NAMES.put("he_il", "Hebrew");
		NAMES.put("ar_sa", "Arabic");
		NAMES.put("hi_in", "Hindi");
		NAMES.put("th_th", "Thai");
		NAMES.put("el_gr", "Greek");
		NAMES.put("cs_cz", "Czech");
		NAMES.put("da_dk", "Danish");
		NAMES.put("fi_fi", "Finnish");
		NAMES.put("no_no", "Norwegian");
		NAMES.put("hu_hu", "Hungarian");
		NAMES.put("ro_ro", "Romanian");
	}

	private LanguageCatalog() {
	}

	public static String nameFor(String code) {
		return NAMES.getOrDefault(code == null ? "" : code.toLowerCase(Locale.ROOT), "");
	}

	/** The list to show when the backend cannot be reached. */
	public static List<Entry> fallback() {
		List<Entry> rows = new ArrayList<>();
		for (Map.Entry<String, String> entry : NAMES.entrySet()) {
			// `false` across the board on purpose: with no backend to ask,
			// claiming a dictionary is ready would be a guess, and the one
			// shown as ready would be the one that silently is not.
			rows.add(new Entry(entry.getKey(), entry.getValue(), false, false));
		}
		return rows;
	}

	/**
	 * Ask the backend. Never fails: an unreachable backend yields
	 * {@link #fallback()} rather than an error, because a picker that refuses
	 * to open is worse than one showing a shorter list.
	 */
	public static CompletableFuture<List<Entry>> fetch() {
		URI uri = URI.create(LexClientConfig.httpBase() + "/languages");
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(4))
				.GET()
				.build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(LanguageCatalog::parse)
				.exceptionally(error -> {
					Leximine.LOGGER.debug("language list unavailable; using the built-in one",
							error);
					return fallback();
				});
	}

	private static List<Entry> parse(HttpResponse<String> response) {
		if (response.statusCode() != 200) {
			return fallback();
		}
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		// Ready first, then everything else: the ones that cost nothing to
		// switch to are the ones worth showing at the top, and the
		// installable list is 140 entries of mostly noise.
		Map<String, Entry> byCode = new LinkedHashMap<>();
		collect(root, "ready", true, byCode);
		collect(root, "installable", false, byCode);
		return byCode.isEmpty() ? fallback() : new ArrayList<>(byCode.values());
	}

	private static void collect(JsonObject root, String field, boolean ready,
			Map<String, Entry> into) {
		if (!root.has(field) || !root.get(field).isJsonArray()) {
			return;
		}
		JsonArray array = root.getAsJsonArray(field);
		for (JsonElement element : array) {
			// The two arrays are not the same shape -- `ready` carries objects
			// with a code and a name, `installable` is bare strings -- so both
			// forms are accepted rather than assuming one.
			String code;
			String name = "";
			boolean sentences = false;
			if (element.isJsonObject()) {
				JsonObject object = element.getAsJsonObject();
				if (!object.has("code")) {
					continue;
				}
				code = object.get("code").getAsString();
				if (object.has("name") && !object.get("name").isJsonNull()) {
					name = object.get("name").getAsString();
				}
				sentences = object.has("sentences")
						&& object.get("sentences").getAsBoolean();
			} else if (element.isJsonPrimitive()) {
				code = element.getAsString();
			} else {
				continue;
			}
			if (code == null || code.isBlank() || into.containsKey(code)) {
				continue;
			}
			String friendly = nameFor(code);
			into.put(code, new Entry(code, friendly.isBlank() ? name : friendly,
					ready, sentences));
		}
	}
}
