package org.kodiscraper.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class SubdlSubtitleSource implements SubtitleSource {
    private static final String API_HOST = "api.subdl.com";
    private static final String CDN_HOST = "dl.subdl.com";
    private static final String API = "https://" + API_HOST;
    private static final String CDN = "https://" + CDN_HOST;
    private static final int SIMPLIFIED = 1;
    private static final int TRADITIONAL = 2;
    private static final int BOTH = SIMPLIFIED | TRADITIONAL;
    private static final int MAX_ARCHIVE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 12 * 1024 * 1024;
    private static final int MAX_ENTRIES = 48;
    private static final int MAX_ATTEMPTS = 6;
    private static final Pattern API_KEY = Pattern.compile("[!-~]{1,256}");
    private static final Pattern IMDB_ID = Pattern.compile("tt[0-9]{5,12}");
    private static final Pattern DOWNLOAD_ID = Pattern.compile("[a-zA-Z0-9_-]{1,100}");
    private static final Pattern ZIP_PATH = Pattern.compile("/subtitle/[a-zA-Z0-9_-]{1,130}\\.zip");
    private static final Pattern FILE_PATH = Pattern.compile("/subtitle/[a-zA-Z0-9_-]{1,100}/[a-zA-Z0-9_-]{1,100}");
    private static final Pattern EXTENSION = Pattern.compile("(?i)\\.(srt|ass|ssa|vtt)$");
    private static final Pattern VIDEO_EXTENSION = Pattern.compile("(?i)\\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|zip|srt|ass|ssa|vtt)$");
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WEB_SOURCE = Pattern.compile("web[ ._-]*(?:dl|rip)");
    private static final Pattern BLURAY_SOURCE = Pattern.compile("blu[ ._-]*ray|uhd[ ._-]*bd|bd[ ._-]*rip|br[ ._-]*rip|remux");
    private static final Pattern GROUP = Pattern.compile("-([a-z0-9]{2,20})(?:[ ._-]+(?:chs|cht|sc|tc|zh|hans|hant))?$");

    private final String key;

    SubdlSubtitleSource(String key) {
        this.key = key == null ? "" : key.trim();
    }

    @Override
    public List<SubtitleFiles.Track> find(SubtitleQuery query) throws IOException {
        if (!API_KEY.matcher(key).matches()) {
            throw new IOException("SubDL API 密钥格式无效");
        }
        String imdb = query.imdbId == null ? "" : query.imdbId.toLowerCase(Locale.ROOT);
        if (query.tmdbId <= 0 && !IMDB_ID.matcher(imdb).matches() && query.year <= 0) {
            return Collections.emptyList();
        }

        List<Candidate> candidates = new ArrayList<>();
        try {
            candidates.addAll(search(query, imdb, BOTH, false));
        } catch (SubtitleHttp.HttpException failed) {
            if (failed.statusCode != 400 && failed.statusCode != 404) throw failed;
        }
        int missing = BOTH & ~languages(candidates);
        if (missing != 0) {
            try {
                candidates.addAll(search(query, imdb, missing, true));
            } catch (IOException failed) {
                checkCancelled(failed);
                if (candidates.isEmpty()) throw failed;
            }
        }
        candidates.sort(Comparator.comparingInt((Candidate item) -> item.score).reversed());
        prioritizeSecondLanguage(candidates);
        List<SubtitleFiles.Track> tracks = new ArrayList<>();
        Set<String> used = new HashSet<>();
        IOException downloadFailure = null;
        int attempts = 0;
        for (Candidate candidate : candidates) {
            if (tracks.size() >= SubtitleSource.MAX_TRACKS || attempts >= MAX_ATTEMPTS) break;
            if (!used.add(candidate.language + "/" + candidate.identity())) continue;
            attempts++;
            try {
                tracks.add(download(candidate, query.basename));
            } catch (IOException failed) {
                checkCancelled(failed);
                if (failed instanceof SubtitleHttp.HttpException
                        && ((SubtitleHttp.HttpException) failed).statusCode == 429) {
                    if (tracks.isEmpty()) throw failed;
                    break;
                }
                if (downloadFailure == null) downloadFailure = failed;
            }
        }
        if (tracks.isEmpty() && downloadFailure != null) throw downloadFailure;
        return tracks;
    }

    private List<Candidate> search(SubtitleQuery query, String imdb, int languages, boolean legacy) throws IOException {
        boolean hasTmdb = query.tmdbId > 0;
        boolean hasImdb = IMDB_ID.matcher(imdb).matches();
        if (!hasTmdb && !hasImdb) return searchTitle(query, languages, legacy);
        List<Candidate> results = hasTmdb
                ? request(query, imdb, "tmdb_id=" + query.tmdbId, Lookup.TMDB, "", languages, legacy)
                : Collections.emptyList();
        if (results.isEmpty() && hasImdb) {
            return request(query, imdb, "imdb_id=" + imdb, Lookup.IMDB, "", languages, legacy);
        }
        return results;
    }

    private List<Candidate> searchTitle(SubtitleQuery query, int languages, boolean legacy) throws IOException {
        Set<String> requestedTitles = new HashSet<>();
        for (String title : query.titles) {
            String normalized = normalizedTitle(title);
            if (normalized.isEmpty() || title.length() > 220 || !requestedTitles.add(normalized)) continue;
            List<Candidate> results = request(query, "", "film_name=" + SubtitleHttp.encode(title)
                            + "&year=" + query.year, Lookup.TITLE, normalized, languages, legacy);
            if (!results.isEmpty()) return results;
            if (requestedTitles.size() >= 2) break;
        }
        return Collections.emptyList();
    }

    private List<Candidate> request(SubtitleQuery query, String imdb, String search, Lookup lookup,
                                    String title, int requested, boolean legacy) throws IOException {
        String codes = languageCodes(requested, legacy);
        String common = search + "&type=movie&languages=" + SubtitleHttp.encode(codes)
                + "&unpack=1&releases=1&subs_per_page=30";
        JSONObject payload;
        if (legacy) {
            try {
                payload = SubtitleHttp.json(API + "/api/v1/subtitles?" + common
                        + "&client=custom_integration&api_key=" + SubtitleHttp.encode(key),
                        null, null, API_HOST);
            } catch (SubtitleHttp.HttpException failed) {
                throw failed;
            } catch (IOException failed) {
                checkCancelled(failed);
                // A platform exception can echo the URL, including the legacy query credential.
                throw new IOException("SubDL 旧版字幕接口连接失败");
            }
        } else {
            payload = SubtitleHttp.json(API + "/api/v2/subtitles/search?" + common,
                    "Authorization", "Bearer " + key, API_HOST);
        }
        if (payload.has("status") && !payload.optBoolean("status")) {
            String error = text(payload, "error").toLowerCase(Locale.ROOT);
            if (error.contains("not found") || error.contains("no subtitle") || error.contains("no result")) {
                return Collections.emptyList();
            }
            throw new IOException("SubDL 搜索失败，请检查字幕服务和 API 密钥");
        }

        if (!verifyTitle(payload.optJSONArray("results"), lookup, query, imdb, title)) {
            return Collections.emptyList();
        }

        JSONArray rows = payload.optJSONArray("subtitles");
        List<Candidate> found = new ArrayList<>();
        if (rows == null) return found;
        for (int index = 0; index < rows.length() && index < 60; index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null || row.optBoolean("full_season")) continue;
            int language = language(row);
            if ((language & requested) == 0) continue;
            String nId = text(row, "n_id");
            if (!DOWNLOAD_ID.matcher(nId).matches()) nId = "";
            String archive = downloadPath(text(row, "url"), ZIP_PATH);
            DirectFile direct = bestFile(row.optJSONArray("unpack_files"), query.basename, language);
            if (archive.isEmpty() && (legacy || nId.isEmpty()) && direct == null) continue;
            String release = text(row, "release_name");
            int score = Math.max(releaseScore(query.basename, release),
                    releaseScore(query.basename, text(row, "name")));
            JSONArray releases = row.optJSONArray("releases");
            if (releases != null) {
                for (int releaseIndex = 0; releaseIndex < releases.length() && releaseIndex < 20; releaseIndex++) {
                    score = Math.max(score, releaseScore(query.basename, releases.optString(releaseIndex)));
                }
            }
            if (direct != null) score = Math.max(score, direct.score) + 3;
            if (row.optBoolean("hi")) score -= 10;
            found.add(new Candidate(language, score, legacy ? "" : nId, archive, direct));
        }
        return found;
    }

    private enum Lookup { TMDB, IMDB, TITLE }

    private static boolean verifyTitle(JSONArray results, Lookup lookup, SubtitleQuery query, String imdb,
                                       String title) {
        JSONObject first = results == null ? null : results.optJSONObject(0);
        if (first == null) return false;
        String type = text(first, "type");
        if (!type.isEmpty() && !"movie".equalsIgnoreCase(type)) return false;
        if (lookup == Lookup.TITLE) {
            if (!exactTitleAndYear(first, title, query.year)) return false;
            String identity = resultId(first);
            for (int index = 1; index < results.length(); index++) {
                JSONObject alternative = results.optJSONObject(index);
                if (exactTitleAndYear(alternative, title, query.year)
                        && (identity.isEmpty() || !identity.equals(resultId(alternative)))) {
                    return false;
                }
            }
            return true;
        }
        int returnedTmdb = first.optInt("tmdb_id");
        String returnedImdb = text(first, "imdb_id").toLowerCase(Locale.ROOT);
        if (lookup == Lookup.TMDB ? returnedTmdb != query.tmdbId : !imdb.equals(returnedImdb)) {
            return false;
        }
        return !(query.tmdbId > 0 && returnedTmdb > 0 && query.tmdbId != returnedTmdb)
                && !(IMDB_ID.matcher(imdb).matches() && IMDB_ID.matcher(returnedImdb).matches()
                && !imdb.equals(returnedImdb));
    }

    private static boolean exactTitleAndYear(JSONObject result, String title, int year) {
        if (result == null || !"movie".equalsIgnoreCase(text(result, "type")) || result.optInt("year") != year) {
            return false;
        }
        return title.equals(normalizedTitle(text(result, "name")))
                || title.equals(normalizedTitle(text(result, "original_name")));
    }

    private static String resultId(JSONObject result) {
        int tmdb = result.optInt("tmdb_id");
        if (tmdb > 0) return "tmdb:" + tmdb;
        String imdb = text(result, "imdb_id").toLowerCase(Locale.ROOT);
        if (IMDB_ID.matcher(imdb).matches()) return "imdb:" + imdb;
        String subdl = text(result, "sd_id");
        return subdl.isEmpty() ? "" : "subdl:" + subdl;
    }

    private static String normalizedTitle(String title) {
        return WORD_SEPARATOR.matcher(Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll(" ").trim();
    }

    private SubtitleFiles.Track download(Candidate candidate, String basename) throws IOException {
        if (candidate.direct != null) {
            try {
                byte[] data = SubtitleHttp.get(CDN + candidate.direct.path, null, null,
                        SubtitleFiles.MAX_BYTES, CDN_HOST);
                return SubtitleFiles.validate(candidate.direct.filename, data, candidate.language == TRADITIONAL);
            } catch (IOException failed) {
                checkCancelled(failed);
                if (candidate.nId.isEmpty() && candidate.archive.isEmpty()) throw failed;
                if (failed instanceof SubtitleHttp.HttpException
                        && ((SubtitleHttp.HttpException) failed).statusCode == 429) throw failed;
            }
        }
        byte[] archive;
        if (!candidate.nId.isEmpty()) {
            try {
                archive = SubtitleHttp.get(API + "/api/v2/subtitles/" + candidate.nId + "/download?format=zip",
                        "Authorization", "Bearer " + key, MAX_ARCHIVE_BYTES, API_HOST);
            } catch (IOException failed) {
                checkCancelled(failed);
                if (candidate.archive.isEmpty() || (failed instanceof SubtitleHttp.HttpException
                        && ((SubtitleHttp.HttpException) failed).statusCode == 429)) throw failed;
                archive = SubtitleHttp.get(CDN + candidate.archive, null, null, MAX_ARCHIVE_BYTES, CDN_HOST);
            }
        } else {
            archive = SubtitleHttp.get(CDN + candidate.archive, null, null, MAX_ARCHIVE_BYTES, CDN_HOST);
        }
        try {
            return unpack(archive, basename, candidate.language, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException badNameEncoding) {
            Charset codePage = Charset.forName(candidate.language == TRADITIONAL ? "Big5" : "GB18030");
            try {
                return unpack(archive, basename, candidate.language, codePage);
            } catch (IllegalArgumentException invalidName) {
                throw new IOException("SubDL 压缩包文件名无效");
            }
        }
    }

    private static SubtitleFiles.Track unpack(byte[] archive, String basename, int language, Charset charset)
            throws IOException {
        SubtitleFiles.Track best = null;
        int bestScore = Integer.MIN_VALUE;
        int expanded = 0;
        int count = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), charset)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES || entry.getSize() > MAX_EXPANDED_BYTES - expanded) {
                    throw new IOException("SubDL 字幕压缩包超出大小限制");
                }
                String name = entry.getName();
                int fileLanguage = language(name);
                boolean supported = !entry.isDirectory() && SubtitleFiles.supportsFilename(name)
                        && (fileLanguage == 0 || fileLanguage == language);
                ByteArrayOutputStream content = supported ? new ByteArrayOutputStream() : null;
                int bytesRead;
                while ((bytesRead = zip.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消");
                    expanded += bytesRead;
                    if (expanded > MAX_EXPANDED_BYTES) {
                        throw new IOException("SubDL 字幕压缩包超出大小限制");
                    }
                    if (content != null) {
                        if (content.size() + bytesRead <= SubtitleFiles.MAX_BYTES) {
                            content.write(buffer, 0, bytesRead);
                        } else {
                            content = null;
                        }
                    }
                }
                if (content != null) {
                    int score = releaseScore(basename, filename(name)) + (fileLanguage == language ? 25 : 0);
                    if (score > bestScore) {
                        try {
                            best = SubtitleFiles.validate(filename(name), content.toByteArray(), language == TRADITIONAL);
                            bestScore = score;
                        } catch (IOException invalidSubtitle) {
                            checkCancelled(invalidSubtitle);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        if (best == null) throw new IOException("SubDL 压缩包没有 Kodi 可用的字幕");
        return best;
    }

    private static DirectFile bestFile(JSONArray entries, String basename, int wantedLanguage) {
        DirectFile best = null;
        if (entries == null) return null;
        for (int index = 0; index < entries.length() && index < MAX_ENTRIES; index++) {
            JSONObject item = entries.optJSONObject(index);
            if (item == null || item.optLong("size") > SubtitleFiles.MAX_BYTES) continue;
            String path = downloadPath(text(item, "url"), FILE_PATH);
            String filename = filename(text(item, "name"));
            String format = text(item, "format").toLowerCase(Locale.ROOT);
            if (!SubtitleFiles.supportsFilename(filename) && EXTENSION.matcher("." + format).matches()) {
                filename += "." + format;
            }
            int itemLanguage = language(item);
            int fileLanguage = language(filename);
            if (path.isEmpty() || !SubtitleFiles.supportsFilename(filename)
                    || (fileLanguage != 0 && fileLanguage != wantedLanguage)
                    || (fileLanguage == 0 && itemLanguage != 0 && itemLanguage != wantedLanguage)) {
                continue;
            }
            int score = Math.max(releaseScore(basename, filename),
                    releaseScore(basename, text(item, "release_name")));
            if (fileLanguage == wantedLanguage) score += 25;
            if (item.optBoolean("hi")) score -= 10;
            if (best == null || score > best.score) best = new DirectFile(path, filename, score);
        }
        return best;
    }

    private static String downloadPath(String value, Pattern allowedPath) {
        if (allowedPath.matcher(value).matches()) return value;
        try {
            URI uri = new URI(value);
            String path = uri.getRawPath();
            if ("https".equalsIgnoreCase(uri.getScheme()) && CDN_HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && path != null && allowedPath.matcher(path).matches()) {
                return path;
            }
        } catch (URISyntaxException ignored) {
            return "";
        }
        return "";
    }

    private static int language(JSONObject object) {
        for (String field : new String[]{"language", "lang", "language_code", "name"}) {
            int match = language(text(object, field));
            if (match != 0) return match;
        }
        return 0;
    }

    private static int language(String value) {
        String code = value.toLowerCase(Locale.ROOT).replace('_', '-').trim();
        if (code.equals("zh-bg") || code.equals("zh-hant") || code.equals("zh-tw")
                || code.equals("zh-hk") || code.equals("big-5-code")
                || (code.contains("chinese") && code.contains("traditional"))
                || code.contains("繁體") || code.contains("繁体")
                || languageSuffix(code, "cht", "hant", "zh-hant", "zh-tw", "zh-hk", "big5")) {
            return TRADITIONAL;
        }
        if (code.equals("zh") || code.equals("zho") || code.equals("chi") || code.equals("zh-cn")
                || code.equals("zh-hans") || code.equals("zh-sg") || code.equals("chinese")
                || code.equals("chinese bg code") || code.equals("chinese gb code")
                || (code.contains("chinese") && code.contains("simplified"))
                || code.contains("简体") || code.contains("簡體")
                || languageSuffix(code, "chs", "hans", "zh-hans", "zh-cn", "zh-sg", "gbk")) {
            return SIMPLIFIED;
        }
        return 0;
    }

    private static boolean languageSuffix(String value, String... codes) {
        String stem = EXTENSION.matcher(value).replaceFirst("");
        for (String code : codes) {
            if (stem.equals(code) || stem.endsWith("." + code) || stem.endsWith("-" + code)) return true;
        }
        return false;
    }

    private static int releaseScore(String basename, String candidate) {
        String local = releaseStem(basename);
        String remote = releaseStem(candidate);
        if (local.isEmpty() || remote.isEmpty()) return 0;
        String localTokens = WORD_SEPARATOR.matcher(local).replaceAll(" ").trim();
        String remoteTokens = WORD_SEPARATOR.matcher(remote).replaceAll(" ").trim();
        if (localTokens.equals(remoteTokens)) return 1000;
        Set<String> localWords = new HashSet<>();
        Collections.addAll(localWords, localTokens.split(" +"));
        Set<String> remoteWords = new HashSet<>();
        Collections.addAll(remoteWords, remoteTokens.split(" +"));
        int total = localWords.size() + remoteWords.size();
        localWords.retainAll(remoteWords);
        int score = 100 * localWords.size() / Math.max(1, total);

        int localSource = source(local);
        int remoteSource = source(remote);
        if (localSource > 0 && remoteSource > 0) score += localSource == remoteSource ? 30 : -40;
        Matcher localGroup = GROUP.matcher(local);
        Matcher remoteGroup = GROUP.matcher(remote);
        if (localSource > 0 && remoteSource > 0 && localGroup.find() && remoteGroup.find()
                && localGroup.group(1).equals(remoteGroup.group(1))) score += 45;
        if (edition(local) != edition(remote)) score -= 35;
        return score;
    }

    private static int edition(String release) {
        if (release.matches(".*director[ ._'-]*s?[ ._-]*cut.*")) return 1;
        if (release.matches(".*(?:extended|unrated).*")) return 2;
        if (release.matches(".*theatrical.*")) return 3;
        return 0;
    }

    private static int source(String release) {
        if (BLURAY_SOURCE.matcher(release).find()) return 1;
        if (WEB_SOURCE.matcher(release).find()) return 2;
        if (release.contains("hdtv")) return 3;
        if (release.contains("dvd")) return 4;
        return 0;
    }

    private static String releaseStem(String name) {
        return VIDEO_EXTENSION.matcher(Normalizer.normalize(name == null ? "" : name,
                Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim()).replaceFirst("");
    }

    private static String languageCodes(int languages, boolean legacy) {
        String simplified = legacy ? "ZH" : "zh-hans";
        String traditional = legacy ? "ZH_BG" : "zh-hant";
        return languages == BOTH ? simplified + "," + traditional
                : languages == SIMPLIFIED ? simplified : traditional;
    }

    private static int languages(List<Candidate> candidates) {
        int languages = 0;
        for (Candidate candidate : candidates) languages |= candidate.language;
        return languages;
    }

    private static void prioritizeSecondLanguage(List<Candidate> candidates) {
        if (candidates.size() < 2) return;
        int first = candidates.get(0).language;
        for (int index = 1; index < candidates.size(); index++) {
            if (candidates.get(index).language != first) {
                candidates.add(1, candidates.remove(index));
                return;
            }
        }
    }

    private static String text(JSONObject object, String key) {
        return object == null || object.isNull(key) ? "" : object.optString(key, "").trim();
    }

    private static String filename(String raw) {
        return raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
    }

    private static void checkCancelled(IOException failed) throws IOException {
        if (failed instanceof InterruptedIOException && Thread.currentThread().isInterrupted()) throw failed;
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消");
    }

    private static final class Candidate {
        final int language;
        final int score;
        final String nId;
        final String archive;
        final DirectFile direct;

        Candidate(int language, int score, String nId, String archive, DirectFile direct) {
            this.language = language;
            this.score = score;
            this.nId = nId;
            this.archive = archive;
            this.direct = direct;
        }

        String identity() {
            if (!archive.isEmpty()) return archive;
            if (!nId.isEmpty()) return nId;
            return direct.path;
        }
    }

    private static final class DirectFile {
        final String path;
        final String filename;
        final int score;

        DirectFile(String path, String filename, int score) {
            this.path = path;
            this.filename = filename;
            this.score = score;
        }
    }
}
