package org.kodiscraper.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.kodiscraper.app.TvdbMovie.*;

final class TvdbClient implements MovieMetadataSource {
    private static final String API = "https://api4.thetvdb.com/v4";
    private static final int JSON_LIMIT = 8 * 1024 * 1024;
    private static final int IMAGE_LIMIT = 20 * 1024 * 1024;
    private final String apiKey;
    private final String pin;
    private final String language;
    private final TmdbClient supplement;
    private final Set<HttpURLConnection> active = new HashSet<>();
    private final Map<Integer, JSONObject> movies = new HashMap<>();
    private volatile boolean cancelled;
    private String session = "";
    private JSONArray artworkTypes;
    private JSONArray peopleTypes;

    TvdbClient(String apiKey, String pin, String language) {
        this(apiKey, pin, language, null);
    }

    TvdbClient(String apiKey, String pin, String language, TmdbClient supplement) {
        this.apiKey = apiKey.trim();
        this.pin = pin.trim();
        this.supplement = supplement;
        Locale locale = Locale.forLanguageTag(language);
        if (locale.getLanguage().equals("zh")) {
            this.language = locale.getCountry().matches("TW|HK|MO") ? "zht" : "zho";
        } else {
            this.language = locale.getISO3Language();
        }
    }

    @Override public Provider provider() { return Provider.TVDB; }

    @Override public void validate() throws IOException {
        if (artworkTypes == null) artworkTypes = array("/artwork/types");
    }

    @Override public void cancel() {
        List<HttpURLConnection> working;
        synchronized (active) {
            cancelled = true;
            working = new ArrayList<>(active);
        }
        for (HttpURLConnection connection : working) connection.disconnect();
        if (supplement != null) supplement.cancel();
    }

    @Override public int findMovieByImdb(String imdbId) throws IOException {
        return imdbId != null && imdbId.matches("tt[0-9]{5,12}") ? findRemote(imdbId, "imdb") : 0;
    }

    @Override public int findMovieByTmdb(int id) throws IOException {
        return id > 0 ? findRemote(String.valueOf(id), "tmdb") : 0;
    }

    private int findRemote(String value, String type) throws IOException {
        JSONArray results;
        try {
            results = array("/search/remoteid/" + value);
        } catch (HttpError error) {
            if (error.statusCode == 404) return 0;
            throw error;
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (JSONObject row : rows(results)) {
            int id = object(row, "movie").optInt("id");
            if (id > 0) ids.add(id);
        }
        if (ids.size() > 10) return 0;
        int match = 0;
        for (int id : ids) {
            if (value.equals(remoteId(rawMovie(id), type))) {
                if (match > 0) return 0;
                match = id;
            }
        }
        return match;
    }

    private record Hit(int id, int year, String title, String original, String overview, boolean exact) {}

    @Override public List<TmdbClient.Candidate> search(String title, int year) throws IOException {
        if (title == null || title.trim().isEmpty()) return new ArrayList<>();
        if (title.length() > 250) throw new IOException("电影名称过长");
        String key = TmdbClient.normalizedTitle(title);
        if (key.isEmpty()) return new ArrayList<>();
        String address = "/search?type=movie&limit=30&query=" + SubtitleHttp.encode(title);
        List<Hit> hits = searchHits(address, key);
        if (year > 0 && hits.stream().noneMatch(hit -> hit.exact && hit.year == year)) {
            List<Hit> dated = searchHits(address + "&year=" + year, key);
            Set<Integer> seen = new HashSet<>();
            for (Hit hit : dated) seen.add(hit.id);
            for (Hit hit : hits) if (seen.add(hit.id)) dated.add(hit);
            hits = dated;
        }
        hits.sort(Comparator.comparingInt((Hit hit) -> hit.exact ? 0 : 1)
                .thenComparingInt(hit -> year <= 0 ? 0 : hit.year > 0 ? Math.abs(hit.year - year) : Integer.MAX_VALUE));
        long matching = hits.stream().filter(hit -> hit.exact && (year <= 0 || hit.year == year)).count();
        List<TmdbClient.Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < Math.min(hits.size(), 14); index++) {
            Hit hit = hits.get(index);
            boolean certain = index == 0 && hit.exact && matching == 1
                    && (year > 0 ? hit.year == year : key.length() >= 2);
            candidates.add(new TmdbClient.Candidate(hit.id, hit.year, hit.title, hit.original, hit.overview, certain));
        }
        return candidates;
    }

