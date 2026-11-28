package org.kodiscraper.app;

import android.icu.text.Transliterator;
import android.icu.util.ULocale;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

public final class TmdbClient implements MovieMetadataSource {
    private static final String API_BASE = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/";
    private static final String DIAGNOSTIC_IMAGE = IMAGE_BASE + "w92/1E5baAaEse26fej7uHcjOgEE2t2.jpg";
    private static final String ENGLISH = "en-US";
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_PROBE_BYTES = 256 * 1024;
    private static final int PROBE_TIMEOUT_MILLIS = 6000;
    private static final int MAX_RESULTS = 14;
    private static final int MAX_ALIAS_LOOKUPS = 4;
    private static final int MAX_SHORT_TITLE_PAGES = 3;
    private static final int DISCOVER_PAGE_SIZE = 20;
    private static final int RECENT_DETAIL_CONCURRENCY = 4;
    private static final int MAX_MOVIE_TITLE_LENGTH = 220;
    private static final int MAX_OVERVIEW_LENGTH = 1200;
    private static final int MAX_GENRES = 8;
    private static final int MAX_GENRE_LENGTH = 48;
    private static final Pattern IMAGE_PATH = Pattern.compile(
            "/[A-Za-z0-9_-]+\\.(jpe?g|png|webp|gif)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMDB_ID = Pattern.compile("tt[0-9]{5,12}");
    private static final Pattern YOUTUBE_ID = Pattern.compile("[a-zA-Z0-9_-]{11}");
    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]+=*");
    private static final String[] MOVIE_ART_GROUPS = {"posters", "backdrops", "logos"};

    private final String apiKey;
    private final String bearerToken;
    private final String language;
    private final String languageCode;
    private final Map<Integer, JSONObject> collections = new HashMap<>();
    private final Set<HttpURLConnection> activeRequests = new HashSet<>();
    private volatile boolean cancelled;
    private boolean configurationLoaded;
    private JSONObject imageConfiguration;

    public TmdbClient(String credential, String language) {
        String token = credential == null ? "" : credential.trim();
        boolean explicitBearer = token.regionMatches(true, 0, "Bearer ", 0, 7);
        if (explicitBearer) token = token.substring(7).trim();
        boolean isApiKey = !explicitBearer && token.matches("[A-Za-z0-9]{32}");
        this.apiKey = isApiKey ? token : "";
        this.bearerToken = isApiKey ? "" : token;
        String locale = language == null ? "" : language.trim().replace('_', '-');
        this.language = locale.matches("[A-Za-z]{2,3}(-[A-Za-z]{2})?") ? locale : "zh-CN";
        this.languageCode = this.language.split("-", 2)[0].toLowerCase(Locale.ROOT);
    }

    public void validate() throws IOException {
        if (configuration() == null) throw new IOException("TMDB 未返回图片配置");
    }

    public Diagnostic diagnose() throws InterruptedIOException {
        String configurationUrl = API_BASE + "/configuration";
        Probe api = probe(configurationUrl, false, false);
        Probe imageCdn = probe(DIAGNOSTIC_IMAGE, false, true);
        Probe credentials;
        if (apiKey.isEmpty() && bearerToken.isEmpty()) {
            credentials = new Probe(ProbeState.NOT_PROVIDED, 0, 0);
        } else if (!validCredentialFormat()) {
            credentials = new Probe(ProbeState.INVALID_FORMAT, 0, 0);
        } else {
            String address = apiKey.isEmpty() ? configurationUrl : configurationUrl + "?api_key=" + apiKey;
            credentials = probe(address, true, false);
        }
        checkRunning();
        return new Diagnostic(api, imageCdn, credentials);
    }

    public void cancel() {
        List<HttpURLConnection> connections;
        synchronized (activeRequests) {
            cancelled = true;
            connections = new ArrayList<>(activeRequests);
        }
        for (HttpURLConnection connection : connections) connection.disconnect();
    }

    public int findMovieByImdb(String imdbId) throws IOException {
        String id = imdbId == null ? "" : imdbId.trim().toLowerCase(Locale.ROOT);
        if (!IMDB_ID.matcher(id).matches()) return 0;
        JSONObject response = json("/find/" + id, "external_source", "imdb_id",
                "language", language);
        JSONArray movies = response.optJSONArray("movie_results");
        JSONObject movie = movies == null ? null : movies.optJSONObject(0);
        return movie == null ? 0 : movie.optInt("id");
    }

