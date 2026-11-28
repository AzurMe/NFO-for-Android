package org.kodiscraper.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class TrailerPlayer {
    private static final Pattern YOUTUBE_ID = Pattern.compile("[a-zA-Z0-9_-]{11}");
    private static final Pattern DIRECT_VIDEO = Pattern.compile(".+\\.(mp4|m4v|mkv|webm|mov|m3u8)",
            Pattern.CASE_INSENSITIVE);

    record Source(Uri media, String youtubeId) {}

    private TrailerPlayer() {}

    static Source source(Uri local, String link) {
        if (local != null && "content".equals(local.getScheme())) return new Source(local, null);
        if (link.isEmpty()) return null;
        Uri uri = Uri.parse(link);
        if (!uri.isHierarchical() || uri.getUserInfo() != null || uri.getHost() == null) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String scheme = uri.getScheme();
        String id = null;
        if ("plugin".equals(scheme) && "plugin.video.youtube".equals(host)) {
            id = uri.getQueryParameter("video_id");
            if (id == null) id = uri.getQueryParameter("videoid");
        } else if ("https".equalsIgnoreCase(scheme)) {
            List<String> path = uri.getPathSegments();
            switch (host) {
                case "youtu.be":
                    if (path.size() == 1) id = path.get(0);
                    break;
                case "youtube.com":
                case "www.youtube.com":
                case "m.youtube.com":
                case "www.youtube-nocookie.com":
                    if (path.size() == 1 && "watch".equals(path.get(0))) id = uri.getQueryParameter("v");
                    if (path.size() == 2 && ("embed".equals(path.get(0)) || "shorts".equals(path.get(0)))) {
                        id = path.get(1);
                    }
                    break;
                default:
                    String name = uri.getLastPathSegment();
                    if (name != null && DIRECT_VIDEO.matcher(name).matches()) return new Source(uri, null);
            }
        }
        return id != null && YOUTUBE_ID.matcher(id).matches() ? new Source(null, id) : null;
    }

    static AlertDialog show(MainActivity activity, Source source) {
        return source.youtubeId() == null ? showVideo(activity, source.media())
                : showYouTube(activity, source.youtubeId());
    }

    private static AlertDialog showVideo(MainActivity activity, Uri source) {
        FrameLayout panel = panel(activity);
        VideoView video = new VideoView(activity);
        panel.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        TextView status = activity.text("正在加载预告片…", 14, 0xffffffff, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(activity.dp(14), 0, activity.dp(14), 0);
        panel.addView(status, new FrameLayout.LayoutParams(-1, -1));
        MediaController controls = new MediaController(activity);
        video.setMediaController(controls);
        video.setOnPreparedListener(player -> {
            status.setVisibility(View.GONE);
            video.start();
            controls.show(2500);
        });
        video.setOnErrorListener((player, what, extra) -> {
            DebugLog.event("trailer", "playback_error=" + what + " detail=" + extra);
            status.setText("预告片无法播放，请检查文件格式或网络连接。");
            status.setVisibility(View.VISIBLE);
            return true;
        });
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("预告片")
                .setView(panel).setNegativeButton("关闭", null).create();
        dialog.setOnDismissListener(ignored -> {
            controls.hide();
            video.stopPlayback();
        });
        dialog.show();
        video.setVideoURI(source);
        return dialog;
    }

    private static AlertDialog showYouTube(MainActivity activity, String videoId) {
        FrameLayout panel = panel(activity);
        WebView video = new WebView(activity);
        video.setBackgroundColor(0xff000000);
        video.setContentDescription("YouTube 预告片");
        WebSettings settings = video.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        video.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() || !"https".equals(request.getUrl().getScheme());
            }
        });
        panel.addView(video, new FrameLayout.LayoutParams(-1, -1));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("预告片")
                .setView(panel).setNegativeButton("关闭", null)
                .setNeutralButton("在 YouTube 打开", (box, button) -> openYouTube(activity, videoId)).create();
        dialog.setOnDismissListener(ignored -> {
            video.stopLoading();
            panel.removeView(video);
            video.destroy();
        });
        dialog.show();
        // The YouTube WebView integration expects the app identity as the HTTP referrer.
        String origin = "https://" + activity.getPackageName();
        String html = """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="referrer" content="strict-origin-when-cross-origin">
                <style>html,body,#player,iframe {width:100%%;height:100%%;margin:0;background:#000}
                body{color:white;font:14px sans-serif}#message{padding:14px;line-height:1.5}
                [hidden]{display:none!important}</style></head>
                <body><div id="player"></div><p id="message" hidden>当前无法嵌入播放，可选择在 YouTube 打开。</p>
                <script>
                function showError() {
                    document.getElementById('player').hidden = true;
                    document.getElementById('message').hidden = false;
                }
                function onYouTubeIframeAPIReady() {
                    new YT.Player('player', {videoId:'%s', playerVars:{origin:'%s',playsinline:1,fs:0},
                        events:{onError:showError}});
                }
                </script><script src="https://www.youtube.com/iframe_api" onerror="showError()"></script>
                </body></html>
                """;
        video.loadDataWithBaseURL(origin + "/", String.format(Locale.ROOT, html, videoId, origin),
                "text/html", "UTF-8", null);
        return dialog;
    }

    private static FrameLayout panel(MainActivity activity) {
        FrameLayout panel = new FrameLayout(activity);
        panel.setBackgroundColor(0xff000000);
        panel.setMinimumHeight(activity.dp(240));
        return panel;
    }

    private static void openYouTube(MainActivity activity, String id) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(activity, "未找到可以打开 YouTube 的应用", Toast.LENGTH_SHORT).show();
        }
    }
}
