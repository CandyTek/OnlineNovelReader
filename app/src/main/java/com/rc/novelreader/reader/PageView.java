package com.rc.novelreader.reader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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
    public static final int COVER = 0, SLIDE = 1, NONE = 2, SCROLL = 3;

    public interface Host {
        int chapterCount();

        String chapterTitle(int idx);

        /** Must answer asynchronously with {@link #setChapter} or {@link #setError}. */
        void requestChapter(int idx);

        /** Whether the chapter may be requested before the reader gets to it (it is cached, or preloading is on). */
        boolean mayPreload(int idx);

        void onCenterTap();

        void onPosition(int idx, int page, int pages, int offset);

        /** The user tried to go before the first or past the last chapter. */
        void onEdge(boolean end);
    }

    private static final int LOADING = 0, READY = 1, ERROR = 2;

    private static final class Slot {
        int idx;
        int state = LOADING;
        String title = "", text, message = "";
        ChapterLayout layout;
        /** Entered backwards: show the end of the chapter once it is loaded. */
        boolean fromEnd;

        int pages() {
            return state == READY && layout != null ? layout.pages.size() : 1;
        }
    }

    private Host host;
    private Slot prev, cur, next;
    private int page;
    private int pendingOffset = -1;
    private boolean menuOpen;

    // appearance
    private int mode = COVER, bg, fg, marginH;
    private float lineMult = 1.5f;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint infoPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final float density;
    private String loadingText = "…";
    private boolean showPercent = true;
    private int battery = -1;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.US);
    private final GradientDrawable shadow = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0x50000000, 0x00000000});

    // geometry
    private int safeTop, safeBottom, contentTop, contentBottom, contentW;

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

    public void setLoadingText(String s) {
        loadingText = s;
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
        marginH = Math.round(ReaderPrefs.marginDp * density);
        float sp = getResources().getDisplayMetrics().scaledDensity;
        paint.setTextSize(ReaderPrefs.textSp * sp);
        paint.setColor(fg);
        titlePaint.setTextSize(ReaderPrefs.textSp * sp * 1.3f);
        titlePaint.setColor(fg);
        infoPaint.setTextSize(11.5f * density);
        infoPaint.setColor((fg & 0x00FFFFFF) | 0x99000000);
        finishAnimation();
        scroller.forceFinished(true);
        if (cur != null && cur.state == READY) pendingOffset = offset;
        relayout();
    }

    /** Shows chapter idx at the given text offset (the chapter is requested from the host). */
    public void open(int idx, int offset) {
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
        if (s == cur) {
            if (pendingOffset < 0 && s.fromEnd) {
                if (mode == SCROLL) scrollY = Math.max(0, columnHeight(s) - (oldH - scrollY));
                else page = s.pages() - 1;
            }
            s.fromEnd = false;
            applyPendingOffset();
            ensureNeighbours();
            notifyPosition();
        }
        invalidate();
    }

    public void setError(int idx, String message) {
        Slot s = slot(idx);
        if (s == null) return;
        s.state = ERROR;
        s.message = message == null ? "" : message;
        s.layout = null;
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
        return cur.layout.pageOffset(page);
    }

    /** One page forward/back (volume keys, tap zones). */
    public void turn(boolean forward) {
        if (cur == null) return;
        if (mode == SCROLL) {
            int dy = Math.round((contentBottom - contentTop) * 0.85f) * (forward ? 1 : -1);
            scroller.forceFinished(true);
            lastFlingY = 0;
            scroller.startScroll(0, 0, 0, dy, 260);
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
            animateTo(true);
        }
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
            host.requestChapter(cur.idx);
            invalidate();
        }
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
        contentW = w - 2 * marginH;
        contentTop = safeTop + Math.round(34 * density);
        contentBottom = h - safeBottom - Math.round(30 * density);
        for (Slot s : new Slot[]{prev, cur, next}) if (s != null && s.state == READY) layout(s);
        applyPendingOffset();
        invalidate();
    }

    private void layout(Slot s) {
        if (contentW <= 0 || s.text == null) return;
        s.layout = ChapterLayout.build(s.title, s.text, paint, titlePaint, contentW, contentBottom - contentTop, lineMult);
    }

    private void applyPendingOffset() {
        if (cur == null || cur.state != READY || cur.layout == null) return;
        if (pendingOffset >= 0) {
            if (mode == SCROLL) scrollY = cur.layout.yOfOffset(pendingOffset);
            else page = cur.layout.pageOfOffset(pendingOffset);
            pendingOffset = -1;
        }
        page = Math.max(0, Math.min(page, cur.pages() - 1));
        if (mode == SCROLL) clampScroll(false);
    }

    private void notifyPosition() {
        if (host == null || cur == null) return;
        host.onPosition(cur.idx, page, cur.pages(), currentOffset());
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
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
        fill.setColor(bg);
        c.drawRect(0, 0, w, h, fill);
        if (s.state == READY && s.layout != null) {
            int[] pg = s.layout.pages.get(Math.max(0, Math.min(pageIdx, s.layout.pages.size() - 1)));
            if (pg[1] > pg[0]) {
                c.save();
                c.translate(marginH, contentTop - s.layout.lines.get(pg[0]).top);
                s.layout.draw(c, pg[0], pg[1], contentW);
                c.restore();
            }
        } else {
            drawMessage(c, s, contentTop + (contentBottom - contentTop) * 0.36f);
        }
        float frac = s.state == READY ? (pageIdx + 1f) / s.pages() : 0;
        drawChrome(c, s, s.state == READY ? (pageIdx + 1) + "/" + s.pages() : "", frac);
        c.restore();
    }

    private void drawMessage(Canvas c, Slot s, float y) {
        String msg = s.state == ERROR ? s.message : loadingText;
        TextPaint p = new TextPaint(paint);
        p.setTextSize(15 * density);
        p.setColor((fg & 0x00FFFFFF) | 0xB0000000);
        StaticLayout l = StaticLayout.Builder.obtain(msg, 0, msg.length(), p, Math.max(contentW, 10))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.4f).build();
        c.save();
        c.translate(marginH, y);
        l.draw(c);
        c.restore();
    }

    /** Header (chapter title) and footer (battery, clock, page, progress). */
    private void drawChrome(Canvas c, Slot s, String pageInfo, float chapterFraction) {
        int w = getWidth(), h = getHeight();
        float headerY = safeTop + 21 * density, footerY = h - safeBottom - 10 * density;
        CharSequence title = TextUtils.ellipsize(s.title == null ? "" : s.title, infoPaint, contentW, TextUtils.TruncateAt.END);
        c.drawText(title, 0, title.length(), marginH, headerY, infoPaint);

        String left = clock.format(new Date());
        if (battery >= 0) left = battery + "%  " + left;
        c.drawText(left, marginH, footerY, infoPaint);
        int count = host == null ? 0 : host.chapterCount();
        String right = pageInfo;
        if (count > 0 && showPercent) {
            float pct = (s.idx + chapterFraction) * 100f / count;
            right = (right.isEmpty() ? "" : right + "   ") + String.format(Locale.US, "%.1f%%", Math.min(100f, pct));
        }
        c.drawText(right, w - marginH - infoPaint.measureText(right), footerY, infoPaint);
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
        fill.setColor(bg);
        c.drawRect(0, 0, w, h, fill);
        c.save();
        c.clipRect(0, contentTop, w, contentBottom);
        float y = contentTop - scrollY;
        drawColumn(c, cur, y);
        float ny = y + columnHeight(cur) + chapterGap();
        if (next != null && ny < contentBottom) drawColumn(c, next, ny);
        c.restore();
        float frac = cur.state == READY ? Math.min(1f, (scrollY + (contentBottom - contentTop)) / Math.max(1f, columnHeight(cur))) : 0;
        drawChrome(c, cur, "", frac);
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
        c.translate(marginH, y);
        s.layout.draw(c, from, to, contentW);
        c.restore();
    }

    // ------------------------------------------------------------------ paged mode mechanics

    /** Chooses what a turn in direction dir would show. @return false at the ends of the book. */
    private boolean pickTarget(int dir) {
        targetSlot = null;
        if (dir > 0) {
            if (page + 1 < cur.pages()) {
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
                targetPage = prev.pages() - 1;
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
            page = cur.pages() - 1;
            cur.fromEnd = cur.state != READY;
        }
        targetSlot = null;
        dragDir = 0;
        dragDx = 0;
        ensureNeighbours();
        notifyPosition();
    }

    private void animateTo(final boolean complete) {
        int w = getWidth();
        float end = complete ? (dragDir > 0 ? -w : w) : 0;
        animCommit = complete;
        animator = ValueAnimator.ofFloat(dragDx, end);
        animator.setDuration(Math.max(80, Math.min(300, (long) (Math.abs(end - dragDx) / w * 320))));
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            dragDx = (Float) a.getAnimatedValue();
            invalidate();
        });
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
        if (changed) ensureNeighbours();
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
                downX = x;
                downY = lastY = y;
                moved = blocked = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
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
                    if (mode != NONE) blocked = !pickTarget(dx < 0 ? 1 : -1);
                }
                if (moved && !blocked && dragDir != 0 && mode != NONE) {
                    dragDx = dragDir > 0 ? Math.min(0, dx) : Math.max(0, dx);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                velocity.computeCurrentVelocity(1000, maxFling);
                float vx = velocity.getXVelocity(), vy = velocity.getYVelocity();
                velocity.recycle();
                velocity = null;
                if (!moved) {
                    tap(x);
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

    private void tap(float x) {
        int w = getWidth();
        if (x > w * 0.33f && x < w * 0.67f) {
            if (cur.state == ERROR) retry();
            else host.onCenterTap();
        } else {
            turn(x >= w * 0.67f);
        }
    }
}