    public List<Candidate> search(String title, int year) throws IOException {
        String query = title == null ? "" : title.trim();
        String titleKey = normalizedTitle(query);
        Map<Integer, SearchHit> hits = new LinkedHashMap<>();
        if (titleKey.isEmpty()) return new ArrayList<>();
        if (query.length() > 250) throw new IOException("电影名称过长");

        collect(hits, searchResponse(query, year, language));
        List<SearchHit> ranked = rank(hits, titleKey, year);
        if (!confident(ranked, year, titleKey)) {
            if (year > 0) collect(hits, searchResponse(query, 0, language));

            String simplified = simplifyChinese(query);
            if (!query.equals(simplified) && !confident(rank(hits, titleKey, year), year, titleKey)) {
                collect(hits, searchResponse(simplified, year, language));
            }
            if (!language.equalsIgnoreCase(ENGLISH)
                    && !confident(rank(hits, titleKey, year), year, titleKey)) {
                collect(hits, searchResponse(query, 0, ENGLISH));
            }
            ranked = rank(hits, titleKey, year);
        }

        if (!confident(ranked, year, titleKey)) {
            int lookedUp = 0;
            for (SearchHit hit : ranked) {
                if (lookedUp == MAX_ALIAS_LOOKUPS) break;
                if (hit.exact || (year > 0 && hit.year > 0 && Math.abs(year - hit.year) > 1)) {
                    continue;
                }
                lookedUp++;
                JSONObject response = optionalJson("/movie/" + hit.id + "/alternative_titles");
                JSONArray titles = response == null ? null : response.optJSONArray("titles");
                if (titles == null) continue;
                for (int i = 0; i < titles.length(); i++) {
                    JSONObject alternative = titles.optJSONObject(i);
                    if (alternative != null) hit.addTitle(string(alternative, "title"));
                }
            }
            ranked = rank(hits, titleKey, year);
        }

        boolean firstIsConfident = confident(ranked, year, titleKey);
        if (!firstIsConfident && year > 0) {
            String shortened = MovieStorage.firstPartBaseTitle(query);
            if (!shortened.isEmpty()) {
                List<SearchHit> shortenedHits = new ArrayList<>();
                SearchHit shortenedMatch = searchShortenedTitle(shortened, year, shortenedHits);
                for (SearchHit hit : ranked) {
                    if (hit.exact && (hit.year == 0 || Math.abs(year - hit.year) <= 1)) {
                        shortenedMatch = null;
                        break;
                    }
                }
                ranked = mergeSearchResults(ranked, shortenedHits, shortenedMatch);
                firstIsConfident = shortenedMatch != null;
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_RESULTS, ranked.size()); i++) {
            candidates.add(new Candidate(ranked.get(i), i == 0 && firstIsConfident));
        }
        return candidates;
    }

    public List<RecentMovie> recentMovies(LocalDate from, LocalDate through) throws IOException {
        checkRunning();
        if (from == null || through == null || from.isAfter(through)) {
            throw new IOException("新片上映日期范围无效");
        }
        JSONArray popular = discoverRecent(from, through, false);
        JSONArray scored = discoverRecent(from, through, true);
        Map<Integer, JSONObject> seeds = new LinkedHashMap<>();
        for (int i = 0; i < DISCOVER_PAGE_SIZE; i++) {
            collectRecentSeed(seeds, popular, i, from, through);
            collectRecentSeed(seeds, scored, i, from, through);
        }
        return resolveRecentMovies(new ArrayList<>(seeds.values()), from, through);
    }

    private JSONArray discoverRecent(LocalDate from, LocalDate through, boolean byScore) throws IOException {
        return json("/discover/movie", "language", language,
                "primary_release_date.gte", from.toString(), "primary_release_date.lte", through.toString(),
                "include_adult", "false", "include_video", "false", "page", "1",
                "sort_by", byScore ? "vote_average.desc" : "popularity.desc",
                "vote_count.gte", byScore ? "10" : "0").optJSONArray("results");
    }

    private static void collectRecentSeed(Map<Integer, JSONObject> seeds, JSONArray entries, int index,
                                          LocalDate from, LocalDate through) {
        JSONObject movie = entries == null ? null : entries.optJSONObject(index);
        if (movie == null || !isRecentMovie(movie) || releaseWithin(movie, from, through) == null) return;
        int id = movie.optInt("id");
        if (id > 0) seeds.putIfAbsent(id, movie);
    }

    private List<RecentMovie> resolveRecentMovies(List<JSONObject> seeds, LocalDate from,
                                                  LocalDate through) throws IOException {
        List<RecentMovie> result = new ArrayList<>();
        if (seeds.isEmpty()) return result;
        Set<String> imdbIds = new HashSet<>();
        IOException firstFailure = null;
        int concurrency = Math.min(RECENT_DETAIL_CONCURRENCY, seeds.size());
        ExecutorService workers = Executors.newFixedThreadPool(concurrency);
        try {
            for (int start = 0; start < seeds.size(); start += concurrency) {
                checkRunning();
                List<Callable<RecentMovie>> batch = new ArrayList<>();
                for (int i = start; i < Math.min(start + concurrency, seeds.size()); i++) {
                    JSONObject seed = seeds.get(i);
                    batch.add(() -> resolveRecentMovie(seed, from, through));
                }
                for (Future<RecentMovie> future : workers.invokeAll(batch)) {
                    try {
                        RecentMovie movie = future.get();
                        if (movie != null && imdbIds.add(movie.imdbId())) result.add(movie);
                    } catch (ExecutionException failure) {
                        checkRunning();
                        Throwable cause = failure.getCause();
                        if (cause instanceof IOException) {
                            IOException error = (IOException) cause;
                            if (error instanceof HttpException) {
                                int status = ((HttpException) error).statusCode;
                                if (status == 404) continue;
                                if (status == 401 || status == 403 || status == 429) throw error;
                            }
                            if (firstFailure == null) firstFailure = error;
                        } else {
                            throw new IOException("读取 TMDB 新片资料失败", cause);
                        }
                    }
                }
                if (firstFailure != null) break;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancel();
            throw new InterruptedIOException("已取消 TMDB 请求");
        } finally {
            workers.shutdownNow();
        }
        checkRunning();
        if (result.isEmpty() && firstFailure != null) throw firstFailure;
        return result;
    }

    private RecentMovie resolveRecentMovie(JSONObject seed, LocalDate from, LocalDate through) throws IOException {
        int id = seed.optInt("id");
        JSONObject details = json("/movie/" + id, "language", language);
        if (details.optInt("id") != id || !isRecentMovie(details)) return null;
        LocalDate date = releaseWithin(details, from, through);
        String imdbId = string(details, "imdb_id").toLowerCase(Locale.ROOT);
        if (date == null || !IMDB_ID.matcher(imdbId).matches()) return null;
        String originalTitle = movieText(details, seed, "original_title", MAX_MOVIE_TITLE_LENGTH);
        String title = movieText(details, seed, "title", MAX_MOVIE_TITLE_LENGTH);
        if (title.isEmpty()) title = originalTitle;
        if (title.isEmpty()) return null;
        String poster = string(details, "poster_path");
        if (!validImagePath(poster)) poster = string(seed, "poster_path");
        return new RecentMovie(title, originalTitle, imdbId, date, validImagePath(poster) ? poster : "",
                movieText(details, seed, "overview", MAX_OVERVIEW_LENGTH), recentGenres(details));
    }

    private static String movieText(JSONObject details, JSONObject seed, String field, int limit) {
        String value = movieText(string(details, field), limit);
        return value.isEmpty() ? movieText(string(seed, field), limit) : value;
    }

    private static String movieText(String source, int limit) {
        if (source == null || source.isEmpty()) return "";
        StringBuilder value = new StringBuilder(Math.min(source.length(), limit));
        boolean separator = false;
        int added = 0;
        for (int offset = 0; offset < source.length() && added < limit; ) {
            int character = source.codePointAt(offset);
            offset += Character.charCount(character);
            if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
                separator = value.length() > 0;
                continue;
            }
            if (Character.isISOControl(character) || (character >= 0x202A && character <= 0x202E)
                    || (character >= 0x2066 && character <= 0x2069)) continue;
            if (separator) {
                if (added + 1 >= limit) break;
                value.append(' ');
                added++;
                separator = false;
            }
            value.appendCodePoint(character);
            added++;
        }
        return value.toString();
    }

