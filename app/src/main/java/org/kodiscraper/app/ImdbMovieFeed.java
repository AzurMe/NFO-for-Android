package org.kodiscraper.app;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

final class ImdbMovieFeed {
    private static final String RATINGS_URL = "https://datasets.imdbws.com/title.ratings.tsv.gz";
    private static final String HEADER = "tconst\taverageRating\tnumVotes";
    private static final Pattern IMDB_ID = Pattern.compile("tt[0-9]{5,12}");
    private static final int MAX_COMPRESSED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 96 * 1024 * 1024;
    private static final int MAX_ROW_BYTES = 128;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final long MAX_CACHE_AGE_MILLIS = 24 * 60 * 60 * 1000L;
    private static final Object CACHE_LOCK = new Object();

    private final File cache;
    private volatile HttpURLConnection activeDownload;

    ImdbMovieFeed(Context context) {
        cache = new File(context.getCacheDir(), "imdb-title-ratings.tsv.gz");
    }

    record Movie(String title, String originalTitle, String imdbId, LocalDate releaseDate,
                 double rating, int votes) {}

    private record Rating(double value, int votes) {}

    List<Movie> load(TmdbClient tmdb, LocalDate today) throws IOException {
        ScrapeEngine.checkCancelled();
        LocalDate from = today.minusDays(29);
        List<TmdbClient.RecentMovie> recent = tmdb.recentMovies(from, today);
        Map<String, TmdbClient.RecentMovie> identified = new LinkedHashMap<>();
        for (TmdbClient.RecentMovie movie : recent) {
            LocalDate released = movie.releaseDate();
            String id = normalizedId(movie.imdbId());
            if (!id.isEmpty() && released != null && !released.isBefore(from) && !released.isAfter(today)) {
                identified.putIfAbsent(id, movie);
            }
        }
        if (identified.isEmpty()) return Collections.emptyList();
        Map<String, Rating> ratings = ratings(identified.keySet());
        List<Movie> movies = new ArrayList<>();
        for (Map.Entry<String, TmdbClient.RecentMovie> item : identified.entrySet()) {
            Rating rating = ratings.get(item.getKey());
            if (rating == null) continue;
            TmdbClient.RecentMovie movie = item.getValue();
            String title = cleanTitle(movie.title());
            String originalTitle = cleanTitle(movie.originalTitle());
            if (title.isEmpty()) title = originalTitle;
            if (!title.isEmpty()) {
                movies.add(new Movie(title, originalTitle, item.getKey(), movie.releaseDate(),
                        rating.value(), rating.votes()));
            }
        }
        return movies;
    }

    void cancel() {
        HttpURLConnection connection = activeDownload;
        if (connection != null) connection.disconnect();
    }

    private Map<String, Rating> ratings(Set<String> wanted) throws IOException {
        synchronized (CACHE_LOCK) {
            ScrapeEngine.checkCancelled();
            long age = System.currentTimeMillis() - cache.lastModified();
            if (cache.isFile() && age >= 0 && age < MAX_CACHE_AGE_MILLIS) {
                try {
                    return readRatings(cache, wanted);
                } catch (IOException invalidCache) {
                    ScrapeEngine.checkCancelled();
                    // A fresh but unreadable cache can be recovered from the official dataset.
                }
            }

            File download = new File(cache.getParentFile(), "imdb-title-ratings.download");
            try {
                downloadRatings(download);
                Map<String, Rating> matches = readRatings(download, wanted);
                ScrapeEngine.checkCancelled();
                // Both files are in the private cache directory. A failed replacement leaves the
                // previous file in place, and the current verified ratings remain available.
                download.renameTo(cache);
                return matches;
            } finally {
                if (download.exists()) download.delete();
            }
        }
    }

