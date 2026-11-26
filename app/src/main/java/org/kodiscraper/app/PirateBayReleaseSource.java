package org.kodiscraper.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

final class PirateBayReleaseSource {
    private static final String HOST = "apibay.org";
    private static final String MOVIE_CATEGORIES = "201,202,207,209,210,211";
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 9_000;
    private static final int RETRY_DELAY_MS = 250;
    private static final int MAX_TITLE_LENGTH = 180;
    private static final int MAX_NAME_LENGTH = 400;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_ROWS = 200;
    private static final int OBSERVED_RESULT_LIMIT = 100;
    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList(MOVIE_CATEGORIES.split(",")));
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern IMDB = Pattern.compile("tt[0-9]{5,12}");
    private static final Pattern INTEGER = Pattern.compile("[0-9]{1,18}");
    private static final String TAG_START = "(?i)(?<![\\p{L}\\p{N}])";
    private static final String TAG_END = "(?![\\p{L}\\p{N}])";
    private static final String LABEL_SEPARATOR = "[\\p{Z}\\p{Pd}._]";
    private static final String HDR10_TEXT = "HDR" + LABEL_SEPARATOR + "?10";
    private static final String PLUS_SUFFIX = LABEL_SEPARATOR + "*(?:\\+|plus" + TAG_END + ")";
    private static final Pattern HDR10 = Pattern.compile(TAG_START + HDR10_TEXT
            + "(?![\\p{L}\\p{N}]|" + PLUS_SUFFIX + ")");
    private static final Pattern HDR10_PLUS = Pattern.compile(TAG_START + HDR10_TEXT + PLUS_SUFFIX);
    private static final Pattern HDR = Pattern.compile(TAG_START + "HDR" + TAG_END);
    private static final Pattern SDR = Pattern.compile(TAG_START + "SDR" + TAG_END);
    private static final Pattern DOLBY_VISION = Pattern.compile(TAG_START + "(?:DV|DoVi|Dolby" + LABEL_SEPARATOR
            + "*Vision)" + TAG_END);
    private static final Pattern HEIGHT = Pattern.compile(TAG_START + "([0-9]{3,4})p" + TAG_END);
    private static final Pattern RELEASE_YEAR = Pattern.compile("(?<![0-9])(?:18[89][0-9]|19[0-9]{2}|20[0-9]{2})(?![0-9])");
    private static final Pattern LEADING_GROUP = Pattern.compile("^\\s*(?:\\[[^\\]\\r\\n]{1,32}\\]\\s*){1,2}");
    private static final Pattern EPISODES = Pattern.compile(TAG_START + "(?:S[0-9]{1,2}(?:[ ._-]*E[0-9]{1,3})*"
            + "|(?:Season|Series)[ ._-]*[0-9]{1,2}|(?:Episode|Ep|E)[ ._-]*[0-9]{1,3}|[0-9]{1,2}x[0-9]{2,3})" + TAG_END);
    private static final String EXTRA_LABEL = "(?:Trailer|Teaser|Samples?|Featurettes?|Extras|Behind"
            + LABEL_SEPARATOR + "+The" + LABEL_SEPARATOR + "+Scenes)";
    private static final Pattern PRIMARY_EXTRA = Pattern.compile("(?i)^[^\\p{L}\\p{N}]*(?:Official"
            + LABEL_SEPARATOR + "+)?" + EXTRA_LABEL + TAG_END);
    private static final Pattern EXTRA = Pattern.compile(TAG_START + EXTRA_LABEL + TAG_END);
    private static final String SEQUEL_NUMBER = "(?:[2-9]|[1-9][0-9]+|II|III|IV|V|VI|VII|VIII|IX|X"
            + "|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten)";
    private static final Pattern SEQUEL = Pattern.compile(TAG_START + "(?:(?:Part|Chapter|Volume|Vol|Episode)"
            + LABEL_SEPARATOR + "*)?" + SEQUEL_NUMBER + TAG_END);
    private static final Pattern ATTACHED_SEQUEL_NUMBER = Pattern.compile("(?:[2-9]|[1-9][0-9]+)");
    private static final Set<String> EDITIONS = new HashSet<>(Arrays.asList(
            "4krestoration", "collectorsedition", "criterioncollection", "directorscut", "extendedcut",
            "extendededition", "finalcut", "imax", "remastered", "restored", "specialedition",
            "thedirectorscut", "thefinalcut", "theatricalcut", "ultimatecut", "unrated"));

    private static final Comparator<Candidate> ORDER = Comparator.comparingInt((Candidate option) -> option.seeders)
            .reversed().thenComparing(option -> option.name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(option -> option.name).thenComparing(option -> option.bytes == 0)
            .thenComparingLong(option -> option.bytes)
            .thenComparing(option -> option.infoHash);

    record SearchResult(List<Candidate> candidates, boolean incomplete, boolean capped) {}

    static final class Candidate {
        final String name;
        final String infoHash;
        final long bytes;
        final int seeders;
        final int height;
        final boolean hdr;
        final boolean hdr10;
        final boolean hdr10Plus;
        final boolean dolbyVision;
        final boolean sdr;

        private Candidate(String name, String infoHash, long bytes, int seeders, String tags) {
            this.name = name;
            this.infoHash = infoHash;
            this.bytes = bytes;
            this.seeders = seeders;
            this.height = resolution(tags);
            this.hdr10 = HDR10.matcher(tags).find();
            this.hdr10Plus = HDR10_PLUS.matcher(tags).find();
            this.dolbyVision = DOLBY_VISION.matcher(tags).find();
            this.hdr = hdr10 || hdr10Plus || dolbyVision || HDR.matcher(tags).find();
            this.sdr = SDR.matcher(tags).find();
        }

        String magnet() {
            return "magnet:?xt=urn:btih:" + infoHash + "&dn=" + SubtitleHttp.encode(name).replace("+", "%20");
        }
    }

    List<Candidate> search(String title, int year, String imdbId) throws IOException {
        return search(title, null, year, imdbId).candidates();
    }

    SearchResult search(String original, String localized, int year, String imdbId) throws IOException {
        Map<String, String> titles = new LinkedHashMap<>();
        addTitle(titles, original);
        addTitle(titles, localized);
        if (titles.isEmpty() || year < 1888 || year > 2099) {
            throw new IOException("查询资源需要影片名称和上映年份");
        }
        String targetImdb = imdb(imdbId);
        Set<String> queries = new LinkedHashSet<>();
        if (!targetImdb.isEmpty()) {
            queries.add(targetImdb);
        }
        for (String title : titles.values()) {
            queries.add(title + " " + year);
        }

        Map<String, Candidate> byHash = new HashMap<>();
        boolean capped = false;
        for (String query : queries) {
            ScrapeEngine.checkCancelled();
            String address = "https://" + HOST + "/q.php?q=" + SubtitleHttp.encode(query)
                    + "&cat=" + SubtitleHttp.encode(MOVIE_CATEGORIES);
            try {
                byte[] data = lookup(address);
                ScrapeEngine.checkCancelled();
                JSONArray rows = parseRows(new String(data, StandardCharsets.UTF_8));
                capped |= rows.length() >= OBSERVED_RESULT_LIMIT;
                collect(rows, titles.keySet(), String.valueOf(year), targetImdb, byHash);
            } catch (IOException failure) {
                ScrapeEngine.checkCancelled();
                if (failure instanceof InterruptedIOException || byHash.isEmpty()) {
                    throw failure;
                }
                return new SearchResult(sorted(byHash), true, capped);
            }
        }
        return new SearchResult(sorted(byHash), false, capped);
    }

    private static void addTitle(Map<String, String> titles, String value) {
        String title = value == null ? "" : value.trim();
        if (title.length() > MAX_TITLE_LENGTH || !visibleText(title)) {
            return;
        }
        String key = titleKey(title);
        if (!key.isEmpty()) {
            titles.putIfAbsent(key, title);
        }
    }

    private static byte[] lookup(String address) throws IOException {
        for (int attempt = 0; ; attempt++) {
            ScrapeEngine.checkCancelled();
            try {
                return SubtitleHttp.get(address, null, null, MAX_RESPONSE_BYTES, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, HOST);
            } catch (IOException failure) {
                ScrapeEngine.checkCancelled();
                if (attempt != 0 || !SubtitleHttp.isRetryableConnection(failure)) {
                    throw lookupError(failure);
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("操作已停止");
                }
            }
        }
    }

    private static IOException lookupError(IOException failure) {
        if (failure instanceof SubtitleHttp.HttpException response) {
            return new IOException(HOST + " 查询返回 HTTP " + response.statusCode, failure);
        }
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        if (cause instanceof UnknownHostException) {
            return new IOException("无法解析 " + HOST + "（DNS）", failure);
        }
        if (cause instanceof SocketTimeoutException) {
            return new IOException("查询 " + HOST + " 超时", failure);
        }
        if (failure instanceof InterruptedIOException) {
            return failure;
        }
        if (failure instanceof SSLException || cause instanceof SSLException) {
            return new IOException(HOST + " 的 HTTPS 连接失败", failure);
        }
        if (failure instanceof ProtocolException || cause instanceof ProtocolException) {
            return new IOException(HOST + " 返回了无效的 HTTP 数据", failure);
        }
        if (cause instanceof ConnectException) {
            return new IOException("无法连接 " + HOST, failure);
        }
        if (cause instanceof SocketException || cause instanceof EOFException) {
            return new IOException(HOST + " 连接中断", failure);
        }
        return new IOException("无法读取 " + HOST + " 的查询结果", failure);
    }

    static List<Candidate> parseCandidates(String response, String title, int year, String imdbId) throws IOException {
        Map<String, Candidate> byHash = new HashMap<>();
        collect(parseRows(response), Collections.singleton(titleKey(title)), String.valueOf(year), imdb(imdbId), byHash);
        return sorted(byHash);
    }

    private static JSONArray parseRows(String response) throws IOException {
        try {
            return new JSONArray(response);
        } catch (JSONException malformed) {
            throw new IOException("海盗湾返回了无法读取的数据", malformed);
        }
    }

    private static void collect(JSONArray rows, Set<String> targetTitles, String year, String imdbId,
                                Map<String, Candidate> byHash) throws IOException {
        for (int i = 0; i < Math.min(MAX_ROWS, rows.length()); i++) {
            ScrapeEngine.checkCancelled();
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            Candidate candidate = candidate(row, targetTitles, year, imdbId);
            if (candidate != null) {
                Candidate previous = byHash.get(candidate.infoHash);
                if (previous == null || ORDER.compare(candidate, previous) < 0) {
                    byHash.put(candidate.infoHash, candidate);
                }
            }
        }
    }

    private static List<Candidate> sorted(Map<String, Candidate> byHash) {
        List<Candidate> results = new ArrayList<>(byHash.values());
        results.sort(ORDER);
        return Collections.unmodifiableList(results);
    }

    private static Candidate candidate(JSONObject row, Set<String> titles, String year, String imdbId) {
        String name = row.optString("name", "").trim();
        String category = row.optString("category", "").trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || !visibleText(name)
                || (!category.isEmpty() && !CATEGORIES.contains(category))) {
            return null;
        }
        String recordImdb = imdb(row.optString("imdb", ""));
        boolean exactImdb = !imdbId.isEmpty() && imdbId.equals(recordImdb);
        if (!imdbId.isEmpty() && !recordImdb.isEmpty() && !exactImdb) {
            return null;
        }
        String hash = row.optString("info_hash", "").trim().toLowerCase(Locale.ROOT);
        if (!HASH.matcher(hash).matches() || hash.equals("0000000000000000000000000000000000000000")) {
            return null;
        }
        String tags = releaseTags(name, titles, year, exactImdb);
        if (tags == null) {
            return null;
        }
        long bytes = number(row.optString("size", ""));
        int seeds = (int) Math.min(Integer.MAX_VALUE, number(row.optString("seeders", "")));
        return new Candidate(name, hash, bytes, seeds, tags);
    }

    private static String releaseTags(String name, Set<String> titles, String year, boolean exactImdb) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC);
        Matcher years = RELEASE_YEAR.matcher(normalized);
        boolean conflict = false;
        while (years.find()) {
            String releaseTitle = normalized.substring(0, years.start());
            String withoutGroup = LEADING_GROUP.matcher(releaseTitle).replaceFirst("");
            String key = titleKey(releaseTitle);
            String groupFreeKey = titleKey(withoutGroup);
            if (matchesTitle(key, titles) || matchesTitle(groupFreeKey, titles)) {
                if (years.group().equals(year)) {
                    String tags = normalized.substring(years.end());
                    return EPISODES.matcher(tags).find() || PRIMARY_EXTRA.matcher(tags).find() ? null : tags;
                }
                conflict = true;
            } else if (exactImdb && isSequel(withoutGroup, groupFreeKey, titles)) {
                conflict = true;
            }
        }
        if (!exactImdb || conflict || EPISODES.matcher(normalized).find() || EXTRA.matcher(normalized).find()) {
            return null;
        }
        String withoutGroup = LEADING_GROUP.matcher(normalized).replaceFirst("");
        return isSequel(withoutGroup, titleKey(withoutGroup), titles) ? null : normalized;
    }

    private static boolean matchesTitle(String name, Set<String> titles) {
        for (String title : titles) {
            if (!title.isEmpty() && (name.equals(title)
                    || (name.startsWith(title) && EDITIONS.contains(name.substring(title.length()))))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSequel(String name, String key, Set<String> titles) {
        Matcher labels = SEQUEL.matcher(name);
        while (labels.find()) {
            if (titles.contains(titleKey(name.substring(0, labels.start())))) {
                return true;
            }
        }
        for (String title : titles) {
            if (!title.isEmpty() && key.startsWith(title)
                    && ATTACHED_SEQUEL_NUMBER.matcher(key.substring(title.length())).matches()) {
                return true;
            }
        }
        return false;
    }

    private static int resolution(String tags) {
        int height = 0;
        Matcher heights = HEIGHT.matcher(tags);
        while (heights.find()) {
            height = Math.max(height, Integer.parseInt(heights.group(1)));
        }
        return height;
    }

    private static String titleKey(String title) {
        String text = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder key = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int character = text.codePointAt(i);
            if (Character.isLetterOrDigit(character)) {
                key.appendCodePoint(character);
            }
            i += Character.charCount(character);
        }
        return key.toString();
    }

    private static boolean visibleText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isISOControl(character) || (character >= '\u2028' && character <= '\u202e')
                    || (character >= '\u2066' && character <= '\u2069')) {
                return false;
            }
        }
        return true;
    }

    private static String imdb(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return IMDB.matcher(id).matches() ? id : "";
    }

    private static long number(String value) {
        String text = value.trim();
        return INTEGER.matcher(text).matches() ? Long.parseLong(text) : 0;
    }
}
