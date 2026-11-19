package com.rc.novelreader.net;

import com.rc.novelreader.App;
import com.rc.novelreader.sniff.Challenge;
import com.rc.novelreader.sniff.PageResult;
import com.rc.novelreader.util.Urls;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Fetches a URL and runs the sniffer on it: fast HTTP first, rendered WebView when that is not good enough. */
public final class PageFetcher {
    private PageFetcher() {}

    /**
     * What fetching has taught about a host, kept across starts (prefs file "fetch_hosts") for a week, so that
     * the lesson does not cost a request again every time the app is opened:
     * "r" only the rendered page works there, HTTP is skipped;
     * "n" its chapters carry their next link in the plain markup, so a page without one is the last chapter and
     * need not be rendered and scrolled to find out.
     */
    private static final Map<String, Long> NOTES = new ConcurrentHashMap<>();
    private static boolean notesLoaded;
    private static final long NOTE_AGE = 7L * 24 * 3600 * 1000;

    private static android.content.SharedPreferences notes() {
        return App.I.getSharedPreferences("fetch_hosts", 0);
    }

    private static synchronized void loadNotes() {
        if (notesLoaded) return;
        notesLoaded = true;
        try {
            long now = System.currentTimeMillis();
            android.content.SharedPreferences.Editor old = null;
            for (Map.Entry<String, ?> e : notes().getAll().entrySet()) {
                long at = e.getValue() instanceof Long ? (Long) e.getValue() : 0;
                if (now - at < NOTE_AGE) NOTES.put(e.getKey(), at);
                else (old == null ? old = notes().edit() : old).remove(e.getKey());
            }
            if (old != null) old.apply();
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean noted(String kind, String host) {
        loadNotes();
        return NOTES.containsKey(kind + ":" + host);
    }

    private static void note(String kind, String host) {
        loadNotes();
        if (host == null || host.isEmpty() || NOTES.put(kind + ":" + host, System.currentTimeMillis()) != null) return;
        try {
            notes().edit().putLong(kind + ":" + host, System.currentTimeMillis()).apply();
        } catch (RuntimeException ignored) {
        }
    }

    private static void unnote(String kind, String host) {
        if (NOTES.remove(kind + ":" + host) == null) return;
        try {
            notes().edit().remove(kind + ":" + host).apply();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Hosts where rendering an answer that was an error (status or error page) gave the same error: the next
     * such answer from there is taken as it is, one request instead of two. Not kept: it is a guess about now.
     */
    private static final Set<String> RENDER_NO_HELP = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** Pages somebody already has (the browser showing them), by normalized address: {PageResult, time}. */
    private static final Map<String, Object[]> OFFERED = new ConcurrentHashMap<>();
    private static final long OFFER_AGE = 120000;

    /**
     * The page r was just sniffed from what the browser shows, and a fetch of the same address is about to
     * follow (reading mode on a chapter that goes on over more pages): that fetch takes r instead of asking the
     * site again. Good for one fetch, for two minutes.
     */
    public static void offer(PageResult r) {
        if (r == null || r.url == null) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Object[]> e : OFFERED.entrySet()) if (now - (Long) e.getValue()[1] > OFFER_AGE) OFFERED.remove(e.getKey());
        OFFERED.put(Urls.normalize(r.url), new Object[]{r, now});
    }

    /** A fetch that must ask the site (reload) drops what was offered for the address. */
    public static void forget(String url) {
        OFFERED.remove(Urls.normalize(url));
    }

    private static PageResult offered(String url) {
        Object[] o = OFFERED.remove(Urls.normalize(url));
        return o == null || System.currentTimeMillis() - (Long) o[1] > OFFER_AGE ? null : (PageResult) o[0];
    }

    /** The site answered with another site's page, or with a next link that leads to one: an ad took the place of the content. */
    private static final class Hijacked extends FetchError {
        Hijacked(String host) {
            super(OFFSITE, 0, host);
        }
    }

    /** What a fetch is doing right now, for whoever shows the wait to the user. */
    public interface Watcher {
        int CONNECT = 1, RENDER = 2, RETRY = 3, FIND_NEXT = 4;

        /** Called on the fetching thread. @param attempt 1-based, of {@link #TRIES} */
        void onStage(int stage, int attempt);
    }

    private static final ThreadLocal<Watcher> WATCHER = new ThreadLocal<>();

    /** Reports the stages of the fetches made on this thread to w until called again with null. */
    public static void watch(Watcher w) {
        if (w == null) WATCHER.remove();
        else WATCHER.set(w);
    }

    private static void stage(int stage, int attempt) {
        Watcher w = WATCHER.get();
        if (w != null) w.onStage(stage, attempt + 1);
    }

    public static final int TRIES = 3;

    /**
     * Sites that sell their navigation to ad networks answer some requests with a redirect to the advertiser, or
     * swap the advertiser into the "next" link. Those answers are random, so the same request is simply made again
     * (a few times, with a pause) instead of being taken as "no such chapter" or "the book ends here".
     */
    public static PageResult fetch(String url, String referer, boolean wantChapter) throws IOException {
        return fetch(url, referer, wantChapter, false);
    }

    /**
     * @param needNext the caller only learns of the next chapter from this page (a book without a chapter list):
     *                 when the page has no next link, it is rendered and scrolled to its end to see where it continues.
     */
    public static PageResult fetch(String url, String referer, boolean wantChapter, boolean needNext) throws IOException {
        return fetch(url, referer, wantChapter, needNext, null);
    }

    /** Hears of a chapter whose text is there while the fetch goes on looking for its next link. */
    public interface Early {
        /** Called on the fetching thread, possibly more than once. */
        void onChapter(PageResult r);
    }

    /**
     * @param early with needNext: told the chapter as soon as its text is known. Finding out where a page without
     *              a next link continues takes a rendering engine and many seconds, and the reader should not
     *              look at "loading" for that long when the text is already here.
     */
    public static PageResult fetch(String url, String referer, boolean wantChapter, boolean needNext, Early early) throws IOException {
        PageResult best = offered(url);
        if (good(best, wantChapter) && !(wantChapter && (best.chapter.nextHijacked || (needNext && lacksNext(best))))) return best;
        best = null;
        IOException error = null;
        for (int attempt = 0; attempt < TRIES; attempt++) {
            if (attempt > 0) {
                stage(Watcher.RETRY, attempt);
                try {
                    Thread.sleep(800L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                PageResult r = fetchOnce(url, referer, wantChapter, needNext, attempt, early);
                boolean hijackedNext = wantChapter && r.chapter != null && r.chapter.nextHijacked;
                if (best == null || !hijackedNext || quality(r, wantChapter) > quality(best, wantChapter)) best = r;
                if (!hijackedNext) return r;
            } catch (Hijacked e) {
                error = e;
            } catch (IOException e) {
                if (best != null) return best;
                throw e;
            }
        }
        if (best != null) return best;
        throw error != null ? error : new IOException("Empty page");
    }

    private static boolean lacksNext(PageResult r) {
        return r != null && r.chapter != null && r.chapter.nextUrl == null && !r.chapter.nextHijacked;
    }

    private static PageResult fetchOnce(final String url, String referer, final boolean wantChapter, boolean needNext, final int attempt, final Early early) throws IOException {
        boolean lastTry = attempt == TRIES - 1;
        String host = Urls.host(url);
        PageResult best = null;
        IOException error = null;
        // the last page seen was a "prove that you are human" check
        boolean challenged = false;
        // the plain answer was an error of the kind a rendering engine does not get past either
        boolean final_ = false, plainFailed = false;
        if (!noted("r", host)) {
            if (attempt == 0) stage(Watcher.CONNECT, attempt);
            try {
                Http.Resp resp = Http.get(url, referer);
                if (!Urls.sameSite(resp.url, url)) {
                    error = new Hijacked(Urls.host(resp.url));
                    if (!lastTry) throw error; // ask again
                    // the same answer every time: the site may simply have moved, so take the page if it is what was asked for
                    PageResult moved = App.SNIFFER.analyze(resp.url, resp.html);
                    if (good(moved, wantChapter)) return moved;
                } else {
                    PageResult plain = App.SNIFFER.analyze(resp.url, resp.html);
                    challenged = !good(plain, wantChapter) && Challenge.looksLike(resp.html);
                    if (resp.code >= 400) {
                        error = new FetchError(FetchError.HTTP, resp.code, null);
                        plainFailed = true;
                        // some sites route every address through their error handler: a page that is plainly
                        // what was asked for is taken, as it is from the rendering engine below
                        if (exact(plain, wantChapter)) best = plain;
                        // "Too many requests" is no page a script will repair, and rendering it is one request more
                        // against that count; a missing page stays missing where rendering did not help before.
                        else if (!challenged && (resp.code == 429
                                || ((resp.code == 404 || resp.code == 410) && RENDER_NO_HELP.contains(host)))) final_ = true;
                    } else {
                        best = plain;
                        if (plain.chapter != null && plain.chapter.errorPage) {
                            plainFailed = true;
                            if (!challenged && RENDER_NO_HELP.contains(host)) return best;
                        }
                    }
                }
            } catch (Hijacked e) {
                throw e;
            } catch (Http.OffSite e) {
                error = new Hijacked(e.host);
                if (!lastTry) throw error;
            } catch (IOException e) {
                error = e;
            } catch (RuntimeException e) {
                error = new IOException(e.toString());
            }
            if (final_) throw error;
            boolean ok = good(best, wantChapter);
            if (ok && wantChapter && best.chapter.nextUrl != null && !best.chapter.nextInChapter) note("n", host);
            if (ok && !(needNext && wantChapter && lacksNext(best))) return best;
            // This site writes the way on into its pages, and this page has none: it is the last chapter. Rendering
            // it and scrolling to its end (a second request, many seconds) would only confirm that.
            if (ok && noted("n", host)) return best;
            // the text is fine, only the way on is missing
            if (early != null && ok) early.onChapter(best);
        }
        // The plain answer was an error or not good enough: scripts, cookies or an anti-bot check may stand between
        // us and the page, and only a rendering engine gets past those.
        stage(Watcher.RENDER, attempt);
        final PageResult[] seen = new PageResult[1];
        String[] page = WebFetcher.fetch(url, referer, 25000, !(needNext && wantChapter) ? null : (at, html) -> {
            if (!Urls.sameSite(at == null ? url : at, url)) return false;
            seen[0] = App.SNIFFER.analyze(at == null ? url : at, html);
            // only a chapter is asked where it goes on: an error page or a human check has no next chapter to find
            if (!lacksNext(seen[0]) || !good(seen[0], true)) return false;
            if (early != null) early.onChapter(seen[0]);
            stage(Watcher.FIND_NEXT, attempt);
            return true;
        });
        if (page != null && page[3] != null && page[1] == null) {
            // the engine could not load the address at all: its own "not available" page is not content
            if (error == null) error = new IOException(page[3]);
            page = null;
        }
        if (page != null) {
            try {
                String at = page[0] == null ? url : page[0];
                PageResult rendered = !Urls.sameSite(at, url) ? null : seen[0] != null ? seen[0] : App.SNIFFER.analyze(at, page[1]);
                int status = page[3] == null ? 0 : Integer.parseInt(page[3]);
                // An error status usually comes with an error page, which must never pass for a chapter. Only a page
                // that is plainly what was asked for is taken (some sites route every address through their 404 handler).
                if (status >= 400 && rendered != null && !(wantChapter ? rendered.type == PageResult.Type.CHAPTER
                        : rendered.type == PageResult.Type.DETAIL && rendered.toc.size() >= 3)) {
                    rendered = null;
                    error = new FetchError(FetchError.HTTP, status, null);
                }
                boolean ok = good(rendered, wantChapter);
                challenged = !ok && Challenge.looksLike(page[1]);
                if (plainFailed) {
                    if (ok) RENDER_NO_HELP.remove(host);
                    else if (!challenged) RENDER_NO_HELP.add(host);
                }
                // the same text with the way on, which only the scripts of the page put there
                boolean hasNext = ok && wantChapter && lacksNext(best) && !lacksNext(rendered);
                if (quality(rendered, wantChapter) > quality(best, wantChapter) || hasNext) {
                    if (ok) note("r", host);
                    best = rendered;
                }
            } catch (RuntimeException e) {
                if (error == null) error = new IOException(e.toString());
            }
        }
        // the lesson "render only" no longer holds: the plain request gets its turn again next time
        if (!good(best, wantChapter) && noted("r", host)) unnote("r", host);
        // a check the hidden engine did not get past by itself: the user has to, in a browser that is seen
        if (challenged && !good(best, wantChapter)) throw new FetchError(FetchError.CAPTCHA, 0, host);
        if (best == null) throw error != null ? error : new IOException("Empty page");
        if (page != null && page[2] != null && lacksNext(best)) {
            best.chapter.nextUrl = page[2];
            best.chapter.nextInChapter = Urls.sameChapter(best.url, page[2]);
            // the plain page will never name the next chapter here: ask the rendering engine straight away
            note("r", host);
        }
        return best;
    }

    /** Plainly the page that was asked for, whatever status came with it. */
    private static boolean exact(PageResult r, boolean wantChapter) {
        if (r == null) return false;
        return wantChapter ? r.type == PageResult.Type.CHAPTER && good(r, true) : r.type == PageResult.Type.DETAIL && r.toc.size() >= 3;
    }

    private static boolean good(PageResult r, boolean wantChapter) {
        if (r == null) return false;
        if (wantChapter) return r.chapter != null && !r.chapter.errorPage && (r.type == PageResult.Type.CHAPTER || r.chapter.wlen >= 300);
        return r.toc.size() >= 3;
    }

    private static int quality(PageResult r, boolean wantChapter) {
        if (r == null) return -1;
        if (wantChapter) return r.chapter == null || r.chapter.errorPage ? 0 : r.chapter.wlen + (r.type == PageResult.Type.CHAPTER ? 200 : 0);
        return r.toc.size();
    }
}
