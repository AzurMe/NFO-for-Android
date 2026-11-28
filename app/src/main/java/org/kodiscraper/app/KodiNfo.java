package org.kodiscraper.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class KodiNfo {
    private static final String TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/";
    private static final Pattern IMAGE_PATH = Pattern.compile("/[a-zA-Z0-9_.-]{1,200}");
    private static final Pattern PREMIERE_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern YOUTUBE_ID = Pattern.compile("[a-zA-Z0-9_-]{11}");
    private static final Set<String> ISO_LANGUAGE_CODES = new HashSet<>(Arrays.asList(Locale.getISOLanguages()));
    private static final Set<String> WRITER_JOBS = new HashSet<>(Arrays.asList(
            "Screenplay", "Writer", "Author", "Story", "Novel", "Original Story", "Teleplay",
            "Adaptation", "Book", "Comic Book", "Graphic Novel", "Characters", "Dialogue"));
    private static final String[] OPTIONAL_ART = {
            "landscape", "banner", "clearart", "clearlogo", "discart", "keyart"
    };

    private KodiNfo() {}

    static void write(
            OutputStream output,
            JSONObject movie,
            Map<String, String> artwork,
            Map<Integer, String> actorThumbnails,
            String language,
            String region,
            boolean hasLocalTrailer
    ) throws IOException {
        String title = first(text(movie, "title"), text(movie, "original_title"));
        if (title.isEmpty()) {
            throw new IOException("电影资料缺少标题");
        }

        Locale locale = Locale.forLanguageTag(language == null ? "" : language);
        String country = first(region == null ? "" : region.trim(), locale.getCountry(), "US")
                .toUpperCase(Locale.ROOT);
        Xml xml = new Xml();
        xml.open("movie");
        xml.tag("title", title);
        xml.tag("originaltitle", text(movie, "original_title"));
        xml.tag("originallanguage", originalLanguage(movie));
        xml.tag("plot", text(movie, "overview"));
        xml.tag("tagline", text(movie, "tagline"));
        int runtime = movie.optInt("runtime");
        if (runtime > 0) {
            xml.tag("runtime", Integer.toString(runtime));
        }

        String premiere = premiere(movie);
        xml.tag("premiered", premiere);
        int year = movie.optInt("year");
        if (premiere.isEmpty() && year >= 1870 && year <= 2100) xml.tag("year", Integer.toString(year));
        xml.tag("mpaa", certification(movie, country));
        writeIds(xml, movie);
        writeRating(xml, movie);
        writeCollection(xml, movie);

        writeNames(xml, "genre", array(movie, "genres"));
        writeNames(xml, "studio", array(movie, "production_companies"));
        JSONArray productionCountries = movie.optJSONArray("production_countries");
        if (productionCountries != null && productionCountries.length() > 0) {
            writeNames(xml, "country", productionCountries);
        } else {
            writeCountryCodes(xml, movie.optJSONArray("origin_country"), locale);
        }

        JSONObject credits = movie.optJSONObject("credits");
        writeCrew(xml, array(credits, "crew"));
        writeCast(xml, array(credits, "cast"), actorThumbnails);
        writeArtwork(xml, movie, artwork);

        if (!hasLocalTrailer) {
            xml.tag("trailer", trailer(movie, locale.getLanguage(), country));
        }
        xml.close("movie");
        output.write(xml.contents.toString().getBytes(StandardCharsets.UTF_8));
    }

    static byte[] preserveUserFields(byte[] generated, byte[]... existing) throws IOException {
        return KodiNfoPreservation.merge(generated, existing);
    }

    private static String originalLanguage(JSONObject movie) {
        JSONArray spoken = array(movie, "spoken_languages");
        for (int index = 0; index < spoken.length(); index++) {
            String code = text(spoken.optJSONObject(index), "iso_639_1").toLowerCase(Locale.ROOT);
            if (ISO_LANGUAGE_CODES.contains(code)) {
                return code;
            }
        }
        return text(movie, "original_language");
    }

    private static void writeIds(Xml xml, JSONObject movie) {
        JSONObject external = movie.optJSONObject("external_ids");
        int tmdb = movie.optInt("id");
        String imdb = first(text(external, "imdb_id"), text(movie, "imdb_id"));
        String tvdb = text(external, "tvdb_id");
        String wikidata = text(external, "wikidata_id");
        boolean tvdbDefault = "tvdb".equals(text(movie, "metadata_source")) && !tvdb.isEmpty();
        if (tmdb > 0) {
            xml.tag("uniqueid", Integer.toString(tmdb), "type", "tmdb", "default", Boolean.toString(!tvdbDefault));
        }
        xml.tag("uniqueid", imdb, "type", "imdb", "default", Boolean.toString(!tvdbDefault && tmdb <= 0));
        xml.tag("uniqueid", tvdb, "type", "tvdb", "default",
                Boolean.toString(tvdbDefault || (tmdb <= 0 && imdb.isEmpty())));
        xml.tag("uniqueid", wikidata, "type", "wikidata", "default",
                Boolean.toString(tmdb <= 0 && imdb.isEmpty() && tvdb.isEmpty()));
    }

    private static void writeRating(Xml xml, JSONObject movie) {
        double value = movie.optDouble("vote_average", -1);
        long votes = movie.optLong("vote_count");
        if (votes <= 0 || !(value >= 0 && value <= 10)) {
            return;
        }
        xml.open("ratings");
        xml.open("rating", "name", "themoviedb", "max", "10", "default", "true");
        xml.tag("value", Double.toString(value));
        xml.tag("votes", Long.toString(votes));
        xml.close("rating");
        xml.close("ratings");
    }

    private static void writeCollection(Xml xml, JSONObject movie) {
        JSONObject details = movie.optJSONObject("collection_details");
        JSONObject belongs = movie.optJSONObject("belongs_to_collection");
        String name = collectionName(movie);
        if (!name.isEmpty()) {
            xml.open("set");
            xml.tag("name", name);
            xml.tag("overview", first(text(details, "overview"), text(belongs, "overview")));
            xml.close("set");
        }
    }

    static String collectionName(JSONObject movie) {
        return first(text(movie.optJSONObject("collection_details"), "name"),
                text(movie.optJSONObject("belongs_to_collection"), "name"));
    }

    private static void writeNames(Xml xml, String tag, JSONArray entries) {
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < entries.length(); index++) {
            names.add(text(entries.optJSONObject(index), "name"));
        }
        for (String name : names) {
            xml.tag(tag, name);
        }
    }

    private static void writeCountryCodes(Xml xml, JSONArray codes, Locale locale) {
        if (codes == null) {
            return;
        }
        Set<String> countries = new LinkedHashSet<>();
        for (int index = 0; index < codes.length(); index++) {
            String code = codes.optString(index).toUpperCase(Locale.ROOT);
            if (code.matches("[A-Z]{2}")) {
                countries.add(Locale.forLanguageTag("und-" + code).getDisplayCountry(locale));
            }
        }
        for (String country : countries) {
            xml.tag("country", country);
        }
    }

    private static void writeCrew(Xml xml, JSONArray crew) {
        Set<String> directors = new LinkedHashSet<>();
        Set<String> writers = new LinkedHashSet<>();
        for (int index = 0; index < crew.length(); index++) {
            JSONObject person = crew.optJSONObject(index);
            String name = personName(person);
            String job = text(person, "job");
            if ("Director".equals(job)) {
                directors.add(name);
            }
            if ("Writing".equals(text(person, "department")) || WRITER_JOBS.contains(job)) {
                writers.add(name);
            }
        }
        for (String director : directors) {
            xml.tag("director", director);
        }
        for (String writer : writers) {
            xml.tag("credits", writer);
        }
    }

    private static void writeCast(Xml xml, JSONArray cast, Map<Integer, String> actorThumbnails) {
        for (int index = 0; index < cast.length(); index++) {
            JSONObject person = cast.optJSONObject(index);
            String name = personName(person);
            if (name.isEmpty()) {
                continue;
            }
            String suppliedThumb = actorThumbnails == null
                    ? "" : httpsUrl(actorThumbnails.get(person.optInt("id")));
            int order = person.optInt("order", index);
            xml.open("actor");
            xml.tag("name", name);
            xml.tag("role", text(person, "character"));
            xml.tag("order", Integer.toString(order >= 0 ? order : index));
            xml.tag("thumb", first(suppliedThumb, imageUrl("w185", text(person, "profile_path"))));
            xml.close("actor");
        }
    }

    static String personName(JSONObject person) {
        return first(text(person, "name"), text(person, "original_name"));
    }

    private static void writeArtwork(Xml xml, JSONObject movie, Map<String, String> artwork) {
        JSONObject images = movie.optJSONObject("images");
        JSONArray posters = array(images, "posters");
        JSONArray backdrops = array(images, "backdrops");
        JSONArray logos = array(images, "logos");

        Set<String> posterUrls = new LinkedHashSet<>();
        posterUrls.add(artReference(artwork, "poster"));
        posterUrls.add(imageUrl("w780", text(movie, "poster_path")));
        addImageVariants(posterUrls, posters, "w780", ImageType.ANY, 5);
        writeThumbs(xml, "poster", posterUrls);

        Set<String> fanartUrls = new LinkedHashSet<>();
        fanartUrls.add(httpsUrl(artReference(artwork, "fanart")));
        boolean hasTextless = addImageVariants(fanartUrls, backdrops, "w1280", ImageType.TEXTLESS, 5);
        if (!hasTextless) {
            fanartUrls.add(imageUrl("w1280", text(movie, "backdrop_path")));
            addImageVariants(fanartUrls, backdrops, "w1280", ImageType.ANY, 5);
        }
        fanartUrls.remove("");
        if (!fanartUrls.isEmpty()) {
            xml.open("fanart");
            for (String url : fanartUrls) {
                xml.tag("thumb", url);
            }
            xml.close("fanart");
        }

        for (String aspect : OPTIONAL_ART) {
            Set<String> urls = new LinkedHashSet<>();
            urls.add(artReference(artwork, aspect));
            switch (aspect) {
                case "landscape":
                    addImageVariants(urls, backdrops, "w1280", ImageType.WITH_LANGUAGE, 4);
                    break;
                case "keyart":
                    addImageVariants(urls, posters, "w780", ImageType.TEXTLESS, 4);
                    break;
                case "clearlogo":
                    addImageVariants(urls, logos, "original", ImageType.PNG, 4);
                    break;
                default:
                    break;
            }
            writeThumbs(xml, aspect, urls);
        }
    }

    private enum ImageType {
        ANY, TEXTLESS, WITH_LANGUAGE, PNG
    }

    private static boolean addImageVariants(
            Set<String> urls, JSONArray images, String size, ImageType type, int limit
    ) {
        int found = 0;
        for (int index = 0; index < images.length() && found < limit; index++) {
            JSONObject image = images.optJSONObject(index);
            String path = text(image, "file_path");
            String language = text(image, "iso_639_1");
            boolean textless = language.isEmpty() || "null".equalsIgnoreCase(language);
            if ((type == ImageType.TEXTLESS && !textless)
                    || (type == ImageType.WITH_LANGUAGE && textless)
                    || (type == ImageType.PNG && !path.toLowerCase(Locale.ROOT).endsWith(".png"))) {
                continue;
            }
            String url = imageUrl(size, path);
            if (!url.isEmpty() && urls.add(url)) {
                found++;
            }
        }
        return found > 0;
    }

    private static void writeThumbs(Xml xml, String aspect, Set<String> urls) {
        for (String url : urls) {
            xml.tag("thumb", url, "aspect", aspect);
        }
    }

    private static String artReference(Map<String, String> artwork, String aspect) {
        String reference = artwork == null ? null : artwork.get(aspect);
        if (reference == null) {
            return "";
        }
        if (reference.contains("/") || reference.indexOf('\\') >= 0) {
            return httpsUrl(reference);
        }
        return reference;
    }

    private static String httpsUrl(String value) {
        return value != null && value.startsWith("https://") ? value : "";
    }

    private static String imageUrl(String size, String path) {
        return IMAGE_PATH.matcher(path).matches() ? TMDB_IMAGE_BASE + size + path : TvdbMovie.imageUrl(path);
    }

    private static String premiere(JSONObject movie) {
        String date = dateOnly(text(movie, "release_date"));
        if (!date.isEmpty()) {
            return date;
        }

        JSONArray countries = array(movie.optJSONObject("release_dates"), "results");
        for (int index = 0; index < countries.length(); index++) {
            JSONArray releases = array(countries.optJSONObject(index), "release_dates");
            for (int releaseIndex = 0; releaseIndex < releases.length(); releaseIndex++) {
                JSONObject release = releases.optJSONObject(releaseIndex);
                String candidate = dateOnly(text(release, "release_date"));
                if (!candidate.isEmpty() && (date.isEmpty() || candidate.compareTo(date) < 0)) {
                    date = candidate;
                }
            }
        }
        return date;
    }

    private static String dateOnly(String date) {
        if (date.length() < 10) {
            return "";
        }
        String datePart = date.substring(0, 10);
        if (!PREMIERE_DATE.matcher(datePart).matches() || datePart.startsWith("0000")) {
            return "";
        }
        try {
            return LocalDate.parse(datePart).toString();
        } catch (DateTimeParseException invalidDate) {
            return "";
        }
    }

    private static String certification(JSONObject movie, String country) {
        JSONArray countries = array(movie.optJSONObject("release_dates"), "results");
        String regional = certificationFor(countries, country);
        if (!regional.isEmpty() || "US".equals(country)) {
            return regional;
        }
        String mpaa = certificationFor(countries, "US");
        return mpaa.isEmpty() ? "" : "US:" + mpaa;
    }

    private static String certificationFor(JSONArray countries, String country) {
        String selected = "";
        int priority = 0;
        for (int index = 0; index < countries.length(); index++) {
            JSONObject releasesByCountry = countries.optJSONObject(index);
            if (!country.equalsIgnoreCase(text(releasesByCountry, "iso_3166_1"))) {
                continue;
            }
            JSONArray releases = array(releasesByCountry, "release_dates");
            for (int releaseIndex = 0; releaseIndex < releases.length(); releaseIndex++) {
                JSONObject release = releases.optJSONObject(releaseIndex);
                if (release == null) {
                    continue;
                }
                String rating = text(release, "certification");
                if (rating.isEmpty()) {
                    continue;
                }
                int releaseType = release.optInt("type");
                int score = releaseType == 3 ? 3 : releaseType == 2 ? 2 : 1;
                if (score > priority) {
                    priority = score;
                    selected = rating;
                }
            }
        }
        return selected;
    }

    private static String trailer(JSONObject movie, String language, String country) {
        JSONArray videos = array(movie.optJSONObject("videos"), "results");
        String best = "";
        int bestScore = 0;
        for (int index = 0; index < videos.length(); index++) {
            JSONObject video = videos.optJSONObject(index);
            String key = text(video, "key");
            String type = text(video, "type");
            if (!"YouTube".equalsIgnoreCase(text(video, "site")) || !YOUTUBE_ID.matcher(key).matches()
                    || !("Trailer".equals(type) || "Teaser".equals(type))) {
                continue;
            }
            int score = "Trailer".equals(type) ? 100 : 10;
            if (language.equalsIgnoreCase(text(video, "iso_639_1"))) {
                score += 8;
            }
            if (video.optBoolean("official")) {
                score += 5;
            }
            if (country.equalsIgnoreCase(text(video, "iso_3166_1"))) {
                score += 3;
            }
            if (score > bestScore) {
                best = key;
                bestScore = score;
            }
        }
        return best.isEmpty() ? "" : "plugin://plugin.video.youtube/play/?video_id=" + best;
    }

    private static JSONArray array(JSONObject object, String key) {
        JSONArray result = object == null ? null : object.optJSONArray(key);
        return result == null ? new JSONArray() : result;
    }

    private static String text(JSONObject object, String key) {
        return object == null || object.isNull(key) ? "" : object.optString(key, "").trim();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static final class Xml {
        private final StringBuilder contents = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        private int depth;

        void open(String name, String... attributes) {
            start(name, attributes);
            contents.append('\n');
            depth++;
        }

        void close(String name) {
            depth--;
            indent();
            end(name);
        }

        void tag(String name, String value, String... attributes) {
            if (value.isEmpty()) {
                return;
            }
            start(name, attributes);
            escape(value);
            end(name);
        }

        private void start(String name, String[] attributes) {
            indent();
            contents.append('<').append(name);
            for (int index = 0; index < attributes.length; index += 2) {
                contents.append(' ').append(attributes[index]).append("=\"");
                escape(attributes[index + 1]);
                contents.append('"');
            }
            contents.append('>');
        }

        private void end(String name) {
            contents.append("</").append(name).append(">\n");
        }

        private void indent() {
            for (int index = 0; index < depth; index++) {
                contents.append("  ");
            }
        }

        private void escape(String value) {
            for (int offset = 0; offset < value.length();) {
                int character = value.codePointAt(offset);
                offset += Character.charCount(character);
                switch (character) {
                    case '&': contents.append("&amp;"); break;
                    case '<': contents.append("&lt;"); break;
                    case '>': contents.append("&gt;"); break;
                    case '"': contents.append("&quot;"); break;
                    case '\'': contents.append("&apos;"); break;
                    default:
                        if (character == '\t' || character == '\n' || character == '\r'
                                || (character >= 0x20 && character <= 0xD7FF)
                                || (character >= 0xE000 && character <= 0xFFFD)
                                || (character >= 0x10000 && character <= 0x10FFFF)) {
                            contents.appendCodePoint(character);
                        }
                        break;
                }
            }
        }
    }
}
