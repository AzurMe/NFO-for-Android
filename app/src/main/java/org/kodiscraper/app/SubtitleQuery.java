package org.kodiscraper.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class SubtitleQuery {
    final MovieStorage storage;
    final MovieStorage.Movie movie;
    final String basename;
    final List<String> titles;
    final int year;
    final int tmdbId;
    final String imdbId;

    SubtitleQuery(MovieStorage storage, MovieStorage.Movie movie, JSONObject details, int tmdbId, String imdbId) {
        this.storage = storage;
        this.movie = movie;
        basename = movie.basename();
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        int releaseYear = 0;
        if (details != null) {
            aliases.add(details.optString("original_title", "").trim());
            aliases.add(details.optString("title", "").trim());
            String date = details.optString("release_date", "");
            if (date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                releaseYear = Integer.parseInt(date.substring(0, 4));
            }
            JSONObject external = details.optJSONObject("external_ids");
            String resolvedId = external == null ? "" : external.optString("imdb_id", "");
            if (!validImdb(resolvedId)) {
                resolvedId = details.optString("imdb_id", "");
            }
            if (validImdb(resolvedId)) {
                imdbId = resolvedId;
            }
        }
        year = releaseYear > 0 ? releaseYear : movie.year;
        aliases.add(movie.title);
        if (details == null && year > 0) {
            // Try the catalog title before the alternate file name within subtitle search limits.
            aliases.add(MovieStorage.firstPartBaseTitle(movie.title));
        }
        aliases.add(movie.alternateTitle);
        if (details == null && year > 0) {
            aliases.add(MovieStorage.firstPartBaseTitle(movie.alternateTitle));
        }
        aliases.remove("");
        titles = new ArrayList<>(aliases);
        this.tmdbId = tmdbId > 0 ? tmdbId : movie.tmdbId;
        this.imdbId = validImdb(imdbId) ? imdbId.toLowerCase(Locale.ROOT)
                : validImdb(movie.imdbId) ? movie.imdbId.toLowerCase(Locale.ROOT) : "";
    }

    private static boolean validImdb(String id) {
        return id != null && id.matches("(?i)tt[0-9]{5,12}");
    }
}
