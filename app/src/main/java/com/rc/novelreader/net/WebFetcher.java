package com.rc.novelreader.net;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;
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
     * @return {final url, html, address of the next chapter found by scrolling (or null)} or null.
     * Must not be called on the main thread.
     */
    public static String[] fetch(final String url, final String referer, final long timeoutMs, NextProbe probe) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("main thread");
        synchronized (LOCK) {
            final String[] out = new String[3];
            final CountDownLatch done = new CountDownLatch(1);
            final long deadline = SystemClock.uptimeMillis() + timeoutMs;
            final Runnable[] grab = new Runnable[1];
            App.MAIN.post(() -> {
                try {
                    ensureWebView();
                    grab[0] = () -> web.evaluateJavascript("document.documentElement.outerHTML", v -> {
                        if (done.getCount() == 0) return;
                        String html = decodeJs(v);
                        boolean interstitial = html.length() < 600 || html.contains("Just a moment")
                                || html.contains("challenge-platform") || html.contains("cf-browser-verification");
                        if (interstitial && SystemClock.uptimeMillis() < deadline - 2000) return; // wait for the real page
                        out[0] = web.getUrl();
                        out[1] = html;
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
                            App.MAIN.removeCallbacks(grab[0]);
                            App.MAIN.postDelayed(grab[0], 900);
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
            return out[1] == null || out[1].isEmpty() ? null : out;
        }
    }

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
     * Readers that have no "next" link continue when the reader reaches the bottom: the next chapter is appended
     * and the address bar moves on to it. This scrolls the page that is loaded right now to its end until the
     * address changes, and returns the new address (same site only), or null when nothing happens.
     */
    private static String scrollForNext(final String startUrl) {
        final String[] found = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        final long deadline = SystemClock.uptimeMillis() + 9000;
        final String startKey = Urls.normalize(startUrl);
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (web == null || done.getCount() == 0) return;
            web.evaluateJavascript(SCROLL_JS, v -> {
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
