package com.rc.novelreader.browser;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.data.Db;
import com.rc.novelreader.data.RuleStore;
import com.rc.novelreader.net.WebFetcher;
import com.rc.novelreader.sniff.PageResult;
import com.rc.novelreader.util.Urls;

import java.io.File;

/**
 * One browser tab: a lazily created WebView plus everything that belongs to the page in it (ad-block counters,
 * sniffing result, per-tab switches). Restored and background-opened tabs cost nothing until they are shown.
 */
final class Tab {
    static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    static final String IPHONE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String NIGHT_JS = "(function(on){var id='__ns_night',s=document.getElementById(id);"
            + "if(on){if(!s){s=document.createElement('style');s.id=id;s.textContent="
            + "'html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}"
            + "img,video,picture,canvas,iframe,embed,object,svg image{filter:invert(1) hue-rotate(180deg)!important}';"
            + "(document.head||document.documentElement).appendChild(s);}}else if(s){s.remove();}})";

    final BrowserActivity a;
    final boolean incognito;
    /** Names the tab across restarts (its saved preview is filed under it). */
    long id = newId();
    /** What the tab showed when it was last left, for the tab views; null until one was taken or read back. */
    Bitmap thumb;
    WebView web;
    /** Address of the page ("" = start page) and what to load when the tab is first shown. */
    String url = "", title = "", pendingUrl;
    Tab parent;
    Bitmap icon;
    long lastActive;

    volatile String pageHost = "";
    volatile int blocked;
    boolean loading, desktop, cosmeticInjected;
    /** Asked for but not answered yet: onPageStarted only comes once the server has replied. */
    boolean starting;
    int progress;
    private String sslBadHost;

    int sniffSeq;
    /** Counts page loads: what was started for an earlier page gives up. */
    private int pageSeq;
    /** The reading-mode button asked for this look: a novel found by it is opened at once. */
    private boolean readAfterSniff;
    PageResult sniffed;
    /** The page has loaded but the sniffer has not yet said what it is. */
    private boolean sniffPending;
    /** A script-initiated jump to another site, held back until the sniffer has decided (see Client). */
    private String heldNavigation;
    /** The address the current navigation was started for (before any redirect), and how often it was re-asked. */
    private String navUrl;
    private int navRetries;
    private static final int NAV_RETRIES = 2;

    private static long lastId;

    private static synchronized long newId() {
        long n = System.currentTimeMillis();
        if (n <= lastId) n = lastId + 1;
        lastId = n;
        return n;
    }

    Tab(BrowserActivity a, boolean incognito) {
        this.a = a;
        this.incognito = incognito;
    }

    boolean isHome() {
        return url.isEmpty() && pendingUrl == null;
    }

    boolean secure() {
        return url.startsWith("https://") && !pageHost.equals(sslBadHost);
    }

    String label() {
        if (!title.isEmpty()) return title;
        return url.isEmpty() ? (pendingUrl == null ? a.getString(R.string.new_tab) : pendingUrl) : url;
    }

    String address() {
        return url.isEmpty() && pendingUrl != null ? pendingUrl : url;
    }

    static File offlineDir() {
        File d = new File(App.I.getFilesDir(), "offline");
        d.mkdirs();
        return d;
    }

