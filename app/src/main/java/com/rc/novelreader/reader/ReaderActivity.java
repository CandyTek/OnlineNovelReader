package com.rc.novelreader.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.browser.BrowserActivity;
import com.rc.novelreader.data.Book;
import com.rc.novelreader.data.Chapter;
import com.rc.novelreader.data.Db;
import com.rc.novelreader.data.Events;
import com.rc.novelreader.data.Library;
import com.rc.novelreader.shelf.DownloadService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native reading screen. */
public class ReaderActivity extends Activity implements PageView.Host, Events.Listener {
    private static final String EXTRA_BOOK = "book", EXTRA_CHAPTER = "chapter";

    private long bookId;
    private Book book;
    private List<Chapter> chapters = new ArrayList<>();
    private final LruCache<Long, String> memory = new LruCache<>(8);
    private final Set<Long> loading = new HashSet<>();

    private PageView page;
    private View topBar, bottomBar, displayPanel, tocPanel;
    private TextView bookTitle, sourceUrl, btnShelf, btnNight, seekTip, tocInfo;
    private SeekBar seek;
    private ListView tocList;
    private BaseAdapter tocAdapter;
    private boolean tocReversed, opened, refreshingToc;
    private int savedIdx = -1, savedOffset = -1;

    public static void start(Context c, long bookId, int chapterIdx) {
        c.startActivity(new Intent(c, ReaderActivity.class).putExtra(EXTRA_BOOK, bookId).putExtra(EXTRA_CHAPTER, chapterIdx));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ReaderPrefs.load();
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        setContentView(R.layout.activity_reader);
        bookId = getIntent().getLongExtra(EXTRA_BOOK, -1);
        book = Db.book(bookId);
        if (book == null) {
            finish();
            return;
        }
        page = findViewById(R.id.page);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        displayPanel = findViewById(R.id.displayPanel);
        tocPanel = findViewById(R.id.tocPanel);
        bookTitle = findViewById(R.id.bookTitle);
        sourceUrl = findViewById(R.id.sourceUrl);
        btnShelf = findViewById(R.id.btnShelf);
        btnNight = findViewById(R.id.btnNight);
        seekTip = findViewById(R.id.seekTip);
        tocInfo = findViewById(R.id.tocInfo);
        seek = findViewById(R.id.seek);
        tocList = findViewById(R.id.tocList);

        page.setHost(this);
        page.setLoadingText(getString(R.string.loading));
        page.applyPrefs();

        final View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop(), bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 30) {
                // bar sizes even while the bars are hidden, so the menus never jump
                android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            }
            topBar.setPadding(0, top, 0, 0);
            bottomBar.setPadding(0, 0, 0, bottom);
            displayPanel.setPadding(displayPanel.getPaddingLeft(), displayPanel.getPaddingTop(), displayPanel.getPaddingRight(), bottom + App.dp(10));
            findViewById(R.id.tocBody).setPadding(0, top, 0, bottom);
            int cutTop = 0;
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) cutTop = insets.getDisplayCutout().getSafeInsetTop();
            page.setSafeInsets(cutTop, 0);
            return insets;
        });

        setupMenus();
        setupDisplayPanel();
        setupToc();
        applyWindowPrefs();
        showSystemUi(false);

        chapters = Db.chapters(bookId);
        int want = getIntent().getIntExtra(EXTRA_CHAPTER, -1);
        if (state != null) want = -1;
        int idx = want >= 0 ? want : book.curIdx;
        int offset = idx == book.curIdx ? book.curOffset : 0;
        if (chapters.isEmpty()) {
            savedIdx = idx;
            savedOffset = offset;
            refreshToc(true, false);
        } else {
            openAt(Math.max(0, Math.min(idx, chapters.size() - 1)), offset);
        }
        bindBook();
        Events.add(this);
        registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
    }

    private void openAt(int idx, int offset) {
        opened = true;
        page.open(idx, offset);
        seek.setMax(Math.max(0, chapters.size() - 1));
        seek.setProgress(idx);
    }

    @Override
    protected void onPause() {
        saveProgress();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (book != null) {
            Events.remove(this);
            try {
                unregisterReceiver(battery);
                unregisterReceiver(tick);
            } catch (RuntimeException ignored) {
            }
        }
        super.onDestroy();
    }

    private void saveProgress() {
        if (book == null || !opened) return;
        Library.saveProgress(bookId, page.currentIdx(), page.currentOffset());
    }

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) page.setBattery(level * 100 / scale);
        }
    };
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            page.invalidate();
        }
    };

    // ------------------------------------------------------------------ PageView.Host

    @Override
    public int chapterCount() {
        return chapters.size();
    }

    @Override
    public String chapterTitle(int idx) {
        return idx >= 0 && idx < chapters.size() ? chapters.get(idx).displayTitle() : "";
    }

    @Override
    public void requestChapter(final int idx) {
        if (idx < 0 || idx >= chapters.size()) {
            App.MAIN.post(() -> page.setError(idx, ""));
            return;
        }
        final Chapter c = chapters.get(idx);
        final String hit = memory.get(c.id);
        if (hit != null) {
            App.MAIN.post(() -> deliver(c.id, hit, null));
            return;
        }
        if (!loading.add(c.id)) return;
        App.IO.execute(() -> {
            String text = null, error = null;
            try {
                text = Library.loadChapter(book, c, false);
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final String body = text, err = error;
            App.MAIN.post(() -> {
                loading.remove(c.id);
                if (body != null) memory.put(c.id, body);
                deliver(c.id, body, err);
            });
        });
    }

    /** Chapter indices can shift while a load is in flight, so results are matched by chapter id. */
    private void deliver(long chapterId, String text, String error) {
        if (isDestroyed()) return;
        for (Chapter c : chapters) {
            if (c.id != chapterId) continue;
            if (text != null) {
                c.cached = true;
                page.setChapter(c.idx, c.displayTitle(), text);
            } else {
                page.setError(c.idx, getString(R.string.load_failed, error == null ? "" : error));
            }
            if (tocPanel.getVisibility() == View.VISIBLE) tocAdapter.notifyDataSetChanged();
            return;
        }
    }

    @Override
    public void onCenterTap() {
        toggleMenu();
    }

    @Override
    public void onPosition(int idx, int pg, int pages, int offset) {
        if (seek.getProgress() != idx && !seekTracking) seek.setProgress(idx);
        if (idx != lastSavedIdx) {
            lastSavedIdx = idx;
            Library.saveProgress(bookId, idx, offset);
            bindBook();
        }
    }

    private int lastSavedIdx = -1;
    private boolean seekTracking;

    @Override
    public void onEdge(boolean end) {
        if (end && book.linked) {
            extendLinked();
            return;
        }
        App.toast(getString(end ? R.string.last_chapter : R.string.first_chapter));
        // maybe the author has published more; an extra request to the site, so opt-in
        if (end && !book.linked && App.prefs().getBoolean("check_at_end", false)) refreshToc(false, false);
    }

    /**
     * A book without a chapter list only knows the next chapter from the previous one's "next" link. When that
     * link was missing or an ad, the end of the list is not the end of the book: ask the last page again.
     */
    private void extendLinked() {
        if (refreshingToc) return;
        refreshingToc = true;
        showTip(getString(R.string.looking_for_next), 0);
        App.IO.execute(() -> {
            boolean grew = false;
            try {
                grew = Library.extendLinked(book);
            } catch (Throwable ignored) {
            }
            final boolean more = grew;
            App.MAIN.post(() -> {
                refreshingToc = false;
                seekTip.setVisibility(View.GONE);
                if (isDestroyed()) return;
                if (more) reloadChapters();
                else App.toast(getString(R.string.last_chapter));
            });
        });
    }

    @Override
    public boolean mayPreload(int idx) {
        if (idx < 0 || idx >= chapters.size()) return false;
        Chapter c = chapters.get(idx);
        return c.cached || memory.get(c.id) != null || App.prefs().getBoolean("preload_chapters", false);
    }

    // ------------------------------------------------------------------ book / chapter list

    private void bindBook() {
        bookTitle.setText(book.title);
        int idx = opened ? page.currentIdx() : 0;
        sourceUrl.setText(idx < chapters.size() ? chapters.get(idx).url : book.detailUrl);
        btnShelf.setVisibility(book.inShelf ? View.GONE : View.VISIBLE);
        page.setShowPercent(!book.linked);
        seek.setEnabled(!book.linked || chapters.size() > 2);
    }

    /** @param initial the book has no chapters yet; @param manual asked for from the contents drawer */
    private void refreshToc(final boolean initial, final boolean manual) {
        if (refreshingToc) return;
        refreshingToc = true;
        final int before = chapters.size();
        if (initial) showTip(getString(R.string.chapters_loading), 0);
        else if (manual) showTip(getString(R.string.toc_updating), 0);
        App.IO.execute(() -> {
            String error = null;
            try {
                Library.refreshToc(book);
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String err = error;
            App.MAIN.post(() -> {
                refreshingToc = false;
                seekTip.setVisibility(View.GONE);
                if (isDestroyed()) return;
                reloadChapters();
                if (manual) {
                    if (err != null) App.toast(getString(R.string.open_failed, err));
                    else if (chapters.size() <= before) App.toast(getString(R.string.no_updates));
                }
                if (initial && chapters.isEmpty()) {
                    App.toast(getString(R.string.open_failed, err == null ? "" : err));
                    finish();
                }
            });
        });
    }

    @Override
    public void onEvent(int type, long id) {
        if (id != bookId) return;
        if (type == Events.BOOK) reloadChapters();
        else if (tocPanel.getVisibility() == View.VISIBLE) reloadCachedFlags();
    }

    private void reloadCachedFlags() {
        List<Chapter> fresh = Db.chapters(bookId);
        if (fresh.size() == chapters.size()) {
            for (int i = 0; i < fresh.size(); i++) chapters.get(i).cached = fresh.get(i).cached;
        }
        tocAdapter.notifyDataSetChanged();
    }

    /** The chapter list changed in the database: swap it in without losing the reader's place. */
    private void reloadChapters() {
        Book fresh = Db.book(bookId);
        if (fresh == null) {
            finish();
            return;
        }
        List<Chapter> list = Db.chapters(bookId);
        long curId = -1;
        String curTitle = "";
        if (opened && page.currentIdx() < chapters.size()) {
            curId = chapters.get(page.currentIdx()).id;
            curTitle = chapters.get(page.currentIdx()).title;
        }
        boolean grewOnly = list.size() >= chapters.size();
        for (int i = 0; grewOnly && i < chapters.size(); i++) grewOnly = list.get(i).id == chapters.get(i).id;
        book = fresh;
        chapters = list;
        seek.setMax(Math.max(0, chapters.size() - 1));
        bindBook();
        tocAdapter.notifyDataSetChanged();
        updateTocInfo();
        if (chapters.isEmpty()) return;
        if (!opened) {
            openAt(Math.max(0, Math.min(savedIdx < 0 ? book.curIdx : savedIdx, chapters.size() - 1)), Math.max(0, savedOffset));
            return;
        }
        if (grewOnly) {
            page.chaptersChanged();
            return;
        }
        int idx = -1;
        for (Chapter c : chapters) {
            if (c.id == curId) {
                idx = c.idx;
                break;
            }
        }
        if (idx < 0 && !curTitle.isEmpty()) {
            for (Chapter c : chapters) {
                if (c.title.equals(curTitle)) {
                    idx = c.idx;
                    break;
                }
            }
        }
        if (idx >= 0) {
            page.reindex(idx);
            seek.setProgress(idx);
        } else {
            openAt(Math.min(book.curIdx, chapters.size() - 1), 0);
        }
    }

    private void jumpTo(int idx) {
        if (idx < 0 || idx >= chapters.size()) return;
        openAt(idx, 0);
        bindBook();
    }

    // ------------------------------------------------------------------ menus

    private boolean menuVisible() {
        return topBar.getVisibility() == View.VISIBLE || displayPanel.getVisibility() == View.VISIBLE
                || tocPanel.getVisibility() == View.VISIBLE;
    }

    private void toggleMenu() {
        if (menuVisible()) hideMenus();
        else {
            bindBook();
            btnNight.setText(ReaderPrefs.isNight() ? R.string.day : R.string.night);
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
            page.setMenuOpen(true);
            showSystemUi(true);
        }
    }

    private void hideMenus() {
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        displayPanel.setVisibility(View.GONE);
        tocPanel.setVisibility(View.GONE);
        page.setMenuOpen(false);
        showSystemUi(false);
    }

    @SuppressWarnings("deprecation")
    private void showSystemUi(boolean show) {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (show) c.show(android.view.WindowInsets.Type.systemBars());
            else c.hide(android.view.WindowInsets.Type.systemBars());
            return;
        }
        int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!show) f |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(f);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && book != null) showSystemUi(menuVisible());
    }

    private void showTip(String text, long hideAfterMs) {
        seekTip.setText(text);
        seekTip.setVisibility(View.VISIBLE);
        seekTip.removeCallbacks(hideTip);
        if (hideAfterMs > 0) seekTip.postDelayed(hideTip, hideAfterMs);
    }

    private final Runnable hideTip = () -> seekTip.setVisibility(View.GONE);

    private void setupMenus() {
        findViewById(R.id.btnBack).setOnClickListener(v -> onBackPressed());
        btnShelf.setOnClickListener(v -> {
            Library.setShelf(bookId, true);
            book.inShelf = true;
            btnShelf.setVisibility(View.GONE);
            App.toast(getString(R.string.added_to_shelf));
        });
        findViewById(R.id.btnReload).setOnClickListener(v -> {
            int idx = page.currentIdx();
            if (idx >= chapters.size()) return;
            final Chapter c = chapters.get(idx);
            memory.remove(c.id);
            hideMenus();
            App.IO.execute(() -> {
                String error = null;
                String text = null;
                try {
                    text = Library.loadChapter(book, c, true);
                } catch (Throwable t) {
                    error = t.getMessage();
                }
                final String body = text, err = error;
                App.MAIN.post(() -> {
                    if (body != null) memory.put(c.id, body);
                    deliver(c.id, body, err);
                });
            });
            showTip(getString(R.string.loading), 1200);
        });
        findViewById(R.id.btnWeb).setOnClickListener(v -> {
            int idx = page.currentIdx();
            String url = idx < chapters.size() ? chapters.get(idx).url : book.detailUrl;
            BrowserActivity.open(this, url);
        });
        findViewById(R.id.btnPrev).setOnClickListener(v -> {
            if (page.currentIdx() > 0) jumpTo(page.currentIdx() - 1);
            else App.toast(getString(R.string.first_chapter));
        });
        findViewById(R.id.btnNext).setOnClickListener(v -> {
            if (page.currentIdx() + 1 < chapters.size()) jumpTo(page.currentIdx() + 1);
            else onEdge(true);
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && progress < chapters.size()) {
                    showTip(chapters.get(progress).displayTitle() + "\n" + getString(R.string.chapter_of, progress + 1, chapters.size()), 0);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seekTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seekTracking = false;
                seekTip.setVisibility(View.GONE);
                if (s.getProgress() != page.currentIdx()) jumpTo(s.getProgress());
            }
        });
        findViewById(R.id.btnToc).setOnClickListener(v -> showToc());
        btnNight.setOnClickListener(v -> {
            if (ReaderPrefs.isNight()) ReaderPrefs.theme = ReaderPrefs.dayTheme;
            else ReaderPrefs.theme = ReaderPrefs.NIGHT_THEME;
            ReaderPrefs.save();
            page.applyPrefs();
            btnNight.setText(ReaderPrefs.isNight() ? R.string.day : R.string.night);
            bindDisplayPanel();
        });
        findViewById(R.id.btnDisplay).setOnClickListener(v -> {
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            bindDisplayPanel();
            displayPanel.setVisibility(View.VISIBLE);
        });
        findViewById(R.id.btnCache).setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(this, v);
            m.getMenu().add(0, 1, 0, R.string.cache_next_50);
            m.getMenu().add(0, 2, 0, R.string.cache_rest);
            m.getMenu().add(0, 3, 0, R.string.cache_all);
            m.setOnMenuItemClickListener(item -> {
                int from = item.getItemId() == 3 ? 0 : page.currentIdx();
                int count = item.getItemId() == 1 ? 50 : -1;
                if (!book.inShelf) {
                    Library.setShelf(bookId, true);
                    book.inShelf = true;
                }
                DownloadService.start(this, bookId, from, count);
                hideMenus();
                return true;
            });
            m.show();
        });
    }

    // ------------------------------------------------------------------ display settings

    private void applyWindowPrefs() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = ReaderPrefs.brightness < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : Math.max(0.01f, ReaderPrefs.brightness / 100f);
        getWindow().setAttributes(lp);
        if (ReaderPrefs.keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void changed() {
        ReaderPrefs.save();
        page.applyPrefs();
        applyWindowPrefs();
        bindDisplayPanel();
    }

    private void setupDisplayPanel() {
        SeekBar bright = findViewById(R.id.brightSeek);
        bright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                ReaderPrefs.brightness = Math.max(1, progress);
                applyWindowPrefs();
                findViewById(R.id.brightSystem).setSelected(false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                ReaderPrefs.save();
            }
        });
        findViewById(R.id.brightSystem).setOnClickListener(v -> {
            ReaderPrefs.brightness = ReaderPrefs.brightness < 0 ? 50 : -1;
            changed();
        });
        click(R.id.fontMinus, () -> ReaderPrefs.textSp = Math.max(12, ReaderPrefs.textSp - 1));
        click(R.id.fontPlus, () -> ReaderPrefs.textSp = Math.min(40, ReaderPrefs.textSp + 1));
        click(R.id.lineMinus, () -> ReaderPrefs.linePct = Math.max(110, ReaderPrefs.linePct - 10));
        click(R.id.linePlus, () -> ReaderPrefs.linePct = Math.min(250, ReaderPrefs.linePct + 10));
        click(R.id.marginMinus, () -> ReaderPrefs.marginDp = Math.max(8, ReaderPrefs.marginDp - 4));
        click(R.id.marginPlus, () -> ReaderPrefs.marginDp = Math.min(48, ReaderPrefs.marginDp + 4));
        click(R.id.modeCover, () -> ReaderPrefs.mode = PageView.COVER);
        click(R.id.modeSlide, () -> ReaderPrefs.mode = PageView.SLIDE);
        click(R.id.modeNone, () -> ReaderPrefs.mode = PageView.NONE);
        click(R.id.modeScroll, () -> ReaderPrefs.mode = PageView.SCROLL);
        click(R.id.optKeepOn, () -> ReaderPrefs.keepOn = !ReaderPrefs.keepOn);
        click(R.id.optVolume, () -> ReaderPrefs.volumeKeys = !ReaderPrefs.volumeKeys);

        LinearLayout row = findViewById(R.id.themeRow);
        for (int i = 0; i < ReaderPrefs.THEMES.length; i++) {
            final int theme = i;
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, App.dp(34), 1);
            lp.setMargins(App.dp(6), 0, App.dp(6), 0);
            row.addView(dot, lp);
            dot.setOnClickListener(v -> {
                ReaderPrefs.theme = theme;
                if (theme < ReaderPrefs.NIGHT_THEME) ReaderPrefs.dayTheme = theme;
                changed();
            });
        }
    }

    private void click(int id, final Runnable change) {
        findViewById(id).setOnClickListener(v -> {
            change.run();
            changed();
        });
    }

    private void bindDisplayPanel() {
        ((TextView) findViewById(R.id.fontValue)).setText(String.valueOf(ReaderPrefs.textSp));
        ((TextView) findViewById(R.id.lineValue)).setText(String.format(java.util.Locale.US, "%.1f", ReaderPrefs.linePct / 100f));
        ((TextView) findViewById(R.id.marginValue)).setText(String.valueOf(ReaderPrefs.marginDp));
        findViewById(R.id.modeCover).setSelected(ReaderPrefs.mode == PageView.COVER);
        findViewById(R.id.modeSlide).setSelected(ReaderPrefs.mode == PageView.SLIDE);
        findViewById(R.id.modeNone).setSelected(ReaderPrefs.mode == PageView.NONE);
        findViewById(R.id.modeScroll).setSelected(ReaderPrefs.mode == PageView.SCROLL);
        findViewById(R.id.optKeepOn).setSelected(ReaderPrefs.keepOn);
        findViewById(R.id.optVolume).setSelected(ReaderPrefs.volumeKeys);
        findViewById(R.id.brightSystem).setSelected(ReaderPrefs.brightness < 0);
        ((SeekBar) findViewById(R.id.brightSeek)).setProgress(ReaderPrefs.brightness < 0 ? 50 : ReaderPrefs.brightness);
        LinearLayout row = findViewById(R.id.themeRow);
        for (int i = 0; i < row.getChildCount(); i++) {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(App.dp(17));
            d.setColor(ReaderPrefs.THEMES[i][0]);
            d.setStroke(App.dp(i == ReaderPrefs.theme ? 2 : 1), i == ReaderPrefs.theme ? 0xFF6FBFAE : 0x55FFFFFF);
            row.getChildAt(i).setBackground(d);
        }
    }

    // ------------------------------------------------------------------ contents

    private int tocPos(int idx) {
        return tocReversed ? chapters.size() - 1 - idx : idx;
    }

    private void updateTocInfo() {
        int cached = 0;
        for (Chapter c : chapters) if (c.cached) cached++;
        tocInfo.setText(getString(R.string.cached_info, chapters.size(), cached));
    }

    private void setupToc() {
        tocAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return chapters.size();
            }

            @Override
            public Object getItem(int position) {
                return chapters.get(tocPos(position));
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View v, ViewGroup parent) {
                TextView t = (TextView) v;
                if (t == null) {
                    t = new TextView(ReaderActivity.this);
                    t.setPadding(App.dp(18), App.dp(13), App.dp(18), App.dp(13));
                    t.setTextSize(14.5f);
                    t.setSingleLine(true);
                    t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                }
                Chapter c = chapters.get(tocPos(position));
                t.setText(c.displayTitle());
                boolean current = opened && c.idx == page.currentIdx();
                t.setTextColor(current ? 0xFF6FBFAE : c.cached ? 0xFFE8ECE9 : 0xFF8C9792);
                return t;
            }
        };
        tocList.setAdapter(tocAdapter);
        tocList.setOnItemClickListener((p, v, pos, id) -> {
            hideMenus();
            jumpTo(tocPos(pos));
        });
        findViewById(R.id.tocScrim).setOnClickListener(v -> hideMenus());
        findViewById(R.id.tocRefresh).setOnClickListener(v -> refreshToc(false, true));
        findViewById(R.id.tocReverse).setOnClickListener(v -> {
            tocReversed = !tocReversed;
            v.setSelected(tocReversed);
            tocAdapter.notifyDataSetChanged();
            tocList.setSelection(Math.max(0, tocPos(page.currentIdx()) - 3));
        });
    }

    private void showToc() {
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        ((TextView) findViewById(R.id.tocTitle)).setText(book.title);
        findViewById(R.id.tocRefresh).setVisibility(book.tocUrl.isEmpty() ? View.GONE : View.VISIBLE);
        reloadCachedFlags();
        updateTocInfo();
        tocPanel.setVisibility(View.VISIBLE);
        tocList.setSelection(Math.max(0, tocPos(page.currentIdx()) - 3));
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (ReaderPrefs.volumeKeys && !menuVisible()
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            page.turn(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            toggleMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (ReaderPrefs.volumeKeys && !menuVisible()
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (displayPanel.getVisibility() == View.VISIBLE || tocPanel.getVisibility() == View.VISIBLE) {
            hideMenus();
            return;
        }
        if (!book.inShelf && opened) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.add_shelf_prompt, book.title))
                    .setPositiveButton(R.string.add, (d, w) -> {
                        Library.setShelf(bookId, true);
                        finish();
                    })
                    .setNegativeButton(R.string.not_now, (d, w) -> finish())
                    .show();
            return;
        }
        super.onBackPressed();
    }
}
