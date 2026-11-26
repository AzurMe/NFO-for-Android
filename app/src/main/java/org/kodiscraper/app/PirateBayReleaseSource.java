package org.kodiscraper.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PirateBayReleaseSource {
    private static final String HOST = "apibay.org";
    private static final int MAX_TITLE_LENGTH = 180;
    private static final int MAX_NAME_LENGTH = 400;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_ROWS = 200;
    private static final int MAX_RESULTS = 20;
    private static final long SIZE_LIMIT = 40_000_000_000L;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern IMDB = Pattern.compile("tt[0-9]{5,12}");
    private static final Pattern INTEGER = Pattern.compile("[0-9]{1,18}");
    private static final Pattern UHD = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])2160p(?![\\p{L}\\p{N}])");
    private static final Pattern HDR10 = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])HDR[\\p{Z}\\p{Pd}._]?10"
            + "(?![\\p{L}\\p{N}+]|[\\p{Z}\\p{Pd}._]+(?:\\+|plus(?![\\p{L}\\p{N}])))");
    private static final Pattern RELEASE_YEAR = Pattern.compile("(?<![0-9])(?:18[89][0-9]|19[0-9]{2}|20[0-9]{2})(?![0-9])");
    private static final Pattern LEADING_GROUP = Pattern.compile("^\\s*(?:\\[[^\\]\\r\\n]{1,32}\\]\\s*){1,2}");
    private static final Set<String> EDITIONS = new HashSet<>(Arrays.asList(
            "4krestoration", "collectorsedition", "criterioncollection", "directorscut", "extendedcut",
            "extendededition", "finalcut", "imax", "remastered", "restored", "specialedition",
            "thedirectorscut", "thefinalcut", "theatricalcut", "ultimatecut", "unrated"));

    private static final Comparator<Candidate> ORDER = Comparator.comparingInt((Candidate option) -> option.seeders)
            .reversed().thenComparing(option -> option.name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(option -> option.name).thenComparingLong(option -> option.bytes)
            .thenComparing(option -> option.infoHash);

    static final class Candidate {
        final String name;
        final String infoHash;
        final long bytes;
        final int seeders;

        Candidate(String name, String infoHash, long bytes, int seeders) {
            this.name = name;
            this.infoHash = infoHash;
            this.bytes = bytes;
            this.seeders = seeders;
        }

        String magnet() {
            return "magnet:?xt=urn:btih:" + infoHash + "&dn=" + SubtitleHttp.encode(name).replace("+", "%20");
        }
    }

    List<Candidate> search(String title, int year, String imdbId) throws IOException {
        String query = title == null ? "" : title.trim();
        if (query.length() > MAX_TITLE_LENGTH || titleKey(query).isEmpty() || year < 1888 || year > 2099) {
            throw new IOException("查询资源需要影片名称和上映年份");
        }
        String address = "https://" + HOST + "/q.php?q=" + SubtitleHttp.encode(query + " " + year) + "&cat=201%2C207";
        byte[] data;
        try {
            data = SubtitleHttp.get(address, null, null, MAX_RESPONSE_BYTES, HOST);
        } catch (SubtitleHttp.HttpException response) {
            throw new IOException("海盗湾查询返回 HTTP " + response.statusCode, response);
        } catch (IOException connection) {
            ScrapeEngine.checkCancelled();
            if (connection.getCause() instanceof SocketTimeoutException) {
                throw new IOException("海盗湾查询超时", connection);
            }
            if (connection.getCause() instanceof UnknownHostException) {
                throw new IOException("无法解析海盗湾地址", connection);
            }
            throw new IOException("无法连接海盗湾查询服务", connection);
        }
        return parseCandidates(new String(data, StandardCharsets.UTF_8), query, year, imdbId);
    }

    static List<Candidate> parseCandidates(String response, String title, int year, String imdbId) throws IOException {
        final JSONArray rows;
        try {
            rows = new JSONArray(response);
        } catch (JSONException malformed) {
            throw new IOException("海盗湾返回了无法读取的数据", malformed);
        }
        String targetTitle = titleKey(title);
        String targetYear = String.valueOf(year);
        String targetImdb = imdb(imdbId);
        Map<String, Candidate> byHash = new HashMap<>();
        for (int i = 0; i < Math.min(MAX_ROWS, rows.length()); i++) {
            ScrapeEngine.checkCancelled();
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            Candidate candidate = candidate(row, targetTitle, targetYear, targetImdb);
            if (candidate != null) {
                Candidate previous = byHash.get(candidate.infoHash);
                if (previous == null || ORDER.compare(candidate, previous) < 0) {
                    byHash.put(candidate.infoHash, candidate);
                }
            }
        }
        List<Candidate> results = new ArrayList<>(byHash.values());
        results.sort(ORDER);
        return new ArrayList<>(results.subList(0, Math.min(MAX_RESULTS, results.size())));
    }

    private static Candidate candidate(JSONObject row, String title, String year, String imdbId) {
        String name = row.optString("name", "").trim();
        String category = row.optString("category", "").trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || !visibleText(name)
                || (!category.isEmpty() && !category.equals("201") && !category.equals("207"))) {
            return null;
        }
        String recordImdb = imdb(row.optString("imdb", ""));
        if ((!imdbId.isEmpty() && !recordImdb.isEmpty() && !imdbId.equals(recordImdb))
                || !matchesRelease(name, title, year)) {
            return null;
        }
        long bytes = number(row.optString("size", ""));
        String hash = row.optString("info_hash", "").trim().toLowerCase(Locale.ROOT);
        if (bytes <= 0 || bytes >= SIZE_LIMIT || !HASH.matcher(hash).matches()
                || hash.equals("0000000000000000000000000000000000000000")) {
            return null;
        }
        int seeds = (int) Math.min(Integer.MAX_VALUE, number(row.optString("seeders", "")));
        return new Candidate(name, hash, bytes, seeds);
    }

    private static boolean matchesRelease(String name, String title, String year) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC);
        Matcher years = RELEASE_YEAR.matcher(normalized);
        while (years.find()) {
            if (years.group().equals(year)) {
                String releaseTitle = normalized.substring(0, years.start());
                if (matchesTitle(releaseTitle, title)
                        || matchesTitle(LEADING_GROUP.matcher(releaseTitle).replaceFirst(""), title)) {
                    String tags = normalized.substring(years.end());
                    return UHD.matcher(tags).find() && HDR10.matcher(tags).find();
                }
            }
        }
        return false;
    }

    private static boolean matchesTitle(String releaseTitle, String title) {
        String normalized = titleKey(releaseTitle);
        return !title.isEmpty() && (normalized.equals(title)
                || (normalized.startsWith(title) && EDITIONS.contains(normalized.substring(title.length()))));
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