    static boolean isOfflinePage(String u) {
        if (u == null || !u.startsWith("file://")) return false;
        try {
            String path = new File(Uri.parse(u).getPath()).getCanonicalPath();
            return path.startsWith(offlineDir().getCanonicalPath() + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    WebView view() {
        if (web != null) return web;
        web = new WebView(a);
        web.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        WebSettings s = web.getSettings();
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(true); // saved offline pages only, see isOfflinePage()
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(true);
        if (incognito) {
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            s.setSaveFormData(false);
        }
        applySettings();
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((u, ua, disposition, mime, length) -> a.onDownload(this, u, ua, disposition, mime, length));
        web.setOnLongClickListener(v -> a.onLongPress(this));
        return web;
    }

    /** Applies the global preferences and this tab's own switches. */
    void applySettings() {
        if (web == null) return;
        SharedPreferences p = App.prefs();
        WebSettings s = web.getSettings();
        String ua;
        if (desktop) ua = DESKTOP_UA;
        else {
            switch (p.getString("ua", "default")) {
                case "desktop": ua = DESKTOP_UA; break;
                case "iphone": ua = IPHONE_UA; break;
                case "custom":
                    ua = p.getString("ua_custom", "").trim();
                    if (ua.isEmpty()) ua = App.UA;
                    break;
                default: ua = App.UA;
            }
        }
        if (!ua.equals(s.getUserAgentString())) s.setUserAgentString(ua);
        s.setTextZoom(p.getInt("text_zoom", 100));
        boolean noImages = p.getBoolean("block_images", false);
        s.setLoadsImagesAutomatically(!noImages);
        s.setBlockNetworkImage(noImages);
        applySite(address());
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(p.getBoolean("cookies", true));
        cm.setAcceptThirdPartyCookies(web, p.getBoolean("third_party_cookies", false));
    }

    /** JavaScript can be switched off globally or for single sites. */
    private void applySite(String u) {
        if (web == null) return;
        boolean js = App.prefs().getBoolean("javascript", true) && !BrowserActivity.jsOff(Urls.host(u));
        if (web.getSettings().getJavaScriptEnabled() != js) web.getSettings().setJavaScriptEnabled(js);
    }

    void load(String u) {
        pendingUrl = null;
        view();
        applySite(u);
        url = u;
        title = "";
        navUrl = u;
        navRetries = 0;
        web.loadUrl(u);
        if (!u.startsWith("javascript:")) begin();
    }

    /**
     * Novel sites sell their own navigation: a tap on "next chapter" lands on an advertiser, either because a
     * script replaced the jump or because the server answered the chapter address with a redirect. Both are
     * random, so the address the reader actually asked for is requested again instead.
     * @return the address to load in place of u, or null when u is not such a hijack.
     */
    private String hijackedFrom(WebView view, WebResourceRequest request, String u, String cur) {
        if (request.isRedirect()) {
            if (navUrl == null || Urls.sameSite(u, navUrl) || !AdBlocker.isStrict(Urls.host(navUrl))) return null;
            // the same redirect every time is the site's own doing (login, a moved domain): let it through
            return navRetries++ < NAV_RETRIES ? navUrl : null;
        }
        navUrl = u;
        navRetries = 0;
        if (cur == null || !request.hasGesture() || Urls.sameSite(u, cur) || !AdBlocker.isStrict(Urls.host(cur))) return null;
        WebView.HitTestResult hit = view.getHitTestResult();
        String href = hit != null && hit.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE ? hit.getExtra() : null;
        if (href == null || !href.startsWith("http") || !Urls.sameSite(href, cur)) return null;
        if (Urls.normalize(href).equals(Urls.normalize(cur))) return null;
        navUrl = href;
        return href;
    }

    /** Called when the tab is brought to the front. */
    void activate() {
        lastActive = System.currentTimeMillis();
        view();
        if (pendingUrl != null) load(pendingUrl);
    }

    /** Frees the WebView but keeps the tab, which reloads when shown again. */
    void sleep() {
        if (web == null) return;
        if (!url.isEmpty()) pendingUrl = url;
        destroyWeb();
    }

    /** Shows the progress bar from the moment a page is asked for, not from the moment it answers. */
    void begin() {
        starting = true;
        progress = FIRST_PROGRESS;
        a.onTabChanged(this);
    }

    void reload() {
        if (web == null) return;
        web.reload();
        begin();
    }

    void stop() {
        if (web == null) return;
        web.stopLoading();
        starting = false;
        a.onTabChanged(this);
    }

    boolean busy() {
        return loading || starting;
    }

    /** Wide enough to be seen on a 2dp line; the engine's own first values are smaller. */
    static final int FIRST_PROGRESS = 12;

    void destroyWeb() {
        sniffSeq++;
        pageSeq++;
        sniffed = null;
        loading = starting = false;
        if (web == null) return;
        if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
        web.stopLoading();
        web.setWebChromeClient(null);
        web.destroy();
        web = null;
    }

    void applyNight() {
        if (web != null) web.evaluateJavascript(NIGHT_JS + "(" + App.prefs().getBoolean("web_night", false) + ")", null);
    }

    private void inject() {
        String js = AdBlocker.cosmeticScript(pageHost);
        if (js != null) web.evaluateJavascript(js, null);
        cosmeticInjected = true;
        if (App.prefs().getBoolean("web_night", false)) applyNight();
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            final String u = request.getUrl().toString();
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                if (u.startsWith("about:") || u.startsWith("blob:") || u.startsWith("data:")) return false;
                if (u.startsWith("file:")) return !isOfflinePage(u);
                if (request.hasGesture() && request.isForMainFrame()) a.askOpenApp(u);
                return true; // pages never launch other apps on their own
            }
            if (!request.isForMainFrame()) return false;
            String cur = view.getUrl();
            final String real = hijackedFrom(view, request, u, cur);
            if (real != null) {
                App.toast(a.getString(R.string.blocked_hijack, Urls.host(u)));
                blocked++;
                a.onTabChanged(Tab.this);
                view.post(() -> {
                    if (web != view) return;
                    navUrl = real;
                    view.loadUrl(real);
                });
                return true;
            }
            if (cur != null && AdBlocker.shouldBlockNavigation(u, cur, request.hasGesture(), request.isRedirect())) {
                App.toast(a.getString(R.string.blocked_redirect, Urls.host(u)));
                blocked++;
                a.onTabChanged(Tab.this);
                return true;
            }
            // A script that sends the reader to another site right after the page appeared is how pirate novel
            // sites deliver ads. On a first visit the site is not known yet, so the jump waits for the sniffer:
            // it is dropped if this turns out to be a novel page and carried out otherwise.
            if (cur != null && sniffPending && !request.hasGesture() && !request.isRedirect()
                    && AdBlocker.strictPossible(Urls.host(cur)) && !Urls.sameSite(u, cur)) {
                heldNavigation = u;
                return true;
            }
            applySite(u);
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String u, Bitmap favicon) {
            if (u == null || u.startsWith("about:")) url = url.startsWith("view-source:") ? url : "";
            else url = u;
            pageHost = Urls.host(url);
            title = "";
            icon = null;
            blocked = 0;
            progress = Math.max(progress, FIRST_PROGRESS);
            loading = true;
            starting = false;
            cosmeticInjected = false;
            sniffed = null;
            sniffSeq++;
            pageSeq++;
            readAfterSniff = false;
            sniffPending = false;
            heldNavigation = null;
            a.onTabChanged(Tab.this);
        }

        @Override
        public void onPageCommitVisible(WebView view, String u) {
            inject();
            // the new document is in place from here on: before, the script would still answer for the old one
            if (u != null && u.startsWith("http")) watchDom(pageSeq, 0);
        }

        @Override
        public void onPageFinished(WebView view, String u) {
            loading = starting = false;
            navUrl = null; // reloads and history moves start no tracked navigation, so nothing stale may remain
            if (u != null && !u.startsWith("about:")) url = u;
            String t = view.getTitle();
            title = t == null || t.equals(u) || t.startsWith("http") ? "" : t;
            a.onTabChanged(Tab.this);
            if (u == null) return;
            if (!u.startsWith("http")) {
                if (isOfflinePage(u)) inject();
                return;
            }
            inject();
            String scripts = UserScripts.forUrl(u);
            if (scripts != null) view.evaluateJavascript(scripts, null);
            final String pageTitle = title;
            if (!incognito) App.IO.execute(() -> Db.addHistory(u, pageTitle));
            if (App.prefs().getBoolean("auto_sniff", true)) {
                sniffPending = true;
                scheduleSniff(250, 0);
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String u, boolean isReload) {
            if (u != null && !u.startsWith("about:") && !u.startsWith("data:")) url = u;
            a.onTabChanged(Tab.this);
            // single-page sites change the URL without a page load
            if (!loading && sniffed != null && u != null && !Urls.normalize(u).equals(Urls.normalize(sniffed.url))) {
                sniffed = null;
                a.onTabChanged(Tab.this);
                if (App.prefs().getBoolean("auto_sniff", true)) scheduleSniff(900, 0);
            }
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String u = request.getUrl().toString();
            if (u.startsWith("file:")) return isOfflinePage(u) ? null : AdBlocker.emptyResponse();
            if (AdBlocker.shouldBlock(u, pageHost, request.isForMainFrame(), request.getRequestHeaders())) {
                blocked++;
                return AdBlocker.emptyResponse();
            }
            return null;
        }

        @Override
        public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
            final String host = Urls.host(error.getUrl());
            if (a.isFinishing() || !host.equals(pageHost) || a.current() != Tab.this) {
                handler.cancel();
                return;
            }
            new AlertDialog.Builder(a)
                    .setMessage(a.getString(R.string.ssl_error) + "\n\n" + host)
                    .setPositiveButton(R.string.proceed, (d, w) -> {
                        sslBadHost = host;
                        handler.proceed();
                        a.onTabChanged(Tab.this);
                    })
                    .setNegativeButton(R.string.cancel, (d, w) -> handler.cancel())
                    .setOnCancelListener(d -> handler.cancel())
                    .show();
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, final HttpAuthHandler handler, String host, String realm) {
            if (a.isFinishing() || a.current() != Tab.this) {
                handler.cancel();
                return;
            }
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = App.dp(20);
            box.setPadding(pad, App.dp(8), pad, 0);
            final EditText user = new EditText(a), pass = new EditText(a);
            user.setHint(R.string.username);
            user.setSingleLine(true);
            pass.setHint(R.string.password);
            pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            box.addView(user);
            box.addView(pass);
            new AlertDialog.Builder(a).setTitle(host).setMessage(realm).setView(box)
                    .setPositiveButton(R.string.ok, (d, w) -> handler.proceed(user.getText().toString(), pass.getText().toString()))
                    .setNegativeButton(R.string.cancel, (d, w) -> handler.cancel())
                    .setOnCancelListener(d -> handler.cancel())
                    .show();
        }

        @Override
        public void onFormResubmission(WebView view, final Message dontResend, final Message resend) {
            if (a.isFinishing() || a.current() != Tab.this) {
                dontResend.sendToTarget();
                return;
            }
            new AlertDialog.Builder(a).setMessage(R.string.resubmit_form)
                    .setPositiveButton(R.string.ok, (d, w) -> resend.sendToTarget())
                    .setNegativeButton(R.string.cancel, (d, w) -> dontResend.sendToTarget())
                    .setOnCancelListener(d -> dontResend.sendToTarget())
                    .show();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // the renderer crashed or was killed for memory: drop the view, keep the tab
            App.MAIN.post(() -> a.onTabCrashed(Tab.this));
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int p) {
            // link taps, history moves and redirects report progress before onPageStarted: that is a load too
            if (p >= 100) starting = false;
            else if (!loading) starting = true;
            progress = Math.max(p, FIRST_PROGRESS);
            if (p >= 35 && !cosmeticInjected && loading) inject();
            a.onTabChanged(Tab.this);
        }

        @Override
        public void onReceivedTitle(WebView view, String t) {
            title = t == null || t.startsWith("http") || t.startsWith("about:") ? "" : t;
            a.onTabChanged(Tab.this);
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap bitmap) {
            icon = bitmap;
            // keyed by the page the view is on now: the callback can arrive after the tab has moved on
            if (!incognito && view.getUrl() != null && view.getUrl().startsWith("http")) Favicons.put(view.getUrl(), bitmap);
        }

        /**
         * target=_blank and window.open(). Without a tap it is a pop-up and dropped; otherwise a throw-away view
         * learns the address first so ad pop-ups can be refused before a tab is opened.
         */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            if (!isUserGesture) {
                blocked++;
                a.onTabChanged(Tab.this);
                return false;
            }
            final String from = view.getUrl();
            final WebView probe = new WebView(a);
            probe.setWebViewClient(new WebViewClient() {
                private boolean handled;

                private void handle(String u) {
                    if (handled) return;
                    handled = true;
                    boolean ad = u == null || !u.startsWith("http")
                            || (from != null && AdBlocker.isOn(Urls.host(from)) && (AdBlocker.isAdHost(Urls.host(u))
                            || (AdBlocker.isStrict(Urls.host(from)) && !Urls.sameSite(u, from))));
                    if (ad) {
                        App.toast(a.getString(R.string.blocked_popup));
                        blocked++;
                        a.onTabChanged(Tab.this);
                    } else if (App.prefs().getBoolean("new_window_same_tab", false)) load(u);
                    else a.openTab(u, Tab.this, incognito, false);
                    App.MAIN.post(probe::destroy);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    handle(request.getUrl().toString());
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String u, Bitmap favicon) {
                    v.stopLoading();
                    handle(u);
                }
            });
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(probe);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            if (parent != null) a.closeTab(Tab.this);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            a.showCustomView(view, callback);
        }

        @Override
        public void onHideCustomView() {
            a.hideCustomView();
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            return a.chooseFile(callback, params);
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            a.askLocation(Tab.this, origin, callback);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            a.askMedia(Tab.this, request);
        }
    }

