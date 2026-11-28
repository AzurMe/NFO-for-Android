package org.kodiscraper.app;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class MovieOrganizer {
    private static final int NFO_LIMIT = 1024 * 1024;
    private static final int MAX_NAME_BYTES = 255;
    private static final Pattern TITLE_DIVIDERS = Pattern.compile("[\\s\\p{Z}._/\\\\:*?\"<>|\\x00-\\x1f]+");
    private static final Pattern GENERIC_TITLE = Pattern.compile("(?i)^(?:movie|main|video|film|feature|影片|电影|正片)$");
    private static final Pattern BRIDGE_DASH = Pattern.compile("[\\s\\p{Z}]+[-–—][\\s\\p{Z}]+");
    private static final Pattern ARTWORK = Pattern.compile("(?:poster|fanart|thumb|banner|landscape|keyart|clearlogo|"
            + "clearart|discart|logo|characterart)[0-9]{0,2}\\.(?:jpg|jpeg|png|webp|tbn|gif|bmp)");
    private static final Pattern TRACK_LABEL = Pattern.compile("(?:[a-z]{2,3}(?:-[a-z0-9]{2,8})*|[0-9]{1,3}|"
            + "forced|default|commentary|signs|sdh|simplified|traditional|简体|繁体|中英|双语|中文|简体中文|繁体中文)");
    private static final Set<String> SUBTITLE_EXTENSIONS = values("srt", "ass", "ssa", "vtt", "sub", "idx", "smi",
            "sami", "sup", "mpl", "ttml", "lrc", "scc");
    private static final Set<String> SUBTITLE_FOLDERS = values("subs", "subtitles", "vobsubs", "sub", "vobsub", "subtitle");
    private static final Set<String> AUDIO_EXTENSIONS = values("aac", "ac3", "eac3", "dts", "flac", "m4a", "mka",
            "mp3", "ogg", "opus", "wav");
    private static final Set<String> AUDIO_FOLDERS = values("audio", "tracks");

    private MovieOrganizer() {}

    record Plan(List<Item> items, List<Skip> skipped) {
        Plan {
            items = Collections.unmodifiableList(new ArrayList<>(items));
            skipped = Collections.unmodifiableList(new ArrayList<>(skipped));
        }
    }

    record Skip(String path, String reason) {}

    record Summary(int renamed, int failed) {}

    static final class Item {
        private final MovieStorage.Directory folder;
        private final MovieStorage.Entry video;
        private final String targetStem;
        private final String targetVideoName;
        private final List<Move> moves;

        private Item(MovieStorage.Directory folder, MovieStorage.Entry video, String targetStem,
                     String targetVideoName, List<Move> moves) {
            this.folder = folder;
            this.video = video;
            this.targetStem = targetStem;
            this.targetVideoName = targetVideoName;
            this.moves = Collections.unmodifiableList(new ArrayList<>(moves));
        }

        String sourceFolder() {
            return folder.relativePath;
        }

        String targetFolder() {
            return childPath(folder.parent.relativePath, targetStem);
        }

        String sourceVideo() {
            return childPath(sourceFolder(), video.displayName);
        }

        String targetVideo() {
            return childPath(targetFolder(), targetVideoName);
        }
    }

    private record Move(MovieStorage.Directory folder, MovieStorage.Entry from, String to) {}

    private record CompletedMove(Move original, MovieStorage.Entry moved) {}

    private record TitleYear(String title, int year) {
        boolean usable() {
            return validYear(year) && usableTitle(title);
        }
    }

    static Plan preview(MovieStorage storage, boolean preferFolderTitle) throws IOException {
        Map<String, List<MovieStorage.Movie>> byFolder = new LinkedHashMap<>();
        Set<String> ancestors = new HashSet<>();
        for (MovieStorage.Movie movie : storage.scan(preferFolderTitle)) {
            byFolder.computeIfAbsent(movie.directory.documentId, ignored -> new ArrayList<>()).add(movie);
            for (MovieStorage.Directory parent = movie.directory.parent; parent != null; parent = parent.parent) {
                ancestors.add(parent.documentId);
            }
        }

        List<Item> proposals = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();
        Map<String, Integer> targets = new HashMap<>();
        for (List<MovieStorage.Movie> group : byFolder.values()) {
            ScrapeEngine.checkCancelled();
            MovieStorage.Movie movie = group.get(0);
            MovieStorage.Directory folder = movie.directory;
            if (folder.parent == null) {
                for (MovieStorage.Movie loose : group) {
                    skipped.add(new Skip(loose.relativePath, "影片直接位于设置的搜索目录"));
                }
                continue;
            }
            if (group.size() != 1 || ancestors.contains(folder.documentId)) {
                skipped.add(new Skip(folder.relativePath, "目录内包含多部影片或其他影片子目录"));
                continue;
            }
            try {
                Item item = prepare(storage, movie);
                proposals.add(item);
                targets.merge(lower(item.targetFolder()), 1, Integer::sum);
            } catch (IOException problem) {
                ScrapeEngine.checkCancelled();
                skipped.add(new Skip(folder.relativePath, message(problem)));
            }
        }

        List<Item> items = new ArrayList<>();
        for (Item item : proposals) {
            if (targets.get(lower(item.targetFolder())) > 1) {
                skipped.add(new Skip(item.sourceFolder(), "多个目录将使用同一目标名称 · " + item.targetFolder()));
            } else {
                items.add(item);
            }
        }
        return new Plan(items, skipped);
    }

    private static Item prepare(MovieStorage storage, MovieStorage.Movie movie) throws IOException {
        MovieStorage.Directory folder = movie.directory;
        List<MovieStorage.Entry> children = storage.list(folder);
        MovieStorage.Entry video = singleVideo(children);
        if (!movie.displayName.equals(video.displayName) || !movie.basename().equals(stem(video.displayName))) {
            throw new IOException("分段影片或目录内容已变化");
        }
        TitleYear hints = titleYear(storage, movie);
        if (!hints.usable()) {
            throw new IOException("无法确认片名或上映年份");
        }
        String targetStem = dotTitle(hints.title) + "." + hints.year;
        String targetVideoName = targetStem + video.displayName.substring(stem(video.displayName).length());
        if (folder.displayName.equals(targetStem) && video.displayName.equals(targetVideoName)) {
            throw new IOException("已符合命名规则");
        }
        ensureTarget(storage, folder.parent, folder, targetStem);
        List<Move> moves = fileMoves(storage, folder, video, children, targetStem, targetVideoName);
        return new Item(folder, video, targetStem, targetVideoName, moves);
    }

    static Summary apply(MovieStorage storage, Plan plan, Consumer<String> report) throws IOException {
        int renamed = 0;
        int failed = 0;
        for (Item item : plan.items()) {
            ScrapeEngine.checkCancelled();
            try {
                applyItem(storage, item, report);
                renamed++;
                report.accept("已整理 · " + item.sourceVideo() + " → " + item.targetVideo());
            } catch (IOException problem) {
                ScrapeEngine.checkCancelled();
                failed++;
                report.accept("整理失败 · " + item.sourceVideo() + " · " + message(problem));
            }
        }
        return new Summary(renamed, failed);
    }

    private static void applyItem(MovieStorage storage, Item item, Consumer<String> report) throws IOException {
        MovieStorage.Directory folder = item.folder;
        List<MovieStorage.Entry> siblings = storage.list(folder.parent);
        if (!sameEntry(named(siblings, folder.displayName), folder)) {
            throw new IOException("源目录已变化，请重新预览");
        }
        ensureTarget(storage, folder.parent, folder, item.targetStem);
        List<MovieStorage.Entry> children = storage.list(folder);
        MovieStorage.Entry video = singleVideo(children);
        if (!sameEntry(video, item.video)) {
            throw new IOException("源影片已变化，请重新预览");
        }
        List<Move> moves = fileMoves(storage, folder, video, children, item.targetStem, item.targetVideoName);
        if (!sameMoves(item.moves, moves)) {
            throw new IOException("影片附属文件已变化，请重新预览");
        }

        List<CompletedMove> completed = new ArrayList<>();
        try {
            for (Move move : moves) {
                ScrapeEngine.checkCancelled();
                MovieStorage.Entry result = storage.renameEntry(move.folder, move.from, move.to, true);
                completed.add(new CompletedMove(move, result));
            }
            if (!folder.displayName.equals(item.targetStem)) {
                ScrapeEngine.checkCancelled();
                // Renaming the folder can invalidate all child document IDs, so this must be last.
                storage.renameEntry(folder.parent, folder, item.targetStem, true);
            }
        } catch (IOException problem) {
            List<String> notRestored = restore(storage, completed);
            if (!notRestored.isEmpty()) {
                String details = "部分文件未能恢复旧名称 · " + String.join("、", notRestored);
                if (Thread.currentThread().isInterrupted()) {
                    report.accept(details);
                }
                throw new IOException(message(problem) + " · " + details, problem);
            }
            throw problem;
        }
    }

    private static List<String> restore(MovieStorage storage, List<CompletedMove> completed) {
        List<String> notRestored = new ArrayList<>();
        for (int i = completed.size() - 1; i >= 0; i--) {
            CompletedMove saved = completed.get(i);
            try {
                storage.renameEntry(saved.original.folder, saved.moved, saved.original.from.displayName, false);
            } catch (IOException failure) {
                notRestored.add(childPath(saved.original.folder.relativePath, saved.moved.displayName));
            }
        }
        return notRestored;
    }

    private static List<Move> fileMoves(MovieStorage storage, MovieStorage.Directory folder, MovieStorage.Entry video,
                                        List<MovieStorage.Entry> children, String targetStem, String targetVideo)
            throws IOException {
        List<Move> moves = new ArrayList<>();
        String videoStem = stem(video.displayName);
        if (videoStem.equals(targetStem)) {
            return moves;
        }
        for (MovieStorage.Entry child : children) {
            ScrapeEngine.checkCancelled();
            if (child instanceof MovieStorage.Directory) {
                MovieStorage.Directory subfolder = (MovieStorage.Directory) child;
                String name = lower(subfolder.displayName);
                Set<String> extensions = SUBTITLE_FOLDERS.contains(name) ? SUBTITLE_EXTENSIONS
                        : AUDIO_FOLDERS.contains(name) ? AUDIO_EXTENSIONS : Collections.emptySet();
                if (!extensions.isEmpty()) {
                    for (MovieStorage.Entry track : storage.list(subfolder)) {
                        if (!(track instanceof MovieStorage.Directory)) {
                            String suffix = suffix(track.displayName, videoStem);
                            if (isTrack(suffix, extensions)) {
                                addMove(storage, moves, subfolder, track, targetStem + suffix);
                            }
                        }
                    }
                }
            } else if (!sameEntry(child, video)) {
                String suffix = suffix(child.displayName, videoStem);
                if (isSidecar(suffix, child.displayName)) {
                    addMove(storage, moves, folder, child, targetStem + suffix);
                }
            }
        }
        moves.sort(Comparator.comparing((Move move) -> move.folder.relativePath)
                .thenComparing(move -> move.from.displayName));
        addMove(storage, moves, folder, video, targetVideo);
        return moves;
    }

    private static void addMove(MovieStorage storage, List<Move> moves, MovieStorage.Directory folder,
                                MovieStorage.Entry from, String target) throws IOException {
        ensureTarget(storage, folder, from, target);
        for (Move planned : moves) {
            if (planned.folder.documentId.equals(folder.documentId) && lower(planned.to).equals(lower(target))) {
                throw new IOException("附属文件目标名称冲突 · " + target);
            }
        }
        moves.add(new Move(folder, from, target));
    }

    private static void ensureTarget(MovieStorage storage, MovieStorage.Directory folder, MovieStorage.Entry source,
                                     String name) throws IOException {
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw new IOException("目标名称过长 · " + name);
        }
        MovieStorage.Entry target = storage.find(folder, name);
        if (target != null && !sameEntry(source, target)) {
            throw new IOException("目标已存在 · " + childPath(folder.relativePath, name));
        }
    }

    private static boolean sameMoves(List<Move> expected, List<Move> current) {
        if (expected.size() != current.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            Move before = expected.get(i);
            Move now = current.get(i);
            if (!before.folder.documentId.equals(now.folder.documentId) || !sameEntry(before.from, now.from)
                    || !before.to.equals(now.to)) {
                return false;
            }
        }
        return true;
    }

    private static MovieStorage.Entry singleVideo(List<MovieStorage.Entry> children) throws IOException {
        MovieStorage.Entry video = null;
        for (MovieStorage.Entry child : children) {
            if (!(child instanceof MovieStorage.Directory) && MovieStorage.isFeatureVideo(child.displayName)) {
                if (video != null) {
                    throw new IOException("目录内有多个视频或影片分段");
                }
                video = child;
            }
        }
        if (video == null || !(lower(video.displayName).endsWith(".mp4") || lower(video.displayName).endsWith(".mkv"))) {
            throw new IOException("仅整理单个 MP4 或 MKV 影片");
        }
        return video;
    }

    private static boolean isSidecar(String suffix, String fullName) {
        String name = lower(suffix);
        if (name.equals(".nfo") || name.equals(".edl")) {
            return true;
        }
        if (name.startsWith("-")) {
            return ARTWORK.matcher(name.substring(1)).matches()
                    || (name.startsWith("-trailer.") && MovieStorage.isVideo(fullName))
                    || (name.startsWith("-theme.") && AUDIO_EXTENSIONS.contains(name.substring(7)));
        }
        return isTrack(name, SUBTITLE_EXTENSIONS) || isTrack(name, AUDIO_EXTENSIONS);
    }

    private static boolean isTrack(String suffix, Set<String> extensions) {
        if (!suffix.startsWith(".")) {
            return false;
        }
        String[] parts = lower(suffix.substring(1)).split("\\.", -1);
        if (!extensions.contains(parts[parts.length - 1])) {
            return false;
        }
        for (int i = 0; i < parts.length - 1; i++) {
            if (!TRACK_LABEL.matcher(parts[i]).matches()) {
                return false;
            }
        }
        return true;
    }

    private static String suffix(String name, String stem) {
        return name.regionMatches(true, 0, stem, 0, stem.length()) ? name.substring(stem.length()) : "";
    }

    private static TitleYear titleYear(MovieStorage storage, MovieStorage.Movie movie) throws IOException {
        TitleYear primary = new TitleYear(movie.title, movie.year);
        if (primary.usable()) {
            return primary;
        }
        TitleYear nfo = localNfo(storage, movie);
        if (nfo.usable()) {
            return nfo;
        }
        TitleYear combined = new TitleYear(usableTitle(nfo.title) ? nfo.title : primary.title,
                validYear(primary.year) ? primary.year : nfo.year);
        if (combined.usable()) {
            return combined;
        }
        TitleYear alternate = new TitleYear(movie.alternateTitle, movie.alternateYear);
        if (alternate.usable()) {
            return alternate;
        }
        MovieStorage.FileNameHints folder = MovieStorage.parseTitle(movie.directory.displayName);
        TitleYear byFolder = new TitleYear(folder.title, folder.year);
        if (byFolder.usable()) {
            return byFolder;
        }
        MovieStorage.FileNameHints file = MovieStorage.parseTitle(movie.displayName);
        return new TitleYear(file.title, file.year);
    }

    private static TitleYear localNfo(MovieStorage storage, MovieStorage.Movie movie) throws IOException {
        List<String> names = new ArrayList<>();
        names.add(movie.nfoName);
        names.addAll(movie.legacyNfoNames);
        TitleYear fallback = new TitleYear("", 0);
        for (String name : names) {
            ScrapeEngine.checkCancelled();
            MovieStorage.Entry entry = storage.find(movie.directory, name);
            if (entry == null || entry instanceof MovieStorage.Directory) {
                continue;
            }
            try {
                TitleYear value = readNfo(storage.read(entry, NFO_LIMIT));
                if (value.usable()) {
                    return value;
                }
                if (!usableTitle(fallback.title) && !validYear(fallback.year)
                        && (usableTitle(value.title) || validYear(value.year))) {
                    fallback = value;
                }
            } catch (IOException | XmlPullParserException invalidNfo) {
                ScrapeEngine.checkCancelled();
            }
        }
        return fallback;
    }

    private static TitleYear readNfo(byte[] data) throws IOException, XmlPullParserException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        parser.setInput(new ByteArrayInputStream(data), null);
        if (parser.nextTag() != XmlPullParser.START_TAG || !"movie".equals(parser.getName())) {
            return new TitleYear("", 0);
        }
        String title = "";
        String originalTitle = "";
        int year = 0;
        int premiered = 0;
        boolean closed = false;
        for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.END_TAG && parser.getDepth() == 1 && "movie".equals(parser.getName())) {
                closed = true;
            }
            if (event != XmlPullParser.START_TAG || parser.getDepth() != 2) {
                continue;
            }
            switch (parser.getName()) {
                case "title": title = parser.nextText().trim(); break;
                case "originaltitle": originalTitle = parser.nextText().trim(); break;
                case "year": year = parseYear(parser.nextText().trim()); break;
                case "premiered":
                    String date = parser.nextText().trim();
                    if (date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                        premiered = parseYear(date.substring(0, 4));
                    }
                    break;
                default: break;
            }
        }
        return closed ? new TitleYear(usableTitle(originalTitle) ? originalTitle : title, validYear(year) ? year : premiered)
                : new TitleYear("", 0);
    }

    private static int parseYear(String value) {
        return value.matches("[0-9]{4}") ? Integer.parseInt(value) : 0;
    }

    private static boolean validYear(int year) {
        return year >= 1888 && year <= 2099;
    }

    private static boolean usableTitle(String title) {
        return title != null && !GENERIC_TITLE.matcher(title.trim()).matches()
                && title.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private static String dotTitle(String title) {
        return TITLE_DIVIDERS.matcher(BRIDGE_DASH.matcher(title.trim()).replaceAll(" "))
                .replaceAll(".").replaceAll("^\\.+|\\.+$", "");
    }

    private static MovieStorage.Entry named(List<MovieStorage.Entry> entries, String name) {
        for (MovieStorage.Entry entry : entries) {
            if (entry.displayName.equals(name)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean sameEntry(MovieStorage.Entry left, MovieStorage.Entry right) {
        return left != null && right != null && left.documentId.equals(right.documentId)
                && left.displayName.equals(right.displayName) && (left instanceof MovieStorage.Directory)
                == (right instanceof MovieStorage.Directory);
    }

    private static Set<String> values(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static String childPath(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    private static String stem(String name) {
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String message(IOException failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? "无法访问文件" : message;
    }
}
