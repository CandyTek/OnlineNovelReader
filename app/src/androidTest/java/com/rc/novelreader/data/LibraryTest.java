package com.rc.novelreader.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.rc.novelreader.net.PageFetcher;
import com.rc.novelreader.sniff.PageResult;
import com.rc.novelreader.testing.MiniSite;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** A book's life in the library: found on a site, read, cached, grown, exported, deleted. */
public class LibraryTest {
    private MiniSite site;

    @Before
    public void up() throws Exception {
        site = new MiniSite();
    }

    @After
    public void down() {
        for (Book b : Db.books(null, null)) Library.deleteBook(b.id);
        site.close();
    }

    private Book open(String path) throws Exception {
        PageResult r = PageFetcher.fetch(site.url(path), null, false);
        assertEquals(PageResult.Type.DETAIL, r.type);
        Book b = Db.book(Library.openFromDetail(r));
        assertNotNull(b);
        return b;
    }

    @Test
    public void bookFromItsContentsPage() throws Exception {
        Book b = open("/b/");
        assertEquals("万古剑神", b.title);
        assertFalse(b.linked);
        List<Chapter> cs = Db.chapters(b.id);
        assertEquals(12, cs.size());
        assertTrue(cs.get(4).title.contains("山门5"));
        assertNull(Library.cachedText(cs.get(0)));

        site.resetHits();
        String text = Library.loadChapter(b, cs.get(0), false);
        assertTrue(text.contains(MiniSite.paragraph(1000)));
        assertEquals("one chapter, one request", 1, site.totalHits());
        // the second reading comes from the file
        assertEquals(text, Library.loadChapter(b, Db.chapter(cs.get(0).id), false));
        assertEquals(1, site.totalHits());
        assertEquals(text, Library.cachedText(Db.chapter(cs.get(0).id)));
        assertEquals(1, Db.cachedCount(b.id));

        Library.saveProgress(b.id, 3, 250);
        Library.setShelf(b.id, true);
        Book again = Db.book(b.id);
        assertEquals(3, again.curIdx);
        assertEquals(250, again.curOffset);
        assertTrue(again.inShelf);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(1, Library.exportTxt(again, out));
        assertTrue(out.toString("UTF-8").contains(MiniSite.paragraph(1000)));

        Library.clearCache(b.id);
        assertNull(Library.cachedText(Db.chapter(cs.get(0).id)));
        assertEquals(0, Db.cachedCount(b.id));

        // opening the same page again is the same book
        assertEquals(b.id, open("/b/").id);
        Library.deleteBook(b.id);
        assertNull(Db.book(b.id));
        assertEquals(0, Db.chapters(b.id).size());
    }

    @Test
    public void contentsThatGrow() throws Exception {
        Book b = open("/b/");
        site.chapters = 15;
        Library.refreshToc(Db.book(b.id));
        List<Chapter> cs = Db.chapters(b.id);
        assertEquals(15, cs.size());
        for (int i = 0; i < cs.size(); i++) assertEquals(i, cs.get(i).idx);
        // nothing new: the list stays as it is
        Library.refreshToc(Db.book(b.id));
        assertEquals(15, Db.chapters(b.id).size());
    }

    @Test(timeout = 240000)
    public void failedChapterLeavesNothingBehind() throws Exception {
        Book b = open("/b/");
        List<Chapter> cs = Db.chapters(b.id);
        site.broken.add(7);
        site.soft.add(8);
        for (int n : new int[]{7, 8}) {
            try {
                String t = Library.loadChapter(b, cs.get(n - 1), false);
                fail("chapter " + n + " loaded: " + t.substring(0, Math.min(40, t.length())));
            } catch (IOException expected) {
            }
            assertNull(Library.cachedText(Db.chapter(cs.get(n - 1).id)));
            assertFalse(Db.chapter(cs.get(n - 1).id).cached);
        }
        // the site recovers: the same address is asked again and now read
        site.broken.clear();
        site.soft.clear();
        assertTrue(Library.loadChapter(b, Db.chapter(cs.get(6).id), false).contains(MiniSite.paragraph(7000)));
        assertTrue(Library.loadChapter(b, Db.chapter(cs.get(7).id), false).contains(MiniSite.paragraph(8000)));
    }

    @Test(timeout = 120000)
    public void manyReadersOfOneChapterShareOneRequest() throws Exception {
        final Book b = open("/b/");
        final Chapter c = Db.chapters(b.id).get(1);
        site.resetHits();
        final CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(8);
        final AtomicInteger good = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            new Thread(() -> {
                try {
                    go.await();
                    if (Library.loadChapter(b, c, false).contains(MiniSite.paragraph(2000))) good.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        done.await();
        assertEquals(8, good.get());
        assertEquals(1, site.hits("/b/2.html"));
    }

    @Test(timeout = 300000)
    public void bookWithoutContentsGrowsLinkByLink() throws Exception {
        PageResult r = PageFetcher.fetch(site.url("/solo/1.html"), null, true);
        assertEquals(PageResult.Type.CHAPTER, r.type);
        long[] at = Library.openFromChapter(r);
        Book b = Db.book(at[0]);
        assertNotNull(b);
        assertTrue(b.linked);
        assertEquals(0, at[1]);
        for (int guard = 0; guard < 10; guard++) {
            List<Chapter> cs = Db.chapters(b.id);
            Chapter last = cs.get(cs.size() - 1);
            if (!last.cached) {
                Library.loadChapter(b, last, false);
                continue;
            }
            if (!Library.extendLinked(Db.book(b.id))) break;
        }
        List<Chapter> cs = Db.chapters(b.id);
        assertEquals(4, cs.size());
        assertTrue(Db.book(b.id).linked);
        assertTrue(Library.cachedText(cs.get(3)).contains(MiniSite.paragraph(104000)));
        // a new chapter appears on the site: the end of the book moves on
        site.soloChapters = 5;
        assertTrue(Library.extendLinked(Db.book(b.id)));
        assertEquals(5, Db.chapters(b.id).size());
    }

    @Test(timeout = 180000)
    public void threeThousandChapters() throws Exception {
        long t = System.currentTimeMillis();
        Book b = open("/wide/");
        List<Chapter> cs = Db.chapters(b.id);
        assertEquals(3000, cs.size());
        assertTrue("took " + (System.currentTimeMillis() - t) + " ms", System.currentTimeMillis() - t < 60000);
        assertTrue(Library.loadChapter(b, cs.get(2999), false).contains(MiniSite.paragraph(3000000)));
        t = System.currentTimeMillis();
        Library.deleteBook(b.id);
        assertTrue("delete took " + (System.currentTimeMillis() - t) + " ms", System.currentTimeMillis() - t < 20000);
    }
}