    // ------------------------------------------------------------------ sniffing

    void scheduleSniff(long delay, final int attempt) {
        final int seq = ++sniffSeq;
        App.MAIN.postDelayed(() -> sniff(seq, false, attempt), delay);
    }

    /**
     * A page is looked at twice (owner's request): two seconds after its markup has been read
     * (DOMContentLoaded), and again after its load event (onPageFinished). The first does not wait for
     * pictures and ad scripts, which on some sites never finish, so the reading-mode button no longer depends
     * on a page that "loads" for ever. This waits for the markup; it reads nothing from the network.
     */
    private void watchDom(final int seq, final int tries) {
        if (seq != pageSeq || web == null || !App.prefs().getBoolean("auto_sniff", true)) return;
        web.evaluateJavascript("document.readyState", v -> {
            if (seq != pageSeq || web == null) return;
            String state = WebFetcher.decodeJs(v);
            if (state.equals("interactive") || state.equals("complete")) {
                App.MAIN.postDelayed(() -> domScan(seq), 2000);
            } else if (state.equals("loading") && tries < 240) {
                App.MAIN.postDelayed(() -> watchDom(seq, tries + 1), 250);
            }
        });
    }

    private void domScan(int seq) {
        if (seq != pageSeq || web == null || sniffed != null) return;
        // while the page still loads, the look after the load event does the repeating
        sniff(++sniffSeq, false, loading ? 2 : 1);
    }

