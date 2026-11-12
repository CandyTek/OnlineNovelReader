package com.rc.novelreader.browser;

import android.content.Context;
import android.content.SharedPreferences;

import com.rc.novelreader.App;
import com.rc.novelreader.util.Urls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Developer aid (debug builds only, see the BuildConfig.DEBUG checks in BrowserActivity): the owner's record of
 * which sites were tested by hand with the reader and whether they passed. One entry per primary domain
 * ("m.example.com" and "www.example.com" are both "example.com"), stored locally in
 * its own preferences file; nothing here changes how a site is sniffed or fetched.
 */
final class SiteTests {
    static final int NONE = 0, PASS = 1, FAIL = 2;

    static final class Entry {
        String host, url;
        int state;
        long time;
    }

    private SiteTests() {
    }

    private static boolean migrated;

    private static synchronized SharedPreferences prefs() {
        SharedPreferences p = App.I.getSharedPreferences("site_tests", Context.MODE_PRIVATE);
        if (!migrated) {
            migrated = true;
            // entries were keyed by full host at first: fold them into their primary domain, newest verdict wins
            SharedPreferences.Editor ed = null;
            for (Map.Entry<String, ?> m : p.getAll().entrySet()) {
                String k = Urls.registrable(m.getKey());
                if (k.equals(m.getKey()) || !(m.getValue() instanceof String)) continue;
                if (ed == null) ed = p.edit();
                ed.remove(m.getKey());
                Entry mine = parse(k, (String) m.getValue());
                String other = p.getString(k, null);
                Entry theirs = other == null ? null : parse(k, other);
                if (mine != null && (theirs == null || mine.time > theirs.time)) ed.putString(k, (String) m.getValue());
            }
            if (ed != null) ed.commit();
        }
        return p;
    }

    private static String key(String urlOrHost) {
        if (urlOrHost == null) return "";
        String h = urlOrHost.contains("://") ? Urls.host(urlOrHost) : urlOrHost;
        return h == null ? "" : Urls.registrable(h);
    }

    private static Entry parse(String host, String v) {
        // "<state>|<time>|<url>"
        String[] p = v.split("\\|", 3);
        if (p.length < 3) return null;
        try {
            Entry e = new Entry();
            e.host = host;
            e.state = Integer.parseInt(p[0]);
            e.time = Long.parseLong(p[1]);
            e.url = p[2];
            return e;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static int state(String urlOrHost) {
        String k = key(urlOrHost);
        if (k.isEmpty()) return NONE;
        String v = prefs().getString(k, null);
        Entry e = v == null ? null : parse(k, v);
        return e == null ? NONE : e.state;
    }

    /** Records the verdict for the site of {@code url}; the address is kept as the place to start a re-test. */
    static void mark(String url, int state) {
        String k = key(url);
        if (k.isEmpty()) return;
        if (state == NONE) prefs().edit().remove(k).apply();
        else prefs().edit().putString(k, state + "|" + System.currentTimeMillis() + "|" + url).apply();
    }

    /** Entries with the given state, newest first. */
    static List<Entry> list(int state) {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, ?> m : prefs().getAll().entrySet()) {
            if (!(m.getValue() instanceof String)) continue;
            Entry e = parse(m.getKey(), (String) m.getValue());
            if (e != null && e.state == state) out.add(e);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }
}
