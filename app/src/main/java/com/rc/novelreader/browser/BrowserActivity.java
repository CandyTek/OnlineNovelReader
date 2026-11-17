package com.rc.novelreader.browser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.SearchManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.BuildConfig;
import com.rc.novelreader.R;
import com.rc.novelreader.data.Db;
import com.rc.novelreader.data.Library;
import com.rc.novelreader.net.Http;
import com.rc.novelreader.net.WebFetcher;
import com.rc.novelreader.reader.ReaderActivity;
import com.rc.novelreader.settings.SettingsActivity;
import com.rc.novelreader.shelf.BookSheet;
import com.rc.novelreader.shelf.ShelfActivity;
import com.rc.novelreader.sniff.PageResult;
import com.rc.novelreader.util.Urls;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The browser: tabs, start page, address bar with suggestions, page tools and site permissions around WebViews
 * that are ad-filtered and watched by the novel sniffer.
 */
public class BrowserActivity extends Activity {
    public static final String EXTRA_QUERY = "q";
    private static final int REQ_FILE = 11, REQ_PERM = 12;
    /** WebViews kept alive at once; older tabs are put to sleep and reload when shown. */
    private static final int MAX_LIVE = 6;

    private final List<Tab> tabs = new ArrayList<>();
    private final ArrayDeque<String> closed = new ArrayDeque<>();
    private Tab cur;

    private FrameLayout webHolder, customBox;
    private View home, topBar, findBar, bottomBar, bottomLine, exitFull;
    private LinearLayout homeBox;
    private EditText urlBox, findText;
    private ProgressBar progress;
    private TextView fab, blockedBadge, tabCount, findCount;
    private ImageButton btnReload, btnBack, btnForward, btnStar, btnClear, siteIcon;
    private ListView suggestList;

    private boolean homeDirty = true, fullscreen;
    private String starUrl;
    private boolean starOn;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;
    private Runnable permGranted, permDenied;

    /** A browser with its tabs exists in this app run (the shelf then steps aside instead of closing the app). */
    public static boolean alive;

    public static void open(Context c, String queryOrUrl) {
        Intent i = new Intent(c, BrowserActivity.class);
        i.putExtra(EXTRA_QUERY, queryOrUrl);
        c.startActivity(i);
    }

    // ------------------------------------------------------------------ per-site JavaScript switch

    private static Set<String> jsOffHosts;

    private static synchronized Set<String> jsOffSet() {
        if (jsOffHosts == null) {
            jsOffHosts = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
            jsOffHosts.addAll(App.prefs().getStringSet("js_off_hosts", new HashSet<String>()));
        }
        return jsOffHosts;
    }

    static boolean jsOff(String host) {
        return !host.isEmpty() && jsOffSet().contains(host);
    }

    private static void setJsOff(String host, boolean off) {
        if (off) jsOffSet().add(host);
        else jsOffSet().remove(host);
        App.prefs().edit().putStringSet("js_off_hosts", new HashSet<>(jsOffSet())).apply();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);
        webHolder = findViewById(R.id.webHolder);
        customBox = findViewById(R.id.customBox);
        home = findViewById(R.id.home);
        homeBox = findViewById(R.id.homeBox);
        topBar = findViewById(R.id.topBar);
        findBar = findViewById(R.id.findBar);
        bottomBar = findViewById(R.id.bottomBar);
        bottomLine = findViewById(R.id.bottomLine);
        exitFull = findViewById(R.id.exitFull);
        urlBox = findViewById(R.id.url);
        findText = findViewById(R.id.findText);
        findCount = findViewById(R.id.findCount);
        progress = findViewById(R.id.progress);
        fab = findViewById(R.id.fab);
        blockedBadge = findViewById(R.id.blocked);
        tabCount = findViewById(R.id.tabCount);
        btnReload = findViewById(R.id.btnReload);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        btnStar = findViewById(R.id.btnStar);
        btnClear = findViewById(R.id.btnClear);
        siteIcon = findViewById(R.id.siteIcon);
        suggestList = findViewById(R.id.suggest);
        suggestList.setAdapter(suggestAdapter);
        suggestList.setOnItemClickListener((p, v, pos, id) -> pickSuggestion(suggestions.get(pos)));

