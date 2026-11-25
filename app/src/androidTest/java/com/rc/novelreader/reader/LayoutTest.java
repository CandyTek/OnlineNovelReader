package com.rc.novelreader.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.TextPaint;

import com.rc.novelreader.testing.MiniSite;

import org.junit.Test;

/** Typesetting of chapters nobody would write on purpose: every character must land on exactly one page. */
public class LayoutTest {
    private static TextPaint paint(float size) {
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        return p;
    }

    private static ChapterLayout build(String text, float size, int w, int h, boolean justify) {
        return ChapterLayout.build("第1章 山门", text, paint(size), paint(size * 1.3f), w, h, 1.55f, 1f, true, justify);
    }

    /** Pages follow each other without a gap, offsets never go back, and looking a page up by its offset finds it. */
    private static void check(String name, ChapterLayout cl, String text) {
        assertTrue(name + ": no page", cl.pages.size() >= 1);
        int next = 0;
        for (int i = 0; i < cl.pages.size(); i++) {
            int[] pg = cl.pages.get(i);
            assertEquals(name + ": page " + i + " starts", next, pg[0]);
            assertTrue(name + ": page " + i + " is empty", pg[1] > pg[0] || cl.lines.isEmpty());
            next = pg[1];
        }
        assertEquals(name + ": lines after the last page", cl.lines.size(), next);
        int at = 0;
        for (ChapterLayout.Line ln : cl.lines) {
            if (ln.paraStart < 0) continue;
            assertTrue(name + ": offset goes back", ln.offset >= at);
            assertTrue(name + ": line ends before it starts", ln.end >= ln.offset);
            assertTrue(name + ": offset past the text", ln.end <= text.length());
            at = ln.offset;
        }
        for (int p = 0; p < cl.pages.size(); p += Math.max(1, cl.pages.size() / 50)) {
            int first = cl.pages.get(p)[0];
            if (first < cl.lines.size() && cl.lines.get(first).paraStart >= 0) {
                // lines that hold no chapter text (indentation alone in a column one letter wide) share an offset
                assertEquals(name + ": page of its own offset", cl.pageOffset(p), cl.pageOffset(cl.pageOfOffset(cl.pageOffset(p))));
            }
        }
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    @Test(timeout = 240000)
    public void extremeTexts() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 4000; i++) many.append(MiniSite.paragraph(i)).append('\n');
        String[][] cases = {
                {"empty", ""},
                {"one character", "山"},
                {"blank lines", "\n\n\n \n\t\n"},
                {"4000 paragraphs", many.toString()},
                {"one paragraph of 200000", repeat(MiniSite.paragraph(1), 200000 / MiniSite.paragraph(1).length())},
                {"no break opportunity", repeat("a", 30000)},
                {"emoji", repeat("📖👨‍👩‍👧 ", 3000)},
                {"right to left", repeat("مرحبا بالعالم ", 2000) + "\n" + MiniSite.paragraph(2)},
                {"combining marks", repeat("á̂̃̄̅", 5000)},
                {"lone surrogates and controls", "\uD83D text \uDC00\u0000\u0007​﻿\n" + MiniSite.paragraph(3)},
                {"english", repeat("The wind came down from the pass and worried at the campfire. ", 3000)},
        };
        for (String[] c : cases) {
            for (boolean justify : new boolean[]{false, true}) {
                long t = System.currentTimeMillis();
                ChapterLayout cl = build(c[1], 38, 1000, 1700, justify);
                check(c[0], cl, c[1]);
                assertTrue(c[0] + " took " + (System.currentTimeMillis() - t) + " ms", System.currentTimeMillis() - t < 60000);
            }
        }
    }

    @Test(timeout = 120000)
    public void extremePages() {
        String text = MiniSite.paragraph(1) + "\n" + MiniSite.paragraph(2) + "\n" + repeat(MiniSite.paragraph(3) + "\n", 40);
        // {text size, width, page height}: letters taller than the page, a column narrower than a letter, a wall
        int[][] shapes = {{38, 1000, 1700}, {200, 1000, 150}, {300, 40, 1700}, {8, 4000, 4000}, {38, 1, 1}, {38, 1000, 30}};
        for (int[] s : shapes) {
            ChapterLayout cl = build(text, s[0], s[1], s[2], true);
            check(s[0] + "px in " + s[1] + "x" + s[2], cl, text);
        }
    }

    @Test
    public void everyPositionCanBeDrawnAndHit() {
        String text = MiniSite.paragraph(1) + "\n" + repeat("The wind came down from the pass. ", 30) + "\n" + MiniSite.paragraph(2);
        ChapterLayout cl = build(text, 38, 900, 1500, true);
        Bitmap bmp = Bitmap.createBitmap(900, 1500, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        for (int[] pg : cl.pages) cl.draw(c, pg[0], pg[1], 900);
        for (int i = 0; i < cl.lines.size(); i++) {
            // the heading is not chapter text and has no offsets
            if (cl.lines.get(i).paraStart < 0) continue;
            for (float x : new float[]{-500, 0, 450, 899, 5000}) {
                int o = cl.offsetAt(i, x), b = cl.boundaryAt(i, x);
                assertTrue("offset " + o, o >= 0 && o <= text.length());
                assertTrue("boundary " + b, b >= 0 && b <= text.length());
            }
            cl.xOf(i, cl.lines.get(i).offset);
        }
        for (int o : new int[]{-5, 0, text.length() / 2, text.length(), text.length() + 99}) {
            cl.pageOfOffset(o);
            cl.yOfOffset(o);
            cl.lineOfOffset(o);
        }
        cl.lineAt(-100);
        cl.lineAt(1e9f);
        bmp.recycle();
    }
}
