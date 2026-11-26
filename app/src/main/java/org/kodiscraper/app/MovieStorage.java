package org.kodiscraper.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Year;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MovieStorage {
    private static final String TRAILER_SUFFIX = "-trailer";
    private static final String[] DOCUMENT_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE
    };
    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "3gp", "asf", "avi", "divx", "f4v", "flv", "iso", "m2p", "m2t", "m2ts", "m4v",
            "mkv", "mov", "mp4", "mpe", "mpeg", "mpg", "mts", "ogm", "ogv", "ram",
            "rm", "rmvb", "strm", "ts", "vob", "webm", "wmv", "wtv", "xvid"));
    private static final Set<String> EXTRA_DIRECTORIES = new HashSet<>(Arrays.asList(
            ".actors", "bdmv", "video_ts", "certificate", "sample", "samples", "trailer",
            "trailers", "extra", "extras", "featurette", "featurettes", "bonus", "deleted scenes",
            "behind the scenes", "bonus features", "special features", "lost.dir", "system volume information"));
    private static final Pattern MOVIE_ID = Pattern.compile(
            "(?i)[\\[{]\\s*(tmdb|imdb)(?:id)?\\s*[-=:]\\s*(tt[0-9]+|[0-9]+)\\s*[\\]}]"
    );
    private static final Pattern IMDB_ID = Pattern.compile("(?i)(?<![\\p{L}0-9])tt[0-9]{5,12}(?![\\p{L}0-9])");
    private static final Pattern EMPTY_BRACKETS = Pattern.compile("\\[\\s*\\]|\\{\\s*\\}|\\(\\s*\\)|【\\s*】");
    private static final Pattern EXPLICIT_YEAR = Pattern.compile(
            "[（(\\[【]\\s*((?:18[89][0-9]|19[0-9]{2}|20[0-9]{2}))\\s*[）)\\]】]"
    );
    private static final Pattern BARE_YEAR = Pattern.compile(
            "(?<![0-9])((?:18[89][0-9]|19[0-9]{2}|20[0-9]{2}))(?![0-9])"
    );
    private static final Pattern RELEASE_TAG = Pattern.compile(
            "(?i)(?:^|[ ._\\[（(【-])(?:[1248]k|[0-9]{3,4}[pi]|blu[ ._-]?ray|b[dr]rip|"
                    + "remux|uhd|web[ ._-]?(?:dl|rip)|hdtv|dvd[ ._-]?rip|hdr10\\+?|hdr|hevc|"
                    + "x[ ._-]?26[45]|h[ ._-]?26[45]|av1|dts(?:-hd)?|truehd|aac|ddp|atmos)(?=$|[ ._\\])）】-])"
    );
    private static final Pattern[] STACK_PART = {
            Pattern.compile("(?i)^(.+?)[ ._-]*?(?:cd|dvd|dis[ck]|part|pt|file)[ ._-]*([0-9]{1,3})(.*)$"),
            Pattern.compile("(?i)^(.+?)[ ._-]*?(?:cd|dvd|dis[ck]|part|pt)[ ._-]*([a-h])($|[ ._-].*)"),
            Pattern.compile("(?i)^(.+?)(?:[ ._-]|(?<=\\)))([a-h])()$")
    };
    private static final Pattern EXTRA_SUFFIX = Pattern.compile(
            "(?i)^(?:trailer|sample|preview|teaser)(?:[ ._-]*[0-9]+)?$|"
                    + "[._-](?:trailer|sample|preview|teaser)(?:[ ._-]*[0-9]+)?$"
    );
    private static final Pattern GENERIC_TITLE = Pattern.compile(
            "(?i)^(?:movie|main|video|film|feature|影片|电影|正片)$"
    );
    private static final Pattern ILLEGAL_FILE_NAME_CHARS = Pattern.compile("[\\x00-\\x1f/\\\\:*?\"<>|]");

    private final ContentResolver resolver;
    private final Uri treeUri;
    private final Directory root;

    public MovieStorage(Context context, Uri treeUri) {
        this.resolver = context.getContentResolver();
        this.treeUri = treeUri;
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        this.root = new Directory(documentUri, documentId, "", "");
    }

    public static class Entry {
        public final Uri uri;
        public final String displayName;
        public final String mimeType;
        final String documentId;

        Entry(Uri uri, String documentId, String displayName, String mimeType) {
            this.uri = uri;
            this.documentId = documentId;
            this.displayName = displayName;
            this.mimeType = mimeType;
        }
    }

    public static final class Directory extends Entry {
        public final String relativePath;
        private Map<String, Entry> children;
        private Map<String, Entry> childrenIgnoringCase;

        Directory(Uri uri, String documentId, String displayName, String relativePath) {
            super(uri, documentId, displayName, Document.MIME_TYPE_DIR);
            this.relativePath = relativePath;
        }

        private void replaceChildren(Map<String, Entry> entries) {
            children = entries;
            childrenIgnoringCase = new LinkedHashMap<>();
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                childrenIgnoringCase.putIfAbsent(lower(entry.getKey()), entry.getValue());
            }
        }

        private void remember(Entry entry) {
            children.put(entry.displayName, entry);
            childrenIgnoringCase.put(lower(entry.displayName), entry);
        }

        private Entry lookup(String name) {
            Entry exact = children.get(name);
            return exact != null ? exact : childrenIgnoringCase.get(lower(name));
        }
    }

    public static final class FileNameHints {
        public final String title;
        public final int year;
        public final String imdbId;
        public final int tmdbId;

        FileNameHints(String title, int year, String imdbId, int tmdbId) {
            this.title = title;
            this.year = year;
            this.imdbId = imdbId;
            this.tmdbId = tmdbId;
        }
    }

    public static final class Movie {
        public final Uri uri;
        public final Directory directory;
        public final String displayName;
        public final String relativePath;
        public final String title;
        public final int year;
        public final String alternateTitle;
        public final int alternateYear;
        public final String imdbId;
        public final int tmdbId;
        public final String nfoName;
        public final List<String> legacyNfoNames;
        public final String localTrailerPath;
        public final boolean singleMovieInDirectory;
        private final String basename;

        Movie(Entry video, Directory directory, String basename, FileNameHints hints, FileNameHints alternate,
                boolean singleMovieInDirectory, List<String> legacyNfoNames, String localTrailerPath) {
            this.uri = video.uri;
            this.directory = directory;
            this.displayName = video.displayName;
            this.relativePath = childPath(directory, video.displayName);
            this.title = hints.title;
            this.year = hints.year;
            this.alternateTitle = alternate == null ? "" : alternate.title;
            this.alternateYear = alternate == null ? 0 : alternate.year;
            this.imdbId = hints.imdbId;
            this.tmdbId = hints.tmdbId;
            this.basename = basename;
            this.nfoName = basename + ".nfo";
            this.singleMovieInDirectory = singleMovieInDirectory;
            this.legacyNfoNames = Collections.unmodifiableList(new ArrayList<>(legacyNfoNames));
            this.localTrailerPath = localTrailerPath;
        }

        public String artworkName(String type, String extension) {
            return basename + "-" + type + (extension.startsWith(".") ? "" : ".") + extension;
        }
    }

    public List<Movie> scan() throws IOException {
        return scan(false);
    }

    public Directory rootDirectory() {
        return root;
    }

    public List<Movie> scan(boolean preferFolderTitle) throws IOException {
        List<Movie> movies = new ArrayList<>();
        ArrayDeque<Directory> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            checkCancelled();
            Directory directory = pending.removeFirst();
            if (!visited.add(directory.documentId)) {
                continue;
            }
            load(directory);
            Map<String, Entry> videos = new LinkedHashMap<>();
            Map<String, String> trailers = new LinkedHashMap<>();
            for (Entry child : directory.children.values()) {
                checkCancelled();
                if (child.displayName.startsWith(".")) {
                    continue;
                }
                if (child instanceof Directory) {
                    String folderLabel = lower(child.displayName).replaceAll("[._-]+", " ").trim();
                    if (!EXTRA_DIRECTORIES.contains(lower(child.displayName)) && !EXTRA_DIRECTORIES.contains(folderLabel)) {
                        pending.addLast((Directory) child);
                    }
                } else if (isVideo(child.displayName)) {
                    String basename = stem(child.displayName);
                    String foldedBase = lower(basename);
                    if (foldedBase.endsWith(TRAILER_SUFFIX)) {
                        trailers.put(foldedBase.substring(0, foldedBase.length() - TRAILER_SUFFIX.length()),
                                child.displayName);
                    } else if (!EXTRA_SUFFIX.matcher(basename).find()) {
                        videos.putIfAbsent(foldedBase, child);
                    }
                }
            }
            List<VideoCandidate> titles = groupStackedParts(directory, videos);
            boolean singleMovie = titles.size() == 1;
            FileNameHints folderHints = singleMovie && !directory.displayName.isEmpty()
                    ? parseFolderTitle(directory.displayName) : null;
            boolean useFolderTitle = preferFolderTitle && folderHints != null && usableTitle(folderHints.title);
            for (VideoCandidate candidate : titles) {
                String firstPartStem = stem(candidate.video.displayName);
                String canonicalNfo = candidate.basename + ".nfo";
                List<String> legacyNfoNames = new ArrayList<>(2);
                addNfoAlias(directory, firstPartStem + ".nfo", canonicalNfo, legacyNfoNames);
                if (singleMovie) {
                    addNfoAlias(directory, "movie.nfo", canonicalNfo, legacyNfoNames);
                }
                FileNameHints fileHints = parseFolderTitle(candidate.basename);
                FileNameHints hints = combineHints(fileHints, folderHints, useFolderTitle);
                FileNameHints alternate = useFolderTitle ? alternateTitle(fileHints, hints) : null;
                String trailer = trailers.get(lower(candidate.basename));
                if (trailer == null && !candidate.basename.equals(firstPartStem)) {
                    trailer = trailers.get(lower(firstPartStem));
                }
                if (trailer == null && singleMovie && !directory.relativePath.isEmpty()) {
                    trailer = trailers.get("movie");
                }
                movies.add(new Movie(candidate.video, directory, candidate.basename, hints, alternate,
                        singleMovie, legacyNfoNames, trailer));
            }
        }
        movies.sort(Comparator.comparing(movie -> movie.relativePath, String.CASE_INSENSITIVE_ORDER));
        return movies;
    }

    private static void addNfoAlias(Directory directory, String name, String canonicalName, List<String> aliases) {
        if (name.equalsIgnoreCase(canonicalName)) {
            return;
        }
        Entry entry = directory.lookup(name);
        if (entry != null && !(entry instanceof Directory) && !aliases.contains(entry.displayName)) {
            aliases.add(entry.displayName);
        }
    }

    private static final class VideoCandidate {
        final Entry video;
        final String basename;

        VideoCandidate(Entry video, String basename) {
            this.video = video;
            this.basename = basename;
        }
    }

    private static List<VideoCandidate> groupStackedParts(Directory directory, Map<String, Entry> videos) {
        Map<String, Map<String, Entry>> stacks = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        if (!directory.relativePath.isEmpty()) {
            for (Entry video : videos.values()) {
                String basename = stem(video.displayName);
                for (Pattern stackPart : STACK_PART) {
                    Matcher match = stackPart.matcher(basename);
                    if (!match.matches()) {
                        continue;
                    }
                    String stackedName = match.group(1) + match.group(3);
                    String key = lower(stackedName);
                    String part = lower(match.group(2));
                    if (Character.isDigit(part.charAt(0))) {
                        part = String.valueOf(Integer.parseInt(part));
                    }
                    names.putIfAbsent(key, stackedName);
                    stacks.computeIfAbsent(key, unused -> new LinkedHashMap<>())
                            .putIfAbsent(part, video);
                    break;
                }
            }
        }
        Set<Entry> stacked = new HashSet<>();
        List<VideoCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Map<String, Entry>> stack : stacks.entrySet()) {
            Map<String, Entry> parts = stack.getValue();
            Entry first = parts.containsKey("2") ? parts.get("1") : null;
            if (first == null && parts.containsKey("b")) {
                first = parts.get("a");
            }
            if (first != null && !videos.containsKey(stack.getKey())) {
                stacked.addAll(parts.values());
                candidates.add(new VideoCandidate(first, names.get(stack.getKey())));
            }
        }
        for (Entry video : videos.values()) {
            if (!stacked.contains(video)) {
                candidates.add(new VideoCandidate(video, stem(video.displayName)));
            }
        }
        return candidates;
    }

    public Entry find(Directory directory, String name) throws IOException {
        checkCancelled();
        if (directory.children == null) {
            load(directory);
        }
        return directory.lookup(name);
    }

    public Directory subdirectory(Directory directory, String name) throws IOException {
        validateName(name);
        Entry existing = find(directory, name);
        if (existing instanceof Directory) {
            return (Directory) existing;
        }
        if (existing != null) {
            throw new IOException("已有同名文件，无法创建文件夹 " + name);
        }
        Entry created = create(directory, name, Document.MIME_TYPE_DIR);
        directory.remember(created);
        return (Directory) created;
    }

    public byte[] read(Entry entry, int limit) throws IOException {
        checkCancelled();
        if (entry instanceof Directory || limit < 0) {
            throw new IOException("无法读取 " + entry.displayName);
        }
        try (InputStream input = resolver.openInputStream(entry.uri)) {
            if (input == null) {
                throw new IOException("无法读取 " + entry.displayName);
            }
            ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(limit, 8192));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled();
                if (count > limit - result.size()) {
                    throw new IOException("文件超过读取上限 " + entry.displayName);
                }
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        } catch (RuntimeException e) {
            throw new IOException("无法读取 " + entry.displayName, e);
        }
    }

    public boolean write(Directory directory, String name, String mimeType, byte[] data, boolean overwrite)
            throws IOException {
        validateName(name);
        if (isVideo(name)) {
            throw new IOException("无法写入视频文件 " + name);
        }
        Entry existing = find(directory, name);
        if (existing != null && !overwrite) {
            return false;
        }
        if (existing instanceof Directory) {
            throw new IOException("已有同名文件夹，无法写入 " + name);
        }
        String outputMime = lower(name).endsWith(".nfo") ? "application/octet-stream" : mimeType;
        if (existing == null) {
            Entry created = create(directory, name, outputMime);
            try {
                writeBytes(created, data);
            } catch (IOException e) {
                cleanup(created, e);
                throw e;
            }
            directory.remember(created);
        } else {
            replace(directory, existing, outputMime, data);
        }
        return true;
    }

    private void replace(Directory directory, Entry existing, String mimeType, byte[] data) throws IOException {
        String name = existing.displayName;
        String suffix = name.substring(stem(name).length());
        String transaction = UUID.randomUUID().toString();
        Entry temporary = create(directory, ".kodi-scraper-" + transaction + suffix, mimeType);
        Entry backup = null;
        try {
            writeBytes(temporary, data);
            checkCancelled();
            // Finish or restore the rename sequence even if cancellation arrives during it.
            backup = rename(directory, existing, ".kodi-backup-" + transaction + suffix);
            Entry committed = rename(directory, temporary, name);
            directory.remember(committed);
        } catch (IOException e) {
            if (backup != null) {
                try {
                    directory.remember(rename(directory, backup, name));
                } catch (IOException restoreError) {
                    e.addSuppressed(restoreError);
                }
            }
            cleanup(temporary, e);
            throw e;
        }
        try {
            remove(backup);
        } catch (IOException e) {
            throw new IOException("已更新 " + name + "，但无法删除备份 " + backup.displayName, e);
        }
    }

    private void load(Directory directory) throws IOException {
        checkCancelled();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directory.documentId);
        Map<String, Entry> entries = new LinkedHashMap<>();
        try (Cursor cursor = resolver.query(childrenUri, DOCUMENT_COLUMNS, null, null, null)) {
            if (cursor == null) {
                throw new IOException("无法读取所选文件夹 " + directory.relativePath);
            }
            while (cursor.moveToNext()) {
                checkCancelled();
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (documentId == null || name == null || name.isEmpty()) {
                    continue;
                }
                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                entries.put(name, entry(directory, documentUri, documentId, name, mime));
            }
        } catch (RuntimeException e) {
            throw new IOException("无法读取所选文件夹 " + directory.relativePath, e);
        }
        directory.replaceChildren(entries);
    }

    private static Entry entry(Directory parent, Uri uri, String documentId, String name, String mime) {
        if (Document.MIME_TYPE_DIR.equals(mime)) {
            return new Directory(uri, documentId, name, childPath(parent, name));
        }
        return new Entry(uri, documentId, name, mime);
    }

    private Entry create(Directory parent, String name, String mime) throws IOException {
        checkCancelled();
        Uri uri;
        try {
            uri = DocumentsContract.createDocument(resolver, parent.uri, mime, name);
        } catch (RuntimeException e) {
            throw new IOException("无法创建 " + name + "，请检查文件夹写入权限", e);
        }
        if (uri == null) {
            throw new IOException("无法创建 " + name + "，请检查文件夹写入权限");
        }
        Entry created;
        try {
            created = stat(parent, uri);
        } catch (IOException e) {
            cleanup(entry(parent, uri, "", name, mime), e);
            throw e;
        }
        if (!name.equals(created.displayName) || !Document.MIME_TYPE_DIR.equals(mime) && created instanceof Directory
                || Document.MIME_TYPE_DIR.equals(mime) && !(created instanceof Directory)) {
            IOException e = new IOException("存储提供方无法创建指定名称 " + name);
            cleanup(created, e);
            throw e;
        }
        return created;
    }

    private Entry stat(Directory parent, Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(uri, DOCUMENT_COLUMNS, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("无法确认输出文件名称");
            }
            String documentId = cursor.getString(0);
            String name = cursor.getString(1);
            if (documentId == null || name == null || name.isEmpty()) {
                throw new IOException("无法确认输出文件名称");
            }
            return entry(parent, uri, documentId, name, cursor.getString(2));
        } catch (RuntimeException e) {
            throw new IOException("无法确认输出文件名称", e);
        }
    }

    private Entry rename(Directory parent, Entry entry, String newName) throws IOException {
        Uri renamed;
        try {
            renamed = DocumentsContract.renameDocument(resolver, entry.uri, newName);
        } catch (RuntimeException e) {
            throw new IOException("存储提供方无法重命名 " + entry.displayName, e);
        }
        if (renamed == null) {
            throw new IOException("存储提供方无法重命名 " + entry.displayName);
        }
        try {
            Entry moved = stat(parent, renamed);
            if (!newName.equals(moved.displayName)) {
                throw new IOException("存储提供方无法使用指定名称 " + newName);
            }
            return moved;
        } catch (IOException e) {
            try {
                if (DocumentsContract.renameDocument(resolver, renamed, entry.displayName) == null) {
                    e.addSuppressed(new IOException("无法恢复 " + entry.displayName));
                }
            } catch (RuntimeException restoreError) {
                e.addSuppressed(restoreError);
            }
            throw e;
        }
    }

    private void writeBytes(Entry entry, byte[] data) throws IOException {
        try (OutputStream output = resolver.openOutputStream(entry.uri, "w")) {
            if (output == null) {
                throw new IOException("无法写入 " + entry.displayName);
            }
            for (int written = 0; written < data.length; ) {
                checkCancelled();
                int count = Math.min(65_536, data.length - written);
                output.write(data, written, count);
                written += count;
            }
        } catch (RuntimeException e) {
            throw new IOException("无法写入 " + entry.displayName, e);
        }
    }

    private void remove(Entry entry) throws IOException {
        try {
            if (!DocumentsContract.deleteDocument(resolver, entry.uri)) {
                throw new IOException("无法删除临时输出 " + entry.displayName);
            }
        } catch (RuntimeException e) {
            throw new IOException("无法删除临时输出 " + entry.displayName, e);
        }
    }

    private void cleanup(Entry entry, IOException error) {
        try {
            remove(entry);
        } catch (IOException cleanupError) {
            error.addSuppressed(cleanupError);
        }
    }

    public static FileNameHints parseTitle(String input) {
        String raw = input == null ? "" : input.trim();
        return parseFolderTitle(isVideo(raw) ? stem(raw) : raw);
    }

    private static FileNameHints parseFolderTitle(String raw) {
        int tmdbId = 0;
        String imdbId = "";
        Matcher ids = MOVIE_ID.matcher(raw);
        while (ids.find()) {
            String kind = lower(ids.group(1));
            String value = lower(ids.group(2));
            if (kind.equals("tmdb")) {
                try {
                    tmdbId = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    // Invalid IDs are ignored and ordinary search remains available.
                }
            } else if (value.startsWith("tt")) {
                imdbId = value;
            }
        }
        String stripped = ids.replaceAll(" ");
        Matcher imdb = IMDB_ID.matcher(stripped);
        if (imdb.find()) {
            if (imdbId.isEmpty()) {
                imdbId = lower(imdb.group());
            }
            stripped = imdb.replaceAll(" ");
        }
        stripped = EMPTY_BRACKETS.matcher(stripped).replaceAll(" ");

        Matcher technical = RELEASE_TAG.matcher(stripped);
        if (technical.find() && !cleanTitle(stripped.substring(0, technical.start())).isEmpty()) {
            stripped = stripped.substring(0, technical.start());
        }
        Matcher explicitYears = EXPLICIT_YEAR.matcher(stripped);
        int year = 0;
        int yearStart = -1;
        while (explicitYears.find()) {
            if (!cleanTitle(stripped.substring(0, explicitYears.start())).isEmpty()) {
                year = Integer.parseInt(explicitYears.group(1));
                yearStart = explicitYears.start();
            }
        }
        if (yearStart == -1) {
            Matcher bareYears = BARE_YEAR.matcher(stripped);
            while (bareYears.find()) {
                String title = cleanTitle(stripped.substring(0, bareYears.start()));
                String remainder = cleanTitle(stripped.substring(bareYears.end()));
                int possibleYear = Integer.parseInt(bareYears.group(1));
                if (!title.isEmpty() && remainder.isEmpty() && possibleYear <= Year.now().getValue() + 3) {
                    year = possibleYear;
                    yearStart = bareYears.start();
                }
            }
        }
        String title = cleanTitle(yearStart >= 0 ? stripped.substring(0, yearStart) : stripped);
        return new FileNameHints(title, year, imdbId, tmdbId);
    }

    private static FileNameHints combineHints(FileNameHints file, FileNameHints folder, boolean preferFolder) {
        if (folder == null) {
            return file;
        }
        boolean sameTitle = cleanComparable(file.title).equals(cleanComparable(folder.title));
        if (preferFolder) {
            int year = folder.year == 0 && sameTitle ? file.year : folder.year;
            boolean folderHasId = folder.tmdbId > 0 || !folder.imdbId.isEmpty();
            return new FileNameHints(folder.title, year, folderHasId ? folder.imdbId : file.imdbId,
                    folderHasId ? folder.tmdbId : file.tmdbId);
        }
        boolean useFolderTitle = file.title.isEmpty() || GENERIC_TITLE.matcher(file.title).matches();
        String title = useFolderTitle && !folder.title.isEmpty() ? folder.title : file.title;
        boolean related = useFolderTitle || sameTitle;
        int year = file.year != 0 || !related ? file.year : folder.year;
        int tmdbId = file.tmdbId != 0 ? file.tmdbId : folder.tmdbId;
        String imdbId = file.imdbId.isEmpty() ? folder.imdbId : file.imdbId;
        return new FileNameHints(title, year, imdbId, tmdbId);
    }

    private static FileNameHints alternateTitle(FileNameHints file, FileNameHints primary) {
        if (!usableTitle(file.title) || cleanComparable(file.title).equals(cleanComparable(primary.title))
                || (primary.year > 0 && file.year > 0 && Math.abs(file.year - primary.year) > 1)) {
            return null;
        }
        int year = primary.year > 0 ? primary.year : file.year;
        return new FileNameHints(file.title, year, "", 0);
    }

    private static boolean usableTitle(String title) {
        return !cleanComparable(title).isEmpty() && !GENERIC_TITLE.matcher(title).matches()
                && !RELEASE_TAG.matcher(title).lookingAt();
    }

    private static String cleanComparable(String title) {
        return lower(title).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String cleanTitle(String title) {
        return title.replaceAll("[._]+", " ")
                .replaceAll("^[\\s+\\-–—\\[【(（]+|[\\s+\\-–—\\[【(（]+$", "")
                .replaceAll("[\\s\\u3000]+", " ").trim();
    }

    public static String actorArtworkName(String actorName) {
        String legal = ILLEGAL_FILE_NAME_CHARS.matcher(actorName.replace(' ', '_')).replaceAll("_");
        if (legal.isEmpty() || legal.equals(".") || legal.equals("..")) {
            return "";
        }
        return legal + ".jpg";
    }

    public static String collectionFolderName(String title) {
        if (title == null) {
            return "";
        }
        return ILLEGAL_FILE_NAME_CHARS.matcher(title).replaceAll("_").replaceFirst("[. ]+$", "");
    }

    private static boolean isVideo(String name) {
        int extensionStart = name.lastIndexOf('.');
        return extensionStart > 0 && VIDEO_EXTENSIONS.contains(lower(name.substring(extensionStart + 1)));
    }

    private static String stem(String name) {
        int extensionStart = name.lastIndexOf('.');
        return extensionStart > 0 ? name.substring(0, extensionStart) : name;
    }

    private static String childPath(Directory directory, String name) {
        return directory.relativePath.isEmpty() ? name : directory.relativePath + "/" + name;
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static void validateName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(0) >= 0) {
            throw new IOException("无效的输出文件名");
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("操作已取消");
        }
    }
}
