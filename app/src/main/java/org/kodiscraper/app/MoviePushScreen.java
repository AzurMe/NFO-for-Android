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

final class MoviePushScreen {
    private static final int[] RELEASE_HEIGHTS = {-1, 4320, 2160, 1440, 1080, 720, 576, 480, 0};

    private final MainActivity activity;
    private final AppAppearance appearance;
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
    private boolean loadingFeed;
    private Thread worker;
    private AlertDialog releasesDialog;

    MoviePushScreen(MainActivity activity) {
        this.activity = activity;
        appearance = activity.appearance();
        root = activity.vertical();
        root.setBackgroundColor(appearance.background);
        refresh = activity.button("刷新", appearance.tint, appearance.foreground);
        refresh.setOnClickListener(view -> refreshClicked());
        activity.add(root, activity.createToolbar("电影推送", refresh), 0);

        LinearLayout content = activity.vertical();
        content.setPadding(dp(18), dp(3), dp(18), dp(10));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        range = activity.text("最近 30 天上映", 13, appearance.muted, false);
        activity.add(content, range, 0);

        LinearLayout filters = activity.horizontal();
        filters.setBackground(activity.shape(appearance.tint, 14, 0));
        filters.setPadding(dp(4), dp(4), dp(4), dp(4));
        popular = activity.button("热门 · 投票数", appearance.primary, appearance.onPrimary);
        popular.setMinHeight(dp(48));
        popular.setOnClickListener(view -> sort(false));
        filters.addView(popular, new LinearLayout.LayoutParams(0, -2, 1f));
        rated = activity.button("高分 · 7.0 以上", appearance.tint, appearance.foreground);
        rated.setMinHeight(dp(48));
        rated.setOnClickListener(view -> sort(true));
        LinearLayout.LayoutParams ratedSpace = new LinearLayout.LayoutParams(0, -2, 1f);
        ratedSpace.leftMargin = dp(4);
        filters.addView(rated, ratedSpace);
        activity.add(content, filters, 8);
        markFilter(popular, true);
        markFilter(rated, false);

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(appearance.accent));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams barSpace = new LinearLayout.LayoutParams(-1, dp(4));
        barSpace.topMargin = dp(8);
        content.addView(progress, barSpace);
        status = activity.text("", 13, appearance.muted, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        activity.add(content, status, 8);

        movieList = new ScrollView(activity);
        movieList.setClipToPadding(false);
        movieList.setFillViewport(true);
        movieList.setOnScrollChangeListener((view, x, y, oldX, oldY) -> loadVisiblePosters());
        content.addView(movieList, new LinearLayout.LayoutParams(-1, 0, 1));
        entries = activity.vertical();
        entries.setPadding(0, dp(2), 0, dp(4));
        movieList.addView(entries, new ScrollView.LayoutParams(-1, -2));
        movieList.getViewTreeObserver().addOnGlobalLayoutListener(this::loadVisiblePosters);
        TextView attribution = activity.text("影片资料、评分与海报来源于 IMDb", 11, appearance.muted, false);
        activity.add(content, attribution, 5);
    }

    View view() {
        return root;
    }

    boolean isBusy() {
        return worker != null;
    }

    record State(List<ImdbMovieFeed.Movie> movies, LocalDate loadedThrough, boolean highRated, int scrollY) {}

    State saveState() {
        return new State(Collections.unmodifiableList(new ArrayList<>(movies)), loadedThrough,
                highRated, movieList.getScrollY());
    }

    void restoreState(State state) {
        if (state == null) {
            return;
        }
        highRated = state.highRated();
        markFilter(popular, !highRated);
        markFilter(rated, highRated);
        if (state.loadedThrough() != null) {
            loadedThrough = state.loadedThrough();
            movies = state.movies();
            requested = true;
            updateRange();
            renderMovies();
            movieList.post(() -> movieList.scrollTo(0, state.scrollY()));
        }
    }

    void onShown() {
        if (!requested && worker == null) {
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
            refreshMovies();
        }
    }

    private void refreshMovies() {
        requested = true;
        LocalDate today = LocalDate.now();
        ImdbMovieFeed feed = new ImdbMovieFeed();
        posterLoader.resetFailures();
        setFeedLoading(true);
        status.setText("正在从 IMDb 读取近 30 天上映的影片…");
        launch(() -> feed.load(today), result -> {
            movies = result;
            loadedThrough = today;
            updateRange();
            setFeedLoading(false);
            renderMovies();
        }, error -> {
            setFeedLoading(false);
            renderMovies();
            status.setText("刷新失败 · " + error);
        });
    }

