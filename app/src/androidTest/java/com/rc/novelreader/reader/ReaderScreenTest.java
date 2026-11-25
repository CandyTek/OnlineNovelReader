package com.rc.novelreader.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.net.Uri;

import com.rc.novelreader.App;
import com.rc.novelreader.data.Book;
import com.rc.novelreader.data.Db;
import com.rc.novelreader.data.Library;
import com.rc.novelreader.data.LocalBooks;
import com.rc.novelreader.net.PageFetcher;
import com.rc.novelreader.sniff.PageResult;
import com.rc.novelreader.testing.MiniSite;
import com.rc.novelreader.testing.Ui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** The real reader on the real screen, fed by the site inside the test. */
public class ReaderScreenTest {
    private MiniSite site;
    private ReaderActivity reader;

    @Before
    public void up() throws Exception {
        site = new MiniSite();
    }

    @After
    public void down() {
        Ui.finish(reader);
        Ui.main(() -> {
            ReaderPrefs.mode = PageView.COVER;
            ReaderPrefs.textSp = 19;
            ReaderPrefs.linePct = 155;
            ReaderPrefs.marginL = ReaderPrefs.marginR = 22;
            ReaderPrefs.save();
        });
        for (Book b : Db.books(null, null)) Library.deleteBook(b.id);
        site.close();
    }

    private long book() throws Exception {
        PageResult r = PageFetcher.fetch(site.url("/b/"), null, false);
        long id = Library.openFromDetail(r);
        Library.setShelf(id, true);
        return id;
    }

    private void open(long id, int chapter) {
        reader = Ui.launch(new Intent(App.I, ReaderActivity.class).putExtra("book", id).putExtra("chapter", chapter));
        assertNotNull(reader.page);
    }

    private boolean settled(long ms) {
        return Ui.waitFor(ms, () -> !reader.page.isLoading());
    }

    /** Turns pages the way the volume key does, waiting out chapter loads. @return the chapter reached */
    private int turn(int times, boolean forward) {
        for (int i = 0; i < times; i++) {
            settled(20000);
            Ui.main(() -> reader.page.turn(forward));
            Ui.sleep(120);
        }
        settled(20000);
        return Ui.get(() -> reader.page.currentIdx());
    }

    @Test(timeout = 600000)
    public void readsThroughInEveryPageMode() throws Exception {
        long id = book();
        site.resetHits();
        open(id, 0);
        assertTrue("first chapter never appeared", settled(20000));
        assertEquals(0, (int) Ui.get(() -> reader.page.currentIdx()));
        int at = 0;
        for (final int mode : new int[]{PageView.COVER, PageView.SLIDE, PageView.NONE, PageView.CURL, PageView.SCROLL}) {
            Ui.main(() -> {
                ReaderPrefs.mode = mode;
                reader.changed();
            });
            int now = turn(14, true);
            assertTrue("mode " + mode + " went back from " + at + " to " + now, now >= at);
            assertTrue("mode " + mode + " shows an error", !Ui.get(() -> reader.page.isError()));
            at = now;
            Ui.rotate(reader, 2);
            assertEquals("rotation moved the chapter in mode " + mode, at, (int) Ui.get(() -> reader.page.currentIdx()));
        }
        assertTrue("70 page turns reached only chapter " + at, at >= 3);
        // back to the first page and beyond
        Ui.main(() -> {
            ReaderPrefs.mode = PageView.COVER;
            reader.changed();
        });
        assertEquals(0, turn(120, false));
        // the request budget: no chapter was asked for twice, and none far ahead of the reader
        for (int n = 1; n <= site.chapters; n++) {
            assertTrue("chapter " + n + " was requested " + site.hits("/b/" + n + ".html") + " times", site.hits("/b/" + n + ".html") <= 1);
            if (n > at + 2) assertEquals("chapter " + n + " was fetched ahead", 0, site.hits("/b/" + n + ".html"));
        }
    }