    private static List<String> recentGenres(JSONObject details) {
        JSONArray entries = details.optJSONArray("genres");
        Set<String> names = new LinkedHashSet<>();
        if (entries != null) {
            for (int i = 0; i < entries.length() && names.size() < MAX_GENRES; i++) {
                String value = movieText(string(entries.optJSONObject(i), "name"), MAX_GENRE_LENGTH);
                if (!value.isEmpty()) names.add(value);
            }
        }
        return new ArrayList<>(names);
    }

    private static boolean isRecentMovie(JSONObject movie) {
        String mediaType = string(movie, "media_type");
        return !movie.optBoolean("adult") && !movie.optBoolean("video")
                && (mediaType.isEmpty() || mediaType.equals("movie"));
    }

    private static LocalDate releaseWithin(JSONObject movie, LocalDate from, LocalDate through) {
        String value = string(movie, "release_date");
        if (value.length() != 10) return null;
        try {
            LocalDate date = LocalDate.parse(value);
            return date.isBefore(from) || date.isAfter(through) ? null : date;
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    public static String moviePosterUrl(String posterPath) {
        return posterPath != null && validImagePath(posterPath) ? IMAGE_BASE + "w342" + posterPath : "";
    }

    public record RecentMovie(String title, String originalTitle, String imdbId,
                              LocalDate releaseDate, String posterPath, String overview, List<String> genres) {
        public RecentMovie {
            overview = overview == null ? "" : overview;
            genres = genres == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(genres));
        }

        public RecentMovie(String title, String originalTitle, String imdbId,
                           LocalDate releaseDate, String posterPath) {
            this(title, originalTitle, imdbId, releaseDate, posterPath, "", Collections.emptyList());
        }
    }

    public JSONObject details(int tmdbId) throws IOException {
        if (tmdbId <= 0) throw new IOException("无效的 TMDB 电影编号");
        String imageLanguages = languageCode.equals("en") ? "en,null" : languageCode + ",en,null";
        String moviePath = "/movie/" + tmdbId;
        JSONObject movie = json(moviePath, "language", language,
                "append_to_response", "external_ids,credits,images,videos,keywords,release_dates,"
                        + "alternative_titles,translations",
                "include_image_language", imageLanguages);

        fillMissingTranslations(movie);
        addTrailerFallback(movie, moviePath);
        addOriginalArtwork(movie, moviePath);

        JSONObject collection = movie.optJSONObject("belongs_to_collection");
        int collectionId = collection == null ? 0 : collection.optInt("id");
        if (collectionId > 0 && !collections.containsKey(collectionId)) {
            String path = "/collection/" + collectionId;
            JSONObject info = optionalJson(path, "language", language);
            if (info != null && string(info, "overview").isEmpty()
                    && !language.equalsIgnoreCase(ENGLISH)) {
                JSONObject fallback = optionalJson(path, "language", ENGLISH);
                if (fallback != null) put(info, "overview", string(fallback, "overview"));
            }
            collections.put(collectionId, info);
        }
        JSONObject info = collections.get(collectionId);
        if (info != null) put(movie, "collection_details", info);
        checkRunning();
        return movie;
    }

    public String artwork(JSONObject movie, String type) {
        String group;
        String primary = "";
        boolean textlessOnly = false;
        boolean textBearingOnly = false;
        switch (type) {
            case "poster":
                group = "posters";
                primary = "poster_path";
                break;
            case "fanart":
            case "backdrop":
                group = "backdrops";
                primary = "backdrop_path";
                break;
            case "clearlogo":
            case "logo":
                group = "logos";
                break;
            case "keyart":
                group = "posters";
                textlessOnly = true;
                break;
            case "landscape":
                group = "backdrops";
                textBearingOnly = true;
                break;
            default:
                return "";
        }

        JSONObject images = movie.optJSONObject("images");
        JSONArray candidates = images == null ? null : images.optJSONArray(group);
        String bestPath = "";
        double bestScore = Double.NEGATIVE_INFINITY;
        if (candidates != null) {
            String originalLanguage = originalLanguage(movie);
            String primaryPath = string(movie, primary);
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject image = candidates.optJSONObject(i);
                if (image == null) continue;
                String path = string(image, "file_path");
                if (!validImagePath(path)) continue;
                String code = imageLanguage(image);
                if ((textlessOnly && !code.isEmpty()) || (textBearingOnly && code.isEmpty())) {
                    continue;
                }
                int width = image.optInt("width");
                int height = image.optInt("height");
                if (width > 0 && height > 0
                        && ((textlessOnly && width >= height)
                            || (group.equals("backdrops") && width <= height))) {
                    continue;
                }

                double score = imageLanguageScore(code, originalLanguage,
                        group.equals("backdrops") && !textBearingOnly);
                score += artworkQuality(image, path, primaryPath);
                if (group.equals("logos") && path.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    score += 3;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestPath = path;
                }
            }
        }
        if (!bestPath.isEmpty()) return bestPath;
        String fallback = string(movie, primary);
        return validImagePath(fallback) ? fallback : "";
    }

