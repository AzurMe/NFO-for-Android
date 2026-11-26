package org.kodiscraper.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.MissingResourceException;

public final class VideoProbe {
    private VideoProbe() {
    }

    public static JSONObject probe(Context context, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || Thread.currentThread().isInterrupted()) {
            return new JSONObject();
        }

        try (AssetFileDescriptor source = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (source == null) {
                return new JSONObject();
            }

            MediaExtractor extractor = new MediaExtractor();
            try {
                if (source.getDeclaredLength() == AssetFileDescriptor.UNKNOWN_LENGTH) {
                    extractor.setDataSource(source.getFileDescriptor());
                } else {
                    extractor.setDataSource(source.getFileDescriptor(), source.getStartOffset(), source.getDeclaredLength());
                }
                return readTracks(extractor);
            } finally {
                extractor.release();
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
            return new JSONObject();
        }
    }

    private static JSONObject readTracks(MediaExtractor extractor) throws JSONException {
        JSONObject tracks = new JSONObject();
        int count = extractor.getTrackCount();
        for (int index = 0; index < count && !Thread.currentThread().isInterrupted(); index++) {
            MediaFormat format = extractor.getTrackFormat(index);
            String mime = string(format, MediaFormat.KEY_MIME).toLowerCase(Locale.ROOT);
            if (mime.startsWith("video/")) {
                addTrack(tracks, "video", video(format, mime));
            } else if (mime.startsWith("audio/")) {
                addTrack(tracks, "audio", audio(format, mime));
            } else if (isSubtitle(mime)) {
                String language = language(format);
                addTrack(tracks, "subtitle", new JSONObject().put("language", language.isEmpty() ? "und" : language));
            }
        }
        return tracks;
    }

    private static void addTrack(JSONObject tracks, String type, JSONObject info) throws JSONException {
        if (info.length() == 0) {
            return;
        }
        JSONArray ofType = tracks.optJSONArray(type);
        if (ofType == null) {
            ofType = new JSONArray();
            tracks.put(type, ofType);
        }
        ofType.put(info);
    }

    private static JSONObject video(MediaFormat format, String mime) throws JSONException {
        JSONObject track = new JSONObject();
        int profile = integer(format, MediaFormat.KEY_PROFILE);
        putText(track, "codec", videoCodec(mime, profile));
        int width = integer(format, MediaFormat.KEY_WIDTH);
        int height = integer(format, MediaFormat.KEY_HEIGHT);
        putPositive(track, "width", width);
        putPositive(track, "height", height);

        if (width > 0 && height > 0) {
            int sarWidth = integer(format, MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH);
            int sarHeight = integer(format, MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT);
            double aspect = (double) width / height;
            if (sarWidth > 0 && sarHeight > 0) {
                aspect *= (double) sarWidth / sarHeight;
            }
            track.put("aspect", aspect);
        }

        putPositive(track, "durationinseconds", longValue(format, MediaFormat.KEY_DURATION) / 1_000_000L);
        putText(track, "hdrtype", hdrType(format, mime));
        return track;
    }

    private static JSONObject audio(MediaFormat format, String mime) throws JSONException {
        JSONObject track = new JSONObject();
        putText(track, "codec", audioCodec(format, mime));
        putText(track, "language", language(format));
        putPositive(track, "channels", integer(format, MediaFormat.KEY_CHANNEL_COUNT));
        return track;
    }

    private static String videoCodec(String mime, int profile) {
        return switch (mime) {
            case "video/avc", "video/h264" -> "h264";
            case "video/hevc", "video/h265" -> "hevc";
            case "video/av01", "video/av1" -> "av1";
            case "video/x-vnd.on2.vp8" -> "vp8";
            case "video/x-vnd.on2.vp9" -> "vp9";
            case "video/mpeg", "video/mpeg1" -> "mpeg1video";
            case "video/mpeg2" -> "mpeg2video";
            case "video/mp4v-es" -> "mpeg4";
            case "video/3gpp" -> "h263";
            case "video/x-vc1" -> "vc1";
            case "video/theora", "video/x-theora" -> "theora";
            case "video/dolby-vision" -> switch (profile) {
                case CodecProfileLevel.DolbyVisionProfileDvavPer, CodecProfileLevel.DolbyVisionProfileDvavPen,
                        CodecProfileLevel.DolbyVisionProfileDvavSe -> "h264";
                case CodecProfileLevel.DolbyVisionProfileDvheDer, CodecProfileLevel.DolbyVisionProfileDvheDen,
                        CodecProfileLevel.DolbyVisionProfileDvheDtr, CodecProfileLevel.DolbyVisionProfileDvheStn,
                        CodecProfileLevel.DolbyVisionProfileDvheDth, CodecProfileLevel.DolbyVisionProfileDvheDtb,
                        CodecProfileLevel.DolbyVisionProfileDvheSt -> "hevc";
                default -> "";
            };
            default -> "";
        };
    }

