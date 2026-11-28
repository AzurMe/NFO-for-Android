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
import android.widget.ScrollView;
import android.widget.TextView;

import org.w3c.dom.Element;

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
    private static final int TOP_GENRES = 7;
    private static final Object MORE_GENRES = new Object();
    private final MainActivity activity;
    private final AppAppearance appearance;
    private final LinearLayout root;
    private final HorizontalScrollView genreScroll;
    private final LinearLayout genreChips;
    private final TextView status;
    private final Button chooseFolder;
    private final GridView grid;
    private final PosterAdapter adapter = new PosterAdapter();
    private MoviePosterLoader posters;
    private List<ScrapeEngine.LibraryMovie> library = Collections.emptyList();
    private List<String> genreLabels = Collections.emptyList();
    private Thread scan;
    private Thread detailRead;
    private AlertDialog movieDetails;
    private AlertDialog trailerDialog;
    private Uri directory;
    private String selectedGenre;
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
        genreScroll = new HorizontalScrollView(activity);
        genreScroll.setVisibility(View.GONE);
        genreScroll.setHorizontalScrollBarEnabled(false);
        genreChips = activity.horizontal();
        genreChips.setPadding(activity.dp(14), activity.dp(8), activity.dp(14), activity.dp(5));
        genreScroll.addView(genreChips);
        root.addView(genreScroll, new LinearLayout.LayoutParams(-1, -2));
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
        grid.setOnItemClickListener((parent, view, position, id) -> showMovie(adapter.getItem(position)));
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    View view() { return root; }

    void refresh(Uri tree, boolean preferFolder) {
        if (closed) return;
        if (scan != null) scan.interrupt();
        scan = null;
        if (!Objects.equals(directory, tree)) {
            directory = tree;
            selectedGenre = null;
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
            labels.addAll(movie.genres());
            for (String label : labels) counts.merge(label, 1, Integer::sum);
        }
        genreLabels = new ArrayList<>(counts.keySet());
        genreLabels.sort(Comparator.comparingInt((String label) -> counts.get(label)).reversed()
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        if (selectedGenre != null && !counts.containsKey(selectedGenre)) selectedGenre = null;
        rebuildGenres();
        filter();
    }

    private void rebuildGenres() {
        genreChips.removeAllViews();
        genreScroll.setVisibility(genreLabels.isEmpty() ? View.GONE : View.VISIBLE);
        if (genreLabels.isEmpty()) return;
        addGenre("全部", null);
        List<String> featured = genreLabels.subList(0, Math.min(TOP_GENRES, genreLabels.size()));
        for (String genre : featured) addGenre(genre, genre);
        if (selectedGenre != null && featured.stream().noneMatch(selectedGenre::equalsIgnoreCase)) {
            addGenre(selectedGenre, selectedGenre);
        }
        if (genreLabels.size() > TOP_GENRES) addGenre("更多类型", MORE_GENRES);
    }

    private void addGenre(String label, Object value) {
        Button chip = activity.button(label, appearance.surface, appearance.muted);
        chip.setTextSize(13);
        chip.setMaxWidth(activity.dp(210));
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setTag(value);
        chip.setOnClickListener(view -> {
            if (value == MORE_GENRES) {
                showAllGenres();
                return;
            }
            selectedGenre = (String) value;
            filter();
        });
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, activity.dp(40));
        space.rightMargin = activity.dp(6);
        genreChips.addView(chip, space);
    }

    private void showAllGenres() {
        String[] choices = genreLabels.toArray(new String[0]);
        new AlertDialog.Builder(activity).setTitle("电影类型")
                .setItems(choices, (dialog, index) -> {
                    selectedGenre = choices[index];
                    rebuildGenres();
                    filter();
                }).setNegativeButton("关闭", null).show();
    }

    private void filter() {
        for (int index = 0; index < genreChips.getChildCount(); index++) {
            Button chip = (Button) genreChips.getChildAt(index);
            if (chip.getTag() == MORE_GENRES) continue;
            String value = (String) chip.getTag();
            boolean active = value == null ? selectedGenre == null : value.equalsIgnoreCase(selectedGenre);
            chip.setSelected(active);
            chip.setTextColor(active ? appearance.accent : appearance.muted);
            chip.setBackground(activity.shape(active ? appearance.tint : appearance.surface, 12, appearance.border));
        }
        List<ScrapeEngine.LibraryMovie> visible = new ArrayList<>();
        for (ScrapeEngine.LibraryMovie movie : library) {
            if (selectedGenre == null || movie.genres().stream().anyMatch(selectedGenre::equalsIgnoreCase)) visible.add(movie);
        }
        adapter.visible = visible;
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
        status.setText(visible.size() + " 部影片");
    }

    private void showMovie(ScrapeEngine.LibraryMovie movie) {
        if (movieDetails != null || closed) return;
        LinearLayout body = activity.vertical();
        body.setPadding(activity.dp(22), activity.dp(12), activity.dp(22), activity.dp(16));
        TextView message = activity.text("正在读取 NFO…", 14, appearance.muted, false);
        activity.add(body, message, 0);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(movie.title())
                .setView(scroll).setNegativeButton("关闭", null).create();
        movieDetails = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (detailRead != null) detailRead.interrupt();
            detailRead = null;
            movieDetails = null;
        });
        dialog.show();
        detailRead = new Thread(() -> {
            try {
                byte[] bytes = MovieStorage.read(activity.getContentResolver(), movie.nfo(), ScrapeEngine.NFO_READ_LIMIT);
                Element nfo = KodiNfoPreservation.readMovie(bytes);
                activity.runOnUiThread(() -> {
                    if (closed || movieDetails != dialog || !dialog.isShowing()) return;
                    new PosterMovieDetails(activity, body).render(nfo, movie, source -> {
                        stopTrailer();
                        trailerDialog = TrailerPlayer.show(activity, source);
                    });
                });
            } catch (IOException | RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) return;
                DebugLog.failure("poster-details", failure);
                activity.runOnUiThread(() -> {
                    if (movieDetails == dialog && dialog.isShowing()) message.setText("无法读取这部电影的 NFO。");
                });
            }
        }, "PosterDetails");
        detailRead.start();
    }

    void stopTrailer() {
        if (trailerDialog != null) trailerDialog.dismiss();
        trailerDialog = null;
    }

    @Override public void close() {
        closed = true;
        if (scan != null) scan.interrupt();
        if (detailRead != null) detailRead.interrupt();
        stopTrailer();
        if (movieDetails != null) movieDetails.dismiss();
        posters.close();
    }

    private final class PosterAdapter extends BaseAdapter {
        private List<ScrapeEngine.LibraryMovie> visible = Collections.emptyList();
        @Override public int getCount() { return visible.size(); }
        @Override public ScrapeEngine.LibraryMovie getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            PosterCard card = recycled instanceof PosterCard current ? current : new PosterCard();
            ScrapeEngine.LibraryMovie movie = getItem(position);
            card.setContentDescription(movie.year() > 0 ? movie.title() + " · " + movie.year() : movie.title());
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

        PosterCard() {
            super(activity);
            setOrientation(VERTICAL);
            setBackground(activity.shape(appearance.surface, 12, appearance.border));
            setClipToOutline(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
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
        }
    }
}
