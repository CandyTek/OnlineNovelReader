package com.rc.novelreader.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import com.rc.novelreader.App;
import com.rc.novelreader.data.Db;
import com.rc.novelreader.sniff.PageResult;
import com.rc.novelreader.testing.MiniSite;
import com.rc.novelreader.testing.Ui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The real browser with real WebViews on the site inside the test. */
public class BrowserScreenTest {
    private MiniSite site;
    private BrowserActivity browser;

    @Before
    public void up() throws Exception {
        site = new MiniSite();
    }

    @After
    public void down() {
        if (browser != null) {
            Ui.main(() -> {
                while (browser.tabs.size() > 1) browser.closeTab(browser.tabs.get(browser.tabs.size() - 1));
            });
        }
        Ui.finish(browser);
        Db.clearHistory();
        site.close();
    }

    private void open(String path) {
        browser = Ui.launch(new Intent(App.I, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_QUERY, site.url(path)));
    }

    private boolean sniffedAs(final PageResult.Type type, long ms) {
        return Ui.waitFor(ms, () -> {
            Tab t = browser.current();
            return t != null && t.sniffed != null && t.sniffed.type == type;
        });
    }

    private int live() {
        return Ui.get(() -> {
            int n = 0;
            for (Tab t : browser.tabs) if (t.web != null) n++;
            return n;
        });
    }

    @Test(timeout = 300000)
    public void novelPagesAreRecognised() {
        site.resetHits();
        open("/b/3.html");
        assertTrue("the chapter was not recognised", sniffedAs(PageResult.Type.CHAPTER, 30000));
        assertTrue(Ui.get(() -> browser.current().sniffed.chapter.text).contains(MiniSite.paragraph(3000)));
        // looking at a page reads the DOM; it must not ask the site again, nor fetch the next chapter
        Ui.sleep(4000);
        assertEquals(1, site.hits("/b/3.html"));
        assertEquals(0, site.hits("/b/4.html"));
        Ui.rotate(browser, 2);

        Ui.main(() -> browser.current().load(site.url("/b/")));
        assertTrue("the contents page was not recognised", sniffedAs(PageResult.Type.DETAIL, 30000));
        assertEquals(site.chapters, (int) Ui.get(() -> browser.current().sniffed.toc.size()));

        Ui.main(() -> browser.current().load(site.url("/portal/")));
        Ui.waitFor(15000, () -> !browser.current().busy() && browser.current().address().contains("/portal/"));
        Ui.sleep(5000);
        String seen = Ui.get(() -> {
            PageResult r = browser.current().sniffed;
            return r == null || !r.isNovel() ? null : r.type + " " + r.confidence() + " at " + browser.current().address();
        });
        assertTrue("a portal page was taken for a novel: " + seen, seen == null);
        boolean inHistory = false;
        for (String[] h : Db.history()) inHistory |= h[0].contains("/b/3.html");
        assertTrue("the visit is missing from the history", inHistory);
    }

    @Test(timeout = 300000)
    public void manyTabsKeepFewWebViews() {
        open("/b/1.html");
        assertTrue(sniffedAs(PageResult.Type.CHAPTER, 30000));
        // tabs of an earlier run wait unloaded in the list: only this one may be alive
        assertEquals(1, live());
        Ui.main(() -> {
            for (int i = browser.tabs.size() - 1; i >= 0; i--) if (browser.tabs.get(i) != browser.current()) browser.closeTab(browser.tabs.get(i));
        });
        for (int i = 2; i <= 14; i++) {
            final String u = site.url("/b/" + (i % 12 + 1) + ".html?tab=" + i);
            Ui.main(() -> browser.openTab(u, null, false, false));
            Ui.sleep(400);
        }
        assertEquals(14, (int) Ui.get(() -> browser.tabs.size()));
        assertTrue(live() + " WebViews alive", live() <= 6);
        // a tab that was put to sleep comes back when it is shown
        Ui.main(() -> browser.switchTo(browser.tabs.get(0)));
        assertTrue("the first tab did not come back", sniffedAs(PageResult.Type.CHAPTER, 30000));
        Ui.rotate(browser, 2);
        // tabs opened in the background cost nothing until shown
        final int before = site.totalHits();
        Ui.main(() -> {
            for (int i = 0; i < 20; i++) browser.openTab(site.url("/b/2.html?bg=" + i), browser.current(), false, true);
        });
        Ui.sleep(2500);
        assertEquals("background tabs made requests", before, site.totalHits());
        assertTrue(live() <= 6);
        // closing all of them, the one in front first
        Ui.main(() -> {
            while (browser.tabs.size() > 1) browser.closeTab(browser.current());
        });
        Ui.sleep(1000);
        assertNotNull(Ui.get(() -> browser.current()));
        assertTrue(live() <= 1);
    }

    @Test(timeout = 300000)
    public void incognitoTabLeavesNoHistory() {
        open("/b/5.html");
        assertTrue(sniffedAs(PageResult.Type.CHAPTER, 30000));
        Ui.main(() -> browser.openTab(site.url("/b/6.html"), null, true, false));
        assertTrue(sniffedAs(PageResult.Type.CHAPTER, 30000));
        Ui.sleep(1500);
        for (String[] h : Db.history()) assertFalse("an incognito visit is in the history", h[0].contains("/b/6.html"));
    }

    @Test(timeout = 300000)
    public void pagesThatAreNotPages() {
        open("/empty.html");
        for (String p : new String[]{"/junk.html", "/deep.html", "/big.html", "/para.html", "/check.html", "/nothing.html", "/loop", "/cut.html"}) {
            Ui.main(() -> browser.current().load(site.url(p)));
            Ui.waitFor(20000, () -> !browser.current().busy());
            Ui.sleep(3500);
        }
        // a port nobody listens on, and addresses that are not addresses
        for (String u : new String[]{"http://127.0.0.1:9/", "http://[::bad", "about:blank", "javascript:void(0)", "data:text/html,<h1>x</h1>"}) {
            Ui.main(() -> browser.current().load(u));
            Ui.sleep(2500);
        }
        Ui.main(() -> browser.current().load(site.url("/b/2.html")));
        assertTrue("the browser no longer recognises a chapter after the broken pages", sniffedAs(PageResult.Type.CHAPTER, 30000));
    }
}
