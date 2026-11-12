package com.rc.novelreader.sniff;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One extraction rule. A rule applies to a page when its host regex matches (if given) AND its
 * signature selector matches an element (if given). Every selector is optional: whatever a rule
 * does not specify is filled in by the heuristic engine.
 *
 * Selector syntax: jsoup CSS, optionally followed by "@attr" to read an attribute
 * (e.g. "meta[property=og:novel:author]@content").
 */
public final class SiteRule {
    public static final String BUILTIN = "builtin", LEARNED = "learned", USER = "user";

    public String name = "";
    public String source = USER;
    /** Regex searched in the host name; null = any host. */
    public Pattern host;
    /** CSS selector that must match somewhere in the page; null = no structural condition. */
    public String signature;

    // chapter page
    public String content;
    public String chapterTitle;
    public String next;
    public String prev;
    public String tocLink;
    public String bookTitleInChapter;

    // detail / chapter-list page
    public String chapterList;
    public String bookTitle;
    public String author;
    public String cover;
    public String intro;

    /** Regexes removed from chapter text. */
    public final List<Pattern> purify = new ArrayList<>();

    public static SiteRule fromJson(JSONObject o, String source) {
        SiteRule r = new SiteRule();
        r.source = source;
        r.name = o.optString("name", "");
        String h = str(o, "host");
        if (h != null) r.host = Pattern.compile(h, Pattern.CASE_INSENSITIVE);
        r.signature = str(o, "signature");
        r.content = str(o, "content");
        r.chapterTitle = str(o, "chapterTitle");
        r.next = str(o, "next");
        r.prev = str(o, "prev");
        r.tocLink = str(o, "tocLink");
        r.bookTitleInChapter = str(o, "bookTitleInChapter");
        r.chapterList = str(o, "chapterList");
        r.bookTitle = str(o, "bookTitle");
        r.author = str(o, "author");
        r.cover = str(o, "cover");
        r.intro = str(o, "intro");
        JSONArray p = o.optJSONArray("purify");
        if (p != null) {
            for (int i = 0; i < p.length(); i++) {
                String s = p.optString(i, "");
                if (!s.isEmpty()) r.purify.add(Pattern.compile(s));
            }
        }
        if (r.host == null && r.signature == null) {
            throw new IllegalArgumentException("rule '" + r.name + "' needs a host or a signature");
        }
        return r;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            if (host != null) o.put("host", host.pattern());
            putIf(o, "signature", signature);
            putIf(o, "content", content);
            putIf(o, "chapterTitle", chapterTitle);
            putIf(o, "next", next);
            putIf(o, "prev", prev);
            putIf(o, "tocLink", tocLink);
            putIf(o, "bookTitleInChapter", bookTitleInChapter);
            putIf(o, "chapterList", chapterList);
            putIf(o, "bookTitle", bookTitle);
            putIf(o, "author", author);
            putIf(o, "cover", cover);
            putIf(o, "intro", intro);
            if (!purify.isEmpty()) {
                JSONArray a = new JSONArray();
                for (Pattern p : purify) a.put(p.pattern());
                o.put("purify", a);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** Parses a JSON array of rules; throws IllegalArgumentException with a readable message on bad input. */
    public static List<SiteRule> parseList(String json, String source) {
        List<SiteRule> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i), source));
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex: " + e.getMessage());
        }
        return out;
    }

    private static String str(JSONObject o, String k) {
        String s = o.optString(k, "").trim();
        return s.isEmpty() ? null : s;
    }

    private static void putIf(JSONObject o, String k, String v) throws JSONException {
        if (v != null && !v.isEmpty()) o.put(k, v);
    }
}
