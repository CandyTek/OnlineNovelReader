package com.rc.novelreader.reader;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
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
        /**
         * A line stretched to the right margin: x of every character from the line's start to its visible end
         * (one more than there are characters), null when the line is drawn as the layout has it.
         */
        float[] xs;
        /** Added after every character of a stretched line, and the layout index stretching starts at. */
        float gap;
        int stretchFrom;
    }

    final List<Line> lines = new ArrayList<>();
    /** {first line, end line (exclusive)} per page. */
    final List<int[]> pages = new ArrayList<>();
    float height;

    static ChapterLayout build(String title, String text, TextPaint paint, TextPaint titlePaint,
                               int width, int pageHeight, float lineMult, float paraMult, boolean indentOn, boolean justify) {
        ChapterLayout cl = new ChapterLayout();
        float y = 0;
        float textSize = paint.getTextSize();
        if (title != null && !title.isEmpty()) {
            y += textSize * 0.4f;
            StaticLayout tl = heading(title, titlePaint, width);
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
                // words are spread by the layout itself; text without spaces between words is spread here
                StaticLayout pl = make(indent + text.substring(pos, nl), paint, width, lineMult, justify && !cjk);
                int first = cl.lines.size();
                cl.add(pl, y, pos, indent.length());
                if (justify && cjk) cl.stretch(first, width);
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

    /** The chapter heading as the column starts with it (0.4 of the text size below the column's top). */
    static StaticLayout heading(String title, TextPaint titlePaint, int width) {
        return make(title, titlePaint, width, 1.25f, false);
    }

    private static StaticLayout make(String s, TextPaint paint, int width, float mult, boolean justify) {
        StaticLayout.Builder b = StaticLayout.Builder.obtain(s, 0, s.length(), paint, Math.max(width, 10));
        if (justify && Build.VERSION.SDK_INT >= 26) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        return b
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

    /**
     * Stretches the lines of the paragraph added last, except its final one, to the right margin by widening
     * the space after every character alike. Line breaking leaves a ragged edge wherever a punctuation mark
     * may not start a line or a narrow character (a digit, a Latin letter) sits among the wide ones.
     */
    private void stretch(int first, int width) {
        for (int i = first; i < lines.size() - 1; i++) {
            Line ln = lines.get(i);
            StaticLayout l = ln.layout;
            int start = l.getLineStart(ln.line), end = l.getLineVisibleEnd(ln.line);
            int from = start + (ln.line == 0 ? ln.indentLen : 0);
            if (end - from < 8) continue;
            float[] adv = new float[end - start];
            l.getPaint().getTextWidths(l.getText(), start, end, adv);
            float natural = 0;
            int gaps = -1;
            for (int k = 0; k < adv.length; k++) {
                natural += adv[k];
                // a character without an advance of its own (second half of a pair, a combining mark) is not spaced
                if (start + k >= from && adv[k] > 0) gaps++;
            }
            float slack = width - natural;
            // a line that falls far short was broken for another reason (a long address): leave it alone
            if (gaps < 4 || slack < 0.5f || slack > l.getPaint().getTextSize() * 3) continue;
            ln.gap = slack / gaps;
            ln.stretchFrom = from;
            ln.xs = new float[adv.length + 1];
            float x = 0;
            for (int k = 0; k < adv.length; k++) {
                ln.xs[k] = x;
                x += adv[k];
                if (start + k >= from && adv[k] > 0) x += ln.gap;
            }
            ln.xs[adv.length] = x - ln.gap;
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
        int at;
        if (l.xs != null) {
            // as the layout answers: the character boundary nearest to x
            int k = 0;
            while (k < l.xs.length - 1 && x >= (l.xs[k] + l.xs[k + 1]) / 2) k++;
            at = l.layout.getLineStart(l.line) + k;
        } else at = l.layout.getOffsetForHorizontal(l.line, x);
        int o = l.paraStart + Math.max(0, at - l.indentLen);
        return Math.max(l.offset, Math.min(o, Math.max(l.offset, l.end - 1)));
    }

    /**
     * Chapter offset of the gap between two characters that is nearest to x on the line; unlike
     * {@link #offsetAt} the gap behind the last character counts too (a selection can end there). -1 for the
     * heading.
     */
    int boundaryAt(int lineIdx, float x) {
        Line l = lines.get(lineIdx);
        if (l.paraStart < 0) return -1;
        int at;
        if (l.xs != null) {
            int k = 0;
            while (k < l.xs.length - 1 && x >= (l.xs[k] + l.xs[k + 1]) / 2) k++;
            at = l.layout.getLineStart(l.line) + k;
        } else at = l.layout.getOffsetForHorizontal(l.line, x);
        int o = l.paraStart + Math.max(0, at - l.indentLen);
        return Math.max(l.offset, Math.min(o, Math.max(l.offset, l.end)));
    }

    /** x of the chapter offset on the line; an offset at or past the line's end gives its right edge. */
    float xOf(int lineIdx, int offset) {
        Line l = lines.get(lineIdx);
        if (l.xs != null) {
            int k = offset >= l.end ? l.xs.length - 1
                    : Math.max(l.offset, offset) - l.paraStart + l.indentLen - l.layout.getLineStart(l.line);
            return l.xs[Math.max(0, Math.min(k, l.xs.length - 1))];
        }
        if (offset >= l.end) return l.layout.getLineRight(l.line);
        return l.layout.getPrimaryHorizontal(Math.max(l.offset, offset) - l.paraStart + l.indentLen);
    }

    /** Draws lines [from, to) with the column's y=0 at the canvas origin. */
    void draw(Canvas c, int from, int to, int width) {
        int i = from;
        while (i < to) {
            Line a = lines.get(i);
            if (a.xs != null) {
                drawStretched(c, a);
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < to && lines.get(j + 1).layout == a.layout && lines.get(j + 1).xs == null) j++;
            Line b = lines.get(j);
            c.save();
            c.translate(0, a.layoutTop);
            c.clipRect(0, a.layout.getLineTop(a.line), width, b.layout.getLineBottom(b.line));
            a.layout.draw(c);
            c.restore();
            i = j + 1;
        }
    }

    /**
     * One call with wider letter spacing. The engine puts half of the spacing in front of each character, so
     * the start moves back by half of what was added to keep the first character where the layout has it.
     */
    private static void drawStretched(Canvas c, Line l) {
        TextPaint p = l.layout.getPaint();
        float spacing = p.getLetterSpacing();
        int start = l.layout.getLineStart(l.line);
        p.setLetterSpacing(spacing + l.gap / p.getTextSize());
        c.drawText(l.layout.getText(), l.stretchFrom, start + l.xs.length - 1,
                l.xs[l.stretchFrom - start] - l.gap / 2, l.layoutTop + l.layout.getLineBaseline(l.line), p);
        p.setLetterSpacing(spacing);
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
