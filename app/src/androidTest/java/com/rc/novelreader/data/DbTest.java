package com.rc.novelreader.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.List;

/** History, bookmarks and address-bar completion with the addresses and titles the web really has. */
public class DbTest {
    private static final String ODD = "It's \"quoted\" 100% _under_ 📖 ‮; DROP TABLE books;--";

    @After
    public void clean() {
        Db.clearHistory();
        for (String[] b : Db.bookmarks()) Db.removeBookmark(b[0]);
    }

    @Test
    public void historyCountsVisits() {
        Db.addHistory("http://one.example/a", "One");
        Db.addHistory("http://one.example/a", "One again");
        Db.addHistory("http://two.example/", ODD);
        List<String[]> h = Db.history();
        assertEquals(2, h.size());
        assertEquals(1, Db.topSites(8).size());
        assertEquals("http://one.example/a", Db.topSites(8).get(0)[0]);
        assertEquals("2", Db.topSites(8).get(0)[2]);
        boolean odd = false;
        for (String[] r : h) odd |= ODD.equals(r[1]);
        assertTrue(odd);
        Db.deleteHistory("http://two.example/");
        assertEquals(1, Db.history().size());
    }

    @Test
    public void historyIsCapped() {
        Db.get().beginTransaction();
        try {
            for (int i = 0; i < 2100; i++) Db.addHistory("http://site" + i + ".example/", "Site " + i);
            Db.get().setTransactionSuccessful();
        } finally {
            Db.get().endTransaction();
        }
        assertEquals(2000, Db.history().size());
    }

    @Test
    public void bookmarksAndSuggestions() {
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 20000; i++) longTitle.append('长');
        Db.addBookmark("http://mark.example/x?q='%_", ODD);
        Db.addBookmark("http://long.example/", longTitle.toString());
        Db.addBookmark("http://long.example/", "twice");
        Db.addBookmark("http://null.example/", null);
        assertEquals(3, Db.bookmarks().size());
        assertTrue(Db.isBookmarked("http://mark.example/x?q='%_"));
        assertFalse(Db.isBookmarked(null));
        Db.addHistory("http://mark.example/other", "Other");
        // characters that mean something to LIKE and to SQL are only text
        for (String q : new String[]{"mark", "%", "_", "'", "\"", ODD, ""}) {
            List<String[]> s = Db.suggest(q, 6);
            assertTrue(q, s.size() <= 12);
        }
        assertEquals("b", Db.suggest("mark.example/x", 6).get(0)[2]);
        Db.updateBookmark("http://mark.example/x?q='%_", "http://long.example/", "moved onto another");
        assertEquals(2, Db.bookmarks().size());
        Db.removeBookmark("http://long.example/");
        assertFalse(Db.isBookmarked("http://long.example/"));
    }
}