    void sniffNow() {
        readAfterSniff = false;
        sniff(++sniffSeq, true, 9);
    }

    /** The reading-mode button on a page that was not recognised (yet): look now, and read if it is a novel. */
    void sniffThenRead() {
        sniff(++sniffSeq, true, 9);
        readAfterSniff = true;
    }

    /** Hands the rendered DOM to the dual-engine sniffer on a background thread. */
    private void sniff(final int seq, final boolean manual, final int attempt) {
        if (seq != sniffSeq || web == null) return;
        final String u = web.getUrl();
        if (u == null || !u.startsWith("http")) return;
        web.evaluateJavascript("(function(){var h=document.documentElement.outerHTML;return h.length>5000000?'':h})()", v -> {
            final String html = WebFetcher.decodeJs(v);
            if (html.isEmpty() || seq != sniffSeq) {
                if (manual) App.toast(a.getString(R.string.sniff_none, 0, 0));
                if (seq == sniffSeq) settleHeldNavigation(false);
                return;
            }
            App.IO.execute(() -> {
                if (manual) dumpForDebugging(u, html);
                PageResult r;
                try {
                    r = App.SNIFFER.analyze(u, html);
                } catch (Throwable t) {
                    android.util.Log.w("Sniffer", t);
                    r = null;
                }
                final PageResult result = r;
                App.MAIN.post(() -> onSniffed(seq, result, manual, attempt));
            });
        });
    }

