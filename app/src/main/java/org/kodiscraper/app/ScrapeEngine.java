package org.kodiscraper.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
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
    private static final String[] TYPES = {"poster", "fanart", "clearlogo", "landscape", "keyart"};
    private static final String[] SIZES = {"w780", "w1280", "original", "w1280", "w780"};
    private static final String[] EXTENSIONS = {"jpg", "png", "jpeg", "webp"};

    enum Result { WRITTEN, INCOMPLETE, PRESERVED, REVIEW }

    interface Progress {
        void update(String text);
    }

    private final Context context;
    private final MovieStorage storage;
    private final TmdbClient tmdb;
    private final String language;
    private final boolean overwrite;
    private final boolean actorImages;

    ScrapeEngine(Context context, MovieStorage storage, TmdbClient tmdb,
                 String language, boolean overwrite, boolean actorImages) {
        this.context = context;
        this.storage = storage;
        this.tmdb = tmdb;
        this.language = language;
        this.overwrite = overwrite;
        this.actorImages = actorImages;
    }

    Result scrape(MovieStorage.Movie movie, int selectedId, Progress progress) throws IOException {
        checkCancelled();
        String nfoName = movie.legacyNfoName == null ? movie.nfoName : movie.legacyNfoName;
        MovieStorage.Entry existingNfo = storage.find(movie.directory, nfoName);
        if (existingNfo == null && !nfoName.equals(movie.nfoName)) {
            nfoName = movie.nfoName;
            existingNfo = storage.find(movie.directory, nfoName);
        }
        boolean preserve = existingNfo != null && !overwrite;
        Identifiers existingIds = readIds(existingNfo);
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
            if (trustedId <= 0 || (tmdbId > 0 && tmdbId != trustedId)) {
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
        int artWritten = 0;
        for (int i = 0; i < TYPES.length; i++) {
            checkCancelled();
            String type = TYPES[i];
            String path = tmdb.artwork(details, type);
            try {
                String target = findArtwork(movie, type);
                if (target != null) {
                    artwork.put(type, target);
                }
                if ((target == null || overwrite) && path != null && !path.isEmpty()) {
                    target = target == null ? movie.artworkName(type, "clearlogo".equals(type) ? "png" : "jpg") : target;
                    byte[] data = formattedImage(tmdb.image(path, SIZES[i]), target);
                    if (storage.write(movie.directory, target, mime(target), data, overwrite)) {
                        artWritten++;
                    }
                    artwork.put(type, target);
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
        checkCancelled();
        if (!preserve) {
            progress.update("正在读取本地视频轨道");
            JSONObject streams = VideoProbe.probe(context, movie.uri);
            if (streams.length() > 0) {
                try {
                    details.put("streamdetails", streams);
                } catch (JSONException cause) {
                    throw new IOException("无法整理视频轨道", cause);
                }
            }
            checkCancelled();
            ByteArrayOutputStream xml = new ByteArrayOutputStream();
            KodiNfo.write(xml, details, artwork, new HashMap<>(), language, region(language),
                    movie.localTrailerPath != null);
            storage.write(movie.directory, nfoName, "application/xml", xml.toByteArray(), overwrite);
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
            if (!overwrite && storage.find(actors, target) != null) {
                continue;
            }
            try {
                byte[] data = formattedImage(tmdb.image(profile, "w185"), target);
                if (storage.write(actors, target, mime(target), data, overwrite)) {
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
        for (String ext : EXTENSIONS) {
            MovieStorage.Entry entry = storage.find(movie.directory, movie.artworkName(type, ext));
            if (entry != null && !(entry instanceof MovieStorage.Directory)) {
                return entry.displayName;
            }
        }
        if (!overwrite && movie.singleMovieInDirectory) {
            for (String ext : EXTENSIONS) {
                MovieStorage.Entry entry = storage.find(movie.directory, type + "." + ext);
                if (entry != null && !(entry instanceof MovieStorage.Directory)) {
                    return entry.displayName;
                }
            }
        }
        return null;
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

    private Identifiers readIds(MovieStorage.Entry entry) throws IOException {
        Identifiers ids = new Identifiers();
        if (entry == null) {
            return ids;
        }
        try {
            byte[] bytes = storage.read(entry, 1024 * 1024);
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new ByteArrayInputStream(bytes), null);
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
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

}
