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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ScrapeEngine {
    static final int ACTOR_LIMIT = 20;
    static final int ALTERNATE_FANART_LIMIT = 3;
    private static final int NFO_READ_LIMIT = 1024 * 1024;
    private static final String[] TYPES = {"poster", "fanart", "clearlogo", "landscape", "keyart"};
    private static final String[] SIZES = {"w780", "w1280", "original", "w1280", "w780"};
    private static final String[] EXTENSIONS = {"jpg", "png", "jpeg", "webp"};
    private static final String[] ACTOR_EXTENSIONS = {"jpg", "png", "tbn"};

    enum Result { WRITTEN, INCOMPLETE, PRESERVED, REVIEW }

    interface Progress {
        void update(String text);
    }

    private final MovieStorage storage;
    private final MovieStorage collectionStorage;
    private final TmdbClient tmdb;
    private final String language;
    private final boolean overwrite;
    private final boolean actorImages;
    private final boolean alternateFanart;
    private final Set<Integer> savedCollections = new HashSet<>();

    ScrapeEngine(MovieStorage storage, MovieStorage collectionStorage, TmdbClient tmdb,
                 String language, boolean overwrite, boolean actorImages, boolean alternateFanart) {
        this.storage = storage;
        this.collectionStorage = collectionStorage;
        this.tmdb = tmdb;
        this.language = language;
        this.overwrite = overwrite;
        this.actorImages = actorImages;
        this.alternateFanart = alternateFanart;
    }

    Result scrape(MovieStorage.Movie movie, int selectedId, Progress progress) throws IOException {
        checkCancelled();
        List<NfoSidecar> sidecars = readSidecars(movie);
        boolean preserve = !sidecars.isEmpty() && !overwrite;
        Identifiers existingIds = preferredIds(sidecars);
        int tmdbId = selectedId;
        if (tmdbId <= 0) {
            tmdbId = movie.tmdbId > 0 ? movie.tmdbId : existingIds.tmdbId;
            String imdbId = movie.imdbId == null || movie.imdbId.isEmpty() ? existingIds.imdbId : movie.imdbId;
            if (tmdbId <= 0 && imdbId != null && !imdbId.isEmpty()) {
                tmdbId = tmdb.findMovieByImdb(imdbId);
                if (tmdbId <= 0) {
                    return preserve ? Result.PRESERVED : Result.REVIEW;
                }
            }
        }
        if (preserve) {
            // Existing metadata without an identity cannot safely be used to backfill its artwork.
            if (existingIds.tmdbId <= 0 && existingIds.imdbId == null) {
                return Result.PRESERVED;
            }
            int trustedId = existingIds.tmdbId > 0 ? existingIds.tmdbId : tmdb.findMovieByImdb(existingIds.imdbId);
            if (trustedId <= 0 || (tmdbId > 0 && tmdbId != trustedId)
                    || hasConflictingIds(sidecars, existingIds, trustedId)) {
                return Result.PRESERVED;
            }
            tmdbId = trustedId;
        }
        if (tmdbId <= 0) {
            tmdbId = confidentMatch(tmdb.search(movie.title, movie.year), 0);
            if (tmdbId <= 0 && !movie.alternateTitle.isEmpty()) {
                tmdbId = confidentMatch(tmdb.search(movie.alternateTitle, movie.alternateYear), movie.year);
            }
            if (tmdbId <= 0) {
                return Result.REVIEW;
            }
        }

        JSONObject details = tmdb.details(tmdbId);
        Map<String, String> artwork = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        for (String type : TYPES) {
            String existing = findArtwork(movie, type);
            if (existing != null) {
                artwork.put(type, existing);
            }
        }
        byte[] updatedNfo = null;
        if (!preserve) {
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
        for (int i = 0; i < TYPES.length; i++) {
            checkCancelled();
            String type = TYPES[i];
            String path = tmdb.artwork(details, type);
            try {
                String target = artwork.get(type);
                if ((target == null || overwrite) && path != null && !path.isEmpty()) {
                    target = target == null ? movie.artworkName(type, "clearlogo".equals(type) ? "png" : "jpg") : target;
                    if (writeImage(storage, movie.directory, target, path, SIZES[i])) {
                        artWritten++;
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
        if (updatedNfo != null) {
            storage.write(movie.directory, movie.nfoName, "application/xml", updatedNfo, overwrite);
            for (NfoSidecar sidecar : sidecars) {
                if (!sidecar.name.equalsIgnoreCase(movie.nfoName)) {
                    storage.write(movie.directory, sidecar.name, "application/xml", updatedNfo, true);
                }
            }
        }
        if (!warnings.isEmpty()) {
            progress.update("以下图片写入失败：" + String.join("、", warnings));
            return Result.INCOMPLETE;
        }
        return !preserve || artWritten > 0 ? Result.WRITTEN : Result.PRESERVED;
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
        List<String> images = tmdb.alternateFanart(details, ALTERNATE_FANART_LIMIT);
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
        String poster = tmdb.collectionArtwork(movie, "poster");
        String fanart = tmdb.collectionArtwork(movie, "fanart");
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
        byte[] data = formattedImage(tmdb.image(path, size), name);
        return collectionStorage.write(directory, name, mime(name), data, false) ? 1 : 0;
    }

    private boolean writeImage(MovieStorage targetStorage, MovieStorage.Directory directory, String name,
                               String path, String size) throws IOException {
        byte[] data = formattedImage(tmdb.image(path, size), name);
        return targetStorage.write(directory, name, mime(name), data, overwrite);
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
            String profile = tmdb.castProfile(person);
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
        if (problem instanceof TmdbClient.HttpException) {
            int code = ((TmdbClient.HttpException) problem).statusCode;
            if (code == 401 || code == 403 || code == 429) {
                throw problem;
            }
        }
    }

    private String findArtwork(MovieStorage.Movie movie, String type) throws IOException {
        String named = movie.artworkName(type, "jpg");
        String image = findImage(storage, movie.directory, named.substring(0, named.length() - 4));
        if (image == null && !overwrite && movie.singleMovieInDirectory) {
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
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("TMDB 返回无法读取的图片");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 3000) {
            options.inSampleSize *= 2;
        }
        Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (image == null) {
            throw new IOException("TMDB 返回无法读取的图片");
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

    private List<NfoSidecar> readSidecars(MovieStorage.Movie movie) throws IOException {
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
            if (sidecar.ids.tmdbId > 0 || sidecar.ids.imdbId != null) {
                return sidecar.ids;
            }
        }
        return new Identifiers();
    }

    private static boolean hasConflictingIds(List<NfoSidecar> sidecars, Identifiers preferred, int tmdbId) {
        for (NfoSidecar sidecar : sidecars) {
            Identifiers ids = sidecar.ids;
            if ((ids.tmdbId > 0 && ids.tmdbId != tmdbId)
                    || (preferred.imdbId != null && ids.imdbId != null && !preferred.imdbId.equals(ids.imdbId))) {
                return true;
            }
        }
        return false;
    }

    private Identifiers readIds(byte[] bytes) throws IOException {
        Identifiers ids = new Identifiers();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new ByteArrayInputStream(bytes), null);
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG && parser.getDepth() == 1
                        && !"movie".equalsIgnoreCase(parser.getName())) {
                    break;
                }
                if (event != XmlPullParser.START_TAG || parser.getDepth() != 2) {
                    continue;
                }
                String tag = parser.getName();
                if (tag.equals("uniqueid")) {
                    String type = parser.getAttributeValue(null, "type");
                    String value = parser.nextText().trim();
                    if ("tmdb".equalsIgnoreCase(type)) {
                        ids.tmdbId = parseId(value);
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
            // Old NFO files may be plain scraper URLs or use malformed XML.
        }
        return ids;
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
        String imdbId;
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
