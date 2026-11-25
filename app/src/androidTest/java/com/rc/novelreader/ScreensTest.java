package com.rc.novelreader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.rc.novelreader.browser.LibraryActivity;
import com.rc.novelreader.data.Book;
import com.rc.novelreader.data.Db;
import com.rc.novelreader.data.Library;
import com.rc.novelreader.data.LocalBooks;
import com.rc.novelreader.reader.ReplaceActivity;
import com.rc.novelreader.settings.RulesActivity;
import com.rc.novelreader.settings.ScriptsActivity;
import com.rc.novelreader.settings.SettingsActivity;
import com.rc.novelreader.shelf.ShelfActivity;
import com.rc.novelreader.testing.MiniSite;
import com.rc.novelreader.testing.Ui;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Every other screen: opens with nothing in the app and with a lot in it, and survives being turned. */
public class ScreensTest {
    @After
    public void clean() {
        for (Book b : Db.books(null, null)) Library.deleteBook(b.id);
        Db.clearHistory();
        for (String[] b : Db.bookmarks()) Db.removeBookmark(b[0]);
    }

    private static void show(Intent i) {
        Activity a = Ui.launch(i);
        Ui.sleep(600);
        Ui.rotate(a, 2);
        assertFalse(a.getClass().getSimpleName() + " closed itself", a.isFinishing());
        Ui.finish(a);
    }

    private static Intent of(Class<?> c) {
        return new Intent(App.I, c);
    }

    private static long importBook(String title, int chapters) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= chapters; i++) sb.append("第").append(i).append("章 行路\n\n").append(MiniSite.paragraph(i)).append("\n\n");
        File f = new File(App.I.getCacheDir(), title + ".txt");
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        try {
            return LocalBooks.importFile(App.I, Uri.fromFile(f));
        } finally {
            f.delete();
        }
    }

    @Test(timeout = 300000)
    public void emptyApp() {
        show(of(ShelfActivity.class));
        show(of(SettingsActivity.class));
        show(of(RulesActivity.class));
        show(of(ScriptsActivity.class));
        for (int tab = 0; tab < 4; tab++) show(of(LibraryActivity.class).putExtra("tab", tab));
    }

    @Test(timeout = 600000)
    public void fullApp() throws Exception {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) longName.append("长");
        long first = 0;
        for (int i = 0; i < 120; i++) {
            long id = importBook(i == 7 ? "x" : "书" + i, 2);
            if (i == 0) first = id;
            if (i == 7) {
                // a title no site should send but one will
                android.content.ContentValues v = new android.content.ContentValues();
                v.put("title", longName.toString());
                v.put("author", longName.toString());
                Db.updateBook(id, v);
            }
        }
        Db.get().beginTransaction();
        try {
            for (int i = 0; i < 1500; i++) {
                Db.addHistory("http://site" + i + ".example/page/" + i, i % 9 == 0 ? longName.toString() : "Page " + i);
                if (i < 400) Db.addBookmark("http://mark" + i + ".example/", i % 7 == 0 ? "" : "Mark " + i);
            }
            Db.get().setTransactionSuccessful();
        } finally {
            Db.get().endTransaction();
        }
        long t = System.currentTimeMillis();
        show(of(ShelfActivity.class));
        assertTrue("the shelf with 120 books took " + (System.currentTimeMillis() - t) + " ms", System.currentTimeMillis() - t < 30000);
        for (int tab = 0; tab < 4; tab++) show(of(LibraryActivity.class).putExtra("tab", tab));
        show(of(ReplaceActivity.class).putExtra("book", first));
        show(of(SettingsActivity.class));
    }

    @Test(timeout = 120000)
    public void screensOpenedWithNonsense() {
        // a book that does not exist, a tab that does not exist, a file that is not there
        for (Intent i : new Intent[]{
                of(com.rc.novelreader.reader.ReaderActivity.class).putExtra("book", 987654321L),
                of(com.rc.novelreader.reader.ReaderActivity.class),
                of(ReplaceActivity.class).putExtra("book", -5L),
                of(LibraryActivity.class).putExtra("tab", 99),
                of(ShelfActivity.class).setAction(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///no/such/book.txt"), "text/plain"),
        }) {
            Activity a = Ui.launch(i);
            Ui.sleep(1200);
            Ui.finish(a);
        }
    }
}
