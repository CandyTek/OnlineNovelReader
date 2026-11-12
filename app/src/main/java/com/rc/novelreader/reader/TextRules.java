package com.rc.novelreader.reader;

import com.rc.novelreader.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reader's own corrections: text to replace or to leave out, for one book or for all. They are applied to
 * a chapter when it is shown; the cached text stays as it was fetched, so removing a rule brings the text back.
 */
final class TextRules {
    static final class Rule {
        String find = "", to = "";
        boolean regex;
        /** 0 = every book. */
        long book;
        private Pattern compiled;

        private Pattern pattern() {
            if (compiled == null) {
                try {
                    compiled = Pattern.compile(find, regex ? 0 : Pattern.LITERAL);
                } catch (Exception e) {
                    compiled = Pattern.compile(find, Pattern.LITERAL);
                }
            }
            return compiled;
        }
    }

    private TextRules() {}

    private static List<Rule> rules;

    static synchronized List<Rule> all() {
        if (rules == null) {
            rules = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(App.prefs().getString("r_replace", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Rule r = new Rule();
                    r.find = o.getString("f");
                    r.to = o.optString("t");
                    r.regex = o.optBoolean("r");
                    r.book = o.optLong("b");
                    if (!r.find.isEmpty()) rules.add(r);
                }
            } catch (Exception ignored) {
            }
        }
        return rules;
    }

    static synchronized void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Rule r : all()) {
                r.compiled = null;
                arr.put(new JSONObject().put("f", r.find).put("t", r.to).put("r", r.regex).put("b", r.book));
            }
        } catch (Exception ignored) {
        }
        App.prefs().edit().putString("r_replace", arr.toString()).apply();
    }

    static synchronized String apply(long bookId, String text) {
        if (text == null) return null;
        for (Rule r : all()) {
            if (r.book != 0 && r.book != bookId) continue;
            try {
                text = r.pattern().matcher(text).replaceAll(r.regex ? r.to : Matcher.quoteReplacement(r.to));
            } catch (Exception ignored) {
                // a replacement with a bad group reference: leave the text alone
            }
        }
        // a removed passage must not leave empty paragraphs behind
        return text.replaceAll("\n{2,}", "\n").replaceAll("^\n+|\n+$", "");
    }
}