    private void updateRange() {
        range.setText(loadedThrough.minusDays(29) + " 至 " + loadedThrough + " 上映");
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
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{selected ? appearance.onPrimary : appearance.disabled,
                        selected ? appearance.onPrimary : appearance.foreground}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(appearance.ripple),
                activity.shape(selected ? appearance.primary : appearance.tint, 10, 0), null));
    }

    private void renderMovies() {
        List<ImdbMovieFeed.Movie> visible = new ArrayList<>(movies);
        Comparator<ImdbMovieFeed.Movie> rating = Comparator.comparingDouble(ImdbMovieFeed.Movie::rating).reversed();
        Comparator<ImdbMovieFeed.Movie> votes = Comparator.comparingInt(ImdbMovieFeed.Movie::votes).reversed();
        if (highRated) {
            visible.removeIf(movie -> movie.rating() < ImdbMovieFeed.HIGH_RATING);
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
            card.setPadding(dp(14), dp(13), dp(14), dp(12));
            card.setBackground(activity.shape(appearance.surface, 16, appearance.border));
            LinearLayout summary = activity.horizontal();
            summary.setGravity(Gravity.TOP);
            ImageView poster = moviePoster(movie);
            summary.addView(poster, new LinearLayout.LayoutParams(dp(72), dp(108)));
            if (!movie.posterUrl().isEmpty()) {
                posters.add(new PosterBinding(poster, movie.posterUrl()));
            }
            LinearLayout facts = activity.vertical();
            LinearLayout.LayoutParams factsSpace = new LinearLayout.LayoutParams(0, -2, 1f);
            factsSpace.leftMargin = dp(12);
            summary.addView(facts, factsSpace);
            TextView title = activity.text(movie.title(), 18, appearance.foreground, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            activity.add(facts, title, 0);
            if (!movie.originalTitle().isEmpty() && !movie.originalTitle().equalsIgnoreCase(movie.title())) {
                TextView original = activity.text(movie.originalTitle(), 13, appearance.muted, false);
                original.setMaxLines(2);
                original.setEllipsize(TextUtils.TruncateAt.END);
                activity.add(facts, original, 3);
            }
            String score = String.format(Locale.ROOT, "IMDb %.1f", movie.rating());
            TextView badge = activity.text(score, 12, appearance.foreground, true);
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            badge.setBackground(activity.shape(appearance.tint, 7, 0));
            LinearLayout.LayoutParams badgeSpace = new LinearLayout.LayoutParams(-2, -2);
            badgeSpace.topMargin = dp(5);
            facts.addView(badge, badgeSpace);
            activity.add(facts, activity.text((movie.releaseLabel().isEmpty() ? "" : movie.releaseLabel() + " · ") + number.format(movie.votes())
                    + " 票", 13, appearance.muted, false), 4);
            if (!movie.genres().isEmpty()) {
                TextView genres = activity.text(String.join(" · ", movie.genres()), 12, appearance.muted, false);
                genres.setMaxLines(2);
                genres.setEllipsize(TextUtils.TruncateAt.END);
                activity.add(facts, genres, 4);
            }
            activity.add(card, summary, 0);
            if (!movie.overview().isEmpty()) {
                TextView overview = activity.text(movie.overview(), 13, appearance.muted, false);
                overview.setMaxLines(3);
                overview.setEllipsize(TextUtils.TruncateAt.END);
                overview.setLineSpacing(dp(2), 1f);
                activity.add(card, overview, 9);
            }
            LinearLayout actions = activity.horizontal();
            Button details = activity.button("影片详情", appearance.surface, appearance.foreground);
            details.setMinHeight(dp(48));
            details.setContentDescription("影片详情 · " + movie.title());
            details.setOnClickListener(view -> showMovieDetails(movie));
            details.setBackground(new RippleDrawable(ColorStateList.valueOf(appearance.ripple),
                    activity.shape(appearance.surface, 12, appearance.border), null));
            actions.addView(details, new LinearLayout.LayoutParams(0, -2, 1f));
            Button select = activity.button("查找下载链接", appearance.tint, appearance.accent);
            select.setMinHeight(dp(48));
            select.setContentDescription("查找下载链接 · " + movie.title());
            select.setOnClickListener(view -> showReleases(movie));
            LinearLayout.LayoutParams selectSpace = new LinearLayout.LayoutParams(0, -2, 1f);
            selectSpace.leftMargin = dp(8);
            actions.addView(select, selectSpace);
            activity.add(card, actions, 9);
            activity.add(entries, card, 8);
        }
        movieList.post(this::loadVisiblePosters);
    }

    private ImageView moviePoster(ImdbMovieFeed.Movie movie) {
        ImageView poster = new ImageView(activity);
        poster.setBackground(activity.shape(appearance.tint, 9, appearance.border));
        poster.setClipToOutline(true);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (!movie.posterUrl().isEmpty()) {
            poster.setContentDescription(movie.title() + "海报");
        }
        return poster;
    }

    private void loadVisiblePosters() {
        if (!root.isShown()) return;
        Rect visible = new Rect();
        for (PosterBinding binding : posters) {
            if (binding.view().getLocalVisibleRect(visible) && visible.height() >= dp(8)) {
                posterLoader.bindImdb(binding.view(), binding.path());
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
            activity.add(facts, activity.text(movie.originalTitle(), 15, appearance.foreground, true), 0);
        }
        activity.add(facts, activity.text(String.format(Locale.ROOT, "IMDb %.1f · %,d 票", movie.rating(),
                movie.votes()), 15, appearance.foreground, true), 5);
        if (!movie.releaseLabel().isEmpty()) {
            activity.add(facts, activity.text(movie.releaseLabel(), 13, appearance.muted, false), 7);
        }
        if (!movie.genres().isEmpty()) {
            activity.add(facts, activity.text(String.join(" · ", movie.genres()), 13, appearance.muted, false), 8);
        }
        activity.add(content, summary, 0);
        activity.add(content, activity.text("简介", 16, appearance.foreground, true), 14);
        TextView overview = activity.text(movie.overview().isEmpty() ? "暂无简介" : movie.overview(),
                14, appearance.muted, false);
        overview.setLineSpacing(dp(4), 1f);
        activity.add(content, overview, 6);
        content.setPadding(dp(22), dp(12), dp(22), dp(12));
        ScrollView details = new ScrollView(activity);
        details.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(movie.title()).setView(details)
                .setNegativeButton("关闭", null)
                .setPositiveButton("查找下载链接", (box, button) -> showReleases(movie)).show();
        if (!movie.posterUrl().isEmpty() && dialog.isShowing()) {
            posterLoader.bindImdb(poster, movie.posterUrl());
        }
    }

    private void showReleases(ImdbMovieFeed.Movie movie) {
        if (worker != null) return;
        LinearLayout content = dialogContent();
        LinearLayout tools = activity.horizontal();
        String host = PirateBayReleaseSource.HOST;
        String searching = "正在查询 " + host + "…";
        TextView result = activity.text(searching, 13, appearance.muted, true);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        tools.addView(result, new LinearLayout.LayoutParams(0, -2, 1f));
        Button filter = activity.button("筛选", appearance.tint, appearance.accent);
        filter.setMinHeight(dp(48));
        filter.setEnabled(false);
        LinearLayout.LayoutParams filterSpace = new LinearLayout.LayoutParams(-2, -2);
        filterSpace.leftMargin = dp(8);
        tools.addView(filter, filterSpace);
        Button retry = activity.button("重试", appearance.tint, appearance.foreground);
        retry.setMinHeight(dp(48));
        retry.setContentDescription("重试查询 " + host);
        retry.setVisibility(View.GONE);
        LinearLayout.LayoutParams retrySpace = new LinearLayout.LayoutParams(-2, -2);
        retrySpace.leftMargin = dp(6);
        tools.addView(retry, retrySpace);
        activity.add(content, tools, 0);
        TextView notice = activity.text("", 12, appearance.muted, false);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
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
                .setNegativeButton("关闭", null).create();
        releasesDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (releasesDialog == dialog) {
                releasesDialog = null;
                stopWork();
            }
        });
        dialog.show();

        list.setOnItemClickListener((parent, row, position, id) -> {
            PirateBayReleaseSource.Candidate candidate = adapter.getItem(position);
            dialog.dismiss();
            chooseLink(candidate);
        });
        Runnable render = () -> {
            PirateBayReleaseSource.SearchResult source = adapter.source;
            int total = source.candidates().size();
            int visible = adapter.getCount();
            boolean failed = !adapter.queryFailure.isEmpty();
            if (failed && total == 0) {
                result.setText("无法查询 " + host);
            } else if (total == 0 && source.incomplete()) {
                result.setText("暂无法确认有没有相关链接");
            } else if (total == 0) {
                result.setText("未找到相关磁力链接");
            } else if (visible == 0) {
                result.setText("没有符合筛选条件的链接");
            } else {
                result.setText((adapter.filter.active() ? visible + " / " + total : Integer.toString(total))
                        + " 条 · 按做种数排序");
            }
            List<String> messages = new ArrayList<>(3);
            if (failed) {
                messages.add(adapter.queryFailure);
                if (total > 0) messages.add("已保留上次查询结果");
            } else if (source.incomplete()) {
                messages.add(host + " 部分查询失败，结果可能不完整");
            }
            if (source.capped()) messages.add("部分查询已达来源返回上限");
            notice.setText(String.join("\n", messages));
            notice.setVisibility(messages.isEmpty() ? View.GONE : View.VISIBLE);
            filter.setEnabled(total > 0);
            filter.setText(adapter.filter.active() ? "筛选已启用" : "筛选");
            retry.setVisibility(failed || source.incomplete() ? View.VISIBLE : View.GONE);
            list.setVisibility(visible == 0 ? View.GONE : View.VISIBLE);
        };
        filter.setOnClickListener(view -> showReleaseFilters(adapter.filter, selected -> {
            adapter.filter = selected;
            adapter.update();
            render.run();
            list.setSelection(0);
        }));
        Runnable search = () -> {
            if (worker != null) return;
            retry.setVisibility(View.GONE);
            filter.setEnabled(false);
            result.setText(searching);
            notice.setVisibility(View.GONE);
            launch(() -> findReleases(movie), found -> {
                adapter.source = found;
                adapter.queryFailure = "";
                adapter.update();
                render.run();
                list.setSelection(0);
            }, error -> {
                adapter.queryFailure = error;
                render.run();
            });
        };
        retry.setOnClickListener(view -> search.run());
        search.run();
    }

    private PirateBayReleaseSource.SearchResult findReleases(ImdbMovieFeed.Movie movie) throws IOException {
        return new PirateBayReleaseSource().search(movie.originalTitle(), movie.title(),
                movie.year(), movie.imdbId());
    }

    private void showReleaseFilters(ReleaseFilter current, Consumer<ReleaseFilter> apply) {
        LinearLayout form = dialogContent();
        Button preset = activity.button("2160p · HDR10 · 小于 40 GB", appearance.tint, appearance.accent);
        preset.setMinHeight(dp(48));
        activity.add(form, preset, 0);

        String[] resolutions = new String[RELEASE_HEIGHTS.length];
        int resolutionIndex = 0;
        for (int index = 0; index < RELEASE_HEIGHTS.length; index++) {
            int height = RELEASE_HEIGHTS[index];
            if (height == current.height()) resolutionIndex = index;
            resolutions[index] = height < 0 ? "不限" : height == 0 ? "未标注" : height + "p";
        }
        Spinner resolution = filterSpinner(resolutions, resolutionIndex);
        addFilterControl(form, "分辨率", resolution);

        ReleaseFilter.VideoFormat[] formats = ReleaseFilter.VideoFormat.values();
        String[] labels = new String[formats.length];
        for (int index = 0; index < formats.length; index++) labels[index] = formats[index].label;
        Spinner videoFormat = filterSpinner(labels, current.format().ordinal());
        addFilterControl(form, "HDR 格式", videoFormat);

        EditText max = filterField("不限", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 24);
        if (current.maxBytesExclusive() > 0) {
            max.setText(BigDecimal.valueOf(current.maxBytesExclusive(), 9).stripTrailingZeros().toPlainString());
        }
        addFilterControl(form, "大小小于（GB）", max);
        EditText minimum = filterField("不限", InputType.TYPE_CLASS_NUMBER, 12);
        if (current.minSeeders() > 0) minimum.setText(Integer.toString(current.minSeeders()));
        addFilterControl(form, "最少做种数", minimum);
        EditText keyword = filterField("可选", InputType.TYPE_CLASS_TEXT, 120);
        keyword.setText(current.keyword());
        addFilterControl(form, "标题关键字", keyword);

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

    private void addFilterControl(LinearLayout form, String name, View control) {
        TextView label = activity.text(name, 13, appearance.foreground, true);
        control.setId(View.generateViewId());
        label.setLabelFor(control.getId());
        activity.add(form, label, 9);
        activity.add(form, control, 4);
    }

    private Spinner filterSpinner(String[] values, int selected) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> options = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, values);
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(options);
        spinner.setSelection(selected);
        spinner.setMinimumHeight(dp(48));
        return spinner;
    }

    private EditText filterField(String hint, int type, int maxLength) {
        EditText field = activity.field(hint, type);
        field.setSingleLine(true);
        field.setMinHeight(dp(48));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        return field;
    }

    private final class ReleaseAdapter extends BaseAdapter {
        PirateBayReleaseSource.SearchResult source = new PirateBayReleaseSource.SearchResult(Collections.emptyList(), false, false);
        ReleaseFilter filter = ReleaseFilter.ALL;
        String queryFailure = "";
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
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(appearance.ripple),
                        activity.shape(appearance.tint, 10, 0), null));
                TextView title = activity.text("", 14, appearance.foreground, true);
                TextView tags = activity.text("", 12, appearance.muted, false);
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
    }

    private int dp(float value) {
        return activity.dp(value);
    }
}