    private List<Hit> searchHits(String address, String key) throws IOException {
        List<Hit> hits = new ArrayList<>();
        JSONArray results;
        try {
            results = array(address);
        } catch (HttpError error) {
            if (error.statusCode == 404) return hits;
            throw error;
        }
        Set<Integer> seen = new HashSet<>();
        for (JSONObject hit : rows(results)) {
            if (!"movie".equals(text(hit, "type"))) continue;
            int id = hit.optInt("tvdb_id");
            if (id <= 0 || !seen.add(id)) continue;
            String original = first(text(hit, "name"), text(hit, "title"));
            JSONObject titles = object(hit, "translations");
            JSONObject plots = object(hit, "overviews");
            String selected = first(text(titles, language), original, text(titles, "eng"));
            if (selected.isEmpty()) continue;
            boolean exact = key.equals(TmdbClient.normalizedTitle(selected)) || key.equals(TmdbClient.normalizedTitle(original));
            for (java.util.Iterator<String> keys = titles.keys(); !exact && keys.hasNext();) {
                exact = key.equals(TmdbClient.normalizedTitle(text(titles, keys.next())));
            }
            JSONArray aliases = hit.optJSONArray("aliases");
            for (int index = 0; !exact && aliases != null && index < aliases.length(); index++) {
                exact = key.equals(TmdbClient.normalizedTitle(aliases.optString(index, "")));
            }
            hits.add(new Hit(id, ScrapeEngine.parseId(text(hit, "year")), selected, original,
                    first(text(plots, language), text(plots, "eng"), text(hit, "overview")), exact));
        }
        return hits;
    }

    private JSONObject rawMovie(int id) throws IOException {
        JSONObject cached = movies.get(id);
        if (cached != null) return cached;
        JSONObject movie = get("/movies/" + id + "/extended?meta=translations").optJSONObject("data");
        if (movie == null || movie.optInt("id") != id) throw new IOException("TheTVDB 返回的影片编号不匹配");
        if (movies.size() >= 8) movies.clear();
        movies.put(id, movie);
        return movie;
    }

    @Override public JSONObject details(int id) throws IOException {
        if (id <= 0) throw new IOException("无效的 TheTVDB 电影编号");
        validate();
        if (peopleTypes == null) peopleTypes = array("/people/types");
        try {
            JSONObject result = TvdbMovie.toKodiData(rawMovie(id), language, artworkTypes, peopleTypes);
            supplement(result);
            return result;
        } catch (JSONException invalid) {
            throw new IOException("TheTVDB 电影资料无法读取", invalid);
        }
    }

    private void supplement(JSONObject movie) throws IOException, JSONException {
        if (supplement == null) return;
        int tmdbId = movie.optInt("id");
        String imdbId = text(object(movie, "external_ids"), "imdb_id");
        try {
            if (tmdbId <= 0 && !imdbId.isEmpty()) tmdbId = supplement.findMovieByImdb(imdbId);
            if (tmdbId <= 0) return;
            JSONObject extra = supplement.details(tmdbId);
            String imdbMatch = first(text(object(extra, "external_ids"), "imdb_id"), text(extra, "imdb_id"));
            if (extra.optInt("id") != tmdbId
                    || (!imdbId.isEmpty() && !imdbMatch.isEmpty() && !imdbId.equals(imdbMatch))) {
                movie.put("supplement_warning", "TheTVDB 和 TMDB 的影片编号有冲突，已保留 TheTVDB 资料。");
                return;
            }
            movie.put("id", tmdbId);
            for (String key : new String[]{"vote_average", "vote_count", "belongs_to_collection", "collection_details"}) {
                if (!extra.isNull(key)) movie.put(key, extra.opt(key));
            }
        } catch (IOException unavailable) {
            checkRunning();
            movie.put("supplement_warning", "TMDB 评分或合集暂时无法补齐，已保留 TheTVDB 资料。");
        }
    }

    @Override public String artwork(JSONObject movie, String type) {
        if ("poster".equals(type)) return text(movie, "poster_path");
        if ("fanart".equals(type)) return text(movie, "backdrop_path");
        JSONObject photos = object(movie, "images");
        String group;
        switch (type) {
            case "clearlogo": group = "logos"; break;
            case "keyart": group = "posters"; break;
            case "landscape": group = "backdrops"; break;
            default: return "";
        }
        for (JSONObject image : rows(photos, group)) {
            String url = text(image, "file_path");
            boolean textless = text(image, "iso_639_1").isEmpty();
            if (("clearlogo".equals(type) && url.toLowerCase(Locale.ROOT).endsWith(".png"))
                    || ("keyart".equals(type) && textless) || ("landscape".equals(type) && !textless)) return url;
        }
        return "";
    }

