package com.rc.novelreader.util;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure Java: what a "latest release" answer of the GitHub API says, and which of two versions is newer. */
public final class AppUpdate {
    private AppUpdate() {
    }

    public static final class Release {
        /** The tag, which is the version ("1.1", "v1.2.0"). */
        public String tag = "";
        public String notes = "";
        /** The release's page on the site, for the user's browser. */
        public String page = "";
        /** Address and size of the package attached to the release; empty when it has none. */
        public String apk = "";
        public long size;
    }

    /** Null when the text is not a published release (an error answer, a draft, a pre-release). */
    public static Release parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            Release r = new Release();
            r.tag = o.optString("tag_name", "").trim();
            if (r.tag.isEmpty() || o.optBoolean("draft") || o.optBoolean("prerelease")) return null;
            r.notes = o.isNull("body") ? "" : o.optString("body", "").trim();
            r.page = o.optString("html_url", "");
            JSONArray assets = o.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String url = a.optString("browser_download_url", "");
                if (!a.optString("name", "").toLowerCase().endsWith(".apk") || !url.startsWith("https://")) continue;
                r.apk = url;
                r.size = a.optLong("size");
                break;
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compares the numbers in two versions part by part ("v1.10" is newer than "1.9", "1.0" equals "1.0.0").
     * Anything that is not a digit only separates the parts.
     */
    public static int compare(String a, String b) {
        String[] x = parts(a), y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    public static boolean newer(String candidate, String installed) {
        return compare(candidate, installed) > 0;
    }

    private static String[] parts(String v) {
        String s = v == null ? "" : v.replaceAll("^[^0-9]+", "");
        return s.isEmpty() ? new String[0] : s.split("[^0-9]+");
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.length() > 15 ? s.substring(0, 15) : s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