    private static String hdrType(MediaFormat format, String mime) {
        if ("video/dolby-vision".equals(mime)) {
            return "dolbyvision";
        }
        return switch (integer(format, MediaFormat.KEY_COLOR_TRANSFER)) {
            case MediaFormat.COLOR_TRANSFER_HLG -> "hlg";
            case MediaFormat.COLOR_TRANSFER_ST2084 -> format.containsKey(MediaFormat.KEY_HDR10_PLUS_INFO)
                    ? "hdr10plus" : "hdr10";
            default -> "";
        };
    }

    private static String audioCodec(MediaFormat format, String mime) {
        return switch (mime) {
            case "audio/mp4a-latm", "audio/aac" -> aacCodec(format);
            case "audio/mp4a.40.02" -> "aac_lc";
            case "audio/mp4a.40.05" -> "he_aac";
            case "audio/mp4a.40.29" -> "he_aac_v2";
            case "audio/ac3" -> "ac3";
            case "audio/eac3" -> "eac3";
            case "audio/eac3-joc" -> "eac3_ddp_atmos";
            case "audio/ac4" -> "ac4";
            case "audio/true-hd", "audio/truehd", "audio/vnd.dolby.mlp" -> "truehd";
            case "audio/vnd.dts" -> "dca";
            case "audio/vnd.dts.hd" -> switch (integer(format, MediaFormat.KEY_PROFILE)) {
                case CodecProfileLevel.DTS_HDProfileHRA -> "dtshd_hra";
                case CodecProfileLevel.DTS_HDProfileMA -> "dtshd_ma";
                default -> "dtshd";
            };
            case "audio/mpeg-l1" -> "mp1";
            case "audio/mpeg-l2" -> "mp2";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/flac" -> "flac";
            case "audio/alac" -> "alac";
            case "audio/opus" -> "opus";
            case "audio/vorbis" -> "vorbis";
            case "audio/raw" -> integer(format, MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_8BIT
                    ? "pcm_u8" : "";
            default -> "";
        };
    }

    private static String aacCodec(MediaFormat format) {
        int aacProfile = integer(format, MediaFormat.KEY_AAC_PROFILE);
        int profile = aacProfile > 0 ? aacProfile : integer(format, MediaFormat.KEY_PROFILE);
        return switch (profile) {
            case CodecProfileLevel.AACObjectLC -> "aac_lc";
            case CodecProfileLevel.AACObjectSSR -> "aac_ssr";
            case CodecProfileLevel.AACObjectLTP -> "aac_ltp";
            case CodecProfileLevel.AACObjectHE -> "he_aac";
            case CodecProfileLevel.AACObjectHE_PS -> "he_aac_v2";
            default -> "aac";
        };
    }

    private static boolean isSubtitle(String mime) {
        return mime.startsWith("text/") || mime.startsWith("subtitle/") || mime.contains("subtitle")
                || mime.contains("subrip") || mime.contains("vobsub") || mime.contains("ttml")
                || mime.equals("application/pgs") || mime.equals("application/dvbsubs")
                || mime.equals("application/x-quicktime-tx3g") || mime.equals("application/x-mp4-vtt");
    }

    private static String language(MediaFormat format) {
        String value = string(format, MediaFormat.KEY_LANGUAGE).trim().replace('_', '-');
        String language = Locale.forLanguageTag(value).getLanguage();
        if (language.length() == 3) {
            return language;
        }
        try {
            return Locale.forLanguageTag(language).getISO3Language();
        } catch (MissingResourceException ignored) {
            return "";
        }
    }

    private static String string(MediaFormat format, String key) {
        String value = format.getString(key);
        return value == null ? "" : value;
    }

    private static int integer(MediaFormat format, String key) {
        return format.containsKey(key) ? format.getInteger(key) : 0;
    }

    private static long longValue(MediaFormat format, String key) {
        return format.containsKey(key) ? format.getLong(key) : 0;
    }

    private static void putText(JSONObject track, String key, String value) throws JSONException {
        if (!value.isEmpty()) {
            track.put(key, value);
        }
    }

    private static void putPositive(JSONObject track, String key, long value) throws JSONException {
        if (value > 0) {
            track.put(key, value);
        }
    }
}
