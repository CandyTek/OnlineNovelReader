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
import android.widget.FrameLayout;
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

    static final int REQ_FONT = 11, REQ_IMAGE = 12;

    long bookId;
    Book book;
    List<Chapter> chapters = new ArrayList<>();
    private final LruCache<Long, String> memory = new LruCache<>(8);
    private final Set<Long> loading = new HashSet<>();

    PageView page;
    private ReaderSheets sheets;
    ReaderTools tools;
    Notes notes;
    /** Highlights per chapter id as the page view wants them; dropped whenever text or notes change. */
    private final java.util.HashMap<Long, int[]> noteRanges = new java.util.HashMap<>();
    private long lastTouch, pendingChars;
    private int statIdx = -1, statOffset;
    private float brightAcc;
    private android.hardware.SensorManager sensors;
    private long lastShake;
    private Speaker speaker;
    private final com.rc.novelreader.sniff.FillerFilter filler = new com.rc.novelreader.sniff.FillerFilter();
    private List<ReaderPrefs.Theme> themeList = new ArrayList<>();
    private View eyeView, restView, selBar, speechPanel;
    private TextView restText, speakToggle, speakTimer, rateValue, pitchValue;
    /** The user moved to another place while the book is read aloud: the voice follows once the text is there. */
    private boolean speakFollow;
    private boolean autoOn;
    private long restUntil, tickAt;
    /** Reading time since the last break; kept across reader sessions of one app run. */
    private static long readMs, lastSeen;
    private View topBar, bottomBar, displayPanel, tocPanel;
    private TextView bookTitle, sourceUrl, btnShelf, btnNight, seekTip, tocInfo;
    private SeekBar seek;
    private ListView tocList;
    private BaseAdapter tocAdapter;
    private boolean tocReversed, opened, refreshingToc;
    private int savedIdx = -1, savedOffset = -1;

    private static java.lang.ref.WeakReference<ReaderActivity> live;

    /** Shows the reader that is already open, if there is one. */
    public static boolean bringToFront(Context c) {
        ReaderActivity r = live == null ? null : live.get();
        if (r == null || r.isDestroyed() || r.isFinishing()) return false;
        c.startActivity(new Intent(c, ReaderActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        return true;
    }

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
        sheets = new ReaderSheets(this);
        tools = new ReaderTools(this, sheets);
        sheets.tools = tools;
        notes = new Notes(bookId);
        speaker = new Speaker(this, speechHost);
        restUntil = App.prefs().getLong("r_rest_until", 0);
        buildOverlays();

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
            speechPanel.setPadding(App.dp(14), App.dp(12), App.dp(14), bottom + App.dp(10));
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
        live = new java.lang.ref.WeakReference<>(this);
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
    protected void onResume() {
        super.onResume();
        if (book == null) return;
        long now = System.currentTimeMillis();
        // time away from the book longer than a break counts as one
        if (lastSeen > 0 && now - lastSeen >= ReaderPrefs.restFor * 60000L) readMs = 0;
        tickAt = now;
        App.MAIN.removeCallbacks(restTick);
        restTick.run();
        keepAwake();
        lastTouch = now;
        applySchedule(false);
        listenForShake(ReaderPrefs.shake);
    }

    @Override
    protected void onPause() {
        saveProgress();
        ReaderPrefs.save();
        Stats.flush();
        listenForShake(false);
        lastSeen = System.currentTimeMillis();
        App.MAIN.removeCallbacks(restTick);
        App.MAIN.removeCallbacks(screenOff);
        stopAuto();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (book != null) {
            speaker.release();
            App.MAIN.removeCallbacks(restTick);
            App.MAIN.removeCallbacks(screenOff);
            App.MAIN.removeCallbacks(autoTick);
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
            applySchedule(false);
        }
    };

    // ------------------------------------------------------------------ PageView.Host

    @Override
    public int chapterCount() {
        return chapters.size();
    }

    @Override
    public String chapterTitle(int idx) {
        return idx >= 0 && idx < chapters.size() ? titleOf(chapters.get(idx)) : "";
    }

    /** The chapter's heading as shown (script conversion applies to it too). */
    String titleOf(Chapter c) {
        String t = c.displayTitle();
        return ReaderPrefs.convert == 0 ? t : ReaderTools.hanzi().convert(t, ReaderPrefs.convert);
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
                text = display(c, Library.loadChapter(book, c, false));
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
            boolean current = opened && c.idx == page.currentIdx();
            if (text != null) {
                c.cached = true;
                noteRanges.remove(c.id);
                page.setChapter(c.idx, titleOf(c), text);
                if (current && speaker.active() && (speaker.waiting() || speakFollow)) {
                    int from = speaker.waiting() ? 0 : page.currentOffset();
                    speakFollow = false;
                    speaker.speak(c.idx, titleOf(c), text, from);
                }
            } else {
                page.setError(c.idx, getString(R.string.load_failed, error == null ? "" : error));
                if (current && speaker.active()) speaker.stop();
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
        // text passed since the last position, for the statistics; jumps are not reading
        if (idx == statIdx && offset > statOffset && offset - statOffset < 4000) pendingChars += offset - statOffset;
        statIdx = idx;
        statOffset = offset;
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
        stopAuto();
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
                if (more) {
                    reloadChapters();
                    // the reader asked for the next chapter: go there instead of waiting for a second tap
                    if (page.currentIdx() + 1 < chapters.size()) jumpTo(page.currentIdx() + 1);
                } else {
                    App.toast(getString(R.string.last_chapter));
                    if (speaker.waiting()) speaker.stop();
                }
            });
        });
    }

    @Override
    public void onAction(int action) {
        switch (action) {
            case ReaderPrefs.A_PREV_CHAPTER:
                if (page.currentIdx() > 0) jumpTo(page.currentIdx() - 1);
                else App.toast(getString(R.string.first_chapter));
                break;
            case ReaderPrefs.A_NEXT_CHAPTER:
                if (page.currentIdx() + 1 < chapters.size()) jumpTo(page.currentIdx() + 1);
                else onEdge(true);
                break;
            case ReaderPrefs.A_CONTENTS:
                if (!menuVisible()) toggleMenu();
                showToc();
                break;
            case ReaderPrefs.A_NIGHT:
                switchNight();
                break;
            case ReaderPrefs.A_BOOKMARK:
                sheets.addBookmark();
                break;
            case ReaderPrefs.A_SPEAK:
                toggleSpeech();
                break;
            case ReaderPrefs.A_AUTO:
                if (autoOn) stopAuto();
                else startAuto();
                break;
            case ReaderPrefs.A_SEARCH:
                sheets.search();
                break;
            case ReaderPrefs.A_BACK:
                onBackPressed();
                break;
            default:
                break;
        }
    }

    private void toggleSpeech() {
        if (!speaker.active()) startSpeech(page.currentOffset());
        else if (speaker.paused()) speaker.resume();
        else speaker.pause();
    }

    private void switchNight() {
        ReaderPrefs.toggleNight();
        ReaderPrefs.save();
        page.applyPrefs();
        btnNight.setText(ReaderPrefs.isNight() ? R.string.day : R.string.night);
        bindDisplayPanel();
    }

    @Override
    public void onBrightnessDrag(float delta) {
        if (ReaderPrefs.brightness < 0) {
            int sys = 128;
            try {
                sys = android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS);
            } catch (Exception ignored) {
            }
            ReaderPrefs.brightness = Math.max(1, Math.min(100, sys * 100 / 255));
            brightAcc = 0;
        }
        // the whole height of the screen is the whole range; saved when the reader is left
        brightAcc += delta * 100;
        int step = (int) brightAcc;
        if (step == 0) return;
        brightAcc -= step;
        ReaderPrefs.brightness = Math.max(1, Math.min(100, ReaderPrefs.brightness + step));
        applyWindowPrefs();
        showTip(getString(R.string.brightness) + " " + ReaderPrefs.brightness + "%", 900);
    }

    @Override
    public int[] notes(int idx) {
        if (idx < 0 || idx >= chapters.size()) return null;
        Chapter c = chapters.get(idx);
        int[] r = noteRanges.get(c.id);
        if (r == null) {
            String text = memory.get(c.id);
            if (text == null) return null;
            r = notes.ranges(c.id, text);
            noteRanges.put(c.id, r);
        }
        return r;
    }

    @Override
    public void onNoteTap(int idx, int start) {
        if (idx < 0 || idx >= chapters.size()) return;
        Notes.Note n = notes.find(chapters.get(idx).id, start);
        if (n != null) tools.editNote(n, false);
    }

    void notesChanged() {
        noteRanges.clear();
        page.invalidate();
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
        // an imported book has no page to load again or to open
        findViewById(R.id.btnReload).setVisibility(book.local() ? View.GONE : View.VISIBLE);
        findViewById(R.id.btnWeb).setVisibility(book.local() ? View.GONE : View.VISIBLE);
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
        jump(idx, 0);
    }

    void jump(int idx, int offset) {
        if (idx < 0 || idx >= chapters.size()) return;
        if (speaker.active()) speakFollow = true;
        openAt(idx, offset);
        bindBook();
    }

    // ------------------------------------------------------------------ shown text

    /** What the reader sees of a chapter: the fetched text minus filler, with the user's replacements. */
    String display(Chapter c, String raw) {
        if (raw == null) return null;
        String t = ReaderPrefs.filler ? filler.strip(c.id, raw) : raw;
        t = TextRules.apply(bookId, t);
        return ReaderPrefs.convert == 0 ? t : ReaderTools.hanzi().convert(t, ReaderPrefs.convert);
    }

    /** The text of a chapter as it is shown, null when it is not loaded. */
    String shownText(int idx) {
        return idx >= 0 && idx < chapters.size() ? memory.get(chapters.get(idx).id) : null;
    }

    /** The filter or the replacements changed: typeset the open chapter again. */
    void reprocess() {
        memory.evictAll();
        noteRanges.clear();
        if (!opened) return;
        int idx = page.currentIdx(), off = page.currentOffset();
        if (speaker.active()) speaker.stop();
        openAt(idx, off);
    }

    /** Marks a passage for a few seconds (a search hit). */
    void flash(final int idx, int a, int b) {
        page.setHighlight(idx, a, b);
        page.postDelayed(() -> {
            if (!speaker.active()) page.setHighlight(-1, 0, 0);
        }, 6000);
    }

    // ------------------------------------------------------------------ menus

    private boolean menuVisible() {
        return topBar.getVisibility() == View.VISIBLE || displayPanel.getVisibility() == View.VISIBLE
                || tocPanel.getVisibility() == View.VISIBLE || speechPanel.getVisibility() == View.VISIBLE;
    }

    private void toggleMenu() {
        if (menuVisible()) hideMenus();
        else {
            stopAuto();
            page.clearSelection();
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
        speechPanel.setVisibility(View.GONE);
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
                    text = display(c, Library.loadChapter(book, c, true));
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
        btnNight.setOnClickListener(v -> switchNight());
        findViewById(R.id.btnDisplay).setOnClickListener(v -> {
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            bindDisplayPanel();
            displayPanel.setVisibility(View.VISIBLE);
        });
        findViewById(R.id.btnSpeak).setOnClickListener(v -> {
            if (!speaker.active() && !startSpeech(page.currentOffset())) return;
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            bindSpeech();
            speechPanel.setVisibility(View.VISIBLE);
        });
        findViewById(R.id.btnMore).setOnClickListener(v -> {
            hideMenus();
            sheets.more();
        });
    }

    void cacheMenu() {
        String[] items = {getString(R.string.cache_next_50), getString(R.string.cache_rest), getString(R.string.cache_all)};
        new AlertDialog.Builder(this).setTitle(R.string.cache).setItems(items, (d, w) -> {
            int from = w == 2 ? 0 : page.currentIdx();
            int count = w == 0 ? 50 : -1;
            if (!book.inShelf) {
                Library.setShelf(bookId, true);
                book.inShelf = true;
            }
            DownloadService.start(this, bookId, from, count);
        }).show();
    }

    // ------------------------------------------------------------------ display settings

    @SuppressWarnings("SourceLockedOrientationActivity")
    void applyWindowPrefs() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = ReaderPrefs.brightness < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : Math.max(0.01f, ReaderPrefs.brightness / 100f);
        getWindow().setAttributes(lp);
        keepAwake();
        int want = ReaderPrefs.orient == 1 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ReaderPrefs.orient == 2 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (getRequestedOrientation() != want) setRequestedOrientation(want);
        if (eyeView != null) {
            eyeView.setVisibility(ReaderPrefs.eye > 0 ? View.VISIBLE : View.GONE);
            eyeView.setBackgroundColor(android.graphics.Color.argb(ReaderPrefs.eye * 2, 255, 150, 40));
        }
    }

    void changed() {
        ReaderPrefs.save();
        page.applyPrefs();
        applyWindowPrefs();
        bindDisplayPanel();
        listenForShake(ReaderPrefs.shake);
    }

    /**
     * Night theme by the clock. Acts only when the schedule crosses a boundary (or was just edited), so a theme
     * the reader picks by hand stays until the next boundary.
     */
    void applySchedule(boolean edited) {
        if (ReaderPrefs.nightFrom < 0) return;
        int want = ReaderPrefs.scheduledNight() ? 1 : 0;
        if (!edited && App.prefs().getInt("r_sched_state", -1) == want) return;
        App.prefs().edit().putInt("r_sched_state", want).apply();
        if ((want == 1) != ReaderPrefs.isNight()) switchNight();
    }

    private final android.hardware.SensorEventListener shake = new android.hardware.SensorEventListener() {
        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            float g = android.hardware.SensorManager.GRAVITY_EARTH;
            double force = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / g;
            long now = System.currentTimeMillis();
            if (force < 2.4 || now - lastShake < 1500 || resting()) return;
            lastShake = now;
            toggleSpeech();
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor s, int accuracy) {
        }
    };

    private void listenForShake(boolean on) {
        if (sensors == null) sensors = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensors == null) return;
        sensors.unregisterListener(shake);
        android.hardware.Sensor acc = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        if (on && acc != null) sensors.registerListener(shake, acc, android.hardware.SensorManager.SENSOR_DELAY_UI);
    }

    void themesChanged() {
        rebuildThemes();
        changed();
    }

    // ------------------------------------------------------------------ screen timer, auto page, breaks

    private final Runnable screenOff = () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    /** Keeps the screen on for the chosen time after the last touch; automatic page turning counts as reading. */
    private void keepAwake() {
        App.MAIN.removeCallbacks(screenOff);
        if (ReaderPrefs.screenMin == 0 && !autoOn) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (ReaderPrefs.screenMin > 0 && !autoOn) App.MAIN.postDelayed(screenOff, ReaderPrefs.screenMin * 60000L);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        lastTouch = System.currentTimeMillis();
        if (book != null) keepAwake();
    }

    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (!autoOn || ReaderPrefs.mode == PageView.SCROLL) return;
            if (!menuVisible() && !resting() && !page.hasSelection()) page.turn(true);
            App.MAIN.postDelayed(this, ReaderPrefs.autoSec * 1000L);
        }
    };

    void startAuto() {
        hideMenus();
        autoOn = true;
        keepAwake();
        App.MAIN.removeCallbacks(autoTick);
        // scroll mode moves the text steadily; the paged modes turn a page every so many seconds
        if (ReaderPrefs.mode == PageView.SCROLL) page.setAutoScroll(App.dp(ReaderPrefs.autoSpeed));
        else App.MAIN.postDelayed(autoTick, ReaderPrefs.autoSec * 1000L);
        App.toast(getString(ReaderPrefs.mode == PageView.SCROLL ? R.string.rt_auto_scroll_started : R.string.rd_auto_started));
    }

    private void stopAuto() {
        if (!autoOn) return;
        autoOn = false;
        App.MAIN.removeCallbacks(autoTick);
        page.setAutoScroll(0);
        keepAwake();
        App.toast(getString(R.string.rd_auto_stopped));
    }

    private boolean resting() {
        return restView != null && restView.getVisibility() == View.VISIBLE;
    }

    private final Runnable restTick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            // statistics: time in front of the text, not in a break, not listening, and not long after the last touch
            long step = now - tickAt;
            if (step > 0 && step < 5000 && !resting() && !(speaker.active() && !speaker.paused())
                    && (autoOn || now - lastTouch < 180000)) {
                Stats.add(bookId, step, pendingChars);
                pendingChars = 0;
            }
            if (restUntil > now) {
                long left = (restUntil - now + 999) / 1000;
                restText.setText(getString(R.string.rd_rest_now, String.format(java.util.Locale.US, "%d:%02d", left / 60, left % 60)));
                if (!resting()) {
                    stopAuto();
                    hideMenus();
                    page.clearSelection();
                    restView.setVisibility(View.VISIBLE);
                }
                readMs = 0;
            } else {
                if (resting()) {
                    restView.setVisibility(View.GONE);
                    restUntil = 0;
                    App.prefs().edit().remove("r_rest_until").apply();
                    readMs = 0;
                } else if (ReaderPrefs.restEvery <= 0) {
                    readMs = 0;
                } else {
                    // listening is not reading; a long gap between ticks means the screen was off
                    long d = now - tickAt;
                    if (d < 5000 && !(speaker.active() && !speaker.paused())) readMs += d;
                    if (readMs >= ReaderPrefs.restEvery * 60000L) {
                        restUntil = now + ReaderPrefs.restFor * 60000L;
                        // survives leaving the reader or restarting the app
                        App.prefs().edit().putLong("r_rest_until", restUntil).apply();
                    }
                }
            }
            tickAt = now;
            App.MAIN.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ overlays built in code

    private TextView panelChip(int text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(android.view.Gravity.CENTER);
        t.setTextSize(13);
        t.setTextColor(0xFFE8ECE9);
        t.setBackgroundResource(R.drawable.chip_bg);
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout panelRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, App.dp(46)));
        return row;
    }

    private TextView stepRow(LinearLayout parent, int label, final Runnable minus, final Runnable plus) {
        LinearLayout row = panelRow(parent);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(0xFF8C9792);
        row.addView(l, new LinearLayout.LayoutParams(App.dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, App.dp(36), 1);
        lp.setMargins(App.dp(4), 0, App.dp(4), 0);
        row.addView(panelChip(R.string.rd_minus, v -> minus.run()), lp);
        TextView value = new TextView(this);
        value.setGravity(android.view.Gravity.CENTER);
        value.setTextSize(14);
        value.setTextColor(0xFFE8ECE9);
        row.addView(value, new LinearLayout.LayoutParams(App.dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(panelChip(R.string.rd_plus, v -> plus.run()), lp);
        return value;
    }

    private void buildOverlays() {
        FrameLayout root = findViewById(R.id.root);
        // warm tint over the page, under the menus
        eyeView = new View(this);
        eyeView.setVisibility(View.GONE);
        root.addView(eyeView, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // menu next to selected text, two rows
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundResource(R.drawable.tip_bg);
        bar.setPadding(App.dp(4), 0, App.dp(4), 0);
        int[] labels = {R.string.rd_copy, R.string.rt_highlight, R.string.rt_note, R.string.rt_lookup, R.string.rd_speak_here,
                R.string.rd_replace, R.string.rd_hide, R.string.rd_paragraph, R.string.rt_translate, R.string.share};
        LinearLayout line = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 5 == 0) {
                line = new LinearLayout(this);
                bar.addView(line);
            }
            final int which = i;
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(13.5f);
            t.setTextColor(0xFFFFFFFF);
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(App.dp(6), 0, App.dp(6), 0);
            t.setMinWidth(App.dp(60));
            t.setOnClickListener(v -> selectionAction(which));
            line.addView(t, new LinearLayout.LayoutParams(0, App.dp(42), 1));
        }
        selBar = bar;
        selBar.setVisibility(View.GONE);
        root.addView(selBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL));

        // read-aloud controls
        LinearLayout sp = new LinearLayout(this);
        sp.setOrientation(LinearLayout.VERTICAL);
        sp.setBackgroundResource(R.color.reader_panel);
        sp.setClickable(true);
        LinearLayout row = panelRow(sp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, App.dp(36), 1);
        lp.setMargins(App.dp(4), 0, App.dp(4), 0);
        speakToggle = panelChip(R.string.rd_pause, v -> {
            if (!speaker.active()) startSpeech(page.currentOffset());
            else if (speaker.paused()) speaker.resume();
            else speaker.pause();
        });
        row.addView(speakToggle, lp);
        row.addView(panelChip(R.string.stop, v -> {
            speaker.stop();
            hideMenus();
        }), lp);
        speakTimer = panelChip(R.string.rd_sleep_timer, v -> sheets.sleepTimer(speaker));
        row.addView(speakTimer, lp);
        row.addView(panelChip(R.string.rd_voice, v -> sheets.voiceMenu()), lp);
        rateValue = stepRow(sp, R.string.rd_rate, () -> tune(-10, 0), () -> tune(10, 0));
        pitchValue = stepRow(sp, R.string.rd_pitch, () -> tune(0, -10), () -> tune(0, 10));
        speechPanel = sp;
        speechPanel.setVisibility(View.GONE);
        speechPanel.setPadding(App.dp(14), App.dp(12), App.dp(14), App.dp(10));
        root.addView(speechPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM));

        // forced break: covers everything and swallows touches
        FrameLayout rest = new FrameLayout(this);
        rest.setBackgroundColor(0xFF101413);
        rest.setClickable(true);
        restText = new TextView(this);
        restText.setTextColor(0xFFB9C2BD);
        restText.setTextSize(19);
        restText.setGravity(android.view.Gravity.CENTER);
        restText.setLineSpacing(0, 1.5f);
        restText.setPadding(App.dp(32), 0, App.dp(32), 0);
        rest.addView(restText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER));
        restView = rest;
        restView.setVisibility(View.GONE);
        root.addView(restView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ------------------------------------------------------------------ selection

    @Override
    public void onSelection(boolean active) {
        if (!active || !page.hasSelection()) {
            selBar.setVisibility(View.GONE);
            return;
        }
        android.graphics.RectF r = page.selectionBounds();
        int h = App.dp(84);
        float y = r.top - h - App.dp(10);
        if (y < App.dp(28)) y = Math.min(r.bottom + App.dp(10), page.getHeight() - h - App.dp(28));
        selBar.setY(y);
        selBar.setVisibility(View.VISIBLE);
    }

    @Override
    public void onSelectionLongPress() {
        String sel = page.selectedText();
        if (!sel.isEmpty()) sheets.editRule(null, sel);
    }

    private Notes.Note noteFromSelection() {
        int idx = page.currentIdx();
        Notes.Note n = new Notes.Note();
        n.chapterId = chapters.get(idx).id;
        n.idx = idx;
        n.a = page.selectionStart();
        n.b = page.selectionEnd();
        n.text = page.selectedText();
        n.chapterTitle = titleOf(chapters.get(idx));
        n.color = ReaderPrefs.noteColor;
        n.at = System.currentTimeMillis();
        return n;
    }

    private void selectionAction(int which) {
        final String sel = page.selectedText();
        if (sel.isEmpty() || page.currentIdx() >= chapters.size()) return;
        switch (which) {
            case 0:
                ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(android.content.ClipData.newPlainText("text", sel));
                App.toast(getString(R.string.rd_copied));
                page.clearSelection();
                break;
            case 1: {
                Notes.Note n = noteFromSelection();
                page.clearSelection();
                notes.all().add(n);
                notes.save();
                notesChanged();
                break;
            }
            case 2: {
                Notes.Note n = noteFromSelection();
                page.clearSelection();
                tools.editNote(n, true);
                break;
            }
            case 3: {
                String context = tools.sentenceAround(page.currentIdx(), page.selectionStart(), page.selectionEnd());
                page.clearSelection();
                tools.lookup(sel, context);
                break;
            }
            case 4: {
                int from = page.selectionStart();
                page.clearSelection();
                startSpeech(Math.max(1, from));
                break;
            }
            case 5:
                sheets.editRule(null, sel);
                break;
            case 6: {
                // leave the passage out from now on; the rule can be removed under "Text replacements"
                TextRules.Rule r = new TextRules.Rule();
                r.find = sel;
                r.book = bookId;
                TextRules.all().add(r);
                TextRules.save();
                reprocess();
                App.toast(getString(R.string.rd_hidden));
                break;
            }
            case 7:
                page.selectParagraphs();
                break;
            case 8:
                page.clearSelection();
                tools.translate(sel);
                break;
            default:
                page.clearSelection();
                try {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, sel), getString(R.string.share)));
                } catch (RuntimeException ignored) {
                }
        }
    }

    // ------------------------------------------------------------------ reading aloud

    /** @return false when the chapter's text is not there yet */
    private boolean startSpeech(int offset) {
        int idx = page.currentIdx();
        String text = shownText(idx);
        if (text == null) {
            App.toast(getString(R.string.loading));
            return false;
        }
        speakFollow = false;
        speaker.speak(idx, titleOf(chapters.get(idx)), text, offset);
        return true;
    }

    private void tune(int rate, int pitch) {
        ReaderPrefs.ttsRate = Math.max(30, Math.min(400, ReaderPrefs.ttsRate + rate));
        ReaderPrefs.ttsPitch = Math.max(50, Math.min(200, ReaderPrefs.ttsPitch + pitch));
        ReaderPrefs.save();
        bindSpeech();
        speaker.retune();
    }

    void bindSpeech() {
        speakToggle.setText(!speaker.active() ? R.string.rd_start : speaker.paused() ? R.string.rd_resume : R.string.rd_pause);
        int left = speaker.sleepLeft();
        speakTimer.setText(left == 0 ? getString(R.string.rd_sleep_timer) : getString(R.string.rd_minutes, left));
        speakTimer.setSelected(left > 0);
        rateValue.setText(String.format(java.util.Locale.US, "%.1fx", ReaderPrefs.ttsRate / 100f));
        pitchValue.setText(String.format(java.util.Locale.US, "%.1f", ReaderPrefs.ttsPitch / 100f));
    }

    private final Speaker.Host speechHost = new Speaker.Host() {
        @Override
        public void onSpeakPassage(int idx, int a, int b) {
            page.setHighlight(idx, a, b);
            if (b > a) page.reveal(idx, a);
        }

        @Override
        public void onSpeakProgress(int idx, int offset) {
            page.reveal(idx, offset);
        }

        @Override
        public void onSpeakChapterEnd(int idx) {
            if (isDestroyed()) return;
            if (idx + 1 < chapters.size()) {
                openAt(idx + 1, 0);
                bindBook();
            } else if (book.linked) {
                if (page.currentIdx() != idx) openAt(idx, 0);
                extendLinked();
            } else {
                App.toast(getString(R.string.last_chapter));
                speaker.stop();
            }
        }

        @Override
        public void onSpeakState() {
            if (isDestroyed()) return;
            if (!speaker.active()) page.setHighlight(-1, 0, 0);
            bindSpeech();
        }

        @Override
        public void onSpeakError(int reason) {
            App.toast(getString(reason == Speaker.NO_ENGINE ? R.string.rd_tts_no_engine
                    : reason == Speaker.ONLINE_FAILED ? R.string.rt_voice_stopped : R.string.rd_tts_no_voice));
        }
    };

    // ------------------------------------------------------------------ picking files

    void pick(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(request == REQ_IMAGE ? "image/*" : "*/*");
        try {
            startActivityForResult(i, request);
        } catch (RuntimeException e) {
            try {
                startActivityForResult(i.setAction(Intent.ACTION_GET_CONTENT), request);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == REQ_FONT) sheets.importFont(data.getData());
        else if (request == REQ_IMAGE) sheets.importImage(data.getData());
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
        click(R.id.modeCover, () -> ReaderPrefs.mode = PageView.COVER);
        click(R.id.modeSlide, () -> ReaderPrefs.mode = PageView.SLIDE);
        click(R.id.modeNone, () -> ReaderPrefs.mode = PageView.NONE);
        click(R.id.modeScroll, () -> ReaderPrefs.mode = PageView.SCROLL);
        click(R.id.modeCurl, () -> ReaderPrefs.mode = PageView.CURL);
        click(R.id.optVolume, () -> ReaderPrefs.volumeKeys = !ReaderPrefs.volumeKeys);
        findViewById(R.id.optFont).setOnClickListener(v -> sheets.fonts());
        findViewById(R.id.optLayout).setOnClickListener(v -> {
            hideMenus();
            sheets.layout();
        });
        rebuildThemes();
    }

    /** One dot per theme; the user's own can be edited with a long press, "+" makes a new one. */
    private void rebuildThemes() {
        LinearLayout row = findViewById(R.id.themeRow);
        row.removeAllViews();
        themeList = ReaderPrefs.themes();
        for (final ReaderPrefs.Theme t : themeList) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(App.dp(46), App.dp(34));
            lp.setMargins(App.dp(5), 0, App.dp(5), 0);
            row.addView(dot, lp);
            dot.setOnClickListener(v -> {
                ReaderPrefs.select(t);
                changed();
            });
            dot.setOnLongClickListener(v -> {
                if (!t.custom()) return false;
                sheets.editTheme(t);
                return true;
            });
        }
        TextView plus = new TextView(this);
        plus.setText(R.string.rd_plus);
        plus.setGravity(android.view.Gravity.CENTER);
        plus.setTextColor(0xFFE8ECE9);
        plus.setTextSize(18);
        plus.setBackgroundResource(R.drawable.chip_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(App.dp(46), App.dp(34));
        lp.setMargins(App.dp(5), 0, App.dp(5), 0);
        row.addView(plus, lp);
        plus.setOnClickListener(v -> sheets.editTheme(null));
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
        findViewById(R.id.modeCover).setSelected(ReaderPrefs.mode == PageView.COVER);
        findViewById(R.id.modeSlide).setSelected(ReaderPrefs.mode == PageView.SLIDE);
        findViewById(R.id.modeNone).setSelected(ReaderPrefs.mode == PageView.NONE);
        findViewById(R.id.modeScroll).setSelected(ReaderPrefs.mode == PageView.SCROLL);
        findViewById(R.id.modeCurl).setSelected(ReaderPrefs.mode == PageView.CURL);
        findViewById(R.id.optVolume).setSelected(ReaderPrefs.volumeKeys);
        findViewById(R.id.brightSystem).setSelected(ReaderPrefs.brightness < 0);
        ((SeekBar) findViewById(R.id.brightSeek)).setProgress(ReaderPrefs.brightness < 0 ? 50 : ReaderPrefs.brightness);
        LinearLayout row = findViewById(R.id.themeRow);
        for (int i = 0; i < themeList.size() && i < row.getChildCount(); i++) {
            ReaderPrefs.Theme t = themeList.get(i);
            boolean on = t.key.equals(ReaderPrefs.themeKey);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(App.dp(17));
            // a texture or picture shows as a shaded dot
            if (t.texture != 0 || !t.image.isEmpty()) {
                d.setOrientation(GradientDrawable.Orientation.TL_BR);
                d.setColors(new int[]{t.bg, android.graphics.Color.rgb(android.graphics.Color.red(t.bg) * 3 / 4,
                        android.graphics.Color.green(t.bg) * 3 / 4, android.graphics.Color.blue(t.bg) * 2 / 3)});
            } else d.setColor(t.bg);
            d.setStroke(App.dp(on ? 2 : 1), on ? 0xFF6FBFAE : 0x55FFFFFF);
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
        // while the text scrolls by itself the volume keys set its speed
        if (autoOn && ReaderPrefs.mode == PageView.SCROLL && !menuVisible()
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            ReaderPrefs.autoSpeed = Math.max(4, Math.min(300, ReaderPrefs.autoSpeed + (keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 4 : -4)));
            page.setAutoScroll(App.dp(ReaderPrefs.autoSpeed));
            showTip(getString(R.string.rt_auto_speed) + " " + ReaderPrefs.autoSpeed, 900);
            return true;
        }
        if (ReaderPrefs.volumeKeys && !menuVisible() && !(speaker.active() && !speaker.paused())
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (!resting()) page.turn(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU && !resting()) {
            toggleMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (autoOn && ReaderPrefs.mode == PageView.SCROLL && !menuVisible()
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true;
        if (ReaderPrefs.volumeKeys && !menuVisible() && !(speaker.active() && !speaker.paused())
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (displayPanel.getVisibility() == View.VISIBLE || tocPanel.getVisibility() == View.VISIBLE
                || speechPanel.getVisibility() == View.VISIBLE) {
            hideMenus();
            return;
        }
        if (page.hasSelection()) {
            page.clearSelection();
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
