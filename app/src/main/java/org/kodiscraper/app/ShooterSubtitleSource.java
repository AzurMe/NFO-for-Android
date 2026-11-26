package org.kodiscraper.app;

import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ShooterSubtitleSource implements SubtitleSource {
    private static final String API = "https://www.shooter.cn/api/subapi.php";
    private static final String DOMAIN = "shooter.cn";
    private static final int HASH_BLOCK_SIZE = 4096;
    private static final int RESPONSE_LIMIT = 192 * 1024;
    private static final int DOWNLOAD_LIMIT = 9;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Override
    public List<SubtitleFiles.Track> find(SubtitleQuery query) throws IOException {
        String hash = videoHash(query);
        if (hash.isEmpty()) {
            return Collections.emptyList();
        }

        String filename = query.movie.displayName;
        int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        filename = filename.substring(separator + 1);
        String form = "filehash=" + SubtitleHttp.encode(hash)
                + "&pathinfo=" + SubtitleHttp.encode(filename) + "&format=json&lang=chn";
        byte[] response = SubtitleHttp.postForm(API, form, RESPONSE_LIMIT, DOMAIN);
        if (response.length == 1 && (response[0] & 0xff) == 0xff) {
            return Collections.emptyList();
        }

        JSONArray matches;
        try {
            matches = new JSONArray(new String(response, StandardCharsets.UTF_8));
        } catch (JSONException cause) {
            throw new IOException("射手影音返回的字幕结果无法读取", cause);
        }

        List<SubtitleFiles.Track> tracks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        IOException firstFailure = null;
        int attempted = 0;
        for (int i = 0; i < matches.length() && tracks.size() < MAX_TRACKS
                && attempted < DOWNLOAD_LIMIT; i++) {
            ScrapeEngine.checkCancelled();
            JSONObject match = matches.optJSONObject(i);
            // Kodi does not apply SPlayer's separate subtitle delay to a sidecar.
            if (match == null || match.optInt("Delay", 0) != 0) {
                continue;
            }
            JSONArray files = match.optJSONArray("Files");
            if (files == null) {
                continue;
            }
            for (int j = 0; j < files.length() && tracks.size() < MAX_TRACKS
                    && attempted < DOWNLOAD_LIMIT; j++) {
                ScrapeEngine.checkCancelled();
                JSONObject file = files.optJSONObject(j);
                if (file == null) {
                    continue;
                }
                String name = "subtitle." + file.optString("Ext");
                String url = file.optString("Link");
                if (!SubtitleFiles.supportsFilename(name) || !url.startsWith("https://") || !seen.add(url)) {
                    continue;
                }
                attempted++;
                try {
                    byte[] bytes = SubtitleHttp.get(url, null, null, SubtitleFiles.MAX_BYTES, DOMAIN);
                    tracks.add(SubtitleFiles.validate(name, bytes));
                } catch (IOException failure) {
                    ScrapeEngine.checkCancelled();
                    if (failure instanceof SubtitleHttp.HttpException
                            && ((SubtitleHttp.HttpException) failure).statusCode == 429) {
                        if (tracks.isEmpty()) {
                            throw failure;
                        }
                        return tracks;
                    }
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                }
            }
        }
        if (tracks.isEmpty() && firstFailure != null) {
            throw firstFailure;
        }
        return tracks;
    }

    private static String videoHash(SubtitleQuery query) throws IOException {
        ScrapeEngine.checkCancelled();
        try {
            ParcelFileDescriptor descriptor = query.storage.openVideo(query.movie);
            if (descriptor == null) {
                return "";
            }
            try (ParcelFileDescriptor.AutoCloseInputStream input =
                         new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                return hash(input.getChannel());
            }
        } catch (IOException unavailable) {
            ScrapeEngine.checkCancelled();
            // Some Storage Access Framework providers expose pipes without seek or length.
            return "";
        }
    }

    static String hash(FileChannel video) throws IOException {
        long size = video.size();
        if (size < 2 * HASH_BLOCK_SIZE) {
            return "";
        }
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("此设备无法计算射手影音的影片指纹", unavailable);
        }

        // SPlayer hashes four small blocks, with integer division performed before doubling.
        long third = size / 3;
        long[] offsets = {HASH_BLOCK_SIZE, third * 2, third, size - 2 * HASH_BLOCK_SIZE};
        ByteBuffer block = ByteBuffer.allocate(HASH_BLOCK_SIZE);
        StringBuilder fingerprint = new StringBuilder(131);
        for (long offset : offsets) {
            ScrapeEngine.checkCancelled();
            if (fingerprint.length() > 0) {
                fingerprint.append(';');
            }
            block.clear();
            block.limit((int) Math.min(HASH_BLOCK_SIZE, size - offset));
            video.position(offset);
            while (block.hasRemaining()) {
                ScrapeEngine.checkCancelled();
                if (video.read(block) <= 0) {
                    return "";
                }
            }
            block.flip();
            md5.update(block);
            for (byte value : md5.digest()) {
                fingerprint.append(HEX[(value & 0xff) >>> 4]).append(HEX[value & 0xf]);
            }
        }
        return fingerprint.toString();
    }
}
