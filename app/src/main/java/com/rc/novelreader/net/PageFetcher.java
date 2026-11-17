package com.rc.novelreader.net;

import com.rc.novelreader.App;
import com.rc.novelreader.sniff.PageResult;
import com.rc.novelreader.util.Urls;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Fetches a URL and runs the sniffer on it: fast HTTP first, rendered WebView when that is not good enough. */
public final class PageFetcher {
    private PageFetcher() {}

    /** Hosts where only the rendered page worked; HTTP is skipped for them from then on. */
    private static final Set<String> RENDER_ONLY = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

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
        PageResult best = null;
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
                PageResult r = fetchOnce(url, referer, wantChapter, needNext, attempt);
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

    private static PageResult fetchOnce(final String url, String referer, final boolean wantChapter, boolean needNext, final int attempt) throws IOException {
        boolean lastTry = attempt == TRIES - 1;
        String host = Urls.host(url);
        PageResult best = null;
        IOException error = null;
        if (!RENDER_ONLY.contains(host)) {
            if (attempt == 0) stage(Watcher.CONNECT, attempt);
            try {
                Http.Resp resp = Http.get(url, referer);
                if (!Urls.sameSite(resp.url, url)) {
                    error = new Hijacked(Urls.host(resp.url));
                    if (!lastTry) throw error; // ask again
                    // the same answer every time: the site may simply have moved, so take the page if it is what was asked for
                    PageResult moved = App.SNIFFER.analyze(resp.url, resp.html);
                    if (good(moved, wantChapter)) return moved;
                } else if (resp.code >= 400) error = new FetchError(FetchError.HTTP, resp.code, null);
                else best = App.SNIFFER.analyze(resp.url, resp.html);
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
            if (good(best, wantChapter) && !(needNext && wantChapter && lacksNext(best))) return best;
        }
        // The plain answer was an error or not good enough: scripts, cookies or an anti-bot check may stand between
        // us and the page, and only a rendering engine gets past those.
        stage(Watcher.RENDER, attempt);
        final PageResult[] seen = new PageResult[1];
        String[] page = WebFetcher.fetch(url, referer, 25000, !(needNext && wantChapter) ? null : (at, html) -> {
            if (!Urls.sameSite(at == null ? url : at, url)) return false;
            seen[0] = App.SNIFFER.analyze(at == null ? url : at, html);
            if (!lacksNext(seen[0]) || seen[0].chapter.errorPage) return false;
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
                if (quality(rendered, wantChapter) > quality(best, wantChapter)) {
                    if (good(rendered, wantChapter)) RENDER_ONLY.add(host);
                    best = rendered;
                }
            } catch (RuntimeException e) {
                if (error == null) error = new IOException(e.toString());
            }
        }
        if (best == null) throw error != null ? error : new IOException("Empty page");
        if (page != null && page[2] != null && lacksNext(best)) {
            best.chapter.nextUrl = page[2];
            best.chapter.nextInChapter = Urls.sameChapter(best.url, page[2]);
        }
        return best;
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
