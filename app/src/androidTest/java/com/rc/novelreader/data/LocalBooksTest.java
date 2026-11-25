package com.rc.novelreader.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.Uri;

import com.rc.novelreader.App;
import com.rc.novelreader.testing.MiniSite;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Books imported from files, including files that are not books. */
public class LocalBooksTest {
    private final File dir = App.I.getCacheDir();

    @After
    public void clean() {
        for (Book b : Db.books(null, null)) Library.deleteBook(b.id);
    }

    private Uri file(String name, byte[] bytes) throws IOException {
        File f = new File(dir, name);
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(bytes);
        }
        f.deleteOnExit();
        return Uri.fromFile(f);
    }

    private static String novel(int chapters, int paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= chapters; i++) {
            sb.append("第").append(i).append("章 行路").append(i).append("\n\n");
            for (int p = 0; p < paragraphs; p++) sb.append("　　").append(MiniSite.paragraph(i * 100 + p)).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    @Test(timeout = 180000)
    public void largeTextFile() throws Exception {
        long id = LocalBooks.importFile(App.I, file("寒江雪.txt", novel(1500, 12).getBytes(StandardCharsets.UTF_8)));
        Book b = Db.book(id);
        assertNotNull(b);
        assertTrue(b.local());
        assertTrue(b.inShelf);
        List<Chapter> cs = Db.chapters(id);
        assertEquals(1500, cs.size());
        assertTrue(cs.get(1499).title.contains("行路1500"));
        assertTrue(Library.cachedText(cs.get(700)).contains(MiniSite.paragraph(701 * 100)));
        // the stored text is the only copy: clearing the cache must not touch it, and nothing is fetched for it
        Library.clearCache(id);
        assertNotNull(Library.cachedText(Db.chapter(cs.get(700).id)));
        assertEquals(Library.cachedText(cs.get(3)), Library.loadChapter(b, cs.get(3), false));
    }

    @Test
    public void gbkTextFile() throws Exception {
        long id = LocalBooks.importFile(App.I, file("gbk.txt", novel(5, 8).getBytes(Charset.forName("GBK"))));
        assertEquals(5, Db.chapters(id).size());
        assertTrue(Library.cachedText(Db.chapters(id).get(0)).contains(MiniSite.paragraph(100)));
    }

    @Test
    public void textWithoutChapters() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) sb.append(MiniSite.paragraph(i)).append("\n");
        long id = LocalBooks.importFile(App.I, file("plain.txt", sb.toString().getBytes(StandardCharsets.UTF_8)));
        assertTrue(Db.chapters(id).size() >= 1);
    }

    @Test
    public void epub() throws Exception {
        File f = new File(dir, "book.epub");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            put(z, "mimetype", "application/epub+zip");
            put(z, "META-INF/container.xml", "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                    + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            StringBuilder man = new StringBuilder(), spine = new StringBuilder();
            for (int i = 1; i <= 3; i++) {
                man.append("<item id=\"c").append(i).append("\" href=\"c").append(i).append(".xhtml\" media-type=\"application/xhtml+xml\"/>");
                spine.append("<itemref idref=\"c").append(i).append("\"/>");
                StringBuilder body = new StringBuilder("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Part ").append(i)
                        .append("</title></head><body><h1>Part ").append(i).append("</h1>");
                for (int p = 0; p < 10; p++) body.append("<p>").append(MiniSite.paragraph(i * 50 + p)).append("</p>");
                put(z, "OEBPS/c" + i + ".xhtml", body.append("</body></html>").toString());
            }
            put(z, "OEBPS/content.opf", "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\">"
                    + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Glass Harbor</dc:title><dc:creator>A. Writer</dc:creator></metadata>"
                    + "<manifest>" + man + "</manifest><spine>" + spine + "</spine></package>");
        }
        f.deleteOnExit();
        long id = LocalBooks.importFile(App.I, Uri.fromFile(f));
        assertEquals("Glass Harbor", Db.book(id).title);
        assertEquals(3, Db.chapters(id).size());
        assertTrue(Library.cachedText(Db.chapters(id).get(2)).contains(MiniSite.paragraph(150)));
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    @Test
    public void filesThatAreNotBooks() throws Exception {
        byte[] junk = new byte[300000];
        new Random(3).nextBytes(junk);
        byte[] fakeZip = junk.clone();
        fakeZip[0] = 'P';
        fakeZip[1] = 'K';
        Uri[] bad = {file("empty.txt", new byte[0]), file("blank.txt", "\n \n\t\n".getBytes(StandardCharsets.UTF_8)),
                file("broken.epub", fakeZip), Uri.fromFile(new File(dir, "no-such-file.txt"))};
        for (Uri u : bad) {
            try {
                LocalBooks.importFile(App.I, u);
                fail(u + " became a book");
            } catch (IOException expected) {
            }
        }
        // random bytes are still "text" to a decoder; whatever comes of them, it must not be a crash
        try {
            LocalBooks.importFile(App.I, file("junk.txt", junk));
        } catch (IOException fine) {
        }
        // a refused file leaves no half-made book
        for (Book b : Db.books(null, null)) assertTrue(b.title, Db.chapters(b.id).size() > 0);
    }
}
