package com.rc.novelreader.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.rc.novelreader.App;

import java.util.ArrayList;
import java.util.List;

/** SQLite storage for books, chapter lists and browsing history. Chapter text lives in files. */
public final class Db extends SQLiteOpenHelper {
    private static Db instance;

    public static synchronized SQLiteDatabase get() {
        if (instance == null) instance = new Db(App.I);
        return instance.getWritableDatabase();
    }

    private Db(Context c) {
        super(c, "novel.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT, urlKey TEXT UNIQUE, title TEXT, author TEXT,"
                + " cover TEXT, intro TEXT, detailUrl TEXT, tocUrl TEXT, inShelf INTEGER DEFAULT 0, pinned INTEGER DEFAULT 0,"
                + " linked INTEGER DEFAULT 0, addedAt INTEGER DEFAULT 0, lastReadAt INTEGER DEFAULT 0, curIdx INTEGER DEFAULT 0,"
                + " curOffset INTEGER DEFAULT 0, chapterCount INTEGER DEFAULT 0, newCount INTEGER DEFAULT 0, lastChapter TEXT,"
                + " lastCheckAt INTEGER DEFAULT 0, updatedAt INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE chapters(id INTEGER PRIMARY KEY AUTOINCREMENT, bookId INTEGER, idx INTEGER, title TEXT,"
                + " url TEXT, urlKey TEXT, cached INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX ch_book ON chapters(bookId, idx)");
        db.execSQL("CREATE INDEX ch_key ON chapters(urlKey)");
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT, title TEXT, time INTEGER,"
                + " visits INTEGER DEFAULT 1)");
        db.execSQL(BOOKMARKS);
    }

    private static final String BOOKMARKS =
            "CREATE TABLE IF NOT EXISTS bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT UNIQUE, title TEXT, time INTEGER)";

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE history ADD COLUMN visits INTEGER DEFAULT 1");
            db.execSQL(BOOKMARKS);
        }
    }

    // ------------------------------------------------------------------ books

    static Book readBook(Cursor c) {
        Book b = new Book();
        b.id = c.getLong(c.getColumnIndexOrThrow("id"));
        b.urlKey = str(c, "urlKey");
        b.title = str(c, "title");
        b.author = str(c, "author");
        b.cover = str(c, "cover");
        b.intro = str(c, "intro");
        b.detailUrl = str(c, "detailUrl");
        b.tocUrl = str(c, "tocUrl");
        b.inShelf = num(c, "inShelf") != 0;
        b.pinned = num(c, "pinned") != 0;
        b.linked = num(c, "linked") != 0;
        b.addedAt = num(c, "addedAt");
        b.lastReadAt = num(c, "lastReadAt");
        b.curIdx = (int) num(c, "curIdx");
        b.curOffset = (int) num(c, "curOffset");
        b.chapterCount = (int) num(c, "chapterCount");
        b.newCount = (int) num(c, "newCount");
        b.lastChapter = str(c, "lastChapter");
        b.lastCheckAt = num(c, "lastCheckAt");
        b.updatedAt = num(c, "updatedAt");
        return b;
    }

    private static String str(Cursor c, String col) {
        String s = c.getString(c.getColumnIndexOrThrow(col));
        return s == null ? "" : s;
    }

    private static long num(Cursor c, String col) {
        return c.getLong(c.getColumnIndexOrThrow(col));
    }

    static ContentValues values(Book b) {
        ContentValues v = new ContentValues();
        v.put("urlKey", b.urlKey);
        v.put("title", b.title);
        v.put("author", b.author);
        v.put("cover", b.cover);
        v.put("intro", b.intro);
        v.put("detailUrl", b.detailUrl);
        v.put("tocUrl", b.tocUrl);
        v.put("inShelf", b.inShelf ? 1 : 0);
        v.put("pinned", b.pinned ? 1 : 0);
        v.put("linked", b.linked ? 1 : 0);
        v.put("addedAt", b.addedAt);
        v.put("lastReadAt", b.lastReadAt);
        v.put("curIdx", b.curIdx);
        v.put("curOffset", b.curOffset);
        v.put("chapterCount", b.chapterCount);
        v.put("newCount", b.newCount);
        v.put("lastChapter", b.lastChapter);
        v.put("lastCheckAt", b.lastCheckAt);
        v.put("updatedAt", b.updatedAt);
        return v;
    }

    public static Book book(long id) {
        try (Cursor c = get().rawQuery("SELECT * FROM books WHERE id=?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? readBook(c) : null;
        }
    }

    public static Book bookByKey(String key) {
        try (Cursor c = get().rawQuery("SELECT * FROM books WHERE urlKey=?", new String[]{key})) {
            return c.moveToFirst() ? readBook(c) : null;
        }
    }

    public static List<Book> books(String where, String order) {
        List<Book> out = new ArrayList<>();
        try (Cursor c = get().rawQuery("SELECT * FROM books" + (where == null ? "" : " WHERE " + where)
                + (order == null ? "" : " ORDER BY " + order), null)) {
            while (c.moveToNext()) out.add(readBook(c));
        }
        return out;
    }

    public static long insert(Book b) {
        b.id = get().insertOrThrow("books", null, values(b));
        return b.id;
    }

    public static void update(Book b) {
        get().update("books", values(b), "id=?", new String[]{String.valueOf(b.id)});
    }

    public static void updateBook(long id, ContentValues v) {
        get().update("books", v, "id=?", new String[]{String.valueOf(id)});
    }

    // ------------------------------------------------------------------ chapters

    static Chapter readChapter(Cursor c) {
        Chapter ch = new Chapter();
        ch.id = c.getLong(0);
        ch.bookId = c.getLong(1);
        ch.idx = c.getInt(2);
        ch.title = c.getString(3) == null ? "" : c.getString(3);
        ch.url = c.getString(4);
        ch.urlKey = c.getString(5);
        ch.cached = c.getInt(6) != 0;
        return ch;
    }

    private static final String CH_COLS = "id, bookId, idx, title, url, urlKey, cached";

    public static List<Chapter> chapters(long bookId) {
        List<Chapter> out = new ArrayList<>();
        try (Cursor c = get().rawQuery("SELECT " + CH_COLS + " FROM chapters WHERE bookId=? ORDER BY idx",
                new String[]{String.valueOf(bookId)})) {
            while (c.moveToNext()) out.add(readChapter(c));
        }
        return out;
    }

    public static Chapter chapter(long id) {
        try (Cursor c = get().rawQuery("SELECT " + CH_COLS + " FROM chapters WHERE id=?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? readChapter(c) : null;
        }
    }

    /** Chapter with this URL key; shelf books are preferred when several books contain it. */
    public static Chapter chapterByKey(String urlKey) {
        try (Cursor c = get().rawQuery("SELECT c.id, c.bookId, c.idx, c.title, c.url, c.urlKey, c.cached FROM chapters c"
                + " JOIN books b ON b.id=c.bookId WHERE c.urlKey=? ORDER BY b.inShelf DESC, b.lastReadAt DESC LIMIT 1", new String[]{urlKey})) {
            return c.moveToFirst() ? readChapter(c) : null;
        }
    }

    public static int count(String sql, String... args) {
        try (Cursor c = get().rawQuery(sql, args)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public static int cachedCount(long bookId) {
        return count("SELECT COUNT(*) FROM chapters WHERE bookId=? AND cached=1", String.valueOf(bookId));
    }

    public static long insertChapter(long bookId, int idx, String title, String url, String urlKey) {
        ContentValues v = new ContentValues();
        v.put("bookId", bookId);
        v.put("idx", idx);
        v.put("title", title);
        v.put("url", url);
        v.put("urlKey", urlKey);
        return get().insertOrThrow("chapters", null, v);
    }

    // ------------------------------------------------------------------ history

    public static void addHistory(String url, String title) {
        SQLiteDatabase db = get();
        int visits = count("SELECT visits FROM history WHERE url=?", url) + 1;
        db.delete("history", "url=?", new String[]{url});
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title);
        v.put("time", System.currentTimeMillis());
        v.put("visits", visits);
        db.insert("history", null, v);
        db.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY time DESC LIMIT 2000)");
    }

    private static List<String[]> rows(String sql, String... args) {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = get().rawQuery(sql, args)) {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        }
        return out;
    }

    /** Rows of {url, title, time}, newest first. */
    public static List<String[]> history() {
        return rows("SELECT url, title, time FROM history ORDER BY time DESC");
    }

    /** The most visited pages, one per site: rows of {url, title, visits}. */
    public static List<String[]> topSites(int limit) {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> hosts = new java.util.HashSet<>();
        for (String[] r : rows("SELECT url, title, visits FROM history WHERE visits > 1 ORDER BY visits DESC, time DESC LIMIT 200")) {
            if (hosts.add(com.rc.novelreader.util.Urls.host(r[0]))) out.add(r);
            if (out.size() >= limit) break;
        }
        return out;
    }

    public static void deleteHistory(String url) {
        get().delete("history", "url=?", new String[]{url});
    }

    public static void clearHistory() {
        get().delete("history", null, null);
    }

    // ------------------------------------------------------------------ bookmarks

    /** Rows of {url, title, time}, newest first. */
    public static List<String[]> bookmarks() {
        return rows("SELECT url, title, time FROM bookmarks ORDER BY time DESC");
    }

    public static boolean isBookmarked(String url) {
        return url != null && count("SELECT COUNT(*) FROM bookmarks WHERE url=?", url) > 0;
    }

    public static void addBookmark(String url, String title) {
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title == null ? "" : title);
        v.put("time", System.currentTimeMillis());
        get().insertWithOnConflict("bookmarks", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public static void updateBookmark(String oldUrl, String url, String title) {
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title);
        get().updateWithOnConflict("bookmarks", v, "url=?", new String[]{oldUrl}, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public static void removeBookmark(String url) {
        get().delete("bookmarks", "url=?", new String[]{url});
    }

    /** Address-bar completion: rows of {url, title, kind} where kind is "b" (bookmark) or "h" (history). */
    public static List<String[]> suggest(String text, int limit) {
        String like = "%" + text.replace("%", "").replace("_", "") + "%";
        List<String[]> out = rows("SELECT url, title, 'b' FROM bookmarks WHERE url LIKE ? OR title LIKE ? ORDER BY time DESC LIMIT 4",
                like, like);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] r : out) seen.add(r[0]);
        for (String[] r : rows("SELECT url, title, 'h' FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY visits DESC, time DESC"
                + " LIMIT " + (limit + 4), like, like)) {
            if (out.size() >= limit) break;
            if (seen.add(r[0])) out.add(r);
        }
        return out;
    }
}
