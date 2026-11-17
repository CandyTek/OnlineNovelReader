package com.rc.novelreader.reader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The reading surface. Holds three chapter slots (previous, current, next) so page turns and
 * scrolling cross chapter boundaries without a pause, and renders them either as pages
 * (cover / slide / instant turn) or as one continuous vertical scroll.
 */
public class PageView extends View {
    public static final int COVER = 0, SLIDE = 1, NONE = 2, SCROLL = 3, CURL = 4;

    public interface Host {
        int chapterCount();

        String chapterTitle(int idx);

        /** Must answer asynchronously with {@link #setChapter} or {@link #setError}. */
        void requestChapter(int idx);

        /** The chapter on screen changed, or started, finished or failed loading (see {@link #isError}, {@link #isLoading}). */
        void onChapterState();

        /** Whether the chapter may be requested before the reader gets to it (it is cached, or preloading is on). */
        boolean mayPreload(int idx);

        void onCenterTap();

        void onPosition(int idx, int page, int pages, int offset);

        /** The user tried to go before the first or past the last chapter. */
        void onEdge(boolean end);

        /** A text selection was made, changed (active) or dropped. */
        void onSelection(boolean active);

        /** Long press on the selected text. */
        void onSelectionLongPress();

        /** A tap zone or gesture asked for one of the {@link ReaderPrefs} actions the view cannot do itself. */
        void onAction(int action);

        /** The finger moved along the brightness strip: change in brightness, as a fraction of the full range. */
        void onBrightnessDrag(float delta);

        /** The reader's highlights in chapter idx as {start, end, colour} triples, or null. */
        int[] notes(int idx);

        /** A highlight was tapped; start is the first number of its triple. */
        void onNoteTap(int idx, int start);
    }

    private static final int LOADING = 0, READY = 1, ERROR = 2;

    private static final class Slot {
        int idx;
        int state = LOADING;
        String title = "", text;
        /** ERROR: what went wrong. LOADING: what the loader is doing right now (may be empty). */
        CharSequence message = "";
        /** When loading began, for the "waited so long" line. */
        long since = android.os.SystemClock.uptimeMillis();
        ChapterLayout layout;
        /** Entered backwards: show the end of the chapter once it is loaded. */
        boolean fromEnd;
    }

    private Host host;
    private Slot prev, cur, next;
    private int page;
    private int pendingOffset = -1;
    private boolean menuOpen;