    /** Debuggable builds keep the DOM of manually sniffed pages so misdetections can be replayed in tests. */
    private void dumpForDebugging(String u, String html) {
        if ((a.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        try {
            File dir = new File(a.getFilesDir(), "sniff");
            dir.mkdirs();
            File f = new File(dir, System.currentTimeMillis() + ".html");
            try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8")) {
                w.write("<!--URL:" + u + "-->\n");
                w.write(html);
            }
        } catch (Exception ignored) {
        }
    }

    private void settleHeldNavigation(boolean novel) {
        sniffPending = false;
        String held = heldNavigation;
        heldNavigation = null;
        if (held == null || web == null) return;
        if (novel) {
            App.toast(a.getString(R.string.blocked_redirect, Urls.host(held)));
            blocked++;
            a.onTabChanged(this);
        } else load(held);
    }

    private void onSniffed(int seq, PageResult r, boolean manual, int attempt) {
        if (seq != sniffSeq || web == null) return;
        final boolean read = manual && readAfterSniff;
        if (manual) readAfterSniff = false;
        settleHeldNavigation(r != null && r.isNovel());
        if (r != null && r.isNovel()) {
            sniffed = r;
            boolean chapter = r.type == PageResult.Type.CHAPTER;
            if (r.confidence() >= 70 && !RuleStore.isNovelHost(pageHost)) {
                RuleStore.addNovelHost(pageHost);
                cosmeticInjected = false;
                web.evaluateJavascript("window.__nsInstalled=false", null);
                inject(); // re-run in strict mode now that the site is known
            }
            if (manual && !read) {
                App.toast(a.getString(R.string.sniff_result, a.getString(chapter ? R.string.type_chapter : R.string.type_detail),
                        r.engine, r.confidence()));
            }
            a.onTabChanged(this);
            if (read) a.readFrom(this);
            return;
        }
        if (manual) {
            App.toast(a.getString(R.string.sniff_none, r == null ? 0 : r.chapterScore, r == null ? 0 : r.detailScore));
        } else if (attempt < 2) {
            // content rendered late by script: look again
            App.MAIN.postDelayed(() -> sniff(seq, false, attempt + 1), attempt == 0 ? 1500 : 3500);
        }
    }
}
