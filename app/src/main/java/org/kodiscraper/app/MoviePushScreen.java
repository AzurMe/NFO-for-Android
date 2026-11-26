package org.kodiscraper.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.kodiscraper.app.MainActivity.CARD;
import static org.kodiscraper.app.MainActivity.DARK;
import static org.kodiscraper.app.MainActivity.EDGE;
import static org.kodiscraper.app.MainActivity.GREEN;
import static org.kodiscraper.app.MainActivity.INK;
import static org.kodiscraper.app.MainActivity.LIME;
import static org.kodiscraper.app.MainActivity.MUTED;
import static org.kodiscraper.app.MainActivity.PALE;
import static org.kodiscraper.app.MainActivity.PAPER;
import static org.kodiscraper.app.MainActivity.WHITE;

final class MoviePushScreen {
    private static final double HIGH_RATING = 7.0;

    private final MainActivity activity;
    private final Supplier<TmdbClient> tmdbClient;
    private final Runnable configureTmdb;
    private final LinearLayout root;
    private final LinearLayout entries;
    private final TextView range;
    private final TextView status;
    private final Button popular;
    private final Button rated;
    private final Button refresh;
    private final ProgressBar progress;

    private List<ImdbMovieFeed.Movie> movies = Collections.emptyList();
    private LocalDate loadedThrough;
    private boolean highRated;
    private boolean requested;
    private boolean needsTmdb;
    private boolean loadingFeed;
    private Thread worker;
    private TmdbClient activeTmdb;
    private ImdbMovieFeed activeFeed;
    private AlertDialog releasesDialog;

    MoviePushScreen(MainActivity activity, Runnable openNavigation, Supplier<TmdbClient> tmdbClient,
                    Runnable configureTmdb) {
        this.activity = activity;
        this.tmdbClient = tmdbClient;
        this.configureTmdb = configureTmdb;
        root = activity.vertical();
        root.setBackgroundColor(PAPER);
        root.setPadding(dp(18), dp(14), dp(18), dp(10));

        LinearLayout hero = activity.vertical();
        hero.setPadding(dp(14), dp(12), dp(14), dp(16));
        hero.setBackground(activity.shape(DARK, 22, 0));
        LinearLayout heading = activity.horizontal();
        Button menu = activity.button("☰", 0xff24533f, WHITE);
        menu.setTextSize(20);
        menu.setContentDescription("打开导航菜单");
        menu.setOnClickListener(view -> openNavigation.run());
        heading.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = activity.text("电影推送", 26, WHITE, true);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1f);
        titleSpace.leftMargin = dp(12);
        heading.addView(title, titleSpace);
        refresh = activity.button("刷新", 0xff24533f, WHITE);
        refresh.setOnClickListener(view -> refreshClicked());
        heading.addView(refresh, new LinearLayout.LayoutParams(-2, dp(44)));
        activity.add(hero, heading, 0);
        range = activity.text("最近 30 天上映", 13, LIME, false);
        activity.add(hero, range, 8);
        activity.add(root, hero, 0);

        LinearLayout filters = activity.horizontal();
        popular = activity.button("热门 · 投票数", GREEN, WHITE);
        popular.setOnClickListener(view -> sort(false));
        filters.addView(popular, new LinearLayout.LayoutParams(0, dp(44), 1f));
        rated = activity.button("高分 · 7.0 以上", PALE, GREEN);
        rated.setOnClickListener(view -> sort(true));
        LinearLayout.LayoutParams ratedSpace = new LinearLayout.LayoutParams(0, dp(44), 1f);
        ratedSpace.leftMargin = dp(8);
        filters.addView(rated, ratedSpace);
        activity.add(root, filters, 12);
        markFilter(popular, true);

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams barSpace = new LinearLayout.LayoutParams(-1, dp(5));
        barSpace.topMargin = dp(9);
        root.addView(progress, barSpace);
        status = activity.text("", 13, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        activity.add(root, status, 10);

        ScrollView list = new ScrollView(activity);
        list.setClipToPadding(false);
        list.setFillViewport(true);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        entries = activity.vertical();
        entries.setPadding(0, dp(2), 0, dp(4));
        list.addView(entries, new ScrollView.LayoutParams(-1, -2));
        TextView attribution = activity.text("上映日期由 TMDB 提供。\nInformation courtesy of IMDb "
                + "(https://www.imdb.com). Used with permission.", 11, MUTED, false);
        activity.add(root, attribution, 6);
    }

    View view() {
        return root;
    }

    void onShown() {
        if ((!requested || needsTmdb) && worker == null) {
            refreshMovies();
        }
    }

    void close() {
        stopWork();
        if (releasesDialog != null) {
            releasesDialog.dismiss();
        }
    }

