package org.kodiscraper.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class ScrapeEngine {
    static final int ACTOR_LIMIT = 20;
    static final int ALTERNATE_FANART_LIMIT = 3;
    private static final int NFO_READ_LIMIT = 1024 * 1024;
    private static final int ARTWORK_REPAIR_READ_LIMIT = 16 * 1024 * 1024;
    private static final byte[] EMPTY_MOVIE_NFO = "<movie/>".getBytes(StandardCharsets.UTF_8);
    private static final String[] TYPES = {"poster", "fanart", "clearlogo", "landscape", "keyart"};
    private static final String[] SIZES = {"w780", "w1280", "original", "w1280", "w780"};
    private static final String[] EXTENSIONS = {"jpg", "png", "jpeg", "webp"};
    private static final String[] ACTOR_EXTENSIONS = {"jpg", "png", "tbn"};

    enum Result { WRITTEN, INCOMPLETE, PRESERVED, REVIEW }

    static final class Outcome {
        final Result result;
        final SubtitleQuery subtitleQuery;

        Outcome(Result result, SubtitleQuery subtitleQuery) {
            this.result = result;
            this.subtitleQuery = subtitleQuery;
        }
    }

    interface Progress {
        void update(String text);
    }

    private final MovieStorage storage;
    private final MovieStorage collectionStorage;
    private final MovieMetadataSource source;
    private final String language;
    private final boolean overwrite;
    private final boolean actorImages;
    private final boolean alternateFanart;
    private final Set<Integer> savedCollections = new HashSet<>();

    ScrapeEngine(MovieStorage storage, MovieStorage collectionStorage, MovieMetadataSource source,
                 String language, boolean overwrite, boolean actorImages, boolean alternateFanart) {
        this.storage = storage;
        this.collectionStorage = collectionStorage;
        this.source = source;
        this.language = language;
        this.overwrite = overwrite;
        this.actorImages = actorImages;
        this.alternateFanart = alternateFanart;
    }

    boolean isCompleteLocally(MovieStorage.Movie movie) throws IOException {
        try {
            List<NfoSidecar> sidecars = readSidecars(storage, movie);
            return completeNfo(sidecars)
                    && readableArtwork(movie, "poster") && readableArtwork(movie, "fanart");
        } catch (IOException unreadable) {
            checkCancelled();
            return false;
        }
    }

    private boolean readableArtwork(MovieStorage.Movie movie, String type) throws IOException {
        String name = findArtwork(movie, type, movie.singleMovieInDirectory);
        MovieStorage.Entry entry = name == null ? null : storage.find(movie.directory, name);
        return entry != null && storage.hasImageDimensions(entry);
    }

    private static boolean completeNfo(List<NfoSidecar> sidecars) {
        return !sidecars.isEmpty() && sidecars.stream().allMatch(sidecar -> sidecar.ids.fullMovie);
    }

    private boolean isCorruptArtwork(MovieStorage.Movie movie, String name) throws IOException {
        MovieStorage.Entry entry = storage.find(movie.directory, name);
        if (entry == null) throw new IOException("图片文件已移动");
        if (storage.hasImageDimensions(entry)) return false;
        // A failing bounds probe can also mean a document provider lost access. Only replace the
        // file in automatic mode if its actual bytes can still be read and verified as invalid.
        byte[] contents = storage.read(entry, ARTWORK_REPAIR_READ_LIMIT);
        BitmapFactory.Options bounds = imageBounds(contents);
        return bounds.outWidth <= 0 || bounds.outHeight <= 0;
    }

    private static boolean isPrimaryArtwork(String type) {
        return "poster".equals(type) || "fanart".equals(type);
    }

    Outcome scrape(MovieStorage.Movie movie, int selectedId, Progress progress) throws IOException {
        return scrape(movie, selectedId, progress, query -> {});
    }

    Outcome scrape(MovieStorage.Movie movie, int selectedId, Progress progress,
                   Consumer<SubtitleQuery> subtitleMatch) throws IOException {
        checkCancelled();
        List<NfoSidecar> sidecars = readSidecars(storage, movie);
        boolean preserve = !sidecars.isEmpty() && !overwrite;
        boolean nfoComplete = completeNfo(sidecars);
        boolean repairNfo = preserve && !nfoComplete
                && sidecars.stream().allMatch(sidecar -> sidecar.ids.validMovie);
        Identifiers existingIds = preferredIds(sidecars);
        boolean tvdb = source.provider() == MovieMetadataSource.Provider.TVDB;
        int tvdbLocal = tvdb && (selectedId <= 0 || preserve) ? localProviderId(existingIds) : 0;
        SubtitleQuery subtitleQuery = selectedId > 0 && !tvdb
                ? new SubtitleQuery(storage, movie, null, selectedId, null)
                : fallbackSubtitleQuery(storage, movie, sidecars);
        int sourceId = selectedId;
        if (sourceId <= 0) {
            if (tvdb) {
                sourceId = tvdbLocal;
                if (sourceId <= 0 && movie.imdbId != null && !movie.imdbId.isEmpty()) {
                    sourceId = source.findMovieByImdb(movie.imdbId);
                }
                if (sourceId <= 0 && movie.tmdbId > 0) sourceId = source.findMovieByTmdb(movie.tmdbId);
            } else {
                sourceId = movie.tmdbId > 0 ? movie.tmdbId : existingIds.tmdbId;
                String imdbId = movie.imdbId == null || movie.imdbId.isEmpty() ? existingIds.imdbId : movie.imdbId;
                if (sourceId <= 0 && imdbId != null && !imdbId.isEmpty()) {
                    sourceId = source.findMovieByImdb(imdbId);
                    if (sourceId <= 0 && !preserve) return new Outcome(Result.REVIEW, subtitleQuery);
                }
            }
        }
        if (preserve) {
            int trustedId = tvdb ? tvdbLocal : localProviderId(existingIds);
            if (trustedId <= 0 && !existingIds.hasIdentity() && hasFilenameId(movie)) {
                trustedId = sourceId;
            }
            if (trustedId <= 0 || (sourceId > 0 && sourceId != trustedId)
                    || hasConflictingIds(sidecars, existingIds, tvdb ? existingIds.tmdbId : trustedId)) {
                if (nfoComplete && readableArtwork(movie, "poster") && readableArtwork(movie, "fanart")) {
                    return new Outcome(Result.PRESERVED, subtitleQuery);
                }
                progress.update("无法确认已有资料的影片身份，保留原文件，请手动匹配。");
                return new Outcome(Result.REVIEW, subtitleQuery);
            }
            sourceId = trustedId;
        }
        if (sourceId <= 0) {
            sourceId = confidentMatch(source.search(movie.title, movie.year), 0);
            if (sourceId <= 0 && !movie.alternateTitle.isEmpty()) {
                sourceId = confidentMatch(source.search(movie.alternateTitle, movie.alternateYear), movie.year);
            }
            if (sourceId <= 0) {
                return new Outcome(Result.REVIEW, subtitleQuery);
            }
        }

        JSONObject details = source.details(sourceId);
        if (selectedId <= 0 && (tvdb || preserve)) {
            if (conflictsWithDetails(movie, existingIds, details, preserve)) {
                progress.update("刮削来源与已有影片编号不一致，保留原文件，请手动匹配。");
                return new Outcome(Result.REVIEW, subtitleQuery);
            }
        }
        subtitleQuery = new SubtitleQuery(storage, movie, details, tvdb ? details.optInt("id") : sourceId, null);
        subtitleMatch.accept(subtitleQuery);
        String supplementWarning = details.optString("supplement_warning");
        if (!supplementWarning.isEmpty()) progress.update(supplementWarning);
        Map<String, String> artwork = new HashMap<>();
        Map<String, String> damagedArtwork = new HashMap<>();
        Set<String> unreadableArtwork = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        for (String type : TYPES) {
            String existing = findArtwork(movie, type);
            if (existing != null) {
                try {
                    if (!overwrite && isPrimaryArtwork(type) && isCorruptArtwork(movie, existing)) {
                        damagedArtwork.put(type, existing);
                    } else {
                        artwork.put(type, existing);
                    }
                } catch (IOException unreadable) {
                    checkCancelled();
                    unreadableArtwork.add(type);
                    warnings.add(artworkLabel(type) + "（原文件无法读取，已保留）");
                }
            }
        }
        byte[] updatedNfo = null;
        if (!preserve || repairNfo) {
            progress.update("正在合并 Kodi 本地资料");
            byte[][] previous = new byte[sidecars.size()][];
            for (int i = 0; i < sidecars.size(); i++) {
                previous[i] = sidecars.get(i).contents;
            }
            ByteArrayOutputStream xml = new ByteArrayOutputStream();
            KodiNfo.write(xml, details, artwork, new HashMap<>(), language, region(language),
                    movie.localTrailerPath != null);
            updatedNfo = KodiNfo.preserveUserFields(xml.toByteArray(), previous);
        }
        int artWritten = 0;
        List<String> unavailable = new ArrayList<>();
        for (int i = 0; i < TYPES.length; i++) {
            checkCancelled();
            String type = TYPES[i];
            if (unreadableArtwork.contains(type)) continue;
            String path = source.artwork(details, type);
            try {
                String target = artwork.get(type);
                if (target == null && (path == null || path.isEmpty()) && isPrimaryArtwork(type)) {
                    unavailable.add(artworkLabel(type));
                }
                if ((target == null || overwrite) && path != null && !path.isEmpty()) {
                    String damaged = damagedArtwork.get(type);
                    if (target == null) {
                        target = damaged == null
                                ? movie.artworkName(type, "clearlogo".equals(type) ? "png" : "jpg") : damaged;
                    }
                    boolean written = damaged == null
                            ? writeImage(storage, movie.directory, target, path, SIZES[i])
                            : repairImage(movie, target, path, SIZES[i]);
                    if (written) {
                        artWritten++;
                    } else if (isPrimaryArtwork(type) && !readableArtwork(movie, type)) {
                        warnings.add(artworkLabel(type));
                    }
                }
            } catch (IOException problem) {
                rethrowFatal(problem);
                warnings.add(artworkLabel(type));
            }
        }
        if (actorImages) {
            try {
                artWritten += saveActors(movie, details, warnings);
            } catch (IOException problem) {
                rethrowFatal(problem);
                warnings.add("演员头像");
            }
        }
        if (alternateFanart) {
            artWritten += saveAlternateFanart(movie, details, warnings);
        }
        if (collectionStorage != null) {
            try {
                artWritten += saveCollectionArtwork(details);
            } catch (IOException problem) {
                rethrowFatal(problem);
                warnings.add("合集图片");
            }
        }
        checkCancelled();
        boolean nfoWritten = false;
        boolean nfoIncomplete = preserve && !nfoComplete && !repairNfo;
        if (updatedNfo != null) {
            if (repairNfo) {
                for (NfoSidecar sidecar : sidecars) {
                    if (!sidecar.ids.fullMovie) {
                        boolean repaired = repairNfo(movie, sidecar, updatedNfo, progress);
                        nfoWritten |= repaired;
                        nfoIncomplete |= !repaired;
                    }
                }
            } else {
                nfoWritten = storage.write(movie.directory, movie.nfoName, "application/xml", updatedNfo, overwrite);
                for (NfoSidecar sidecar : sidecars) {
                    if (!sidecar.name.equalsIgnoreCase(movie.nfoName)) {
                        nfoWritten |= storage.write(movie.directory, sidecar.name, "application/xml", updatedNfo, true);
                    }
                }
                if (!nfoWritten) {
                    throw new IOException("NFO 输出位置已占用，未更新影片资料。");
                }
            }
        }
        if (!warnings.isEmpty()) {
            progress.update("以下图片写入失败：" + String.join("、", warnings));
        }
        if (!unavailable.isEmpty()) {
            progress.update(source.provider().label + " 暂无以下图片：" + String.join("、", unavailable));
        }
        if (preserve && !nfoComplete && !repairNfo) {
            progress.update("已有 NFO 无法安全合并，已保留原文件。");
        }
        if (!warnings.isEmpty() || !unavailable.isEmpty() || nfoIncomplete) {
            return new Outcome(Result.INCOMPLETE, subtitleQuery);
        }
        return new Outcome(nfoWritten || artWritten > 0 ? Result.WRITTEN : Result.PRESERVED, subtitleQuery);
    }

    private boolean repairNfo(MovieStorage.Movie movie, NfoSidecar sidecar, byte[] updated, Progress progress)
            throws IOException {
        MovieStorage.Entry current = storage.find(movie.directory, sidecar.name);
        byte[] currentBytes;
        try {
            currentBytes = current == null ? null : storage.read(current, NFO_READ_LIMIT);
        } catch (IOException unreadable) {
            checkCancelled();
            progress.update("已有 NFO 无法重新读取，已保留。");
            return false;
        }
        if (!Arrays.equals(sidecar.contents, currentBytes)) {
            progress.update("已有 NFO 在刮削期间发生变化，已保留。");
            return false;
        }
        return storage.write(movie.directory, sidecar.name, "application/xml", updated, true);
    }

    private int localProviderId(Identifiers ids) throws IOException {
        if (source.provider() == MovieMetadataSource.Provider.TMDB && ids.tmdbId > 0) return ids.tmdbId;
        if (source.provider() == MovieMetadataSource.Provider.TVDB && ids.tvdbId > 0) return ids.tvdbId;
        int matched = ids.imdbId == null ? 0 : source.findMovieByImdb(ids.imdbId);
        return matched > 0 || ids.tmdbId <= 0 ? matched : source.findMovieByTmdb(ids.tmdbId);
    }

    private static boolean hasFilenameId(MovieStorage.Movie movie) {
        return movie.tmdbId > 0 || (movie.imdbId != null && !movie.imdbId.isEmpty());
    }

    private static boolean conflictsWithDetails(MovieStorage.Movie movie, Identifiers existingIds,
                                                JSONObject details, boolean preserve) {
        JSONObject external = details.optJSONObject("external_ids");
        int tmdbId = details.optInt("id");
        int tvdbId = external == null ? 0 : external.optInt("tvdb_id");
        String imdbId = TvdbMovie.text(external, "imdb_id");
        if (imdbId.isEmpty()) imdbId = TvdbMovie.text(details, "imdb_id");
        return (movie.tmdbId > 0 && tmdbId > 0 && movie.tmdbId != tmdbId)
                || imdbConflict(movie.imdbId, imdbId)
                || (preserve && ((existingIds.tmdbId > 0 && tmdbId > 0 && existingIds.tmdbId != tmdbId)
                || (existingIds.tvdbId > 0 && tvdbId > 0 && existingIds.tvdbId != tvdbId)
                || imdbConflict(existingIds.imdbId, imdbId)));
    }

    private static boolean imdbConflict(String expected, String received) {
        return expected != null && !expected.isEmpty() && !received.isEmpty() && !expected.equalsIgnoreCase(received);
    }

    private static int confidentMatch(List<TmdbClient.Candidate> results, int requiredYear) {
        if (results.isEmpty()) {
            return 0;
        }
        TmdbClient.Candidate candidate = results.get(0);
        return candidate.confident && (requiredYear <= 0 || candidate.year == requiredYear) ? candidate.id : 0;
    }

    private int saveAlternateFanart(MovieStorage.Movie movie, JSONObject details, List<String> warnings)
            throws IOException {
        List<String> images = source.alternateFanart(details, ALTERNATE_FANART_LIMIT);
        int written = 0;
        int failures = 0;
        for (int i = 0; i < images.size(); i++) {
            String type = "fanart" + (i + 1);
            try {
                String target = findArtwork(movie, type);
                if (target == null || overwrite) {
                    target = target == null ? movie.artworkName(type, "jpg") : target;
                    if (writeImage(storage, movie.directory, target, images.get(i), "w1280")) {
                        written++;
                    }
                }
            } catch (IOException problem) {
                rethrowFatal(problem);
                failures++;
            }
        }
        if (failures > 0) {
            warnings.add(failures + " 张备用背景图");
        }
        return written;
    }

    private int saveCollectionArtwork(JSONObject movie) throws IOException {
        JSONObject collection = movie.optJSONObject("belongs_to_collection");
        int id = collection == null ? 0 : collection.optInt("id");
        if (id <= 0 || savedCollections.contains(id)) {
            return 0;
        }
        String folder = MovieStorage.collectionFolderName(KodiNfo.collectionName(movie));
        String poster = source.collectionArtwork(movie, "poster");
        String fanart = source.collectionArtwork(movie, "fanart");
        if (folder.isEmpty() || (poster.isEmpty() && fanart.isEmpty())) {
            return 0;
        }
        MovieStorage.Directory directory = collectionStorage.subdirectory(collectionStorage.rootDirectory(), folder);
        int written = saveCollectionImage(directory, "poster", poster, "w780")
                + saveCollectionImage(directory, "fanart", fanart, "w1280");
        savedCollections.add(id);
        return written;
    }

    private int saveCollectionImage(MovieStorage.Directory directory, String type, String path, String size)
            throws IOException {
        if (path.isEmpty()) {
            return 0;
        }
        if (findImage(collectionStorage, directory, type) != null) {
            return 0;
        }
        String name = type + ".jpg";
        byte[] data = formattedImage(source.image(path, size), name);
        return collectionStorage.write(directory, name, mime(name), data, false) ? 1 : 0;
    }

    private boolean writeImage(MovieStorage targetStorage, MovieStorage.Directory directory, String name,
                               String path, String size) throws IOException {
        byte[] data = formattedImage(source.image(path, size), name);
        return targetStorage.write(directory, name, mime(name), data, overwrite);
    }

    private boolean repairImage(MovieStorage.Movie movie, String name, String path, String size) throws IOException {
        byte[] data = formattedImage(source.image(path, size), name);
        if (!isCorruptArtwork(movie, name)) return false;
        return storage.write(movie.directory, name, mime(name), data, true);
    }

    private int saveActors(MovieStorage.Movie movie, JSONObject details, List<String> warnings) throws IOException {
        JSONObject credits = details.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        if (cast == null) {
            return 0;
        }
        MovieStorage.Directory actors = null;
        Set<String> used = new HashSet<>();
        int imageCount = 0;
        int written = 0;
        int failed = 0;
        for (int i = 0; i < cast.length() && imageCount < ACTOR_LIMIT; i++) {
            checkCancelled();
            JSONObject person = cast.optJSONObject(i);
            if (person == null) {
                continue;
            }
            String profile = source.castProfile(person);
            String name = KodiNfo.personName(person);
            if (profile == null || profile.isEmpty() || name.isEmpty()) {
                continue;
            }
            String target = MovieStorage.actorArtworkName(name);
            if (target.isEmpty() || !used.add(target.toLowerCase(Locale.ROOT))) {
                continue;
            }
            imageCount++;
            if (actors == null) {
                actors = storage.subdirectory(movie.directory, ".actors");
            }
            try {
                String existing = findActorImage(actors, target);
                if (existing != null && !overwrite) {
                    continue;
                }
                if (writeImage(storage, actors, existing == null ? target : existing, profile, "w185")) {
                    written++;
                }
            } catch (IOException problem) {
                rethrowFatal(problem);
                failed++;
            }
        }
        if (failed > 0) {
            warnings.add(failed + " 张演员头像");
        }
        return written;
    }

    private static void rethrowFatal(IOException problem) throws IOException {
        checkCancelled();
        if (problem instanceof MovieMetadataSource.HttpError http) {
            int code = http.statusCode;
            if (code == 401 || code == 403 || code == 429) {
                throw problem;
            }
        }
    }

    private String findArtwork(MovieStorage.Movie movie, String type) throws IOException {
        return findArtwork(movie, type, !overwrite && movie.singleMovieInDirectory);
    }

    private String findArtwork(MovieStorage.Movie movie, String type, boolean allowFolderName) throws IOException {
        String named = movie.artworkName(type, "jpg");
        String image = findImage(storage, movie.directory, named.substring(0, named.length() - 4));
        if (image == null && allowFolderName) {
            image = findImage(storage, movie.directory, type);
        }
        return image;
    }

    private static String findImage(MovieStorage storage, MovieStorage.Directory directory, String stem)
            throws IOException {
        return findImage(storage, directory, stem, EXTENSIONS);
    }

    private static String findImage(MovieStorage storage, MovieStorage.Directory directory, String stem,
                                    String[] extensions) throws IOException {
        for (String extension : extensions) {
            MovieStorage.Entry entry = storage.find(directory, stem + "." + extension);
            if (entry != null && !(entry instanceof MovieStorage.Directory)) {
                return entry.displayName;
            }
        }
        return null;
    }

    private String findActorImage(MovieStorage.Directory directory, String jpgName) throws IOException {
        return findImage(storage, directory, jpgName.substring(0, jpgName.length() - 4),
                ACTOR_EXTENSIONS);
    }

    private static byte[] formattedImage(byte[] data, String target) throws IOException {
        BitmapFactory.Options bounds = imageBounds(data);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("刮削来源返回无法读取的图片");
        }
        String type = mime(target);
        if (type.equals("image/jpeg") && data.length > 3
                && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) {
            return data;
        }
        if (type.equals("image/png") && data.length > 8 && (data[0] & 0xff) == 0x89
                && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return data;
        }
        if (type.equals("image/webp") && data.length > 12 && data[8] == 'W' && data[9] == 'E'
                && data[10] == 'B' && data[11] == 'P') {
            return data;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 3000) {
            options.inSampleSize *= 2;
        }
        Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (image == null) {
            throw new IOException("刮削来源返回无法读取的图片");
        }
        Bitmap.CompressFormat format = Bitmap.CompressFormat.JPEG;
        if (type.equals("image/png")) {
            format = Bitmap.CompressFormat.PNG;
        } else if (type.equals("image/webp")) {
            format = Bitmap.CompressFormat.WEBP;
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!image.compress(format, 92, output)) {
                throw new IOException("无法转换图片格式");
            }
            return output.toByteArray();
        } finally {
            image.recycle();
        }
    }

    private static BitmapFactory.Options imageBounds(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        return bounds;
    }

    static SubtitleQuery subtitleQuery(MovieStorage storage, MovieStorage.Movie movie) throws IOException {
        try {
            return fallbackSubtitleQuery(storage, movie, readSidecars(storage, movie));
        } catch (IOException unreadable) {
            checkCancelled();
            return new SubtitleQuery(storage, movie, null, 0, null);
        }
    }

    private static SubtitleQuery fallbackSubtitleQuery(MovieStorage storage, MovieStorage.Movie movie,
                                                       List<NfoSidecar> sidecars) {
        Identifiers ids = preferredIds(sidecars);
        if (movie.tmdbId > 0 || (movie.imdbId != null && !movie.imdbId.isEmpty())
                || hasConflictingIds(sidecars, ids, ids.tmdbId)) {
            return new SubtitleQuery(storage, movie, null, 0, null);
        }
        return new SubtitleQuery(storage, movie, null, ids.tmdbId, ids.imdbId);
    }

    private static List<NfoSidecar> readSidecars(MovieStorage storage, MovieStorage.Movie movie) throws IOException {
        List<String> names = new ArrayList<>();
        names.add(movie.nfoName);
        names.addAll(movie.legacyNfoNames);
        List<NfoSidecar> sidecars = new ArrayList<>();
        for (String name : names) {
            MovieStorage.Entry entry = storage.find(movie.directory, name);
            if (entry != null && !(entry instanceof MovieStorage.Directory)) {
                byte[] contents = storage.read(entry, NFO_READ_LIMIT);
                sidecars.add(new NfoSidecar(entry.displayName, contents, readIds(contents)));
            }
        }
        return sidecars;
    }

    private static Identifiers preferredIds(List<NfoSidecar> sidecars) {
        for (NfoSidecar sidecar : sidecars) {
            if (sidecar.ids.hasIdentity()) {
                return sidecar.ids;
            }
        }
        return new Identifiers();
    }

    private static boolean hasConflictingIds(List<NfoSidecar> sidecars, Identifiers preferred, int tmdbId) {
        for (NfoSidecar sidecar : sidecars) {
            Identifiers ids = sidecar.ids;
            if ((ids.tmdbId > 0 && ids.tmdbId != tmdbId)
                    || (preferred.tvdbId > 0 && ids.tvdbId > 0 && ids.tvdbId != preferred.tvdbId)
                    || (preferred.imdbId != null && ids.imdbId != null && !preferred.imdbId.equals(ids.imdbId))) {
                return true;
            }
        }
        return false;
    }

    private static Identifiers readIds(byte[] bytes) throws IOException {
        Identifiers ids = new Identifiers();
        boolean movieRoot = false;
        boolean titled = false;
        boolean closed = false;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new ByteArrayInputStream(bytes), null);
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.nextToken()) {
                if (event == XmlPullParser.DOCDECL) return new Identifiers();
                if (event == XmlPullParser.TEXT && parser.getDepth() == 0 && !parser.isWhitespace()) {
                    if (!closed || !safeMovieNfo(bytes)) return new Identifiers();
                    break;
                }
                if (event == XmlPullParser.START_TAG && parser.getDepth() == 1) {
                    if (movieRoot || !"movie".equals(parser.getName())) return new Identifiers();
                    movieRoot = true;
                }
                if (event == XmlPullParser.END_TAG && parser.getDepth() == 1) {
                    closed = true;
                }
                if (event != XmlPullParser.START_TAG || parser.getDepth() != 2) {
                    continue;
                }
                String tag = parser.getName();
                if (tag.equals("title")) {
                    titled |= !parser.nextText().trim().isEmpty();
                } else if (tag.equals("uniqueid")) {
                    String type = parser.getAttributeValue(null, "type");
                    String value = parser.nextText().trim();
                    if ("tmdb".equalsIgnoreCase(type)) {
                        ids.tmdbId = parseId(value);
                    } else if ("tvdb".equalsIgnoreCase(type)) {
                        ids.tvdbId = parseId(value);
                    } else if ("imdb".equalsIgnoreCase(type) && value.matches("tt[0-9]+")) {
                        ids.imdbId = value;
                    }
                } else if (tag.equals("tmdbid")) {
                    int legacyId = parseId(parser.nextText().trim());
                    if (ids.tmdbId <= 0) {
                        ids.tmdbId = legacyId;
                    }
                } else if (tag.equals("id") || tag.equals("imdbid")) {
                    String value = parser.nextText().trim();
                    if (ids.imdbId == null && value.matches("tt[0-9]+")) {
                        ids.imdbId = value;
                    }
                }
            }
        } catch (Exception ignored) {
            checkCancelled();
            // Kodi combination NFOs may append a provider URL after a complete movie element.
            if (!closed || !safeMovieNfo(bytes)) return new Identifiers();
        }
        ids.validMovie = movieRoot && closed;
        ids.fullMovie = ids.validMovie && titled;
        return ids;
    }

    private static boolean safeMovieNfo(byte[] bytes) {
        try {
            KodiNfo.preserveUserFields(EMPTY_MOVIE_NFO, bytes);
            return true;
        } catch (IOException malformed) {
            return false;
        }
    }

    static int parseId(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("操作已停止");
        }
    }

    private static String region(String language) {
        int separator = language.indexOf('-');
        return separator < 0 ? "CN" : language.substring(separator + 1);
    }

    private static String mime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static String artworkLabel(String type) {
        switch (type) {
            case "poster": return "海报";
            case "fanart": return "背景图";
            case "clearlogo": return "透明标志";
            case "landscape": return "横版图";
            default: return "无字主视觉";
        }
    }

    private static final class Identifiers {
        int tmdbId;
        int tvdbId;
        String imdbId;
        boolean validMovie;
        boolean fullMovie;

        boolean hasIdentity() {
            return tmdbId > 0 || tvdbId > 0 || imdbId != null;
        }
    }

    private static final class NfoSidecar {
        final String name;
        final byte[] contents;
        final Identifiers ids;

        NfoSidecar(String name, byte[] contents, Identifiers ids) {
            this.name = name;
            this.contents = contents;
            this.ids = ids;
        }
    }

}