    @Override public List<String> alternateFanart(JSONObject movie, int limit) {
        Set<String> alternatives = new LinkedHashSet<>();
        String primary = text(movie, "backdrop_path");
        for (JSONObject image : rows(object(movie, "images"), "backdrops")) {
            if (alternatives.size() >= limit) break;
            String url = text(image, "file_path");
            if (!url.equals(primary) && text(image, "iso_639_1").isEmpty()) alternatives.add(url);
        }
        return new ArrayList<>(alternatives);
    }

    @Override public String collectionArtwork(JSONObject movie, String type) {
        return supplement == null ? "" : supplement.collectionArtwork(movie, type);
    }
    @Override public String castProfile(JSONObject person) { return text(person, "profile_path"); }

    @Override public byte[] image(String path, String size) throws IOException {
        if (supplement != null && path.startsWith("/")) return supplement.image(path, size);
        String address = imageUrl(path);
        if (address.isEmpty()) throw new IOException("TheTVDB 图片地址无效");
        return request(address, null, "", IMAGE_LIMIT);
    }

    private JSONArray array(String path) throws IOException {
        JSONObject response = get(path);
        JSONArray rows = response.optJSONArray("data");
        if (rows == null && !response.isNull("data")) throw new IOException("TheTVDB 数据格式异常");
        return rows == null ? new JSONArray() : rows;
    }

    private JSONObject get(String path) throws IOException {
        authenticate();
        try {
            return json(request(API + path, null, session, JSON_LIMIT));
        } catch (HttpError denied) {
            if (denied.statusCode != 401) throw denied;
            session = "";
            authenticate();
            return json(request(API + path, null, session, JSON_LIMIT));
        }
    }

    private void authenticate() throws IOException {
        if (!session.isEmpty()) return;
        if (!apiKey.matches("[A-Za-z0-9_-]{8,160}") || (!pin.isEmpty() && !pin.matches("[A-Za-z0-9_-]{1,80}"))) {
            throw new IOException("请填写有效的 TheTVDB API Key 和所需的订阅 PIN");
        }
        try {
            JSONObject body = new JSONObject().put("apikey", apiKey);
            if (!pin.isEmpty()) body.put("pin", pin);
            session = text(object(json(request(API + "/login", body.toString(), "", JSON_LIMIT)), "data"), "token");
        } catch (JSONException invalid) {
            throw new IOException("无法读取 TheTVDB 登录结果", invalid);
        }
        if (!session.matches("[A-Za-z0-9_.~-]{8,8192}")) {
            session = "";
            throw new IOException("TheTVDB 未返回有效的会话令牌");
        }
    }

    private static JSONObject json(byte[] bytes) throws IOException {
        try {
            JSONObject response = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!"success".equals(response.optString("status"))) throw new IOException("TheTVDB 返回了错误结果");
            return response;
        } catch (JSONException invalid) {
            throw new IOException("TheTVDB 返回的资料无法读取", invalid);
        }
    }

    private byte[] request(String address, String payload, String token, int limit) throws IOException {
        long started = System.nanoTime();
        checkRunning();
        boolean api = address.startsWith(API + "/");
        HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
        try {
            synchronized (active) {
                checkRunning();
                active.add(connection);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(9_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", "NFOForAndroid/1.12.0");
            if (api && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (api && payload != null) {
                byte[] body = payload.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            int code = connection.getResponseCode();
            DebugLog.http(connection.getURL().getHost(), code, started);
            if (code != 200) {
                if (api) {
                    String hint = code == 401 || code == 403 ? "，请检查 API Key 或订阅 PIN" : "";
                    throw new HttpError(code, "TheTVDB API 请求失败（HTTP " + code + "）" + hint);
                }
                throw new IOException("TheTVDB 图片请求失败（HTTP " + code + "）");
            }
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384];
                for (int count; (count = in.read(buffer)) != -1;) {
                    checkRunning();
                    if (count > limit - out.size()) throw new IOException("TheTVDB 响应超过读取上限");
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            }
        } catch (java.net.SocketTimeoutException timeout) {
            DebugLog.failure("http:" + connection.getURL().getHost(), timeout);
            checkRunning();
            throw new IOException((api ? "TheTVDB API" : "TheTVDB 图片") + "请求超时", timeout);
        } catch (java.net.UnknownHostException dns) {
            DebugLog.failure("http:" + connection.getURL().getHost(), dns);
            checkRunning();
            throw new IOException("无法解析 TheTVDB 服务器域名", dns);
        } catch (IOException failure) {
            DebugLog.failure("http:" + connection.getURL().getHost(), failure);
            throw failure;
        } finally {
            synchronized (active) { active.remove(connection); }
            connection.disconnect();
        }
    }

    private void checkRunning() throws InterruptedIOException {
        ScrapeEngine.checkCancelled();
        if (cancelled) throw new InterruptedIOException("操作已停止");
    }
}
