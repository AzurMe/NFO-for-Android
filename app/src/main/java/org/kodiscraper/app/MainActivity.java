package org.kodiscraper.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int PICK_FOLDER = 1;
    private static final Uri MOVIES_LOCATION = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Movies");
    private static final int INK = 0xff142a21;
    private static final int MUTED = 0xff4e675a;
    private static final int GREEN = 0xff194b39;
    private static final int DARK = 0xff10392c;
    private static final int LIME = 0xffe1f28c;
    private static final int PAPER = 0xfff4f4eb;
    private static final int CARD = 0xfffffff9;
    private static final int EDGE = 0xffdce5d8;
    private static final int PALE = 0xffedf3e5;
    private static final int WHITE = Color.WHITE;
    private static final int RED = 0xff9b372c;
    private static final String[] LANGUAGES = {"zh-CN", "zh-TW", "en-US", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES"};
    private static final String[] LANGUAGE_NAMES = {"简体中文", "繁體中文", "English", "日本語", "한국어", "Français", "Deutsch", "Español"};
    private static final int LOG_LIMIT = 50;

    private final ArrayDeque<String> messages = new ArrayDeque<>();
    private final Set<String> needsReview = new HashSet<>();
    private volatile boolean stopped;
    private volatile Thread worker;
    private TmdbClient activeClient;
    private boolean busy;
    private boolean apiVerified;
    private Uri folder;
    private SharedPreferences settings;
    private List<MovieStorage.Movie> movies = Collections.emptyList();
    private FrameLayout frame;
    private ScrollView scroll;
    private TextView folderName;
    private TextView apiState;
    private TextView diagnosticReport;
    private TextView runState;
    private TextView log;
    private ProgressBar progress;
    private Button pickFolder;
    private Button validate;
    private Button start;
    private Button manual;
    private Button showCredential;
    private EditText credential;
    private Spinner language;
    private Switch overwrite;
    private Switch actors;
    private Switch preferFolder;
    private LinearLayout logPanel;
    private String savedCredential = "";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = getSharedPreferences("scraper", MODE_PRIVATE);
        savedCredential = CredentialStore.read(settings);
        String savedFolder = settings.getString("folder", "");
        if (!savedFolder.isEmpty()) {
            folder = Uri.parse(savedFolder);
        }
        configureWindow();
        buildScreen();
    }

    private void configureWindow() {
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void buildScreen() {
        frame = new FrameLayout(this);
        frame.setBackgroundColor(PAPER);
        frame.setFocusableInTouchMode(true);
        LinearLayout stack = vertical();
        frame.addView(stack, viewport());
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        stack.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(0, 0, 0, 0);
            }
            return insets;
        });
        LinearLayout column = vertical();
        column.setPadding(dp(18), dp(14), dp(18), dp(16));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        addHeader(column);
        addDirectory(column);
        addConnection(column);
        addOptions(column);
        addLog(column);
        addControls(stack);
        setContentView(frame);
        frame.requestFocus();
        updateFolderLabel();
        updateUi();
    }

    private FrameLayout.LayoutParams viewport() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return new FrameLayout.LayoutParams(Math.min(screenWidth, dp(660)), -1, Gravity.CENTER_HORIZONTAL);
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        frame.getChildAt(0).setLayoutParams(viewport());
    }

    private void addHeader(LinearLayout column) {
        LinearLayout hero = vertical();
        hero.setPadding(dp(20), dp(17), dp(17), dp(22));
        hero.setBackground(shape(DARK, 24, 0));
        LinearLayout top = horizontal();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        top.addView(icon, new LinearLayout.LayoutParams(dp(37), dp(37)));
        TextView wordmark = text("KODI · 电影资料库", 12, LIME, true);
        wordmark.setLetterSpacing(.08f);
        LinearLayout.LayoutParams mark = new LinearLayout.LayoutParams(0, -2, 1f);
        mark.leftMargin = dp(8);
        top.addView(wordmark, mark);
        Button about = button("关于", 0xff24533f, WHITE);
        about.setContentDescription("关于与 TMDB 署名");
        top.addView(about, new LinearLayout.LayoutParams(dp(68), dp(42)));
        about.setOnClickListener(view -> showAbout());
        hero.addView(top);
        TextView title = text("电影刮削器", 36, WHITE, true);
        title.setLetterSpacing(-.03f);
        add(hero, title, 12);
        TextView subtitle = text("写入 Kodi 可读取的 NFO、海报和演员资料", 14, 0xffc5dbc9, false);
        add(hero, subtitle, 7);
        add(column, hero, 0);
    }

    private void addDirectory(LinearLayout column) {
        LinearLayout card = card(column);
        add(card, text("电影搜索路径", 19, INK, true), 0);
        folderName = text("", 14, MUTED, false);
        folderName.setMaxLines(3);
        folderName.setTextIsSelectable(true);
        add(card, folderName, 7);
        pickFolder = button("设置搜索路径", PALE, GREEN);
        pickFolder.setOnClickListener(view -> chooseFolder());
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-2, dp(45));
        placement.topMargin = dp(11);
        card.addView(pickFolder, placement);
    }

    private void addConnection(LinearLayout column) {
        LinearLayout card = card(column);
        LinearLayout heading = horizontal();
        heading.addView(text("TMDB 连接", 19, INK, true), new LinearLayout.LayoutParams(0, -2, 1f));
        apiState = text("", 12, MUTED, true);
        heading.addView(apiState);
        add(card, heading, 0);
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
        showCredential = button("显示", PALE, GREEN);
        showCredential.setOnClickListener(view -> {
            boolean concealed = credential.getTransformationMethod() instanceof PasswordTransformationMethod;
            credential.setTransformationMethod(concealed ? null : PasswordTransformationMethod.getInstance());
            credential.setSelection(credential.length());
            showCredential.setText(concealed ? "隐藏" : "显示");
        });
        LinearLayout.LayoutParams showPlacement = new LinearLayout.LayoutParams(dp(58), dp(48));
        showPlacement.leftMargin = dp(7);
        input.addView(showCredential, showPlacement);
        add(card, input, 11);
        validate = button("测试 TMDB 连接", PALE, GREEN);
        validate.setOnClickListener(view -> validateApi());
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-2, dp(44));
        placement.topMargin = dp(10);
        card.addView(validate, placement);
        diagnosticReport = text("", 13, INK, false);
        diagnosticReport.setTextIsSelectable(true);
        diagnosticReport.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        diagnosticReport.setLineSpacing(dp(3), 1f);
        diagnosticReport.setVisibility(View.GONE);
        add(card, diagnosticReport, 8);
    }

    private void addOptions(LinearLayout column) {
        LinearLayout card = card(column);
        add(card, text("刮削设置", 19, INK, true), 0);
        LinearLayout row = horizontal();
        row.addView(text("资料语言", 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1f));
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
        overwrite = option("覆盖已有 NFO 和图片", settings.getBoolean("overwrite", false));
        add(card, overwrite, 0);
    }

    private void addControls(LinearLayout stack) {
        LinearLayout actions = vertical();
        actions.setBackgroundColor(PAPER);
        actions.setPadding(dp(18), dp(7), dp(18), dp(10));
        stack.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        start = button("一键刮削", GREEN, WHITE);
        start.setTextSize(17);
        start.setOnClickListener(view -> {
            if (busy) {
                cancelWork();
            } else {
                runBatch();
            }
        });
        actions.addView(start, new LinearLayout.LayoutParams(-1, dp(54)));
        manual = button("手动匹配影片", PALE, GREEN);
        manual.setOnClickListener(view -> openManual());
        LinearLayout.LayoutParams manualPlacement = new LinearLayout.LayoutParams(-1, dp(48));
        manualPlacement.topMargin = dp(7);
        actions.addView(manual, manualPlacement);
    }

    private void addLog(LinearLayout column) {
        LinearLayout card = card(column);
        logPanel = card;
        add(card, text("刮削记录", 19, INK, true), 0);
        runState = text("等待开始", 14, MUTED, true);
        runState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(card, runState, 7);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(GREEN));
        progress.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        progress.setMax(100);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(8));
        bar.topMargin = dp(6);
        card.addView(progress, bar);
        progress.setVisibility(View.GONE);
        log = text("", 13, INK, false);
        log.setTextIsSelectable(true);
        log.setLineSpacing(dp(4), 1f);
        add(card, log, 7);
        log.setVisibility(View.GONE);
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        Uri initial = folder == null ? MOVIES_LOCATION : folder;
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        try {
            startActivityForResult(intent, PICK_FOLDER);
        } catch (RuntimeException unavailable) {
            note("这台设备未提供系统文件夹选择器。");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri selected = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0
                || (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
            note("请选择允许读取和写入的目录。");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(selected, flags);
        } catch (SecurityException problem) {
            note("无法保存该文件夹的访问权限，请选择其他目录。");
            return;
        }
        Uri previous = folder;
        folder = selected;
        movies = Collections.emptyList();
        needsReview.clear();
        settings.edit().putString("folder", folder.toString()).apply();
        if (previous != null && !previous.equals(folder)) {
            try {
                getContentResolver().releasePersistableUriPermission(previous,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // A previously selected document provider may no longer be available.
            }
        }
        updateFolderLabel();
        updateUi();
        note("已选择 " + directoryName());
    }

    private String directoryName() {
        if (folder == null) {
            return "";
        }
        try {
            String document = DocumentsContract.getTreeDocumentId(folder);
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
        folderName.setText(folder == null ? "内部存储 / Movies（等待授权）" : directoryName());
        pickFolder.setText(folder == null ? "设置搜索路径" : "更改搜索路径");
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
                runState.setText("TMDB 连接检测完成");
                note(summary);
                updateUi();
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
        TmdbClient client = clientOrNotify();
        if (client == null) {
            return;
        }
        Uri selectedFolder = folder;
        String selectedLanguage = language();
        boolean replace = overwrite.isChecked();
        boolean downloadActors = actors.isChecked();
        boolean byFolder = preferFolder.isChecked();
        persistOptions();
        messages.clear();
        needsReview.clear();
        updateLog();
        begin(client, "正在扫描目录…", () -> {
            MovieStorage storage = new MovieStorage(getApplicationContext(), selectedFolder);
            List<MovieStorage.Movie> scanned = storage.scan(byFolder);
            post(() -> {
                movies = scanned;
                note("发现 " + scanned.size() + " 部影片。");
            });
            if (scanned.isEmpty()) {
                return () -> runState.setText("没有发现可刮削的电影文件");
            }
            client.validate();
            post(() -> {
                apiVerified = true;
                updateUi();
            });
            ScrapeEngine engine = new ScrapeEngine(getApplicationContext(), storage, client,
                    selectedLanguage, replace, downloadActors);
            Batch batch = new Batch();
            for (MovieStorage.Movie movie : scanned) {
                ScrapeEngine.checkCancelled();
                int index = ++batch.started;
                post(() -> setProgress(index, scanned.size(), movie.title));
                try {
                    ScrapeEngine.Result result = engine.scrape(movie, 0,
                            message -> post(() -> note(movie.relativePath + " · " + message)));
                    batch.count(result);
                    post(() -> {
                        if (result == ScrapeEngine.Result.REVIEW) {
                            needsReview.add(movie.relativePath);
                        }
                        note(resultLabel(result) + " · " + movie.relativePath);
                    });
                } catch (IOException problem) {
                    ScrapeEngine.checkCancelled();
                    if (shouldStopBatch(problem)) {
                        String summary = batch.summary(index - 1, scanned.size());
                        post(() -> note(summary));
                        throw problem;
                    }
                    if (problem instanceof TmdbClient.HttpException
                            && ((TmdbClient.HttpException) problem).statusCode == 404) {
                        batch.count(ScrapeEngine.Result.REVIEW);
                        post(() -> {
                            needsReview.add(movie.relativePath);
                            note("未找到资料，请手动匹配 · " + movie.relativePath);
                        });
                    } else {
                        batch.failed++;
                        post(() -> note("失败 · " + movie.relativePath + " · " + errorText(problem)));
                    }
                }
            }
            String summary = batch.summary(scanned.size(), scanned.size());
            return () -> {
                runState.setText(summary);
                note(summary);
            };
        });
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
                    runState.setText("没有发现可匹配的电影文件");
                } else {
                    runState.setText("已发现 " + movies.size() + " 部影片");
                    showMovieList();
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
        TextView source = text(movie.relativePath, 13, MUTED, false);
        add(form, source, 0);
        EditText title = field("电影片名、tmdb:123 或 IMDb ID", InputType.TYPE_CLASS_TEXT);
        title.setSingleLine(true);
        title.setText(currentTitle);
        add(form, title, 9);
        EditText year = field("上映年份（可选）", InputType.TYPE_CLASS_NUMBER);
        year.setSingleLine(true);
        if (currentYear > 0) {
            year.setText(String.valueOf(currentYear));
        }
        add(form, year, 9);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("查找 TMDB 影片")
                .setView(form).setNegativeButton("取消", null).setPositiveButton("搜索", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String query = title.getText().toString().trim();
            if (query.isEmpty()) {
                title.setError("请输入片名或电影 ID");
                return;
            }
            TmdbClient client = clientOrNotify();
            if (client == null) {
                dialog.dismiss();
                return;
            }
            dialog.dismiss();
            searchManual(movie, query, ScrapeEngine.parseId(year.getText().toString()), client);
        }));
        dialog.show();
    }

    private void searchManual(MovieStorage.Movie movie, String query, int year, TmdbClient client) {
        begin(client, "正在查找影片…", () -> {
            String lower = query.toLowerCase(Locale.ROOT);
            int directId = 0;
            if (lower.matches("(tmdb[: ]*)?[0-9]+")) {
                directId = ScrapeEngine.parseId(lower.replaceFirst("^tmdb[: ]*", ""));
            } else if (lower.matches("tt[0-9]+")) {
                directId = client.findMovieByImdb(lower);
            } else {
                List<TmdbClient.Candidate> matches = client.search(query, year);
                return () -> showCandidates(movie, query, year, matches);
            }
            if (directId <= 0) {
                return () -> showCandidates(movie, query, year, Collections.emptyList());
            }
            org.json.JSONObject details = client.details(directId);
            int id = directId;
            String title = details.optString("title");
            String date = details.optString("release_date");
            String original = details.optString("original_title");
            return () -> confirmManual(movie, id, candidateLabel(title, original,
                    date.length() >= 4 ? date.substring(0, 4) : "", id), details.optString("overview"));
        });
    }

    private void showCandidates(MovieStorage.Movie movie, String query, int year, List<TmdbClient.Candidate> matches) {
        runState.setText(matches.isEmpty() ? "未找到匹配的影片" : "找到 " + matches.size() + " 个候选");
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("选择匹配结果")
                .setNegativeButton("取消", null)
                .setNeutralButton("修改搜索", (d, which) -> showQuery(movie, query, year));
        if (matches.isEmpty()) {
            dialog.setMessage("没有候选结果。可以试试原名、调整年份或输入 TMDB ID。");
        } else {
            CharSequence[] labels = new CharSequence[matches.size()];
            for (int i = 0; i < matches.size(); i++) {
                TmdbClient.Candidate candidate = matches.get(i);
                labels[i] = candidateLabel(candidate.title, candidate.originalTitle,
                        candidate.year > 0 ? String.valueOf(candidate.year) : "", candidate.id);
            }
            dialog.setItems(labels, (d, which) -> {
                TmdbClient.Candidate candidate = matches.get(which);
                confirmManual(movie, candidate.id, labels[which].toString(), candidate.overview);
            });
        }
        dialog.show();
    }

    private static String candidateLabel(String title, String original, String year, int id) {
        String alternate = original == null || original.isEmpty() || original.equals(title) ? "" : original + "  ·  ";
        return title + (year.isEmpty() ? "" : " (" + year + ")") + "\n" + alternate + "TMDB " + id;
    }

    private void confirmManual(MovieStorage.Movie movie, int id, String label, String overview) {
        runState.setText("待确认匹配");
        String shortened = overview == null || overview.isEmpty() ? ""
                : "\n\n" + (overview.length() <= 270 ? overview : overview.substring(0, 270) + "…");
        String message = label + shortened + "\n\n将为所选影片写入资料，并替换同名的已有 NFO 和图片。";
        new AlertDialog.Builder(this).setTitle("确认写入").setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认刮削", (dialog, which) -> writeManual(movie, id))
                .show();
    }

    private void writeManual(MovieStorage.Movie movie, int id) {
        TmdbClient client = clientOrNotify();
        if (client == null) {
            return;
        }
        Uri selected = folder;
        String selectedLanguage = language();
        boolean downloadActors = actors.isChecked();
        persistOptions();
        begin(client, "正在刮削 " + movie.title, () -> {
            MovieStorage storage = new MovieStorage(getApplicationContext(), selected);
            ScrapeEngine engine = new ScrapeEngine(getApplicationContext(), storage, client,
                    selectedLanguage, true, downloadActors);
            ScrapeEngine.Result result = engine.scrape(movie, id, message -> post(() -> note(message)));
            return () -> {
                needsReview.remove(movie.relativePath);
                apiVerified = true;
                runState.setText(resultLabel(result));
                note(resultLabel(result) + " · " + movie.relativePath);
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

    private TmdbClient clientOrNotify() {
        String value = credential.getText().toString().trim();
        if (value.isEmpty()) {
            credential.setError("请输入 TMDB API Key 或 Read Access Token");
            credential.requestFocus();
            scroll.smoothScrollTo(0, Math.max(0, ((View) credential.getParent().getParent()).getTop() - dp(14)));
            return null;
        }
        saveCredential();
        return new TmdbClient(value, language());
    }

    private String language() {
        return LANGUAGES[Math.max(0, language.getSelectedItemPosition())];
    }

    private void saveCredential() {
        String value = credential.getText().toString().trim();
        if (!value.equals(savedCredential)) {
            if (CredentialStore.save(settings, value)) {
                savedCredential = value;
            } else if (log != null) {
                note("设备无法保存凭据，本次仍可使用。");
            }
        }
    }

    private void persistOptions() {
        settings.edit().putInt("language", language.getSelectedItemPosition())
                .putBoolean("actors", actors.isChecked())
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

    private interface Task {
        Runnable run() throws IOException;
    }

    private void begin(TmdbClient client, String status, Task task) {
        begin(client, status, false, task);
    }

    private void begin(TmdbClient client, String status, boolean diagnostic, Task task) {
        if (busy) {
            return;
        }
        busy = true;
        stopped = false;
        activeClient = client;
        dismissKeyboard();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runState.setText(status);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        updateUi();
        if (!diagnostic) {
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, logPanel.getTop() - dp(8))));
        }
        worker = new Thread(() -> {
            Runnable completed = null;
            Exception problem = null;
            try {
                completed = task.run();
            } catch (IOException | RuntimeException error) {
                problem = error;
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
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                progress.setVisibility(View.GONE);
                updateUi();
                if (stopped) {
                    runState.setText("已停止");
                    if (diagnostic) {
                        diagnosticReport.setText("连接检测已停止");
                    } else {
                        note("任务已停止。已写入的资料可以继续使用。");
                    }
                } else if (failure != null) {
                    runState.setText("操作失败");
                    note(errorText(failure));
                    if (diagnostic) {
                        diagnosticReport.setText(errorText(failure));
                    }
                    if (failure instanceof TmdbClient.HttpException
                            && ((TmdbClient.HttpException) failure).statusCode == 401) {
                        apiVerified = false;
                        updateUi();
                    }
                } else if (onComplete != null) {
                    onComplete.run();
                }
            });
        }, "MovieScraper");
        worker.start();
    }

    private void cancelWork() {
        stopped = true;
        runState.setText("正在停止…");
        updateUi();
        Thread running = worker;
        if (running != null) {
            running.interrupt();
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
        Thread running = worker;
        if (running != null) {
            running.interrupt();
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
        progress.setIndeterminate(false);
        progress.setMax(total);
        progress.setProgress(index - 1);
        runState.setText(index + " / " + total + " · " + title);
    }

    private void updateUi() {
        if (start == null) {
            return;
        }
        pickFolder.setEnabled(!busy);
        validate.setEnabled(!busy);
        manual.setEnabled(!busy);
        credential.setEnabled(!busy);
        showCredential.setEnabled(!busy);
        actors.setEnabled(!busy);
        overwrite.setEnabled(!busy);
        preferFolder.setEnabled(!busy);
        language.setEnabled(!busy);
        start.setEnabled(!busy || !stopped);
        start.setText(!busy ? "一键刮削" : stopped ? "正在停止…" : "停止任务");
        manual.setText(needsReview.isEmpty() ? "手动匹配影片" : "手动匹配影片 · " + needsReview.size() + " 部待确认");
        apiState.setText(apiVerified ? "连接正常" : credential.length() > 0 ? "已输入" : "未设置");
        apiState.setTextColor(apiVerified ? GREEN : MUTED);
    }

    private void note(String message) {
        if (messages.size() >= LOG_LIMIT) {
            messages.removeLast();
        }
        messages.addFirst(message);
        updateLog();
    }

    private void updateLog() {
        log.setText(String.join("\n", messages));
        log.setVisibility(messages.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void dismissKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(frame.getWindowToken(), 0);
        frame.requestFocus();
    }

    private void showAbout() {
        LinearLayout info = dialogContent();
        add(info, text("Kodi 电影刮削器 1.1.1", 19, INK, true), 0);
        add(info, text("在 Kodi 中将电影源的内容类型设为「电影」，资料来源选择「仅本地信息」。"
                + "按一部电影一个文件夹整理的资料库可开启「电影位于与片名匹配的独立文件夹」。"
                + "如需读取演员头像和更多图片类型，可以开启演员缩略图，并将影片图片级别设为「最大」或自定义。", 14, MUTED, false), 12);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.tmdb_logo);
        logo.setContentDescription("The Movie Database");
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        LinearLayout.LayoutParams logoSpace = new LinearLayout.LayoutParams(dp(157), dp(28));
        logoSpace.topMargin = dp(18);
        info.addView(logo, logoSpace);
        add(info, text("This product uses the TMDB API but is not endorsed or certified by TMDB.", 13, MUTED, false), 5);
        new AlertDialog.Builder(this).setTitle("关于").setView(info).setPositiveButton("关闭", null).show();
    }

    private static boolean shouldStopBatch(IOException problem) {
        if (!(problem instanceof TmdbClient.HttpException)) {
            return false;
        }
        int code = ((TmdbClient.HttpException) problem).statusCode;
        return code == 401 || code == 403 || code == 429;
    }

    private static String errorText(Exception problem) {
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
            case INCOMPLETE: return "部分图片待补";
            case PRESERVED: return "已保留";
            default: return "待确认";
        }
    }

    private static final class Batch {
        int started;
        int written;
        int incomplete;
        int preserved;
        int review;
        int failed;

        void count(ScrapeEngine.Result result) {
            switch (result) {
                case WRITTEN: written++; break;
                case INCOMPLETE: incomplete++; break;
                case PRESERVED: preserved++; break;
                case REVIEW: review++; break;
            }
        }

        String summary(int completed, int total) {
            return "处理 " + completed + "/" + total + " 部 · 写入 " + written + " · 待补图 "
                    + incomplete + " · 保留 " + preserved + " · 待确认 " + review + " · 失败 " + failed;
        }
    }

    private LinearLayout card(LinearLayout column) {
        LinearLayout panel = vertical();
        panel.setPadding(dp(16), dp(14), dp(16), dp(13));
        panel.setBackground(shape(CARD, 18, EDGE));
        add(column, panel, 10);
        return panel;
    }

    private LinearLayout dialogContent() {
        LinearLayout content = vertical();
        content.setPadding(dp(22), dp(12), dp(22), dp(5));
        return content;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return view;
    }

    private EditText field(String hint, int type) {
        EditText input = new EditText(this);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(14);
        input.setInputType(type);
        input.setHint(hint);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setBackground(shape(PAPER, 10, EDGE));
        return input;
    }

    private Button button(String name, int fill, int foreground) {
        Button button = new Button(this);
        button.setText(name);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setLetterSpacing(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setStateListAnimator(null);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
                new int[] {0xff74877a, foreground}));
        RippleDrawable background = new RippleDrawable(ColorStateList.valueOf(0x290e291b), shape(fill, 12, 0), null);
        button.setBackground(background);
        return button;
    }

    private Switch option(String description, boolean value) {
        Switch toggle = new Switch(this);
        toggle.setText(description);
        toggle.setTextColor(INK);
        toggle.setTextSize(14);
        toggle.setMinHeight(dp(43));
        toggle.setSwitchPadding(dp(12));
        toggle.setChecked(value);
        return toggle;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2);
        margin.topMargin = dp(top);
        parent.addView(child, margin);
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
