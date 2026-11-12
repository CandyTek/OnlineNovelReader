package com.rc.novelreader.reader;

import android.graphics.Canvas;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.rc.novelreader.util.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * A chapter typeset as one continuous column of lines, plus its division into pages.
 * Paged modes draw one page slice; scroll mode draws the column at an offset.
 */
final class ChapterLayout {
    static final class Line {
        StaticLayout layout;
        int line;
        /** y of the layout's origin inside the column. */
        float layoutTop;
        float top, bottom;
        /** Offset of the line's first character in the chapter text. */
        int offset;
    }

    final List<Line> lines = new ArrayList<>();
    /** {first line, end line (exclusive)} per page. */
    final List<int[]> pages = new ArrayList<>();
    float height;

    static ChapterLayout build(String title, String text, TextPaint paint, TextPaint titlePaint,
                               int width, int pageHeight, float lineMult) {
        ChapterLayout cl = new ChapterLayout();
        float y = 0;
        float textSize = paint.getTextSize();
        if (title != null && !title.isEmpty()) {
            y += textSize * 0.4f;
            StaticLayout tl = make(title, titlePaint, width, 1.25f);
            cl.add(tl, y, 0, 0);
            y += tl.getHeight() + textSize * 1.3f;
        }
        boolean cjk = Texts.mostlyCjk(text);
        String indent = cjk ? "　　" : "";
        float paraGap = textSize * (cjk ? 0.55f : 0.9f) * Math.max(0.6f, lineMult - 0.8f) / 0.75f;
        int pos = 0, n = text.length();
        while (pos <= n) {
            int nl = text.indexOf('\n', pos);
            if (nl < 0) nl = n;
            if (nl > pos) {
                StaticLayout pl = make(indent + text.substring(pos, nl), paint, width, lineMult);
                cl.add(pl, y, pos, indent.length());
                y += pl.getHeight() + paraGap;
            }
            pos = nl + 1;
        }
        cl.height = Math.max(0, y - (cl.lines.isEmpty() ? 0 : paraGap));

        int s = 0, total = cl.lines.size();
        while (s < total) {
            float top = cl.lines.get(s).top;
            int e = s + 1;
            while (e < total && cl.lines.get(e).bottom - top <= pageHeight) e++;
            cl.pages.add(new int[]{s, e});
            s = e;
        }
        if (cl.pages.isEmpty()) cl.pages.add(new int[]{0, 0});
        return cl;
    }

    private static StaticLayout make(String s, TextPaint paint, int width, float mult) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), paint, Math.max(width, 10))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, mult)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
    }

    private void add(StaticLayout l, float y, int paraStart, int indentLen) {
        for (int i = 0; i < l.getLineCount(); i++) {
            Line ln = new Line();
            ln.layout = l;
            ln.line = i;
            ln.layoutTop = y;
            ln.top = y + l.getLineTop(i);
            ln.bottom = y + l.getLineBottom(i);
            ln.offset = paraStart + Math.max(0, l.getLineStart(i) - indentLen);
            lines.add(ln);
        }
    }

    int pageOfOffset(int offset) {
        int p = 0;
        for (int i = 0; i < pages.size(); i++) {
            int[] pg = pages.get(i);
            if (pg[0] < lines.size() && lines.get(pg[0]).offset <= offset) p = i;
            else break;
        }
        return p;
    }

    int pageOffset(int page) {
        if (page < 0 || page >= pages.size()) return 0;
        int first = pages.get(page)[0];
        return first < lines.size() ? lines.get(first).offset : 0;
    }

    /** Column y of the line containing the text offset. */
    float yOfOffset(int offset) {
        float y = 0;
        for (Line l : lines) {
            if (l.offset <= offset) y = l.top;
            else break;
        }
        return offset <= 0 ? 0 : y;
    }

    /** Index of the first line whose bottom is below y. */
    int lineAt(float y) {
        int lo = 0, hi = lines.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).bottom <= y) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Draws lines [from, to) with the column's y=0 at the canvas origin. */
    void draw(Canvas c, int from, int to, int width) {
        int i = from;
        while (i < to) {
            Line a = lines.get(i);
            int j = i;
            while (j + 1 < to && lines.get(j + 1).layout == a.layout) j++;
            Line b = lines.get(j);
            c.save();
            c.translate(0, a.layoutTop);
            c.clipRect(0, a.layout.getLineTop(a.line), width, b.layout.getLineBottom(b.line));
            a.layout.draw(c);
            c.restore();
            i = j + 1;
        }
    }
}
