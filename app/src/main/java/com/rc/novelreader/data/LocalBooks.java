package com.rc.novelreader.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.rc.novelreader.util.BookParser;
import com.rc.novelreader.util.Texts;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Books read from a file the user picked (plain text or EPUB). They live entirely in the chapter cache. */
public final class LocalBooks {
    private LocalBooks() {}

    private static final long MAX_BYTES = 200L * 1024 * 1024;

    /** Blocks. @return the id of the new shelf book */
    public static long importFile(Context ctx, Uri uri) throws IOException {
        String name = "";
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.getString(i) != null) name = c.getString(i);
            }
        } catch (RuntimeException ignored) {
        }
        if (name.isEmpty() && uri.getLastPathSegment() != null) name = uri.getLastPathSegment();
        File tmp = new File(ctx.getCacheDir(), "import-" + System.currentTimeMillis());
        try {
            long size = 0;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("Cannot read the file");
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if ((size += n) > MAX_BYTES) throw new IOException("The file is too large");
                }
            }
            BookParser.Parsed p;
            if (isZip(tmp)) {
                p = BookParser.parseEpub(tmp);
            } else {
                byte[] all = new byte[(int) tmp.length()];
                try (InputStream in = new FileInputStream(tmp)) {
                    int off = 0;
                    for (int n; off < all.length && (n = in.read(all, off, all.length - off)) > 0; ) off += n;
                }
                p = BookParser.splitTxt(name, BookParser.decode(all));
            }
            if (p.sections.isEmpty()) throw new IOException("No readable text in the file");
            if (p.title.isEmpty()) p.title = name.replaceAll("\\.[A-Za-z0-9]{2,5}$", "");
            return store(p);
        } finally {
            tmp.delete();
        }
    }

    private static boolean isZip(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return in.read() == 'P' && in.read() == 'K';
        }
    }

    private static long store(BookParser.Parsed p) throws IOException {
        long now = System.currentTimeMillis();
        Book b = new Book();
        b.urlKey = "local:" + now;
        b.title = Texts.or(p.title, "?");
        b.author = p.author;
        b.inShelf = true;
        b.addedAt = b.lastReadAt = now;
        // "checked" so nothing ever treats the list as incomplete
        b.lastCheckAt = now;
        b.chapterCount = p.sections.size();
        b.lastChapter = p.sections.get(p.sections.size() - 1).title;
        Db.insert(b);
        SQLiteDatabase db = Db.get();
        db.beginTransaction();
        try {
            int idx = 0;
            for (BookParser.Section s : p.sections) {
                long id = Db.insertChapter(b.id, idx, s.title, "", b.urlKey + ":" + idx);
                Library.write(Library.file(b.id, id), s.text);
                ContentValues v = new ContentValues();
                v.put("cached", 1);
                db.update("chapters", v, "id=?", new String[]{String.valueOf(id)});
                idx++;
            }
            db.setTransactionSuccessful();
        } catch (IOException | RuntimeException e) {
            db.endTransaction();
            Library.deleteBook(b.id);
            throw e;
        }
        db.endTransaction();
        Events.post(Events.SHELF, b.id);
        return b.id;
    }
}