    @Test(timeout = 300000)
    public void extremeSettings() throws Exception {
        open(book(), 0);
        assertTrue(settled(20000));
        // {text sp, line %, side margin}
        int[][] shapes = {{8, 100, 0}, {72, 300, 0}, {40, 155, 160}, {19, 155, 22}};
        for (final int[] s : shapes) {
            Ui.main(() -> {
                ReaderPrefs.textSp = s[0];
                ReaderPrefs.linePct = s[1];
                ReaderPrefs.marginL = ReaderPrefs.marginR = s[2];
                reader.changed();
            });
            turn(6, true);
            Ui.rotate(reader, 2);
            turn(3, false);
            assertTrue(!Ui.get(() -> reader.page.isError()));
        }
        // settings changed while a chapter is still on its way
        Ui.main(() -> {
            reader.page.open(9, 0);
            ReaderPrefs.textSp = 30;
            reader.changed();
            ReaderPrefs.textSp = 19;
            reader.changed();
        });
        assertTrue(settled(20000));
        assertEquals(9, (int) Ui.get(() -> reader.page.currentIdx()));
    }

    @Test(timeout = 300000)
    public void failedChapterIsAPageAndRetryReadsIt() throws Exception {
        long id = book();
        site.broken.add(3);
        open(id, 1);
        assertTrue(settled(20000));
        // into the chapter the site refuses
        for (int i = 0; i < 40 && Ui.get(() -> reader.page.currentIdx()) < 2; i++) turn(1, true);
        assertEquals(2, (int) Ui.get(() -> reader.page.currentIdx()));
        assertTrue("the refused chapter is not shown as failed", Ui.waitFor(60000, () -> reader.page.isError()));
        // a failed chapter can be skipped, and the chapters after it cost one request each
        turn(8, true);
        assertTrue("stuck on the failed chapter", Ui.get(() -> reader.page.currentIdx()) >= 3);
        for (int n = 4; n <= site.chapters; n++) assertTrue("chapter " + n, site.hits("/b/" + n + ".html") <= 1);
        // the refused one was asked for a few times at most (plain, rendered, coming back to it), not in a loop
        assertTrue("the failed chapter was requested " + site.hits("/b/3.html") + " times", site.hits("/b/3.html") <= 5);
        Ui.main(() -> reader.page.open(2, 0));
        Ui.waitFor(60000, () -> reader.page.isError());
        site.broken.clear();
        Ui.main(() -> reader.page.retry());
        assertTrue("retry did not bring the chapter", Ui.waitFor(60000, () -> !reader.page.isError() && !reader.page.isLoading()));
        assertEquals(2, (int) Ui.get(() -> reader.page.currentIdx()));
    }

    @Test(timeout = 300000)
    public void endOfBookAndSavedPlace() throws Exception {
        long id = book();
        open(id, 11);
        assertTrue(settled(20000));
        int at = turn(30, true);
        assertTrue("past the end: " + at, at <= 12);
        turn(3, false);
        Ui.main(() -> reader.page.open(6, 0));
        assertTrue(settled(20000));
        turn(1, true);
        Ui.sleep(2500);
        Ui.finish(reader);
        reader = null;
        Book b = Db.book(id);
        assertEquals("the place was not saved", 6, b.curIdx);
        assertTrue("the place inside the chapter was not saved", b.curOffset > 0);
        // and the reader comes back to it
        open(id, -1);
        assertTrue(settled(20000));
        assertEquals(6, (int) Ui.get(() -> reader.page.currentIdx()));
        assertTrue(Ui.get(() -> reader.page.currentOffset()) > 0);
    }

    @Test(timeout = 300000)
    public void importedBookWithAHugeParagraph() throws Exception {
        StringBuilder sb = new StringBuilder("第1章 长段\n\n");
        while (sb.length() < 200000) sb.append(MiniSite.paragraph(sb.length()));
        sb.append("\n\n第2章 空\n\n\n\n第3章 短\n\n完。\n");
        File f = new File(App.I.getCacheDir(), "huge.txt");
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        long id = LocalBooks.importFile(App.I, Uri.fromFile(f));
        f.delete();
        int before = site.totalHits();
        open(id, 0);
        assertTrue("the huge chapter never appeared", settled(60000));
        turn(8, true);
        Ui.main(() -> {
            ReaderPrefs.mode = PageView.SCROLL;
            reader.changed();
        });
        turn(8, true);
        Ui.rotate(reader, 2);
        final int last = Db.chapters(id).size() - 1;
        Ui.main(() -> reader.page.open(last, 0));
        assertTrue(settled(20000));
        turn(5, true);
        assertTrue(!Ui.get(() -> reader.page.isError()));
        assertEquals("an imported book caused requests", before, site.totalHits());
    }
}
