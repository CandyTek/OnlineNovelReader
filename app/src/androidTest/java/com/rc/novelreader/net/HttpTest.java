package com.rc.novelreader.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.rc.novelreader.testing.MiniSite;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;

/** The plain HTTP client against answers a site should not give but does. */
public class HttpTest {
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
    public void gbkPageIsDecoded() throws Exception {
        Http.Resp r = Http.get(site.url("/gbk/1.html"), null);
        assertEquals(200, r.code);
        assertTrue(r.html.contains("寒江雪"));
        assertTrue(r.html.contains("山门1"));
    }

    @Test
    public void redirectIsFollowed() throws Exception {
        Http.Resp r = Http.get(site.url("/redir"), null);
        assertEquals(200, r.code);
        assertTrue(r.url, r.url.endsWith("/b/1.html"));
    }

    @Test(timeout = 30000)
    public void redirectLoopEnds() {
        site.resetHits();
        try {
            Http.get(site.url("/loop"), null);
        } catch (IOException expected) {
            // giving up is the answer
        }
        assertTrue("asked " + site.hits("/loop") + " times", site.hits("/loop") <= 10);
    }

    @Test
    public void errorStatusIsReported() throws Exception {
        assertEquals(404, Http.get(site.url("/nothing-here.html"), null).code);
        try {
            Http.getBytes(site.url("/nothing-here.png"), null);
            fail("a 404 picture was returned as bytes");
        } catch (IOException expected) {
        }
    }

    @Test(timeout = 60000)
    public void oversizedBodyIsCut() throws Exception {
        try {
            Http.Resp r = Http.get(site.url("/huge"), null);
            assertTrue("kept " + r.html.length(), r.html.length() <= 6 * 1024 * 1024 + 65536);
        } catch (IOException alsoFine) {
        }
    }

    @Test(timeout = 60000)
    public void truncatedBodyDoesNotHang() {
        try {
            Http.get(site.url("/cut.html"), null);
        } catch (IOException fine) {
        }
    }

    @Test
    public void emptyAndBinaryAnswersAreText() throws Exception {
        assertEquals("", Http.get(site.url("/empty.html"), null).html.trim());
        assertTrue(Http.get(site.url("/junk.html"), null).html.length() > 0);
    }

    @Test(timeout = 20000)
    public void deadPortFailsQuickly() {
        try {
            Http.get("http://127.0.0.1:9/", null);
            fail("a closed port answered");
        } catch (IOException expected) {
        }
    }
}
