package com.rc.novelreader.data;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

import com.rc.novelreader.App;
import com.rc.novelreader.net.FetchError;
import com.rc.novelreader.net.PageFetcher;
import com.rc.novelreader.sniff.ContentCleaner;
import com.rc.novelreader.sniff.PageResult;
import com.rc.novelreader.sniff.PageResult.ChapterContent;
import com.rc.novelreader.sniff.PageResult.ChapterRef;
import com.rc.novelreader.util.Texts;
import com.rc.novelreader.util.Urls;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Book operations that tie the sniffer, the network and the database together.
 * Everything here blocks; call it off the main thread unless noted.
 */
public final class Library {
    private Library() {}

    private static final long WEEK = 7L * 24 * 3600 * 1000;

    // ------------------------------------------------------------------ files

    private static File dir(long bookId) {
        return new File(App.I.getFilesDir(), "books/" + bookId);
    }

    static File file(long bookId, long chapterId) {
        return new File(dir(bookId), chapterId + ".txt");
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            for (int n; off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0; ) off += n;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        }
    }

    static void write(File f, String text) throws IOException {
        File d = f.getParentFile();
        if (d != null && !d.exists() && !d.mkdirs() && !d.exists()) throw new IOException("Cannot create " + d);
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("Cannot write " + f);
    }

    private static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    // ------------------------------------------------------------------ opening books from sniffed pages

    /** Creates or finds the book for a sniffed detail page. @return book id */
    public static long openFromDetail(PageResult r) {
        String key = Urls.normalize(r.url);
        Book b = Db.bookByKey(key);
        if (b == null) {
            b = new Book();
            b.urlKey = key;
            b.detailUrl = r.url;
            b.tocUrl = r.url;
            b.title = Texts.or(r.book.title, Urls.host(r.url));
            b.author = r.book.author;
            b.cover = r.book.cover;
            b.intro = r.book.intro;
            b.addedAt = b.lastReadAt = System.currentTimeMillis();
            Db.insert(b);
        } else {
            fillMeta(b, r.book);
            // A linked book stays linked until a chapter list has really been merged (refreshToc does that and
            // checks the list contains the chapter being read). Unlinking here without a list froze the book
            // at the chapters read so far.
            if (b.linked) b.tocUrl = r.url;
            Db.update(b);
        }
        // the page in the browser may show only part of the list (pagination, "all chapters" link)
        boolean partial = r.toc.isEmpty() || !r.tocPages.isEmpty() || r.tocNextUrl != null || r.moreTocUrl != null
                || (b.linked && !Db.chapters(b.id).isEmpty());
        if (!b.linked && !r.toc.isEmpty() && Db.chapters(b.id).size() < r.toc.size()) {
            if (!partial && b.lastCheckAt == 0) b.lastCheckAt = System.currentTimeMillis();
            mergeChapters(b, r.toc, false);
        }
        final long id = b.id;
        if (partial) {
            App.IO.execute(() -> {
                try {
                    Book fresh = Db.book(id);
                    // the page in the browser is the first page of the list: only what it lacks is asked for
                    if (fresh != null) refreshToc(fresh, r);
                } catch (IOException ignored) {
                }
            });
        }
        return id;
    }

    /** Creates or finds the book for a sniffed chapter page. @return {book id, chapter index} */
    public static long[] openFromChapter(PageResult r) {
        ChapterContent cc = r.chapter;
        String chKey = Urls.normalize(r.url);
        Chapter known = Db.chapterByKey(chKey);
        if (known != null) {
            keepText(known, r);
            return new long[]{known.bookId, known.idx};
        }

        String tocUrl = cc.tocUrl;
        Book b = tocUrl == null ? null : Db.bookByKey(Urls.normalize(tocUrl));
        if (b != null) {
            // a known book, but this chapter is not in its list: refresh the list, then match by URL or title
            try {
                refreshToc(b);
            } catch (IOException ignored) {
            }
            List<Chapter> chapters = Db.chapters(b.id);
            int idx = indexOf(chapters, chKey, cc.title);
            if (idx >= 0) {
                if (chapters.get(idx).urlKey.equals(chKey)) keepText(chapters.get(idx), r);
                return new long[]{b.id, idx};
            }
            if (!chapters.isEmpty() && !b.linked) return new long[]{b.id, Math.min(b.curIdx, chapters.size() - 1)};
        }
        if (b == null) {
            b = new Book();
            b.urlKey = tocUrl != null ? Urls.normalize(tocUrl) : "chap:" + chKey;
            b.detailUrl = Texts.or(tocUrl, r.url);
            b.tocUrl = Texts.or(tocUrl, "");
            // on a chapter page the page-level title is usually the chapter's, not the book's
            String pageTitle = Texts.bare(r.book.title).equals(Texts.bare(cc.title)) ? "" : r.book.title;
            b.title = Texts.or(cc.bookTitle, Texts.or(pageTitle, Urls.host(r.url)));
            b.linked = true;
            b.addedAt = b.lastReadAt = System.currentTimeMillis();
            Db.insert(b);
        }
        // linked mode: start the chain with the chapter the user is looking at
        List<Chapter> existing = Db.chapters(b.id);
        int idx = existing.size();
        long chId = Db.insertChapter(b.id, idx, cc.title, r.url, chKey);
        if (!cc.nextInChapter) {
            try {
                write(file(b.id, chId), cc.text);
                ContentValues v = new ContentValues();
                v.put("cached", 1);
                Db.get().update("chapters", v, "id=?", new String[]{String.valueOf(chId)});
            } catch (IOException ignored) {
            }
            if (cc.nextUrl != null) appendLinked(b.id, idx, cc.nextUrl);
        } else if (!cc.errorPage) {
            // the loader has to walk the chapter's pages: it starts from this one instead of fetching it again
            PageFetcher.offer(r);
        }
        b.curIdx = idx;
        b.curOffset = 0;
        b.chapterCount = Db.count("SELECT COUNT(*) FROM chapters WHERE bookId=?", String.valueOf(b.id));
        Db.update(b);
        final long id = b.id;
        // The user asked for this chapter only; the contents page is an extra request, so it is opt-in.
        if (!b.tocUrl.isEmpty() && App.prefs().getBoolean("auto_toc", false)) {
            App.IO.execute(() -> {
                try {
                    Book fresh = Db.book(id);
                    if (fresh != null) refreshToc(fresh);
                } catch (IOException ignored) {
                }
            });
        }
        return new long[]{b.id, idx};
    }

    /**
     * The page open in the browser is this chapter, and the reader is about to show it: its text is stored, so
     * the site is not asked for the same page a second time. A chapter split over pages is left to the loader,
     * which is handed this first page.
     * @return true when the text was stored
     */
    private static boolean keepText(Chapter c, PageResult r) {
        ChapterContent cc = r.chapter;
        if (c.cached || cc == null || cc.errorPage || cc.text == null || Texts.wlen(cc.text) < 2) return false;
        if (cc.nextInChapter) {
            PageFetcher.offer(r);
            return false;
        }
        try {
            write(file(c.bookId, c.id), cc.text);
        } catch (IOException e) {
            return false;
        }
        ContentValues v = new ContentValues();
        v.put("cached", 1);
        // a chapter known only from a "next" link has no name until its page is read
        String t = ContentCleaner.stripPageMark(cc.title);
        if (c.title.isEmpty() && !t.isEmpty()) v.put("title", t);
        Db.get().update("chapters", v, "id=?", new String[]{String.valueOf(c.id)});
        Book b = Db.book(c.bookId);
        if (b != null && b.linked && cc.nextUrl != null) appendLinked(b.id, c.idx, cc.nextUrl);
        if (v.containsKey("title")) Events.post(Events.BOOK, c.bookId);
        return true;
    }

    /**
     * A page the user has in front of them (the reader's verification window) turned out to be a chapter of a
     * book: its text is kept, so that showing it costs the site nothing more.
     */
    public static boolean keepSniffed(PageResult r) {
        if (r == null || r.chapter == null) return false;
        Chapter known = Db.chapterByKey(Urls.normalize(r.url));
        if (known == null || !keepText(known, r)) return false;
        Events.post(Events.CACHE, known.bookId);
        return true;
    }

    private static int indexOf(List<Chapter> chapters, String urlKey, String title) {
        for (Chapter c : chapters) if (c.urlKey.equals(urlKey)) return c.idx;
        String bare = Texts.bare(ContentCleaner.stripPageMark(title));
        if (bare.length() < 2) return -1;
        for (Chapter c : chapters) if (Texts.bare(c.title).equals(bare)) return c.idx;
        return -1;
    }

    private static void fillMeta(Book b, PageResult.BookInfo info) {
        if (b.author.isEmpty()) b.author = info.author;
        if (b.cover.isEmpty()) b.cover = info.cover;
        if (b.intro.isEmpty()) b.intro = info.intro;
        if ((b.title.isEmpty() || b.title.equals(Urls.host(b.detailUrl)) || b.linked) && !info.title.isEmpty()) b.title = info.title;
    }

    /** In linked mode, registers the chapter that follows chapter #idx (if it is the last known one). */
    private static void appendLinked(long bookId, int idx, String nextUrl) {
        String key = Urls.normalize(nextUrl);
        SQLiteDatabase db = Db.get();
        synchronized (Library.class) {
            if (Db.count("SELECT COUNT(*) FROM chapters WHERE bookId=? AND urlKey=?", String.valueOf(bookId), key) > 0) return;
            int n = Db.count("SELECT COUNT(*) FROM chapters WHERE bookId=?", String.valueOf(bookId));
            if (idx != n - 1) return;
            Db.insertChapter(bookId, n, "", nextUrl, key);
            db.execSQL("UPDATE books SET chapterCount=? WHERE id=?", new Object[]{n + 1, bookId});
        }
        Events.post(Events.BOOK, bookId);
    }

    // ------------------------------------------------------------------ chapter list

    /**
     * Re-reads the chapter list from the web (following list pagination and "all chapters" links)
     * and merges it into the database. @return number of chapters that are new.
     */
    public static int refreshToc(Book b) throws IOException {
        return refreshToc(b, null);
    }

    /** @param have the contents page as the browser shows it right now, used instead of fetching it when it has a list */
    private static int refreshToc(Book b, PageResult have) throws IOException {
        if (b.tocUrl.isEmpty()) return 0;
        // Two parts of the app can want the same list at the same moment (a book just opened from its page is
        // completed in the background while the reader, finding no chapters yet, asks too): the second waits
        // for the first and takes its answer instead of reading every page of the list again.
        synchronized (lock(TOC_LOCKS, b.id)) {
            Object[] last = TOC_DONE.get(b.id);
            if (last != null && System.currentTimeMillis() - (Long) last[0] < 5000) {
                if (last[1] != null) throw new IOException(((IOException) last[1]).getMessage(), (IOException) last[1]);
                Book now = Db.book(b.id);
                if (now != null) {
                    b.tocUrl = now.tocUrl;
                    b.linked = now.linked;
                }
                return 0;
            }
            try {
                int added = readToc(b, have);
                TOC_DONE.put(b.id, new Object[]{System.currentTimeMillis(), null});
                return added;
            } catch (IOException e) {
                TOC_DONE.put(b.id, new Object[]{System.currentTimeMillis(), e});
                throw e;
            }
        }
    }

    private static final Object[] TOC_LOCKS = locks(16), TEXT_LOCKS = locks(64);
    /** Book id to {when its list was last read, the IOException that ended it or null}. */
    private static final Map<Long, Object[]> TOC_DONE = new java.util.concurrent.ConcurrentHashMap<>();

    private static Object[] locks(int n) {
        Object[] o = new Object[n];
        for (int i = 0; i < n; i++) o[i] = new Object();
        return o;
    }

    private static Object lock(Object[] set, long id) {
        return set[(int) ((id & 0x7fffffff) % set.length)];
    }

    private static int readToc(Book b, PageResult have) throws IOException {
        boolean reuse = have != null && !have.toc.isEmpty() && Urls.normalize(have.url).equals(Urls.normalize(b.tocUrl));
        PageResult r = reuse ? have : PageFetcher.fetch(b.tocUrl, b.detailUrl, false);
        PageResult.BookInfo info = r.book;
        String tocUrl = b.tocUrl;
        if (r.moreTocUrl != null && r.toc.size() < 40) {
            try {
                PageResult more = PageFetcher.fetch(r.moreTocUrl, b.tocUrl, false);
                if (more.toc.size() > r.toc.size()) {
                    tocUrl = r.moreTocUrl;
                    r = more;
                }
            } catch (IOException ignored) {
            }
        }
        List<List<ChapterRef>> pages = new ArrayList<>();
        pages.add(r.toc);
        Set<String> seenPages = new HashSet<>();
        seenPages.add(Urls.normalize(tocUrl));
        Set<String> seenChapters = new HashSet<>();
        for (ChapterRef c : r.toc) seenChapters.add(Urls.normalize(c.url));
        if (!r.tocPages.isEmpty()) {
            for (String p : r.tocPages) {
                if (pages.size() >= 400 || !seenPages.add(Urls.normalize(p))) continue;
                List<ChapterRef> fresh = fresh(PageFetcher.fetch(p, tocUrl, false).toc, seenChapters);
                if (!fresh.isEmpty()) pages.add(fresh);
            }
        } else {
            String next = r.tocNextUrl;
            String ref = tocUrl;
            while (next != null && pages.size() < 400 && seenPages.add(Urls.normalize(next))) {
                PageResult p = PageFetcher.fetch(next, ref, false);
                List<ChapterRef> fresh = fresh(p.toc, seenChapters);
                if (fresh.isEmpty()) break;
                pages.add(fresh);
                ref = next;
                next = p.tocNextUrl;
            }
        }
        // newest-first paginated lists: every page is already flipped, so flip the page order too
        if (pages.size() >= 2) {
            int a = firstNumber(pages.get(0)), z = firstNumber(pages.get(pages.size() - 1));
            if (a >= 0 && z >= 0 && z < a) java.util.Collections.reverse(pages);
        }
        List<ChapterRef> list = new ArrayList<>();
        for (List<ChapterRef> p : pages) list.addAll(p);
        if (list.isEmpty()) throw new IOException("No chapter list found");

        Book fresh = Db.book(b.id);
        if (fresh == null) return 0;
        List<Chapter> old = Db.chapters(b.id);
        if (fresh.linked && !old.isEmpty()) {
            // The contents URL of a linked book is often only a guess (the parent directory), so the
            // page must be recognised as a book page in its own right and contain the chapter being read.
            if (r.type != PageResult.Type.DETAIL) return 0;
            Chapter cur = old.get(Math.max(0, Math.min(fresh.curIdx, old.size() - 1)));
            boolean found = false;
            String bare = Texts.bare(cur.title);
            for (ChapterRef c : list) {
                if (Urls.normalize(c.url).equals(cur.urlKey) || (bare.length() >= 2 && Texts.bare(c.title).equals(bare))) {
                    found = true;
                    break;
                }
            }
            if (!found) return 0;
        }
        fillMeta(fresh, info);
        fresh.tocUrl = tocUrl;
        // chapters only count as "new" once the complete list has been seen before
        boolean established = fresh.lastCheckAt > 0 && !fresh.linked;
        fresh.linked = false;
        fresh.lastCheckAt = System.currentTimeMillis();
        int added = mergeChapters(fresh, list, established);
        b.tocUrl = fresh.tocUrl;
        b.linked = false;
        return added;
    }

    private static List<ChapterRef> fresh(List<ChapterRef> in, Set<String> seen) {
        List<ChapterRef> out = new ArrayList<>();
        for (ChapterRef c : in) if (seen.add(Urls.normalize(c.url))) out.add(c);
        return out;
    }

    private static int firstNumber(List<ChapterRef> page) {
        for (ChapterRef c : page) {
            int n = Texts.chapterNumber(c.title);
            if (n >= 0) return n;
        }
        return -1;
    }

    /**
     * Replaces a book's chapter list, keeping rows (and their cached text) whose URL is still present.
     * Saves the book row as well. @return number of added chapters (0 unless countNew).
     */
    private static synchronized int mergeChapters(Book b, List<ChapterRef> list, boolean countNew) {
        SQLiteDatabase db = Db.get();
        List<Chapter> old = Db.chapters(b.id);
        Map<String, Chapter> byKey = new HashMap<>();
        for (Chapter c : old) byKey.put(c.urlKey, c);
        String curKey = old.isEmpty() ? null : old.get(Math.max(0, Math.min(b.curIdx, old.size() - 1))).urlKey;
        String curTitle = old.isEmpty() ? "" : Texts.bare(old.get(Math.max(0, Math.min(b.curIdx, old.size() - 1))).title);
        int added = 0, newCur = -1, titleCur = -1;
        db.beginTransaction();
        try {
            Set<Long> kept = new HashSet<>();
            int idx = 0;
            for (ChapterRef ref : list) {
                String key = Urls.normalize(ref.url);
                Chapter c = byKey.get(key);
                if (c != null && kept.contains(c.id)) continue;
                if (c != null) {
                    kept.add(c.id);
                    // never trade a real chapter title for a worse one (file names, bare numbers)
                    boolean keepTitle = Texts.isChapterTitle(c.title) && !Texts.isChapterTitle(ref.title);
                    String title = keepTitle ? c.title : ref.title;
                    if (c.idx != idx || !c.title.equals(title)) {
                        ContentValues v = new ContentValues();
                        v.put("idx", idx);
                        v.put("title", title);
                        db.update("chapters", v, "id=?", new String[]{String.valueOf(c.id)});
                    }
                } else {
                    Db.insertChapter(b.id, idx, ref.title, ref.url, key);
                    added++;
                }
                if (key.equals(curKey)) newCur = idx;
                if (titleCur < 0 && curTitle.length() >= 2 && Texts.bare(ref.title).equals(curTitle)) titleCur = idx;
                idx++;
            }
            for (Chapter c : old) {
                if (kept.contains(c.id)) continue;
                db.delete("chapters", "id=?", new String[]{String.valueOf(c.id)});
                file(b.id, c.id).delete();
            }
            if (!old.isEmpty()) {
                int cur = newCur >= 0 ? newCur : titleCur >= 0 ? titleCur : Math.min(b.curIdx, idx - 1);
                if (cur != b.curIdx && newCur < 0) b.curOffset = 0;
                b.curIdx = Math.max(0, cur);
            }
            b.chapterCount = idx;
            b.lastChapter = list.isEmpty() ? "" : list.get(list.size() - 1).title;
            if (!countNew) added = 0;
            if (added > 0) {
                b.newCount += added;
                b.updatedAt = System.currentTimeMillis();
            }
            db.update("books", Db.values(b), "id=?", new String[]{String.valueOf(b.id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Events.post(Events.BOOK, b.id);
        return added;
    }

    // ------------------------------------------------------------------ chapter text

    /** Cached text of a chapter, or null when it is not cached. Cheap enough for the main thread. */
    public static String cachedText(Chapter c) {
        File f = file(c.bookId, c.id);
        if (!f.exists()) return null;
        try {
            return read(f);
        } catch (IOException e) {
            return null;
        }
    }

    /** Returns the chapter text, downloading and caching it when needed (merging paginated chapters). */
    public static String loadChapter(Book b, Chapter c, boolean force) throws IOException {
        return loadChapter(b, c, force, null);
    }

    /** Receives the text of a chapter before {@link #loadChapter} returns. */
    public interface Soon {
        /** Called once at most, on the loading thread. */
        void onText(String text);
    }

    /**
     * @param soon the last chapter of a book without a chapter list is also asked where the book goes on, which
     *             can take much longer than the text: soon gets the text (already stored) when that search begins.
     */
    public static String loadChapter(final Book b, final Chapter c, boolean force, final Soon soon) throws IOException {
        final File f = file(b.id, c.id);
        if ((!force || b.local()) && f.exists()) return read(f);
        if (b.local()) throw new IOException("The text of this imported book is missing");
        // The reader and a caching job can reach the same chapter together: one of them fetches, the other
        // waits and reads what was stored.
        synchronized (lock(TEXT_LOCKS, c.id)) {
            if (!force && f.exists()) return read(f);
            if (force) PageFetcher.forget(c.url);
            return fetchChapter(b, c, force, soon, f);
        }
    }

    private static String fetchChapter(final Book b, final Chapter c, boolean force, final Soon soon, final File f) throws IOException {

        StringBuilder text = new StringBuilder();
        String title = null, url = c.url, referer = Texts.or(b.tocUrl, b.detailUrl), nextChapter = null;
        Set<String> seen = new HashSet<>();
        seen.add(Urls.normalize(url));
        // a book without a chapter list learns of the next chapter only from the last one's page
        boolean needNext = b.linked && c.idx == Db.count("SELECT COUNT(*) FROM chapters WHERE bookId=?", String.valueOf(b.id)) - 1;
        final boolean[] told = {false};
        PageFetcher.Early early = soon == null || !needNext ? null : r -> {
            ChapterContent e = r.chapter;
            if (told[0] || e == null || e.errorPage || e.nextInChapter || Texts.wlen(e.text) < 2) return;
            try {
                write(f, e.text);
            } catch (IOException x) {
                return;
            }
            told[0] = true;
            ContentValues v = new ContentValues();
            v.put("cached", 1);
            String t = ContentCleaner.stripPageMark(e.title);
            if (c.title.isEmpty() && !t.isEmpty()) {
                v.put("title", t);
                c.title = t;
            }
            Db.get().update("chapters", v, "id=?", new String[]{String.valueOf(c.id)});
            c.cached = true;
            soon.onText(e.text);
        };
        for (int page = 0; page < 40; page++) {
            PageResult r = PageFetcher.fetch(url, referer, true, needNext, page == 0 ? early : null);
            ChapterContent cc = r.chapter;
            // The site's "not found" or "try again" page is not the chapter, and half a chapter is not one either:
            // nothing is stored, so the same address is simply asked again next time.
            if (cc != null && cc.errorPage) throw new FetchError(FetchError.ERROR_PAGE, 0, cc.title);
            if (cc == null) {
                if (page == 0) throw new FetchError(FetchError.NO_TEXT, 0, null);
                break;
            }
            String t = ContentCleaner.stripPageMark(cc.title);
            if (page > 0 && title != null && !title.isEmpty() && !t.isEmpty() && !Texts.bare(t).equals(Texts.bare(title))) {
                nextChapter = url; // that was already the next chapter, not a continuation page
                break;
            }
            if (page == 0) title = t;
            if (text.length() > 0) text.append('\n');
            text.append(cc.text);
            nextChapter = cc.nextInChapter ? null : cc.nextUrl;
            if (!cc.nextInChapter || cc.nextUrl == null || !seen.add(Urls.normalize(cc.nextUrl))) break;
            referer = url;
            url = cc.nextUrl;
        }
        String body = text.toString();
        if (Texts.wlen(body) < 2) throw new FetchError(FetchError.NO_TEXT, 0, null);
        write(f, body);
        ContentValues v = new ContentValues();
        v.put("cached", 1);
        // A chapter found through a "next" link is named by its page, so loading it again may rename it (the first
        // answer may have been something else). A title from a chapter list only gives way to a proper one.
        boolean rename = force && title != null && !title.equals(c.title)
                && (b.linked || (!Texts.isChapterTitle(c.title) && Texts.isChapterTitle(title)));
        if ((c.title.isEmpty() || rename) && title != null && !title.isEmpty()) {
            v.put("title", title);
            c.title = title;
        }
        Db.get().update("chapters", v, "id=?", new String[]{String.valueOf(c.id)});
        c.cached = true;
        if (b.linked && nextChapter != null) appendLinked(b.id, c.idx, nextChapter);
        return body;
    }

    // ------------------------------------------------------------------ shelf maintenance

    public static void setShelf(long bookId, boolean in) {
        ContentValues v = new ContentValues();
        v.put("inShelf", in ? 1 : 0);
        if (in) v.put("addedAt", System.currentTimeMillis());
        Db.updateBook(bookId, v);
        Events.post(Events.SHELF, bookId);
    }

    public static void saveProgress(long bookId, int idx, int offset) {
        ContentValues v = new ContentValues();
        v.put("curIdx", idx);
        v.put("curOffset", offset);
        v.put("lastReadAt", System.currentTimeMillis());
        v.put("newCount", 0);
        Db.updateBook(bookId, v);
    }

    public static void deleteBook(long bookId) {
        SQLiteDatabase db = Db.get();
        db.delete("chapters", "bookId=?", new String[]{String.valueOf(bookId)});
        db.delete("books", "id=?", new String[]{String.valueOf(bookId)});
        deleteDir(dir(bookId));
        Events.post(Events.SHELF, bookId);
    }

    public static void clearCache(long bookId) {
        Book b = Db.book(bookId);
        // an imported book has no source to fetch the text from again
        if (b != null && b.local()) return;
        Db.get().execSQL("UPDATE chapters SET cached=0 WHERE bookId=?", new Object[]{bookId});
        deleteDir(dir(bookId));
        Events.post(Events.CACHE, bookId);
    }

    /** Checks every shelf book for new chapters. @return total number of new chapters. */
    public static int checkUpdates() {
        int total = 0;
        for (Book b : Db.books("inShelf=1 AND linked=0", null)) {
            try {
                total += refreshToc(b);
            } catch (IOException ignored) {
            }
        }
        Events.post(Events.SHELF, 0);
        return total;
    }

    /**
     * The user tried to read past the last known chapter of a book that grows link by link: asks the last
     * chapter's page again for its next link (it may have been an ad the first time, or the chapter is new).
     * @return true when a chapter was added.
     */
    public static boolean extendLinked(Book b) throws IOException {
        List<Chapter> all = Db.chapters(b.id);
        if (!b.linked || all.isEmpty()) return false;
        Chapter last = all.get(all.size() - 1);
        // an unread tail entry is itself the next chapter; loading it is the reader's job
        if (!last.cached) return false;
        File f = file(b.id, last.id), keep = new File(f.getPath() + ".keep");
        boolean saved = f.exists() && f.renameTo(keep);
        try {
            loadChapter(b, last, true);
        } catch (IOException e) {
            if (saved) keep.renameTo(f); // never lose text the reader already has
            throw e;
        }
        keep.delete();
        return Db.chapters(b.id).size() > all.size();
    }

    /**
     * Books that an older version unlinked without ever merging a chapter list (mergeChapters always sets
     * lastChapter) were frozen at the chapters read so far: let them grow again. Runs at app start.
     */
    public static void repair() {
        try {
            Db.get().execSQL("UPDATE books SET linked=1 WHERE linked=0 AND lastCheckAt=0"
                    + " AND IFNULL(lastChapter,'')='' AND chapterCount>0");
        } catch (RuntimeException ignored) {
        }
    }

    /** Drops books that were only peeked at (never shelved) and not opened for a week. */
    public static void cleanup() {
        repair();
        try {
            long limit = System.currentTimeMillis() - WEEK;
            for (Book b : Db.books("inShelf=0 AND lastReadAt<" + limit, null)) deleteBook(b.id);
        } catch (RuntimeException ignored) {
        }
    }

    /** Writes all cached chapters as one text file. @return number of chapters written. */
    public static int exportTxt(Book b, OutputStream out) throws IOException {
        int n = 0;
        try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            w.write(b.title + "\n");
            if (!b.author.isEmpty()) w.write(b.author + "\n");
            w.write("\n");
            for (Chapter c : Db.chapters(b.id)) {
                String text = cachedText(c);
                if (text == null) continue;
                w.write("\n" + c.displayTitle() + "\n\n");
                for (String p : text.split("\n")) w.write("    " + p + "\n");
                n++;
            }
        }
        return n;
    }
}
