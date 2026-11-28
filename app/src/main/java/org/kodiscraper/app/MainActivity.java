package org.kodiscraper.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final int TMDB_BATCH_CONCURRENCY = 3;
    private static final int PICK_FOLDER = 1;
    private static final int PICK_COLLECTION_FOLDER = 2;
    private static final int EXPORT_DEBUG_LOG = 3;
    private static final int DIRECTORY_ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final Uri MOVIES_LOCATION = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Movies");
    private static final String STATE_PAGE = "navigation_page";
    private static final String[] LANGUAGES = {"zh-CN", "zh-TW", "en-US", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES"};
    private static final String[] LANGUAGE_NAMES = {"简体中文", "繁體中文", "English", "日本語", "한국어", "Français", "Deutsch", "Español"};
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final Pattern SECRET_PARAMETER = Pattern.compile(
            "(?i)([?&](?:api[_-]?key|(?:access[_-]?)?token|authorization)=)[^&#\\s]+");
    private static final Pattern BEARER_VALUE = Pattern.compile("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+");

    private final Set<String> needsReview = new HashSet<>();
    private volatile boolean stopped;
    private volatile Thread worker;
    private MovieMetadataSource activeClient;
    private boolean busy;
    private boolean apiVerified;
    private Uri folder;
    private Uri collectionFolder;
    private SharedPreferences settings;
    private AppAppearance appearance;
    private AppAppearance.Mode appearanceMode;
    private List<MovieStorage.Movie> movies = Collections.emptyList();
    private FrameLayout frame;
    private FrameLayout pageHost;
    private LinearLayout scraperPage;
    private LinearLayout subtitlePage;
    private LinearLayout debugPage;
    private LinearLayout scraperColumn;
    private LinearLayout subtitleColumn;
    private ScrollView subtitleScroll;
    private Button pickSubtitleFolder;
    private TextView subtitleFolder;
    private PosterWallScreen posterWall;
    private boolean posterRefreshPending;
    private FrameLayout navigationOverlay;
    private View navigationScrim;
    private ScrollView navigationPanel;
    private boolean navigationOpen;
    private final EnumMap<Page, Button> navigationItems = new EnumMap<>(Page.class);
    private View navigationReturnFocus;
    private Runnable pendingScraperDialog;
    private OnBackInvokedCallback navigationBackCallback;
    private MoviePushScreen moviePush;
    private MoviePushScreen.State retainedMoviePush;
    private View moviePushPage;
    private Page page;
    private ScrollView scroll;
    private TextView folderName;
    private TextView collectionFolderName;
    private TextView apiState;
    private TextView diagnosticReport;
    private RunPanel scraperLog;
    private RunPanel subtitleLog;
    private RunPanel activeRunPanel;
    private Button pickFolder;
    private Button organizeMovies;
    private Button pickCollectionFolder;
    private Button clearCollectionFolder;
    private Button validate;
    private Button start;
    private Button manual;
    private Button showCredential;
    private Button subtitleSettings;
    private Button subtitlesOnly;
    private Button testAssrt;
    private EditText credential;
    private Spinner language;
    private Switch overwrite;
    private Switch actors;
    private Switch preferFolder;
    private Switch alternateFanart;
    private Switch autoSubtitles;
    private Switch skipExistingSubtitles;
    private String savedCredential = "";
    private String savedTvdbKey = "";
    private String savedTvdbPin = "";
    private boolean tvdbSupplement;
    private MovieMetadataSource.Provider metadataProvider;
    private Button metadataSourceButton;
    private LinearLayout tvdbOptions;
    private String savedAssrtToken = "";
    private String savedSubdlKey = "";

    private enum Page {
        HOME("电影海报墙", UiIcon.Kind.LIBRARY), SCRAPER("电影刮削器", UiIcon.Kind.FILM),
        SUBTITLES("字幕下载器", UiIcon.Kind.SUBTITLES), PUSH("电影推送", UiIcon.Kind.RECOMMEND),
        DEBUG("调试日志", UiIcon.Kind.LOG);

        final String label;
        final UiIcon.Kind icon;

        Page(String label, UiIcon.Kind icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppAppearance.wrap(base));
    }

    @Override
    public void onCreate(Bundle state) {
        setTheme(R.style.AppTheme);
        super.onCreate(state);
        settings = getSharedPreferences("scraper", MODE_PRIVATE);
        DebugLog.configure(this, settings.getBoolean("debug_log", false));
        DebugLog.event("app", "opened version=1.17.0 android_api=" + Build.VERSION.SDK_INT);
        appearance = AppAppearance.from(this);
        appearanceMode = AppAppearance.read(settings);
        savedCredential = CredentialStore.read(settings, "tmdb");
        savedTvdbKey = CredentialStore.read(settings, "tvdb");
        savedTvdbPin = CredentialStore.read(settings, "tvdb_pin");
        tvdbSupplement = settings.getBoolean("tvdb_supplement", false);
        metadataProvider = "tvdb".equals(settings.getString("metadata_source", "tmdb"))
                ? MovieMetadataSource.Provider.TVDB : MovieMetadataSource.Provider.TMDB;
        savedAssrtToken = CredentialStore.read(settings, "assrt");
        savedSubdlKey = CredentialStore.read(settings, "subdl");
        String savedFolder = settings.getString("folder", "");
        if (!savedFolder.isEmpty()) {
            folder = Uri.parse(savedFolder);
        }
        String savedCollectionFolder = settings.getString("collection_folder", "");
        if (!savedCollectionFolder.isEmpty()) {
            collectionFolder = Uri.parse(savedCollectionFolder);
        }
        RetainedUi retained = getLastNonConfigurationInstance() instanceof RetainedUi value ? value : null;
        if (retained != null) {
            needsReview.addAll(retained.needsReview());
            movies = retained.movies();
            apiVerified = retained.apiVerified();
            retainedMoviePush = retained.moviePush();
        }
        configureWindow();
        buildScreen();
        if (retained != null) {
            scraperLog.state().setText(retained.scraperStatus());
            subtitleLog.state().setText(retained.subtitleStatus());
            diagnosticReport.setText(retained.report());
            diagnosticReport.setVisibility(retained.reportVisible() ? View.VISIBLE : View.GONE);
            scroll.post(() -> scroll.scrollTo(0, retained.scraperScroll()));
        }
        showPage(state == null ? Page.HOME : Page.valueOf(state.getString(STATE_PAGE, Page.HOME.name())));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLog(scraperLog, scraperLog.history().currentTask());
        updateLog(subtitleLog, subtitleLog.history().currentTask());
        refreshPosters();
    }

    AppAppearance appearance() {
        return appearance;
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        MoviePushScreen.State pushState = moviePush == null ? retainedMoviePush : moviePush.saveState();
        String scraperStatus = busy && activeRunPanel == scraperLog ? "任务已中断" : scraperLog.state().getText().toString();
        String subtitleStatus = busy && activeRunPanel == subtitleLog ? "任务已中断" : subtitleLog.state().getText().toString();
        return new RetainedUi(new HashSet<>(needsReview), new ArrayList<>(movies),
                apiVerified, scraperStatus, subtitleStatus, diagnosticReport.getText().toString(),
                diagnosticReport.getVisibility() == View.VISIBLE, scroll.getScrollY(), pushState);
    }

    private record RetainedUi(Set<String> needsReview, List<MovieStorage.Movie> movies,
                              boolean apiVerified, String scraperStatus, String subtitleStatus, String report, boolean reportVisible,
                              int scraperScroll, MoviePushScreen.State moviePush) {}

    private void configureWindow() {
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
    }

    private void buildScreen() {
        frame = new FrameLayout(this);
        frame.setBackgroundColor(appearance.background);
        frame.setFocusableInTouchMode(true);
        pageHost = new FrameLayout(this);
        frame.addView(pageHost, new FrameLayout.LayoutParams(-1, -1));
        posterWall = new PosterWallScreen(this, this::refreshPosters, () -> {
            if (!busy) chooseFolder();
        }, () -> {
            if (busy) return;
            showPage(Page.SCRAPER);
            previewMovieOrganization();
        });
        pageHost.addView(posterWall.view(), viewport());
        scraperPage = vertical();
        pageHost.addView(scraperPage, viewport());
        scraperPage.addView(createToolbar(Page.SCRAPER.label, null), new LinearLayout.LayoutParams(-1, -2));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scraperPage.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                WindowInsetsController controller = view.getWindowInsetsController();
                if (controller != null) {
                    int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    controller.setSystemBarsAppearance(appearance.dark ? 0 : lightBars, lightBars);
                }
            } else {
                view.setPadding(0, 0, 0, 0);
            }
            return insets;
        });
        scraperColumn = scrollColumn(scroll);
        scraperLog = addLog(scraperColumn, "刮削记录", new ScrapeHistory(this));
        LinearLayout scraperSettings = card(scraperColumn);
        addDirectory(settingsSection(scraperSettings, false));
        addConnection(settingsSection(scraperSettings, true));
        addOptions(settingsSection(scraperSettings, true));
        addScraperActions();
        subtitlePage = vertical();
        subtitlePage.addView(createToolbar(Page.SUBTITLES.label, null));
        subtitleScroll = new ScrollView(this);
        subtitlePage.addView(subtitleScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        subtitleColumn = scrollColumn(subtitleScroll);
        subtitleLog = addLog(subtitleColumn, "字幕下载记录", new ScrapeHistory(this, "subtitle_history"));
        LinearLayout subtitleConfig = card(subtitleColumn);
        addSubtitleDirectory(settingsSection(subtitleConfig, false));
        addSubtitleSources(settingsSection(subtitleConfig, true));
        addSubtitles(settingsSection(subtitleConfig, true));
        subtitlesOnly = primaryAction("一键下载字幕", this::runSubtitleBatch);
        addFooter(subtitlePage, subtitlesOnly);
        pageHost.addView(subtitlePage, viewport());
        debugPage = buildDebugPage();
        pageHost.addView(debugPage, viewport());
        buildNavigation();
        pageHost.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop,
                oldRight, oldBottom) -> resizeContent(right - left));
        setContentView(frame);
        frame.requestFocus();
        updateFolderLabel();
        updateCollectionFolderLabel();
        updateUi();
    }

    private LinearLayout scrollColumn(ScrollView parent) {
        parent.setOverScrollMode(View.OVER_SCROLL_NEVER);
        parent.setVerticalScrollBarEnabled(false);
        LinearLayout column = vertical();
        column.setPadding(dp(16), dp(2), dp(16), dp(18));
        parent.addView(column, new ScrollView.LayoutParams(-1, -2));
        return column;
    }

    private LinearLayout buildDebugPage() {
        LinearLayout screen = vertical();
        screen.addView(createToolbar(Page.DEBUG.label, null));
        ScrollView scroller = new ScrollView(this);
        screen.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout panel = card(scrollColumn(scroller));
        Switch recording = option("生成调试日志", settings.getBoolean("debug_log", false));
        recording.setOnCheckedChangeListener((view, enabled) -> {
            if (!enabled) DebugLog.event("app", "recording stopped");
            settings.edit().putBoolean("debug_log", enabled).apply();
            DebugLog.configure(this, enabled);
            if (enabled) DebugLog.event("app", "recording started version=1.17.0 android_api=" + Build.VERSION.SDK_INT);
        });
        add(panel, recording, 0);
        add(panel, text("记录运行阶段、网络状态和错误位置。最近约 2 MB 日志保存在本机。",
                13, appearance.muted, false), 4);
        Button export = button("导出 .log 文件", appearance.tint, appearance.accent);
        export.setMinHeight(dp(48));
        export.setOnClickListener(view -> chooseDebugExport());
        add(panel, export, 12);
        return screen;
    }

    private void chooseDebugExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "NFOForAndroid-debug-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)) + ".log");
        try {
            startActivityForResult(intent, EXPORT_DEBUG_LOG);
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, "无法打开系统文件保存器", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportDebugLog(Uri location) {
        new Thread(() -> {
            String message;
            try {
                byte[] logs = DebugLog.snapshot();
                try (OutputStream output = getContentResolver().openOutputStream(location, "wt")) {
                    if (output == null) throw new IOException("输出文件无法打开");
                    output.write(logs.length == 0 ? "暂无调试记录。\n".getBytes(java.nio.charset.StandardCharsets.UTF_8) : logs);
                }
                message = "已导出调试日志";
            } catch (IOException | RuntimeException problem) {
                DebugLog.failure("export", problem);
                message = "调试日志导出失败";
            }
            String outcome = message;
            post(() -> Toast.makeText(this, outcome, Toast.LENGTH_SHORT).show());
        }, "DebugLogExport").start();
    }

    private void refreshPosters() {
        posterRefreshPending = busy;
        if (!busy) posterWall.refresh(folder, preferFolder.isChecked());
    }

    private FrameLayout.LayoutParams viewport() {
        int available = pageHost.getWidth();
        int width = available == 0 ? -1 : Math.min(available, dp(660));
        return new FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL);
    }

    private void resizeContent(int width) {
        if (width <= 0) {
            return;
        }
        int pageWidth = Math.min(width, dp(660));
        resizeWidth(scraperPage, pageWidth);
        resizeWidth(subtitlePage, pageWidth);
        resizeWidth(posterWall.view(), pageWidth);
        resizeWidth(debugPage, pageWidth);
        resizeWidth(moviePushPage, pageWidth);
        resizeWidth(navigationPanel, Math.min(dp(310), Math.max(0, width - dp(64))));
    }

    private static void resizeWidth(View view, int width) {
        if (view != null && view.getLayoutParams().width != width) {
            android.view.ViewGroup.LayoutParams layout = view.getLayoutParams();
            layout.width = width;
            view.setLayoutParams(layout);
        }
    }

    private void buildNavigation() {
        navigationOverlay = new FrameLayout(this);
        navigationOverlay.setVisibility(View.GONE);
        navigationScrim = new View(this);
        navigationScrim.setBackgroundColor(appearance.scrim);
        navigationScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        navigationScrim.setFocusable(false);
        navigationScrim.setOnClickListener(view -> closeNavigation(true));
        navigationOverlay.addView(navigationScrim, new FrameLayout.LayoutParams(-1, -1));

        navigationPanel = new ScrollView(this);
        navigationPanel.setFillViewport(true);
        navigationPanel.setOverScrollMode(View.OVER_SCROLL_NEVER);
        navigationPanel.setVerticalScrollBarEnabled(false);
        GradientDrawable drawerSurface = shape(appearance.surface, 0, 0);
        float radius = dp(22);
        drawerSurface.setCornerRadii(new float[]{0, 0, radius, radius, radius, radius, 0, 0});
        navigationPanel.setBackground(drawerSurface);
        navigationPanel.setClipToOutline(true);
        navigationPanel.setElevation(dp(12));
        navigationPanel.setClickable(true);
        if (Build.VERSION.SDK_INT >= 28) {
            navigationPanel.setAccessibilityPaneTitle("导航菜单");
        }
        FrameLayout.LayoutParams drawer = new FrameLayout.LayoutParams(dp(310), -1, Gravity.LEFT);
        navigationOverlay.addView(navigationPanel, drawer);
        LinearLayout content = vertical();
        content.setPadding(dp(16), dp(14), dp(16), dp(18));
        navigationPanel.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout heading = horizontal();
        heading.setPadding(dp(3), 0, 0, 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView name = text("NFO for Android", 18, appearance.foreground, true);
        LinearLayout.LayoutParams brandSpace = new LinearLayout.LayoutParams(0, -2, 1f);
        brandSpace.setMarginStart(dp(8));
        heading.addView(name, brandSpace);
        Button dismiss = iconButton(UiIcon.Kind.CLOSE, "关闭导航菜单");
        dismiss.setOnClickListener(view -> closeNavigation(true));
        heading.addView(dismiss, new LinearLayout.LayoutParams(dp(48), dp(48)));
        add(content, heading, 0);

        for (Page item : Page.values()) {
            navigationItems.put(item, navigationItem(content, item, item == Page.HOME ? 20 : 5));
        }
        add(content, drawerAction("外观 · " + appearanceMode.label, UiIcon.Kind.APPEARANCE,
                this::showAppearanceDialog), 14);
        add(content, drawerAction("关于", UiIcon.Kind.INFO, this::showAbout), 5);
        frame.addView(navigationOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private Button drawerAction(String label, UiIcon.Kind icon, Runnable action) {
        Button entry = button(label, appearance.surface, appearance.foreground);
        entry.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        entry.setPaddingRelative(dp(14), dp(7), dp(14), dp(7));
        addButtonIcon(entry, icon, appearance.muted);
        entry.setMinHeight(dp(48));
        entry.setOnClickListener(view -> action.run());
        return entry;
    }

    private void showAppearanceDialog() {
        if (busy || (moviePush != null && moviePush.isBusy()) || pendingScraperDialog != null) {
            Toast.makeText(this, "当前操作完成后可切换外观", Toast.LENGTH_SHORT).show();
            return;
        }
        AppAppearance.Mode[] modes = AppAppearance.Mode.values();
        String[] labels = new String[modes.length];
        for (int index = 0; index < modes.length; index++) labels[index] = modes[index].label;
        new AlertDialog.Builder(this).setTitle("外观")
                .setSingleChoiceItems(labels, appearanceMode.ordinal(), (dialog, position) -> {
                    dialog.dismiss();
                    if (modes[position] != appearanceMode) {
                        AppAppearance.save(settings, modes[position]);
                        recreate();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private Button navigationItem(LinearLayout parent, Page destination, int top) {
        Button item = button(destination.label, appearance.surface, appearance.foreground);
        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        item.setPaddingRelative(dp(14), dp(6), dp(14), dp(6));
        addButtonIcon(item, destination.icon, appearance.muted);
        item.setMinHeight(dp(51));
        item.setTextSize(16);
        item.setOnClickListener(view -> showPage(destination));
        add(parent, item, top);
        return item;
    }

    private void openNavigation() {
        if (navigationOpen) return;
        navigationOpen = true;
        navigationPanel.animate().cancel();
        navigationScrim.animate().cancel();
        if (navigationOverlay.getVisibility() != View.VISIBLE) {
            navigationReturnFocus = getCurrentFocus();
            navigationPanel.setTranslationX(navigationOffset());
            navigationScrim.setAlpha(0f);
        }
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(frame.getWindowToken(), 0);
        }
        navigationItems.forEach((destination, item) -> updateNavigationItem(item, destination == page));
        pageHost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        navigationOverlay.setVisibility(View.VISIBLE);
        if (UiMotion.enabled()) {
            navigationScrim.animate().alpha(1f).setDuration(180).setInterpolator(UiMotion.EASE_OUT).start();
            navigationPanel.animate().translationX(0f).setDuration(240).setInterpolator(UiMotion.EASE_OUT).start();
        } else {
            navigationScrim.setAlpha(1f);
            navigationPanel.setTranslationX(0f);
        }
        navigationItems.get(page).requestFocus();
        updateNavigationBack();
    }

    private float navigationOffset() {
        return -(navigationPanel.getLayoutParams().width + dp(16));
    }

    private void updateNavigationItem(Button item, boolean selected) {
        item.setSelected(selected);
        item.setTextColor(selected ? appearance.accent : appearance.foreground);
        item.getCompoundDrawablesRelative()[0].setTint(selected ? appearance.accent : appearance.muted);
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(appearance.ripple),
                shape(selected ? appearance.tint : appearance.surface, 15, 0), null));
    }

    private void closeNavigation(boolean restoreFocus) {
        if (navigationOpen) {
            navigationOpen = false;
            navigationScrim.animate().cancel();
            navigationPanel.animate().cancel();
            if (UiMotion.enabled() && navigationPanel.isLaidOut()) {
                navigationScrim.animate().alpha(0f).setDuration(170).setInterpolator(UiMotion.EASE_IN).start();
                navigationPanel.animate().translationX(navigationOffset()).setDuration(170)
                        .setInterpolator(UiMotion.EASE_IN).withEndAction(() -> finishClosingNavigation(restoreFocus)).start();
            } else {
                finishClosingNavigation(restoreFocus);
            }
        }
        updateNavigationBack();
    }

    private void finishClosingNavigation(boolean restoreFocus) {
        if (navigationOpen) return;
        navigationOverlay.setVisibility(View.GONE);
        pageHost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (restoreFocus && navigationReturnFocus != null && navigationReturnFocus.isShown()) {
            navigationReturnFocus.requestFocus();
        }
        navigationReturnFocus = null;
        if (pendingScraperDialog != null && page == Page.SCRAPER) {
            frame.post(this::showPendingScraperDialog);
        }
    }

    private void showPage(Page destination) {
        if (page == destination) {
            closeNavigation(true);
            return;
        }
        if (destination == Page.PUSH && moviePush == null) {
            moviePush = new MoviePushScreen(this);
            moviePushPage = moviePush.view();
            pageHost.addView(moviePushPage, viewport());
            if (retainedMoviePush != null) {
                moviePush.restoreState(retainedMoviePush);
                retainedMoviePush = null;
            }
        }
        boolean animate = page != null;
        page = destination;
        DebugLog.event("navigation", "page=" + page.name());
        for (Page entry : Page.values()) {
            View view = pageView(entry);
            if (view == null) continue;
            UiMotion.reset(view);
            view.setVisibility(entry == destination ? View.VISIBLE : View.GONE);
        }
        closeNavigation(false);
        frame.requestFocus();
        if (animate) UiMotion.reveal(pageView(destination));
        if (page == Page.PUSH) {
            moviePush.onShown();
        }
    }

    private View pageView(Page item) {
        return switch (item) {
            case HOME -> posterWall.view();
            case SCRAPER -> scraperPage;
            case SUBTITLES -> subtitlePage;
            case PUSH -> moviePushPage;
            case DEBUG -> debugPage;
        };
    }

    private void showScraperDialog(Runnable dialog) {
        pendingScraperDialog = dialog;
        showPendingScraperDialog();
    }

    private void showPendingScraperDialog() {
        if (pendingScraperDialog != null && page == Page.SCRAPER
                && navigationOverlay.getVisibility() != View.VISIBLE && !isFinishing() && !isDestroyed()) {
            Runnable dialog = pendingScraperDialog;
            pendingScraperDialog = null;
            dialog.run();
        }
    }

    private boolean handleNavigationBack() {
        if (navigationOpen) {
            closeNavigation(true);
            return true;
        }
        if (page != Page.HOME) {
            showPage(Page.HOME);
            return true;
        }
        return false;
    }

    private void updateNavigationBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean intercept = page != Page.HOME || navigationOpen;
            if (intercept && navigationBackCallback == null) {
                navigationBackCallback = this::handleNavigationBack;
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, navigationBackCallback);
            } else if (!intercept && navigationBackCallback != null) {
                removeNavigationBackCallback();
            }
        }
    }

    private void removeNavigationBackCallback() {
        if (Build.VERSION.SDK_INT >= 33 && navigationBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(navigationBackCallback);
            navigationBackCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (!handleNavigationBack()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(STATE_PAGE, page.name());
    }

    LinearLayout createToolbar(String label, View trailing) {
        LinearLayout toolbar = horizontal();
        toolbar.setPaddingRelative(dp(16), dp(8), dp(16), dp(10));
        Button menu = iconButton(UiIcon.Kind.MENU, "打开导航菜单");
        menu.setOnClickListener(view -> openNavigation());
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text(label, 24, appearance.foreground, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1f);
        titleSpace.setMarginStart(dp(12));
        titleSpace.setMarginEnd(dp(6));
        toolbar.addView(title, titleSpace);
        if (trailing != null) {
            trailing.setMinimumWidth(dp(48));
            toolbar.addView(trailing, new LinearLayout.LayoutParams(-2, dp(48)));
        }
        return toolbar;
    }

    private Button iconButton(UiIcon.Kind kind, String description) {
        Button result = button("", appearance.tint, appearance.accent);
        result.setContentDescription(description);
        result.setPaddingRelative(dp(12), 0, dp(12), 0);
        addButtonIcon(result, kind, appearance.accent);
        result.setCompoundDrawablePadding(0);
        return result;
    }

    private void addButtonIcon(Button button, UiIcon.Kind kind, int color) {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(new UiIcon(kind, dp(24), color), null, null, null);
        button.setCompoundDrawablePadding(dp(12));
    }

    private void addDirectory(LinearLayout card) {
        addSettingsHeading(card, "电影搜索路径");
        folderName = text("", 14, appearance.muted, false);
        folderName.setMaxLines(3);
        folderName.setTextIsSelectable(true);
        add(card, folderName, 7);
        pickFolder = button("设置搜索路径", appearance.tint, appearance.accent);
        pickFolder.setOnClickListener(view -> chooseFolder());
        pickFolder.setMinHeight(dp(48));
        add(card, pickFolder, 11);
    }

    private void addConnection(LinearLayout card) {
        LinearLayout selection = horizontal();
        selection.addView(settingsHeading("影片资料来源"), new LinearLayout.LayoutParams(0, -2, 1f));
        metadataSourceButton = button(metadataProvider.label, appearance.tint, appearance.accent);
        metadataSourceButton.setContentDescription("选择影片资料来源 · " + metadataProvider.label);
        metadataSourceButton.setMinHeight(dp(48));
        metadataSourceButton.setOnClickListener(view -> selectMetadataSource());
        selection.addView(metadataSourceButton);
        add(card, selection, 0);
        tvdbOptions = vertical();
        LinearLayout tvdbButtons = horizontal();
        Button setupTvdb = button("配置 TheTVDB", appearance.tint, appearance.accent);
        setupTvdb.setOnClickListener(view -> configureTvdb());
        tvdbButtons.addView(setupTvdb);
        Button testTvdb = button("测试连接", appearance.tint, appearance.accent);
        testTvdb.setContentDescription("测试 TheTVDB 连接");
        testTvdb.setOnClickListener(view -> testTvdb());
        LinearLayout.LayoutParams testSpace = new LinearLayout.LayoutParams(-2, dp(48));
        testSpace.leftMargin = dp(8);
        tvdbButtons.addView(testTvdb, testSpace);
        add(tvdbOptions, tvdbButtons, 4);
        tvdbOptions.setVisibility(metadataProvider == MovieMetadataSource.Provider.TVDB ? View.VISIBLE : View.GONE);
        add(card, tvdbOptions, 0);
        LinearLayout heading = horizontal();
        heading.addView(text("TMDB 连接", 14, appearance.foreground, true), new LinearLayout.LayoutParams(0, -2, 1f));
        apiState = text("", 12, appearance.muted, true);
        heading.addView(apiState);
        add(card, heading, 12);
        LinearLayout input = horizontal();
        credential = field("API Key 或 Read Access Token",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        credential.setSingleLine(true);
        credential.setTransformationMethod(PasswordTransformationMethod.getInstance());
        credential.setSaveEnabled(false);
        credential.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        credential.setText(savedCredential);
        credential.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                apiVerified = false;
                if (diagnosticReport != null) {
                    diagnosticReport.setVisibility(View.GONE);
                }
                updateUi();
            }

            @Override
            public void afterTextChanged(Editable editable) {}
        });
        credential.setOnFocusChangeListener((view, focused) -> {
            if (!focused) {
                saveCredential();
            }
        });
        input.addView(credential, new LinearLayout.LayoutParams(0, dp(48), 1f));
        showCredential = button("显示", appearance.tint, appearance.accent);
        showCredential.setMinHeight(dp(48));
        showCredential.setMinimumWidth(dp(58));
        showCredential.setOnClickListener(view -> {
            boolean concealed = credential.getTransformationMethod() instanceof PasswordTransformationMethod;
            credential.setTransformationMethod(concealed ? null : PasswordTransformationMethod.getInstance());
            credential.setSelection(credential.length());
            showCredential.setText(concealed ? "隐藏" : "显示");
        });
        LinearLayout.LayoutParams showPlacement = new LinearLayout.LayoutParams(-2, -2);
        showPlacement.leftMargin = dp(7);
        input.addView(showCredential, showPlacement);
        add(card, input, 11);
        validate = button("测试 TMDB 连接", appearance.tint, appearance.accent);
        validate.setOnClickListener(view -> validateApi());
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-2, dp(44));
        placement.topMargin = dp(10);
        card.addView(validate, placement);
        diagnosticReport = text("", 13, appearance.foreground, false);
        diagnosticReport.setTextIsSelectable(true);
        diagnosticReport.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        diagnosticReport.setLineSpacing(dp(3), 1f);
        diagnosticReport.setVisibility(View.GONE);
        add(card, diagnosticReport, 8);
    }

    private void selectMetadataSource() {
        if (busy) return;
        MovieMetadataSource.Provider[] choices = MovieMetadataSource.Provider.values();
        String[] labels = {choices[0].label, choices[1].label};
        new AlertDialog.Builder(this).setTitle("影片资料来源")
                .setSingleChoiceItems(labels, metadataProvider.ordinal(), (dialog, index) -> {
                    metadataProvider = choices[index];
                    settings.edit().putString("metadata_source", metadataProvider.idType).apply();
                    metadataSourceButton.setText(metadataProvider.label);
                    metadataSourceButton.setContentDescription("选择影片资料来源 · " + metadataProvider.label);
                    tvdbOptions.setVisibility(index == MovieMetadataSource.Provider.TVDB.ordinal() ? View.VISIBLE : View.GONE);
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void configureTvdb() {
        if (busy) return;
        LinearLayout form = dialogContent();
        TextView help = text("请使用自己的 TheTVDB v4 API Key。订阅密钥还需要对应的 PIN。\n"
                + "https://www.thetvdb.com/dashboard/account/apikey", 13, appearance.muted, false);
        Linkify.addLinks(help, Linkify.WEB_URLS);
        add(form, help, 0);
        EditText key = field("TheTVDB API Key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSingleLine(true);
        key.setSaveEnabled(false);
        key.setText(savedTvdbKey);
        add(form, key, 10);
        EditText pin = field("订阅 PIN（如需）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pin.setSingleLine(true);
        pin.setSaveEnabled(false);
        pin.setText(savedTvdbPin);
        add(form, pin, 8);
        Switch supplement = option("使用已配置的 TMDB 补齐评分和合集", tvdbSupplement);
        add(form, supplement, 6);
        new AlertDialog.Builder(this).setTitle("配置 TheTVDB").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    savedTvdbKey = key.getText().toString().trim();
                    savedTvdbPin = pin.getText().toString().trim();
                    tvdbSupplement = supplement.isChecked();
                    settings.edit().putBoolean("tvdb_supplement", tvdbSupplement).apply();
                    boolean keyStored = CredentialStore.save(settings, "tvdb", savedTvdbKey);
                    boolean pinStored = CredentialStore.save(settings, "tvdb_pin", savedTvdbPin);
                    if (!keyStored || !pinStored) note("设备无法保存 TheTVDB 凭据，本次仍可使用。");
                }).show();
    }

    private void testTvdb() {
        if (busy) return;
        if (savedTvdbKey.isEmpty()) {
            configureTvdb();
            return;
        }
        TvdbClient client = new TvdbClient(savedTvdbKey, savedTvdbPin, language());
        begin(client, "正在检测 TheTVDB 连接…", () -> {
            client.validate();
            return () -> finishWithLog("TheTVDB API 已连通，凭据有效。");
        });
    }

    private void addSubtitleDirectory(LinearLayout panel) {
        addSettingsHeading(panel, "电影目录");
        subtitleFolder = text("", 13, appearance.muted, false);
        add(panel, subtitleFolder, 5);
        pickSubtitleFolder = button("设置搜索路径", appearance.tint, appearance.accent);
        pickSubtitleFolder.setMinHeight(dp(48));
        pickSubtitleFolder.setOnClickListener(view -> chooseFolder());
        add(panel, pickSubtitleFolder, 8);
    }

    private void addSubtitles(LinearLayout card) {
        addSettingsHeading(card, "下载设置");
        autoSubtitles = option("随一键刮削和手动匹配下载", settings.getBoolean("auto_subtitles", false));
        add(card, autoSubtitles, 4);
        skipExistingSubtitles = option("跳过已有外挂字幕的电影", settings.getBoolean("skip_existing_subtitles", true));
        skipExistingSubtitles.setOnCheckedChangeListener((view, enabled) ->
                settings.edit().putBoolean("skip_existing_subtitles", enabled).apply());
        add(card, skipExistingSubtitles, 0);
        add(card, text("字幕保存到影片的 Subs 文件夹。", 13, appearance.muted, false), 2);
    }

    private void addSubtitleSources(LinearLayout card) {
        addSettingsHeading(card, "字幕来源");
        LinearLayout buttons = horizontal();
        subtitleSettings = button("设置字幕来源", appearance.tint, appearance.accent);
        subtitleSettings.setOnClickListener(view -> showSubtitleSettings());
        subtitleSettings.setMinHeight(dp(48));
        buttons.addView(subtitleSettings, new LinearLayout.LayoutParams(0, -2, 1f));
        testAssrt = button("测试伪射手连接", appearance.tint, appearance.accent);
        testAssrt.setMinHeight(dp(48));
        testAssrt.setOnClickListener(view -> validateAssrt());
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(0, -2, 1f);
        placement.leftMargin = dp(8);
        buttons.addView(testAssrt, placement);
        add(card, buttons, 9);
    }

    private void showSubtitleSettings() {
        LinearLayout content = dialogContent();
        add(content, text("优先用射手影音按视频指纹匹配，无需密钥。填写下列任一凭据可增加搜索范围。",
                14, appearance.muted, false), 0);
        int passwordType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        EditText assrt = field("伪射手 API Token（选填）", passwordType);
        assrt.setText(savedAssrtToken);
        add(content, assrt, 9);
        EditText subdl = field("SubDL API Key（选填）", passwordType);
        subdl.setText(savedSubdlKey);
        add(content, subdl, 8);
        TextView sources = text("", 13, appearance.muted, false);
        sources.setAutoLinkMask(Linkify.WEB_URLS);
        sources.setText("字幕服务由 https://www.shooter.cn、https://assrt.net 和 https://subdl.com 提供。");
        add(content, sources, 10);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("字幕来源").setView(content)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String assrtToken = assrt.getText().toString().trim();
            String subdlKey = subdl.getText().toString().trim();
            if (!assrtToken.isEmpty() && !assrtToken.matches("[A-Za-z0-9]{32}")) {
                assrt.setError("请输入 32 位伪射手 API Token");
                assrt.requestFocus();
                return;
            }
            if (!subdlKey.isEmpty() && !subdlKey.matches("[!-~]{1,256}")) {
                subdl.setError("请输入有效的 SubDL API Key");
                subdl.requestFocus();
                return;
            }
            boolean storedAssrt = assrtToken.equals(savedAssrtToken)
                    || CredentialStore.save(settings, "assrt", assrtToken);
            boolean storedSubdl = subdlKey.equals(savedSubdlKey)
                    || CredentialStore.save(settings, "subdl", subdlKey);
            savedAssrtToken = assrtToken;
            savedSubdlKey = subdlKey;
            if (!storedAssrt || !storedSubdl) {
                note("设备无法保存字幕凭据，本次仍可使用。");
            }
            dialog.dismiss();
        });
    }

    private void addOptions(LinearLayout card) {
        addSettingsHeading(card, "刮削设置");
        LinearLayout row = horizontal();
        row.addView(text("资料语言", 14, appearance.foreground, false), new LinearLayout.LayoutParams(0, -2, 1f));
        language = new Spinner(this, Spinner.MODE_DROPDOWN);
        language.setContentDescription("选择资料语言");
        ArrayAdapter<String> choices = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, LANGUAGE_NAMES);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        language.setAdapter(choices);
        language.setSelection(Math.min(LANGUAGES.length - 1, Math.max(0, settings.getInt("language", 0))));
        row.addView(language, new LinearLayout.LayoutParams(dp(140), dp(44)));
        add(card, row, 5);
        preferFolder = option("优先按电影文件夹名匹配", settings.getBoolean("prefer_folder", true));
        preferFolder.setOnCheckedChangeListener((button, checked) -> {
            movies = Collections.emptyList();
            needsReview.clear();
            updateUi();
        });
        add(card, preferFolder, 0);
        actors = option("下载本地演员头像（最多 " + ScrapeEngine.ACTOR_LIMIT + " 张）", settings.getBoolean("actors", true));
        add(card, actors, 0);
        alternateFanart = option("额外保存备用背景图（最多 " + ScrapeEngine.ALTERNATE_FANART_LIMIT + " 张）",
                settings.getBoolean("alternate_fanart", false));
        add(card, alternateFanart, 0);
        overwrite = option("更新待刮削影片的已有资料", settings.getBoolean("overwrite", false));
        add(card, overwrite, 0);
        add(card, text("更新时保留 NFO 中的观看记录、续播点和个人评分。", 12, appearance.muted, false), 2);
        manual = button("手动匹配影片", appearance.tint, appearance.accent);
        manual.setMinHeight(dp(48));
        manual.setOnClickListener(view -> openManual());
        add(card, manual, 10);

        add(card, text("Kodi 合集资料目录", 14, appearance.foreground, true), 12);
        collectionFolderName = text("", 13, appearance.muted, false);
        collectionFolderName.setMaxLines(3);
        collectionFolderName.setTextIsSelectable(true);
        add(card, collectionFolderName, 4);
        LinearLayout folderButtons = horizontal();
        pickCollectionFolder = button("选择合集目录", appearance.tint, appearance.accent);
        pickCollectionFolder.setOnClickListener(view -> chooseDirectory(PICK_COLLECTION_FOLDER, collectionFolder));
        folderButtons.addView(pickCollectionFolder, new LinearLayout.LayoutParams(-2, dp(42)));
        clearCollectionFolder = button("取消选择", appearance.tint, appearance.accent);
        clearCollectionFolder.setOnClickListener(view -> clearCollectionFolder());
        LinearLayout.LayoutParams clearPlacement = new LinearLayout.LayoutParams(-2, dp(42));
        clearPlacement.leftMargin = dp(8);
        folderButtons.addView(clearCollectionFolder, clearPlacement);
        add(card, folderButtons, 8);
    }

    private void addScraperActions() {
        LinearLayout row = horizontal();
        row.setBaselineAligned(false);
        organizeMovies = button("整理与重命名", appearance.tint, appearance.accent);
        organizeMovies.setMinHeight(dp(52));
        organizeMovies.setOnClickListener(view -> previewMovieOrganization());
        row.addView(organizeMovies, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        start = primaryAction("一键刮削", this::runBatch);
        LinearLayout.LayoutParams primarySpace = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        primarySpace.setMarginStart(dp(8));
        row.addView(start, primarySpace);
        addFooter(scraperPage, row);
    }

    private Button primaryAction(String label, Runnable launch) {
        Button action = button(label, appearance.primary, appearance.onPrimary);
        action.setMinHeight(dp(52));
        action.setOnClickListener(view -> {
            if (busy) cancelWork();
            else launch.run();
        });
        return action;
    }

    private void addFooter(LinearLayout page, View actions) {
        LinearLayout.LayoutParams actionSpace = new LinearLayout.LayoutParams(-1, -2);
        actionSpace.setMargins(dp(16), dp(6), dp(16), dp(12));
        page.addView(actions, actionSpace);
    }

    private record RunPanel(ScrapeHistory history, TextView state, ProgressBar progress, ScrollView viewer, TextView entries) {}

    private RunPanel addLog(LinearLayout column, String title, ScrapeHistory history) {
        LinearLayout card = card(column);
        LinearLayout header = horizontal();
        header.addView(settingsHeading(title), new LinearLayout.LayoutParams(0, -2, 1));
        Button previous = button("历史日志", appearance.tint, appearance.accent);
        previous.setMinHeight(dp(48));
        previous.setOnClickListener(view -> showHistory(history));
        header.addView(previous);
        add(card, header, 0);
        TextView runState = text("等待开始", 14, appearance.muted, true);
        runState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(card, runState, 7);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(appearance.accent));
        progress.setIndeterminateTintList(ColorStateList.valueOf(appearance.accent));
        progress.setMax(100);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(8));
        bar.topMargin = dp(6);
        card.addView(progress, bar);
        progress.setVisibility(View.GONE);
        TextView log = text("", 15, appearance.foreground, false);
        log.setTextIsSelectable(true);
        log.setHorizontallyScrolling(false);
        log.setLineSpacing(dp(3), 1f);
        ScrollView viewer = new ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int limit = dp(144);
                if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                        || MeasureSpec.getSize(heightSpec) > limit) {
                    heightSpec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        viewer.setNestedScrollingEnabled(true);
        viewer.setOverScrollMode(View.OVER_SCROLL_NEVER);
        viewer.addView(log, new ScrollView.LayoutParams(-1, -2));
        viewer.setVisibility(View.GONE);
        add(card, viewer, 7);
        return new RunPanel(history, runState, progress, viewer, log);
    }

    private void showHistory(ScrapeHistory history) {
        List<ScrapeHistory.Entry> entries = history.newestFirst();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("历史日志")
                .setPositiveButton("关闭", null);
        if (entries.isEmpty()) {
            dialog.setMessage("暂无日志");
        } else {
            ScrollView historyScroll = new ScrollView(this);
            LinearLayout content = dialogContent();
            TextView lines = text("", 15, appearance.foreground, false);
            lines.setText(ScrapeLogText.render(entries, true, dp(14)));
            lines.setLineSpacing(dp(4), 1f);
            lines.setTextIsSelectable(true);
            lines.setHorizontallyScrolling(false);
            add(content, lines, 0);
            historyScroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            dialog.setView(historyScroll);
        }
        dialog.show();
    }

    private void chooseFolder() {
        chooseDirectory(PICK_FOLDER, folder == null ? MOVIES_LOCATION : folder);
    }

    private void previewMovieOrganization() {
        if (busy || !requireFolder()) return;
        Uri selected = folder;
        boolean byFolder = preferFolder.isChecked();
        begin(null, "正在查找可整理的电影…", () -> {
            MovieStorage storage = new MovieStorage(getApplicationContext(), selected);
            MovieOrganizer.Plan plan = MovieOrganizer.preview(storage, byFolder);
            return () -> {
                currentRunPanel().state().setText(plan.items().isEmpty() ? "没有可重命名的影片" : "重命名方案待确认");
                showScraperDialog(() -> showOrganizationPreview(selected, plan));
            };
        });
    }

    private void showOrganizationPreview(Uri selected, MovieOrganizer.Plan plan) {
        LinearLayout content = dialogContent();
        boolean hasChanges = !plan.items().isEmpty();
        add(content, text("可整理 " + plan.items().size() + " 部 · 跳过 " + plan.skipped().size() + " 项",
                15, appearance.foreground, true), 0);
        if (hasChanges) {
            add(content, text("关联的 Kodi 资料和字幕会随视频改名，其他文件保留。",
                    13, appearance.muted, false), 7);
        }
        StringBuilder paths = new StringBuilder();
        for (MovieOrganizer.Item item : plan.items()) {
            String sourceFolder = item.sourceFolder();
            int separator = sourceFolder.lastIndexOf('/');
            if (separator >= 0) {
                paths.append("所在目录 · ").append(sourceFolder, 0, separator).append('\n');
            }
            if (item.createsFolder()) {
                paths.append("根目录 · ").append(item.sourceVideo())
                        .append("\n新建文件夹 · ").append(item.targetFolder())
                        .append("\n移动至 · ").append(item.targetVideo()).append("\n\n");
            } else {
                paths.append("电影文件夹\n原名称 · ").append(pathName(sourceFolder))
                        .append("\n新名称 · ").append(pathName(item.targetFolder()))
                        .append("\n电影文件\n原名称 · ").append(pathName(item.sourceVideo()))
                        .append("\n新名称 · ").append(pathName(item.targetVideo())).append("\n\n");
            }
        }
        for (MovieOrganizer.Skip skip : plan.skipped()) {
            paths.append("跳过 · ").append(skip.path()).append("\n").append(skip.reason()).append("\n\n");
        }
        if (paths.length() > 0) {
            TextView entries = text(paths.toString().trim(), 13, appearance.muted, false);
            entries.setTextIsSelectable(true);
            add(content, entries, 12);
        }
        ScrollView preview = new ScrollView(this);
        preview.addView(content, new ScrollView.LayoutParams(-1, -2));
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("重命名预览").setView(preview);
        if (hasChanges) {
            dialog.setNegativeButton("取消", (view, which) -> currentRunPanel().state().setText("已取消重命名"));
            dialog.setOnCancelListener(view -> currentRunPanel().state().setText("已取消重命名"));
            dialog.setPositiveButton("确认重命名", (view, which) -> applyMovieOrganization(selected, plan));
        } else {
            dialog.setNegativeButton("关闭", null);
        }
        dialog.show();
    }

    private static String pathName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void applyMovieOrganization(Uri selected, MovieOrganizer.Plan plan) {
        if (busy || !selected.equals(folder)) return;
        movies = Collections.emptyList();
        needsReview.clear();
        beginLibraryTask(null, "正在整理电影文件…", () -> {
            MovieStorage storage = new MovieStorage(getApplicationContext(), selected);
            MovieOrganizer.Summary result = MovieOrganizer.apply(storage, plan, progress -> {
                MovieOrganizer.Item item = progress.item();
                post(() -> noteMovie(item.sourceVideo(), pathName(item.targetFolder()), progress.summary(), progress.detail()));
            });
            return () -> finishWithLog("重命名完成 · 已整理 " + result.renamed() + " 部 · 失败 " + result.failed() + " 部");
        });
    }

    private void chooseDirectory(int request, Uri initial) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(DIRECTORY_ACCESS
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (initial != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        }
        try {
            startActivityForResult(intent, request);
        } catch (RuntimeException unavailable) {
            note("这台设备未提供系统文件夹选择器。");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_DEBUG_LOG) {
            if (result == RESULT_OK && data != null && data.getData() != null) exportDebugLog(data.getData());
            return;
        }
        if ((request != PICK_FOLDER && request != PICK_COLLECTION_FOLDER)
                || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri selected = data.getData();
        int flags = data.getFlags() & DIRECTORY_ACCESS;
        if (flags != DIRECTORY_ACCESS) {
            note("请选择允许读取和写入的目录。");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(selected, flags);
        } catch (SecurityException problem) {
            note("无法保存该文件夹的访问权限，请选择其他目录。");
            return;
        }
        if (request == PICK_COLLECTION_FOLDER) {
            Uri previous = collectionFolder;
            collectionFolder = selected;
            settings.edit().putString("collection_folder", selected.toString()).apply();
            releaseUnusedDirectory(previous);
            updateCollectionFolderLabel();
            note("合集图片目录 · " + directoryName(selected));
            return;
        }
        Uri previous = folder;
        folder = selected;
        movies = Collections.emptyList();
        needsReview.clear();
        settings.edit().putString("folder", folder.toString()).apply();
        releaseUnusedDirectory(previous);
        updateFolderLabel();
        updateUi();
        note("已选择 " + directoryName(folder));
        DebugLog.event("storage", "movie tree changed");
        refreshPosters();
    }

    private void releaseUnusedDirectory(Uri previous) {
        if (previous != null && !previous.equals(folder) && !previous.equals(collectionFolder)) {
            try {
                getContentResolver().releasePersistableUriPermission(previous, DIRECTORY_ACCESS);
            } catch (SecurityException ignored) {
                // The previous directory grant may have been revoked.
            }
        }
    }

    private void clearCollectionFolder() {
        Uri previous = collectionFolder;
        collectionFolder = null;
        settings.edit().remove("collection_folder").apply();
        releaseUnusedDirectory(previous);
        updateCollectionFolderLabel();
    }

    private static String directoryName(Uri directory) {
        try {
            String document = DocumentsContract.getTreeDocumentId(directory);
            if (document.startsWith("primary:")) {
                return "内部存储 / " + document.substring("primary:".length());
            }
            if (document.startsWith("raw:")) {
                return document.substring("raw:".length());
            }
            return document.replace(':', '/');
        } catch (IllegalArgumentException ignored) {
            return "已授权目录";
        }
    }

    private void updateFolderLabel() {
        folderName.setText(folder == null ? "内部存储 / Movies（等待授权）" : directoryName(folder));
        pickFolder.setText(folder == null ? "设置搜索路径" : "更改搜索路径");
        subtitleFolder.setText(folderName.getText());
        pickSubtitleFolder.setText(pickFolder.getText());
    }

    private void updateCollectionFolderLabel() {
        collectionFolderName.setText(collectionFolder == null
                ? "可选。选择与 Kodi 相同的目录以补齐合集海报和背景图。"
                : directoryName(collectionFolder));
        pickCollectionFolder.setText(collectionFolder == null ? "选择合集目录" : "更改合集目录");
        clearCollectionFolder.setVisibility(collectionFolder == null ? View.GONE : View.VISIBLE);
    }

    private void validateApi() {
        saveCredential();
        TmdbClient client = new TmdbClient(credential.getText().toString().trim(), language());
        diagnosticReport.setText("正在检测 TMDB API、图片服务器和凭据…");
        diagnosticReport.setVisibility(View.VISIBLE);
        begin(client, "正在检测 TMDB 连接…", true, () -> {
            TmdbClient.Diagnostic result = client.diagnose();
            return () -> {
                apiVerified = result.credentials.state == TmdbClient.ProbeState.VALID;
                boolean apiReachable = result.api.state == TmdbClient.ProbeState.REACHABLE || apiVerified;
                boolean imagesReachable = result.imageCdn.state == TmdbClient.ProbeState.REACHABLE;
                diagnosticReport.setText("API · api.themoviedb.org\n" + probeText(result.api, true)
                        + "\n\n图片 · image.tmdb.org\n" + probeText(result.imageCdn, false)
                        + "\n\n凭据校验\n" + probeText(result.credentials, false));
                String summary = "TMDB API" + (apiReachable ? "已连通" : "未确认连通") + "，图片服务器"
                        + (imagesReachable ? "已连通。" : "未确认连通。");
                currentRunPanel().state().setText("TMDB 连接检测完成");
                note(summary);
                updateUi();
            };
        });
    }

    private void validateAssrt() {
        AssrtSubtitleSource source = new AssrtSubtitleSource(savedAssrtToken);
        begin(null, "正在检测伪射手连接…", () -> {
            AssrtSubtitleSource.Diagnostic diagnostic = source.diagnoseRoutes();
            return () -> {
                currentRunPanel().state().setText(diagnostic.reachable() ? "伪射手 API 已响应" : "未收到可识别的伪射手 API 响应");
                note(diagnostic.report());
            };
        });
    }

    private static String probeText(TmdbClient.Probe probe, boolean anonymous) {
        String description;
        switch (probe.state) {
            case REACHABLE:
                description = anonymous && probe.httpStatus == 401 ? "已连通（未携带凭据的请求）" : "已连通";
                break;
            case VALID: description = "有效"; break;
            case NOT_PROVIDED: return "未填写，已单独检测网络";
            case INVALID_FORMAT: return "格式无效，请检查输入";
            case AUTH_REJECTED:
                description = probe.httpStatus == 403 ? "服务器拒绝访问，请检查凭据或访问限制" : "服务器拒绝凭据";
                break;
            case DNS_ERROR: description = "域名解析失败"; break;
            case TIMEOUT: description = "请求超时"; break;
            case TLS_ERROR: description = "TLS 握手或证书校验失败"; break;
            case NETWORK_ERROR: description = "网络连接失败"; break;
            case RATE_LIMITED: description = "服务器限流"; break;
            case UNEXPECTED_RESPONSE: description = "返回内容异常，请检查代理或网络登录页"; break;
            default: description = "服务器返回错误";
        }
        return description + (probe.httpStatus > 0 ? " · HTTP " + probe.httpStatus : "")
                + " · " + probe.elapsedMillis + " ms";
    }

    private void runBatch() {
        if (!requireFolder()) {
            return;
        }
        MovieMetadataSource client = createMetadataClient();
        if (client.provider() == MovieMetadataSource.Provider.TMDB) saveCredential();
        Uri selectedFolder = folder;
        Uri selectedCollection = collectionFolder;
        String selectedLanguage = language();
        boolean replace = overwrite.isChecked();
        boolean downloadActors = actors.isChecked();
        boolean downloadAlternate = alternateFanart.isChecked();
        boolean byFolder = preferFolder.isChecked();
        String assrt = savedAssrtToken;
        String subdl = savedSubdlKey;
        Supplier<SubtitleScraper> subtitles = autoSubtitles.isChecked() ? () -> new SubtitleScraper(assrt, subdl) : null;
        boolean skipSubtitles = skipExistingSubtitles.isChecked();
        persistOptions();
        needsReview.clear();
        beginLibraryTask(client, "正在扫描目录… · " + client.provider().label, () -> {
            MovieStorage storage = new MovieStorage(getApplicationContext(), selectedFolder);
            List<MovieStorage.Movie> scanned = storage.scan(byFolder);
            post(() -> {
                movies = scanned;
                note("发现 " + scanned.size() + " 部影片。");
            });
            if (scanned.isEmpty()) {
                return () -> finishWithLog("没有发现可刮削的电影文件");
            }
            MovieStorage collections = selectedCollection == null ? null
                    : new MovieStorage(getApplicationContext(), selectedCollection);
            ScrapeEngine engine = new ScrapeEngine(storage, collections, client,
                    selectedLanguage, replace, downloadActors, downloadAlternate);
            List<MovieStorage.Movie> complete = new ArrayList<>();
            List<MovieStorage.Movie> pending = new ArrayList<>();
            for (int index = 0; index < scanned.size(); index++) {
                ScrapeEngine.checkCancelled();
                MovieStorage.Movie movie = scanned.get(index);
                int position = index + 1;
                post(() -> setProgress(position, scanned.size(), "检查本地 · " + movie.title));
                (engine.isCompleteLocally(movie) ? complete : pending).add(movie);
            }
            DebugLog.event("preflight", "scanned=" + scanned.size() + " complete=" + complete.size()
                    + " pending=" + pending.size());
            SubtitleBatch subtitleBatch = new SubtitleBatch();
            Set<MovieStorage.Movie> subtitlePending = subtitles == null ? Collections.emptySet()
                    : new HashSet<>(pendingSubtitles(storage, scanned, skipSubtitles, subtitleBatch, true));
            post(() -> {
                note("本地检查完成 · 资料齐全 " + complete.size() + " 部 · 待刮削 " + pending.size() + " 部");
                if (!stopped && !pending.isEmpty()) {
                    currentRunPanel().state().setText("正在连接 " + client.provider().label + "…");
                    currentRunPanel().progress().setIndeterminate(true);
                }
            });
            if (!pending.isEmpty()) {
                client.validate();
                post(() -> {
                    if (client.provider() == MovieMetadataSource.Provider.TMDB) apiVerified = true;
                    updateUi();
                });
            }
            Batch batch = new Batch();
            batch.skipped = complete.size();
            scrapePending(pending, storage, engine, client,
                    movie -> subtitlePending.contains(movie) ? subtitles.get() : null, batch, subtitleBatch);
            if (subtitles != null) {
                List<MovieStorage.Movie> subtitleOnly = new ArrayList<>(complete);
                subtitleOnly.removeIf(movie -> !subtitlePending.contains(movie));
                downloadPendingSubtitles(storage, subtitleOnly, subtitles.get(), subtitleBatch, true);
            }
            String summary = batch.summary(pending.size(), pending.size())
                    + (subtitles == null ? "" : "\n" + subtitleBatch.summary());
            return () -> finishWithLog(summary);
        });
    }

    private record FilmRun(String title, Batch metadata, SubtitleBatch subtitles) {}

    private static final class MovieTaskFailure extends IOException {
        final MovieStorage.Movie movie;
        final IOException failure;

        MovieTaskFailure(MovieStorage.Movie movie, IOException failure) {
            super(failure);
            this.movie = movie;
            this.failure = failure;
        }
    }

    private void scrapePending(List<MovieStorage.Movie> pending, MovieStorage storage, ScrapeEngine engine,
                               MovieMetadataSource client, Function<MovieStorage.Movie, SubtitleScraper> subtitles,
                               Batch batch, SubtitleBatch subtitleBatch) throws IOException {
        if (pending.isEmpty()) return;
        int width = Math.min(pending.size(), client.provider() == MovieMetadataSource.Provider.TMDB
                ? TMDB_BATCH_CONCURRENCY : 1);
        post(() -> note("开始刮削 " + pending.size() + " 部"));
        DebugLog.event("metadata", "queued=" + pending.size() + " workers=" + width);
        ExecutorService workers = Executors.newFixedThreadPool(width, job -> new Thread(job, "MovieBatch"));
        ExecutorCompletionService<FilmRun> queue = new ExecutorCompletionService<>(workers);
        int submitted = 0;
        int completed = 0;
        try {
            while (submitted < width) {
                int index = submitted++;
                queue.submit(() -> scrapeFilm(storage, engine, pending.get(index), index + 1, subtitles));
            }
            while (completed < pending.size()) {
                FilmRun run = queue.take().get();
                batch.add(run.metadata());
                subtitleBatch.add(run.subtitles());
                int count = ++completed;
                post(() -> setProgress(count, pending.size(), "已处理 · " + run.title()));
                ScrapeEngine.checkCancelled();
                if (submitted < pending.size()) {
                    int index = submitted++;
                    queue.submit(() -> scrapeFilm(storage, engine, pending.get(index), index + 1, subtitles));
                }
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("操作已停止");
        } catch (ExecutionException failure) {
            int count = completed;
            post(() -> note(batch.summary(count, pending.size())));
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("并行刮削失败", cause);
        } finally {
            workers.shutdownNow();
            if (completed < pending.size()) client.cancel();
            boolean interrupted = Thread.interrupted();
            while (!workers.isTerminated()) {
                try {
                    workers.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private FilmRun scrapeFilm(MovieStorage storage, ScrapeEngine engine, MovieStorage.Movie movie, int index,
                               Function<MovieStorage.Movie, SubtitleScraper> subtitles) throws IOException {
        Batch movieBatch = new Batch();
        movieBatch.started = index;
        SubtitleBatch subtitleBatch = new SubtitleBatch();
        try (SubtitleWork work = startSubtitles(subtitles.apply(movie), storage, movie, 0)) {
            SubtitleQuery match = scrapeBatchMovie(engine, movie, work, movieBatch);
            if (work != null) {
                work.matched(match == null ? ScrapeEngine.subtitleQuery(storage, movie) : match);
                recordSubtitles(movie, subtitleBatch, work::result);
            }
        }
        return new FilmRun(movie.title, movieBatch, subtitleBatch);
    }

    private SubtitleQuery scrapeBatchMovie(ScrapeEngine engine, MovieStorage.Movie movie, SubtitleWork work,
                                            Batch batch) throws IOException {
        try {
            ScrapeEngine.Outcome scraped = engine.scrape(movie, 0,
                    message -> post(() -> noteMovie(movie, "", message)),
                    work == null ? query -> {} : work::matched);
            batch.count(scraped.result);
            DebugLog.event("metadata", "item=" + batch.started + " outcome=" + scraped.result.name());
            post(() -> {
                if (scraped.result == ScrapeEngine.Result.REVIEW) needsReview.add(movie.relativePath);
                String status = "资料 · " + resultLabel(scraped.result);
                noteMovie(movie, status, status);
            });
            return scraped.subtitleQuery;
        } catch (IOException problem) {
            ScrapeEngine.checkCancelled();
            if (shouldStopBatch(problem)) {
                throw new MovieTaskFailure(movie, problem);
            }
            if (problem instanceof MovieMetadataSource.HttpError http && http.statusCode == 404) {
                batch.count(ScrapeEngine.Result.REVIEW);
                post(() -> {
                    needsReview.add(movie.relativePath);
                    noteMovie(movie, "资料 · 待确认", "未找到资料，请手动匹配");
                });
            } else {
                batch.failed++;
                post(() -> noteMovie(movie, "资料 · 刮削失败", "资料 · 刮削失败 · " + errorText(problem)));
            }
            return null;
        }
    }

    private static SubtitleWork startSubtitles(SubtitleScraper scraper, MovieStorage storage,
                                               MovieStorage.Movie movie, int selectedId) {
        return scraper == null ? null : new SubtitleWork(scraper, new SubtitleQuery(storage, movie, null, selectedId, null));
    }

    private SubtitleScraper subtitleScraper() {
        return new SubtitleScraper(savedAssrtToken, savedSubdlKey);
    }

    private void runSubtitleBatch() {
        if (!requireFolder()) {
            return;
        }
        Uri selected = folder;
        boolean byFolder = preferFolder.isChecked();
        boolean skipSubtitles = skipExistingSubtitles.isChecked();
        SubtitleScraper subtitles = subtitleScraper();
        begin(null, "正在扫描字幕下载目录…", () -> {
            MovieStorage storage = new MovieStorage(getApplicationContext(), selected);
            List<MovieStorage.Movie> scanned = storage.scan(byFolder);
            if (scanned.isEmpty()) {
                return () -> finishWithLog("没有发现可匹配字幕的电影文件");
            }
            SubtitleBatch batch = new SubtitleBatch();
            List<MovieStorage.Movie> pending = pendingSubtitles(storage, scanned, skipSubtitles, batch, true);
            downloadPendingSubtitles(storage, pending, subtitles, batch, false);
            String summary = batch.summary();
            return () -> finishWithLog(summary);
        });
    }

    private List<MovieStorage.Movie> pendingSubtitles(MovieStorage storage, List<MovieStorage.Movie> scanned,
                                                      boolean skipExisting, SubtitleBatch batch, boolean showProgress)
            throws IOException {
        if (!skipExisting) return scanned;
        List<MovieStorage.Movie> pending = new ArrayList<>();
        for (int index = 0; index < scanned.size(); index++) {
            ScrapeEngine.checkCancelled();
            MovieStorage.Movie movie = scanned.get(index);
            int position = index + 1;
            if (showProgress) post(() -> setProgress(position, scanned.size(), "检查本地字幕 · " + movie.title));
            try {
                if (SubtitleFiles.hasLocalSubtitle(storage, movie)) {
                    batch.skipped++;
                } else {
                    pending.add(movie);
                }
            } catch (IOException failure) {
                ScrapeEngine.checkCancelled();
                batch.failed++;
                DebugLog.failure("subtitle-preflight", failure);
                post(() -> noteMovie(movie, "字幕 · 检查失败", "无法检查本地字幕 · " + errorText(failure)));
            }
        }
        int skipped = batch.skipped;
        int failed = batch.failed;
        DebugLog.event("subtitle-preflight", "scanned=" + scanned.size() + " skipped=" + skipped
                + " pending=" + pending.size() + " failed=" + failed);
        if (showProgress) post(() -> note("字幕检查完成 · 已有 " + skipped + " 部 · 待下载 " + pending.size()
                + " 部" + (failed > 0 ? " · 无法检查 " + failed + " 部" : "")));
        return pending;
    }

    private void downloadPendingSubtitles(MovieStorage storage, List<MovieStorage.Movie> pending,
                                           SubtitleScraper scraper, SubtitleBatch batch, boolean metadataComplete)
            throws IOException {
        for (int index = 0; index < pending.size(); index++) {
            ScrapeEngine.checkCancelled();
            MovieStorage.Movie movie = pending.get(index);
            int position = index + 1;
            post(() -> {
                setProgress(position, pending.size(), "字幕 · " + movie.title);
                if (metadataComplete) noteMovie(movie, "资料 · 齐全", "已跳过刮削，本地资料齐全");
            });
            SubtitleQuery query = ScrapeEngine.subtitleQuery(storage, movie);
            recordSubtitles(movie, batch, () -> scraper.download(query));
        }
    }

    private interface SubtitleResult {
        SubtitleScraper.Outcome get() throws IOException;
    }

    private void recordSubtitles(MovieStorage.Movie movie, SubtitleBatch batch, SubtitleResult download)
            throws IOException {
        try {
            SubtitleScraper.Outcome result = download.get();
            DebugLog.event("subtitle", "source=" + (result.source.isEmpty() ? "none" : result.source)
                    + " saved=" + result.written + " duplicate=" + result.duplicates
                    + " provider_failures=" + result.providerFailures.size() + " write_error=" + (result.writeFailure != null));
            batch.written += result.written;
            batch.duplicates += result.duplicates;
            List<String> parts = new ArrayList<>();
            if (!result.source.isEmpty()) parts.add(result.source);
            if (result.written > 0) {
                parts.add("保存 " + result.written + " 个");
            }
            if (result.duplicates > 0) parts.add("已有 " + result.duplicates + " 个");
            if (result.writeFailure != null) {
                ScrapeEngine.checkCancelled();
                batch.failed++;
                parts.add("保存失败");
            } else if (!result.found()) {
                batch.missing++;
                parts.add("未找到中文字幕");
            }
            String summary = "字幕 · " + String.join(" · ", parts);
            List<String> detail = new ArrayList<>();
            detail.add(summary);
            detail.addAll(result.writtenFiles);
            for (SubtitleScraper.ProviderFailure failure : result.providerFailures) {
                detail.add("来源失败 · " + failure.source() + " · " + failure.message());
            }
            if (result.writeFailure != null) detail.add("保存失败 · " + errorText(result.writeFailure));
            post(() -> noteMovie(movie, summary, String.join("\n", detail)));
        } catch (IOException problem) {
            ScrapeEngine.checkCancelled();
            batch.failed++;
            DebugLog.failure("subtitle", problem);
            post(() -> noteMovie(movie, "字幕 · 下载失败", "字幕 · 下载失败 · " + errorText(problem)));
        }
    }

    private void openManual() {
        if (!requireFolder()) {
            return;
        }
        if (!movies.isEmpty()) {
            showMovieList();
            return;
        }
        Uri selected = folder;
        boolean byFolder = preferFolder.isChecked();
        begin(null, "正在扫描目录…", () -> {
            List<MovieStorage.Movie> scanned = new MovieStorage(getApplicationContext(), selected).scan(byFolder);
            return () -> {
                movies = scanned;
                if (movies.isEmpty()) {
                    currentRunPanel().state().setText("没有发现可匹配的电影文件");
                } else {
                    currentRunPanel().state().setText("已发现 " + movies.size() + " 部影片");
                    showScraperDialog(this::showMovieList);
                }
            };
        });
    }

    private void showMovieList() {
        ArrayList<MovieStorage.Movie> ordered = new ArrayList<>(movies);
        ordered.sort((a, b) -> Boolean.compare(needsReview.contains(b.relativePath), needsReview.contains(a.relativePath)));
        LinearLayout content = dialogContent();
        EditText filter = field("按文件名筛选", InputType.TYPE_CLASS_TEXT);
        filter.setSingleLine(true);
        add(content, filter, 0);
        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(310)));
        ArrayAdapter<String> rows = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        list.setAdapter(rows);
        ArrayList<MovieStorage.Movie> visible = new ArrayList<>();
        Runnable update = () -> {
            String query = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
            visible.clear();
            ArrayList<String> labels = new ArrayList<>();
            for (MovieStorage.Movie movie : ordered) {
                if (!movie.relativePath.toLowerCase(Locale.ROOT).contains(query)
                        && !movie.title.toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                if (visible.size() == 300) {
                    break;
                }
                visible.add(movie);
                labels.add((needsReview.contains(movie.relativePath) ? "待匹配 · " : "") + movie.relativePath);
            }
            rows.clear();
            rows.addAll(labels);
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                update.run();
            }

            @Override
            public void afterTextChanged(Editable editable) {}
        });
        update.run();
        AlertDialog chooser = new AlertDialog.Builder(this).setTitle("选择影片").setView(content)
                .setNegativeButton("关闭", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            MovieStorage.Movie movie = visible.get(position);
            chooser.dismiss();
            showQuery(movie, movie.title, movie.year);
        });
        chooser.show();
        chooser.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private void showQuery(MovieStorage.Movie movie, String currentTitle, int currentYear) {
        LinearLayout form = dialogContent();
        TextView source = text(movie.relativePath, 13, appearance.muted, false);
        add(form, source, 0);
        EditText title = field("电影片名、" + metadataProvider.idType + ":123 或 IMDb ID", InputType.TYPE_CLASS_TEXT);
        title.setSingleLine(true);
        title.setText(currentTitle);
        add(form, title, 9);
        EditText year = field("上映年份（可选）", InputType.TYPE_CLASS_NUMBER);
        year.setSingleLine(true);
        if (currentYear > 0) {
            year.setText(String.valueOf(currentYear));
        }
        add(form, year, 9);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("查找 " + metadataProvider.label + " 影片")
                .setView(form).setNegativeButton("取消", null).setPositiveButton("搜索", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String query = title.getText().toString().trim();
            if (query.isEmpty()) {
                title.setError("请输入片名或电影 ID");
                return;
            }
            MovieMetadataSource client = clientOrNotify();
            if (client == null) {
                dialog.dismiss();
                return;
            }
            dialog.dismiss();
            searchManual(movie, query, ScrapeEngine.parseId(year.getText().toString()), client);
        }));
        dialog.show();
    }

    private void searchManual(MovieStorage.Movie movie, String query, int year, MovieMetadataSource client) {
        begin(client, "正在查找影片…", () -> {
            String lower = query.toLowerCase(Locale.ROOT);
            int directId = 0;
            if (lower.matches("(" + client.provider().idType + "[: ]*)?[0-9]+")) {
                directId = ScrapeEngine.parseId(lower.replaceFirst("^" + client.provider().idType + "[: ]*", ""));
            } else if (lower.matches("tmdb[: ]*[0-9]+")) {
                directId = client.findMovieByTmdb(ScrapeEngine.parseId(lower.replaceFirst("^tmdb[: ]*", "")));
            } else if (lower.matches("tt[0-9]+")) {
                directId = client.findMovieByImdb(lower);
            } else {
                List<TmdbClient.Candidate> matches = client.search(query, year);
                return () -> showScraperDialog(() -> showCandidates(movie, query, year, matches));
            }
            if (directId <= 0) {
                return () -> showScraperDialog(() -> showCandidates(movie, query, year, Collections.emptyList()));
            }
            org.json.JSONObject details = client.details(directId);
            int id = directId;
            String title = details.optString("title");
            String date = details.optString("release_date");
            String original = details.optString("original_title");
            return () -> showScraperDialog(() -> confirmManual(movie, id, candidateLabel(title, original,
                    date.length() >= 4 ? date.substring(0, 4) : "", id, client.provider()), details.optString("overview")));
        });
    }

    private void showCandidates(MovieStorage.Movie movie, String query, int year, List<TmdbClient.Candidate> matches) {
        currentRunPanel().state().setText(matches.isEmpty() ? "未找到匹配的影片" : "找到 " + matches.size() + " 个候选");
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("选择匹配结果")
                .setNegativeButton("取消", null)
                .setNeutralButton("修改搜索", (d, which) -> showQuery(movie, query, year));
        if (matches.isEmpty()) {
            dialog.setMessage("没有候选结果。可以试试原名、调整年份或输入 " + metadataProvider.label + " ID。");
        } else {
            CharSequence[] labels = new CharSequence[matches.size()];
            for (int i = 0; i < matches.size(); i++) {
                TmdbClient.Candidate candidate = matches.get(i);
                labels[i] = candidateLabel(candidate.title, candidate.originalTitle,
                        candidate.year > 0 ? String.valueOf(candidate.year) : "", candidate.id, metadataProvider);
            }
            dialog.setItems(labels, (d, which) -> {
                TmdbClient.Candidate candidate = matches.get(which);
                confirmManual(movie, candidate.id, labels[which].toString(), candidate.overview);
            });
        }
        dialog.show();
    }

    private static String candidateLabel(String title, String original, String year, int id, MovieMetadataSource.Provider provider) {
        String alternate = original == null || original.isEmpty() || original.equals(title) ? "" : original + "  ·  ";
        return title + (year.isEmpty() ? "" : " (" + year + ")") + "\n" + alternate + provider.label + " " + id;
    }

    private void confirmManual(MovieStorage.Movie movie, int id, String label, String overview) {
        currentRunPanel().state().setText("待确认匹配");
        String shortened = overview == null || overview.isEmpty() ? ""
                : "\n\n" + (overview.length() <= 270 ? overview : overview.substring(0, 270) + "…");
        String message = label + shortened + "\n\n将更新所选影片的资料和图片，并保留旧 NFO 中的观看记录、续播点和个人评分。";
        if (autoSubtitles.isChecked()) {
            message += "同时查找中文字幕。";
        }
        new AlertDialog.Builder(this).setTitle("确认写入").setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认刮削", (dialog, which) -> writeManual(movie, id))
                .show();
    }

    private void writeManual(MovieStorage.Movie movie, int id) {
        MovieMetadataSource client = clientOrNotify();
        if (client == null) {
            return;
        }
        Uri selected = folder;
        Uri selectedCollection = collectionFolder;
        String selectedLanguage = language();
        boolean downloadActors = actors.isChecked();
        boolean downloadAlternate = alternateFanart.isChecked();
        SubtitleScraper subtitles = autoSubtitles.isChecked() ? subtitleScraper() : null;
        boolean skipSubtitles = skipExistingSubtitles.isChecked();
        persistOptions();
        beginLibraryTask(client, "正在手动刮削 · " + client.provider().label, movie, () -> {
            MovieStorage storage = new MovieStorage(getApplicationContext(), selected);
            MovieStorage collections = selectedCollection == null ? null
                    : new MovieStorage(getApplicationContext(), selectedCollection);
            ScrapeEngine engine = new ScrapeEngine(storage, collections, client,
                    selectedLanguage, true, downloadActors, downloadAlternate);
            SubtitleBatch batch = new SubtitleBatch();
            ScrapeEngine.Result result;
            int tmdbId = client.provider() == MovieMetadataSource.Provider.TMDB ? id : 0;
            boolean needsSubtitles = subtitles != null
                    && !pendingSubtitles(storage, Collections.singletonList(movie), skipSubtitles, batch, false).isEmpty();
            if (batch.skipped > 0) post(() -> noteMovie(movie, "字幕 · 已有", "已有本地字幕，已跳过下载"));
            try (SubtitleWork work = startSubtitles(needsSubtitles ? subtitles : null, storage, movie, tmdbId)) {
                ScrapeEngine.Outcome scraped = engine.scrape(movie, id, message -> post(() -> noteMovie(movie, "", message)),
                        work == null ? query -> {} : work::matched);
                result = scraped.result;
                String status = "资料 · " + resultLabel(result);
                post(() -> noteMovie(movie, status, status));
                if (work != null) {
                    work.matched(scraped.subtitleQuery);
                    recordSubtitles(movie, batch, work::result);
                }
            }
            return () -> {
                needsReview.remove(movie.relativePath);
                if (client.provider() == MovieMetadataSource.Provider.TMDB) apiVerified = true;
                currentRunPanel().state().setText(resultLabel(result));
                if (subtitles != null) {
                    noteMovie(movie, "", batch.summary());
                }
                updateUi();
            };
        });
    }

    private boolean requireFolder() {
        if (folder != null) {
            return true;
        }
        note("请先选择电影目录。");
        chooseFolder();
        return false;
    }

    private MovieMetadataSource createMetadataClient() {
        String tmdbKey = credential.getText().toString().trim();
        return metadataProvider == MovieMetadataSource.Provider.TVDB
                ? new TvdbClient(savedTvdbKey, savedTvdbPin, language(), tvdbSupplement && !tmdbKey.isEmpty()
                        ? new TmdbClient(tmdbKey, language()) : null)
                : new TmdbClient(tmdbKey, language());
    }

    private MovieMetadataSource clientOrNotify() {
        if (metadataProvider == MovieMetadataSource.Provider.TVDB) {
            if (!savedTvdbKey.isEmpty()) return createMetadataClient();
            configureTvdb();
            return null;
        }
        String value = credential.getText().toString().trim();
        if (value.isEmpty()) {
            credential.setError("请输入 TMDB API Key 或 Read Access Token");
            focusTmdbCredential();
            return null;
        }
        saveCredential();
        return createMetadataClient();
    }

    private void focusTmdbCredential() {
        credential.requestFocus();
        scroll.post(() -> scroll.smoothScrollTo(0,
                Math.max(0, ((View) credential.getParent().getParent()).getTop() - dp(14))));
    }

    private String language() {
        return LANGUAGES[Math.max(0, language.getSelectedItemPosition())];
    }

    private void saveCredential() {
        String value = credential.getText().toString().trim();
        if (!value.equals(savedCredential)) {
            if (CredentialStore.save(settings, "tmdb", value)) {
                savedCredential = value;
            } else if (scraperLog != null) {
                note("设备无法保存凭据，本次仍可使用。");
            }
        }
    }

    private void persistOptions() {
        settings.edit().putInt("language", language.getSelectedItemPosition())
                .putBoolean("actors", actors.isChecked())
                .putBoolean("alternate_fanart", alternateFanart.isChecked())
                .putBoolean("auto_subtitles", autoSubtitles.isChecked())
                .putBoolean("prefer_folder", preferFolder.isChecked())
                .putBoolean("overwrite", overwrite.isChecked()).apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (credential != null) {
            saveCredential();
            persistOptions();
        }
    }

    @Override
    protected void onStop() {
        if (posterWall != null) posterWall.stopTrailer();
        super.onStop();
    }

    private interface Task {
        Runnable run() throws IOException;
    }

    private void begin(MovieMetadataSource client, String status, Task task) {
        begin(client, status, false, task);
    }

    private void beginLibraryTask(MovieMetadataSource client, String status, Task task) {
        beginLibraryTask(client, status, null, task);
    }

    private void beginLibraryTask(MovieMetadataSource client, String status, MovieStorage.Movie movie, Task task) {
        if (busy) return;
        posterRefreshPending = true;
        begin(client, status, false, movie, task);
    }

    private void begin(MovieMetadataSource client, String status, boolean diagnostic, Task task) {
        begin(client, status, diagnostic, null, task);
    }

    private void begin(MovieMetadataSource client, String status, boolean diagnostic,
                       MovieStorage.Movie failureMovie, Task task) {
        if (busy) {
            return;
        }
        RunPanel taskPanel = currentRunPanel();
        activeRunPanel = taskPanel;
        busy = true;
        stopped = false;
        long started = System.nanoTime();
        DebugLog.event("task", "start page=" + page.name() + " provider="
                + (client == null ? "local" : client.provider().idType) + " diagnostic=" + diagnostic);
        activeClient = client;
        dismissKeyboard();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        currentRunPanel().state().setText(status);
        String firstEntry = "[" + LocalDateTime.now().format(LOG_TIME) + "] " + status;
        updateLog(taskPanel, taskPanel.history().append(redactLogText(firstEntry), true));
        currentRunPanel().progress().setVisibility(View.VISIBLE);
        currentRunPanel().progress().setIndeterminate(true);
        updateUi();
        if (!diagnostic) {
            ScrollView taskScroll = page == Page.SUBTITLES ? subtitleScroll : scroll;
            taskScroll.post(() -> {
                if (UiMotion.enabled()) taskScroll.smoothScrollTo(0, 0);
                else taskScroll.scrollTo(0, 0);
            });
        }
        worker = new Thread(() -> {
            Runnable completed = null;
            Exception problem = null;
            try {
                completed = task.run();
            } catch (IOException | RuntimeException error) {
                problem = error;
                DebugLog.failure("task", error instanceof MovieTaskFailure film ? film.failure : error);
            }
            Runnable onComplete = completed;
            Exception failure = problem;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                activeClient = null;
                worker = null;
                busy = false;
                DebugLog.event("task", "finished result=" + (stopped ? "stopped" : failure == null ? "ok" : "failed")
                        + " duration_ms=" + DebugLog.elapsed(started));
                if (posterRefreshPending) refreshPosters();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                currentRunPanel().progress().setVisibility(View.GONE);
                updateUi();
                try {
                    if (stopped) {
                        currentRunPanel().state().setText("已停止");
                        if (diagnostic) {
                            diagnosticReport.setText("连接检测已停止");
                            note("连接检测已停止");
                        } else {
                            note("任务已停止。已写入的资料可以继续使用。");
                        }
                    } else if (failure != null) {
                        currentRunPanel().state().setText("操作失败");
                        MovieTaskFailure film = failure instanceof MovieTaskFailure item ? item : null;
                        Exception reason = film == null ? failure : film.failure;
                        MovieStorage.Movie affectedMovie = film == null ? failureMovie : film.movie;
                        String message = errorText(reason);
                        if (affectedMovie == null) {
                            note(message);
                        } else {
                            noteMovie(affectedMovie, "资料 · 刮削失败", "资料 · 刮削失败 · " + message);
                        }
                        if (diagnostic) {
                            diagnosticReport.setText(redactLogText(message));
                        }
                        if (reason instanceof TmdbClient.HttpException http && http.statusCode == 401) {
                            apiVerified = false;
                            updateUi();
                        }
                    } else if (onComplete != null) {
                        onComplete.run();
                    }
                } finally {
                    activeRunPanel = null;
                }
            });
        }, "MovieScraper");
        worker.start();
    }

    private void cancelWork() {
        stopped = true;
        DebugLog.event("task", "stop requested");
        currentRunPanel().state().setText("正在停止…");
        updateUi();
        Thread running = worker;
        if (running != null) {
            running.interrupt();
            SubtitleHttp.cancel(running);
        }
        if (activeClient != null) {
            activeClient.cancel();
        }
    }

    private void post(Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                action.run();
            }
        });
    }

    @Override
    protected void onDestroy() {
        removeNavigationBackCallback();
        if (navigationPanel != null) navigationPanel.animate().cancel();
        if (navigationScrim != null) navigationScrim.animate().cancel();
        if (posterWall != null) posterWall.close();
        if (moviePush != null) {
            moviePush.close();
        }
        Thread running = worker;
        if (running != null) {
            running.interrupt();
            SubtitleHttp.cancel(running);
        }
        if (activeClient != null) {
            activeClient.cancel();
        }
        super.onDestroy();
    }

    private void setProgress(int index, int total, String title) {
        if (stopped) {
            return;
        }
        currentRunPanel().progress().setIndeterminate(false);
        currentRunPanel().progress().setMax(total);
        currentRunPanel().progress().setProgress(index - 1);
        currentRunPanel().state().setText(index + " / " + total + " · " + title);
        DebugLog.event("progress", "position=" + index + " total=" + total);
    }

    private void updateUi() {
        if (start == null) {
            return;
        }
        pickFolder.setEnabled(!busy);
        pickSubtitleFolder.setEnabled(!busy);
        organizeMovies.setEnabled(!busy);
        validate.setEnabled(!busy);
        metadataSourceButton.setEnabled(!busy);
        manual.setEnabled(!busy);
        credential.setEnabled(!busy);
        showCredential.setEnabled(!busy);
        subtitleSettings.setEnabled(!busy);
        testAssrt.setEnabled(!busy);
        autoSubtitles.setEnabled(!busy);
        skipExistingSubtitles.setEnabled(!busy);
        actors.setEnabled(!busy);
        alternateFanart.setEnabled(!busy);
        pickCollectionFolder.setEnabled(!busy);
        clearCollectionFolder.setEnabled(!busy);
        overwrite.setEnabled(!busy);
        preferFolder.setEnabled(!busy);
        language.setEnabled(!busy);
        updatePrimaryAction(start, "一键刮削");
        updatePrimaryAction(subtitlesOnly, "一键下载字幕");
        manual.setText(needsReview.isEmpty() ? "手动匹配影片" : "手动匹配 · " + needsReview.size());
        manual.setContentDescription(needsReview.isEmpty() ? "手动匹配影片"
                : "手动匹配影片 · " + needsReview.size() + " 部待确认");
        apiState.setText(apiVerified ? "连接正常" : credential.length() > 0 ? "已输入" : "未设置");
        apiState.setTextColor(apiVerified ? appearance.accent : appearance.muted);
    }

    private void updatePrimaryAction(Button action, String label) {
        action.setEnabled(!busy || !stopped);
        action.setText(!busy ? label : stopped ? "正在停止…" : "停止任务");
    }

    private RunPanel currentRunPanel() {
        return activeRunPanel != null ? activeRunPanel : page == Page.SUBTITLES ? subtitleLog : scraperLog;
    }

    private void note(String message) {
        RunPanel panel = currentRunPanel();
        updateLog(panel, panel.history().append(redactLogText(message), false));
    }

    private void noteMovie(MovieStorage.Movie movie, String concise, String detail) {
        String title = movie.title + (movie.year > 0 ? " (" + movie.year + ")" : "");
        noteMovie(movie.relativePath, title, concise, detail);
    }

    private void noteMovie(String moviePath, String movieTitle, String concise, String detail) {
        RunPanel panel = currentRunPanel();
        String visible = redactLogText(concise);
        List<ScrapeHistory.Entry> updated = panel.history().appendMovie(redactLogText(moviePath),
                redactLogText(movieTitle), visible, redactLogText(detail));
        if (!visible.isEmpty()) updateLog(panel, updated);
    }

    private String redactLogText(String message) {
        String safe = SECRET_PARAMETER.matcher(message).replaceAll("$1[已隐藏]");
        safe = BEARER_VALUE.matcher(safe).replaceAll("$1[已隐藏]");
        String input = credential.getText().toString().trim();
        for (String value : new String[]{input, savedCredential, savedTvdbKey, savedTvdbPin, savedAssrtToken, savedSubdlKey}) {
            if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                value = value.substring(7).trim();
            }
            if (value.length() >= 8) {
                safe = safe.replace(value, "[已隐藏]");
            }
        }
        return safe;
    }

    private void finishWithLog(String summary) {
        currentRunPanel().state().setText(summary);
        note(summary);
    }

    private void updateLog(RunPanel panel, List<ScrapeHistory.Entry> entries) {
        boolean follow = !panel.viewer().canScrollVertically(1);
        if (!entries.isEmpty()) {
            ScrapeHistory.Entry latest = entries.get(0);
            if (!latest.isMovie() && latest.concise().contentEquals(panel.state().getText())) {
                entries = entries.subList(1, entries.size());
            }
        }
        CharSequence rendered = ScrapeLogText.render(entries, false, dp(14));
        panel.entries().setText(rendered);
        panel.viewer().setVisibility(rendered.length() == 0 ? View.GONE : View.VISIBLE);
        if (follow && rendered.length() > 0) {
            panel.viewer().post(() -> panel.viewer().scrollTo(0, panel.entries().getHeight()));
        }
    }

    private void dismissKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(frame.getWindowToken(), 0);
        frame.requestFocus();
    }

    private void showAbout() {
        LinearLayout info = dialogContent();
        add(info, text("Kodi 电影刮削器 1.17.0", 19, appearance.foreground, true), 0);
        add(info, text("在 Kodi 中将电影源的内容类型设为「电影」，资料来源选择「仅本地信息」。"
                + "按一部电影一个文件夹整理的资料库可开启「电影位于与片名匹配的独立文件夹」。"
                + "如需读取演员头像和更多图片类型，可以开启演员缩略图，并将影片图片级别设为「最大」或自定义。", 14, appearance.muted, false), 12);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.tmdb_logo);
        logo.setContentDescription("The Movie Database");
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        LinearLayout.LayoutParams logoSpace = new LinearLayout.LayoutParams(dp(157), dp(28));
        logoSpace.topMargin = dp(18);
        info.addView(logo, logoSpace);
        add(info, text("This product uses the TMDB API but is not endorsed or certified by TMDB.", 13, appearance.muted, false), 5);
        TextView tvdb = text("Metadata and artwork provided by TheTVDB.com. https://www.thetvdb.com/",
                13, appearance.muted, false);
        Linkify.addLinks(tvdb, Linkify.WEB_URLS);
        add(info, tvdb, 12);
        ScrollView details = new ScrollView(this);
        details.addView(info, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("关于").setView(details).setPositiveButton("关闭", null).show();
    }

    private static boolean shouldStopBatch(IOException problem) {
        if (!(problem instanceof MovieMetadataSource.HttpError http)) {
            return false;
        }
        int code = http.statusCode;
        return code == 401 || code == 403 || code == 429;
    }

    static String errorText(Exception problem) {
        if (problem instanceof TmdbClient.HttpException) {
            switch (((TmdbClient.HttpException) problem).statusCode) {
                case 401: return "TMDB 凭据无效，请检查 API Key 或 Read Access Token。";
                case 403: return "TMDB 拒绝了请求（403），请检查凭据或网络。";
                case 404: return "TMDB 没有找到该影片。";
                case 429: return "TMDB 请求过于频繁，稍后可再次运行。";
                default: return "TMDB 暂时无法完成请求（HTTP " + ((TmdbClient.HttpException) problem).statusCode + "）。";
            }
        }
        if (problem instanceof SecurityException) {
            return "电影目录的访问权限已失效，请重新选择文件夹。";
        }
        String message = problem.getMessage();
        return message == null || message.isEmpty() || !(problem instanceof IOException)
                ? "无法访问资料或写入文件，请检查网络和目录权限。"
                : message;
    }

    private static String resultLabel(ScrapeEngine.Result result) {
        switch (result) {
            case WRITTEN: return "已写入";
            case INCOMPLETE: return "有资料待补";
            case PRESERVED: return "已保留";
            default: return "待确认";
        }
    }

    private static final class Batch {
        int started;
        int written;
        int incomplete;
        int preserved;
        int skipped;
        int review;
        int failed;

        void add(Batch run) {
            written += run.written;
            incomplete += run.incomplete;
            preserved += run.preserved;
            review += run.review;
            failed += run.failed;
        }

        void count(ScrapeEngine.Result result) {
            switch (result) {
                case WRITTEN: written++; break;
                case INCOMPLETE: incomplete++; break;
                case PRESERVED: preserved++; break;
                case REVIEW: review++; break;
            }
        }

        String summary(int completed, int total) {
            String count = total == 0 ? "没有待刮削影片" : "处理 " + completed + "/" + total + " 部";
            return count + " · 写入 " + written + " · 待补 "
                    + incomplete + " · 保留 " + preserved + " · 跳过 " + skipped + " · 待确认 " + review + " · 失败 " + failed;
        }
    }

    private static final class SubtitleBatch {
        int written;
        int duplicates;
        int skipped;
        int missing;
        int failed;

        void add(SubtitleBatch run) {
            written += run.written;
            duplicates += run.duplicates;
            skipped += run.skipped;
            missing += run.missing;
            failed += run.failed;
        }

        String summary() {
            return "字幕 · 保存 " + written + " 个 · 已跳过 " + skipped + " 部 · 重复 " + duplicates + " 个 · 未匹配 " + missing
                    + " 部 · 失败 " + failed + " 部";
        }
    }

    private LinearLayout card(LinearLayout column) {
        LinearLayout panel = vertical();
        panel.setPadding(dp(17), dp(15), dp(17), dp(15));
        panel.setBackground(shape(appearance.surface, 22, appearance.border));
        add(column, panel, 12);
        return panel;
    }

    private LinearLayout settingsSection(LinearLayout settingsCard, boolean separated) {
        if (separated) {
            View rule = new View(this);
            rule.setBackgroundColor(appearance.border);
            LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, dp(1));
            margin.setMargins(0, dp(17), 0, dp(16));
            settingsCard.addView(rule, margin);
        }
        LinearLayout section = vertical();
        add(settingsCard, section, 0);
        return section;
    }

    private TextView settingsHeading(String label) {
        TextView heading = text(label, 17, appearance.foreground, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        return heading;
    }

    private void addSettingsHeading(LinearLayout section, String label) {
        add(section, settingsHeading(label), 0);
    }

    private LinearLayout dialogContent() {
        LinearLayout content = vertical();
        content.setPadding(dp(22), dp(12), dp(22), dp(5));
        return content;
    }

    LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size < 15 ? size + 1 : size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return view;
    }

    EditText field(String hint, int type) {
        EditText input = new EditText(this);
        input.setTextColor(appearance.foreground);
        input.setHintTextColor(appearance.muted);
        input.setTextSize(16);
        input.setInputType(type);
        input.setHint(hint);
        StateListDrawable fieldBackground = new StateListDrawable();
        fieldBackground.addState(new int[]{android.R.attr.state_focused},
                shape(appearance.surface, 14, appearance.accent));
        fieldBackground.addState(new int[0], shape(appearance.background, 14, appearance.border));
        input.setBackground(fieldBackground);
        input.setMinHeight(dp(48));
        input.setPadding(dp(13), dp(9), dp(13), dp(9));
        return input;
    }

    Button button(String name, int fill, int foreground) {
        Button button = new Button(this);
        button.setText(name);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setLetterSpacing(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
                new int[] {appearance.disabled, foreground}));
        int ripple = fill == appearance.primary ? 0x26000000 | (foreground & 0x00ffffff) : appearance.ripple;
        StateListDrawable surfaces = new StateListDrawable();
        surfaces.addState(new int[]{android.R.attr.state_focused}, shape(fill, 14, foreground));
        surfaces.addState(new int[0], shape(fill, 14, 0));
        RippleDrawable background = new RippleDrawable(ColorStateList.valueOf(ripple), surfaces, null);
        button.setBackground(background);
        return button;
    }

    private Switch option(String description, boolean value) {
        Switch toggle = new Switch(this);
        toggle.setText(description);
        toggle.setTextColor(appearance.foreground);
        toggle.setTextSize(16);
        toggle.setMinHeight(dp(49));
        toggle.setSwitchPadding(dp(12));
        toggle.setChecked(value);
        return toggle;
    }

    void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2);
        margin.topMargin = dp(top);
        parent.addView(child, margin);
    }

    GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
