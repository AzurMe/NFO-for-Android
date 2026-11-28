package org.kodiscraper.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

final class AssrtSubtitleSource implements SubtitleSource {
    private static final String API_HOST = "api.assrt.net";
    private static final String BACKUP_API_HOST = "api.makedie.me";
    private static final long API_INTERVAL_NANOS = 3_050_000_000L;
    private static final long BACKUP_COOLDOWN_NANOS = 5 * 60 * 1_000_000_000L;
    private static final Object API_PACING = new Object();
    private static long nextApiStart = System.nanoTime();
    private static volatile long backupRetryAfter = System.nanoTime();
    private static final int MAX_SEARCHES = 3;
    private static final int MAX_DETAILS = 5;
    private static final int MAX_DOWNLOADS = 9;
    private static final int FOREIGN = -1;
    private static final int UNKNOWN = 0;
    private static final int CHINESE = 1;
    private static final int TRADITIONAL = 2;
    private static final int SIMPLIFIED = 3;
    private static final int BILINGUAL = 4;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9]{32}");
    private static final Pattern FILE_EXTENSION = Pattern.compile(
            "(?i)\\.(?:srt|ass|ssa|vtt|sub|smi|sami|mkv|mp4|avi|mov|wmv|m2ts|rar|zip|7z)$");
    private static final Pattern RELEASE = Pattern.compile(
            "(?i)(?:^|[ ._(-])(?:[0-9]{3,4}[pi]|blu[ ._-]?ray|b[dr]rip|remux|uhd|"
                    + "web[ ._-]?(?:dl|rip)|hdtv|dvd[ ._-]?rip|x26[45]|hevc|av1)(?=$|[ ._)-])");
    private static final Pattern EPISODE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])S[0-9]{1,2}[ ._-]*E[0-9]{1,3}(?![0-9])|第[0-9一二三四五六七八九十]+[季集话話]");
    private static final Pattern RELEASE_YEAR = Pattern.compile(
            "(?<![0-9])(?:18[89][0-9]|19[0-9]{2}|20[0-9]{2})(?![0-9])");
    private static final String LABEL_SEPARATOR = "[ ._()\\[\\]{}【】·-]";
    private static final Pattern BILINGUAL_LABEL = trailingLabel("(?:chs[&+_-]eng|cht[&+_-]eng|"
            + "chi[&+_-]eng|bilingual|dual[ ._-]?language|中英(?:双语|雙語)?|双语|雙語)");
    private static final Pattern SIMPLIFIED_LABEL = trailingLabel("(?:chs|zho[ ._-]?hans|"
            + "zh[ ._-]?(?:cn|hans)|simplified(?:[ ._-]?chinese)?|简体(?:中文)?|简中|簡體|簡中)");
    private static final Pattern TRADITIONAL_LABEL = trailingLabel("(?:cht|zho[ ._-]?hant|"
            + "zh[ ._-]?(?:tw|hk|hant)|traditional(?:[ ._-]?chinese)?|繁体(?:中文)?|繁中|繁體)");
    private static final Pattern CHINESE_LABEL = trailingLabel("(?:chi|zho|zh|chinese|中文)");
    private static final Pattern FOREIGN_LABEL = trailingLabel("(?:eng|en|english|jpn|ja|japanese|"
            + "kor|ko|korean|fre|fra|french|spa|spanish|英文|英语|英語|日文|日语|日語|韩文|韩语|韓文|韓語)");
    private static final Pattern[] LANGUAGE_LABELS = {
            BILINGUAL_LABEL, SIMPLIFIED_LABEL, TRADITIONAL_LABEL, CHINESE_LABEL, FOREIGN_LABEL
    };

    private final String token;
    private String activeApiHost = API_HOST;

    AssrtSubtitleSource(String token) {
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public List<SubtitleFiles.Track> find(SubtitleQuery query) throws IOException {
        if (!TOKEN.matcher(token).matches()) {
            throw new IOException("伪射手 Token 格式无效，请填写 32 位字母或数字");
        }
        MatchContext context = new MatchContext(query);
        if (context.titles.isEmpty()) return new ArrayList<>();

        Map<Integer, Hit> hits = new LinkedHashMap<>();
        IOException searchFailure = null;
        int searches = 0;
        for (Search search : searchQueries(query, context)) {
            checkRunning();
            String url = "search?q=" + SubtitleHttp.encode(search.text) + "&cnt=12&filelist=1";
            if (search.filename) url += "&is_file=1";
            JSONArray entries;
            try {
                entries = apiResults(url, "搜索");
            } catch (IOException failure) {
                checkRunning();
                if (hits.isEmpty() || !hasTransportFailure(failure)) throw failure;
                searchFailure = failure;
                break;
            }
            searches++;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                int id = entry.optInt("id");
                Evidence evidence = evidence(context, entry);
                if (id <= 0 || !evidence.matches || evidence.conflict) continue;
                int language = providerLanguage(entry);
                if (language <= UNKNOWN) {
                    int listed = listedLanguage(entry.optJSONArray("filelist"));
                    if (listed > UNKNOWN) language = listed;
                }
                if (language == FOREIGN) continue;
                Hit hit = new Hit(id, evidence, language, entry.optInt("vote_score"));
                hits.merge(id, hit, (old, fresh) -> old.score() >= fresh.score() ? old : fresh);
            }
            long enough = hits.values().stream().filter(hit -> hit.language > UNKNOWN
                    && hit.evidence.sufficient(context)).count();
            boolean sameRelease = hits.values().stream().anyMatch(hit -> hit.language > UNKNOWN
                    && hit.evidence.release);
            if (searches >= MAX_SEARCHES || enough >= MAX_TRACKS || sameRelease) break;
        }

        List<Hit> ranked = new ArrayList<>(hits.values());
        ranked.sort(Comparator.comparingInt(Hit::score).reversed());
        List<SubtitleFiles.Track> tracks = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();
        Set<String> usedLinks = new HashSet<>();
        IOException downloadFailure = null;
        int downloads = 0;
        for (int i = 0; i < Math.min(MAX_DETAILS, ranked.size()); i++) {
            checkRunning();
            Hit hit = ranked.get(i);
            JSONArray entries;
            try {
                entries = apiResults("detail?id=" + hit.id, "获取字幕详情");
            } catch (IOException problem) {
                checkRunning();
                if (!tracks.isEmpty()) return tracks;
                throw problem;
            }
            JSONObject detail = findDetail(entries, hit.id);
            if (detail == null) continue;
            Evidence details = evidence(context, detail);
            if (details.conflict) continue;
            int language = providerLanguage(detail);
            if (language == UNKNOWN) language = hit.language;

            List<Download> files = downloads(context, detail, hit.evidence, details, language);
            for (Download file : files) {
                checkRunning();
                if (tracks.size() >= MAX_TRACKS || downloads >= MAX_DOWNLOADS) break;
                if (!usedLinks.add(file.url)) continue;
                downloads++;
                try {
                    byte[] bytes = downloadFile(file.url);
                    if (contents.stream().anyMatch(existing -> Arrays.equals(existing, bytes))) continue;
                    SubtitleFiles.Track track = SubtitleFiles.validate(file.name, bytes);
                    tracks.add(track);
                    contents.add(bytes);
                } catch (IOException problem) {
                    checkRunning();
                    if (downloadFailure == null || problem instanceof SubtitleHttp.ConnectionException) {
                        downloadFailure = problem;
                    }
                }
            }
            if (tracks.size() >= MAX_TRACKS || downloads >= MAX_DOWNLOADS) break;
        }
        if (tracks.isEmpty() && downloadFailure != null) {
            String reason = downloadFailure instanceof SubtitleHttp.ConnectionException
                    || downloadFailure instanceof SubtitleHttp.HttpException
                    ? downloadFailure.getMessage() : "无法读取或校验字幕文件";
            throw new IOException("字幕文件下载失败 · " + reason, downloadFailure);
        }
        if (tracks.isEmpty() && searchFailure != null) throw searchFailure;
        return tracks;
    }

    private static byte[] downloadFile(String url) throws IOException {
        try {
            return SubtitleHttp.getFresh(url, null, null, SubtitleFiles.MAX_BYTES, "assrt.net", "makedie.me");
        } catch (IOException failure) {
            checkRunning();
            if (!SubtitleHttp.isRetryableConnection(failure)) throw failure;
            return SubtitleHttp.getFresh(url, null, null, SubtitleFiles.MAX_BYTES, "assrt.net", "makedie.me");
        }
    }

    private static List<Search> searchQueries(SubtitleQuery query, MatchContext context) {
        List<Search> searches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (context.releaseName) {
            addSearch(searches, seen, query.basename, true);
        }
        for (String title : query.titles) {
            addSearch(searches, seen, query.year > 0 ? title + " " + query.year : title, false);
        }
        if (query.year > 0) {
            for (String title : query.titles) addSearch(searches, seen, title, false);
        }
        return searches;
    }

    private static void addSearch(List<Search> searches, Set<String> seen, String text, boolean filename) {
        if (text == null || searches.size() >= MAX_SEARCHES) return;
        String query = text.trim();
        int characters = query.codePointCount(0, query.length());
        if (characters >= 3 && characters <= 240 && seen.add(normalized(query))) {
            searches.add(new Search(query, filename));
        }
    }

    private JSONArray apiResults(String path, String step) throws IOException {
        JSONObject response;
        try {
            response = requestWithFallback("sub/" + path);
        } catch (SubtitleHttp.HttpException error) {
            if (error.apiStatus == 101 || error.apiStatus == 20900) return new JSONArray();
            throw new IOException(step + "失败 · " + apiErrorMessage(error.apiStatus, error.statusCode), error);
        } catch (IOException error) {
            checkRunning();
            throw new IOException(step + "失败 · " + error.getMessage(), error);
        }
        int status = response.optInt("status", -1);
        if (status == 101 || status == 20900) return new JSONArray();
        if (status != 0) throw new IOException(step + "失败 · " + apiErrorMessage(status, 200));
        JSONObject result = response.optJSONObject("sub");
        JSONArray entries = result == null ? null : result.optJSONArray("subs");
        return entries == null ? new JSONArray() : entries;
    }

    private JSONObject requestWithFallback(String path) throws IOException {
        String firstHost = backupCoolingDown() ? API_HOST : activeApiHost;
        try {
            return requestPreferredApi(firstHost, path, token);
        } catch (IOException firstError) {
            checkRunning();
            if (!rememberBackupFailure(firstHost, firstError) && !canFailOver(firstError)) {
                throw firstError;
            }
            String secondHost = firstHost.equals(API_HOST) ? BACKUP_API_HOST : API_HOST;
            if (secondHost.equals(BACKUP_API_HOST) && backupCoolingDown()) {
                throw new IOException(firstHost + " · " + apiFailureReason(firstError) + "；" + secondHost
                        + " · 线路故障后暂缓重连", firstError);
            }
            JSONObject result;
            try {
                result = requestPreferredApi(secondHost, path, token);
            } catch (IOException secondError) {
                checkRunning();
                rememberBackupFailure(secondHost, secondError);
                if (secondError instanceof SubtitleHttp.HttpException response
                        && (response.apiStatus == 101 || response.apiStatus == 20900
                            || response.apiStatus == 30900 || response.statusCode == 429
                            || (secondHost.equals(API_HOST) && !canFailOver(response)))) {
                    throw secondError;
                }
                throw bothRoutesFailed(firstHost, firstError, secondHost, secondError);
            }
            int status = result.optInt("status", -1);
            if (status == 0) {
                activeApiHost = secondHost;
                markBackupReachable(secondHost);
            } else if (secondHost.equals(BACKUP_API_HOST) && status != 101 && status != 20900 && status != 30900) {
                throw bothRoutesFailed(firstHost, firstError, secondHost, new IOException(apiErrorMessage(status, 200)));
            }
            return result;
        }
    }

    private JSONObject requestPreferredApi(String host, String path, String credential) throws IOException {
        try {
            return requestApi(host, path, credential);
        } catch (IOException failure) {
            checkRunning();
            if (!host.equals(API_HOST)
                    || (!SubtitleHttp.isRetryableConnection(failure) && !isGatewayFailure(failure))) {
                throw failure;
            }
            return requestApi(host, path, credential);
        }
    }

    private static IOException bothRoutesFailed(String firstHost, IOException firstError,
                                                String secondHost, IOException secondError) {
        IOException result = new IOException(firstHost + " · " + apiFailureReason(firstError) + "；"
                + secondHost + " · " + apiFailureReason(secondError), firstError);
        result.addSuppressed(secondError);
        return result;
    }

    private static String apiFailureReason(IOException failure) {
        return failure instanceof SubtitleHttp.HttpException response
                ? apiErrorMessage(response.apiStatus, response.statusCode) : failure.getMessage();
    }

    private boolean rememberBackupFailure(String host, IOException failure) {
        if (!host.equals(BACKUP_API_HOST)
                || (!canFailOver(failure) && !(failure.getCause() instanceof SSLException))) {
            return false;
        }
        backupRetryAfter = System.nanoTime() + BACKUP_COOLDOWN_NANOS;
        activeApiHost = API_HOST;
        return true;
    }

    private static boolean backupCoolingDown() {
        return backupRetryAfter - System.nanoTime() > 0;
    }

    private static void markBackupReachable(String host) {
        if (host.equals(BACKUP_API_HOST)) backupRetryAfter = System.nanoTime();
    }

    private static boolean hasTransportFailure(IOException failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof IOException current && canFailOver(current)) return true;
        }
        return false;
    }

    private static boolean canFailOver(IOException failure) {
        return failure instanceof SubtitleHttp.ConnectionException || isGatewayFailure(failure);
    }

    private static boolean isGatewayFailure(IOException failure) {
        if (failure instanceof SubtitleHttp.HttpException) {
            SubtitleHttp.HttpException response = (SubtitleHttp.HttpException) failure;
            return response.apiStatus < 0
                    && (response.statusCode == 502 || response.statusCode == 503 || response.statusCode == 504);
        }
        return false;
    }

    private JSONObject requestApi(String host, String path, String credential) throws IOException {
        waitForQuota();
        String bearer = credential.isEmpty() ? null : "Bearer " + credential;
        return SubtitleHttp.jsonFresh("https://" + host + "/v1/" + path,
                bearer == null ? null : "Authorization", bearer, host);
    }

    String diagnose() throws IOException {
        String credential = TOKEN.matcher(token).matches() ? token : "";
        String heading = "伪射手 API · " + (credential.isEmpty()
                ? token.isEmpty() ? "未填写 Token" : "Token 格式无效"
                : "检测 Token");
        return heading + "\n" + probeHost(API_HOST, credential) + "\n" + probeHost(BACKUP_API_HOST, credential);
    }

    private String probeHost(String host, String credential) throws IOException {
        String result;
        try {
            JSONObject response = requestPreferredApi(host, "user/quota", credential);
            int status = response.optInt("status", -1);
            if (status >= 0) markBackupReachable(host);
            result = probeResult(status, 200, credential);
        } catch (SubtitleHttp.HttpException error) {
            if (!rememberBackupFailure(host, error) && error.apiStatus >= 0) markBackupReachable(host);
            result = probeResult(error.apiStatus, error.statusCode, credential);
        } catch (IOException error) {
            checkRunning();
            rememberBackupFailure(host, error);
            result = error.getMessage();
        }
        return host + " · " + result;
    }

    private static String probeResult(int status, int httpStatus, String credential) {
        String result;
        if (status == 0) {
            result = credential.isEmpty() ? "已连通" : "已连通，Token 有效";
        } else if (credential.isEmpty() && (status == 20001 || httpStatus == 401)) {
            result = "已连通，等待填写 Token";
        } else {
            result = apiErrorMessage(status, httpStatus);
        }
        return result + " · HTTP " + httpStatus;
    }

    private static String apiErrorMessage(int status, int httpStatus) {
        if (status == 30900 || httpStatus == 429) {
            return "请求达到配额，请稍后再试";
        }
        if (status == 20001 || status == 1 || httpStatus == 401) {
            return "Token 验证失败";
        }
        if (httpStatus == 403) {
            return "服务器拒绝访问";
        }
        if (status >= 30000 || httpStatus >= 500) {
            return "服务暂时不可用（HTTP " + httpStatus + "）";
        }
        if (status == -1 && httpStatus == 200) return "返回了无法识别的数据";
        return "API 请求失败（HTTP " + httpStatus + (status > 0 ? "，状态 " + status : "") + "）";
    }

    private static void waitForQuota() throws InterruptedIOException {
        while (true) {
            checkRunning();
            long remaining;
            synchronized (API_PACING) {
                long now = System.nanoTime();
                remaining = nextApiStart - now;
                if (remaining <= 0) {
                    nextApiStart = now + API_INTERVAL_NANOS;
                    return;
                }
            }
            try {
                Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                checkRunning();
            }
        }
    }

    private static JSONObject findDetail(JSONArray entries, int id) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject candidate = entries.optJSONObject(i);
            if (candidate != null && candidate.optInt("id", id) == id) return candidate;
        }
        return null;
    }

    private static List<Download> downloads(MatchContext context, JSONObject detail,
                                           Evidence summary, Evidence details, int language) {
        List<Download> result = new ArrayList<>();
        JSONArray files = detail.optJSONArray("filelist");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                addDownload(result, context, summary, details, language,
                        text(file, "f"), text(file, "url"));
            }
        }
        addDownload(result, context, summary, details, language, text(detail, "filename"), text(detail, "url"));
        result.sort(Comparator.comparingInt(Download::score).reversed());
        return result;
    }

    private static void addDownload(List<Download> files, MatchContext context,
                                    Evidence summary, Evidence details, int language,
                                    String filename, String address) {
        String name = fileName(filename);
        if (!SubtitleFiles.supportsFilename(name)) return;
        Evidence file = evidence(context, name);
        if (file.conflict || (!summary.sufficient(context) && !details.sufficient(context)
                && !file.sufficient(context))) return;

        int localLanguage = filenameLanguage(name);
        if (localLanguage == FOREIGN) return;
        int selectedLanguage = localLanguage > UNKNOWN ? localLanguage : language;
        if (selectedLanguage <= UNKNOWN) return;
        String url = secureDownload(address);
        if (url.isEmpty()) return;
        int score = selectedLanguage * 20 + (file.release ? 150 : 0) + (file.sameYear ? 40 : 0)
                + (file.matches ? 20 : 0) + (localLanguage > UNKNOWN ? 9 : 0);
        if (name.toLowerCase(Locale.ROOT).endsWith(".srt")) score += 2;
        files.add(new Download(name, url, score));
    }

    private static String secureDownload(String address) {
        if (address.isEmpty() || address.length() > 8192) return "";
        try {
            URI uri = new URI(address.replace(" ", "%20"));
            String protocol = uri.getScheme();
            if (protocol == null || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) return "";
            String ascii = uri.toASCIIString();
            if (protocol.equalsIgnoreCase("https")) return ascii;
            return protocol.equalsIgnoreCase("http") ? "https" + ascii.substring(protocol.length()) : "";
        } catch (URISyntaxException invalid) {
            return "";
        }
    }

    private static int listedLanguage(JSONArray files) {
        int language = UNKNOWN;
        if (files == null) return language;
        for (int i = 0; i < files.length(); i++) {
            language = Math.max(language, filenameLanguage(text(files.optJSONObject(i), "f")));
        }
        return language;
    }

    private static int providerLanguage(JSONObject subtitle) {
        JSONObject language = subtitle.optJSONObject("lang");
        if (language == null) return UNKNOWN;
        JSONObject list = language.optJSONObject("langlist");
        String description = text(language, "desc");
        boolean simplified = flag(list, "langchs") || description.matches(".*(?:简体|简中|簡體|簡中|简繁|簡繁).*");
        boolean traditional = flag(list, "langcht") || description.matches(".*(?:繁体|繁中|繁體|简繁|簡繁).*");
        boolean chinese = simplified || traditional || description.contains("中文")
                || description.contains("中英") || description.contains("汉语") || description.contains("漢語");
        boolean foreign = flag(list, "langjap") || flag(list, "langkor") || flag(list, "langfra")
                || flag(list, "langesp") || description.matches(".*(?:日文|日语|韩文|韩语|法文|法语|西班牙).*");
        boolean bilingual = flag(list, "langdou") || description.matches(".*(?:双语|雙語|中英|bilingual).*");
        if (bilingual && (chinese || !foreign)) return BILINGUAL;
        if (simplified) return SIMPLIFIED;
        if (traditional) return TRADITIONAL;
        if (chinese) return CHINESE;
        return foreign || flag(list, "langeng") || description.matches(".*(?:英文|英语|英語).*")
                ? FOREIGN : UNKNOWN;
    }

    private static boolean flag(JSONObject list, String key) {
        return list != null && (list.optBoolean(key) || list.optInt(key) == 1
                || text(list, key).equals("1"));
    }

    private static int filenameLanguage(String filename) {
        String stem = Normalizer.normalize(withoutExtension(fileName(filename)), Normalizer.Form.NFKC);
        if (BILINGUAL_LABEL.matcher(stem).find()) return BILINGUAL;
        if (SIMPLIFIED_LABEL.matcher(stem).find()) return SIMPLIFIED;
        if (TRADITIONAL_LABEL.matcher(stem).find()) return TRADITIONAL;
        if (CHINESE_LABEL.matcher(stem).find()) return CHINESE;
        return FOREIGN_LABEL.matcher(stem).find() ? FOREIGN : UNKNOWN;
    }

    private static Pattern trailingLabel(String label) {
        return Pattern.compile("(?:^|" + LABEL_SEPARATOR + ")" + label + LABEL_SEPARATOR + "*$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static Evidence evidence(MatchContext context, JSONObject item) {
        Evidence result = new Evidence();
        for (String field : new String[]{"native_name", "videoname", "title", "filename"}) {
            result.add(evidence(context, text(item, field)));
        }
        return result;
    }

    private static Evidence evidence(MatchContext context, String raw) {
        Evidence evidence = new Evidence();
        if (raw.isEmpty()) return evidence;
        // ASSRT titles commonly separate alternative titles with slashes.
        for (String value : raw.split("[/／|\\r\\n]")) {
            String name = withoutExtension(value.trim());
            if (name.isEmpty()) continue;
            if (!context.episodic && EPISODE.matcher(name).find()) {
                evidence.conflict = true;
                continue;
            }
            String full = normalized(name);
            boolean nameMatches = context.titles.contains(full);
            if (!nameMatches) {
                for (Pattern label : LANGUAGE_LABELS) name = label.matcher(name).replaceFirst("");
            }
            MovieStorage.FileNameHints parsed = MovieStorage.parseTitle(name);
            String title = normalized(parsed.title);
            boolean titleMatches = context.titles.contains(title);
            boolean releaseMatches = context.releaseName
                    && (full.equals(context.release) || normalized(name).equals(context.release));
            evidence.matches |= nameMatches || titleMatches || releaseMatches;
            evidence.release |= releaseMatches;
            if (parsed.year > 0 && !(nameMatches && !titleMatches)) {
                if (context.year > 0 && parsed.year != context.year) evidence.conflict = true;
                if (parsed.year == context.year && (nameMatches || titleMatches || releaseMatches)) {
                    evidence.sameYear = true;
                }
            } else if (parsed.year == 0 && !nameMatches && context.year > 0) {
                Matcher years = RELEASE_YEAR.matcher(name);
                while (years.find()) {
                    if (!context.titles.contains(normalized(name.substring(0, years.start())))) continue;
                    evidence.matches = true;
                    int year = Integer.parseInt(years.group());
                    evidence.sameYear |= year == context.year;
                    evidence.conflict |= year != context.year;
                }
            }
        }
        return evidence;
    }

    private static String withoutExtension(String name) {
        return FILE_EXTENSION.matcher(name).replaceFirst("");
    }

    private static String fileName(String name) {
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return name.substring(separator + 1);
    }

    private static String normalized(String title) {
        String text = Normalizer.normalize(title, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder compact = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int character = text.codePointAt(i);
            if (Character.isLetterOrDigit(character)) compact.appendCodePoint(character);
            i += Character.charCount(character);
        }
        return compact.toString();
    }

    private static String text(JSONObject object, String key) {
        return object == null || object.isNull(key) ? "" : object.optString(key, "").trim();
    }

    private static void checkRunning() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("字幕刮削已停止");
    }

    private static final class MatchContext {
        private final Set<String> titles = new LinkedHashSet<>();
        private final String release;
        private final boolean releaseName;
        private final boolean episodic;
        private final int year;

        private MatchContext(SubtitleQuery query) {
            for (String name : query.titles) {
                String key = normalized(name);
                if (!key.isEmpty()) titles.add(key);
            }
            release = normalized(query.basename);
            releaseName = RELEASE.matcher(query.basename).find();
            episodic = EPISODE.matcher(query.basename).find();
            year = query.year;
        }
    }

    private static final class Evidence {
        private boolean matches;
        private boolean sameYear;
        private boolean release;
        private boolean conflict;

        private void add(Evidence other) {
            matches |= other.matches;
            sameYear |= other.sameYear;
            release |= other.release;
            conflict |= other.conflict;
        }

        private boolean sufficient(MatchContext context) {
            return !conflict && (release || (matches && (context.year <= 0 || sameYear)));
        }
    }

    private record Search(String text, boolean filename) {}

    private record Hit(int id, Evidence evidence, int language, int votes) {
        private int score() {
            return (evidence.release ? 1000 : evidence.sameYear ? 600 : 250)
                    + Math.max(language, 0) * 15 + Math.max(0, Math.min(20, votes));
        }
    }

    private record Download(String name, String url, int score) {}
}
