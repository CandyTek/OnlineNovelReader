package com.rc.novelreader.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.Uri;

import com.rc.novelreader.App;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** The backup zip: what goes in, what must never go in, and coming back from it. */
public class BackupTest {
    private static final String SECRET_A = "sk-test-NEVER-IN-A-BACKUP", SECRET_B = "dav-pass-NEVER-IN-A-BACKUP";

    @After
    public void clean() {
        for (Book b : Db.books(null, null)) Library.deleteBook(b.id);
        Db.clearHistory();
        for (String[] b : Db.bookmarks()) Db.removeBookmark(b[0]);
        App.prefs().edit().remove("ai_key").remove("webdav_pass").remove("backup_test_marker").commit();
    }

    private static Map<String, byte[]> entries(byte[] zip) throws IOException {
        Map<String, byte[]> m = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            byte[] buf = new byte[65536];
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                for (int n; (n = z.read(buf)) > 0; ) o.write(buf, 0, n);
                m.put(e.getName(), o.toByteArray());
            }
        }
        return m;
    }

    private static long importBook() throws IOException {
        File f = new File(App.I.getCacheDir(), "backup-book.txt");
        try (OutputStream o = new FileOutputStream(f)) {
            o.write("第1章 起\n\n很久以前的一个故事。\n\n第2章 承\n\n故事还在继续。\n".getBytes(StandardCharsets.UTF_8));
        }
        try {
            return LocalBooks.importFile(App.I, Uri.fromFile(f));
        } finally {
            f.delete();
        }
    }

    @Test(timeout = 120000)
    public void writeAndRestore() throws Exception {
        long id = importBook();
        Db.addBookmark("http://kept.example/", "Kept");
        App.prefs().edit().putString("ai_key", SECRET_A).putString("webdav_pass", SECRET_B)
                .putString("backup_test_marker", "before").commit();

        ByteArrayOutputStream full = new ByteArrayOutputStream(), small = new ByteArrayOutputStream();
        Backup.write(App.I, full, true);
        Backup.write(App.I, small, false);
        Map<String, byte[]> in = entries(full.toByteArray());
        assertNotNull(in.get("db/novel.db"));
        assertNotNull(in.get("prefs/default.json"));
        String prefs = new String(in.get("prefs/default.json"), StandardCharsets.UTF_8);
        assertTrue(prefs.contains("backup_test_marker"));
        boolean text = false;
        for (Map.Entry<String, byte[]> e : in.entrySet()) {
            String body = new String(e.getValue(), StandardCharsets.ISO_8859_1);
            assertFalse(e.getKey() + " holds the key", body.contains(SECRET_A));
            assertFalse(e.getKey() + " holds the password", body.contains(SECRET_B));
            text |= e.getKey().startsWith("files/books/");
        }
        assertTrue("chapter text is missing from the full backup", text);
        for (String name : entries(small.toByteArray()).keySet()) assertFalse(name, name.startsWith("files/books/"));
        // the database was closed for the copy and must simply open again
        assertEquals(2, Db.chapters(id).size());

        // everything changes, then the backup is brought back
        Library.deleteBook(id);
        Db.removeBookmark("http://kept.example/");
        App.prefs().edit().putString("backup_test_marker", "after").putString("ai_key", "a newer key").commit();
        Backup.restore(App.I, new ByteArrayInputStream(full.toByteArray()));
        Db.closeAll();
        Book b = Db.book(id);
        assertNotNull("the book did not come back", b);
        assertEquals(2, Db.chapters(id).size());
        assertTrue(Library.cachedText(Db.chapters(id).get(1)).contains("故事还在继续"));
        assertTrue(Db.isBookmarked("http://kept.example/"));
        assertEquals("before", App.I.getSharedPreferences(App.I.getPackageName() + "_preferences", 4).getString("backup_test_marker", null));
        // a key is never overwritten by a backup
        assertEquals("a newer key", App.I.getSharedPreferences(App.I.getPackageName() + "_preferences", 4).getString("ai_key", null));
    }

    @Test
    public void hostileZipStaysInItsFolder() throws Exception {
        File outside = new File(App.I.getFilesDir().getParentFile(), "escaped.txt");
        outside.delete();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o)) {
            for (String name : new String[]{"../escaped.txt", "files/../../escaped.txt", "/data/local/tmp/escaped.txt", "elsewhere/x.txt"}) {
                z.putNextEntry(new ZipEntry(name));
                z.write("x".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        try {
            Backup.restore(App.I, new ByteArrayInputStream(o.toByteArray()));
        } catch (IOException fine) {
            // "this is not a backup" is the right answer
        }
        assertFalse(outside.exists());
        assertNull(Db.bookByKey("nothing"));
    }

    @Test
    public void notAZip() {
        try {
            Backup.restore(App.I, new ByteArrayInputStream("this is not a zip file".getBytes(StandardCharsets.UTF_8)));
            fail("text was restored as a backup");
        } catch (IOException expected) {
        }
        // the data is still there and usable
        Db.addBookmark("http://still.example/", "Still");
        assertTrue(Db.isBookmarked("http://still.example/"));
    }
}
