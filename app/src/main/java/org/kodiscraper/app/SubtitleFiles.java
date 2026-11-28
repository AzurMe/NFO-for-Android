package org.kodiscraper.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class SubtitleFiles {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final String SUBTITLE_DIRECTORY = "Subs";
    private static final Set<String> LOCAL_SUBTITLE_DIRECTORIES = new HashSet<>(Arrays.asList(
            "subs", "sub", "subtitles", "subtitle", "vobsubs", "vobsub"));
    private static final Set<String> OTHER_LOCAL_EXTENSIONS = new HashSet<>(Arrays.asList(
            "sub", "smi", "sami", "sup", "mpl", "ttml", "scc"));
    private static final String CHINESE_SUFFIX = ".CHN";
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Charset BIG5 = Charset.forName("Big5");
    private static final Pattern OWN_SUBTITLE_SUFFIX = Pattern.compile("(?:\\.[0-9]+)?\\.(?:srt|ass|ssa|vtt)");
    private static final String SRT_TIME = "[0-9]{1,3}:[0-5][0-9]:[0-5][0-9][,.][0-9]{3}";
    private static final String VTT_TIME = "(?:[0-9]{2,}:)?[0-5][0-9]:[0-5][0-9]\\.[0-9]{3}";
    private static final Pattern SRT_CUE = cuePattern(SRT_TIME);
    private static final Pattern VTT_CUE = cuePattern(VTT_TIME);
    private static final Pattern CUE_NUMBER = Pattern.compile("[0-9]{1,9}");
    private static final Pattern ASS_TIME = Pattern.compile("[0-9]{1,3}:[0-5][0-9]:[0-5][0-9]\\.[0-9]{1,3}");
    private static final Pattern CUE_TAG = Pattern.compile("<[^>\\n]*>");
    private static final Pattern ASS_TAG = Pattern.compile("\\{[^}\\n]*\\}");

    private SubtitleFiles() {
    }

    static final class Track {
        final String extension;
        private final byte[] bytes;

        private Track(String extension, byte[] bytes) {
            this.extension = extension;
            this.bytes = bytes;
        }
    }

    static final class Result {
        final List<String> writtenFiles;
        final int written;
        final int duplicates;
        final IOException failure;

        Result(List<String> writtenFiles, int duplicates, IOException failure) {
            this.writtenFiles = Collections.unmodifiableList(new ArrayList<>(writtenFiles));
            this.written = writtenFiles.size();
            this.duplicates = duplicates;
            this.failure = failure;
        }
    }

    static boolean supportsFilename(String filename) {
        return !extension(filename).isEmpty();
    }

    static boolean hasLocalSubtitle(MovieStorage storage, MovieStorage.Movie movie) throws IOException {
        boolean ownFolder = movie.singleMovieInDirectory && movie.directory.parent != null;
        String basename = movie.basename().toLowerCase(Locale.ROOT);
        String videoStem = stem(movie.displayName.toLowerCase(Locale.ROOT));
        List<MovieStorage.Entry> entries = storage.list(movie.directory);
        if (hasTrack(storage, entries, ownFolder, basename, videoStem)) return true;
        for (MovieStorage.Entry entry : entries) {
            checkCancelled();
            if (entry instanceof MovieStorage.Directory folder
                    && LOCAL_SUBTITLE_DIRECTORIES.contains(entry.displayName.toLowerCase(Locale.ROOT))) {
                List<MovieStorage.Entry> tracks = storage.list(folder);
                if (hasTrack(storage, tracks, ownFolder, basename, videoStem)) return true;
                for (MovieStorage.Entry child : tracks) {
                    checkCancelled();
                    if (child instanceof MovieStorage.Directory language && !child.displayName.startsWith(".")
                            && hasTrack(storage, storage.list(language), ownFolder, basename, videoStem)) return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTrack(MovieStorage storage, List<MovieStorage.Entry> entries, boolean ownFolder,
                                    String basename, String videoStem) throws IOException {
        for (MovieStorage.Entry entry : entries) {
            checkCancelled();
            if (entry instanceof MovieStorage.Directory || entry.displayName.startsWith(".")) continue;
            String name = entry.displayName.toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            if (dot <= 0 || (!supportsFilename(name) && !OTHER_LOCAL_EXTENSIONS.contains(name.substring(dot + 1)))) {
                continue;
            }
            String trackStem = name.substring(0, dot);
            if ((ownFolder || matchesVideo(trackStem, basename) || matchesVideo(trackStem, videoStem))
                    && storage.hasContent(entry)) return true;
        }
        return false;
    }

    private static boolean matchesVideo(String track, String video) {
        return track.equals(video) || track.startsWith(video + ".");
    }

    private static String stem(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    static Track validate(String filename, byte[] payload) throws IOException {
        return validate(filename, payload, false);
    }

    static Track validate(String filename, byte[] payload, boolean preferBig5) throws IOException {
        String extension = extension(filename);
        if (extension.isEmpty()) {
            throw new IOException("不支持的字幕格式");
        }
        String text = normalize(payload, preferBig5);
        boolean hasChinese;
        switch (extension) {
            case "ass":
            case "ssa":
                hasChinese = hasChineseAssDialogue(text);
                break;
            case "vtt":
                hasChinese = hasChineseTimedCue(text, VTT_CUE, true);
                break;
            default:
                hasChinese = hasChineseTimedCue(text, SRT_CUE, false);
                break;
        }
        if (!hasChinese) {
            throw new IOException("文件中没有可读取的中文字幕");
        }
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_BYTES) {
            throw new IOException("字幕文件超过大小上限");
        }
        return new Track(extension, utf8);
    }

    static Result save(MovieStorage storage, MovieStorage.Movie movie, List<Track> tracks) throws IOException {
        if (tracks.isEmpty()) {
            return new Result(Collections.emptyList(), 0, null);
        }
        MovieStorage.Directory directory = storage.subdirectory(movie.directory, SUBTITLE_DIRECTORY);
        String filenamePrefix = movie.basename() + CHINESE_SUFFIX;
        String foldedPrefix = filenamePrefix.toLowerCase(Locale.ROOT);
        Set<String> occupied = new HashSet<>();
        Set<String> checksums = new HashSet<>();
        for (MovieStorage.Entry entry : storage.list(directory)) {
            checkCancelled();
            String foldedName = entry.displayName.toLowerCase(Locale.ROOT);
            occupied.add(foldedName);
            if (!(entry instanceof MovieStorage.Directory) && foldedName.startsWith(foldedPrefix)
                    && OWN_SUBTITLE_SUFFIX.matcher(foldedName.substring(foldedPrefix.length())).matches()) {
                addExistingChecksum(storage, entry, checksums);
            }
        }

        List<String> writtenFiles = new ArrayList<>(tracks.size());
        int duplicates = 0;
        try {
            for (Track track : tracks) {
                checkCancelled();
                if (!checksums.add(checksum(track.bytes))) {
                    duplicates++;
                    continue;
                }
                for (int number = 1; ; number++) {
                    String suffix = number == 1 ? "" : "." + number;
                    String filename = filenamePrefix + suffix + "." + track.extension;
                    if (occupied.add(filename.toLowerCase(Locale.ROOT))
                            && storage.write(directory, filename, "application/octet-stream", track.bytes, false)) {
                        writtenFiles.add(directory.displayName + "/" + filename);
                        break;
                    }
                }
            }
        } catch (IOException failure) {
            if (writtenFiles.isEmpty() && duplicates == 0) {
                checkCancelled();
                throw failure;
            }
            return new Result(writtenFiles, duplicates, failure);
        }
        return new Result(writtenFiles, duplicates, null);
    }

    private static void addExistingChecksum(MovieStorage storage, MovieStorage.Entry entry, Set<String> checksums)
            throws IOException {
        try {
            byte[] original = storage.read(entry, MAX_BYTES);
            checksums.add(checksum(normalize(original, false).getBytes(StandardCharsets.UTF_8)));
            checksums.add(checksum(normalize(original, true).getBytes(StandardCharsets.UTF_8)));
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException unreadable) {
            // Reserve the file name even when the existing track cannot be compared.
        }
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        String[] segments = filename.replace('\\', '/').split("/", -1);
        if (segments[0].length() > 1 && segments[0].charAt(1) == ':' && segments[0].charAt(0) < 128
                && Character.isLetter(segments[0].charAt(0))) {
            return "";
        }
        for (String segment : segments) {
            if (segment.isEmpty() || segment.startsWith(".") || segment.equalsIgnoreCase("__MACOSX")
                    || segment.indexOf(0) >= 0) {
                return "";
            }
        }
        String name = segments[segments.length - 1];
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "ass":
            case "srt":
            case "ssa":
            case "vtt":
                return ext;
            default:
                return "";
        }
    }

    private static String normalize(byte[] payload, boolean preferBig5) throws IOException {
        checkCancelled();
        if (payload == null || payload.length == 0 || payload.length > MAX_BYTES) {
            throw new IOException("字幕文件为空或超过大小上限");
        }
        String text;
        try {
            if (startsWith(payload, 0xef, 0xbb, 0xbf)) {
                text = decode(payload, 3, StandardCharsets.UTF_8);
            } else if (startsWith(payload, 0xff, 0xfe)) {
                text = decode(payload, 2, StandardCharsets.UTF_16LE);
            } else if (startsWith(payload, 0xfe, 0xff)) {
                text = decode(payload, 2, StandardCharsets.UTF_16BE);
            } else {
                try {
                    text = decode(payload, 0, StandardCharsets.UTF_8);
                } catch (CharacterCodingException notUtf8) {
                    try {
                        text = decode(payload, 0, preferBig5 ? BIG5 : GB18030);
                    } catch (CharacterCodingException notPreferred) {
                        text = decode(payload, 0, preferBig5 ? GB18030 : BIG5);
                    }
                }
            }
        } catch (CharacterCodingException malformed) {
            throw new IOException("字幕文字编码无法读取", malformed);
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isISOControl(character) && character != '\r' && character != '\n' && character != '\t') {
                throw new IOException("字幕文件包含非文本数据");
            }
        }
        String lines = text.replace("\r\n", "\n").replace('\r', '\n');
        int end = lines.length();
        while (end > 0 && lines.charAt(end - 1) == '\n') {
            end--;
        }
        return lines.substring(0, end) + '\n';
    }

    private static boolean startsWith(byte[] data, int... bytes) {
        if (data.length < bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if ((data[i] & 0xff) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static String decode(byte[] payload, int offset, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload, offset, payload.length - offset)).toString();
    }

    private static Pattern cuePattern(String time) {
        return Pattern.compile(time + "[ \\t]+-->[ \\t]+" + time + "(?:[ \\t].*)?");
    }

    private static boolean hasChineseTimedCue(String text, Pattern cue, boolean vtt) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String header;
            do {
                checkCancelled();
                header = reader.readLine();
            } while (header != null && header.trim().isEmpty());
            if (header == null) {
                return false;
            }
            header = header.trim();
            if (vtt) {
                if (!(header.equals("WEBVTT") || header.startsWith("WEBVTT ") || header.startsWith("WEBVTT\t"))) {
                    return false;
                }
            } else if (CUE_NUMBER.matcher(header).matches()) {
                header = reader.readLine();
            }
            if (!vtt && (header == null || !cue.matcher(header.trim()).matches())) {
                return false;
            }
            boolean insideCue = !vtt;
            String raw;
            while ((raw = reader.readLine()) != null) {
                checkCancelled();
                String line = raw.trim();
                if (line.isEmpty()) {
                    insideCue = false;
                } else if (cue.matcher(line).matches()) {
                    insideCue = true;
                } else if (insideCue && containsChineseText(CUE_TAG.matcher(line).replaceAll(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasChineseAssDialogue(String text) throws IOException {
        boolean hasScriptInfo = false;
        boolean inEvents = false;
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                checkCancelled();
                String line = raw.trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    hasScriptInfo |= line.equalsIgnoreCase("[Script Info]");
                    inEvents = line.equalsIgnoreCase("[Events]");
                } else if (hasScriptInfo && inEvents && line.regionMatches(true, 0, "Dialogue:", 0, 9)) {
                    String[] fields = line.substring(9).split(",", 10);
                    if (fields.length == 10 && ASS_TIME.matcher(fields[1].trim()).matches()
                            && ASS_TIME.matcher(fields[2].trim()).matches()
                            && containsChineseText(ASS_TAG.matcher(fields[9]).replaceAll(""))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsChineseText(String text) {
        boolean hasHan = false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return false;
            }
            hasHan |= script == Character.UnicodeScript.HAN;
            i += Character.charCount(codePoint);
        }
        return hasHan;
    }

    private static String checksum(byte[] bytes) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("操作已取消");
        }
    }
}
