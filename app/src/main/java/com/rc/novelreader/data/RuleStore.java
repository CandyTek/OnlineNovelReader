package com.rc.novelreader.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.rc.novelreader.App;
import com.rc.novelreader.sniff.SiteRule;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Persists rules for the rule engine (built-in asset, user JSON, learned per host) and the set of known novel hosts. */
public final class RuleStore {
    private RuleStore() {}

    private static final String USER = "user_rules", LEARNED = "learned_rules", NOVEL_HOSTS = "novel_hosts";
    private static final Set<String> novelHosts = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static int builtinCount, userCount;

    public static void load(Context ctx) {
        SharedPreferences p = App.prefs();
        try {
            List<SiteRule> builtin = SiteRule.parseList(readAsset(ctx, "rules.json"), SiteRule.BUILTIN);
            builtinCount = builtin.size();
            App.SNIFFER.rules.setBuiltin(builtin);
        } catch (Exception e) {
            android.util.Log.e("RuleStore", "built-in rules", e);
        }
        try {
            List<SiteRule> user = SiteRule.parseList(p.getString(USER, ""), SiteRule.USER);
            userCount = user.size();
            App.SNIFFER.rules.setUser(user);
        } catch (Exception ignored) {
        }
        try {
            JSONObject o = new JSONObject(p.getString(LEARNED, "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String host = it.next();
                App.SNIFFER.rules.putLearned(host, SiteRule.fromJson(o.getJSONObject(host), SiteRule.LEARNED));
            }
        } catch (Exception ignored) {
        }
        novelHosts.addAll(p.getStringSet(NOVEL_HOSTS, new HashSet<String>()));
        App.SNIFFER.setLearnListener((host, rule) -> saveLearned());
    }

    private static synchronized void saveLearned() {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, SiteRule> e : App.SNIFFER.rules.learned().entrySet()) o.put(e.getKey(), e.getValue().toJson());
        } catch (Exception ignored) {
        }
        App.prefs().edit().putString(LEARNED, o.toString()).apply();
    }

    public static String userJson() {
        return App.prefs().getString(USER, "");
    }

    /** Validates and stores user rules; throws IllegalArgumentException with a readable message. */
    public static int saveUser(String json) {
        List<SiteRule> rules = SiteRule.parseList(json, SiteRule.USER);
        App.prefs().edit().putString(USER, json).apply();
        App.SNIFFER.rules.setUser(rules);
        userCount = rules.size();
        return userCount;
    }

    public static void clearLearned() {
        App.SNIFFER.rules.learned().clear();
        saveLearned();
    }

    public static String learnedJson() {
        StringBuilder sb = new StringBuilder();
        for (SiteRule r : App.SNIFFER.rules.learned().values()) {
            try {
                sb.append(r.toJson().toString(2)).append("\n");
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    public static int[] counts() {
        return new int[]{builtinCount, App.SNIFFER.rules.learned().size(), userCount};
    }

    public static boolean isNovelHost(String host) {
        return novelHosts.contains(host);
    }

    public static void addNovelHost(String host) {
        if (host.isEmpty() || !novelHosts.add(host)) return;
        if (novelHosts.size() > 800) novelHosts.clear();
        novelHosts.add(host);
        App.prefs().edit().putStringSet(NOVEL_HOSTS, new HashSet<>(novelHosts)).apply();
    }

    public static String readAsset(Context ctx, String name) throws java.io.IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
