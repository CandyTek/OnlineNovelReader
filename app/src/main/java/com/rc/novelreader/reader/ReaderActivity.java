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
import com.rc.novelreader.net.FetchError;
import com.rc.novelreader.net.PageFetcher;
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
    private boolean autoOn, autoPaused;
    private View autoPanel;
    private TextView autoValue, autoToggle;
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

    /** Back to the book read last: the reader that is open, or a new one. @return false when there is no such book */
    public static boolean continueLast(Context c) {
        if (bringToFront(c)) return true;
        long id = App.prefs().getLong("reader_last", -1);
        if (id < 0 || Db.book(id) == null) return false;
        start(c, id, -1);
        return true;
    }

    private static boolean resumeChecked;

    /**
     * A process that dies with the reader in front never reaches onPause: a crash, or the system killing every
     * app that uses WebView at the moment it updates WebView. The first screen of the next process goes back
     * to the book instead of leaving the reader on the shelf.
     */
    public static void resumeAfterKill(Activity a) {
        resumeOnStart(a, false, false);
    }

    /**
     * The same, plus the "continue reading on start" switch for the shelf: started from the launcher it goes
     * straight to the book read last. A shelf the system rebuilt counts only as the first screen of a process
     * (the app was dropped in the background); one rebuilt under a living reader or browser is just the shelf.
     * Not after a death in the reader, which is decided above, perhaps against reopening; and the switch
     * counts as a reopening there, so a reader that dies on opening leaves the shelf reachable next time.
     */
    public static void resumeOnStart(Activity a, boolean launcher, boolean rebuilt) {
        boolean first = !resumeChecked;
        resumeChecked = true;
        android.content.SharedPreferences p = App.prefs();
        long now = System.currentTimeMillis();
        long id = first ? p.getLong("reader_front", -1) : -1;
        if (id >= 0) {
            // a reader that dies on opening must not lock the user out of the shelf
            boolean again = now - p.getLong("reader_resumed_at", 0) < 60000;
            p.edit().remove("reader_front").putLong("reader_resumed_at", now).apply();
            if (again || Db.book(id) == null) return;
            start(a, id, -1);
            App.toast(a.getString(R.string.resumed_after_kill));
            return;
        }
        if (!launcher || (rebuilt && !first) || !p.getBoolean("resume_reading", false)) return;
        id = p.getLong("reader_last", -1);
        if (id < 0 || Db.book(id) == null) return;
        p.edit().putLong("reader_resumed_at", now).apply();
        if (!bringToFront(a)) start(a, id, -1);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        resumeChecked = true;
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
        page.setLoadingText(getString(R.string.loading), getString(R.string.waited));
        // an imported book has no page to open
        page.setErrorLabels(getString(R.string.retry), book.local() ? null : getString(R.string.err_open_web), getString(R.string.verify));
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
            autoPanel.setPadding(App.dp(14), App.dp(12), App.dp(14), bottom + App.dp(10));
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
        App.prefs().edit().putLong("reader_front", bookId).putLong("reader_last", bookId).apply();
        long now = System.currentTimeMillis();
        // time away from the book longer than a break counts as one
        if (lastSeen > 0 && now - lastSeen >= ReaderPrefs.restFor * 60000L) readMs = 0;
        tickAt = now;
        App.MAIN.removeCallbacks(restTick);
        restTick.run();
        keepAwake();
        lastTouch = now;
        inFront = true;
        if (ReplaceActivity.changed) {
            // the replacements were edited on their own screen
            ReplaceActivity.changed = false;
            reprocess();
        }
        // the settings can be opened from the book, so the switch is read on every return
        page.setAutoRetry(!book.local() && App.prefs().getBoolean("auto_retry", true), getString(R.string.retry_in));
        applySchedule(false);
        listenForShake(ReaderPrefs.shake);
    }

    @Override
    protected void onPause() {
        App.MAIN.removeCallbacks(savePosition);
        inFront = false;
        App.prefs().edit().remove("reader_front").apply();
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
        if (book == null || !opened || page.currentIdx() >= chapters.size()) return;
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
            // the page after the last chapter of a book without a chapter list: the reader wants the book to go on
            if (idx == chapters.size() && hasTail()) App.MAIN.post(this::seekNext);
            else App.MAIN.post(() -> page.setError(idx, "", false));
            return;
        }
        final Chapter c = chapters.get(idx);
        final String hit = memory.get(c.id);
        if (hit != null) {
            App.MAIN.post(() -> deliver(c.id, hit, null));
            return;
        }
        load(c, false, null);
    }

    /**
     * Gets a chapter's text off the main thread and hands it to the page.
     * @param force fetch it from the site even when it is cached
     * @param keep  the text that was on screen before a forced fetch: it comes back when the fetch fails
     */
    private void load(final Chapter c, final boolean force, final String keep) {
        // one fetch per chapter at a time; whoever asks second gets the same answer
        if (!loading.add(c.id)) return;
        final Book b = book;
        App.IO.execute(() -> {
            String text = null;
            Throwable error = null;
            // the site was really asked (text from the device passes no stage)
            final boolean[] asked = {false};
            PageFetcher.watch((stage, attempt) -> {
                asked[0] = true;
                App.MAIN.post(() -> {
                if (isDestroyed() || !loading.contains(c.id)) return;
                for (Chapter at : chapters) {
                    if (at.id != c.id) continue;
                    page.setLoadingDetail(at.idx, stageText(stage, attempt));
                    // its text is shown already and the reader waits on the page after it
                    if (seekAfter == c.id) page.setLoadingDetail(at.idx + 1, getString(R.string.looking_for_next) + "\n" + stageText(stage, attempt));
                }
                });
            });
            final String[] soon = new String[1];
            try {
                text = display(c, Library.loadChapter(b, c, force, raw -> {
                    // The text is here, and the loader goes on to find out where the book continues (many seconds
                    // in a rendering engine): the reader gets the chapter now instead of "loading" until then.
                    final String shown = display(c, raw);
                    soon[0] = shown;
                    App.MAIN.post(() -> {
                        if (isDestroyed() || shown == null || !loading.contains(c.id)) return;
                        memory.put(c.id, shown);
                        for (Chapter at : chapters) if (at.id == c.id && at != c && at.title.isEmpty()) at.title = c.title;
                        deliver(c.id, shown, null);
                    });
                }));
            } catch (Throwable t) {
                error = t;
            } finally {
                PageFetcher.watch(null);
            }
            final String body = text;
            final Throwable err = error;
            final String early = soon[0];
            App.MAIN.post(() -> {
                loading.remove(c.id);
                if (isDestroyed()) return;
                if (body != null) memory.put(c.id, body);
                else if (early != null) {
                    // only the search for the next chapter failed: the text stays
                } else if (keep != null) {
                    // refreshing failed: the reader keeps what was there and is told why nothing changed
                    memory.put(c.id, keep);
                    deliver(c.id, keep, null);
                    App.toast(getString(R.string.reload_failed, explain(err)[0]));
                    return;
                }
                // loading may have named the chapter while the list was swapped for a newer copy
                for (Chapter at : chapters) if (at.id == c.id && at != c && at.title.isEmpty()) at.title = c.title;
                if (early == null || (body != null && !body.equals(early))) deliver(c.id, body, err);
                // The last chapter of a book without a chapter list was just read from the site, and its page
                // named no next one: that answer is good for the page after it, should the reader turn there.
                if (body != null && asked[0] && hasTail() && !chapters.isEmpty() && chapters.get(chapters.size() - 1).id == c.id) {
                    endKnown = c.id;
                    endKnownAt = android.os.SystemClock.elapsedRealtime();
                }
                if (seekAfter == c.id) {
                    // the reader is on the page after this chapter, waiting for what this load found out
                    seekAfter = -1;
                    for (Chapter at : chapters) {
                        if (at.id == c.id) {
                            endSeek(at.idx + 1, body == null ? err : null);
                            break;
                        }
                    }
                }
            });
        });
    }

    /** What went wrong: {in words anybody understands, the technical detail}. */
    String[] explain(Throwable t) {
        int msg = R.string.err_other;
        String detail = t == null ? "" : t.getMessage() == null ? t.toString() : t.getMessage();
        if (t instanceof FetchError) {
            FetchError f = (FetchError) t;
            if (f.kind == FetchError.HTTP) {
                msg = f.code == 404 || f.code == 410 ? R.string.err_not_found
                        : f.code == 401 || f.code == 403 || f.code == 429 ? R.string.err_refused
                        : f.code >= 500 ? R.string.err_server : R.string.err_other;
            } else if (f.kind == FetchError.OFFSITE) msg = R.string.err_ad;
            else if (f.kind == FetchError.CAPTCHA) msg = R.string.err_captcha;
            else if (f.kind == FetchError.ERROR_PAGE) msg = R.string.err_error_page;
            else msg = R.string.err_no_text;
        } else if (t instanceof java.net.SocketTimeoutException) msg = R.string.err_timeout;
        else if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                || t instanceof java.net.NoRouteToHostException || t instanceof javax.net.ssl.SSLException
                || detail.startsWith("net::")) msg = R.string.err_network;
        return new String[]{getString(msg), detail};
    }

    /** The message of a failed chapter: what happened in plain words, and smaller the detail for those who want it. */
    private CharSequence errorText(Throwable t) {
        String[] e = explain(t);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(getString(R.string.load_failed));
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
        sb.append("\n").append(e[0]);
        if (!e[1].isEmpty()) {
            int from = sb.length();
            sb.append("\n").append(e[1]);
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.8f), from, sb.length(), 0);
        }
        return sb;
    }

    private String stageText(int stage, int attempt) {
        switch (stage) {
            case PageFetcher.Watcher.CONNECT:
                return getString(R.string.stage_connect);
            case PageFetcher.Watcher.RENDER:
                return getString(R.string.stage_render);
            case PageFetcher.Watcher.RETRY:
                return getString(R.string.stage_retry, attempt, PageFetcher.TRIES);
            case PageFetcher.Watcher.FIND_NEXT:
                return getString(R.string.stage_next);
            default:
                return "";
        }
    }

    /** Shows the steps of the fetches made on the calling (background) thread under a tip that is on screen. */
    private void watchTip(final String base) {
        PageFetcher.watch((stage, attempt) -> App.MAIN.post(() -> {
            if (!isDestroyed() && refreshingToc) showTip(base + "\n" + stageText(stage, attempt), 0);
        }));
    }

    @Override
    public void onOpenWeb(int idx) {
        openWeb(idx);
    }

    /**
     * The site wants a person: its page is shown in a window over the book, the reader does what it asks, and
     * the chapter is asked for again when the window closes (from the device when the window saw it arrive).
     */
    @Override
    public void onVerify(final int idx) {
        // from the page after the last chapter: the last chapter's page is the one that was refused
        int at = idx >= chapters.size() ? chapters.size() - 1 : idx;
        if (at < 0) return;
        VerifyWindow.show(this, chapters.get(at).url, true, () -> {
            if (!isDestroyed()) page.retry(idx);
        });
    }

    /**
     * Repeating a failed chapter by itself is only for somebody who is reading: the reader in front, no break,
     * and a touch in the last ten minutes (or the book turning its own pages, or being read aloud).
     */
    @Override
    public boolean mayAutoRetry() {
        return inFront && !isDestroyed() && !book.local() && !resting()
                && (autoOn || speaker.active() || System.currentTimeMillis() - lastTouch < 600000);
    }

    private boolean inFront;

    private void openWeb(int idx) {
        // from the page after the last chapter: the last chapter's page is where the site would show the way on
        if (idx >= chapters.size() && !chapters.isEmpty()) idx = chapters.size() - 1;
        String url = idx >= 0 && idx < chapters.size() ? chapters.get(idx).url : book.detailUrl;
        BrowserActivity.open(this, url);
    }

    /** Chapter indices can shift while a load is in flight, so results are matched by chapter id. */
    private void deliver(long chapterId, String text, Throwable error) {
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
                page.setError(c.idx, errorText(error), true, FetchError.captcha(error));
                if (current && speaker.active()) speaker.stop();
            }
            if (tocPanel.getVisibility() == View.VISIBLE) tocAdapter.notifyDataSetChanged();
            return;
        }
    }

    @Override
    public void onCenterTap() {
        // while the book reads by itself the menu zone brings up its controls; the menu is one tap further
        if (autoOn && !menuVisible()) autoPanel.setVisibility(autoPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        else toggleMenu();
    }

    @Override
    public void onPosition(int idx, int pg, int pages, int offset) {
        // the page after the last chapter is no place in the book
        if (idx >= chapters.size()) return;
        // text passed since the last position, for the statistics; jumps are not reading
        if (idx == statIdx && offset > statOffset && offset - statOffset < 4000) pendingChars += offset - statOffset;
        statIdx = idx;
        statOffset = offset;
        if (seek.getProgress() != idx && !seekTracking) seek.setProgress(idx);
        if (idx != lastSavedIdx) {
            lastSavedIdx = idx;
            Library.saveProgress(bookId, idx, offset);
            bindBook();
        } else {
            // the place inside the chapter too, once the page has rested: a killed process never reaches onPause
            App.MAIN.removeCallbacks(savePosition);
            App.MAIN.postDelayed(savePosition, 1500);
        }
    }

    private final Runnable savePosition = new Runnable() {
        @Override
        public void run() {
            if (!isDestroyed()) saveProgress();
        }
    };

    private int lastSavedIdx = -1;
    private boolean seekTracking;

    @Override
    public void onEdge(boolean end) {
        stopAuto();
        if (end && hasTail()) {
            // the page after the last chapter has its own button; no request for every further tap
            if (page.currentIdx() >= chapters.size()) return;
            // the last chapter known did not load: its page is the one that names the next, so ask for it again
            if (page.isError()) page.retry();
            else if (!page.isLoading()) openTail();
            return;
        }
        App.toast(getString(end ? R.string.last_chapter : R.string.first_chapter));
        // maybe the author has published more; an extra request to the site, so opt-in
        if (end && !book.linked && App.prefs().getBoolean("check_at_end", false)) refreshToc(false, false);
    }

    /** A book without a chapter list ends in a page of its own, where the search for the next chapter is shown. */
    private boolean hasTail() {
        return book.linked && !book.local();
    }

    private void openTail() {
        if (speaker.active()) speakFollow = true;
        openAt(chapters.size(), 0);
        bindBook();
    }

    private boolean seeking;
    /** Id of the last chapter when the site has just said that nothing follows it, and when it said so. */
    private long endKnown = -1, endKnownAt;
    /** Id of the last chapter while its own load is still asking the site where the book goes on, else -1. */
    private long seekAfter = -1;

    /**
     * A book without a chapter list only knows the next chapter from the previous one's "next" link. When that
     * link was missing or an ad, the end of the list is not the end of the book: ask the last page again. Called
     * when the page view turns to the place after the last chapter, and everything is reported on that page.
     */
    private void seekNext() {
        if (isDestroyed() || seeking) return;
        final int at = chapters.size();
        final String tip = getString(R.string.looking_for_next);
        final Chapter last = chapters.isEmpty() ? null : chapters.get(at - 1);
        page.setLoadingDetail(at, tip);
        if (last != null && loading.contains(last.id)) {
            // that very question is being asked right now: no second request, the answer comes with that load
            seekAfter = last.id;
            return;
        }
        if (last != null && endKnown == last.id && android.os.SystemClock.elapsedRealtime() - endKnownAt < 600000) {
            // asked minutes ago, while loading that chapter: the same answer without the same request.
            // Once only: the Retry button on this page is a new question.
            endKnown = -1;
            endSeek(at, null);
            return;
        }
        seeking = true;
        final Book b = book;
        App.IO.execute(() -> {
            Throwable error = null;
            PageFetcher.watch((stage, attempt) -> App.MAIN.post(() -> {
                if (!isDestroyed() && seeking) page.setLoadingDetail(at, tip + "\n" + stageText(stage, attempt));
            }));
            try {
                Library.extendLinked(b);
            } catch (Throwable t) {
                error = t;
            } finally {
                PageFetcher.watch(null);
            }
            // The search fetches the last chapter's page again, so its stored text (and title) may no longer be
            // what this screen holds: without this the old text stayed until the book was opened again.
            String now = null;
            try {
                now = last == null ? null : display(last, Library.cachedText(last));
            } catch (RuntimeException ignored) {
            }
            final String text = now;
            final Throwable err = error;
            App.MAIN.post(() -> {
                seeking = false;
                if (isDestroyed()) return;
                boolean changed = text != null && !text.equals(memory.get(last.id));
                if (changed) {
                    memory.put(last.id, text);
                    noteRanges.remove(last.id);
                }
                reloadChapters();
                if (changed && page.holds(at - 1)) deliver(last.id, text, null);
                endSeek(at, err);
            });
        });
    }

    /** The search for the chapter with index at is over: the book has it now, or the page there says why not. */
    private void endSeek(int at, Throwable err) {
        if (at < chapters.size()) {
            if (page.holds(at)) requestChapter(at);
            bindBook();
            return;
        }
        if (speaker.waiting()) speaker.stop();
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (err != null) {
            // "last chapter" only when the site was really asked and had nothing more; a failure is a failure
            String[] e = explain(err);
            sb.append(getString(R.string.next_failed, e[0]));
            int from = sb.length();
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, from, 0);
            if (!e[1].isEmpty()) {
                sb.append("\n").append(e[1]);
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.8f), from, sb.length(), 0);
            }
        } else {
            sb.append(getString(R.string.last_chapter));
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
            sb.append("\n").append(getString(R.string.no_next_yet));
        }
        page.setError(at, sb, err != null, FetchError.captcha(err));
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
        page.setTail(hasTail());
        seek.setEnabled(!book.linked || chapters.size() > 2);
    }

    /** @param initial the book has no chapters yet; @param manual asked for from the contents drawer */
    private void refreshToc(final boolean initial, final boolean manual) {
        if (refreshingToc) return;
        refreshingToc = true;
        final int before = chapters.size();
        final String tip = initial ? getString(R.string.chapters_loading) : manual ? getString(R.string.toc_updating) : null;
        if (tip != null) showTip(tip, 0);
        final TextView chip = findViewById(R.id.tocRefresh);
        chip.setText(R.string.refreshing);
        chip.setEnabled(false);
        App.IO.execute(() -> {
            Throwable error = null;
            if (tip != null) watchTip(tip);
            try {
                Library.refreshToc(book);
            } catch (Throwable t) {
                error = t;
            } finally {
                PageFetcher.watch(null);
            }
            final Throwable err = error;
            App.MAIN.post(() -> {
                refreshingToc = false;
                seekTip.setVisibility(View.GONE);
                if (isDestroyed()) return;
                chip.setText(R.string.refresh);
                chip.setEnabled(true);
                reloadChapters();
                if (manual) {
                    // the list is behind a human check: here it is, and Refresh works once it is passed
                    if (FetchError.captcha(err) && !book.tocUrl.isEmpty()) VerifyWindow.show(ReaderActivity.this, book.tocUrl, false, null);
                    if (err != null) App.toast(getString(R.string.toc_failed, explain(err)[0]));
                    else if (chapters.size() <= before) App.toast(getString(R.string.no_updates));
                }
                if (initial && chapters.isEmpty()) {
                    App.toast(getString(R.string.open_failed, err == null ? "" : explain(err)[0]));
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
        final int was = chapters.size();
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
            // The place after the last chapter has become a chapter without anybody waiting for the answer here
            // (it was found by a load that began earlier): the page there must not stay as it is.
            if (chapters.size() > was && was > 0 && page.holds(was) && !seeking && seekAfter < 0) requestChapter(was);
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

    /** Adds a view that floats over the page (and its warm tint) but stays under the menus and panels. */
    void addOverPage(View v, FrameLayout.LayoutParams lp) {
        FrameLayout root = findViewById(R.id.root);
        root.addView(v, Math.min(2, root.getChildCount()), lp);
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
            hideMenus();
            // already on its way, and the page says so
            if (loading.contains(c.id)) return;
            // The text leaves the screen while the page is fetched again, so the wait and its outcome are visible;
            // if the fetch fails the old text comes back.
            String shown = memory.get(c.id);
            memory.remove(c.id);
            noteRanges.remove(c.id);
            if (speaker.active()) speaker.stop();
            page.setLoading(idx);
            load(c, true, shown);
        });
        findViewById(R.id.btnWeb).setOnClickListener(v -> openWeb(page.currentIdx()));
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

    /** Paged modes with the sweeping line switched off: the page is turned when its time is up. */
    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (!autoOn || ReaderPrefs.mode == PageView.SCROLL || ReaderPrefs.autoSweep) return;
            if (!autoPaused && !menuVisible() && !resting() && !page.hasSelection()) page.turn(true);
            App.MAIN.postDelayed(this, ReaderPrefs.autoSec * 1000L);
        }
    };

    void startAuto() {
        hideMenus();
        autoOn = true;
        autoPaused = false;
        keepAwake();
        applyAuto();
        App.toast(getString(ReaderPrefs.mode == PageView.SCROLL ? R.string.rt_auto_scroll_started : R.string.rd_auto_started));
    }

    /** Hands the current speed to the page: scroll mode moves the text, the paged modes sweep a line or turn. */
    private void applyAuto() {
        boolean scroll = ReaderPrefs.mode == PageView.SCROLL;
        App.MAIN.removeCallbacks(autoTick);
        page.setAuto(scroll ? App.dp(ReaderPrefs.autoSpeed) : 0, !scroll && ReaderPrefs.autoSweep ? ReaderPrefs.autoSec : 0);
        page.setAutoPaused(autoPaused);
        if (!scroll && !ReaderPrefs.autoSweep) App.MAIN.postDelayed(autoTick, ReaderPrefs.autoSec * 1000L);
        autoValue.setText(scroll ? String.valueOf(ReaderPrefs.autoSpeed) : getString(R.string.rd_auto_per_page, ReaderPrefs.autoSec));
        autoToggle.setText(autoPaused ? R.string.rd_resume : R.string.rd_pause);
    }

    private void autoFaster(boolean faster) {
        if (ReaderPrefs.mode == PageView.SCROLL) {
            ReaderPrefs.autoSpeed = Math.max(4, Math.min(300, ReaderPrefs.autoSpeed + (faster ? 4 : -4)));
        } else {
            ReaderPrefs.autoSec = Math.max(2, Math.min(120, ReaderPrefs.autoSec + (faster ? -1 : 1)));
        }
        applyAuto();
        if (autoPanel.getVisibility() != View.VISIBLE) showTip(getString(R.string.rd_auto_speed) + " " + autoValue.getText(), 900);
    }

    private void pauseAuto() {
        autoPaused = !autoPaused;
        applyAuto();
        if (autoPanel.getVisibility() != View.VISIBLE) showTip(getString(autoPaused ? R.string.rd_auto_paused : R.string.rd_auto_started), 900);
    }

    private void stopAuto() {
        if (!autoOn) return;
        autoOn = autoPaused = false;
        App.MAIN.removeCallbacks(autoTick);
        page.setAuto(0, 0);
        autoPanel.setVisibility(View.GONE);
        // the speed may have been changed while it ran
        ReaderPrefs.save();
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
                    && ((autoOn && !autoPaused) || now - lastTouch < 180000)) {
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

    private static final int SEL_COLS = 4;
    private int selRows = 3;

    private void buildOverlays() {
        FrameLayout root = findViewById(R.id.root);
        // warm tint over the page, under the menus
        eyeView = new View(this);
        eyeView.setVisibility(View.GONE);
        root.addView(eyeView, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // menu next to selected text: rows of four, wide enough for the labels of either language
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundResource(R.drawable.tip_bg);
        bar.setPadding(App.dp(4), 0, App.dp(4), 0);
        int[] labels = {R.string.rd_copy, R.string.rt_highlight, R.string.rt_note, R.string.rt_lookup, R.string.rd_speak_here,
                R.string.rd_search_short, R.string.rd_paragraph, R.string.rt_translate, R.string.rd_replace, R.string.rd_hide,
                R.string.share};
        // the numbers selectionAction knows the entries by
        final int[] ids = {0, 1, 2, 3, 4, 10, 7, 8, 5, 6, 9};
        LinearLayout line = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % SEL_COLS == 0) {
                line = new LinearLayout(this);
                bar.addView(line);
            }
            final int which = ids[i];
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(13.5f);
            t.setTextColor(0xFFFFFFFF);
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(App.dp(5), 0, App.dp(5), 0);
            t.setSingleLine(true);
            t.setMinWidth(App.dp(74));
            t.setOnClickListener(v -> selectionAction(which));
            line.addView(t, new LinearLayout.LayoutParams(0, App.dp(40), 1));
        }
        selRows = (labels.length + SEL_COLS - 1) / SEL_COLS;
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
        // controls of automatic reading; the text keeps moving under them so a new speed can be judged at once
        LinearLayout ap = new LinearLayout(this);
        ap.setOrientation(LinearLayout.VERTICAL);
        ap.setBackgroundResource(R.color.reader_panel);
        ap.setClickable(true);
        autoValue = stepRow(ap, R.string.rd_auto_speed, () -> autoFaster(false), () -> autoFaster(true));
        ((LinearLayout.LayoutParams) autoValue.getLayoutParams()).width = App.dp(92);
        row = panelRow(ap);
        autoToggle = panelChip(R.string.rd_pause, v -> pauseAuto());
        row.addView(autoToggle, lp);
        row.addView(panelChip(R.string.stop, v -> stopAuto()), lp);
        row.addView(panelChip(R.string.menu, v -> toggleMenu()), lp);
        autoPanel = ap;
        autoPanel.setVisibility(View.GONE);
        autoPanel.setPadding(App.dp(14), App.dp(12), App.dp(14), App.dp(10));
        root.addView(autoPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM));

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
        int h = App.dp(40) * selRows;
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
                page.growSelection();
                break;
            case 8:
                page.clearSelection();
                tools.translate(sel);
                break;
            case 10:
                // every place in the book where the selected words occur
                page.clearSelection();
                sheets.search(sel);
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
            } else if (hasTail()) {
                openTail();
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
            if (!t.paper.isEmpty() || !t.image.isEmpty()) {
                d.setOrientation(GradientDrawable.Orientation.TL_BR);
                d.setColors(new int[]{t.bg, android.graphics.Color.rgb(android.graphics.Color.red(t.bg) * 5 / 6,
                        android.graphics.Color.green(t.bg) * 5 / 6, android.graphics.Color.blue(t.bg) * 4 / 5)});
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

    private int keyTaken;

    /**
     * A keyboard (or a remote control). Taken before the views get the key: a button that happens to hold the
     * focus would use up Space and Enter, and the arrows would wander between views.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (book != null && e.getAction() == KeyEvent.ACTION_DOWN && readerKey(code, e)) {
            keyTaken = code;
            onUserInteraction();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP && code == keyTaken) {
            keyTaken = 0;
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean readerKey(int code, KeyEvent e) {
        if (e.isAltPressed() || e.isMetaPressed() || resting()) return false;
        boolean ctrl = e.isCtrlPressed(), first = e.getRepeatCount() == 0;
        if (!ctrl && (code == KeyEvent.KEYCODE_M || code == KeyEvent.KEYCODE_F10)) {
            if (first) toggleMenu();
            return true;
        }
        // with a menu up the keys belong to it (seek bar, lists, buttons)
        if (menuVisible() || !opened) return false;
        if (ctrl) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (first) onAction(code == KeyEvent.KEYCODE_DPAD_RIGHT ? ReaderPrefs.A_NEXT_CHAPTER : ReaderPrefs.A_PREV_CHAPTER);
                return true;
            }
            if (code == KeyEvent.KEYCODE_F || code == KeyEvent.KEYCODE_D) {
                if (first) onAction(code == KeyEvent.KEYCODE_F ? ReaderPrefs.A_SEARCH : ReaderPrefs.A_BOOKMARK);
                return true;
            }
            return false;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                page.turn(code == KeyEvent.KEYCODE_DPAD_RIGHT);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (ReaderPrefs.mode == PageView.SCROLL) page.nudge(code == KeyEvent.KEYCODE_DPAD_DOWN);
                else page.turn(code == KeyEvent.KEYCODE_DPAD_DOWN);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
                page.turn(code == KeyEvent.KEYCODE_PAGE_DOWN);
                return true;
            case KeyEvent.KEYCODE_SPACE:
                if (autoOn) {
                    if (first) pauseAuto();
                } else page.turn(!e.isShiftPressed());
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (page.isError()) {
                    if (first) page.retry();
                } else if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (first) toggleMenu();
                } else page.turn(true);
                return true;
            case KeyEvent.KEYCODE_LEFT_BRACKET:
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                if (first) onAction(code == KeyEvent.KEYCODE_RIGHT_BRACKET ? ReaderPrefs.A_NEXT_CHAPTER : ReaderPrefs.A_PREV_CHAPTER);
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME:
                if (first) jump(page.currentIdx(), 0);
                return true;
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_NUMPAD_ADD:
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                boolean bigger = code != KeyEvent.KEYCODE_MINUS && code != KeyEvent.KEYCODE_NUMPAD_SUBTRACT;
                ReaderPrefs.textSp = Math.max(12, Math.min(40, ReaderPrefs.textSp + (bigger ? 1 : -1)));
                changed();
                showTip(getString(R.string.font) + " " + ReaderPrefs.textSp, 900);
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                if (first) onBackPressed();
                return true;
            default:
                break;
        }
        int action = code == KeyEvent.KEYCODE_T ? ReaderPrefs.A_CONTENTS : code == KeyEvent.KEYCODE_N ? ReaderPrefs.A_NIGHT
                : code == KeyEvent.KEYCODE_F ? ReaderPrefs.A_SEARCH : code == KeyEvent.KEYCODE_B ? ReaderPrefs.A_BOOKMARK
                : code == KeyEvent.KEYCODE_S ? ReaderPrefs.A_SPEAK : code == KeyEvent.KEYCODE_A ? ReaderPrefs.A_AUTO : -1;
        if (action < 0) return false;
        if (first) onAction(action);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // while the book reads by itself the volume keys set its speed
        if (autoOn && !menuVisible() && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            autoFaster(keyCode == KeyEvent.KEYCODE_VOLUME_UP);
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
        if (autoOn && !menuVisible() && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true;
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
        if (autoPanel != null && autoPanel.getVisibility() == View.VISIBLE) {
            autoPanel.setVisibility(View.GONE);
            return;
        }
        if (!book.inShelf && opened) {
            String standing = App.prefs().getString("leave_unshelved", "ask");
            if ("add".equals(standing)) Library.setShelf(bookId, true);
            else if (!"skip".equals(standing)) {
                askShelf();
                return;
            }
        }
        super.onBackPressed();
    }

    /** Leaving a book that is not on the shelf. With the box ticked the answer given becomes the standing one. */
    private void askShelf() {
        final android.widget.CheckBox again = new android.widget.CheckBox(this);
        again.setText(R.string.dont_ask_again);
        again.setTextSize(14);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(App.dp(18), App.dp(6), App.dp(18), 0);
        box.addView(again);
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.add_shelf_prompt, book.title))
                .setView(box)
                .setPositiveButton(R.string.add, (d, w) -> {
                    if (again.isChecked()) App.prefs().edit().putString("leave_unshelved", "add").apply();
                    Library.setShelf(bookId, true);
                    finish();
                })
                .setNegativeButton(R.string.not_now, (d, w) -> {
                    if (again.isChecked()) {
                        App.prefs().edit().putString("leave_unshelved", "skip").apply();
                        App.toast(getString(R.string.dont_ask_note));
                    }
                    finish();
                })
                .show();
    }
}
