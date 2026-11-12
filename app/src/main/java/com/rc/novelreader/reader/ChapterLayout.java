package com.rc.novelreader.reader;

import android.graphics.Canvas;
import android.graphics.Paint;
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
        /** Chapter offset just past the line's last character. */
        int end;
        /** Chapter offset of the paragraph the line belongs to; -1 for the heading, which is not chapter text. */
        int paraStart = -1;
        /** Characters of indentation put in front of the paragraph (they are not chapter text either). */
        int indentLen;
    }

    final List<Line> lines = new ArrayList<>();
    /** {first line, end line (exclusive)} per page. */
    final List<int[]> pages = new ArrayList<>();
    float height;

    static ChapterLayout build(String title, String text, TextPaint paint, TextPaint titlePaint,
                               int width, int pageHeight, float lineMult, float paraMult, boolean indentOn) {
        ChapterLayout cl = new ChapterLayout();
        float y = 0;
        float textSize = paint.getTextSize();
        if (title != null && !title.isEmpty()) {
            y += textSize * 0.4f;
            StaticLayout tl = make(title, titlePaint, width, 1.25f);
            cl.add(tl, y, -1, 0);
            y += tl.getHeight() + textSize * 1.3f;
        }
        boolean cjk = Texts.mostlyCjk(text);
        String indent = cjk && indentOn ? "　　" : "";
        float paraGap = textSize * (cjk ? 0.55f : 0.9f) * Math.max(0.6f, lineMult - 0.8f) / 0.75f * paraMult;
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
            ln.paraStart = paraStart;
            ln.indentLen = indentLen;
            int base = Math.max(0, paraStart);
            ln.offset = base + Math.max(0, l.getLineStart(i) - indentLen);
            ln.end = paraStart < 0 ? 0 : base + Math.max(0, l.getLineEnd(i) - indentLen);
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

    /** Index of the text line that holds the chapter offset, -1 when there is no text. */
    int lineOfOffset(int offset) {
        int found = -1;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.paraStart < 0) continue;
            if (l.offset <= offset || found < 0) found = i;
            else break;
        }
        return found;
    }

    /** Chapter offset of the character at x on the line, -1 for the heading. */
    int offsetAt(int lineIdx, float x) {
        Line l = lines.get(lineIdx);
        if (l.paraStart < 0) return -1;
        int o = l.paraStart + Math.max(0, l.layout.getOffsetForHorizontal(l.line, x) - l.indentLen);
        return Math.max(l.offset, Math.min(o, Math.max(l.offset, l.end - 1)));
    }

    /** x of the chapter offset on the line; an offset at or past the line's end gives its right edge. */
    float xOf(int lineIdx, int offset) {
        Line l = lines.get(lineIdx);
        if (offset >= l.end) return l.layout.getLineRight(l.line);
        return l.layout.getPrimaryHorizontal(Math.max(l.offset, offset) - l.paraStart + l.indentLen);
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

    /** Paints the background of the chapter text [a, b) on lines [from, to). */
    void mark(Canvas c, int from, int to, int a, int b, Paint p) {
        for (int i = from; i < to; i++) {
            Line l = lines.get(i);
            if (l.paraStart < 0 || l.end <= a || l.offset >= b || l.end <= l.offset) continue;
            float x1 = xOf(i, a);
            float x2 = xOf(i, b);
            if (x2 > x1) c.drawRect(x1, l.top, x2, l.bottom, p);
        }
    }
}
