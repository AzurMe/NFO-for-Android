package org.kodiscraper.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class TvdbMovie {
    private TvdbMovie() {}

    static JSONObject toKodiData(JSONObject movie, String language, JSONArray artworkTypes, JSONArray peopleTypes)
            throws JSONException {
        JSONObject translations = object(movie, "translations");
        String original = text(movie, "name");
        String title = translation(rows(translations, "nameTranslations"), language, "name");
        String overview = translation(rows(translations, "overviewTranslations"), language, "overview");
        String tagline = translation(rows(translations, "overviewTranslations"), language, "tagline");
        String date = text(object(movie, "first_release"), "date");
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            date = "";
            for (JSONObject release : rows(movie, "releases")) {
                String candidate = text(release, "date");
                if (candidate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && (date.isEmpty() || candidate.compareTo(date) < 0)) {
                    date = candidate;
                }
            }
        }
        JSONObject external = new JSONObject().put("tvdb_id", movie.optInt("id"));
        String imdb = remoteId(movie, "imdb");
        if (imdb.matches("tt[0-9]{5,12}")) external.put("imdb_id", imdb);
        String wikidata = remoteId(movie, "wikidata");
        if (wikidata.matches("Q[0-9]+")) external.put("wikidata_id", wikidata);
        JSONObject out = new JSONObject()
                .put("metadata_source", "tvdb").put("id", ScrapeEngine.parseId(remoteId(movie, "tmdb")))
                .put("external_ids", external).put("title", first(title, original))
                .put("original_title", original).put("original_language", isoLanguage(text(movie, "originalLanguage")))
                .put("overview", overview).put("tagline", tagline)
                .put("runtime", movie.optInt("runtime")).put("release_date", date)
                .put("year", ScrapeEngine.parseId(text(movie, "year")))
                .put("genres", movie.optJSONArray("genres"))
                .put("keywords", new JSONObject().put("keywords", movie.optJSONArray("tagOptions")));
        addCredits(out, movie, peopleTypes);
        addArtwork(out, movie, language, artworkTypes);
        addReleaseInfo(out, movie, date);
        addStudios(out, movie);
        JSONArray trailers = new JSONArray();
        for (JSONObject trailer : rows(movie, "trailers")) {
            String id = youtubeId(text(trailer, "url"));
            if (!id.isEmpty()) {
                trailers.put(new JSONObject().put("type", "Trailer").put("site", "YouTube").put("key", id)
                        .put("iso_639_1", isoLanguage(text(trailer, "language"))));
            }
        }
        out.put("videos", new JSONObject().put("results", trailers));
        return out;
    }

    private static void addCredits(JSONObject out, JSONObject movie, JSONArray peopleTypes) throws JSONException {
        Map<Integer, String> roles = names(peopleTypes);
        JSONArray cast = new JSONArray();
        JSONArray crew = new JSONArray();
        ArrayList<JSONObject> characters = rows(movie, "characters");
        characters.sort(Comparator.comparingInt(person -> person.optInt("sort", Integer.MAX_VALUE)));
        for (JSONObject character : characters) {
            String name = text(character, "personName");
            if (name.isEmpty()) continue;
            String role = text(character, "peopleType");
            String type = first(roles.get(character.optInt("type")), role).toLowerCase(Locale.ROOT);
            JSONObject person = new JSONObject().put("name", name).put("id", character.optInt("peopleId"));
            if (type.contains("director")) {
                crew.put(person.put("job", "Director"));
            } else if (type.contains("writer") || type.contains("screenplay")) {
                crew.put(person.put("job", "Writer"));
            } else if (type.contains("actor") || type.contains("guest star")) {
                cast.put(person.put("character", text(character, "name")).put("order", cast.length())
                        .put("profile_path", imageUrl(first(text(character, "personImgURL"), text(character, "image")))));
            }
        }
        out.put("credits", new JSONObject().put("cast", cast).put("crew", crew));
    }

    private static void addArtwork(JSONObject out, JSONObject movie, String language, JSONArray types) throws JSONException {
        Map<Integer, String> names = names(types);
        ArrayList<JSONObject> images = rows(movie, "artworks");
        images.sort(Comparator.comparingInt((JSONObject art) -> languageRank(text(art, "language"), language))
                .thenComparing(Comparator.comparingDouble((JSONObject art) -> art.optDouble("score", 0)).reversed())
                .thenComparing(Comparator.comparingLong((JSONObject art) -> (long) art.optInt("width") * art.optInt("height")).reversed()));
        JSONArray posters = new JSONArray();
        JSONArray backdrops = new JSONArray();
        JSONArray logos = new JSONArray();
        for (JSONObject image : images) {
            String url = imageUrl(text(image, "image"));
            if (url.isEmpty()) continue;
            String name = names.getOrDefault(image.optInt("type"), "");
            String imageLanguage = isoLanguage(text(image, "language"));
            boolean textless = image.has("includesText") ? !image.optBoolean("includesText") : imageLanguage.isEmpty();
            JSONObject mapped = new JSONObject().put("file_path", url)
                    .put("iso_639_1", textless ? "" : first(imageLanguage, "und"));
            if (name.contains("poster")) posters.put(mapped);
            else if (name.contains("background") || name.contains("fanart") || name.contains("backdrop")
                    || name.contains("landscape") || name.contains("screencap") || name.contains("thumb")) backdrops.put(mapped);
            else if (name.contains("logo")) logos.put(mapped);
        }
        String poster = first(text(posters.optJSONObject(0), "file_path"), imageUrl(text(movie, "image")));
        String background = "";
        for (int index = 0; index < backdrops.length(); index++) {
            JSONObject item = backdrops.getJSONObject(index);
            background = first(background, text(item, "file_path"));
            if (text(item, "iso_639_1").isEmpty()) {
                background = text(item, "file_path");
                break;
            }
        }
        out.put("poster_path", poster).put("backdrop_path", background);
        out.put("images", new JSONObject().put("posters", posters).put("backdrops", backdrops).put("logos", logos));
    }

    private static void addReleaseInfo(JSONObject out, JSONObject movie, String date) throws JSONException {
        JSONArray countries = new JSONArray();
        for (JSONObject country : rows(movie, "production_countries")) {
            String name = first(text(country, "name"), text(country, "country"));
            if (!name.isEmpty()) countries.put(new JSONObject().put("name", name));
        }
        String origin = isoCountry(text(movie, "originalCountry"));
        out.put("production_countries", countries).put("origin_country", new JSONArray().put(origin));
        JSONArray ratings = new JSONArray();
        for (JSONObject rating : rows(movie, "contentRatings")) {
            String country = isoCountry(text(rating, "country"));
            String value = text(rating, "name");
            if (!country.isEmpty() && !value.isEmpty()) {
                JSONObject entry = new JSONObject().put("certification", value).put("release_date", date).put("type", 3);
                ratings.put(new JSONObject().put("iso_3166_1", country).put("release_dates", new JSONArray().put(entry)));
            }
        }
        out.put("release_dates", new JSONObject().put("results", ratings));
    }

    private static void addStudios(JSONObject out, JSONObject movie) throws JSONException {
        Map<String, JSONObject> unique = new LinkedHashMap<>();
        JSONObject companies = object(movie, "companies");
        for (ArrayList<JSONObject> list : java.util.Arrays.asList(rows(movie, "studios"),
                rows(companies, "studio"), rows(companies, "production"))) {
            for (JSONObject studio : list) {
                String name = text(studio, "name");
                if (!name.isEmpty()) unique.putIfAbsent(name, studio);
            }
        }
        out.put("production_companies", new JSONArray(unique.values()));
    }

    static String remoteId(JSONObject movie, String kind) {
        for (JSONObject remote : rows(movie, "remoteIds")) {
            String name = text(remote, "sourceName").toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            boolean matches = kind.equals(name) || ("tmdb".equals(kind) && name.startsWith("themoviedb"));
            if (matches) return text(remote, "id");
        }
        return "";
    }

    static String imageUrl(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            URI uri = URI.create(value.startsWith("banners/") ? "/" + value : value);
            String host = uri.getHost();
            if (uri.isAbsolute() && (!"https".equalsIgnoreCase(uri.getScheme())
                    || !("artworks.thetvdb.com".equalsIgnoreCase(host) || "thetvdb.com".equalsIgnoreCase(host)
                    || "www.thetvdb.com".equalsIgnoreCase(host)))) return "";
            String path = uri.getRawPath();
            if (uri.getRawAuthority() != null && host == null) return "";
            if (uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null
                    || uri.getFragment() != null || path == null || !path.startsWith("/banners/")
                    || !path.equals(uri.normalize().getRawPath())
                    || !path.matches("/banners/[a-zA-Z0-9_./%()-]+\\.(?i:jpe?g|png|webp)")) return "";
            return "https://artworks.thetvdb.com" + path;
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    static String first(String ...values) {
        for (String value : values) if (value != null && !value.isEmpty()) return value;
        return "";
    }

    static String text(JSONObject value, String key) {
        return value == null || value.isNull(key) ? "" : value.optString(key, "").trim();
    }

    static JSONObject object(JSONObject parent, String key) {
        JSONObject result = parent.optJSONObject(key);
        return result == null ? new JSONObject() : result;
    }

    static ArrayList<JSONObject> rows(JSONObject parent, String key) { return rows(parent.optJSONArray(key)); }

    static ArrayList<JSONObject> rows(JSONArray array) {
        ArrayList<JSONObject> rows = new ArrayList<>();
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                JSONObject row = array.optJSONObject(index);
                if (row != null) rows.add(row);
            }
        }
        return rows;
    }

    private static Map<Integer, String> names(JSONArray array) {
        Map<Integer, String> names = new LinkedHashMap<>();
        for (JSONObject row : rows(array)) {
            names.put(row.optInt("id"), (text(row, "slug") + " " + text(row, "name")).trim().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static String translation(ArrayList<JSONObject> rows, String language, String key) {
        String best = "";
        int rank = Integer.MAX_VALUE;
        for (JSONObject row : rows) {
            String value = text(row, key);
            int preference = languageRank(text(row, "language"), language);
            if (!value.isEmpty() && preference < rank) {
                rank = preference;
                best = value;
            }
        }
        return best;
    }

    private static int languageRank(String actual, String wanted) {
        if (actual.equals(wanted)) return 0;
        if (actual.equals("eng")) return 1;
        if (actual.isEmpty()) return 2;
        return 3;
    }

    private static String isoCountry(String code) {
        for (String iso : Locale.getISOCountries()) {
            if (iso.equalsIgnoreCase(code) || new Locale("", iso).getISO3Country().equalsIgnoreCase(code)) return iso;
        }
        return "";
    }

    private static String isoLanguage(String code) {
        if (code.equals("zht") || code.equals("zho") || code.equals("chi")) return "zh";
        for (String iso : Locale.getISOLanguages()) {
            if (iso.equalsIgnoreCase(code) || new Locale(iso).getISO3Language().equalsIgnoreCase(code)) return iso;
        }
        return "";
    }

    private static String youtubeId(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (path == null || !"https".equalsIgnoreCase(uri.getScheme())) return "";
            String id = "";
            if ("youtu.be".equalsIgnoreCase(host)) id = path.replaceFirst("^/", "");
            if ("www.youtube.com".equalsIgnoreCase(host) || "youtube.com".equalsIgnoreCase(host)) {
                for (String field : first(uri.getQuery(), "").split("&")) {
                    if (field.startsWith("v=")) id = field.substring(2);
                }
            }
            return id.matches("[a-zA-Z0-9_-]{11}") ? id : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }
}
