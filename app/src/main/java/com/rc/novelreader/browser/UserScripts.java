package com.rc.novelreader.browser;

import com.rc.novelreader.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Greasemonkey-style user scripts: JavaScript run on matching pages after they load. A script's metadata block
 * (@name, @match / @include) is understood; GM_* APIs are not provided.
 */
public final class UserScripts {
    private UserScripts() {}

    public static final class Script {
        public String name = "", match = "*", code = "";
        public boolean on = true;
    }

    private static List<Script> cache;

    public static synchronized List<Script> all() {
        if (cache == null) {
            cache = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(App.prefs().getString("user_scripts", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Script s = new Script();
                    s.name = o.optString("name");
                    s.match = o.optString("match", "*");
                    s.code = o.optString("code");
                    s.on = o.optBoolean("on", true);
                    cache.add(s);
                }
            } catch (Exception ignored) {
            }
        }
        return cache;
    }

    public static synchronized void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Script s : all()) {
                arr.put(new JSONObject().put("name", s.name).put("match", s.match).put("code", s.code).put("on", s.on));
            }
        } catch (Exception ignored) {
        }
        App.prefs().edit().putString("user_scripts", arr.toString()).apply();
    }

    private static final Pattern META = Pattern.compile("(?m)^\\s*//\\s*@(name|match|include)\\s+(.+?)\\s*$");

    /** Fills name and match from a "// ==UserScript==" header when the code has one. */
    public static void readHeader(Script s) {
        if (!s.code.contains("==UserScript==")) return;
        StringBuilder match = new StringBuilder();
        Matcher m = META.matcher(s.code);
        while (m.find()) {
            if (m.group(1).equals("name")) {
                if (s.name.isEmpty()) s.name = m.group(2);
            } else {
                if (match.length() > 0) match.append('\n');
                match.append(m.group(2));
            }
        }
        if (match.length() > 0 && (s.match.isEmpty() || s.match.equals("*"))) s.match = match.toString();
    }

    /** Patterns are separated by commas or new lines; "*" is a wildcard; a bare host matches that site. */
    static boolean matches(String patterns, String url) {
        for (String p : patterns.split("[,\\n]")) {
            p = p.trim();
            if (p.isEmpty()) continue;
            if (p.equals("*")) return true;
            if (!p.contains("/") && !p.contains("*")) p = "*://*" + p + "/*";
            StringBuilder re = new StringBuilder();
            for (String part : p.split("\\*", -1)) {
                if (re.length() > 0) re.append(".*");
                re.append(Pattern.quote(part));
            }
            if (Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE).matcher(url).matches()
                    || Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE).matcher(url + "/").matches()) return true;
        }
        return false;
    }

    /** JavaScript to run on this page, or null. Each script is isolated in its own function and try block. */
    public static String forUrl(String url) {
        StringBuilder sb = null;
        for (Script s : new ArrayList<>(all())) {
            if (!s.on || s.code.trim().isEmpty() || !matches(s.match, url)) continue;
            if (sb == null) sb = new StringBuilder();
            sb.append("(function(){try{\n").append(s.code).append("\n}catch(e){console.error('userscript',e)}})();\n");
        }
        return sb == null ? null : sb.toString();
    }
}