    private void refreshClicked() {
        if (loadingFeed) {
            stopWork();
            setFeedLoading(false);
            renderMovies();
            status.setText("已取消刷新");
        } else if (worker == null) {
            if (needsTmdb) {
                requested = false;
                configureTmdb.run();
            } else {
                refreshMovies();
            }
        }
    }

    private void refreshMovies() {
        TmdbClient tmdb = tmdbClient.get();
        requested = true;
        needsTmdb = tmdb == null;
        if (needsTmdb) {
            refresh.setText("设置 TMDB");
            status.setText("填写 TMDB 凭据后即可获取最近上映的影片。");
            return;
        }
        LocalDate today = LocalDate.now();
        ImdbMovieFeed feed = new ImdbMovieFeed(activity.getApplicationContext());
        activeTmdb = tmdb;
        activeFeed = feed;
        setFeedLoading(true);
        status.setText("正在查找近 30 天上映的影片和 IMDb 评分…");
        launch(() -> feed.load(tmdb, today), result -> {
            movies = result;
            loadedThrough = today;
            range.setText(today.minusDays(29) + " 至 " + today + " 上映");
            setFeedLoading(false);
            renderMovies();
        }, error -> {
            setFeedLoading(false);
            renderMovies();
            status.setText("刷新失败 · " + error);
        });
    }

    private void setFeedLoading(boolean loading) {
        loadingFeed = loading;
        refresh.setText(loading ? "取消" : "刷新");
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        popular.setEnabled(!loading);
        rated.setEnabled(!loading);
        if (loading) {
            entries.removeAllViews();
        }
    }

    private void sort(boolean byRating) {
        highRated = byRating;
        markFilter(popular, !byRating);
        markFilter(rated, byRating);
        renderMovies();
    }