    private void downloadRatings(File destination) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(RATINGS_URL).toURL().openConnection();
        try {
            activeDownload = connection;
            ScrapeEngine.checkCancelled();
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "NFOForAndroid");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("IMDb 评分下载失败（HTTP " + code + "）");
            }
            if (connection.getContentLengthLong() > MAX_COMPRESSED_BYTES) {
                throw new IOException("IMDb 评分文件超出下载限制");
            }
            int total = 0;
            byte[] buffer = new byte[BUFFER_BYTES];
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    ScrapeEngine.checkCancelled();
                    if (count > MAX_COMPRESSED_BYTES - total) {
                        throw new IOException("IMDb 评分文件超出下载限制");
                    }
                    total += count;
                    output.write(buffer, 0, count);
                }
            }
        } finally {
            activeDownload = null;
            connection.disconnect();
        }
    }

    private static Map<String, Rating> readRatings(File dataset, Set<String> wanted) throws IOException {
        if (dataset.length() <= 0 || dataset.length() > MAX_COMPRESSED_BYTES) {
            throw new IOException("IMDb 评分文件大小无效");
        }
        long[] ids = new long[wanted.size()];
        int index = 0;
        for (String id : wanted) ids[index++] = Long.parseLong(id.substring(2));
        Arrays.sort(ids);

        Map<String, Rating> found = new HashMap<>();
        byte[] buffer = new byte[BUFFER_BYTES];
        byte[] row = new byte[MAX_ROW_BYTES];
        int expanded = 0;
        int length = 0;
        int rows = 0;
        try (InputStream raw = new BufferedInputStream(new FileInputStream(dataset), BUFFER_BYTES);
             InputStream input = new GZIPInputStream(raw, BUFFER_BYTES)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                ScrapeEngine.checkCancelled();
                if (count > MAX_EXPANDED_BYTES - expanded) {
                    throw new IOException("IMDb 评分文件超出解压限制");
                }
                expanded += count;
                for (index = 0; index < count; index++) {
                    byte character = buffer[index];
                    if (character == '\n') {
                        if (length > 0) {
                            readRow(row, length, rows++ == 0, ids, wanted, found);
                        }
                        length = 0;
                    } else {
                        if (length == row.length) throw new IOException("IMDb 评分文件记录过长");
                        row[length++] = character;
                    }
                }
            }
        }
        if (length > 0) readRow(row, length, rows++ == 0, ids, wanted, found);
        if (rows < 2) throw new IOException("IMDb 评分文件没有数据");
        return found;
    }

    private static void readRow(byte[] row, int length, boolean header, long[] sought,
                                Set<String> wanted, Map<String, Rating> found) throws IOException {
        if (row[length - 1] == '\r') length--;
        if (header) {
            if (!HEADER.equals(new String(row, 0, length, StandardCharsets.US_ASCII))) {
                throw new IOException("IMDb 评分文件标题格式无效");
            }
            return;
        }

        if (length < 8 || row[0] != 't' || row[1] != 't') return;
        long number = 0;
        int idEnd = 2;
        for (; idEnd < length && row[idEnd] != '\t'; idEnd++) {
            int digit = row[idEnd] - '0';
            if (digit < 0 || digit > 9 || idEnd >= 14) return;
            number = number * 10 + digit;
        }
        if (idEnd == length || Arrays.binarySearch(sought, number) < 0) return;
        String id = new String(row, 0, idEnd, StandardCharsets.US_ASCII);
        if (!wanted.contains(id)) return;

        int ratingStart = idEnd + 1;
        int ratingEnd = ratingStart;
        while (ratingEnd < length && row[ratingEnd] != '\t') ratingEnd++;
        if (ratingEnd == length) return;
        try {
            double score = Double.parseDouble(new String(row, ratingStart, ratingEnd - ratingStart,
                    StandardCharsets.US_ASCII));
            int votes = Integer.parseInt(new String(row, ratingEnd + 1, length - ratingEnd - 1,
                    StandardCharsets.US_ASCII));
            if (score >= 1 && score <= 10 && votes > 0) found.put(id, new Rating(score, votes));
        } catch (NumberFormatException invalidValue) {
            // Keep the feed available for the remaining titles if a rating value is invalid.
        }
    }

    private static String normalizedId(String id) {
        if (id == null) return "";
        String value = id.trim().toLowerCase(Locale.ROOT);
        return IMDB_ID.matcher(value).matches() ? value : "";
    }

    private static String cleanTitle(String value) {
        return value == null ? "" : value.trim();
    }
}
