package com.rc.novelreader.net;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.rc.novelreader.App;
import com.rc.novelreader.browser.AdBlocker;
import com.rc.novelreader.util.Urls;

import org.json.JSONTokener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Fallback fetcher: renders a page in an off-screen WebView and returns the resulting DOM.
 * Used when a plain HTTP fetch yields nothing usable (content built by JavaScript, anti-bot
 * interstitials, cookies set by script). One page at a time.
 */
public final class WebFetcher {
    private WebFetcher() {}

    private static final Object LOCK = new Object();
    private static WebView web;

    /** Decodes the JSON string literal handed to evaluateJavascript callbacks. */
    public static String decodeJs(String v) {
        if (v == null || v.equals("null") || v.isEmpty()) return "";
        try {
            Object o = new JSONTokener(v).nextValue();
            return o == null ? "" : o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Decides from the rendered page whether the fetcher should go looking for the next chapter by scrolling. */
    public interface NextProbe {
        boolean wanted(String finalUrl, String html);
    }

    public static String[] fetch(String url, String referer, long timeoutMs) {
        return fetch(url, referer, timeoutMs, null);
    }

    /**
     * @return {final url, html, address of the next chapter found by scrolling (or null), status} or null.
     * Status is the HTTP status of the page when it is an error (400 and up), else null. When the address could
     * not be loaded at all, html is null and status holds the reason.
     * Must not be called on the main thread.
     */
    public static String[] fetch(final String url, final String referer, final long timeoutMs, NextProbe probe) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("main thread");
        synchronized (LOCK) {
            final String[] out = new String[4];
            final Nav nav = new Nav();
            final CountDownLatch done = new CountDownLatch(1);
            final long deadline = SystemClock.uptimeMillis() + timeoutMs;
            final Runnable[] grab = new Runnable[1];
            App.MAIN.post(() -> {
                try {
                    ensureWebView();
                    grab[0] = () -> web.evaluateJavascript(GRAB_JS, v -> {
                        if (done.getCount() == 0) return;
                        String[] part = decodeJs(v).split("\n", 3);
                        if (part.length < 3) part = new String[]{"0", "", ""};
                        String html = part[2];
                        boolean late = SystemClock.uptimeMillis() >= deadline - 2000;
                        // the engine's own "webpage not available" page
                        // (an aborted load that a script replaced with a real page is not one: that has an address)
                        if (part[1].startsWith("chrome-error:") || (nav.failure != null && !part[1].startsWith("http"))) {
                            out[3] = nav.failure == null ? "The page could not be loaded" : nav.failure;
                            done.countDown();
                            return;
                        }
                        int status = 0;
                        try {
                            status = Integer.parseInt(part[0]);
                        } catch (NumberFormatException ignored) {
                        }
                        // engines that cannot tell the status of the document: the status seen while loading counts
                        // as long as no other page has finished since
                        if (status == 0 && nav.status >= 400 && nav.finishedSince <= 1) status = nav.status;
                        if (status >= 400 && !nav.waited && !late) {
                            // an anti-bot check answers with an error first and then reloads: give it a moment
                            nav.waited = true;
                            App.MAIN.postDelayed(grab[0], 1500);
                            return;
                        }
                        boolean challenge = html.contains("Just a moment") || html.contains("challenge-platform")
                                || html.contains("cf-browser-verification");
                        // a page still being built by its scripts is short; so is a site's error page, which never
                        // grows: an error status has had its moment above, anything else gets a few looks
                        boolean shell = html.length() < 600 && status < 400 && nav.looks++ < 4;
                        if ((challenge || shell) && !late) {
                            App.MAIN.removeCallbacks(grab[0]);
                            App.MAIN.postDelayed(grab[0], 1000); // wait for the real page
                            return;
                        }
                        out[0] = web.getUrl();
                        out[1] = html;
                        out[3] = status >= 400 ? String.valueOf(status) : null;
                        done.countDown();
                    });
                    web.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageStarted(WebView view, String u, Bitmap favicon) {
                            App.MAIN.removeCallbacks(grab[0]);
                        }

                        @Override
                        public void onPageFinished(WebView view, String u) {
                            if (u == null || u.startsWith("about:")) return;
                            nav.finishedSince++;
                            App.MAIN.removeCallbacks(grab[0]);
                            App.MAIN.postDelayed(grab[0], 900);
                        }

                        @Override
                        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                            if (!request.isForMainFrame()) return;
                            nav.status = response.getStatusCode();
                            nav.failure = null;
                            nav.finishedSince = 0;
                        }

                        @Override
                        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError err) {
                            if (!request.isForMainFrame()) return;
                            nav.failure = String.valueOf(err.getDescription());
                            nav.status = 0;
                            nav.finishedSince = 0;
                        }

                        @Override
                        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                            String u = request.getUrl().toString();
                            if (AdBlocker.shouldBlock(u, Urls.host(url), request.isForMainFrame(), request.getRequestHeaders())) {
                                return AdBlocker.emptyResponse();
                            }
                            return null;
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                            String u = request.getUrl().toString();
                            // background fetches never leave the site (ad redirects) or open other apps
                            return !u.startsWith("http") || (request.isForMainFrame() && !Urls.sameSite(u, url));
                        }
                    });
                    Map<String, String> headers = new HashMap<>();
                    if (referer != null && !referer.isEmpty()) headers.put("Referer", referer);
                    web.loadUrl(url, headers);
                } catch (Throwable t) {
                    done.countDown();
                }
            });
            try {
                if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    // timed out waiting for "finished": take whatever has rendered so far
                    App.MAIN.post(() -> {
                        if (grab[0] != null && web != null) grab[0].run();
                    });
                    done.await(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                if (probe != null && out[1] != null && !out[1].isEmpty() && probe.wanted(out[0], out[1])) {
                    out[2] = scrollForNext(out[0] == null ? url : out[0]);
                }
            } catch (RuntimeException e) {
                android.util.Log.w("WebFetcher", e);
            }
            App.MAIN.post(() -> {
                if (web == null) return;
                App.MAIN.removeCallbacks(grab[0]);
                web.stopLoading();
                web.setWebViewClient(new WebViewClient());
                web.loadUrl("about:blank");
            });
            if (out[3] != null && out[1] == null) return out;
            return out[1] == null || out[1].isEmpty() ? null : out;
        }
    }

    /** What the callbacks saw of the main document while it loaded. */
    private static final class Nav {
        /** Error status of the last main-frame answer, 0 when none. */
        int status;
        /** Why the last main-frame load failed (no answer at all), or null. */
        String failure;
        /** Pages finished since that error: the error page itself is the first. */
        int finishedSince;
        boolean waited;
        /** Times a short page was looked at again. */
        int looks;
    }

    /** Status of the document (0 when the engine does not tell), its address and its markup, one per line. */
    private static final String GRAB_JS = "(function(){var s=0;try{var n=performance.getEntriesByType('navigation')[0];"
            + "if(n&&n.responseStatus)s=n.responseStatus;}catch(e){}"
            + "return s+'\\n'+location.href+'\\n'+document.documentElement.outerHTML;})()";

    /** Scrolls to the end of the page, says so to the scripts of the page, and reports where the page thinks it is. */
    private static final String SCROLL_JS = "(function(){try{var d=document,e=d.scrollingElement||d.documentElement,"
            + "h=Math.max(e.scrollHeight,d.body?d.body.scrollHeight:0);window.scrollTo(0,h);"
            + "var all=d.querySelectorAll('*');for(var i=0;i<all.length&&i<4000;i++){var x=all[i];"
            + "if(x.scrollHeight>x.clientHeight+40&&x.clientHeight>200){x.scrollTop=x.scrollHeight;x.dispatchEvent(new Event('scroll'));}}"
            + "window.dispatchEvent(new Event('scroll'));d.dispatchEvent(new Event('scroll'));"
            // "pull up to continue" readers listen for the finger, not for the scroll position
            + "var w=window.innerWidth,v=window.innerHeight,x=w/2,t=d.elementFromPoint(x,v*0.6)||d.body;"
            + "var fire=function(type,y,end){var p=new Touch({identifier:1,target:t,clientX:x,clientY:y,pageX:x+window.scrollX,"
            + "pageY:y+window.scrollY,screenX:x,screenY:y});t.dispatchEvent(new TouchEvent(type,{bubbles:true,cancelable:true,"
            + "touches:end?[]:[p],targetTouches:end?[]:[p],changedTouches:[p]}));};"
            + "fire('touchstart',v*0.8);for(var k=1;k<=8;k++)fire('touchmove',v*(0.8-0.08*k));fire('touchend',v*0.16,true);"
            + "t.dispatchEvent(new WheelEvent('wheel',{bubbles:true,cancelable:true,deltaY:v,clientX:x,clientY:v*0.6}));"
            + "}catch(err){}return location.href;})()";

    /**
     * Presses the page's own "next chapter" control when it carries no address (a script decides where to go).
     * Returns 'y' when something was pressed. Among several candidates a visible one wins, then a link.
     */
    private static final String CLICK_JS = "(function(){try{"
            + "var re=/^(?:\\u52a0\\u8f7d|\\u9605\\u8bfb|\\u7ee7\\u7eed)?\\u4e0b\\u4e00?[\\u7ae0\\u8282\\u7bc7\\u8bdd\\u56de\\u9875]$|^next(?: ?chapter)?$/i;"
            + "var els=document.querySelectorAll('a,button,[data-type],[onclick],li,span,div'),pick=null,pv=false;"
            + "for(var i=0;i<els.length;i++){var x=els[i];if(x.children.length>2)continue;"
            + "var t=(x.textContent||'').replace(/[^\\w\\u4e00-\\u9fff ]/g,'').trim();if(t.length>12||!re.test(t))continue;"
            + "var v=x.offsetParent!==null;if(!pick||(v&&!pv)||(v==pv&&x.tagName=='A'&&pick.tagName!='A')){pick=x;pv=v;}}"
            + "if(pick){pick.click();return 'y';}}catch(err){}return 'n';})()";

    /**
     * Finds the next chapter of a page that has no usable "next" link, on the page that is loaded right now:
     * first by pressing its own next control (the address is computed by a script), then by scrolling to the end
     * (readers that append the next chapter when the reader reaches the bottom and move the address bar on).
     * Returns the address the page moves to (same site only), or null when nothing happens.
     */
    private static String scrollForNext(final String startUrl) {
        final String[] found = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        final long deadline = SystemClock.uptimeMillis() + 9000;
        final String startKey = Urls.normalize(startUrl);
        final Runnable[] step = new Runnable[1];
        // 0 = press the next control first; afterwards the time until which only the address is watched
        final long[] clickWait = {0};
        step[0] = () -> {
            if (web == null || done.getCount() == 0) return;
            if (clickWait[0] == 0) {
                web.evaluateJavascript(CLICK_JS, v -> {
                    clickWait[0] = "y".equals(decodeJs(v)) ? SystemClock.uptimeMillis() + 3500 : 1;
                    App.MAIN.postDelayed(step[0], clickWait[0] == 1 ? 0 : 300);
                });
                return;
            }
            web.evaluateJavascript(SystemClock.uptimeMillis() < clickWait[0] ? "location.href" : SCROLL_JS, v -> {
                String now = decodeJs(v);
                if (now.startsWith("http") && Urls.sameSite(now, startUrl) && !Urls.normalize(now).equals(startKey)) {
                    found[0] = now;
                    done.countDown();
                } else if (SystemClock.uptimeMillis() >= deadline) done.countDown();
                else App.MAIN.postDelayed(step[0], 450);
            });
        };
        App.MAIN.post(step[0]);
        try {
            done.await(11, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        App.MAIN.removeCallbacks(step[0]);
        return found[0];
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void ensureWebView() {
        if (web != null) return;
        web = new WebView(App.I);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(App.UA);
        s.setBlockNetworkImage(true);
        s.setLoadsImagesAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        web.layout(0, 0, 1080, 1920);
    }
}
