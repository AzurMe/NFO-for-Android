package org.kodiscraper.app;

import android.app.AlertDialog;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class PosterWallScreen implements AutoCloseable {
    private static final int TOP_TAGS = 7;
    private static final Object MORE_TAGS = new Object();
    private final MainActivity activity;
    private final AppAppearance appearance;
    private final LinearLayout root;
    private final HorizontalScrollView tagScroll;
    private final LinearLayout tags;
    private final TextView status;
    private final Button chooseFolder;
    private final GridView grid;
    private final PosterAdapter adapter = new PosterAdapter();
    private MoviePosterLoader posters;
    private List<ScrapeEngine.LibraryMovie> library = Collections.emptyList();
    private List<String> tagLabels = Collections.emptyList();
    private Thread scan;
    private Uri directory;
    private String selectedTag;
    private boolean closed;

    PosterWallScreen(MainActivity activity, Runnable refresh, Runnable pickFolder, Runnable organize) {
        this.activity = activity;
        appearance = activity.appearance();
        posters = new MoviePosterLoader(activity.getContentResolver());
        root = activity.vertical();
        Button reload = activity.button("刷新", appearance.tint, appearance.accent);
        reload.setContentDescription("刷新海报墙");
        reload.setOnClickListener(view -> refresh.run());
        LinearLayout actions = activity.horizontal();
        Button clean = activity.button("整理", appearance.tint, appearance.accent);
        clean.setContentDescription("整理与重命名");
        clean.setOnClickListener(view -> organize.run());
        actions.addView(clean, new LinearLayout.LayoutParams(-2, activity.dp(48)));
        LinearLayout.LayoutParams reloadSpace = new LinearLayout.LayoutParams(-2, activity.dp(48));
        reloadSpace.leftMargin = activity.dp(6);
        actions.addView(reload, reloadSpace);
        root.addView(activity.createToolbar("电影海报墙", actions));
        tagScroll = new HorizontalScrollView(activity);
        tagScroll.setVisibility(View.GONE);
        tagScroll.setHorizontalScrollBarEnabled(false);
        tags = activity.horizontal();
        tags.setPadding(activity.dp(14), activity.dp(8), activity.dp(14), activity.dp(5));
        tagScroll.addView(tags);
        root.addView(tagScroll, new LinearLayout.LayoutParams(-1, -2));
        status = activity.text("", 13, appearance.muted, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(activity.dp(16), activity.dp(3), activity.dp(16), activity.dp(8));
        root.addView(status);
        chooseFolder = activity.button("设置电影目录", appearance.tint, appearance.accent);
        chooseFolder.setMinHeight(activity.dp(48));
        chooseFolder.setOnClickListener(view -> pickFolder.run());
        LinearLayout.LayoutParams choice = new LinearLayout.LayoutParams(-2, -2);
        choice.leftMargin = activity.dp(16);
        root.addView(chooseFolder, choice);
        grid = new GridView(activity);
        grid.setNumColumns(2);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(activity.dp(12));
        grid.setVerticalSpacing(activity.dp(12));
        grid.setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(16));
        grid.setClipToPadding(false);
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    View view() { return root; }

    void refresh(Uri tree, boolean preferFolder) {
        if (closed) return;
        if (scan != null) scan.interrupt();
        scan = null;
        if (!Objects.equals(directory, tree)) {
            directory = tree;
            selectedTag = null;
            replaceMovies(Collections.emptyList());
        }
        chooseFolder.setVisibility(tree == null ? View.VISIBLE : View.GONE);
        if (tree == null) {
            status.setText("选择电影目录后，这里会显示已刮削的影片。");
            return;
        }
        status.setText("正在读取本地资料…");
        scan = new Thread(() -> {
            Thread worker = Thread.currentThread();
            long started = System.nanoTime();
            List<ScrapeEngine.LibraryMovie> found = new ArrayList<>();
            String error = null;
            try {
                MovieStorage storage = new MovieStorage(activity.getApplicationContext(), tree);
                List<MovieStorage.Movie> files = storage.scan(preferFolder);
                for (MovieStorage.Movie file : files) {
                    ScrapeEngine.checkCancelled();
                    ScrapeEngine.LibraryMovie movie = ScrapeEngine.libraryMovie(storage, file);
                    if (movie != null) found.add(movie);
                }
                found.sort(Comparator.comparing(ScrapeEngine.LibraryMovie::title, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(ScrapeEngine.LibraryMovie::year));
                DebugLog.event("library", "scanned=" + files.size() + " complete=" + found.size()
                        + " duration_ms=" + DebugLog.elapsed(started));
            } catch (IOException | RuntimeException problem) {
                if (worker.isInterrupted()) return;
                DebugLog.failure("library", problem);
                error = "无法读取电影目录，请检查文件夹访问权限。";
            }
            String outcome = error;
            activity.runOnUiThread(() -> {
                if (closed || scan != worker) return;
                scan = null;
                if (outcome != null) {
                    status.setText(outcome);
                } else {
                    replaceMovies(found);
                    if (library.isEmpty()) status.setText("暂无已刮削完成的影片。");
                }
            });
        }, "PosterWallScan");
        scan.start();
    }

    private void replaceMovies(List<ScrapeEngine.LibraryMovie> found) {
        posters.close();
        posters = new MoviePosterLoader(activity.getContentResolver());
        library = found;
        Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ScrapeEngine.LibraryMovie movie : found) {
            Set<String> labels = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            labels.addAll(movie.tags());
            for (String label : labels) counts.merge(label, 1, Integer::sum);
        }
        tagLabels = new ArrayList<>(counts.keySet());
        tagLabels.sort(Comparator.comparingInt((String label) -> counts.get(label)).reversed()
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        if (selectedTag != null && !counts.containsKey(selectedTag)) selectedTag = null;
        rebuildTags();
        filter();
    }

    private void rebuildTags() {
        tags.removeAllViews();
        tagScroll.setVisibility(tagLabels.isEmpty() ? View.GONE : View.VISIBLE);
        if (tagLabels.isEmpty()) return;
        addTag("全部", null);
        List<String> featured = tagLabels.subList(0, Math.min(TOP_TAGS, tagLabels.size()));
        for (String tag : featured) addTag(tag, tag);
        if (selectedTag != null && featured.stream().noneMatch(selectedTag::equalsIgnoreCase)) {
            addTag(selectedTag, selectedTag);
        }
        if (tagLabels.size() > TOP_TAGS) addTag("更多标签", MORE_TAGS);
    }

    private void addTag(String label, Object value) {
        Button chip = activity.button(label, appearance.surface, appearance.muted);
        chip.setTextSize(13);
        chip.setMaxWidth(activity.dp(210));
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setTag(value);
        chip.setOnClickListener(view -> {
            if (value == MORE_TAGS) {
                showAllTags();
                return;
            }
            selectedTag = (String) value;
            filter();
        });
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, activity.dp(40));
        space.rightMargin = activity.dp(6);
        tags.addView(chip, space);
    }

    private void showAllTags() {
        String[] choices = tagLabels.toArray(new String[0]);
        new AlertDialog.Builder(activity).setTitle("Kodi 标签")
                .setItems(choices, (dialog, index) -> {
                    selectedTag = choices[index];
                    rebuildTags();
                    filter();
                }).setNegativeButton("关闭", null).show();
    }

    private void filter() {
        for (int index = 0; index < tags.getChildCount(); index++) {
            Button chip = (Button) tags.getChildAt(index);
            if (chip.getTag() == MORE_TAGS) continue;
            String value = (String) chip.getTag();
            boolean active = value == null ? selectedTag == null : value.equalsIgnoreCase(selectedTag);
            chip.setSelected(active);
            chip.setTextColor(active ? appearance.accent : appearance.muted);
            chip.setBackground(activity.shape(active ? appearance.tint : appearance.surface, 12, appearance.border));
        }
        List<ScrapeEngine.LibraryMovie> visible = new ArrayList<>();
        for (ScrapeEngine.LibraryMovie movie : library) {
            if (selectedTag == null || movie.tags().stream().anyMatch(selectedTag::equalsIgnoreCase)) visible.add(movie);
        }
        adapter.visible = visible;
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
        status.setText(visible.size() + " 部影片");
    }

    @Override public void close() {
        closed = true;
        if (scan != null) scan.interrupt();
        posters.close();
    }

    private final class PosterAdapter extends BaseAdapter {
        private List<ScrapeEngine.LibraryMovie> visible = Collections.emptyList();
        @Override public int getCount() { return visible.size(); }
        @Override public ScrapeEngine.LibraryMovie getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean isEnabled(int position) { return false; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            PosterCard card = recycled instanceof PosterCard current ? current : new PosterCard();
            ScrapeEngine.LibraryMovie movie = getItem(position);
            card.title.setText(movie.year() > 0 ? movie.title() + " · " + movie.year() : movie.title());
            if (card.image.getTag() != posters) {
                card.image.setImageDrawable(null);
                card.image.setTag(posters);
            }
            posters.bindLocal(card.image, movie.poster());
            return card;
        }
    }

    private final class PosterCard extends LinearLayout {
        final ImageView image;
        final TextView title;

        PosterCard() {
            super(activity);
            setOrientation(VERTICAL);
            setBackground(activity.shape(appearance.surface, 12, appearance.border));
            setClipToOutline(true);
            image = new ImageView(activity) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int width = MeasureSpec.getSize(widthSpec);
                    setMeasuredDimension(width, width * 3 / 2);
                }
            };
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(appearance.tint);
            image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(image, new LinearLayout.LayoutParams(-1, -2));
            title = activity.text("", 14, appearance.foreground, true);
            title.setPadding(activity.dp(9), activity.dp(8), activity.dp(9), activity.dp(8));
            title.setLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, new LinearLayout.LayoutParams(-1, -2));
        }
    }
}
