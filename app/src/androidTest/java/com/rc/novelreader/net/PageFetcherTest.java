package com.rc.novelreader.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.rc.novelreader.sniff.PageResult;
import com.rc.novelreader.testing.MiniSite;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;

/** Fetching and sniffing together, the way the reader and the caching job use them (HTTP, then the hidden WebView). */
public class PageFetcherTest {
    private static MiniSite site;

    @BeforeClass
    public static void up() throws Exception {
        site = new MiniSite();
    }

    @AfterClass
    public static void down() {
        site.close();
    }

    @Test
    public void chapterCostsOneRequest() throws Exception {
        site.resetHits();
        PageResult r = PageFetcher.fetch(site.url("/b/3.html"), site.url("/b/"), true);
        assertEquals(PageResult.Type.CHAPTER, r.type);
        assertTrue(r.chapter.title, r.chapter.title.contains("山门3"));
        assertTrue(r.chapter.text.contains(MiniSite.paragraph(3000)));
        assertFalse(r.chapter.text.contains("排行榜"));
        assertNotNull(r.chapter.nextUrl);
        assertTrue(r.chapter.nextUrl, r.chapter.nextUrl.endsWith("/b/4.html"));
        assertEquals(1, site.totalHits());
    }

    @Test
    public void contentsPageIsABook() throws Exception {
        PageResult r = PageFetcher.fetch(site.url("/b/"), null, false);
        assertEquals(PageResult.Type.DETAIL, r.type);
        assertEquals(site.chapters, r.toc.size());
        assertEquals("万古剑神", r.book.title);
    }

    @Test
    public void gbkChapter() throws Exception {
        PageResult r = PageFetcher.fetch(site.url("/gbk/1.html"), null, true);
        assertEquals(PageResult.Type.CHAPTER, r.type);
        assertTrue(r.chapter.text.contains(MiniSite.paragraph(7000)));
    }

    @Test(timeout = 120000)
    public void veryLongChapterAndVeryLongParagraph() throws Exception {
        PageResult big = PageFetcher.fetch(site.url("/big.html"), null, true);
        assertEquals(PageResult.Type.CHAPTER, big.type);
        assertTrue("" + big.chapter.text.length(), big.chapter.text.length() > 100000);
        PageResult para = PageFetcher.fetch(site.url("/para.html"), null, true);
        assertTrue("" + para.chapter.text.length(), para.chapter.text.length() > 150000);
    }

    @Test(timeout = 120000)
    public void deeplyNestedPageDoesNotOverflow() throws Exception {
        try {
            PageResult r = PageFetcher.fetch(site.url("/deep.html"), null, true);
            assertNotNull(r);
        } catch (IOException fine) {
            // not being able to read it is acceptable, dying of it is not
        }
    }

    @Test(timeout = 120000)
    public void missingChapterIsAnError() throws Exception {
        try {
            PageResult r = PageFetcher.fetch(site.url("/b/999.html"), site.url("/b/"), true);
            fail("an error page was taken as " + r.type);
        } catch (FetchError e) {
            assertTrue("kind " + e.kind, e.kind == FetchError.HTTP || e.kind == FetchError.ERROR_PAGE);
        }
    }

    @Test(timeout = 120000)
    public void humanCheckIsReportedAsSuch() throws Exception {
        try {
            PageFetcher.fetch(site.url("/check.html"), null, true);
            fail("the check was taken as a chapter");
        } catch (IOException e) {
            assertTrue(String.valueOf(e), FetchError.captcha(e));
        }
    }

    @Test(timeout = 180000)
    public void emptyAndBinaryPagesAreErrors() throws Exception {
        for (String p : new String[]{"/empty.html", "/junk.html"}) {
            try {
                PageResult r = PageFetcher.fetch(site.url(p), null, true);
                assertFalse(p + " became a chapter", r != null && r.type == PageResult.Type.CHAPTER);
            } catch (IOException expected) {
            }
        }
    }

    @Test(timeout = 60000)
    public void slowSiteIsWaitedFor() throws Exception {
        assertEquals(PageResult.Type.CHAPTER, PageFetcher.fetch(site.url("/slow.html"), null, true).type);
    }

    @Test(timeout = 120000)
    public void portalIsNoNovel() throws Exception {
        try {
            PageResult r = PageFetcher.fetch(site.url("/portal/"), null, false);
            assertFalse(r != null && r.type == PageResult.Type.CHAPTER);
        } catch (IOException fine) {
        }
    }
}