    public List<String> alternateFanart(JSONObject movie, int limit) {
        List<String> paths = new ArrayList<>();
        JSONArray backdrops = array(movie.optJSONObject("images"), "backdrops");
        if (limit <= 0 || backdrops == null) return paths;

        String chosen = artwork(movie, "fanart");
        String primary = string(movie, "backdrop_path");
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < backdrops.length(); i++) {
            JSONObject image = backdrops.optJSONObject(i);
            String path = string(image, "file_path");
            if (!validImagePath(path) || path.equals(chosen) || !imageLanguage(image).isEmpty()) {
                continue;
            }
            int width = image.optInt("width");
            int height = image.optInt("height");
            if (width > 0 && height > 0 && width <= height) continue;
            double score = artworkQuality(image, path, primary);
            scores.merge(path, score, Math::max);
        }
        paths.addAll(scores.keySet());
        paths.sort(Comparator.comparingDouble(scores::get).reversed());
        if (paths.size() > limit) paths.subList(limit, paths.size()).clear();
        return paths;
    }

    public String collectionArtwork(JSONObject movie, String type) {
        String field;
        switch (type) {
            case "poster":
                field = "poster_path";
                break;
            case "fanart":
            case "backdrop":
                field = "backdrop_path";
                break;
            default:
                return "";
        }
        String path = string(movie.optJSONObject("collection_details"), field);
        if (validImagePath(path)) return path;
        path = string(movie.optJSONObject("belongs_to_collection"), field);
        return validImagePath(path) ? path : "";
    }

    public String castProfile(JSONObject castPerson) {
        String path = string(castPerson, "profile_path");
        return validImagePath(path) ? path : "";
    }

    public byte[] image(String path, String preferredSize) throws IOException {
        if (!validImagePath(path)) throw new IOException("TMDB 图片地址无效");
        JSONObject config = null;
        try {
            config = configuration();
        } catch (IOException error) {
            checkRunning();
        }
        String base = config == null ? "" : string(config, "secure_base_url");
        if (!base.equals(IMAGE_BASE)) base = IMAGE_BASE;
        String size = preferredSize == null ? "" : preferredSize;
        if (!size.matches("original|[wh][0-9]{2,4}")) size = "original";
        if (config != null && !configuredSize(config, size)) size = "original";
        byte[] data = download(base + size + path, false, MAX_IMAGE_BYTES);
        if (!isImage(data)) throw new IOException("TMDB 返回的图片格式无法识别");
        return data;
    }

    private JSONObject searchResponse(String query, int year, String locale) throws IOException {
        if (year > 0) {
            return json("/search/movie", "query", query, "primary_release_year",
                    Integer.toString(year), "language", locale);
        }
        return json("/search/movie", "query", query, "language", locale);
    }

    private SearchHit searchShortenedTitle(String title, int year, List<SearchHit> ranked)
            throws IOException {
        String titleKey = normalizedTitle(title);
        if (titleKey.isEmpty()) return null;
        Map<Integer, SearchHit> hits = new LinkedHashMap<>();
        int totalPages = 1;
        boolean complete = true;
        for (int page = 1; page <= Math.min(totalPages, MAX_SHORT_TITLE_PAGES); page++) {
            JSONObject response = optionalJson("/search/movie", "query", title,
                    "primary_release_year", Integer.toString(year), "language", language,
                    "page", Integer.toString(page));
            if (response == null) {
                complete = false;
                break;
            }
            if (page == 1) totalPages = response.optInt("total_pages", 1);
            collect(hits, response);
        }
        ranked.addAll(rank(hits, titleKey, year));
        if (!complete || totalPages > MAX_SHORT_TITLE_PAGES) return null;

        SearchHit match = null;
        for (SearchHit hit : ranked) {
            if (hit.year != year || !hit.exact) continue;
            if (match != null) return null;
            match = hit;
        }
        return match;
    }

    private static List<SearchHit> mergeSearchResults(List<SearchHit> full, List<SearchHit> shortened,
                                                       SearchHit preferred) {
        Map<Integer, SearchHit> combined = new LinkedHashMap<>();
        if (preferred != null) combined.put(preferred.id, preferred);
        for (int i = 0; i < Math.max(full.size(), shortened.size()) && combined.size() < MAX_RESULTS; i++) {
            if (i < full.size()) {
                SearchHit hit = full.get(i);
                combined.putIfAbsent(hit.id, hit);
            }
            if (i < shortened.size()) {
                SearchHit hit = shortened.get(i);
                combined.putIfAbsent(hit.id, hit);
            }
        }
        return new ArrayList<>(combined.values());
    }

    private static void collect(Map<Integer, SearchHit> hits, JSONObject response) {
        JSONArray entries = response.optJSONArray("results");
        if (entries == null) return;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            int id = entry.optInt("id");
            if (id <= 0) continue;
            SearchHit hit = hits.get(id);
            if (hit == null) {
                hit = new SearchHit(id, entry);
                hits.put(id, hit);
            }
            hit.addTitle(string(entry, "title"));
            hit.addTitle(string(entry, "original_title"));
        }
    }

    private static List<SearchHit> rank(Map<Integer, SearchHit> hits, String titleKey, int year) {
        List<SearchHit> ranked = new ArrayList<>(hits.values());
        for (SearchHit hit : ranked) {
            hit.exact = hit.titles.contains(titleKey);
            double score = hit.exact ? 100 : 0;
            if (!hit.exact) {
                for (String key : hit.titles) score = Math.max(score, similarity(titleKey, key));
            }
            if (year > 0) {
                if (hit.year == 0) {
                    score -= 10;
                } else {
                    int difference = Math.abs(year - hit.year);
                    if (difference == 0) score += 25;
                    else if (difference == 1) score += 4;
                    else score -= 35;
                }
            }
            hit.score = score;
        }
        ranked.sort(Comparator.comparingDouble((SearchHit hit) -> hit.score).reversed()
                .thenComparing(hit -> !hit.exact)
                .thenComparing(Comparator.comparingInt((SearchHit hit) -> hit.voteCount).reversed()));
        return ranked;
    }

    private static boolean confident(List<SearchHit> ranked, int year, String titleKey) {
        if (ranked.isEmpty()) return false;
        SearchHit first = ranked.get(0);
        if (!first.exact || (titleKey.length() < 2 && year <= 0)) return false;
        if (year > 0 && (first.year <= 0 || Math.abs(year - first.year) > 1)) return false;
        if (ranked.size() == 1) return true;
        SearchHit second = ranked.get(1);
        return first.score - second.score >= (year > 0 ? 10 : 12);
    }

    static String normalizedTitle(String title) {
        String text = Normalizer.normalize(simplifyChinese(title), Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int character = text.codePointAt(i);
            if (Character.isLetterOrDigit(character)) normalized.appendCodePoint(character);
            i += Character.charCount(character);
        }
        return normalized.toString();
    }

    private static String simplifyChinese(String text) {
        if (Build.VERSION.SDK_INT < 29) return text;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character >= '\u3400' && character <= '\u9fff') {
                return ChineseTransliterator.INSTANCE.transliterate(text);
            }
        }
        return text;
    }

    private static final class ChineseTransliterator {
        private static final Transliterator INSTANCE = Transliterator.getInstance("Traditional-Simplified");
    }

    private static double similarity(String first, String second) {
        int maxLength = Math.max(first.length(), second.length());
        if (maxLength == 0) return 0;
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];
        for (int j = 0; j < previous.length; j++) previous[j] = j;
        for (int i = 1; i <= first.length(); i++) {
            current[0] = i;
            for (int j = 1; j < current.length; j++) {
                int substitution = first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(previous[j - 1] + substitution,
                        Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        double score = 95.0 * (maxLength - previous[second.length()]) / maxLength;
        int minLength = Math.min(first.length(), second.length());
        if (minLength >= 3 && (first.contains(second) || second.contains(first))) {
            score = Math.max(score, 50 + 30.0 * minLength / maxLength);
        }
        return score;
    }

    private void fillMissingTranslations(JSONObject movie) {
        JSONObject entry = movie.optJSONObject("translations");
        JSONArray translations = entry == null ? null : entry.optJSONArray("translations");
        if (translations == null) return;
        String original = string(movie, "original_language");
        String[] locale = language.split("-", 2);
        String region = locale.length == 2 ? locale[1] : "";
        for (String field : new String[]{"title", "overview", "tagline", "runtime"}) {
            boolean runtime = field.equals("runtime");
            if (runtime ? movie.optInt(field) > 0 : !string(movie, field).isEmpty()) continue;
            Object best = null;
            int bestScore = 0;
            for (int i = 0; i < translations.length(); i++) {
                JSONObject translation = translations.optJSONObject(i);
                if (translation == null) continue;
                JSONObject data = translation.optJSONObject("data");
                if (data == null) continue;
                Object value;
                if (runtime) {
                    int length = data.optInt(field);
                    if (length <= 0) continue;
                    value = length;
                } else {
                    String text = string(data, field);
                    if (text.isEmpty()) continue;
                    value = text;
                }
                String code = string(translation, "iso_639_1");
                String country = string(translation, "iso_3166_1");
                int score = 0;
                if (code.equals(languageCode)) score = country.equalsIgnoreCase(region) ? 40 : 30;
                else if (code.equals("en")) score = country.equals("US") ? 22 : 20;
                else if (code.equals(original)) score = 10;
                if (score > bestScore) {
                    best = value;
                    bestScore = score;
                }
            }
            if (best != null) put(movie, field, best);
        }
        if (string(movie, "title").isEmpty()) put(movie, "title", string(movie, "original_title"));
    }

    private double imageLanguageScore(String code, String original, boolean preferTextless) {
        if (preferTextless && code.isEmpty()) return 100;
        if (code.equals(languageCode)) return 70;
        if (!code.isEmpty() && code.equals(original)) return 45;
        if (code.equals("en")) return 22;
        return code.isEmpty() ? 5 : 0;
    }

    private static double artworkQuality(JSONObject image, String path, String primaryPath) {
        int votes = Math.max(0, image.optInt("vote_count"));
        double rating = Math.max(0, image.optDouble("vote_average", 0)) * votes / (votes + 5.0);
        double width = Math.min(4, Math.max(0, image.optInt("width")) / 450.0);
        return rating + width + (path.equals(primaryPath) ? 5 : 0);
    }

    private static JSONArray array(JSONObject object, String key) {
        return object == null ? null : object.optJSONArray(key);
    }

    private static String imageLanguage(JSONObject image) {
        String code = string(image, "iso_639_1").toLowerCase(Locale.ROOT);
        return code.equals("null") ? "" : code;
    }

    private static String originalLanguage(JSONObject movie) {
        String code = string(movie, "original_language").toLowerCase(Locale.ROOT);
        return code.matches("[a-z]{2,3}") && !code.equals("xx") && !code.equals("und") ? code : "";
    }

    private void addOriginalArtwork(JSONObject movie, String moviePath) throws IOException {
        String original = originalLanguage(movie);
        if (original.isEmpty() || original.equals(languageCode) || original.equals("en")) return;
        JSONObject images = movie.optJSONObject("images");
        boolean needsOriginal = false;
        for (String group : MOVIE_ART_GROUPS) {
            JSONArray available = array(images, group);
            if (!hasArtworkLanguage(available, languageCode) && !hasArtworkLanguage(available, original)) {
                needsOriginal = true;
                break;
            }
        }
        if (!needsOriginal) return;

        JSONObject additional = optionalJson(moviePath + "/images", "language", originalVideoLocale(original),
                "include_image_language", original);
        if (additional == null) return;
        if (images == null) {
            images = new JSONObject();
            put(movie, "images", images);
        }
        for (String group : MOVIE_ART_GROUPS) {
            JSONArray extras = array(additional, group);
            if (extras == null || extras.length() == 0) continue;
            JSONArray existing = array(images, group);
            if (existing == null) {
                existing = new JSONArray();
                put(images, group, existing);
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < existing.length(); i++) {
                seen.add(string(existing.optJSONObject(i), "file_path"));
            }
            for (int i = 0; i < extras.length(); i++) {
                JSONObject image = extras.optJSONObject(i);
                String path = string(image, "file_path");
                if (validImagePath(path) && seen.add(path)) existing.put(image);
            }
        }
    }

    private static boolean hasArtworkLanguage(JSONArray images, String language) {
        if (images == null) return false;
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (language.equals(imageLanguage(image)) && validImagePath(string(image, "file_path"))) {
                return true;
            }
        }
        return false;
    }

    private void addTrailerFallback(JSONObject movie, String moviePath) throws IOException {
        JSONArray videos = array(movie.optJSONObject("videos"), "results");
        if (hasPlayableTrailer(videos)) return;
        String originalLocale = originalVideoLocale(originalLanguage(movie));
        if (!originalLocale.isEmpty() && !language.equalsIgnoreCase(originalLocale)) {
            JSONObject fallback = optionalJson(moviePath + "/videos", "language", originalLocale);
            if (appendPlayableVideos(movie, fallback)) return;
        }
        if (!language.equalsIgnoreCase(ENGLISH) && !originalLocale.equalsIgnoreCase(ENGLISH)) {
            appendPlayableVideos(movie, optionalJson(moviePath + "/videos", "language", ENGLISH));
        }
    }

    private static String originalVideoLocale(String code) {
        if (code.isEmpty()) return "";
        if (code.equals("en")) return ENGLISH;
        if (code.equals("cn")) return "cn-HK";
        String country = ULocale.addLikelySubtags(new ULocale(code)).getCountry();
        return country.isEmpty() ? code : code + "-" + country;
    }

    private static boolean appendPlayableVideos(JSONObject movie, JSONObject fallback) {
        JSONArray extras = array(fallback, "results");
        if (extras == null || extras.length() == 0) return false;
        JSONObject data = movie.optJSONObject("videos");
        JSONArray existing = array(data, "results");
        Set<String> keys = new HashSet<>();
        if (existing != null) {
            for (int i = 0; i < existing.length(); i++) {
                JSONObject video = existing.optJSONObject(i);
                String type = playableVideoType(video);
                if (!type.isEmpty()) keys.add(type + ":" + string(video, "key"));
            }
        }
        boolean hasTrailer = false;
        for (int i = 0; i < extras.length(); i++) {
            JSONObject video = extras.optJSONObject(i);
            String type = playableVideoType(video);
            if (type.isEmpty()) continue;
            if (keys.add(type + ":" + string(video, "key"))) {
                if (existing == null) {
                    existing = new JSONArray();
                    if (data == null) {
                        data = new JSONObject();
                        put(movie, "videos", data);
                    }
                    put(data, "results", existing);
                }
                existing.put(video);
            }
            if (type.equals("Trailer")) hasTrailer = true;
        }
        return hasTrailer;
    }

    private static boolean hasPlayableTrailer(JSONArray videos) {
        if (videos == null) return false;
        for (int i = 0; i < videos.length(); i++) {
            if (playableVideoType(videos.optJSONObject(i)).equals("Trailer")) return true;
        }
        return false;
    }

    private static String playableVideoType(JSONObject video) {
        if (!string(video, "site").equalsIgnoreCase("YouTube")
                || !YOUTUBE_ID.matcher(string(video, "key")).matches()) return "";
        String type = string(video, "type");
        return type.equals("Trailer") || type.equals("Teaser") ? type : "";
    }

    private JSONObject configuration() throws IOException {
        checkRunning();
        if (configurationLoaded) return imageConfiguration;
        JSONObject response = json("/configuration");
        imageConfiguration = response.optJSONObject("images");
        configurationLoaded = true;
        return imageConfiguration;
    }

    private static boolean configuredSize(JSONObject config, String size) {
        if (size.equals("original")) return true;
        for (String group : new String[]{"poster_sizes", "backdrop_sizes", "logo_sizes",
                "profile_sizes", "still_sizes"}) {
            JSONArray sizes = config.optJSONArray(group);
            if (sizes == null) continue;
            for (int i = 0; i < sizes.length(); i++) {
                if (size.equals(sizes.optString(i))) return true;
            }
        }
        return false;
    }

    private JSONObject optionalJson(String path, String... parameters) throws IOException {
        try {
            return json(path, parameters);
        } catch (IOException error) {
            checkRunning();
            return null;
        }
    }

    private JSONObject json(String path, String... parameters) throws IOException {
        if (apiKey.isEmpty() && bearerToken.isEmpty()) throw new IOException("请先填写 TMDB 凭据");
        if (!validCredentialFormat()) throw new IOException("TMDB 凭据格式无效");
        StringBuilder url = new StringBuilder(API_BASE).append(path);
        char separator = '?';
        for (int i = 0; i < parameters.length; i += 2) {
            url.append(separator).append(parameters[i]).append('=')
                    .append(URLEncoder.encode(parameters[i + 1], "UTF-8"));
            separator = '&';
        }
        if (!apiKey.isEmpty()) {
            url.append(separator).append("api_key=").append(URLEncoder.encode(apiKey, "UTF-8"));
        }
        byte[] bytes = download(url.toString(), true, MAX_JSON_BYTES);
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException error) {
            throw new IOException("TMDB 返回了无法读取的数据");
        }
    }

    private boolean validCredentialFormat() {
        return !apiKey.isEmpty() || (bearerToken.length() <= 8192
                && BEARER_TOKEN.matcher(bearerToken).matches());
    }

    private Probe probe(String address, boolean authenticated, boolean image)
            throws InterruptedIOException {
        long started = System.nanoTime();
        int status = 0;
        ProbeState state;
        HttpURLConnection connection = null;
        try {
            connection = openRequest(address, PROBE_TIMEOUT_MILLIS, PROBE_TIMEOUT_MILLIS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", image ? "image/jpeg" : "application/json");
            if (image) connection.setRequestProperty("Range", "bytes=0-63");
            if (authenticated && !bearerToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            status = Math.max(0, connection.getResponseCode());
            DebugLog.http(connection.getURL().getHost(), status, started);
            checkRunning();
            state = readProbeState(connection, status, authenticated, image);
        } catch (IOException error) {
            checkRunning();
            DebugLog.failure("tmdb-probe", error);
            state = probeFailure(error);
        } finally {
            if (connection != null) finishRequest(connection);
        }
        checkRunning();
        return new Probe(state, status, (System.nanoTime() - started) / 1_000_000);
    }

    private ProbeState readProbeState(HttpURLConnection connection, int status, boolean authenticated,
                                      boolean image) throws IOException {
        if (status == 429) return ProbeState.RATE_LIMITED;
        if (status == 0) return ProbeState.UNEXPECTED_RESPONSE;
        if (image) {
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                return ProbeState.HTTP_ERROR;
            }
            byte[] prefix = new byte[12];
            try (InputStream input = connection.getInputStream()) {
                int offset = 0;
                while (offset < prefix.length) {
                    int count = input.read(prefix, offset, prefix.length - offset);
                    checkRunning();
                    if (count < 0) return ProbeState.UNEXPECTED_RESPONSE;
                    offset += count;
                }
            }
            return isImage(prefix) ? ProbeState.REACHABLE : ProbeState.UNEXPECTED_RESPONSE;
        }
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return authenticated ? ProbeState.AUTH_REJECTED : ProbeState.REACHABLE;
        }
        if (authenticated && status == HttpURLConnection.HTTP_FORBIDDEN) {
            return ProbeState.AUTH_REJECTED;
        }
        if (status != HttpURLConnection.HTTP_OK) return ProbeState.HTTP_ERROR;
        try {
            byte[] body = readResponse(connection, MAX_PROBE_BYTES);
            JSONObject configuration = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (configuration.optJSONObject("images") == null) return ProbeState.UNEXPECTED_RESPONSE;
            return authenticated ? ProbeState.VALID : ProbeState.REACHABLE;
        } catch (JSONException | InvalidResponseException error) {
            return ProbeState.UNEXPECTED_RESPONSE;
        }
    }

    private static ProbeState probeFailure(IOException error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof UnknownHostException) return ProbeState.DNS_ERROR;
            if (cause instanceof SocketTimeoutException) return ProbeState.TIMEOUT;
            if (cause instanceof SSLException || cause instanceof java.security.cert.CertificateException) {
                return ProbeState.TLS_ERROR;
            }
        }
        return ProbeState.NETWORK_ERROR;
    }

    private byte[] download(String address, boolean api, int maxBytes) throws IOException {
        for (int attempt = 0; ; attempt++) {
            checkRunning();
            try {
                return request(address, api, maxBytes);
            } catch (HttpException error) {
                int code = error.statusCode;
                boolean retryable = code == 429 || (code >= 500 && code < 600);
                if (attempt > 0 || !retryable || error.retryDelayMillis > 4000) throw error;
                delay(error.retryDelayMillis);
            } catch (IOException error) {
                checkRunning();
                if (attempt > 0 || error instanceof InvalidResponseException) {
                    throw new IOException(api ? "无法连接 TMDB API，或响应无法读取"
                            : "无法下载 TMDB 图片，或图片过大");
                }
                delay(650);
            }
        }
    }

    private byte[] request(String address, boolean api, int maxBytes) throws IOException {
        long started = System.nanoTime();
        HttpURLConnection connection = openRequest(address, 10000, 14000);
        try {
            connection.setRequestProperty("Accept", api ? "application/json" : "image/*");
            if (api && !bearerToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            int status = connection.getResponseCode();
            DebugLog.http(connection.getURL().getHost(), status, started);
            checkRunning();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new HttpException(status, api, retryDelay(connection.getHeaderField("Retry-After")));
            }
            return readResponse(connection, maxBytes);
        } catch (IOException failure) {
            DebugLog.failure("http:" + connection.getURL().getHost(), failure);
            throw failure;
        } finally {
            finishRequest(connection);
        }
    }

    private HttpURLConnection openRequest(String address, int connectTimeout, int readTimeout)
            throws IOException {
        checkRunning();
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setInstanceFollowRedirects(false);
        synchronized (activeRequests) {
            checkRunning();
            activeRequests.add(connection);
        }
        return connection;
    }

    private void finishRequest(HttpURLConnection connection) {
        synchronized (activeRequests) {
            activeRequests.remove(connection);
        }
        connection.disconnect();
    }

    private byte[] readResponse(HttpURLConnection connection, int maxBytes) throws IOException {
        int length = connection.getContentLength();
        if (length > maxBytes) throw new InvalidResponseException();
        int initial = length > 0 ? Math.min(length, 256 * 1024) : 8192;
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream(initial)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkRunning();
                if (count > maxBytes - bytes.size()) throw new InvalidResponseException();
                bytes.write(buffer, 0, count);
            }
            checkRunning();
            return bytes.toByteArray();
        }
    }

    private static long retryDelay(String retryAfter) {
        if (retryAfter != null) {
            try {
                return Math.max(0, Math.min(60, Long.parseLong(retryAfter.trim()))) * 1000;
            } catch (NumberFormatException ignored) {
                return 1500;
            }
        }
        return 1000;
    }

    private void delay(long milliseconds) throws IOException {
        long deadline = System.nanoTime() + milliseconds * 1_000_000;
        while (true) {
            checkRunning();
            long remaining = (deadline - System.nanoTime()) / 1_000_000;
            if (remaining <= 0) return;
            try {
                Thread.sleep(Math.min(200, remaining));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                checkRunning();
            }
        }
    }

    private void checkRunning() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("已取消 TMDB 请求");
        }
    }

    private static boolean validImagePath(String path) {
        return IMAGE_PATH.matcher(path).matches();
    }

    private static boolean isImage(byte[] bytes) {
        if (bytes.length < 12) return false;
        return ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255)
                || ((bytes[0] & 255) == 137 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G')
                || (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F')
                || (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P');
    }

    private static String string(JSONObject object, String field) {
        return object == null || object.isNull(field) ? "" : object.optString(field, "").trim();
    }

    private static void put(JSONObject object, String field, Object value) {
        try {
            object.put(field, value);
        } catch (JSONException error) {
            throw new IllegalStateException("无法整理 TMDB 数据", error);
        }
    }

    private static int releaseYear(JSONObject entry) {
        String date = string(entry, "release_date");
        if (date.length() < 4) return 0;
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static final class InvalidResponseException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    public enum ProbeState {
        REACHABLE, VALID, NOT_PROVIDED, INVALID_FORMAT, AUTH_REJECTED, DNS_ERROR, TIMEOUT,
        TLS_ERROR, NETWORK_ERROR, RATE_LIMITED, HTTP_ERROR, UNEXPECTED_RESPONSE
    }

    public static final class Probe {
        public final ProbeState state;
        public final int httpStatus;
        public final long elapsedMillis;

        private Probe(ProbeState state, int httpStatus, long elapsedMillis) {
            this.state = state;
            this.httpStatus = httpStatus;
            this.elapsedMillis = elapsedMillis;
        }
    }

    public static final class Diagnostic {
        public final Probe api;
        public final Probe imageCdn;
        public final Probe credentials;

        private Diagnostic(Probe api, Probe imageCdn, Probe credentials) {
            this.api = api;
            this.imageCdn = imageCdn;
            this.credentials = credentials;
        }
    }

    public static final class HttpException extends MovieMetadataSource.HttpError {
        private static final long serialVersionUID = 1L;
        public final int statusCode;
        private final long retryDelayMillis;

        private HttpException(int statusCode, boolean api, long retryDelayMillis) {
            super(statusCode, errorMessage(statusCode, api));
            this.statusCode = statusCode;
            this.retryDelayMillis = retryDelayMillis;
        }

        private static String errorMessage(int statusCode, boolean api) {
            if (api && (statusCode == 401 || statusCode == 403)) {
                return "TMDB 验证失败，请检查 API Key 或读取令牌（HTTP " + statusCode + "）";
            }
            if (statusCode == 429) return "TMDB 请求过于频繁，请稍后重试（HTTP 429）";
            if (api && statusCode == 404) return "TMDB 上没有对应的记录（HTTP 404）";
            String resource = api ? "TMDB API" : "TMDB 图片";
            return resource + "请求失败（HTTP " + statusCode + "）";
        }
    }

    private static final class SearchHit {
        private final int id;
        private final int year;
        private final int voteCount;
        private final String title;
        private final String originalTitle;
        private final String overview;
        private final String posterPath;
        private final Set<String> titles = new HashSet<>();
        private double score;
        private boolean exact;

        private SearchHit(int id, JSONObject entry) {
            this.id = id;
            this.year = releaseYear(entry);
            this.title = string(entry, "title");
            this.originalTitle = string(entry, "original_title");
            this.overview = string(entry, "overview");
            this.posterPath = string(entry, "poster_path");
            this.voteCount = entry.optInt("vote_count");
        }

        private void addTitle(String title) {
            String normalized = normalizedTitle(title);
            if (!normalized.isEmpty()) titles.add(normalized);
        }
    }

    public static final class Candidate {
        public final int id;
        public final int year;
        public final String title;
        public final String originalTitle;
        public final String overview;
        public final String posterPath;
        public final double score;
        public final boolean confident;

        private Candidate(SearchHit hit, boolean confident) {
            this.id = hit.id;
            this.year = hit.year;
            this.title = hit.title.isEmpty() ? hit.originalTitle : hit.title;
            this.originalTitle = hit.originalTitle;
            this.overview = hit.overview;
            this.posterPath = hit.posterPath;
            this.score = hit.score;
            this.confident = confident;
        }

        Candidate(int id, int year, String title, String originalTitle, String overview, boolean confident) {
            this.id = id;
            this.year = year;
            this.title = title;
            this.originalTitle = originalTitle;
            this.overview = overview;
            this.posterPath = "";
            this.score = 0;
            this.confident = confident;
        }
    }
}