    // appearance
    private int mode = COVER, bg, fg, marginL, marginR, extraTop, extraBottom;
    private float lineMult = 1.5f, paraMult = 1f;
    private boolean indent = true, header = true, footer = true;
    private int[] zones = ReaderPrefs.ZONES_DEFAULT;
    private Bitmap bgBitmap;
    private final Rect bgSrc = new Rect(), bgDst = new Rect();
    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint infoPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final float density;
    private String loadingText = "…", waitedText = "%d s";
    private final Paint spinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF spinBox = new RectF();
    private boolean showPercent = true;
    private int battery = -1;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.US);
    private final GradientDrawable shadow = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0x50000000, 0x00000000});

    // geometry
    private int safeTop, safeBottom, contentTop, contentBottom, contentW;
    /** Columns per page (two in landscape when the setting allows) and the space between them. */
    private int cols = 1, gutter;

    // gestures
    private int[] gestures = ReaderPrefs.GESTURES_DEFAULT;
    private boolean edgeBright, ruler;
    private boolean touching, vert, brightDrag, rulerDrag;
    private Runnable pendingTap;
    /** Centre of the reading ruler's clear band; 0 until first shown. */
    private float rulerY;
    private final Paint notePaint = new Paint();

    // paged-mode gesture state
    private int dragDir;           // +1 towards next page, -1 towards previous
    private float dragDx;
    private Slot targetSlot;
    private int targetPage;
    private ValueAnimator animator;
    private boolean animCommit;

    // scroll-mode state
    private float scrollY;
    private final OverScroller scroller;
    private int lastFlingY;
    private long lastEdgeAt;

    // touch
    private final int slop, minFling, maxFling;
    private float downX, downY, lastY;
    private boolean moved, blocked;
    private VelocityTracker velocity;

    // text selection: chapter offsets [selA, selB) in selSlot; the anchor is what the long press picked
    private Slot selSlot;
    private int selA, selB, anchorA, anchorB;
    /** 1 or 2 while the start or end handle is dragged. */
    private int dragHandle;
    private boolean longFired;
    private final Paint selPaint = new Paint(), hlPaint = new Paint(), handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // the passage being read aloud
    private int hlIdx = -1, hlA, hlB;
    private final Runnable longPress = this::onLongPress;

    public PageView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        scroller = new OverScroller(c);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        applyPrefs();
    }

    // ------------------------------------------------------------------ public API

    public void setHost(Host h) {
        host = h;
    }

    /** @param waited format with one %d (seconds), shown once a load takes long */
    public void setLoadingText(String s, String waited) {
        loadingText = s;
        waitedText = waited;
    }

    /** Whole-book progress is only meaningful when the full chapter list is known. */
    public void setShowPercent(boolean show) {
        if (showPercent != show) {
            showPercent = show;
            invalidate();
        }
    }

    public void setMenuOpen(boolean open) {
        menuOpen = open;
    }

    public void setSafeInsets(int top, int bottom) {
        if (top == safeTop && bottom == safeBottom) return;
        safeTop = top;
        safeBottom = bottom;
        relayout();
    }

    public void setBattery(int pct) {
        battery = pct;
        invalidate();
    }

    /** Re-reads {@link ReaderPrefs} and re-typesets, keeping the reading position. */
    public void applyPrefs() {
        int offset = currentOffset();
        mode = ReaderPrefs.mode;
        bg = ReaderPrefs.bg();
        fg = ReaderPrefs.fg();
        lineMult = ReaderPrefs.linePct / 100f;
        paraMult = ReaderPrefs.paraPct / 100f;
        indent = ReaderPrefs.indent;
        header = ReaderPrefs.header;
        footer = ReaderPrefs.footer;
        zones = ReaderPrefs.zones;
        gestures = ReaderPrefs.gestures;
        edgeBright = ReaderPrefs.edgeBright;
        ruler = ReaderPrefs.ruler;
        marginL = Math.round(ReaderPrefs.marginL * density);
        marginR = Math.round(ReaderPrefs.marginR * density);
        extraTop = Math.round(ReaderPrefs.marginT * density);
        extraBottom = Math.round(ReaderPrefs.marginB * density);
        float sp = getResources().getDisplayMetrics().scaledDensity;
        Typeface face = ReaderPrefs.typeface();
        paint.setTypeface(ReaderPrefs.bold ? Typeface.create(face, Typeface.BOLD) : face);
        paint.setFakeBoldText(ReaderPrefs.bold && !paint.getTypeface().isBold());
        paint.setLetterSpacing(ReaderPrefs.letterPct / 100f);
        paint.setTextSize(ReaderPrefs.textSp * sp);
        paint.setColor(fg);
        titlePaint.setTypeface(Typeface.create(face, Typeface.BOLD));
        titlePaint.setFakeBoldText(!titlePaint.getTypeface().isBold());
        titlePaint.setTextSize(ReaderPrefs.textSp * sp * 1.3f);
        titlePaint.setColor(fg);
        boolean dark = ReaderPrefs.theme().dark();
        selPaint.setColor(dark ? 0x665FA8FF : 0x553B8BEB);
        hlPaint.setColor(dark ? 0x40FFD36A : 0x55F2C14E);
        handlePaint.setColor(0xFF3B8BEB);
        dropSelection();
        infoPaint.setTextSize(11.5f * density);
        infoPaint.setColor((fg & 0x00FFFFFF) | 0x99000000);
        finishAnimation();
        scroller.forceFinished(true);
        if (cur != null && cur.state == READY) pendingOffset = offset;
        relayout();
    }

    /** Shows chapter idx at the given text offset (the chapter is requested from the host). */
    public void open(int idx, int offset) {
        dropSelection();
        finishAnimation();
        scroller.forceFinished(true);
        prev = next = null;
        cur = new Slot();
        cur.idx = idx;
        cur.title = host.chapterTitle(idx);
        page = 0;
        scrollY = 0;
        pendingOffset = Math.max(0, offset);
        host.requestChapter(idx);
        host.onChapterState();
        invalidate();
    }

    public void setChapter(int idx, String title, String text) {
        Slot s = slot(idx);
        if (s == null) return;
        float oldH = columnHeight(s);
        s.state = READY;
        s.title = title == null ? "" : title;
        s.text = text == null ? "" : text;
        layout(s);
        if (dragDir != 0) curlDirty = true;
        if (s == cur) {
            if (pendingOffset < 0 && s.fromEnd) {
                if (mode == SCROLL) scrollY = Math.max(0, columnHeight(s) - (oldH - scrollY));
                else page = pages(s) - 1;
            }
            s.fromEnd = false;
            applyPendingOffset();
            ensureNeighbours();
            notifyPosition();
            host.onChapterState();
        }
        invalidate();
    }

    public void setError(int idx, CharSequence message) {
        Slot s = slot(idx);
        if (s == null) return;
        s.state = ERROR;
        s.message = message == null ? "" : message;
        s.layout = null;
        if (s == cur) host.onChapterState();
        invalidate();
    }

    /**
     * The chapter is being fetched again: its text leaves the screen so that the wait is visible, and comes
     * back at the same place with {@link #setChapter} (or as it was, when the host hands the old text back).
     */
    public void setLoading(int idx) {
        Slot s = slot(idx);
        if (s == null) return;
        if (s == cur) {
            dropSelection();
            finishAnimation();
            scroller.forceFinished(true);
            if (s.state == READY && s.layout != null && pendingOffset < 0) pendingOffset = currentOffset();
            // the message is drawn where the text starts
            page = 0;
            scrollY = 0;
        }
        s.state = LOADING;
        s.message = "";
        s.since = android.os.SystemClock.uptimeMillis();
        s.layout = null;
        if (s == cur) host.onChapterState();
        invalidate();
    }

    /** One line under "Loading" that says what is going on (which step, which attempt). */
    public void setLoadingDetail(int idx, CharSequence detail) {
        Slot s = slot(idx);
        if (s == null || s.state != LOADING) return;
        s.message = detail == null ? "" : detail;
        invalidate();
    }

    /** The chapter list changed: the current chapter now has index newIdx. */
    public void reindex(int newIdx) {
        if (cur == null) return;
        finishAnimation();
        cur.idx = newIdx;
        prev = next = null;
        ensureNeighbours();
        notifyPosition();
        invalidate();
    }

    public int currentIdx() {
        return cur == null ? 0 : cur.idx;
    }

    public int currentOffset() {
        if (cur == null || cur.state != READY || cur.layout == null) return Math.max(0, pendingOffset);
        if (mode == SCROLL) {
            int i = cur.layout.lineAt(scrollY + 1);
            return i < cur.layout.lines.size() ? cur.layout.lines.get(i).offset : 0;
        }
        return cur.layout.pageOffset(page * cols);
    }

    /** One page forward/back (volume keys, tap zones). */
    public void turn(boolean forward) {
        turn(forward, 0.85f);
    }

    /** A few lines forward/back (arrow keys): only scroll mode can move by less than a page. */
    public void nudge(boolean forward) {
        turn(forward, 0.22f);
    }

    private void turn(boolean forward, float part) {
        if (cur == null) return;
        if (mode == SCROLL) {
            int dy = Math.round((contentBottom - contentTop) * part) * (forward ? 1 : -1);
            scroller.forceFinished(true);
            lastFlingY = 0;
            scroller.startScroll(0, 0, 0, dy, part < 0.5f ? 140 : 260);
            postInvalidateOnAnimation();
            return;
        }
        finishAnimation();
        if (!pickTarget(forward ? 1 : -1)) return;
        if (mode == NONE) {
            commit();
            invalidate();
        } else {
            dragDx = 0;
            if (prepareCurl()) {
                // a tap turns the page from the lower corner
                tX = forward ? getWidth() * 0.92f : 0;
                tY = forward ? getHeight() * 0.92f : getHeight() - 1;
            }
            animateTo(true);
        }
    }

    private int pages(Slot s) {
        return s.state == READY && s.layout != null ? (s.layout.pages.size() + cols - 1) / cols : 1;
    }

    // ------------------------------------------------------------------ slots

    private Slot slot(int idx) {
        if (cur != null && cur.idx == idx) return cur;
        if (next != null && next.idx == idx) return next;
        if (prev != null && prev.idx == idx) return prev;
        return null;
    }

    private Slot request(int idx, boolean fromEnd) {
        Slot s = new Slot();
        s.idx = idx;
        s.title = host.chapterTitle(idx);
        s.fromEnd = fromEnd;
        host.requestChapter(idx);
        return s;
    }

    /** Preloads the chapters around the current one. */
    private void ensureNeighbours() {
        if (cur == null || host == null || cur.state != READY) return;
        if ((next == null || next.idx != cur.idx + 1)) {
            next = cur.idx + 1 < host.chapterCount() && host.mayPreload(cur.idx + 1) ? request(cur.idx + 1, false) : null;
        }
        if ((prev == null || prev.idx != cur.idx - 1)) {
            prev = cur.idx > 0 && host.mayPreload(cur.idx - 1) ? request(cur.idx - 1, true) : null;
        }
    }

    /** Call when chapters were appended to the list (linked books grow as you read). */
    public void chaptersChanged() {
        ensureNeighbours();
        invalidate();
    }

    public void retry() {
        if (cur != null && cur.state == ERROR) {
            cur.state = LOADING;
            cur.message = "";
            cur.since = android.os.SystemClock.uptimeMillis();
            host.requestChapter(cur.idx);
            host.onChapterState();
            invalidate();
        }
    }

    public boolean isLoading() {
        return cur != null && cur.state == LOADING;
    }

    public boolean isError() {
        return cur != null && cur.state == ERROR;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (cur != null && cur.state == READY && cur.layout != null && pendingOffset < 0) pendingOffset = currentOffset();
        relayout();
    }

    private void relayout() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        cols = ReaderPrefs.twoPage && mode != SCROLL && w > h && w >= 560 * density ? 2 : 1;
        gutter = cols == 1 ? 0 : Math.max(Math.round(28 * density), (marginL + marginR) / 2 + Math.round(12 * density));
        contentW = Math.max(Math.round(40 * density), (w - marginL - marginR - gutter * (cols - 1)) / cols);
        contentTop = safeTop + extraTop + Math.round((header ? 34 : 12) * density);
        contentBottom = h - safeBottom - extraBottom - Math.round((footer ? 30 : 12) * density);
        if (contentBottom - contentTop < 60 * density) contentBottom = contentTop + Math.round(60 * density);
        useBackground(Backgrounds.get(ReaderPrefs.theme(), w, h, () -> {
            useBackground(Backgrounds.get(ReaderPrefs.theme(), getWidth(), getHeight(), null));
            invalidate();
        }));
        for (Slot s : new Slot[]{prev, cur, next}) if (s != null && s.state == READY) layout(s);
        applyPendingOffset();
        invalidate();
    }

    private void layout(Slot s) {
        if (contentW <= 0 || s.text == null) return;
        s.layout = ChapterLayout.build(s.title, s.text, paint, titlePaint, contentW, contentBottom - contentTop,
                lineMult, paraMult, indent);
    }

    private void applyPendingOffset() {
        if (cur == null || cur.state != READY || cur.layout == null) return;
        if (pendingOffset >= 0) {
            if (mode == SCROLL) scrollY = cur.layout.yOfOffset(pendingOffset);
            else page = cur.layout.pageOfOffset(pendingOffset) / cols;
            pendingOffset = -1;
            sweep = 0;
        }
        page = Math.max(0, Math.min(page, pages(cur) - 1));
        if (mode == SCROLL) clampScroll(false);
    }

    private void notifyPosition() {
        if (host == null || cur == null) return;
        host.onPosition(cur.idx, page, pages(cur), currentOffset());
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        drawContent(c);
        drawRuler(c);
    }

    private void drawContent(Canvas c) {
        if (cur == null) {
            c.drawColor(bg);
            return;
        }
        if (mode == SCROLL) {
            drawScroll(c);
            return;
        }
        int w = getWidth();
        if (dragDir == 0 || targetSlot == null) {
            drawPage(c, cur, page, 0);
            drawSweep(c);
            drawHandles(c);
            return;
        }
        if (curl()) {
            drawCurl(c);
            return;
        }
        if (mode == SLIDE) {
            drawPage(c, cur, page, dragDx);
            drawPage(c, targetSlot, targetPage, dragDir > 0 ? dragDx + w : dragDx - w);
        } else if (dragDir > 0) {       // cover: current page slides away to the left
            drawPage(c, targetSlot, targetPage, 0);
            drawPage(c, cur, page, dragDx);
            drawShadow(c, w + dragDx);
        } else {                        // previous page slides in from the left
            drawPage(c, cur, page, 0);
            drawPage(c, targetSlot, targetPage, dragDx - w);
            drawShadow(c, dragDx);
        }
    }

    private void drawShadow(Canvas c, float x) {
        int sw = Math.round(14 * density);
        shadow.setBounds((int) x, 0, (int) x + sw, getHeight());
        shadow.draw(c);
    }

    private void drawPage(Canvas c, Slot s, int pageIdx, float dx) {
        int w = getWidth(), h = getHeight();
        c.save();
        c.translate(dx, 0);
        c.clipRect(0, 0, w, h);
        drawBackground(c);
        if (s.state == READY && s.layout != null) {
            int first = Math.max(0, Math.min(pageIdx, pages(s) - 1)) * cols;
            for (int k = 0; k < cols && first + k < s.layout.pages.size(); k++) {
                int[] pg = s.layout.pages.get(first + k);
                if (pg[1] <= pg[0]) continue;
                c.save();
                c.translate(colX(k), contentTop - s.layout.lines.get(pg[0]).top);
                drawMarks(c, s, pg[0], pg[1]);
                s.layout.draw(c, pg[0], pg[1], contentW);
                c.restore();
            }
        } else {
            drawMessage(c, s, contentTop + (contentBottom - contentTop) * 0.36f);
        }
        float frac = s.state == READY ? (pageIdx + 1f) / pages(s) : 0;
        drawChrome(c, s, s.state == READY ? (pageIdx + 1) + "/" + pages(s) : "", frac);
        c.restore();
    }

    private void drawMessage(Canvas c, Slot s, float y) {
        CharSequence msg = s.message;
        // across the whole page, also where the text is set in two columns: the buttons under it are centred too
        int wide = Math.max(Math.max(contentW, 10), getWidth() - marginL - marginR);
        if (s.state != ERROR) {
            // a turning ring: the page is alive even when the site takes its time
            long now = android.os.SystemClock.uptimeMillis();
            float r = 11 * density, cx = marginL + wide / 2f, cy = y - r - 10 * density;
            spinPaint.setStyle(Paint.Style.STROKE);
            spinPaint.setStrokeWidth(2.5f * density);
            spinPaint.setStrokeCap(Paint.Cap.ROUND);
            spinPaint.setColor((fg & 0x00FFFFFF) | 0x90000000);
            spinBox.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(spinBox, (now % 900) * 0.4f, 270, false, spinPaint);
            postInvalidateDelayed(40);
            // first what everybody understands, then (smaller) the step it is at and how long it has taken
            StringBuilder small = new StringBuilder(msg);
            long waited = (now - s.since) / 1000;
            if (waited >= 4) small.append(small.length() > 0 ? "\n" : "").append(String.format(Locale.US, waitedText, waited));
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(loadingText);
            if (small.length() > 0) {
                sb.append("\n").append(small);
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.82f), loadingText.length(), sb.length(), 0);
            }
            msg = sb;
        }
        TextPaint p = new TextPaint(paint);
        p.setTextSize(15 * density);
        p.setColor((fg & 0x00FFFFFF) | 0xB0000000);
        StaticLayout l = StaticLayout.Builder.obtain(msg, 0, msg.length(), p, wide)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.4f).build();
        c.save();
        c.translate(marginL, y);
        l.draw(c);
        c.restore();
    }

    /** A paper texture is a tile repeated over the page at the screen's density, a picture fills the page. */
    private void useBackground(Bitmap b) {
        bgBitmap = b;
        if (b != null && Backgrounds.tiled(ReaderPrefs.theme())) {
            android.graphics.BitmapShader s = new android.graphics.BitmapShader(b,
                    android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(density / Backgrounds.TILE_DENSITY, density / Backgrounds.TILE_DENSITY);
            s.setLocalMatrix(m);
            bgPaint.setShader(s);
        } else {
            bgPaint.setShader(null);
        }
    }

    private void drawBackground(Canvas c) {
        int w = getWidth(), h = getHeight();
        fill.setColor(bg);
        c.drawRect(0, 0, w, h, fill);
        Bitmap b = bgBitmap;
        if (b == null || b.isRecycled()) return;
        if (bgPaint.getShader() != null) {
            c.drawRect(0, 0, w, h, bgPaint);
            return;
        }
        // fill the page, cropping what does not fit
        int bw = b.getWidth(), bh = b.getHeight();
        if (Backgrounds.sheet(ReaderPrefs.theme())) {
            // a sheet of paper has edges of its own: all four stay, whatever the shape of the screen
            bgSrc.set(0, 0, bw, bh);
        } else if ((long) bw * h > (long) bh * w) {
            int cw = Math.max(1, (int) ((long) bh * w / h));
            bgSrc.set((bw - cw) / 2, 0, (bw - cw) / 2 + cw, bh);
        } else {
            int ch = Math.max(1, (int) ((long) bw * h / w));
            bgSrc.set(0, (bh - ch) / 2, bw, (bh - ch) / 2 + ch);
        }
        bgDst.set(0, 0, w, h);
        c.drawBitmap(b, bgSrc, bgDst, bgPaint);
    }

    /** Selection and read-aloud highlights behind lines [from, to); the canvas is at the column origin. */
    private void drawMarks(Canvas c, Slot s, int from, int to) {
        int[] notes = host == null ? null : host.notes(s.idx);
        for (int i = 0; notes != null && i + 2 < notes.length; i += 3) {
            notePaint.setColor((notes[i + 2] & 0x00FFFFFF) | 0x66000000);
            s.layout.mark(c, from, to, notes[i], notes[i + 1], notePaint);
        }
        if (hlIdx == s.idx && hlB > hlA) s.layout.mark(c, from, to, hlA, hlB, hlPaint);
        if (s == selSlot && selB > selA) s.layout.mark(c, from, to, selA, selB, selPaint);
    }

    private void drawHandles(Canvas c) {
        if (selSlot == null || selSlot != cur) return;
        float r = 9 * density;
        for (int i = 0; i < 2; i++) {
            float[] p = handlePos(i == 1);
            if (p == null) continue;
            c.drawRect(p[0] - density, p[1] - paint.getTextSize(), p[0] + density, p[1], handlePaint);
            c.drawCircle(p[0], p[1] + r, r, handlePaint);
        }
    }

    /** Header (chapter title) and footer (battery, clock, page, progress). */
    private void drawChrome(Canvas c, Slot s, String pageInfo, float chapterFraction) {
        int w = getWidth(), h = getHeight();
        float headerY = safeTop + 21 * density, footerY = h - safeBottom - 10 * density;
        if (header) {
            CharSequence title = TextUtils.ellipsize(s.title == null ? "" : s.title, infoPaint,
                    Math.max(10, w - marginL - marginR), TextUtils.TruncateAt.END);
            c.drawText(title, 0, title.length(), marginL, headerY, infoPaint);
        }
        if (!footer) return;
        String left = clock.format(new Date());
        if (battery >= 0) left = battery + "%  " + left;
        c.drawText(left, marginL, footerY, infoPaint);
        int count = host == null ? 0 : host.chapterCount();
        String right = pageInfo;
        if (count > 0 && showPercent) {
            float pct = (s.idx + chapterFraction) * 100f / count;
            right = (right.isEmpty() ? "" : right + "   ") + String.format(Locale.US, "%.1f%%", Math.min(100f, pct));
        }
        c.drawText(right, w - marginR - infoPaint.measureText(right), footerY, infoPaint);
    }

    private float columnHeight(Slot s) {
        if (s.state == READY && s.layout != null) return Math.max(s.layout.height, 40 * density);
        return (contentBottom - contentTop) * 0.7f;
    }

    private float chapterGap() {
        return paint.getTextSize() * 3f;
    }

    private void drawScroll(Canvas c) {
        int w = getWidth(), h = getHeight();
        drawBackground(c);
        c.save();
        c.clipRect(0, contentTop, w, contentBottom);
        float y = contentTop - scrollY;
        drawColumn(c, cur, y);
        float ny = y + columnHeight(cur) + chapterGap();
        if (next != null && ny < contentBottom) drawColumn(c, next, ny);
        c.restore();
        float frac = cur.state == READY ? Math.min(1f, (scrollY + (contentBottom - contentTop)) / Math.max(1f, columnHeight(cur))) : 0;
        drawChrome(c, cur, "", frac);
        drawHandles(c);
    }

    private void drawColumn(Canvas c, Slot s, float y) {
        if (s.state != READY || s.layout == null) {
            drawMessage(c, s, y + (contentBottom - contentTop) * 0.2f);
            return;
        }
        int from = s.layout.lineAt(contentTop - y);
        int to = s.layout.lineAt(contentBottom - y);
        to = Math.min(s.layout.lines.size(), to + 1);
        if (to <= from) return;
        c.save();
        c.translate(marginL, y);
        drawMarks(c, s, from, to);
        s.layout.draw(c, from, to, contentW);
        c.restore();
    }

    // ------------------------------------------------------------------ paged mode mechanics

    /** Chooses what a turn in direction dir would show. @return false at the ends of the book. */
    private boolean pickTarget(int dir) {
        targetSlot = null;
        if (dir > 0) {
            if (page + 1 < pages(cur)) {
                targetSlot = cur;
                targetPage = page + 1;
            } else if (cur.idx + 1 < host.chapterCount()) {
                if (next == null || next.idx != cur.idx + 1) next = request(cur.idx + 1, false);
                targetSlot = next;
                targetPage = 0;
            }
        } else {
            if (page > 0) {
                targetSlot = cur;
                targetPage = page - 1;
            } else if (cur.idx > 0) {
                if (prev == null || prev.idx != cur.idx - 1) prev = request(cur.idx - 1, true);
                targetSlot = prev;
                targetPage = pages(prev) - 1;
            }
        }
        if (targetSlot == null) {
            dragDir = 0;
            host.onEdge(dir > 0);
            return false;
        }
        dragDir = dir;
        return true;
    }

    private void commit() {
        if (targetSlot == null) return;
        dropSelection();
        sweep = 0;
        boolean other = targetSlot != cur;
        if (targetSlot == cur) {
            page = targetPage;
        } else if (dragDir > 0) {
            prev = cur;
            cur = targetSlot;
            next = null;
            page = 0;
            cur.fromEnd = false;
        } else {
            next = cur;
            cur = targetSlot;
            prev = null;
            page = pages(cur) - 1;
            cur.fromEnd = cur.state != READY;
        }
        targetSlot = null;
        dragDir = 0;
        dragDx = 0;
        ensureNeighbours();
        notifyPosition();
        if (other) host.onChapterState();
    }

    private void animateTo(final boolean complete) {
        int w = getWidth();
        animCommit = complete;
        if (curl()) {
            // the finger's point travels on by itself: off the far side to finish, back to the corner to cancel
            final float sx = tX, sy = tY;
            final float ex = (dragDir > 0) == complete ? -w : w - 1;
            final float ey = cornerY == 0 ? 1 : getHeight() - 1;
            animator = ValueAnimator.ofFloat(0, 1);
            animator.setDuration(Math.max(140, Math.min(520, (long) (Math.abs(ex - sx) / (2f * w) * 560))));
            animator.addUpdateListener(a -> {
                float f = (Float) a.getAnimatedValue();
                tX = sx + (ex - sx) * f;
                tY = sy + (ey - sy) * f;
                invalidate();
            });
        } else {
            float end = complete ? (dragDir > 0 ? -w : w) : 0;
            animator = ValueAnimator.ofFloat(dragDx, end);
            animator.setDuration(Math.max(80, Math.min(300, (long) (Math.abs(end - dragDx) / w * 320))));
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                dragDx = (Float) a.getAnimatedValue();
                invalidate();
            });
        }
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                animator = null;
                endTurn();
            }
        });
        animator.start();
    }

    private void endTurn() {
        if (animCommit) commit();
        targetSlot = null;
        dragDir = 0;
        dragDx = 0;
        invalidate();
    }

    private void finishAnimation() {
        if (animator != null) {
            ValueAnimator a = animator;
            animator = null;
            a.cancel();
            endTurn();
        }
    }

    // ------------------------------------------------------------------ scroll mode mechanics

    private void scrollBy(float dy) {
        if (cur == null) return;
        scrollY += dy;
        clampScroll(dy > 0);
        invalidate();
    }

    /** Keeps scrollY inside the book and hands over to the neighbouring chapter at the boundaries. */
    private void clampScroll(boolean forward) {
        float viewH = contentBottom - contentTop;
        boolean changed = false;
        for (int guard = 0; guard < 4; guard++) {
            float curH = columnHeight(cur), gap = chapterGap();
            if (scrollY < 0) {
                if (cur.idx > 0 && cur.state != LOADING) {
                    if (prev == null || prev.idx != cur.idx - 1) prev = request(cur.idx - 1, true);
                    next = cur;
                    cur = prev;
                    prev = null;
                    cur.fromEnd = cur.state != READY;
                    scrollY += columnHeight(cur) + gap;
                    changed = true;
                    continue;
                }
                scrollY = 0;
                if (cur.idx == 0) edge(false);
            } else if (scrollY >= curH + gap && next != null) {
                prev = cur;
                cur = next;
                next = null;
                cur.fromEnd = false;
                scrollY -= curH + gap;
                changed = true;
                continue;
            } else if (next == null) {
                boolean more = cur.idx + 1 < host.chapterCount();
                // without preloading, the next chapter is requested only once the end of this one is on screen
                if (more && cur.state == READY && (scrollY + viewH >= curH || host.mayPreload(cur.idx + 1))) {
                    next = request(cur.idx + 1, false);
                } else {
                    float max = Math.max(0, curH - viewH);
                    if (scrollY > max) {
                        scrollY = max;
                        if (!more && forward) edge(true);
                    }
                }
            }
            break;
        }
        if (changed) {
            ensureNeighbours();
            host.onChapterState();
        }
        notifyPosition();
    }

    private void edge(boolean end) {
        long now = System.currentTimeMillis();
        if (now - lastEdgeAt < 1500) return;
        lastEdgeAt = now;
        scroller.forceFinished(true);
        host.onEdge(end);
    }

    @Override
    public void computeScroll() {
        if (mode != SCROLL || !scroller.computeScrollOffset()) return;
        int y = scroller.getCurrY();
        scrollBy(y - lastFlingY);
        lastFlingY = y;
        postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (cur == null || host == null) return false;
        if (menuOpen) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) host.onCenterTap();
            return true;
        }
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                finishAnimation();
                scroller.forceFinished(true);
                touching = true;
                downX = x;
                downY = lastY = y;
                moved = blocked = longFired = vert = brightDrag = rulerDrag = false;
                dragHandle = selSlot == null ? 0 : handleAt(x, y);
                if (dragHandle == 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) removeCallbacks(longPress);
                if (dragHandle != 0 || longFired) {
                    extendSelection(x, y);
                    return true;
                }
                if (selSlot != null) return true;
                if (brightDrag) {
                    host.onBrightnessDrag((lastY - y) / Math.max(1, getHeight()));
                    lastY = y;
                    return true;
                }
                if (rulerDrag) {
                    rulerY += y - lastY;
                    lastY = y;
                    invalidate();
                    return true;
                }
                if (!moved && !vert && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                    // a vertical drag: the brightness strip and the ruler come before scrolling
                    if (edgeBright && downX < getWidth() * 0.12f) {
                        brightDrag = true;
                        lastY = y;
                        return true;
                    }
                    if (ruler && Math.abs(downY - rulerY) < rulerBand() / 2 + 14 * density) {
                        rulerDrag = true;
                        lastY = y;
                        return true;
                    }
                    if (mode != SCROLL) vert = true;
                }
                if (vert) return true;
                if (mode == SCROLL) {
                    if (!moved && Math.abs(dy) > slop) {
                        moved = true;
                        lastY = y;
                    }
                    if (moved) {
                        scrollBy(lastY - y);
                        lastY = y;
                    }
                    return true;
                }
                if (!moved && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                    moved = true;
                    if (mode != NONE) {
                        blocked = !pickTarget(dx < 0 ? 1 : -1);
                        if (!blocked) prepareCurl();
                    }
                }
                if (moved && !blocked && dragDir != 0 && mode != NONE) {
                    dragDx = dragDir > 0 ? Math.min(0, dx) : Math.max(0, dx);
                    if (curl()) curlTouch(x, y);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                touching = false;
                velocity.computeCurrentVelocity(1000, maxFling);
                float vx = velocity.getXVelocity(), vy = velocity.getYVelocity();
                velocity.recycle();
                velocity = null;
                removeCallbacks(longPress);
                if (dragHandle != 0 || longFired) {
                    dragHandle = 0;
                    longFired = false;
                    if (selSlot != null) host.onSelection(true);
                    return true;
                }
                if (brightDrag || rulerDrag) {
                    brightDrag = rulerDrag = false;
                    return true;
                }
                if (selSlot != null) {
                    clearSelection();
                    return true;
                }
                if (vert) {
                    vert = false;
                    float dy = y - downY;
                    if (Math.abs(dy) > 56 * density) act(gestures[dy < 0 ? ReaderPrefs.G_UP : ReaderPrefs.G_DOWN]);
                    return true;
                }
                if (!moved) {
                    onTap(x, y);
                    return true;
                }
                if (mode == SCROLL) {
                    if (Math.abs(vy) > minFling) {
                        lastFlingY = 0;
                        scroller.fling(0, 0, 0, (int) -vy, 0, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
                        postInvalidateOnAnimation();
                    }
                    return true;
                }
                if (mode == NONE) {
                    if (Math.abs(x - downX) > slop * 2) turn(x < downX);
                    return true;
                }
                if (dragDir != 0 && !blocked) {
                    boolean with = dragDir > 0 ? vx < -minFling * 4 : vx > minFling * 4;
                    boolean against = dragDir > 0 ? vx > minFling * 4 : vx < -minFling * 4;
                    boolean complete = !against && (with || Math.abs(dragDx) > getWidth() * 0.2f);
                    animateTo(complete);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                removeCallbacks(longPress);
                dragHandle = 0;
                longFired = vert = brightDrag = rulerDrag = false;
                if (velocity != null) {
                    velocity.recycle();
                    velocity = null;
                }
                if (mode != SCROLL && dragDir != 0) animateTo(false);
                return true;
            default:
                return true;
        }
    }

    /** With a double-tap action set, a single tap has to wait to see whether a second one follows. */
    private void onTap(final float x, final float y) {
        int dbl = gestures[ReaderPrefs.G_DOUBLE];
        if (dbl == ReaderPrefs.Z_NONE) {
            tap(x, y);
            return;
        }
        if (pendingTap != null) {
            removeCallbacks(pendingTap);
            pendingTap = null;
            act(dbl);
            return;
        }
        pendingTap = () -> {
            pendingTap = null;
            tap(x, y);
        };
        postDelayed(pendingTap, ViewConfiguration.getDoubleTapTimeout());
    }

    /** The screen is a 3x3 grid; what each cell does is the reader's setting. A highlight under the finger wins. */
    private void tap(float x, float y) {
        if (cur == null) return;
        int note = noteAt(x, y);
        if (note >= 0) {
            host.onNoteTap(cur.idx, note);
            return;
        }
        int col = Math.max(0, Math.min(2, (int) (x * 3 / Math.max(1, getWidth()))));
        int row = Math.max(0, Math.min(2, (int) (y * 3 / Math.max(1, getHeight()))));
        act(zones[row * 3 + col]);
    }

    private void act(int action) {
        if (cur == null) return;
        // a failed chapter has its own retry button; the menu must stay reachable there (contents, browser, leaving)
        if (action == ReaderPrefs.Z_MENU) host.onCenterTap();
        else if (action == ReaderPrefs.Z_PREV) turn(false);
        else if (action == ReaderPrefs.Z_NEXT) turn(true);
        else if (action != ReaderPrefs.Z_NONE) host.onAction(action);
    }

    private int noteAt(float x, float y) {
        if (!ready()) return -1;
        int[] n = host.notes(cur.idx);
        if (n == null || n.length < 3) return -1;
        int o = locate(x, y, true);
        for (int i = 0; o >= 0 && i + 2 < n.length; i += 3) if (o >= n[i] && o < n[i + 1]) return n[i];
        return -1;
    }

    // ------------------------------------------------------------------ reading ruler, automatic scrolling

    private float rulerBand() {
        return paint.getTextSize() * lineMult * 3f;
    }

    /** Dims everything but a band of about three lines, which the finger can move up and down. */
    private void drawRuler(Canvas c) {
        if (!ruler || cur == null) return;
        float half = rulerBand() / 2;
        if (rulerY <= 0) rulerY = contentTop + (contentBottom - contentTop) * 0.35f;
        rulerY = Math.max(contentTop + half, Math.min(contentBottom - half, rulerY));
        fill.setColor(ReaderPrefs.theme().dark() ? 0xB0000000 : 0x78000000);
        c.drawRect(0, 0, getWidth(), rulerY - half, fill);
        c.drawRect(0, rulerY + half, getWidth(), getHeight(), fill);
    }

    // Reading by itself, the two forms other readers have settled on: in scroll mode the text moves up steadily;
    // on pages a line travels down the page and uncovers the next page behind it (Legado's AutoPager, the
    // "rolling blind" of Moon+ Reader), so the eye never loses its place to a sudden turn.

    private float autoPx, sweepSec;
    /** Columns of the next page uncovered so far, 0..cols. */
    private float sweep;
    private boolean autoPaused;
    private long autoAt;
    private final Runnable autoFrame = new Runnable() {
        @Override
        public void run() {
            if ((autoPx <= 0 && sweepSec <= 0) || cur == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = Math.min(100, now - autoAt) / 1000f;
            autoAt = now;
            // a finger on the screen, a menu or a selection holds the text still
            boolean free = !autoPaused && !touching && !menuOpen && selSlot == null;
            if (mode == SCROLL) {
                if (free && autoPx > 0 && scroller.isFinished()) scrollBy(autoPx * dt);
            } else if (free && sweepSec > 0 && animator == null && dragDir == 0 && cur.state == READY) {
                sweep += dt * cols / sweepSec;
                // The end of the chapter is on screen: its successor is what the reader is about to turn to,
                // the same moment scroll mode asks for it.
                if (page + 1 >= pages(cur) && cur.idx + 1 < host.chapterCount() && (next == null || next.idx != cur.idx + 1)) {
                    next = request(cur.idx + 1, false);
                }
                if (sweep >= cols) {
                    sweep = 0;
                    if (pickTarget(1)) commit();
                }
                invalidate();
            }
            postOnAnimation(this);
        }
    };

    /**
     * Starts, retunes or (both 0) stops reading by itself.
     *
     * @param pxPerSecond scroll mode: how fast the text moves up
     * @param secPerPage  paged modes: how long the line takes down one page; 0 leaves the pages alone
     */
    public void setAuto(float pxPerSecond, float secPerPage) {
        boolean ran = autoPx > 0 || sweepSec > 0;
        autoPx = pxPerSecond;
        sweepSec = secPerPage;
        if (secPerPage <= 0) sweep = 0;
        if (autoPx <= 0 && sweepSec <= 0) {
            removeCallbacks(autoFrame);
            autoPaused = false;
        } else if (!ran) {
            autoAt = android.os.SystemClock.uptimeMillis();
            postOnAnimation(autoFrame);
        }
        invalidate();
    }

    public void setAutoPaused(boolean paused) {
        autoPaused = paused;
    }

    /** The part of the next page the line has passed, over the page being read. */
    private void drawSweep(Canvas c) {
        if (sweepSec <= 0 || sweep <= 0 || cur.state != READY || selSlot != null) return;
        Slot s = null;
        int pg = 0;
        if (page + 1 < pages(cur)) {
            s = cur;
            pg = page + 1;
        } else if (next != null && next.idx == cur.idx + 1 && next.state == READY) {
            s = next;
        }
        int w = getWidth();
        float colH = contentBottom - contentTop, line = Math.max(1, density);
        for (int k = 0; k < cols; k++) {
            float part = Math.min(1, sweep - k);
            if (part <= 0) break;
            // two columns are read one after the other, so the line goes down the left one first
            float left = k == 0 ? 0 : colX(k) - gutter / 2f, right = k == cols - 1 ? w : colX(k) + contentW + gutter / 2f;
            float y = contentTop + colH * part;
            if (s != null) {
                c.save();
                c.clipRect(left, contentTop, right, y);
                drawPage(c, s, pg, 0);
                c.restore();
            }
            if (part < 1) {
                fill.setColor((fg & 0x00FFFFFF) | 0x55000000);
                c.drawRect(left, y, right, y + line, fill);
            }
        }
    }

    // ------------------------------------------------------------------ page curl
    //
    // The fold is the construction most Android readers use for a "real page" turn (two quadratic curves from
    // the finger to the page edges; see for example SimulationPageDelegate in Legado, GPL-3.0). The page being
    // turned and the page under it are drawn into two bitmaps once per turn. Turning back plays the same fold
    // in reverse with the previous page as the one that unrolls.

    private Bitmap curlTop, curlUnder;
    private boolean curlDirty;
    private float tX, tY;
    private int cornerX, cornerY;
    private final android.graphics.Path path0 = new android.graphics.Path(), path1 = new android.graphics.Path();
    private final float[] st1 = new float[2], ct1 = new float[2], en1 = new float[2], vx1 = new float[2];
    private final float[] st2 = new float[2], ct2 = new float[2], en2 = new float[2], vx2 = new float[2];
    private final android.graphics.Matrix curlMatrix = new android.graphics.Matrix();
    private final float[] curlValues = {0, 0, 0, 0, 0, 0, 0, 0, 1};
    private final Paint backPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final GradientDrawable underLR = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x70111111, 0x00111111});
    private final GradientDrawable underRL = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0x70111111, 0x00111111});
    private final GradientDrawable foldLR = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00333333, 0x70333333});
    private final GradientDrawable foldRL = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0x00333333, 0x70333333});

    private boolean curl() {
        return mode == CURL && curlTop != null && dragDir != 0 && targetSlot != null;
    }

    /** Readies the two page pictures for a turn that has just been chosen. @return false outside curl mode */
    private boolean prepareCurl() {
        if (mode != CURL || targetSlot == null) return false;
        int w = getWidth(), h = getHeight();
        try {
            if (curlTop == null || curlTop.getWidth() != w || curlTop.getHeight() != h) {
                curlTop = curlUnder = null;
                curlTop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                curlUnder = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
        } catch (OutOfMemoryError e) {
            // no room for the pictures: this turn is drawn as a plain cover turn
            curlTop = curlUnder = null;
            return false;
        }
        renderCurl();
        cornerX = w;
        cornerY = h;
        tX = w - 1;
        tY = h - 1;
        return true;
    }

    private void renderCurl() {
        curlDirty = false;
        if (curlTop == null || curlUnder == null || targetSlot == null) return;
        boolean forward = dragDir > 0;
        drawPage(new Canvas(curlTop), forward ? cur : targetSlot, forward ? page : targetPage, 0);
        drawPage(new Canvas(curlUnder), forward ? targetSlot : cur, forward ? targetPage : page, 0);
    }

    private void curlTouch(float x, float y) {
        int h = getHeight();
        if (dragDir > 0) {
            cornerY = downY <= h / 2f ? 0 : h;
            // started at mid-height: a flat turn, the fold stays vertical
            tY = downY > h / 3f && downY < h * 2 / 3f ? cornerY : y;
        } else {
            cornerY = h;
            tY = h;
        }
        tX = x;
        // exactly on the corner's edge the fold has no direction
        if (Math.abs(tY - cornerY) < 1) tY = cornerY == 0 ? 1 : h - 1;
    }

    private static float nz(float v) {
        return Math.abs(v) < 0.1f ? (v < 0 ? -0.1f : 0.1f) : v;
    }

    /** Where the line through (ax, ay) and b meets the line through c and d. */
    private static void cross(float[] out, float ax, float ay, float[] b, float[] c, float[] d) {
        float a1 = (b[1] - ay) / nz(b[0] - ax);
        float b1 = (ax * b[1] - b[0] * ay) / nz(ax - b[0]);
        float a2 = (d[1] - c[1]) / nz(d[0] - c[0]);
        float b2 = (c[0] * d[1] - d[0] * c[1]) / nz(c[0] - d[0]);
        out[0] = (b2 - b1) / nz(a1 - a2);
        out[1] = a1 * out[0] + b1;
    }

    private void controls(float touchX, float touchY) {
        float midX = (touchX + cornerX) / 2, midY = (touchY + cornerY) / 2;
        ct1[0] = midX - (cornerY - midY) * (cornerY - midY) / nz(cornerX - midX);
        ct1[1] = cornerY;
        ct2[0] = cornerX;
        ct2[1] = midY - (cornerX - midX) * (cornerX - midX) / nz(cornerY - midY);
        st1[0] = ct1[0] - (cornerX - ct1[0]) / 2;
        st1[1] = cornerY;
    }

    private static void clipOut(Canvas c, android.graphics.Path p) {
        if (android.os.Build.VERSION.SDK_INT >= 26) c.clipOutPath(p);
        else c.clipPath(p, android.graphics.Region.Op.DIFFERENCE);
    }

    private void drawCurl(Canvas c) {
        if (curlDirty) renderCurl();
        int w = getWidth(), h = getHeight();
        float touchX = tX, touchY = tY;
        controls(touchX, touchY);
        if (touchX > 0 && touchX < w && (st1[0] < 0 || st1[0] > w)) {
            // the fold would start outside the page: pull the point in until it starts at the edge
            if (st1[0] < 0) st1[0] = w - st1[0];
            float f1 = Math.abs(cornerX - touchX);
            float f2 = w * f1 / nz(st1[0]);
            touchX = Math.abs(cornerX - f2);
            float f3 = Math.abs(cornerX - touchX) * Math.abs(cornerY - touchY) / nz(f1);
            touchY = Math.abs(cornerY - f3);
            controls(touchX, touchY);
        }
        st2[0] = cornerX;
        st2[1] = ct2[1] - (cornerY - ct2[1]) / 2;
        float reach = (float) Math.hypot(touchX - cornerX, touchY - cornerY);
        cross(en1, touchX, touchY, ct1, st1, st2);
        cross(en2, touchX, touchY, ct2, st1, st2);
        vx1[0] = (st1[0] + 2 * ct1[0] + en1[0]) / 4;
        vx1[1] = (2 * ct1[1] + st1[1] + en1[1]) / 4;
        vx2[0] = (st2[0] + 2 * ct2[0] + en2[0]) / 4;
        vx2[1] = (2 * ct2[1] + st2[1] + en2[1]) / 4;
        float longest = (float) Math.hypot(w, h);
        boolean rtOrLb = (cornerX == 0 && cornerY == h) || (cornerX == w && cornerY == 0);
        float degrees = (float) Math.toDegrees(Math.atan2(ct1[0] - cornerX, ct2[1] - cornerY));

        // the part of the turning page that still lies flat
        path0.reset();
        path0.moveTo(st1[0], st1[1]);
        path0.quadTo(ct1[0], ct1[1], en1[0], en1[1]);
        path0.lineTo(touchX, touchY);
        path0.lineTo(en2[0], en2[1]);
        path0.quadTo(ct2[0], ct2[1], st2[0], st2[1]);
        path0.lineTo(cornerX, cornerY);
        path0.close();
        c.save();
        clipOut(c, path0);
        c.drawBitmap(curlTop, 0, 0, null);
        c.restore();

        // the page underneath, with the shadow the lifted page throws on it
        path1.reset();
        path1.moveTo(st1[0], st1[1]);
        path1.lineTo(vx1[0], vx1[1]);
        path1.lineTo(vx2[0], vx2[1]);
        path1.lineTo(st2[0], st2[1]);
        path1.lineTo(cornerX, cornerY);
        path1.close();
        c.save();
        c.clipPath(path0);
        c.clipPath(path1);
        c.drawBitmap(curlUnder, 0, 0, null);
        c.rotate(degrees, st1[0], st1[1]);
        GradientDrawable under = rtOrLb ? underLR : underRL;
        if (rtOrLb) under.setBounds((int) st1[0], (int) st1[1], (int) (st1[0] + reach / 4), (int) (st1[1] + longest));
        else under.setBounds((int) (st1[0] - reach / 4), (int) st1[1], (int) st1[0], (int) (st1[1] + longest));
        under.draw(c);
        c.restore();

        // the back of the lifted page: paper colour with the print showing through, mirrored
        float f1 = Math.abs((st1[0] + ct1[0]) / 2 - ct1[0]), f2 = Math.abs((st2[1] + ct2[1]) / 2 - ct2[1]);
        float f3 = Math.min(f1, f2);
        path1.reset();
        path1.moveTo(vx2[0], vx2[1]);
        path1.lineTo(vx1[0], vx1[1]);
        path1.lineTo(en1[0], en1[1]);
        path1.lineTo(touchX, touchY);
        path1.lineTo(en2[0], en2[1]);
        path1.close();
        c.save();
        c.clipPath(path0);
        c.clipPath(path1);
        drawBackground(c);
        float dis = (float) Math.hypot(cornerX - ct1[0], ct2[1] - cornerY);
        float f8 = (cornerX - ct1[0]) / nz(dis), f9 = (ct2[1] - cornerY) / nz(dis);
        curlValues[0] = 1 - 2 * f9 * f9;
        curlValues[1] = 2 * f8 * f9;
        curlValues[3] = curlValues[1];
        curlValues[4] = 1 - 2 * f8 * f8;
        curlMatrix.reset();
        curlMatrix.setValues(curlValues);
        curlMatrix.preTranslate(-ct1[0], -ct1[1]);
        curlMatrix.postTranslate(ct1[0], ct1[1]);
        backPaint.setAlpha(46);
        c.drawBitmap(curlTop, curlMatrix, backPaint);
        c.rotate(degrees, st1[0], st1[1]);
        GradientDrawable fold = rtOrLb ? foldLR : foldRL;
        if (rtOrLb) fold.setBounds((int) (st1[0] - 1), (int) st1[1], (int) (st1[0] + f3 + 1), (int) (st1[1] + longest));
        else fold.setBounds((int) (st1[0] - f3 - 1), (int) st1[1], (int) (st1[0] + 1), (int) (st1[1] + longest));
        fold.draw(c);
        c.restore();
    }

    // ------------------------------------------------------------------ selection and highlight

    private boolean ready() {
        return cur != null && cur.state == READY && cur.layout != null && !cur.layout.lines.isEmpty();
    }

    private float colX(int k) {
        return marginL + k * (contentW + gutter);
    }

    /** Lines of column k of the page on screen: {first, end}; null when the page has no such column. */
    private int[] colLines(int k) {
        int lp = page * cols + k;
        return lp >= 0 && lp < cur.layout.pages.size() ? cur.layout.pages.get(lp) : null;
    }

    /** The column a line of the current chapter is shown in, -1 when it is not on the page. */
    private int colOf(int line) {
        if (mode == SCROLL) return 0;
        for (int k = 0; k < cols; k++) {
            int[] pg = colLines(k);
            if (pg != null && line >= pg[0] && line < pg[1]) return k;
        }
        return -1;
    }

    /** Screen y of the origin of the current chapter's text as drawn in column k. */
    private float originY(int k) {
        if (mode == SCROLL) return contentTop - scrollY;
        int[] pg = colLines(k);
        return contentTop - (pg != null && pg[0] < cur.layout.lines.size() ? cur.layout.lines.get(pg[0]).top : 0);
    }

    /** Lines of the current chapter that are on screen: {first, end}. */
    private int[] visibleLines() {
        if (mode != SCROLL) {
            int[] a = colLines(0), b = a;
            for (int k = 1; k < cols; k++) if (colLines(k) != null) b = colLines(k);
            return a == null ? new int[]{0, 0} : new int[]{a[0], b[1]};
        }
        int from = cur.layout.lineAt(scrollY);
        int to = Math.min(cur.layout.lines.size(), cur.layout.lineAt(scrollY + contentBottom - contentTop) + 1);
        return new int[]{from, to};
    }

    /** Chapter offset of the character under the point, -1 when there is no text there. */
    private int hit(float x, float y) {
        return locate(x, y, false);
    }

    /** @param exact only a point on a line of text counts; otherwise the nearest line and character are taken */
    private int locate(float x, float y, boolean exact) {
        if (!ready()) return -1;
        int k = 0;
        int[] lines;
        if (mode == SCROLL) lines = visibleLines();
        else {
            if (cols > 1 && x >= colX(1) - gutter / 2f && colLines(1) != null) k = 1;
            lines = colLines(k);
        }
        if (lines == null || lines[1] <= lines[0]) return -1;
        float ly = y - originY(k), lx = x - colX(k);
        int i = Math.max(lines[0], Math.min(lines[1] - 1, cur.layout.lineAt(ly)));
        if (exact) {
            ChapterLayout.Line l = cur.layout.lines.get(i);
            if (ly < l.top || ly > l.bottom || lx < 0 || lx > l.layout.getLineRight(l.line) + paint.getTextSize()) return -1;
        }
        return cur.layout.offsetAt(i, lx);
    }

    /** Screen position of the start or end handle: {x, baseline y}; null when that end is off screen. */
    private float[] handlePos(boolean end) {
        if (selSlot == null || selSlot != cur || !ready()) return null;
        int i = cur.layout.lineOfOffset(end ? selB - 1 : selA);
        int k = i < 0 ? -1 : colOf(i);
        if (k < 0) return null;
        if (mode == SCROLL) {
            int[] vis = visibleLines();
            if (i < vis[0] || i >= vis[1]) return null;
        }
        return new float[]{colX(k) + cur.layout.xOf(i, end ? selB : selA), cur.layout.lines.get(i).bottom + originY(k)};
    }

    private int handleAt(float x, float y) {
        float reach = 30 * density, best = reach * reach;
        int found = 0;
        for (int i = 0; i < 2; i++) {
            float[] p = handlePos(i == 1);
            if (p == null) continue;
            float dx = x - p[0], dy = y - (p[1] + 9 * density), d = dx * dx + dy * dy;
            if (d < best) {
                best = d;
                found = i + 1;
            }
        }
        return found;
    }

    private static boolean sentenceEnd(char ch) {
        return ch == '\u3002' || ch == '\uFF01' || ch == '\uFF1F' || ch == '!' || ch == '?' || ch == '\u2026'
                || ch == '\uFF1B' || ch == ';';
    }

    private void onLongPress() {
        if (moved || vert || brightDrag || rulerDrag || menuOpen || !ready()) return;
        int o = hit(downX, downY);
        if (o < 0) return;
        if (selSlot == cur && o >= selA && o < selB) {
            longFired = true;
            host.onSelectionLongPress();
            return;
        }
        String t = cur.text;
        int a = Math.min(o, t.length()), b = a;
        while (a > 0 && t.charAt(a - 1) != '\n' && !sentenceEnd(t.charAt(a - 1))) a--;
        while (b < t.length() && t.charAt(b) != '\n' && !sentenceEnd(t.charAt(b))) b++;
        // take the closing punctuation and quotes along
        while (b < t.length() && t.charAt(b) != '\n' && (sentenceEnd(t.charAt(b)) || "\u201D\u2019\u300D\u300F\uFF09)\"".indexOf(t.charAt(b)) >= 0)) b++;
        if (b <= a) return;
        selSlot = cur;
        selA = anchorA = a;
        selB = anchorB = b;
        longFired = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private void extendSelection(float x, float y) {
        if (selSlot == null || selSlot != cur) return;
        int o = hit(x, y);
        if (o < 0) return;
        if (dragHandle == 1) selA = Math.min(o, selB - 1);
        else if (dragHandle == 2) selB = Math.max(o + 1, selA + 1);
        else if (o < anchorA) {
            selA = o;
            selB = anchorB;
        } else if (o >= anchorB) {
            selA = anchorA;
            selB = o + 1;
        } else {
            selA = anchorA;
            selB = anchorB;
        }
        invalidate();
    }

    private void dropSelection() {
        if (selSlot == null) return;
        selSlot = null;
        dragHandle = 0;
        if (host != null) host.onSelection(false);
    }

    public void clearSelection() {
        dropSelection();
        invalidate();
    }

    public boolean hasSelection() {
        return selSlot != null && selSlot == cur && selSlot.text != null;
    }

    public String selectedText() {
        if (!hasSelection()) return "";
        String t = selSlot.text;
        return t.substring(Math.max(0, Math.min(selA, t.length())), Math.max(0, Math.min(selB, t.length())));
    }

    public int selectionStart() {
        return hasSelection() ? selA : 0;
    }

    public int selectionEnd() {
        return hasSelection() ? selB : 0;
    }

    /** Grows the selection to whole paragraphs. */
    public void selectParagraphs() {
        if (!hasSelection()) return;
        String t = selSlot.text;
        while (selA > 0 && t.charAt(selA - 1) != '\n') selA--;
        while (selB < t.length() && t.charAt(selB) != '\n') selB++;
        anchorA = selA;
        anchorB = selB;
        invalidate();
        host.onSelection(true);
    }

    /** Top and bottom of the selection on screen, for placing a menu next to it. */
    public RectF selectionBounds() {
        float[] a = handlePos(false), b = handlePos(true);
        float top = a == null ? contentTop : a[1] - paint.getTextSize() * lineMult;
        float bottom = b == null ? contentBottom : b[1] + 20 * density;
        return new RectF(0, top, getWidth(), bottom);
    }

    /** Marks the passage being read aloud; idx -1 removes the mark. */
    public void setHighlight(int idx, int a, int b) {
        hlIdx = idx;
        hlA = a;
        hlB = b;
        invalidate();
    }

    /** Brings the text offset of the current chapter on screen (follows the voice). */
    public void reveal(int idx, int offset) {
        if (!ready() || cur.idx != idx || dragDir != 0 || selSlot != null) return;
        if (mode == SCROLL) {
            float y = cur.layout.yOfOffset(offset), viewH = contentBottom - contentTop;
            if (y < scrollY || y > scrollY + viewH * 0.8f) {
                scroller.forceFinished(true);
                scrollY = Math.max(0, y - paint.getTextSize());
                clampScroll(false);
            }
        } else {
            int p = cur.layout.pageOfOffset(offset) / cols;
            if (p != page) {
                page = p;
                notifyPosition();
            }
        }
        invalidate();
    }
}