        App.numpadEnter(urlBox);
        App.numpadEnter(findText);
        urlBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                go(urlBox.getText().toString());
                return true;
            }
            return false;
        });
        urlBox.setOnFocusChangeListener((v, has) -> {
            btnClear.setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) {
                String u = cur.address();
                urlBox.setText(u);
                urlBox.selectAll();
                suggestList.setVisibility(View.VISIBLE);
                updateSuggestions();
            } else {
                suggestSeq++;
                suggestList.setVisibility(View.GONE);
            }
            refreshUi();
        });
        urlBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (urlBox.hasFocus()) updateSuggestions();
            }
        });
        btnClear.setOnClickListener(v -> urlBox.setText(""));
        btnStar.setOnClickListener(v -> toggleBookmark());
        siteIcon.setOnClickListener(v -> {
            if (cur.isHome()) focusUrl();
            else showPageInfo();
        });
        btnReload.setOnClickListener(v -> {
            if (cur.web == null) return;
            if (cur.busy()) cur.stop();
            else cur.reload();
        });
        btnBack.setOnClickListener(v -> goBack(true));
        btnForward.setOnClickListener(v -> {
            if (cur.web != null && cur.web.canGoForward()) cur.web.goForward();
        });
        findViewById(R.id.btnShelf).setOnClickListener(v -> toShelf());
        findViewById(R.id.btnHome).setOnClickListener(v -> goHome());
        findViewById(R.id.btnTabs).setOnClickListener(v -> showTabs());
        findViewById(R.id.btnMenu).setOnClickListener(v -> showMenu());
        // what a long press does is the user's choice per button (settings); "none" leaves it a plain tap
        View[] held = {btnBack, btnForward, findViewById(R.id.btnHome), findViewById(R.id.btnTabs), findViewById(R.id.btnMenu), btnReload};
        final String[] names = {"back", "forward", "home", "tabs", "menu", "reload"};
        for (int i = 0; i < held.length; i++) {
            final String name = names[i];
            held[i].setOnLongClickListener(v -> runAction(BarActions.of(name)));
        }
        fab.setOnClickListener(v -> enterReadingMode());
        exitFull.setOnClickListener(v -> setFullscreen(false));
        setupFind();

        // "browser_front" still set: the last process died with the browser on screen (see saveTabsSoon), which
        // is the same loss as the system recreating it, so the tabs come back the same way
        if (savedInstanceState != null || App.prefs().getBoolean("restore_tabs", false)
                || App.prefs().getBoolean("browser_front", false)) restoreTabs();
        alive = true;
        handleIntent(getIntent());
        com.rc.novelreader.reader.ReaderActivity.resumeAfterKill(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        String q = i.getStringExtra(EXTRA_QUERY);
        if (q == null && i.getData() != null) q = i.getData().toString();
        if (q == null && Intent.ACTION_SEND.equals(i.getAction())) q = firstUrlOrText(i.getStringExtra(Intent.EXTRA_TEXT));
        if (q == null && Intent.ACTION_WEB_SEARCH.equals(i.getAction())) q = i.getStringExtra(SearchManager.QUERY);
        // an intent is delivered again when the activity is recreated: act on it once
        i.removeExtra(EXTRA_QUERY);
        i.setData(null);
        i.setAction(null);
        String u = q == null ? null : resolve(q);
        if (u != null) {
            // coming back from the reader to the page it was opened from: no second tab for the same address
            String key = Urls.normalize(u);
            for (Tab t : tabs) {
                if (!t.incognito && !t.address().isEmpty() && Urls.normalize(t.address()).equals(key)) {
                    if (t != cur) switchTo(t);
                    return;
                }
            }
            if (cur != null && cur.isHome()) {
                cur.load(u);
                refreshUi();
            } else openTab(u, null, false, false);
        } else if (cur == null) {
            if (tabs.isEmpty()) openTab(null, null, false, false);
            else switchTo(tabs.get(tabs.size() - 1));
        }
    }

    private static String firstUrlOrText(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(text);
        return m.find() ? m.group() : text;
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.prefs().edit().putBoolean("browser_front", true).apply();
        AdBlocker.reload();
        if (tabsCleared) closeAllTabs();
        for (Tab t : tabs) t.applySettings();
        if (cur != null && cur.web != null) {
            cur.web.onResume();
            cur.web.resumeTimers();
        }
        homeDirty = true;
        starUrl = null;
        if (cur != null) refreshUi();
    }

    @Override
    protected void onPause() {
        if (cur != null && cur.web != null) cur.web.onPause();
        if (customView != null) hideCustomView();
        saveTabs();
        App.prefs().edit().remove("browser_front").apply();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        for (Tab t : tabs) t.destroyWeb();
        if (isFinishing() && App.prefs().getBoolean("clear_on_exit", false)) {
            clearData(this, new boolean[]{true, true, true, true, true});
        }
        super.onDestroy();
    }

    Tab current() {
        return cur;
    }

    // ------------------------------------------------------------------ tabs

    Tab openTab(String url, Tab parent, boolean incognito, boolean background) {
        Tab t = new Tab(this, incognito);
        t.parent = parent;
        t.pendingUrl = url;
        int at = parent == null ? -1 : tabs.indexOf(parent);
        if (at >= 0) tabs.add(at + 1, t);
        else tabs.add(t);
        if (background && cur != null) {
            tabCount.setText(String.valueOf(tabs.size()));
            App.toast(getString(R.string.opened_in_background));
        } else switchTo(t);
        return t;
    }

    private void switchTo(Tab t) {
        if (cur != null && cur != t && cur.web != null) cur.web.onPause();
        closeFind();
        cur = t;
        if (!t.isHome()) t.activate();
        t.lastActive = System.currentTimeMillis();
        if (t.web != null) t.web.onResume();
        // keep memory bounded: the least recently used tabs lose their WebView and reload on return
        while (true) {
            Tab oldest = null;
            int live = 0;
            for (Tab x : tabs) {
                if (x.web == null) continue;
                live++;
                if (x != cur && (oldest == null || x.lastActive < oldest.lastActive)) oldest = x;
            }
            if (live <= MAX_LIVE || oldest == null) break;
            oldest.sleep();
        }
        if (urlBox.hasFocus()) blurUrl();
        starUrl = null;
        refreshUi();
    }

    // ------------------------------------------------------------------ swiping between tabs
    //
    // Along the address bar, as in Chrome for Android: left or right is the neighbouring tab and the page follows
    // the finger; down opens the tab list. On the tab button, as in Alook: the further the finger goes, the more
    // tabs it passes. The tab is only switched to when the finger lifts: a sleeping tab reloads when it is
    // shown, and the tabs passed on the way were not asked for.

    private static final int SWIPE_BAR = 1, SWIPE_BUTTON = 2, SWIPE_EAT = -1;
    private int swipeZone, swipeBy;
    private boolean swiping;
    private float swipeX, swipeY;
    private TextView swipeTip;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        int action = e.getActionMasked();
        float x = e.getRawX(), y = e.getRawY();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            swiping = false;
            swipeBy = 0;
            swipeX = x;
            swipeY = y;
            swipeZone = cur == null || customView != null || urlBox.hasFocus() ? 0
                    : inside(topBar, x, y) ? SWIPE_BAR : inside(findViewById(R.id.btnTabs), x, y) ? SWIPE_BUTTON : 0;
            return super.dispatchTouchEvent(e);
        }
        if (swipeZone == 0) return super.dispatchTouchEvent(e);
        boolean end = action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL;
        if (swipeZone == SWIPE_EAT) {
            if (end) swipeZone = 0;
            return true;
        }
        float dx = x - swipeX, dy = y - swipeY;
        if (!swiping) {
            if (end) swipeZone = 0;
            if (action != android.view.MotionEvent.ACTION_MOVE) return super.dispatchTouchEvent(e);
            int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
            boolean across = Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f;
            boolean down = swipeZone == SWIPE_BAR && dy > slop * 2 && dy > Math.abs(dx) * 1.5f;
            if (!across && !down) return super.dispatchTouchEvent(e);
            // from here the gesture is ours: the button or the field under the finger must not act on it
            android.view.MotionEvent cancel = android.view.MotionEvent.obtain(e);
            cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            if (down) {
                swipeZone = SWIPE_EAT;
                showTabs();
                return true;
            }
            swiping = true;
            swipeX = x;
            return true;
        }
        final View content = (View) webHolder.getParent();
        int idx = tabs.indexOf(cur), n = tabs.size();
        if (!end) {
            if (swipeZone == SWIPE_BAR) {
                // one tab per swipe; a page with no neighbour on that side only gives a little
                boolean has = dx < 0 ? idx + 1 < n : idx > 0;
                swipeBy = !has || Math.abs(dx) < App.dp(48) ? 0 : dx < 0 ? 1 : -1;
                content.setTranslationX(has ? dx : dx * 0.2f);
            } else {
                swipeBy = Math.max(-idx, Math.min(n - 1 - idx, Math.round(-dx / App.dp(28))));
            }
            showSwipeTip(swipeBy == 0 ? null : tabs.get(idx + swipeBy), idx + swipeBy, n);
            return true;
        }
        swipeZone = 0;
        swiping = false;
        showSwipeTip(null, 0, 0);
        if (action == android.view.MotionEvent.ACTION_UP && swipeBy != 0 && idx >= 0) {
            switchTo(tabs.get(idx + swipeBy));
            // the new page comes in from the side the finger pulled it from
            content.setTranslationX((swipeBy > 0 ? 1 : -1) * content.getWidth() * 0.3f);
            content.setAlpha(0.3f);
        }
        content.animate().translationX(0).alpha(1f).setDuration(170).start();
        return true;
    }

    private static boolean inside(View v, float rawX, float rawY) {
        if (v == null || !v.isShown()) return false;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return rawX >= at[0] && rawX < at[0] + v.getWidth() && rawY >= at[1] && rawY < at[1] + v.getHeight();
    }

    /** Where the swipe would land: "3 / 7" and the tab's name, over the page. */
    private void showSwipeTip(Tab t, int idx, int count) {
        if (t == null) {
            if (swipeTip != null) swipeTip.setVisibility(View.GONE);
            return;
        }
        if (swipeTip == null) {
            swipeTip = new TextView(this);
            swipeTip.setTextSize(13);
            swipeTip.setTextColor(Color.WHITE);
            swipeTip.setSingleLine(true);
            swipeTip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            swipeTip.setMaxWidth(App.dp(300));
            swipeTip.setPadding(App.dp(14), App.dp(8), App.dp(14), App.dp(8));
            swipeTip.setBackgroundResource(R.drawable.tip_bg);
            swipeTip.setElevation(App.dp(6));
            // on the window, not on the page: the page moves with the finger
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
            ((FrameLayout) findViewById(android.R.id.content)).addView(swipeTip, lp);
        }
        String text = (idx + 1) + " / " + count + "   " + t.label();
        if (!text.contentEquals(swipeTip.getText())) swipeTip.setText(text);
        swipeTip.setVisibility(View.VISIBLE);
    }

    void closeTab(Tab t) {
        int idx = tabs.indexOf(t);
        if (idx < 0) return;
        if (!t.incognito && !t.address().isEmpty() && !t.address().startsWith("view-source:")) {
            closed.push(t.address());
            if (closed.size() > 20) closed.removeLast();
        }
        t.destroyWeb();
        tabs.remove(idx);
        for (Tab x : tabs) {
            if (x.parent == t) x.parent = null;
        }
        if (t != cur) {
            tabCount.setText(String.valueOf(tabs.size()));
            return;
        }
        cur = null;
        if (tabs.isEmpty()) openTab(null, null, false, false);
        else switchTo(t.parent != null && tabs.contains(t.parent) ? t.parent : tabs.get(Math.min(idx, tabs.size() - 1)));
    }

    void onTabChanged(Tab t) {
        if (t == cur && !isDestroyed()) refreshUi();
        saveTabsSoon();
    }

    private boolean savePending;

    /**
     * A process killed in the foreground never reaches onPause (the system does that to every app using
     * WebView when it updates WebView), so the tab list is also written a moment after it changes.
     */
    private void saveTabsSoon() {
        if (savePending) return;
        savePending = true;
        App.MAIN.postDelayed(() -> {
            savePending = false;
            if (!isDestroyed() && !tabsCleared) saveTabs();
        }, 3000);
    }

    void onTabCrashed(Tab t) {
        if (isDestroyed() || !tabs.contains(t)) return;
        t.sleep();
        if (t == cur) {
            App.toast(getString(R.string.tab_crashed));
            switchTo(t);
        }
    }

    private void saveTabs() {
        try {
            JSONArray arr = new JSONArray();
            int current = -1;
            for (Tab t : tabs) {
                String u = t.address();
                if (t.incognito || u.isEmpty() || !u.startsWith("http")) continue;
                if (t == cur) current = arr.length();
                arr.put(new JSONObject().put("u", u).put("t", t.title));
            }
            App.prefs().edit().putString("tabs_json", arr.toString()).putInt("tabs_current", current).apply();
        } catch (Exception ignored) {
        }
    }

    private void restoreTabs() {
        try {
            JSONArray arr = new JSONArray(App.prefs().getString("tabs_json", "[]"));
            int current = App.prefs().getInt("tabs_current", -1);
            Tab show = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Tab t = new Tab(this, false);
                t.pendingUrl = o.getString("u");
                t.title = o.optString("t");
                tabs.add(t);
                if (i == current) show = t;
            }
            if (show != null) switchTo(show);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ navigation

    /** Turns what the user typed into an address: a URL, a saved page, or a search. */
    private String resolve(String input) {
        input = input.trim();
        if (input.startsWith("view-source:")) input = input.substring(12);
        if (input.isEmpty()) return null;
        if (Tab.isOfflinePage(input)) return input;
        String url = Urls.fromInput(input);
        if (url != null) return url;
        try {
            return searchUrl().replace("%s", URLEncoder.encode(input, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private void go(String input) {
        String url = resolve(input);
        if (url == null) return;
        blurUrl();
        cur.load(url);
        cur.lastActive = System.currentTimeMillis();
        refreshUi();
        cur.web.requestFocus();
    }

    private static String searchUrl() {
        switch (App.prefs().getString("search_engine", "bing")) {
            case "baidu": return "https://www.baidu.com/s?wd=%s";
            case "google": return "https://www.google.com/search?q=%s";
            case "ddg": return "https://duckduckgo.com/?q=%s";
            case "sogou": return "https://www.sogou.com/web?query=%s";
            case "custom":
                String c = App.prefs().getString("search_custom", "").trim();
                if (c.contains("%s") && c.startsWith("http")) return c;
                return "https://www.bing.com/search?q=%s";
            default: return "https://www.bing.com/search?q=%s";
        }
    }

    private boolean goBack(boolean mayFinish) {
        if (cur.web != null && cur.web.canGoBack()) {
            cur.web.goBack();
            return true;
        }
        if (cur.parent != null && tabs.contains(cur.parent)) {
            closeTab(cur); // a tab opened from a link returns to where it came from
            return true;
        }
        if (mayFinish) leave();
        return false;
    }

    /**
     * Back with nowhere to go back to. The browser is not closed: closing it would throw away every tab and
     * its history. It steps behind the reader it was opened from, or behind the shelf.
     */
    private void leave() {
        if (App.prefs().getBoolean("clear_on_exit", false)) {
            finish();
            return;
        }
        if (!ReaderActivity.bringToFront(this)) toShelf();
    }

    private void goHome() {
        closeFind();
        cur.destroyWeb();
        cur.url = "";
        cur.title = "";
        cur.pendingUrl = null;
        cur.pageHost = "";
        cur.blocked = 0;
        homeDirty = true;
        refreshUi();
    }

    private void toShelf() {
        // moved to the front, not "clear top": the shelf is usually underneath, and clearing would destroy this browser
        startActivity(new Intent(this, ShelfActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    /** Carries out one of the {@link BarActions}. @return false for "none", so the press ends as a tap */
    private boolean runAction(String id) {
        if (cur == null || BarActions.NONE.equals(id)) return false;
        if (urlBox.hasFocus()) blurUrl();
        final boolean page = cur.url.startsWith("http");
        final android.content.SharedPreferences p = App.prefs();
        switch (id) {
            case "close_tab":
                closeTab(cur);
                break;
            case "new_tab":
                openTab(null, null, false, false);
                break;
            case "incognito_tab":
                openTab(null, null, true, false);
                break;
            case "reopen_closed":
                if (!closed.isEmpty()) openTab(closed.pop(), null, false, false);
                else App.toast(getString(R.string.none));
                break;
            case "tab_list":
                showTabs();
                break;
            case "back":
                goBack(true);
                break;
            case "forward":
                if (cur.web != null && cur.web.canGoForward()) cur.web.goForward();
                break;
            case "back_list":
                showBackForwardList();
                break;
            case "home":
                goHome();
                break;
            case "reload":
                if (cur.web != null) cur.reload();
                break;
            case "page_top":
                if (cur.web != null) cur.web.pageUp(true);
                break;
            case "page_bottom":
                if (cur.web != null) cur.web.pageDown(true);
                break;
            case "history":
                LibraryActivity.open(this, 1);
                break;
            case "bookmarks":
                LibraryActivity.open(this, 0);
                break;
            case "downloads":
                LibraryActivity.open(this, 2);
                break;
            case "offline_pages":
                LibraryActivity.open(this, 3);
                break;
            case "bookmark":
                toggleBookmark();
                break;
            case "find":
                if (!cur.isHome() && cur.web != null) openFind();
                break;
            case "share":
                if (page) share(cur.url, cur.title);
                break;
            case "copy_link":
                if (page) copy(cur.url);
                break;
            case "fullscreen":
                setFullscreen(!fullscreen);
                break;
            case "desktop":
                cur.desktop = !cur.desktop;
                cur.applySettings();
                if (cur.web != null) cur.reload();
                break;
            case "night":
                p.edit().putBoolean("web_night", !p.getBoolean("web_night", false)).apply();
                for (Tab t : tabs) t.applyNight();
                break;
            case "no_images":
                p.edit().putBoolean("block_images", !p.getBoolean("block_images", false)).apply();
                for (Tab t : tabs) t.applySettings();
                if (cur.web != null) cur.reload();
                break;
            case "settings":
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case "clear_data":
                clearDataDialog(this, this::closeAllTabs);
                break;
            case "exit":
                finish();
                break;
            case "shelf":
                toShelf();
                break;
            case "reading_mode":
                if (cur.sniffed != null) enterReadingMode();
                else App.toast(getString(R.string.act_no_novel));
                break;
            case "detect_novel":
                if (page) {
                    App.toast(getString(R.string.sniffing));
                    cur.sniffNow();
                }
                break;
            case "continue_reading":
                if (!ReaderActivity.continueLast(this)) App.toast(getString(R.string.act_nothing_to_read));
                break;
            default:
                return false;
        }
        if (cur != null) refreshUi();
        return true;
    }

    private boolean showBackForwardList() {
        if (cur.web == null) return true;
        final WebBackForwardList list = cur.web.copyBackForwardList();
        if (list.getSize() < 2) return true;
        final int now = list.getCurrentIndex();
        CharSequence[] items = new CharSequence[list.getSize()];
        for (int i = 0; i < items.length; i++) {
            android.webkit.WebHistoryItem it = list.getItemAtIndex(items.length - 1 - i);
            String t = it.getTitle();
            items[i] = (items.length - 1 - i == now ? "● " : "    ") + (t == null || t.isEmpty() ? it.getUrl() : t);
        }
        new AlertDialog.Builder(this).setItems(items, (d, w) -> {
            int steps = (items.length - 1 - w) - now;
            if (steps != 0 && cur.web != null) cur.web.goBackOrForward(steps);
        }).show();
        return true;
    }

    // ------------------------------------------------------------------ chrome state

    private void attach() {
        if (cur.web == null) {
            if (webHolder.getChildCount() > 0) webHolder.removeAllViews();
        } else if (cur.web.getParent() != webHolder) {
            webHolder.removeAllViews();
            if (cur.web.getParent() != null) ((ViewGroup) cur.web.getParent()).removeView(cur.web);
            webHolder.addView(cur.web);
        }
    }

    private void refreshUi() {
        if (cur == null) return;
        attach();
        boolean isHome = cur.isHome();
        home.setVisibility(isHome ? View.VISIBLE : View.GONE);
        webHolder.setVisibility(isHome ? View.INVISIBLE : View.VISIBLE);
        if (isHome && homeDirty) buildHome();
        if (BuildConfig.DEBUG) refreshTestBadge();

        boolean editing = urlBox.hasFocus();
        if (!editing) {
            String shown = displayUrl();
            if (!shown.contentEquals(urlBox.getText())) urlBox.setText(shown);
        }
        progress.setVisibility(cur.busy() && !fullscreen ? View.VISIBLE : View.INVISIBLE);
        progress.setProgress(cur.progress);
        btnReload.setImageResource(cur.busy() ? R.drawable.ic_close : R.drawable.ic_refresh);
        btnReload.setAlpha(isHome ? 0.3f : 1f);
        btnForward.setAlpha(cur.web != null && cur.web.canGoForward() ? 1f : 0.3f);
        tabCount.setText(String.valueOf(tabs.size()));

        int n = cur.blocked;
        blockedBadge.setVisibility(n > 0 && !editing ? View.VISIBLE : View.GONE);
        blockedBadge.setText(String.valueOf(Math.min(n, 999)));

        int icon, tint = getColor(R.color.text_secondary);
        if (cur.incognito) icon = R.drawable.ic_incognito;
        else if (isHome || editing) icon = R.drawable.ic_search;
        else if (cur.secure()) {
            icon = R.drawable.ic_lock;
            tint = getColor(R.color.brand);
        } else icon = R.drawable.ic_info;
        siteIcon.setImageResource(icon);
        siteIcon.setImageTintList(ColorStateList.valueOf(tint));

        String u = cur.url;
        btnStar.setVisibility(!editing && u.startsWith("http") ? View.VISIBLE : View.GONE);
        if (!u.equals(starUrl)) {
            starUrl = u;
            starOn = u.startsWith("http") && Db.isBookmarked(u);
        }
        btnStar.setImageResource(starOn ? R.drawable.ic_star : R.drawable.ic_star_border);
        btnStar.setImageTintList(ColorStateList.valueOf(getColor(starOn ? R.color.brand : R.color.text_secondary)));

        PageResult r = cur.sniffed;
        if (r == null) fab.setVisibility(View.GONE);
        else {
            fab.setText(r.type == PageResult.Type.CHAPTER ? R.string.reading_mode : R.string.book_detected);
            if (fab.getVisibility() != View.VISIBLE) {
                fab.setVisibility(View.VISIBLE);
                fab.setScaleX(0.6f);
                fab.setScaleY(0.6f);
                fab.setAlpha(0f);
                fab.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start();
            }
        }
    }

    private String displayUrl() {
        String u = cur.address();
        if (u.isEmpty()) return "";
        if (u.startsWith("file:")) return cur.title.isEmpty() ? getString(R.string.offline_pages) : cur.title;
        String s = u.replaceFirst("^https?://", "");
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private void focusUrl() {
        urlBox.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(urlBox, InputMethodManager.SHOW_IMPLICIT);
    }

    private void blurUrl() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
        urlBox.clearFocus();
    }

    private void toggleBookmark() {
        String u = cur.url;
        if (!u.startsWith("http")) return;
        if (Db.isBookmarked(u)) {
            Db.removeBookmark(u);
            App.toast(getString(R.string.bookmark_removed));
        } else {
            Db.addBookmark(u, cur.title.isEmpty() ? Urls.host(u) : cur.title);
            App.toast(getString(R.string.bookmark_added));
        }
        starUrl = null;
        homeDirty = true;
        refreshUi();
    }

    // ------------------------------------------------------------------ start page

    private static final int[] TILE_COLORS = {0xFF2F6F62, 0xFF5B6ABF, 0xFFB5651D, 0xFF8E4585, 0xFF2E7DA6, 0xFFB23A48, 0xFF5E7D3A, 0xFF6B5B95};

    private void buildHome() {
        homeDirty = false;
        App.IO.execute(() -> {
            final List<String[]> marks = Db.bookmarks();
            final List<String[]> top = Db.topSites(8);
            App.MAIN.post(() -> {
                if (!isDestroyed()) fillHome(marks, top);
            });
        });
    }

    private void fillHome(List<String[]> marks, List<String[]> top) {
        homeBox.removeAllViews();
        // keep the column readable on tablets and in landscape
        int side = Math.max(App.dp(16), (getResources().getDisplayMetrics().widthPixels - App.dp(560)) / 2);
        homeBox.setPadding(side, homeBox.getPaddingTop(), side, homeBox.getPaddingBottom());
        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextSize(26);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getColor(R.color.brand));
        name.setGravity(Gravity.CENTER);
        homeBox.addView(name, new LinearLayout.LayoutParams(-1, -2));

        TextView search = new TextView(this);
        search.setText(R.string.url_hint);
        search.setTextSize(15);
        search.setTextColor(getColor(R.color.text_secondary));
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackgroundResource(R.drawable.field_bg);
        search.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface)));
        search.setPadding(App.dp(20), 0, App.dp(20), 0);
        search.setElevation(App.dp(1));
        search.setOnClickListener(v -> focusUrl());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, App.dp(46));
        lp.topMargin = App.dp(22);
        lp.bottomMargin = App.dp(10);
        lp.leftMargin = lp.rightMargin = App.dp(4);
        homeBox.addView(search, lp);

        LinearLayout quick = new LinearLayout(this);
        quick.addView(quickLink(R.drawable.ic_book, R.string.bookshelf, this::toShelf));
        quick.addView(quickLink(R.drawable.ic_star, R.string.bookmarks, () -> LibraryActivity.open(this, 0)));
        quick.addView(quickLink(R.drawable.ic_history, R.string.history, () -> LibraryActivity.open(this, 1)));
        quick.addView(quickLink(R.drawable.ic_download, R.string.downloads, () -> LibraryActivity.open(this, 2)));
        homeBox.addView(quick, new LinearLayout.LayoutParams(-1, -2));

        Set<String> seen = new HashSet<>();
        List<String[]> shown = new ArrayList<>();
        for (String[] r : marks) {
            if (shown.size() < 12 && seen.add(r[0])) shown.add(r);
        }
        addTiles(R.string.bookmarks, shown, true);
        shown = new ArrayList<>();
        for (String[] r : top) {
            if (seen.add(r[0])) shown.add(r);
        }
        addTiles(R.string.most_visited, shown, false);
        if (marks.isEmpty() && top.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText(R.string.home_empty);
            hint.setTextSize(13);
            hint.setTextColor(getColor(R.color.text_secondary));
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(App.dp(24), App.dp(40), App.dp(24), 0);
            homeBox.addView(hint);
        }
    }

    private View quickLink(int icon, int label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(getColor(R.color.text_primary));
        t.setGravity(Gravity.CENTER);
        t.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        t.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.brand)));
        t.setCompoundDrawablePadding(App.dp(5));
        t.setPadding(0, App.dp(12), 0, App.dp(10));
        t.setBackgroundResource(selectable());
        t.setOnClickListener(v -> action.run());
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return t;
    }

    private int selectable() {
        android.util.TypedValue v = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, v, true);
        return v.resourceId;
    }

    private void addTiles(int heading, List<String[]> rows, final boolean bookmark) {
        if (rows.isEmpty()) return;
        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextSize(12.5f);
        h.setTextColor(getColor(R.color.text_secondary));
        h.setPadding(App.dp(6), App.dp(16), 0, App.dp(4));
        homeBox.addView(h);
        LinearLayout row = null;
        for (int i = 0; i < rows.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                homeBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            row.addView(tile(rows.get(i), bookmark));
        }
        for (int i = rows.size(); i % 4 != 0; i++) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        }
    }

    private View tile(final String[] r, final boolean bookmark) {
        final String url = r[0], host = Urls.host(url);
        String title = r[1] == null || r[1].isEmpty() ? host : r[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(App.dp(4), App.dp(10), App.dp(4), App.dp(8));
        box.setBackgroundResource(selectable());

        TextView letter = new TextView(this);
        String bare = host.startsWith("www.") ? host.substring(4) : host;
        String first = bookmark && !title.equals(host) ? title : bare;
        letter.setText(first.isEmpty() ? "?" : first.substring(0, first.offsetByCodePoints(0, 1)).toUpperCase());
        letter.setTextSize(19);
        letter.setTextColor(Color.WHITE);
        letter.setTypeface(Typeface.DEFAULT_BOLD);
        letter.setGravity(Gravity.CENTER);
        letter.setBackgroundResource(R.drawable.tile_bg);
        letter.setBackgroundTintList(ColorStateList.valueOf(TILE_COLORS[(host.hashCode() & 0x7fffffff) % TILE_COLORS.length]));
        android.graphics.Bitmap fav = Favicons.get(url);
        if (fav == null) box.addView(letter, new LinearLayout.LayoutParams(App.dp(46), App.dp(46)));
        else {
            ImageView pic = new ImageView(this);
            pic.setImageBitmap(fav);
            pic.setBackgroundResource(R.drawable.tile_bg);
            pic.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.field_bg)));
            pic.setPadding(App.dp(10), App.dp(10), App.dp(10), App.dp(10));
            box.addView(pic, new LinearLayout.LayoutParams(App.dp(46), App.dp(46)));
        }

        TextView label = new TextView(this);
        label.setText(bookmark ? title : bare);
        label.setTextSize(11.5f);
        label.setTextColor(getColor(R.color.text_primary));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, App.dp(6), 0, 0);
        box.addView(label, new LinearLayout.LayoutParams(-1, -2));

        box.setOnClickListener(v -> go(url));
        box.setOnLongClickListener(v -> {
            PopupMenu m = new PopupMenu(this, v);
            m.getMenu().add(0, 1, 0, R.string.open_new_tab);
            m.getMenu().add(0, 2, 0, R.string.open_incognito);
            m.getMenu().add(0, 3, 0, R.string.remove);
            m.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) openTab(url, null, false, false);
                else if (item.getItemId() == 2) openTab(url, null, true, false);
                else {
                    if (bookmark) Db.removeBookmark(url);
                    else Db.deleteHistory(url);
                    buildHome();
                }
                return true;
            });
            m.show();
            return true;
        });
        box.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return box;
    }

    // ------------------------------------------------------------------ address bar suggestions

    /** Rows of {kind, title, value}: c = clipboard, q = search for the text, s = search suggestion, b / h = page. */
    private final List<String[]> suggestions = new ArrayList<>();
    private int suggestSeq;

    private final BaseAdapter suggestAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return suggestions.size();
        }

        @Override
        public Object getItem(int position) {
            return suggestions.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) {
                LinearLayout row = new LinearLayout(BrowserActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(App.dp(52));
                row.setPadding(App.dp(16), 0, App.dp(4), 0);
                ImageView icon = new ImageView(BrowserActivity.this);
                icon.setId(android.R.id.icon);
                icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
                row.addView(icon, new LinearLayout.LayoutParams(App.dp(20), App.dp(20)));
                LinearLayout texts = new LinearLayout(BrowserActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(App.dp(16), App.dp(6), App.dp(8), App.dp(6));
                TextView t1 = new TextView(BrowserActivity.this), t2 = new TextView(BrowserActivity.this);
                t1.setId(android.R.id.text1);
                t2.setId(android.R.id.text2);
                t1.setTextSize(14.5f);
                t1.setTextColor(getColor(R.color.text_primary));
                t1.setSingleLine(true);
                t1.setEllipsize(TextUtils.TruncateAt.END);
                t2.setTextSize(12);
                t2.setTextColor(getColor(R.color.text_secondary));
                t2.setSingleLine(true);
                t2.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                texts.addView(t1);
                texts.addView(t2);
                row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
                ImageButton fill = new ImageButton(BrowserActivity.this);
                fill.setId(android.R.id.button1);
                fill.setImageResource(R.drawable.ic_back);
                fill.setRotation(45);
                fill.setFocusable(false);
                fill.setBackgroundResource(selectable());
                fill.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
                row.addView(fill, new LinearLayout.LayoutParams(App.dp(44), App.dp(44)));
                v = row;
            }
            final String[] s = suggestions.get(position);
            ImageView icon = v.findViewById(android.R.id.icon);
            TextView t1 = v.findViewById(android.R.id.text1), t2 = v.findViewById(android.R.id.text2);
            View fill = v.findViewById(android.R.id.button1);
            boolean page = s[0].equals("b") || s[0].equals("h");
            Favicons.show(icon, page ? s[2] : null, s[0].equals("b") ? R.drawable.ic_star : s[0].equals("h") ? R.drawable.ic_history
                    : s[0].equals("c") ? R.drawable.ic_copy : R.drawable.ic_search, getColor(R.color.text_secondary));
            t1.setText(s[0].equals("c") ? getString(R.string.paste_and_go) : s[1].isEmpty() ? s[2] : s[1]);
            t2.setVisibility(page ? View.VISIBLE : View.GONE);
            t2.setText(s[2]);
            fill.setVisibility(s[0].equals("c") ? View.INVISIBLE : View.VISIBLE);
            fill.setOnClickListener(x -> {
                urlBox.setText(s[2]);
                urlBox.setSelection(urlBox.length());
            });
            return v;
        }
    };

    private void pickSuggestion(String[] s) {
        if (s[0].equals("c")) {
            ClipData clip = ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip();
            CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
            if (text != null && text.length() > 0) go(text.toString());
        } else go(s[2]);
    }

    private void updateSuggestions() {
        final String text = urlBox.getText().toString().trim();
        final int seq = ++suggestSeq;
        final boolean blank = text.isEmpty() || text.equals(cur.address());
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        final boolean hasClip = blank && cm.hasPrimaryClip() && cm.getPrimaryClipDescription() != null
                && (cm.getPrimaryClipDescription().hasMimeType("text/plain") || cm.getPrimaryClipDescription().hasMimeType("text/html"));
        final boolean online = !blank && !cur.incognito && Urls.fromInput(text) == null
                && App.prefs().getBoolean("search_suggest", false);
        App.IO.execute(() -> {
            final List<String[]> out = new ArrayList<>();
            if (blank) {
                if (hasClip) out.add(new String[]{"c", "", ""});
                List<String[]> recent = Db.history();
                for (int i = 0; i < recent.size() && i < 8; i++) out.add(new String[]{"h", recent.get(i)[1], recent.get(i)[0]});
            } else {
                if (Urls.fromInput(text) == null) out.add(new String[]{"q", getString(R.string.search_for, text), text});
                for (String[] r : Db.suggest(text, 8)) out.add(new String[]{r[2], r[1] == null ? "" : r[1], r[0]});
            }
            postSuggestions(seq, out);
            if (!online) return;
            List<String> words = searchSuggestions(text);
            if (words.isEmpty()) return;
            final List<String[]> merged = new ArrayList<>(out);
            int at = Math.min(1, merged.size()), n = 0;
            for (String w : words) {
                if (n >= 5 || w.equalsIgnoreCase(text)) continue;
                merged.add(at + n++, new String[]{"s", w, w});
            }
            postSuggestions(seq, merged);
        });
    }

    private void postSuggestions(final int seq, final List<String[]> rows) {
        App.MAIN.post(() -> {
            if (seq != suggestSeq || isDestroyed()) return;
            suggestions.clear();
            suggestions.addAll(rows);
            suggestAdapter.notifyDataSetChanged();
        });
    }

    /** Completions from the selected search engine (OpenSearch suggestion format). */
    private static List<String> searchSuggestions(String text) {
        List<String> out = new ArrayList<>();
        String api;
        switch (App.prefs().getString("search_engine", "bing")) {
            case "baidu": api = "https://suggestion.baidu.com/su?action=opensearch&ie=utf-8&wd=%s"; break;
            case "google": api = "https://suggestqueries.google.com/complete/search?client=firefox&q=%s"; break;
            case "ddg": api = "https://duckduckgo.com/ac/?type=list&q=%s"; break;
            case "bing": api = "https://api.bing.com/osjson.aspx?query=%s"; break;
            default: return out;
        }
        try {
            String body = Http.get(api.replace("%s", URLEncoder.encode(text, "UTF-8")), null).html;
            JSONArray arr = new JSONArray(body.substring(body.indexOf('['))).getJSONArray(1);
            for (int i = 0; i < arr.length() && i < 8; i++) out.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ find in page

    private void setupFind() {
        findText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (cur.web == null || findBar.getVisibility() != View.VISIBLE) return;
                if (s.length() == 0) {
                    cur.web.clearMatches();
                    findCount.setText("");
                } else cur.web.findAllAsync(s.toString());
            }
        });
        findText.setOnEditorActionListener((v, id, e) -> {
            if (cur.web != null) cur.web.findNext(true);
            return true;
        });
        findViewById(R.id.findNext).setOnClickListener(v -> {
            if (cur.web != null) cur.web.findNext(true);
        });
        findViewById(R.id.findPrev).setOnClickListener(v -> {
            if (cur.web != null) cur.web.findNext(false);
        });
        findViewById(R.id.findClose).setOnClickListener(v -> closeFind());
    }

    private void openFind() {
        if (cur.web == null) return;
        if (fullscreen) setFullscreen(false);
        topBar.setVisibility(View.GONE);
        findBar.setVisibility(View.VISIBLE);
        cur.web.setFindListener((active, count, done) -> {
            if (done) findCount.setText(count == 0 ? "0/0" : (active + 1) + "/" + count);
        });
        findText.requestFocus();
        findText.selectAll();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(findText, InputMethodManager.SHOW_IMPLICIT);
        if (findText.length() > 0) cur.web.findAllAsync(findText.getText().toString());
    }

    private void closeFind() {
        if (findBar.getVisibility() != View.VISIBLE) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(findText.getWindowToken(), 0);
        findBar.setVisibility(View.GONE);
        topBar.setVisibility(View.VISIBLE);
        findCount.setText("");
        if (cur != null && cur.web != null) {
            cur.web.clearMatches();
            cur.web.setFindListener(null);
        }
    }

    // ------------------------------------------------------------------ sheets

    private Dialog sheet(View content) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        content.setBackgroundResource(R.drawable.sheet_bg);
        d.setContentView(content);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // full width on phones, a centred panel on wide screens
            int screen = getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(screen, App.dp(520)), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            w.getAttributes().windowAnimations = android.R.style.Animation_InputMethod;
        }
        return d;
    }

    private static final class Item {
        final int icon, label;
        final boolean on, enabled;
        final Runnable action;

        Item(int icon, int label, boolean on, boolean enabled, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.on = on;
            this.enabled = enabled;
            this.action = action;
        }
    }

    // ---- site test record (debug builds only)

    private TextView testBadge;
    private String testBadgeHost;

    private void markSite(int state) {
        SiteTests.mark(cur.url, state);
        testBadgeHost = null;
        App.toast(getString(state == SiteTests.PASS ? R.string.site_test_marked_pass
                : state == SiteTests.FAIL ? R.string.site_test_marked_fail : R.string.site_test_unmarked));
    }

    /** The floating mark in the top right corner of a page whose site has a verdict. Called from refreshUi. */
    private void refreshTestBadge() {
        String host = cur.isHome() || !cur.url.startsWith("http") ? "" : cur.pageHost;
        if (testBadge != null && host.equals(testBadgeHost)) return;
        testBadgeHost = host;
        int st = host.isEmpty() ? SiteTests.NONE : SiteTests.state(host);
        if (st == SiteTests.NONE) {
            if (testBadge != null) testBadge.setVisibility(View.GONE);
            return;
        }
        if (testBadge == null) {
            testBadge = new TextView(this);
            testBadge.setTextSize(12);
            testBadge.setTextColor(Color.WHITE);
            testBadge.setTypeface(Typeface.DEFAULT_BOLD);
            testBadge.setPadding(App.dp(10), App.dp(5), App.dp(10), App.dp(5));
            testBadge.setBackgroundResource(R.drawable.tip_bg);
            testBadge.setElevation(App.dp(4));
            testBadge.setOnClickListener(v -> showSiteTests());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.setMargins(0, App.dp(8), App.dp(8), 0);
            ((FrameLayout) webHolder.getParent()).addView(testBadge, lp);
        }
        testBadge.setText(st == SiteTests.PASS ? R.string.site_test_badge_pass : R.string.site_test_badge_fail);
        testBadge.setBackgroundTintList(ColorStateList.valueOf(st == SiteTests.PASS ? 0xE62E7D5B : 0xE6C0392B));
        testBadge.setVisibility(View.VISIBLE);
    }

    private void showSiteTests() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(App.dp(8), App.dp(8), App.dp(8), App.dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        final Dialog d = new android.app.AlertDialog.Builder(this).setTitle(R.string.site_test_lists).setView(scroll)
                .setPositiveButton(android.R.string.ok, null).create();
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            box.removeAllViews();
            int[] states = {SiteTests.PASS, SiteTests.FAIL};
            int[] names = {R.string.site_test_badge_pass, R.string.site_test_badge_fail};
            for (int i = 0; i < 2; i++) {
                List<SiteTests.Entry> list = SiteTests.list(states[i]);
                TextView head = new TextView(this);
                head.setText(getString(names[i]) + " (" + list.size() + ")");
                head.setTextSize(13);
                head.setTypeface(Typeface.DEFAULT_BOLD);
                head.setTextColor(states[i] == SiteTests.PASS ? 0xFF2E7D5B : 0xFFC0392B);
                head.setPadding(App.dp(12), App.dp(12), App.dp(12), App.dp(4));
                box.addView(head);
                for (final SiteTests.Entry e : list) {
                    LinearLayout row = new LinearLayout(this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(App.dp(12), 0, 0, 0);
                    row.setMinimumHeight(App.dp(52));
                    row.setBackgroundResource(selectable());
                    ImageView icon = new ImageView(this);
                    Favicons.show(icon, e.url, R.drawable.ic_globe, getColor(R.color.text_secondary));
                    row.addView(icon, new LinearLayout.LayoutParams(App.dp(22), App.dp(22)));
                    LinearLayout texts = new LinearLayout(this);
                    texts.setOrientation(LinearLayout.VERTICAL);
                    texts.setPadding(App.dp(14), App.dp(6), App.dp(4), App.dp(6));
                    TextView t1 = new TextView(this), t2 = new TextView(this);
                    t1.setText(e.host);
                    t1.setTextSize(15);
                    t1.setTextColor(getColor(R.color.text_primary));
                    t1.setSingleLine(true);
                    t1.setEllipsize(TextUtils.TruncateAt.END);
                    t2.setText(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", e.time) + "  " + e.url);
                    t2.setTextSize(11.5f);
                    t2.setTextColor(getColor(R.color.text_secondary));
                    t2.setSingleLine(true);
                    t2.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    texts.addView(t1);
                    texts.addView(t2);
                    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
                    ImageButton del = new ImageButton(this);
                    del.setImageResource(R.drawable.ic_close);
                    del.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
                    del.setBackgroundResource(selectable());
                    del.setOnClickListener(v -> {
                        SiteTests.mark(e.url, SiteTests.NONE);
                        testBadgeHost = null;
                        fill[0].run();
                        refreshUi();
                    });
                    row.addView(del, new LinearLayout.LayoutParams(App.dp(44), App.dp(44)));
                    row.setOnClickListener(v -> {
                        d.dismiss();
                        go(e.url);
                    });
                    box.addView(row);
                }
                if (list.isEmpty()) {
                    TextView none = new TextView(this);
                    none.setText(R.string.site_test_empty);
                    none.setTextColor(getColor(R.color.text_secondary));
                    none.setPadding(App.dp(12), App.dp(8), App.dp(12), App.dp(8));
                    box.addView(none);
                }
            }
        };
        fill[0].run();
        d.show();
    }

    private void showMenu() {
        closeFind();
        if (urlBox.hasFocus()) blurUrl();
        final boolean page = cur.url.startsWith("http"), any = !cur.isHome() && cur.web != null;
        final String host = cur.pageHost;
        final android.content.SharedPreferences p = App.prefs();
        List<Item> items = new ArrayList<>();
        items.add(new Item(R.drawable.ic_star, R.string.bookmarks, false, true, () -> LibraryActivity.open(this, 0)));
        items.add(new Item(R.drawable.ic_history, R.string.history, false, true, () -> LibraryActivity.open(this, 1)));
        items.add(new Item(R.drawable.ic_download, R.string.downloads, false, true, () -> LibraryActivity.open(this, 2)));
        items.add(new Item(R.drawable.ic_save, R.string.offline_pages, false, true, () -> LibraryActivity.open(this, 3)));

        items.add(new Item(starOn ? R.drawable.ic_star : R.drawable.ic_star_border, starOn ? R.string.remove_bookmark : R.string.add_bookmark,
                starOn, page, this::toggleBookmark));
        items.add(new Item(R.drawable.ic_share, R.string.share, false, page, () -> share(cur.url, cur.title)));
        items.add(new Item(R.drawable.ic_search, R.string.find_in_page, false, any, this::openFind));
        items.add(new Item(R.drawable.ic_book, R.string.detect_novel, false, page, () -> {
            App.toast(getString(R.string.sniffing));
            cur.sniffNow();
        }));

        items.add(new Item(R.drawable.ic_add, R.string.new_tab, false, true, () -> openTab(null, null, false, false)));
        items.add(new Item(R.drawable.ic_incognito, R.string.incognito_tab, false, true, () -> openTab(null, null, true, false)));
        items.add(new Item(R.drawable.ic_undo, R.string.reopen_closed, false, !closed.isEmpty(), () -> {
            if (!closed.isEmpty()) openTab(closed.pop(), null, false, false);
        }));
        items.add(new Item(R.drawable.ic_fullscreen, R.string.fullscreen, fullscreen, true, () -> setFullscreen(!fullscreen)));

        items.add(new Item(R.drawable.ic_desktop, R.string.desktop_mode, cur.desktop, true, () -> {
            cur.desktop = !cur.desktop;
            cur.applySettings();
            if (cur.web != null) cur.reload();
        }));
        items.add(new Item(R.drawable.ic_night, R.string.night_mode, p.getBoolean("web_night", false), true, () -> {
            p.edit().putBoolean("web_night", !p.getBoolean("web_night", false)).apply();
            for (Tab t : tabs) t.applyNight();
        }));
        items.add(new Item(R.drawable.ic_image, R.string.no_images, p.getBoolean("block_images", false), true, () -> {
            p.edit().putBoolean("block_images", !p.getBoolean("block_images", false)).apply();
            for (Tab t : tabs) t.applySettings();
            if (cur.web != null) cur.reload();
        }));
        items.add(new Item(R.drawable.ic_text_size, R.string.text_size, p.getInt("text_zoom", 100) != 100, true, this::showTextSize));

        items.add(new Item(R.drawable.ic_shield, R.string.adblock_site, page && AdBlocker.isOn(host), page, () -> {
            AdBlocker.setSiteOff(host, !AdBlocker.isSiteOff(host));
            App.toast(getString(AdBlocker.isSiteOff(host) ? R.string.adblock_site_off : R.string.adblock_site_on));
            cur.reload();
        }));
        items.add(new Item(R.drawable.ic_code, R.string.javascript, page && p.getBoolean("javascript", true) && !jsOff(host), page, () -> {
            setJsOff(host, !jsOff(host));
            App.toast(getString(jsOff(host) ? R.string.js_site_off : R.string.js_site_on));
            cur.applySettings();
            cur.reload();
        }));
        items.add(new Item(R.drawable.ic_info, R.string.page_info, false, any, this::showPageInfo));
        items.add(new Item(R.drawable.ic_code, R.string.view_source, false, page, this::viewSource));

        items.add(new Item(R.drawable.ic_print, R.string.save_pdf, false, any, this::printPage));
        items.add(new Item(R.drawable.ic_save, R.string.save_offline, false, page, this::saveOffline));
        items.add(new Item(R.drawable.ic_home, R.string.add_to_home, false, page, this::addToHomeScreen));
        items.add(new Item(R.drawable.ic_copy, R.string.copy_link, false, page, () -> copy(cur.url)));

        items.add(new Item(R.drawable.ic_globe, R.string.open_external, false, page, () -> openExternally(cur.url)));
        items.add(new Item(R.drawable.ic_delete, R.string.clear_data, false, true, () -> clearDataDialog(this, this::closeAllTabs)));
        items.add(new Item(R.drawable.ic_settings, R.string.settings, false, true,
                () -> startActivity(new Intent(this, SettingsActivity.class))));
        items.add(new Item(R.drawable.ic_power, R.string.exit, false, true, this::finish));
        if (BuildConfig.DEBUG) {
            final int st = page ? SiteTests.state(cur.url) : SiteTests.NONE;
            items.add(new Item(R.drawable.ic_shield, R.string.site_test_pass, st == SiteTests.PASS, page,
                    () -> markSite(st == SiteTests.PASS ? SiteTests.NONE : SiteTests.PASS)));
            items.add(new Item(R.drawable.ic_close, R.string.site_test_fail, st == SiteTests.FAIL, page,
                    () -> markSite(st == SiteTests.FAIL ? SiteTests.NONE : SiteTests.FAIL)));
            items.add(new Item(R.drawable.ic_copy, R.string.site_test_lists, false, true, this::showSiteTests));
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(App.dp(8), App.dp(14), App.dp(8), App.dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        final Dialog d = sheet(scroll);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                box.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            final Item it = items.get(i);
            TextView t = new TextView(this);
            t.setText(it.label);
            t.setTextSize(11.5f);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setMaxLines(2);
            int color = getColor(it.on ? R.color.brand : R.color.text_primary);
            t.setTextColor(color);
            t.setCompoundDrawablesWithIntrinsicBounds(0, it.icon, 0, 0);
            t.setCompoundDrawableTintList(ColorStateList.valueOf(color));
            t.setCompoundDrawablePadding(App.dp(6));
            t.setPadding(App.dp(2), App.dp(12), App.dp(2), App.dp(8));
            t.setMinHeight(App.dp(76));
            t.setBackgroundResource(selectable());
            t.setAlpha(it.enabled ? 1f : 0.3f);
            t.setEnabled(it.enabled);
            t.setOnClickListener(v -> {
                d.dismiss();
                it.action.run();
                refreshUi();
            });
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        d.show();
    }

    private void showTabs() {
        closeFind();
        if (urlBox.hasFocus()) blurUrl();
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog d = sheet(box);
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            box.removeAllViews();
            TextView head = new TextView(this);
            head.setText(getString(R.string.tabs_title, tabs.size()));
            head.setTextSize(15);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextColor(getColor(R.color.text_primary));
            head.setPadding(App.dp(20), App.dp(16), App.dp(20), App.dp(8));
            box.addView(head);

            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (final Tab t : tabs) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(App.dp(20), 0, App.dp(6), 0);
                row.setMinimumHeight(App.dp(56));
                row.setBackgroundResource(selectable());
                if (t == cur) row.setBackgroundColor(getColor(R.color.field_bg));
                ImageView icon = new ImageView(this);
                if (t.icon != null && !t.incognito) icon.setImageBitmap(t.icon);
                else Favicons.show(icon, t.incognito ? null : t.url.isEmpty() ? t.pendingUrl : t.url,
                        t.incognito ? R.drawable.ic_incognito : R.drawable.ic_globe, getColor(R.color.text_secondary));
                row.addView(icon, new LinearLayout.LayoutParams(App.dp(22), App.dp(22)));
                LinearLayout texts = new LinearLayout(this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(App.dp(16), App.dp(8), App.dp(8), App.dp(8));
                TextView t1 = new TextView(this), t2 = new TextView(this);
                t1.setText(t.label());
                t1.setTextSize(14.5f);
                t1.setSingleLine(true);
                t1.setEllipsize(TextUtils.TruncateAt.END);
                t1.setTextColor(getColor(t == cur ? R.color.brand : R.color.text_primary));
                String host = Urls.host(t.address());
                t2.setText(t.incognito ? getString(R.string.incognito) + (host.isEmpty() ? "" : " · " + host) : host);
                t2.setTextSize(12);
                t2.setSingleLine(true);
                t2.setTextColor(getColor(R.color.text_secondary));
                t2.setVisibility(t2.length() == 0 ? View.GONE : View.VISIBLE);
                texts.addView(t1);
                texts.addView(t2);
                row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
                ImageButton close = new ImageButton(this);
                close.setImageResource(R.drawable.ic_close);
                close.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
                close.setBackgroundResource(selectable());
                close.setOnClickListener(v -> {
                    closeTab(t);
                    fill[0].run();
                });
                row.addView(close, new LinearLayout.LayoutParams(App.dp(48), App.dp(48)));
                row.setOnClickListener(v -> {
                    d.dismiss();
                    if (t != cur) switchTo(t);
                });
                list.addView(row);
            }
            final ScrollView scroll = new ScrollView(this);
            scroll.addView(list);
            final int at = tabs.indexOf(cur);
            scroll.post(() -> scroll.scrollTo(0, Math.max(0, at - 2) * App.dp(56)));
            int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
            box.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(max, tabs.size() * App.dp(56))));

            LinearLayout actions = new LinearLayout(this);
            actions.setPadding(App.dp(8), App.dp(4), App.dp(8), App.dp(8));
            actions.addView(sheetButton(R.drawable.ic_add, R.string.new_tab, () -> {
                d.dismiss();
                openTab(null, null, false, false);
            }));
            actions.addView(sheetButton(R.drawable.ic_incognito, R.string.incognito_tab, () -> {
                d.dismiss();
                openTab(null, null, true, false);
            }));
            actions.addView(sheetButton(R.drawable.ic_close, R.string.close_all_tabs, () -> {
                d.dismiss();
                closeAllTabs();
            }));
            box.addView(actions);
        };
        fill[0].run();
        d.show();
    }

    private View sheetButton(int icon, int label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(getColor(R.color.text_primary));
        t.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        t.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.brand)));
        t.setCompoundDrawablePadding(App.dp(4));
        t.setPadding(0, App.dp(10), 0, App.dp(8));
        t.setBackgroundResource(selectable());
        t.setOnClickListener(v -> action.run());
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return t;
    }

    private void closeAllTabs() {
        for (Tab t : new ArrayList<>(tabs)) t.destroyWeb();
        tabs.clear();
        closed.clear();
        tabsCleared = false;
        cur = null;
        openTab(null, null, false, false);
    }

    // ------------------------------------------------------------------ page tools

    private void copy(String text) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("text", text));
        App.toast(getString(R.string.link_copied));
    }

    private void share(String url, String title) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url);
        if (title != null && !title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
        startActivity(Intent.createChooser(i, null));
    }

    private void openExternally(String url) {
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), null));
        } catch (Exception e) {
            App.toast(getString(R.string.no_app_found));
        }
    }

    void askOpenApp(final String url) {
        if (isFinishing() || !App.prefs().getBoolean("open_apps", true)) return;
        new AlertDialog.Builder(this).setMessage(R.string.open_app_prompt)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    try {
                        Intent i = url.startsWith("intent:") ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addCategory(Intent.CATEGORY_BROWSABLE);
                        i.setComponent(null);
                        i.setSelector(null);
                        startActivity(i);
                    } catch (Exception e) {
                        App.toast(getString(R.string.no_app_found));
                    }
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void showTextSize() {
        final android.content.SharedPreferences p = App.prefs();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(App.dp(20), App.dp(18), App.dp(20), App.dp(8));
        final TextView value = new TextView(this);
        value.setGravity(Gravity.CENTER);
        value.setTextSize(16);
        final SeekBar bar = new SeekBar(this);
        bar.setMax(30); // 50%..200% in steps of 5
        bar.setProgress((p.getInt("text_zoom", 100) - 50) / 5);
        value.setText(p.getInt("text_zoom", 100) + "%");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int zoom = 50 + progress * 5;
                value.setText(zoom + "%");
                p.edit().putInt("text_zoom", zoom).apply();
                for (Tab t : tabs) t.applySettings();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(value);
        box.addView(bar, new LinearLayout.LayoutParams(-1, App.dp(48)));
        new AlertDialog.Builder(this).setTitle(R.string.text_size).setView(box)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton("100%", (d, w) -> {
                    p.edit().putInt("text_zoom", 100).apply();
                    for (Tab t : tabs) t.applySettings();
                }).show();
    }

    private void showPageInfo() {
        if (cur.isHome()) return;
        StringBuilder sb = new StringBuilder();
        if (!cur.title.isEmpty()) sb.append(cur.title).append("\n\n");
        sb.append(cur.url).append("\n\n");
        if (cur.url.startsWith("http")) {
            sb.append(getString(cur.secure() ? R.string.info_secure : R.string.info_insecure));
            SslCertificate cert = cur.web == null ? null : cur.web.getCertificate();
            if (cert != null && cur.url.startsWith("https")) {
                String to = cert.getIssuedTo() == null ? "" : cert.getIssuedTo().getCName();
                String by = cert.getIssuedBy() == null ? "" : cert.getIssuedBy().getOName();
                if (by == null || by.isEmpty()) by = cert.getIssuedBy() == null ? "" : cert.getIssuedBy().getCName();
                String until = cert.getValidNotAfterDate() == null ? "" : DateFormat.getDateInstance().format(cert.getValidNotAfterDate());
                sb.append("\n").append(getString(R.string.info_cert, to, by, until));
            }
            sb.append("\n\n").append(getString(R.string.info_blocked, cur.blocked));
            sb.append("\n").append(getString(AdBlocker.isSiteOff(cur.pageHost) ? R.string.adblock_site_off : R.string.adblock_site_on));
            if (AdBlocker.isStrict(cur.pageHost)) sb.append("\n").append(getString(R.string.info_strict));
            if (jsOff(cur.pageHost)) sb.append("\n").append(getString(R.string.js_site_off));
            PageResult r = cur.sniffed;
            if (r != null) {
                sb.append("\n\n").append(getString(R.string.sniff_result,
                        getString(r.type == PageResult.Type.CHAPTER ? R.string.type_chapter : R.string.type_detail), r.engine, r.confidence()));
            }
        }
        final String u = cur.url;
        new AlertDialog.Builder(this).setTitle(R.string.page_info).setMessage(sb)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.copy_link, (d, w) -> copy(u))
                .show();
    }

    private void viewSource() {
        if (cur.web == null) return;
        final Tab from = cur;
        final String u = cur.url;
        cur.web.evaluateJavascript("(function(){var h=document.documentElement.outerHTML;return h.length>3000000?h.substring(0,3000000):h})()", v -> {
            String html = WebFetcher.decodeJs(v);
            if (html.isEmpty() || isDestroyed()) return;
            Tab t = openTab(null, from, from.incognito, false);
            t.view();
            t.url = "view-source:" + u;
            t.title = "view-source:" + Urls.host(u);
            t.web.getSettings().setJavaScriptEnabled(false);
            t.web.loadDataWithBaseURL("about:blank", "<meta name=viewport content='width=device-width'>"
                    + "<pre style='white-space:pre-wrap;word-break:break-all;font:12px/1.45 monospace;margin:8px'>"
                    + TextUtils.htmlEncode(html) + "</pre>", "text/html", "UTF-8", null);
            refreshUi();
        });
    }

    private void printPage() {
        if (cur.web == null) return;
        try {
            String name = cur.title.isEmpty() ? Urls.host(cur.url) : cur.title;
            ((PrintManager) getSystemService(PRINT_SERVICE)).print(name, cur.web.createPrintDocumentAdapter(name),
                    new PrintAttributes.Builder().build());
        } catch (Exception e) {
            App.toast(getString(R.string.save_failed));
        }
    }

    private void saveOffline() {
        if (cur.web == null) return;
        String name = (cur.title.isEmpty() ? Urls.host(cur.url) : cur.title).replaceAll("[\\\\/:*?\"<>|\\s]+", " ").trim();
        if (name.length() > 60) name = name.substring(0, 60);
        final File f = new File(Tab.offlineDir(), name + " " + (System.currentTimeMillis() / 1000 % 100000) + ".mht");
        final String shown = name;
        cur.web.saveWebArchive(f.getAbsolutePath(), false, path ->
                App.toast(path == null ? getString(R.string.save_failed) : getString(R.string.saved_offline, shown)));
    }

    private void addToHomeScreen() {
        if (Build.VERSION.SDK_INT < 26) {
            App.toast(getString(R.string.shortcut_unsupported));
            return;
        }
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            App.toast(getString(R.string.shortcut_unsupported));
            return;
        }
        String label = cur.title.isEmpty() ? Urls.host(cur.url) : cur.title;
        ShortcutInfo info = new ShortcutInfo.Builder(this, "page" + cur.url.hashCode())
                .setShortLabel(label.length() > 24 ? label.substring(0, 24) : label)
                .setIcon(cur.icon != null ? Icon.createWithBitmap(cur.icon) : Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(cur.url), this, BrowserActivity.class))
                .build();
        sm.requestPinShortcut(info, null);
    }

    // ------------------------------------------------------------------ long-press menu

    boolean onLongPress(final Tab t) {
        if (t.web == null) return false;
        WebView.HitTestResult hit = t.web.getHitTestResult();
        final String extra = hit.getExtra();
        switch (hit.getType()) {
            case WebView.HitTestResult.SRC_ANCHOR_TYPE:
                if (extra == null) return false;
                showLinkMenu(t, extra, null);
                return true;
            case WebView.HitTestResult.IMAGE_TYPE:
                if (extra == null) return false;
                showLinkMenu(t, null, extra);
                return true;
            case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE:
                // an image inside a link: ask the page for the link address as well
                Message msg = new Handler(getMainLooper(), m -> {
                    String link = m.getData().getString("url");
                    String src = m.getData().getString("src");
                    if (!isDestroyed()) showLinkMenu(t, link, src == null ? extra : src);
                    return true;
                }).obtainMessage();
                t.web.requestFocusNodeHref(msg);
                return true;
            default:
                return false;
        }
    }

    private void showLinkMenu(final Tab t, final String link, final String image) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (link != null && (link.startsWith("http") || link.startsWith("file:"))) {
            labels.add(getString(R.string.open_new_tab));
            actions.add(() -> openTab(link, t, t.incognito, false));
            labels.add(getString(R.string.open_background));
            actions.add(() -> openTab(link, t, t.incognito, true));
            if (!t.incognito) {
                labels.add(getString(R.string.open_incognito));
                actions.add(() -> openTab(link, t, true, false));
            }
            labels.add(getString(R.string.copy_link));
            actions.add(() -> copy(link));
            labels.add(getString(R.string.share_link));
            actions.add(() -> share(link, null));
            labels.add(getString(R.string.download_link));
            actions.add(() -> Downloads.ask(this, link, t.web == null ? null : t.web.getSettings().getUserAgentString(), null, null, -1, t.url));
        } else if (link != null) {
            labels.add(getString(R.string.copy_link));
            actions.add(() -> copy(link));
        }
        if (image != null) {
            if (image.startsWith("http")) {
                labels.add(getString(R.string.view_image));
                actions.add(() -> openTab(image, t, t.incognito, false));
            }
            labels.add(getString(R.string.save_image));
            actions.add(() -> {
                String name = image.startsWith("data:") ? "image" : Downloads.fileName(image, null, null);
                if (name.endsWith(".bin")) name = name.substring(0, name.length() - 4) + ".jpg";
                Downloads.start(this, image, name, t.web == null ? null : t.web.getSettings().getUserAgentString(), null, t.url);
            });
            if (image.startsWith("http")) {
                labels.add(getString(R.string.copy_image_link));
                actions.add(() -> copy(image));
            }
        }
        if (labels.isEmpty()) return;
        String title = link != null ? link : image;
        if (title.startsWith("data:")) title = getString(R.string.save_image);
        new AlertDialog.Builder(this).setTitle(title.length() > 100 ? title.substring(0, 100) + "…" : title)
                .setItems(labels.toArray(new CharSequence[0]), (d, w) -> actions.get(w).run()).show();
    }

    void onDownload(Tab t, String url, String ua, String disposition, String mime, long length) {
        Downloads.ask(this, url, ua, disposition, mime, length, t.url);
        // a tab that was opened only to start the download has nothing to show
        if (t.web != null && t.parent != null && t.web.copyBackForwardList().getSize() == 0) closeTab(t);
    }

    // ------------------------------------------------------------------ fullscreen

    private void hideSystemBars(boolean on) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (on) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            } else c.show(WindowInsets.Type.systemBars());
        } else {
            w.getDecorView().setSystemUiVisibility(on ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    /** Hides the bars and system UI for reading web pages edge to edge; a small button brings them back. */
    private void setFullscreen(boolean on) {
        fullscreen = on;
        int v = on ? View.GONE : View.VISIBLE;
        topBar.setVisibility(v);
        bottomBar.setVisibility(v);
        bottomLine.setVisibility(v);
        progress.setVisibility(on ? View.GONE : View.INVISIBLE);
        exitFull.setVisibility(on ? View.VISIBLE : View.GONE);
        hideSystemBars(on);
        refreshUi();
    }

    void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customCallback = callback;
        customBox.addView(view, new FrameLayout.LayoutParams(-1, -1));
        customBox.setVisibility(View.VISIBLE);
        hideSystemBars(true);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    void hideCustomView() {
        if (customView == null) return;
        customBox.removeAllViews();
        customBox.setVisibility(View.GONE);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars(fullscreen);
    }

    // ------------------------------------------------------------------ uploads and site permissions

    boolean chooseFile(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        try {
            Intent i = params.createIntent();
            if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_FILE);
            return true;
        } catch (Exception e) {
            fileCallback = null;
            App.toast(getString(R.string.no_app_found));
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                result = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < result.length; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    private void withPermissions(String[] perms, Runnable granted, Runnable denied) {
        List<String> missing = new ArrayList<>();
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            granted.run();
            return;
        }
        if (permDenied != null) permDenied.run();
        permGranted = granted;
        permDenied = denied;
        requestPermissions(missing.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERM) return;
        boolean ok = results.length > 0;
        for (int r : results) ok &= r == PackageManager.PERMISSION_GRANTED;
        Runnable run = ok ? permGranted : permDenied;
        permGranted = permDenied = null;
        if (run != null) run.run();
    }

    void askLocation(Tab t, final String origin, final GeolocationPermissions.Callback callback) {
        if (t != cur || isFinishing() || !App.prefs().getBoolean("allow_location", true)) {
            callback.invoke(origin, false, false);
            return;
        }
        new AlertDialog.Builder(this).setMessage(getString(R.string.wants_location, Urls.host(origin)))
                .setPositiveButton(R.string.allow, (d, w) -> withPermissions(
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                        () -> callback.invoke(origin, true, false), () -> callback.invoke(origin, false, false)))
                .setNegativeButton(R.string.deny, (d, w) -> callback.invoke(origin, false, false))
                .setOnCancelListener(d -> callback.invoke(origin, false, false))
                .show();
    }

    void askMedia(Tab t, final PermissionRequest request) {
        final List<String> resources = new ArrayList<>(), perms = new ArrayList<>(), names = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                resources.add(r);
                perms.add(Manifest.permission.CAMERA);
                names.add(getString(R.string.camera));
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                resources.add(r);
                perms.add(Manifest.permission.RECORD_AUDIO);
                names.add(getString(R.string.microphone));
            }
        }
        if (resources.isEmpty() || t != cur || isFinishing() || !App.prefs().getBoolean("allow_media", true)) {
            request.deny();
            return;
        }
        String host = Urls.host(String.valueOf(request.getOrigin()));
        new AlertDialog.Builder(this).setMessage(getString(R.string.wants_media, host, TextUtils.join(", ", names)))
                .setPositiveButton(R.string.allow, (d, w) -> withPermissions(perms.toArray(new String[0]),
                        () -> request.grant(resources.toArray(new String[0])), request::deny))
                .setNegativeButton(R.string.deny, (d, w) -> request.deny())
                .setOnCancelListener(d -> request.deny())
                .show();
    }

    // ------------------------------------------------------------------ clearing data

    /** Set when open tabs were cleared from another screen; honoured when the browser comes back. */
    private static volatile boolean tabsCleared;

    /** Order of the choices: history, cookies, cache, site storage, open tabs. */
    public static void clearDataDialog(final Activity a, final Runnable closeTabs) {
        final boolean[] checked = {true, true, true, false, false};
        CharSequence[] names = {a.getString(R.string.history), a.getString(R.string.clear_cookies), a.getString(R.string.clear_web_cache),
                a.getString(R.string.clear_storage), a.getString(R.string.clear_tabs)};
        new AlertDialog.Builder(a).setTitle(R.string.clear_data)
                .setMultiChoiceItems(names, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    clearData(a, checked);
                    if (checked[4] && closeTabs != null) closeTabs.run();
                    App.toast(a.getString(R.string.cleared));
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    static void clearData(Context c, boolean[] what) {
        if (what[0]) {
            Db.clearHistory();
            Favicons.clearUnbookmarked();
        }
        if (what[1]) {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        }
        if (what[2]) {
            try {
                WebView w = new WebView(c);
                w.clearCache(true);
                w.clearFormData();
                w.destroy();
            } catch (Throwable ignored) {
            }
        }
        if (what[3]) {
            WebStorage.getInstance().deleteAllData();
            GeolocationPermissions.getInstance().clearAll();
        }
        if (what[4]) {
            App.prefs().edit().remove("tabs_json").remove("tabs_current").apply();
            tabsCleared = true;
        }
    }

    // ------------------------------------------------------------------ reading mode

    private void enterReadingMode() {
        final PageResult r = cur.sniffed;
        if (r == null) return;
        fab.setEnabled(false);
        App.IO.execute(() -> {
            long bookId = -1;
            int idx = -1;
            String error = null;
            try {
                if (r.type == PageResult.Type.CHAPTER) {
                    long[] res = Library.openFromChapter(r);
                    bookId = res[0];
                    idx = (int) res[1];
                } else {
                    bookId = Library.openFromDetail(r);
                }
            } catch (Throwable t) {
                android.util.Log.e("Browser", "open", t);
                error = String.valueOf(t.getMessage());
            }
            final long id = bookId;
            final int chapter = idx;
            final String err = error;
            App.MAIN.post(() -> {
                fab.setEnabled(true);
                if (isDestroyed()) return;
                if (err != null) App.toast(getString(R.string.open_failed, err));
                else if (r.type == PageResult.Type.CHAPTER) ReaderActivity.start(this, id, chapter);
                else BookSheet.show(this, id);
            });
        });
    }

    // ------------------------------------------------------------------ keys

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        homeDirty = true;
        if (cur != null) refreshUi();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) hideCustomView();
        else if (findBar.getVisibility() == View.VISIBLE) closeFind();
        else if (urlBox.hasFocus()) blurUrl();
        else if (!goBack(false)) {
            if (fullscreen) setFullscreen(false);
            leave();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveTabs();
    }
}