    private void markFilter(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? WHITE : GREEN);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x290e291b),
                activity.shape(selected ? GREEN : PALE, 12, 0), null));
    }

    private void renderMovies() {
        List<ImdbMovieFeed.Movie> visible = new ArrayList<>(movies);
        Comparator<ImdbMovieFeed.Movie> rating = Comparator.comparingDouble(ImdbMovieFeed.Movie::rating).reversed();
        Comparator<ImdbMovieFeed.Movie> votes = Comparator.comparingInt(ImdbMovieFeed.Movie::votes).reversed();
        if (highRated) {
            visible.removeIf(movie -> movie.rating() < HIGH_RATING);
            visible.sort(rating.thenComparing(votes));
        } else {
            visible.sort(votes.thenComparing(rating));
        }
        entries.removeAllViews();
        if (loadedThrough != null) {
            status.setText(visible.isEmpty() ? "这段时间暂无符合条件的已评分影片。" : "共 " + visible.size() + " 部");
        }
        NumberFormat number = NumberFormat.getIntegerInstance(Locale.SIMPLIFIED_CHINESE);
        for (ImdbMovieFeed.Movie movie : visible) {
            LinearLayout card = activity.vertical();
            card.setPadding(dp(14), dp(12), dp(14), dp(13));
            card.setBackground(activity.shape(CARD, 16, EDGE));
            LinearLayout titleRow = activity.horizontal();
            titleRow.addView(activity.text(movie.title(), 18, INK, true), new LinearLayout.LayoutParams(0, -2, 1f));
            String score = String.format(Locale.ROOT, "IMDb %.1f", movie.rating());
            TextView badge = activity.text(score, 13, INK, true);
            badge.setPadding(dp(9), dp(5), dp(9), dp(5));
            badge.setBackground(activity.shape(LIME, 9, 0));
            titleRow.addView(badge);
            activity.add(card, titleRow, 0);
            if (!movie.originalTitle().isEmpty() && !movie.originalTitle().equalsIgnoreCase(movie.title())) {
                activity.add(card, activity.text(movie.originalTitle(), 13, MUTED, false), 3);
            }
            activity.add(card, activity.text(movie.releaseDate() + " · " + number.format(movie.votes())
                    + " 票", 13, MUTED, false), 6);
            Button select = activity.button("查找下载链接", PALE, GREEN);
            select.setContentDescription("查找下载链接 · " + movie.title());
            select.setOnClickListener(view -> showReleases(movie));
            LinearLayout.LayoutParams selectSpace = new LinearLayout.LayoutParams(-2, dp(44));
            selectSpace.topMargin = dp(8);
            card.addView(select, selectSpace);
            activity.add(entries, card, 10);
        }
    }

    private void showReleases(ImdbMovieFeed.Movie movie) {
        if (worker != null) return;
        LinearLayout content = dialogContent();
        TextView description = activity.text("2160p · HDR10 · 小于 40 GB", 13, MUTED, true);
        activity.add(content, description, 0);
        TextView result = activity.text("正在搜索海盗湾…", 14, INK, false);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        activity.add(content, result, 9);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout rows = activity.vertical();
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams rowsSpace = new LinearLayout.LayoutParams(-1, dp(310));
        rowsSpace.topMargin = dp(6);
        scroll.setVisibility(View.GONE);
        content.addView(scroll, rowsSpace);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(movie.title()).setView(content)
                .setNegativeButton("关闭", null).create();
        releasesDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (releasesDialog == dialog) {
                releasesDialog = null;
                stopWork();
            }
        });
        dialog.show();

        launch(() -> findReleases(movie), releases -> {
            if (releases.isEmpty()) {
                result.setText("没有符合筛选要求的候选链接。");
                return;
            }
            result.setText(releases.size() + " 个候选 · 规格来自发布标题");
            scroll.setVisibility(View.VISIBLE);
            for (PirateBayReleaseSource.Candidate release : releases) {
                LinearLayout row = activity.vertical();
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x290e291b),
                        activity.shape(PALE, 10, 0), null));
                activity.add(row, activity.text(release.name, 14, INK, true), 0);
                activity.add(row, activity.text(releaseSize(release) + " · " + release.seeders
                        + " 个做种", 12, MUTED, false), 4);
                row.setFocusable(true);
                row.setOnClickListener(view -> {
                    dialog.dismiss();
                    chooseLink(release);
                });
                activity.add(rows, row, 7);
            }
        }, error -> result.setText("链接搜索失败 · " + error));
    }

    private List<PirateBayReleaseSource.Candidate> findReleases(ImdbMovieFeed.Movie movie) throws IOException {
        PirateBayReleaseSource source = new PirateBayReleaseSource();
        String title = movie.originalTitle().isEmpty() ? movie.title() : movie.originalTitle();
        int year = movie.releaseDate().getYear();
        List<PirateBayReleaseSource.Candidate> releases = source.search(title, year, movie.imdbId());
        if (releases.isEmpty() && !title.equalsIgnoreCase(movie.title())) {
            releases = source.search(movie.title(), year, movie.imdbId());
        }
        return releases;
    }

    private void chooseLink(PirateBayReleaseSource.Candidate release) {
        new AlertDialog.Builder(activity).setTitle("选择磁力链接")
                .setMessage(release.name + "\n\n" + releaseSize(release))
                .setNeutralButton("取消", null)
                .setNegativeButton("复制链接", (dialog, which) -> copyLink(release))
                .setPositiveButton("打开链接", (dialog, which) -> openLink(release)).show();
    }

    private void copyLink(PirateBayReleaseSource.Candidate release) {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(release.name, release.magnet()));
            Toast.makeText(activity, "已复制磁力链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void openLink(PirateBayReleaseSource.Candidate release) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(release.magnet())));
        } catch (ActivityNotFoundException unavailable) {
            copyLink(release);
        }
    }

    private static String releaseSize(PirateBayReleaseSource.Candidate release) {
        long hundredths = release.bytes / 10_000_000L;
        return String.format(Locale.ROOT, "%d.%02d GB", hundredths / 100, hundredths % 100);
    }

    private LinearLayout dialogContent() {
        LinearLayout content = activity.vertical();
        content.setPadding(dp(22), dp(12), dp(22), dp(5));
        return content;
    }

    private interface Work<T> {
        T run() throws IOException;
    }

    private <T> void launch(Work<T> task, Consumer<T> success, Consumer<String> failure) {
        worker = new Thread(() -> {
            Thread running = Thread.currentThread();
            try {
                T value = task.run();
                finish(running, () -> success.accept(value));
            } catch (IOException error) {
                finish(running, () -> failure.accept(MainActivity.errorText(error)));
            } catch (RuntimeException error) {
                finish(running, () -> failure.accept("无法读取来源数据，请稍后再试。"));
            }
        }, "MoviePush");
        worker.start();
    }

    private void finish(Thread running, Runnable action) {
        activity.runOnUiThread(() -> {
            if (worker == running && !activity.isDestroyed() && !activity.isFinishing()) {
                worker = null;
                activeFeed = null;
                activeTmdb = null;
                action.run();
            }
        });
    }

    private void stopWork() {
        Thread running = worker;
        worker = null;
        if (running != null) {
            running.interrupt();
            SubtitleHttp.cancel(running);
        }
        if (activeTmdb != null) activeTmdb.cancel();
        if (activeFeed != null) activeFeed.cancel();
        activeTmdb = null;
        activeFeed = null;
    }

    private int dp(float value) {
        return activity.dp(value);
    }
}
