package org.kodiscraper.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ImdbMovieFeed {
    private static final String HOST = "www.imdb.com";
    static final String IMAGE_HOST = "m.media-amazon.com";
    static final String IMDB_IMAGE_HOST = "ia.media-imdb.com";
    private static final Pattern IMDB_ID = Pattern.compile("tt[0-9]{5,12}");
    private static final Pattern SEARCH_DATA = Pattern.compile(
            "(?is)<script\\b(?=[^>]*\\bid\\s*=\\s*['\"]__NEXT_DATA__['\"])[^>]*>(.*?)</script\\s*>");
    private static final Pattern LINKED_DATA = Pattern.compile(
            "(?is)<script\\b(?=[^>]*\\btype\\s*=\\s*['\"]application/ld\\+json['\"])[^>]*>(.*?)</script\\s*>");
    private static final int MAX_PAGE_BYTES = 6 * 1024 * 1024;
    private static final int MAX_MOVIES = 50;
    static final double HIGH_RATING = 7.0;

    record Movie(String title, String originalTitle, String imdbId, LocalDate releaseDate, int year,
                 double rating, int votes, String posterUrl, String overview, List<String> genres) {
        String releaseLabel() {
            if (releaseDate != null) return releaseDate.toString();
            return year > 0 ? Integer.toString(year) : "";
        }
    }

    record Result(List<Movie> movies, boolean highRatedComplete) {}

    Result load(LocalDate today) throws IOException {
        LocalDate from = today.minusDays(29);
        String search = "https://" + HOST + "/search/title/?title_type=feature&release_date="
                + from + "," + today + "&count=" + MAX_MOVIES;
        List<Movie> popular = fetch("popular", search + "&sort=num_votes,desc", from, today);
        Map<String, Movie> movies = new LinkedHashMap<>();
        for (Movie movie : popular) movies.put(movie.imdbId(), movie);
        try {
            List<Movie> rated = fetch("high_rated",
                    search + "&user_rating=" + HIGH_RATING + ",10&num_votes=10,&sort=user_rating,desc",
                    from, today);
            for (Movie movie : rated) movies.putIfAbsent(movie.imdbId(), movie);
        } catch (IOException failure) {
            ScrapeEngine.checkCancelled();
            if (movies.isEmpty()) throw failure;
            return new Result(new ArrayList<>(movies.values()), false);
        }
        return new Result(new ArrayList<>(movies.values()), true);
    }

    private static List<Movie> fetch(String query, String address, LocalDate from, LocalDate today)
            throws IOException {
        String log = "host=" + HOST + " query=" + query;
        byte[] page;
        try {
            page = SubtitleHttp.getOk(address, "Accept-Language", "en-US,en", MAX_PAGE_BYTES, HOST);
        } catch (SubtitleHttp.HttpException failure) {
            DebugLog.event("imdb-feed", log + " result=http_status status=" + failure.statusCode);
            String message = switch (failure.statusCode) {
                case 202 -> "IMDb 返回 HTTP 202，未提供影片列表。";
                case 403 -> "IMDb 拒绝访问搜索页（HTTP 403）。";
                case 429 -> "IMDb 限制了请求频率（HTTP 429）。";
                default -> "IMDb 搜索页返回 HTTP " + failure.statusCode + "。";
            };
            throw new IOException(message, failure);
        } catch (IOException failure) {
            ScrapeEngine.checkCancelled();
            String reason = DebugLog.networkReason(failure);
            DebugLog.event("imdb-feed", log + " result=request_failed reason=" + reason);
            String message = switch (reason) {
                case "dns_failure" -> "无法解析 IMDb 搜索页的域名。";
                case "connection_reset" -> "IMDb 搜索页连接被重置。";
                case "timeout" -> "连接 IMDb 搜索页超时。";
                case "tls_peer_unverified" -> "IMDb 搜索页的 TLS 证书校验失败。";
                default -> "读取 IMDb 搜索页时连接失败。";
            };
            throw new IOException(message, failure);
        }
        try {
            List<Movie> movies = parse(new String(page, StandardCharsets.UTF_8), from, today);
            DebugLog.event("imdb-feed", log + " result=parsed count=" + movies.size());
            return movies;
        } catch (IOException failure) {
            ScrapeEngine.checkCancelled();
            DebugLog.event("imdb-feed", log + " result=unrecognized_content status=200 bytes=" + page.length);
            throw failure;
        }
    }

    static List<Movie> parse(String html, LocalDate from, LocalDate through) throws IOException {
        Map<String, Movie> results = new LinkedHashMap<>();
        Matcher scripts = SEARCH_DATA.matcher(html);
        boolean recognized = false;
        if (scripts.find()) {
            try {
                JSONObject data = new JSONObject(scripts.group(1));
                JSONObject group = object(data, "props", "pageProps", "searchResults", "titleResults");
                JSONArray items = group == null ? null : group.optJSONArray("titleListItems");
                if (items != null) {
                    recognized = true;
                    for (int index = 0; index < items.length() && results.size() < MAX_MOVIES; index++) {
                        ScrapeEngine.checkCancelled();
                        JSONObject item = items.optJSONObject(index);
                        if (item != null) {
                            JSONObject node = item.optJSONObject("node");
                            add(results, searchMovie(node == null ? item : node), from, through);
                        }
                    }
                }
            } catch (JSONException invalid) {
                DebugLog.failure("imdb-search", invalid);
            }
        }
        if (results.isEmpty()) {
            Matcher linked = LINKED_DATA.matcher(html);
            while (linked.find() && results.size() < MAX_MOVIES) {
                try {
                    JSONObject data = new JSONObject(linked.group(1));
                    JSONArray items = data.optJSONArray("itemListElement");
                    if (!"ItemList".equals(data.optString("@type")) || items == null) continue;
                    for (int index = 0; index < items.length() && results.size() < MAX_MOVIES; index++) {
                        ScrapeEngine.checkCancelled();
                        JSONObject movie = object(items.optJSONObject(index), "item");
                        if (movie != null) add(results, linkedMovie(movie), from, through);
                    }
                    recognized |= items.length() == 0 || !results.isEmpty();
                } catch (JSONException invalid) {
                    DebugLog.failure("imdb-search", invalid);
                }
            }
        }
        if (!recognized) throw new IOException("IMDb 返回的页面没有可识别的影片列表。");
        return new ArrayList<>(results.values());
    }

    private static Movie searchMovie(JSONObject node) {
        JSONObject ratings = object(node, "ratingsSummary");
        JSONObject release = object(node, "releaseDate");
        JSONObject releaseYear = object(node, "releaseYear");
        LocalDate date = null;
        if (release != null) {
            try {
                date = LocalDate.of(release.optInt("year"), release.optInt("month"), release.optInt("day"));
            } catch (DateTimeException incomplete) {
                // IMDb can give the year alone. The date range is already applied by the search page.
            }
        }
        JSONObject genres = object(node, "titleGenres");
        if (genres == null) genres = object(node, "genres");
        int year = date != null ? date.getYear() : releaseYear == null ? 0 : releaseYear.optInt("year");
        return new Movie(string(object(node, "titleText"), "text", 240),
                string(object(node, "originalTitleText"), "text", 240), string(node, "id", 20), date,
                year,
                ratings == null ? 0 : ratings.optDouble("aggregateRating", 0),
                ratings == null ? 0 : ratings.optInt("voteCount"),
                posterUrl(string(object(node, "primaryImage"), "url", 1500)),
                string(object(node, "plot", "plotText"), "plainText", 1800),
                genres(genres == null ? null : genres.optJSONArray("genres")));
    }

    private static Movie linkedMovie(JSONObject item) {
        if (!"Movie".equals(item.optString("@type"))) return null;
        Matcher id = IMDB_ID.matcher(item.optString("url"));
        if (!id.find()) return null;
        JSONObject rating = item.optJSONObject("aggregateRating");
        LocalDate date = null;
        try {
            date = LocalDate.parse(item.optString("datePublished"));
        } catch (DateTimeException incomplete) {
            // Structured data sometimes omits a release date.
        }
        JSONObject image = item.optJSONObject("image");
        return new Movie(string(item, "name", 240), string(item, "alternateName", 240), id.group(),
                date, date == null ? 0 : date.getYear(), rating == null ? 0 : rating.optDouble("ratingValue", 0),
                rating == null ? 0 : rating.optInt("ratingCount"),
                posterUrl(image == null ? string(item, "image", 1500) : string(image, "url", 1500)),
                string(item, "description", 1800), genres(item.optJSONArray("genre")));
    }

    private static void add(Map<String, Movie> results, Movie movie, LocalDate from, LocalDate through) {
        if (movie == null || !IMDB_ID.matcher(movie.imdbId()).matches() || movie.title().isEmpty()
                || !(movie.rating() >= 1 && movie.rating() <= 10) || movie.votes() <= 0) return;
        LocalDate date = movie.releaseDate();
        if (date != null && (date.isBefore(from) || date.isAfter(through))) return;
        int year = movie.year();
        if (date == null && year > 0 && (year < from.getYear() || year > through.getYear())) return;
        results.putIfAbsent(movie.imdbId(), movie);
    }

    private static JSONObject object(JSONObject base, String... keys) {
        for (String key : keys) {
            if (base == null) return null;
            base = base.optJSONObject(key);
        }
        return base;
    }

    private static String string(JSONObject object, String key, int limit) {
        if (object == null) return "";
        Object value = object.opt(key);
        return value instanceof String ? clean((String) value, limit) : "";
    }

    private static String clean(String value, int limit) {
        String text = value.trim();
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private static List<String> genres(JSONArray values) {
        if (values == null) return Collections.emptyList();
        Set<String> genres = new LinkedHashSet<>();
        for (int index = 0; index < values.length() && genres.size() < 8; index++) {
            Object value = values.opt(index);
            String genre = value instanceof String ? clean((String) value, 60)
                    : string(object(values.optJSONObject(index), "genre"), "text", 60);
            if (genre.isEmpty()) genre = string(values.optJSONObject(index), "text", 60);
            if (!genre.isEmpty()) genres.add(genre);
        }
        return new ArrayList<>(genres);
    }

    static String posterUrl(String address) {
        try {
            URI uri = new URI(address);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || !(IMAGE_HOST.equalsIgnoreCase(uri.getHost()) || IMDB_IMAGE_HOST.equalsIgnoreCase(uri.getHost()))
                    || uri.getPath() == null || !uri.getPath().startsWith("/images/")) return "";
            return uri.toASCIIString().replaceFirst("(?i)\\._V1_\\.jpg$", "._V1_SX360_.jpg");
        } catch (URISyntaxException invalid) {
            return "";
        }
    }
}
