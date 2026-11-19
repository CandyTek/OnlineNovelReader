package com.rc.novelreader.reader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.data.Library;
import com.rc.novelreader.net.WebFetcher;
import com.rc.novelreader.sniff.PageResult;
import com.rc.novelreader.util.Urls;

/**
 * A site's "prove that you are human" check, shown over the book for the reader to pass. No program gets past
 * such a check, and trying only counts against the device; so the fetch stops, the page says so, and this
 * window is what its "Verify" button opens: the address that was refused, in a browser the reader can see and
 * touch. It shares cookies and the user agent with the fetchers, so whatever pass the site hands out here is
 * theirs too.
 *
 * The window watches the page. Once it has become what was asked for (the chapter, or the chapter list), the
 * chapter's text is stored straight from it and the window closes: passing the check costs the site the one
 * page the reader looked at, not a second request for the same chapter.
 */
final class VerifyWindow {
    private VerifyWindow() {}

    private static final String GRAB_JS = "(function(){var h=document.documentElement.outerHTML;"
            + "return h.length>3000000?'':location.href+'\\n'+h})()";

    /**
     * @param chapter the address is a chapter (else a chapter list)
     * @param done    called on the main thread when the window has closed, passed or not; may be null
     */
    @SuppressLint("SetJavaScriptEnabled")
    static void show(final Activity a, final String url, final boolean chapter, final Runnable done) {
        if (a.isFinishing() || a.isDestroyed() || url == null || !url.startsWith("http")) return;
        final Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(0xFFFFFFFF);

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(App.dp(16), App.dp(8), App.dp(6), App.dp(8));
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(a);
        title.setText(a.getString(R.string.verify_title) + " · " + Urls.host(url));
        title.setTextSize(16);
        title.setTextColor(0xFF1E2A26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView hint = new TextView(a);
        hint.setText(R.string.verify_hint);
        hint.setTextSize(12.5f);
        hint.setTextColor(0xFF6B7772);
        words.addView(title);
        words.addView(hint);
        head.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(a);
        close.setText(R.string.verify_close);
        close.setTextSize(15);
        close.setTextColor(0xFF1E7A5A);
        close.setGravity(Gravity.CENTER);
        close.setPadding(App.dp(14), 0, App.dp(14), 0);
        close.setMinHeight(App.dp(48));
        close.setOnClickListener(v -> d.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, App.dp(48)));
        root.addView(head, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, new LinearLayout.LayoutParams(-1, App.dp(3)));

        final WebView web = new WebView(a);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // the same visitor as the fetchers: a pass is often tied to the user agent it was given to
        s.setUserAgentString(App.UA);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // verification widgets live in frames of their own service
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));

        final boolean[] closed = {false}, busy = {false};
        final Runnable[] look = new Runnable[1];
        look[0] = () -> {
            if (closed[0]) return;
            App.MAIN.removeCallbacks(look[0]);
            // the check may give way without loading a new page: keep looking for as long as the window is up
            App.MAIN.postDelayed(look[0], 2500);
            if (busy[0]) return;
            busy[0] = true;
            web.evaluateJavascript(GRAB_JS, v -> {
                final String got = WebFetcher.decodeJs(v);
                final int cut = got.indexOf('\n');
                if (closed[0] || cut < 0 || !Urls.sameSite(got.substring(0, cut), url)) {
                    busy[0] = false;
                    return;
                }
                App.IO.execute(() -> {
                    boolean ok = false;
                    try {
                        PageResult r = App.SNIFFER.analyze(got.substring(0, cut), got.substring(cut + 1));
                        ok = chapter ? r.chapter != null && !r.chapter.errorPage
                                && (r.type == PageResult.Type.CHAPTER || r.chapter.wlen >= 300) : r.toc.size() >= 3;
                        // the reader has the chapter on screen: keep it, the book need not ask for it again
                        if (ok && chapter) Library.keepSniffed(r);
                    } catch (Throwable t) {
                        android.util.Log.w("Verify", t);
                    }
                    final boolean passed = ok;
                    App.MAIN.post(() -> {
                        busy[0] = false;
                        if (!passed || closed[0]) return;
                        App.toast(a.getString(R.string.verify_done));
                        d.dismiss();
                    });
                });
            });
        };
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // nothing here starts another app
                return !request.getUrl().toString().startsWith("http");
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                App.MAIN.removeCallbacks(look[0]);
                App.MAIN.postDelayed(look[0], 800);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                bar.setProgress(progress);
                bar.setVisibility(progress >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        d.setContentView(root);
        d.setOnDismissListener(x -> {
            closed[0] = true;
            App.MAIN.removeCallbacks(look[0]);
            // what the site handed out must be there for the plain fetcher's very next request
            CookieManager.getInstance().flush();
            web.stopLoading();
            root.removeView(web);
            web.destroy();
            if (done != null) done.run();
        });
        d.show();
        web.loadUrl(url);
    }
}
