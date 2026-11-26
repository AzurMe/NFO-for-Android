package org.kodiscraper.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.math.BigDecimal;
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
    private static final int[] RELEASE_HEIGHTS = {-1, 4320, 2160, 1440, 1080, 720, 576, 480, 0};

    private final MainActivity activity;
    private final Supplier<TmdbClient> tmdbClient;
    private final Runnable configureTmdb;
    private final LinearLayout root;
    private final LinearLayout entries;
    private final ScrollView movieList;
    private final TextView range;
    private final TextView status;
    private final Button popular;
    private final Button rated;
    private final Button refresh;
    private final ProgressBar progress;
    private final MoviePosterLoader posterLoader = new MoviePosterLoader();
    private final List<PosterBinding> posters = new ArrayList<>();

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

        movieList = new ScrollView(activity);
        movieList.setClipToPadding(false);
        movieList.setFillViewport(true);
        movieList.setOnScrollChangeListener((view, x, y, oldX, oldY) -> loadVisiblePosters());
        root.addView(movieList, new LinearLayout.LayoutParams(-1, 0, 1));
        entries = activity.vertical();
        entries.setPadding(0, dp(2), 0, dp(4));
        movieList.addView(entries, new ScrollView.LayoutParams(-1, -2));
        movieList.getViewTreeObserver().addOnGlobalLayoutListener(this::loadVisiblePosters);
        TextView attribution = activity.text("影片资料与海报由 TMDB 提供。\nInformation courtesy of IMDb "
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
        movieList.post(this::loadVisiblePosters);
    }

    void close() {
        stopWork();
        posterLoader.close();
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
        posterLoader.resetFailures();
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
            posters.clear();
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
        posters.clear();
        if (loadedThrough != null) {
            status.setText(visible.isEmpty() ? "这段时间暂无符合条件的已评分影片。" : "共 " + visible.size() + " 部");
        }
        NumberFormat number = NumberFormat.getIntegerInstance(Locale.SIMPLIFIED_CHINESE);
        for (ImdbMovieFeed.Movie movie : visible) {
            LinearLayout card = activity.vertical();
            card.setPadding(dp(14), dp(12), dp(14), dp(13));
            card.setBackground(activity.shape(CARD, 16, EDGE));
            LinearLayout summary = activity.horizontal();
            summary.setGravity(Gravity.TOP);
            ImageView poster = moviePoster(movie);
            summary.addView(poster, new LinearLayout.LayoutParams(dp(78), dp(117)));
            if (!movie.posterPath().isEmpty()) {
                posters.add(new PosterBinding(poster, movie.posterPath()));
            }
            LinearLayout facts = activity.vertical();
            LinearLayout.LayoutParams factsSpace = new LinearLayout.LayoutParams(0, -2, 1f);
            factsSpace.leftMargin = dp(12);
            summary.addView(facts, factsSpace);
            LinearLayout titleRow = activity.horizontal();
            TextView title = activity.text(movie.title(), 18, INK, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            String score = String.format(Locale.ROOT, "IMDb %.1f", movie.rating());
            TextView badge = activity.text(score, 13, INK, true);
            badge.setPadding(dp(9), dp(5), dp(9), dp(5));
            badge.setBackground(activity.shape(LIME, 9, 0));
            LinearLayout.LayoutParams badgeSpace = new LinearLayout.LayoutParams(-2, -2);
            badgeSpace.leftMargin = dp(5);
            titleRow.addView(badge, badgeSpace);
            activity.add(facts, titleRow, 0);
            if (!movie.originalTitle().isEmpty() && !movie.originalTitle().equalsIgnoreCase(movie.title())) {
                TextView original = activity.text(movie.originalTitle(), 13, MUTED, false);
                original.setMaxLines(2);
                original.setEllipsize(TextUtils.TruncateAt.END);
                activity.add(facts, original, 3);
            }
            activity.add(facts, activity.text(movie.releaseDate() + " · " + number.format(movie.votes())
                    + " 票", 13, MUTED, false), 6);
            if (!movie.genres().isEmpty()) {
                TextView genres = activity.text(String.join(" · ", movie.genres()), 12, GREEN, true);
                genres.setMaxLines(2);
                genres.setEllipsize(TextUtils.TruncateAt.END);
                activity.add(facts, genres, 7);
            }
            activity.add(card, summary, 0);
            if (!movie.overview().isEmpty()) {
                TextView overview = activity.text(movie.overview(), 13, MUTED, false);
                overview.setMaxLines(3);
                overview.setEllipsize(TextUtils.TruncateAt.END);
                overview.setLineSpacing(dp(2), 1f);
                activity.add(card, overview, 9);
            }
            LinearLayout actions = activity.horizontal();
            Button details = activity.button("影片详情", PALE, GREEN);
            details.setContentDescription("影片详情 · " + movie.title());
            details.setOnClickListener(view -> showMovieDetails(movie));
            actions.addView(details, new LinearLayout.LayoutParams(0, dp(44), 1f));
            Button select = activity.button("查找下载链接", PALE, GREEN);
            select.setContentDescription("查找下载链接 · " + movie.title());
            select.setOnClickListener(view -> showReleases(movie));
            LinearLayout.LayoutParams selectSpace = new LinearLayout.LayoutParams(0, dp(44), 1f);
            selectSpace.leftMargin = dp(8);
            actions.addView(select, selectSpace);
            activity.add(card, actions, 10);
            activity.add(entries, card, 10);
        }
        movieList.post(this::loadVisiblePosters);
    }

    private ImageView moviePoster(ImdbMovieFeed.Movie movie) {
        ImageView poster = new ImageView(activity);
        poster.setBackground(activity.shape(PALE, 10, EDGE));
        poster.setClipToOutline(true);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (!movie.posterPath().isEmpty()) {
            poster.setContentDescription(movie.title() + "海报");
        }
        return poster;
    }

    private void loadVisiblePosters() {
        if (!root.isShown()) return;
        Rect visible = new Rect();
        for (PosterBinding binding : posters) {
            if (binding.view().getLocalVisibleRect(visible) && visible.height() >= dp(8)) {
                posterLoader.bind(binding.view(), binding.path());
            }
        }
    }

    private record PosterBinding(ImageView view, String path) {}

    private void showMovieDetails(ImdbMovieFeed.Movie movie) {
        LinearLayout content = dialogContent();
        LinearLayout summary = activity.horizontal();
        summary.setGravity(Gravity.TOP);
        ImageView poster = moviePoster(movie);
        summary.addView(poster, new LinearLayout.LayoutParams(dp(94), dp(141)));
        LinearLayout facts = activity.vertical();
        LinearLayout.LayoutParams factsSpace = new LinearLayout.LayoutParams(0, -2, 1f);
        factsSpace.leftMargin = dp(14);
        summary.addView(facts, factsSpace);
        if (!movie.originalTitle().isEmpty() && !movie.originalTitle().equalsIgnoreCase(movie.title())) {
            activity.add(facts, activity.text(movie.originalTitle(), 15, INK, true), 0);
        }
        activity.add(facts, activity.text(String.format(Locale.ROOT, "IMDb %.1f · %,d 票", movie.rating(),
                movie.votes()), 15, GREEN, true), 5);
        activity.add(facts, activity.text(movie.releaseDate() + " 上映", 13, MUTED, false), 7);
        if (!movie.genres().isEmpty()) {
            activity.add(facts, activity.text(String.join(" · ", movie.genres()), 13, GREEN, true), 8);
        }
        activity.add(content, summary, 0);
        activity.add(content, activity.text("简介", 16, INK, true), 14);
        TextView overview = activity.text(movie.overview().isEmpty() ? "暂无简介" : movie.overview(),
                14, MUTED, false);
        overview.setLineSpacing(dp(4), 1f);
        activity.add(content, overview, 6);
        content.setPadding(dp(22), dp(12), dp(22), dp(12));
        ScrollView details = new ScrollView(activity);
        details.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(movie.title()).setView(details)
                .setNegativeButton("关闭", null)
                .setPositiveButton("查找下载链接", (box, button) -> showReleases(movie)).show();
        if (!movie.posterPath().isEmpty() && dialog.isShowing()) {
            posterLoader.bind(poster, movie.posterPath());
        }
    }

    private void showReleases(ImdbMovieFeed.Movie movie) {
        if (worker != null) return;
        LinearLayout content = dialogContent();
        LinearLayout tools = activity.horizontal();
        TextView result = activity.text("正在搜索海盗湾…", 13, MUTED, true);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        tools.addView(result, new LinearLayout.LayoutParams(0, -2, 1f));
        Button filter = activity.button("筛选", PALE, GREEN);
        filter.setEnabled(false);
        LinearLayout.LayoutParams filterSpace = new LinearLayout.LayoutParams(-2, dp(44));
        filterSpace.leftMargin = dp(8);
        tools.addView(filter, filterSpace);
        activity.add(content, tools, 0);
        TextView notice = activity.text("", 12, MUTED, false);
        notice.setVisibility(View.GONE);
        activity.add(content, notice, 6);
        ListView list = new ListView(activity);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        ReleaseAdapter adapter = new ReleaseAdapter();
        list.setAdapter(adapter);
        int height = Math.round(activity.getResources().getDisplayMetrics().heightPixels
                / activity.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams rowsSpace = new LinearLayout.LayoutParams(-1, dp(Math.min(410, Math.max(140, height - 220))));
        rowsSpace.topMargin = dp(6);
        list.setVisibility(View.GONE);
        content.addView(list, rowsSpace);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(movie.title()).setView(content)
                .setNeutralButton("重试", null)
                .setNegativeButton("关闭", null).create();
        releasesDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (releasesDialog == dialog) {
                releasesDialog = null;
                stopWork();
            }
        });
        dialog.show();

        Button retry = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        list.setOnItemClickListener((parent, row, position, id) -> {
            PirateBayReleaseSource.Candidate candidate = adapter.getItem(position);
            dialog.dismiss();
            chooseLink(candidate);
        });
        Runnable render = () -> {
            PirateBayReleaseSource.SearchResult source = adapter.source;
            int total = source.candidates().size();
            int visible = adapter.getCount();
            if (total == 0) {
                result.setText("未找到相关磁力链接");
            } else if (visible == 0) {
                result.setText("没有符合筛选条件的链接");
            } else {
                result.setText((adapter.filter.active() ? visible + " / " + total : Integer.toString(total))
                        + " 条 · 按做种数排序");
            }
            List<String> messages = new ArrayList<>(2);
            if (source.incomplete()) messages.add("部分查询失败，可重试");
            if (source.capped()) messages.add("部分查询已达来源返回上限");
            notice.setText(String.join(" · ", messages));
            notice.setVisibility(messages.isEmpty() ? View.GONE : View.VISIBLE);
            filter.setEnabled(total > 0);
            filter.setText(adapter.filter.active() ? "筛选已启用" : "筛选");
            list.setVisibility(visible == 0 ? View.GONE : View.VISIBLE);
            list.setSelection(0);
        };
        filter.setOnClickListener(view -> showReleaseFilters(adapter.filter, selected -> {
            adapter.filter = selected;
            adapter.update();
            render.run();
        }));
        Runnable search = () -> {
            if (worker != null) return;
            retry.setVisibility(View.GONE);
            filter.setEnabled(false);
            result.setText("正在搜索海盗湾…");
            launch(() -> findReleases(movie), found -> {
                adapter.source = found;
                adapter.update();
                render.run();
                retry.setVisibility(found.incomplete() ? View.VISIBLE : View.GONE);
            }, error -> {
                filter.setEnabled(!adapter.source.candidates().isEmpty());
                result.setText("链接搜索失败 · " + error);
                retry.setVisibility(View.VISIBLE);
            });
        };
        retry.setOnClickListener(view -> search.run());
        search.run();
    }

    private PirateBayReleaseSource.SearchResult findReleases(ImdbMovieFeed.Movie movie) throws IOException {
        return new PirateBayReleaseSource().search(movie.originalTitle(), movie.title(),
                movie.releaseDate().getYear(), movie.imdbId());
    }

    private void showReleaseFilters(ReleaseFilter current, Consumer<ReleaseFilter> apply) {
        LinearLayout form = dialogContent();
        Button preset = activity.button("2160p · HDR10 · 小于 40 GB", PALE, GREEN);
        activity.add(form, preset, 0);

        String[] resolutions = new String[RELEASE_HEIGHTS.length];
        int resolutionIndex = 0;
        for (int index = 0; index < RELEASE_HEIGHTS.length; index++) {
            int height = RELEASE_HEIGHTS[index];
            if (height == current.height()) resolutionIndex = index;
            resolutions[index] = height < 0 ? "不限" : height == 0 ? "未标注" : height + "p";
        }
        activity.add(form, activity.text("分辨率", 13, INK, true), 9);
        Spinner resolution = filterSpinner(resolutions, resolutionIndex);
        activity.add(form, resolution, 2);

        ReleaseFilter.VideoFormat[] formats = ReleaseFilter.VideoFormat.values();
        String[] labels = new String[formats.length];
        for (int index = 0; index < formats.length; index++) labels[index] = formats[index].label;
        activity.add(form, activity.text("HDR 格式", 13, INK, true), 7);
        Spinner videoFormat = filterSpinner(labels, current.format().ordinal());
        activity.add(form, videoFormat, 2);

        activity.add(form, activity.text("大小小于（GB）", 13, INK, true), 7);
        EditText max = filterField("不限", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 24);
        if (current.maxBytesExclusive() > 0) {
            max.setText(BigDecimal.valueOf(current.maxBytesExclusive(), 9).stripTrailingZeros().toPlainString());
        }
        activity.add(form, max, 4);
        activity.add(form, activity.text("最少做种数", 13, INK, true), 7);
        EditText minimum = filterField("不限", InputType.TYPE_CLASS_NUMBER, 12);
        if (current.minSeeders() > 0) minimum.setText(Integer.toString(current.minSeeders()));
        activity.add(form, minimum, 4);
        activity.add(form, activity.text("标题关键字", 13, INK, true), 7);
        EditText keyword = filterField("可选", InputType.TYPE_CLASS_TEXT, 120);
        keyword.setText(current.keyword());
        activity.add(form, keyword, 4);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("筛选磁力链接").setView(scroll)
                .setNeutralButton("清除筛选", (box, button) -> apply.accept(ReleaseFilter.ALL))
                .setNegativeButton("取消", null).setPositiveButton("应用", null).create();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        preset.setOnClickListener(view -> {
            apply.accept(ReleaseFilter.UHD_HDR10_UNDER_40_GB);
            dialog.dismiss();
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            long maxBytes;
            int minSeeders;
            try {
                maxBytes = ReleaseFilter.parseMaxGigabytes(max.getText().toString());
            } catch (IllegalArgumentException invalid) {
                max.setError("请输入有效的正数 GB 值");
                return;
            }
            try {
                minSeeders = ReleaseFilter.parseMinimumSeeders(minimum.getText().toString());
            } catch (IllegalArgumentException invalid) {
                minimum.setError("请输入有效的非负整数");
                return;
            }
            apply.accept(new ReleaseFilter(RELEASE_HEIGHTS[resolution.getSelectedItemPosition()],
                    formats[videoFormat.getSelectedItemPosition()], maxBytes, minSeeders, keyword.getText().toString()));
            dialog.dismiss();
        });
    }

    private Spinner filterSpinner(String[] values, int selected) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> options = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, values);
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(options);
        spinner.setSelection(selected);
        spinner.setMinimumHeight(dp(44));
        spinner.setBackground(activity.shape(PALE, 8, EDGE));
        return spinner;
    }

    private EditText filterField(String hint, int type, int maxLength) {
        EditText field = activity.field(hint, type);
        field.setSingleLine(true);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        return field;
    }

    private final class ReleaseAdapter extends BaseAdapter {
        PirateBayReleaseSource.SearchResult source = new PirateBayReleaseSource.SearchResult(Collections.emptyList(), false, false);
        ReleaseFilter filter = ReleaseFilter.ALL;
        private final List<PirateBayReleaseSource.Candidate> visible = new ArrayList<>();

        void update() {
            visible.clear();
            for (PirateBayReleaseSource.Candidate candidate : source.candidates()) {
                if (filter.matches(candidate)) visible.add(candidate);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public PirateBayReleaseSource.Candidate getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ReleaseRow fields;
            if (convertView == null) {
                LinearLayout row = activity.vertical();
                row.setPadding(dp(10), dp(10), dp(10), dp(10));
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x290e291b),
                        activity.shape(PALE, 10, 0), null));
                TextView title = activity.text("", 14, INK, true);
                TextView tags = activity.text("", 12, MUTED, false);
                activity.add(row, title, 0);
                activity.add(row, tags, 5);
                fields = new ReleaseRow(title, tags);
                row.setTag(fields);
                convertView = row;
            } else {
                fields = (ReleaseRow) convertView.getTag();
            }
            PirateBayReleaseSource.Candidate release = getItem(position);
            fields.title().setText(release.name);
            fields.tags().setText(releaseTags(release));
            return convertView;
        }
    }

    private record ReleaseRow(TextView title, TextView tags) {}

    private void chooseLink(PirateBayReleaseSource.Candidate release) {
        new AlertDialog.Builder(activity).setTitle("选择磁力链接")
                .setMessage(release.name + "\n\n" + releaseTags(release))
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
        if (release.bytes <= 0) return "大小未知";
        long hundredths = release.bytes / 10_000_000L;
        return String.format(Locale.ROOT, "%d.%02d GB", hundredths / 100, hundredths % 100);
    }

    private static String releaseTags(PirateBayReleaseSource.Candidate release) {
        List<String> tags = new ArrayList<>();
        tags.add(releaseSize(release));
        tags.add(release.seeders + " 个做种");
        if (release.height > 0) tags.add(release.height + "p");
        if (release.hdr10) tags.add("HDR10");
        if (release.hdr10Plus) tags.add("HDR10+");
        if (release.dolbyVision) tags.add("杜比视界");
        if (release.hdr && !release.hdr10 && !release.hdr10Plus && !release.dolbyVision) tags.add("HDR");
        if (release.sdr) tags.add("SDR");
        return String.join(" · ", tags);
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
