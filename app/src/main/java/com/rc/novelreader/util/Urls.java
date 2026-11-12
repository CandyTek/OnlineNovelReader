package com.rc.novelreader.util;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java URL helpers (no android.* so the sniffing engine is testable on the JVM). */
public final class Urls {
    private Urls() {}

    private static final Set<String> SECOND_LEVEL = new HashSet<>(Arrays.asList(
            "com", "net", "org", "gov", "edu", "co", "ac", "or", "ne"));
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern PAGE_SUFFIX = Pattern.compile("^(.*?\\d)[_\\-]p?(\\d{1,2})(\\.\\w+)?$");
    private static final Pattern PAGE_QUERY = Pattern.compile("(?i)(^|&)(page|p|pg)=\\d+");

    /** Resolves href against base; returns null for non-http(s) or non-navigational links. */
    public static String resolve(String base, String href) {
        if (href == null) return null;
        href = href.trim();
        if (href.isEmpty() || href.startsWith("#")) return null;
        String low = href.toLowerCase(Locale.ROOT);
        if (low.startsWith("javascript:") || low.startsWith("mailto:") || low.startsWith("tel:")
                || low.startsWith("data:") || low.startsWith("about:")) return null;
        try {
            URL u = new URL(new URL(base), href);
            String p = u.getProtocol();
            if (!"http".equals(p) && !"https".equals(p)) return null;
            return stripFragment(u.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static String stripFragment(String url) {
        int i = url.indexOf('#');
        return i >= 0 ? url.substring(0, i) : url;
    }

    public static String host(String url) {
        if (url == null) return "";
        try {
            String h = new URL(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    public static String path(String url) {
        try {
            String p = new URL(url).getPath();
            return p == null ? "" : p;
        } catch (Exception e) {
            return "";
        }
    }

    private static String query(String url) {
        try {
            String q = new URL(url).getQuery();
            return q == null ? "" : q;
        } catch (Exception e) {
            return "";
        }
    }

    /** Comparison key: scheme-less, lower-case host without www/m, no fragment, no trailing slash/index page. */
    public static String normalize(String url) {
        if (url == null) return "";
        String h = host(url);
        if (h.isEmpty()) return url.trim();
        if (h.startsWith("www.")) h = h.substring(4);
        String p = path(url);
        p = p.replaceAll("/(index|default)\\.(html?|php|aspx?|jsp)$", "/");
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        String q = query(url);
        return h + p + (q.isEmpty() ? "" : "?" + q);
    }

    /** Registrable domain approximation: example.com, example.com.cn, or the IP itself. */
    public static String registrable(String host) {
        if (host == null) return "";
        host = host.toLowerCase(Locale.ROOT);
        if (host.matches("[0-9.]+") || host.indexOf(':') >= 0) return host;
        String[] parts = host.split("\\.");
        int n = parts.length;
        if (n <= 2) return host;
        if (parts[n - 1].length() == 2 && SECOND_LEVEL.contains(parts[n - 2]) && n >= 3) {
            return parts[n - 3] + "." + parts[n - 2] + "." + parts[n - 1];
        }
        return parts[n - 2] + "." + parts[n - 1];
    }

    public static boolean sameSite(String urlA, String urlB) {
        String a = registrable(host(urlA)), b = registrable(host(urlB));
        return !a.isEmpty() && a.equals(b);
    }

    /** Directory part of the URL (host + path up to the last slash). */
    public static String dirOf(String url) {
        String p = path(url);
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1); // /book/123/456/ is a page, not a folder
        int i = p.lastIndexOf('/');
        return host(url) + (i >= 0 ? p.substring(0, i + 1) : "/");
    }

    /** URL "shape": digit runs collapsed, so sibling chapter URLs share one shape. */
    public static String shape(String url) {
        String q = query(url);
        return host(url) + DIGITS.matcher(path(url)).replaceAll("0")
                + (q.isEmpty() ? "" : "?" + DIGITS.matcher(q).replaceAll("0"));
    }

    /** Last number in path/query, or -1. Used for monotonic-order checks. */
    public static long lastNumber(String url) {
        String s = path(url) + "?" + query(url);
        Matcher m = DIGITS.matcher(s);
        String last = null;
        while (m.find()) last = m.group();
        if (last == null) return -1;
        if (last.length() > 17) last = last.substring(last.length() - 17);
        try {
            return Long.parseLong(last);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Key that is identical for all pages of one paginated chapter:
     * /b/1/22.html, /b/1/22_2.html, /b/1/22-3.html, /b/1/22.html?page=2 -> same key.
     */
    public static String chapterKey(String url) {
        String p = path(url);
        int slash = p.lastIndexOf('/');
        String dir = slash >= 0 ? p.substring(0, slash + 1) : "";
        String last = slash >= 0 ? p.substring(slash + 1) : p;
        Matcher m = PAGE_SUFFIX.matcher(last);
        if (m.matches()) last = m.group(1) + (m.group(3) == null ? "" : m.group(3));
        String q = PAGE_QUERY.matcher(query(url)).replaceAll("");
        return host(url) + dir + last + (q.isEmpty() ? "" : "?" + q);
    }

    /** 1-based page number inside a paginated chapter (1 when the URL carries none). */
    public static int pageNo(String url) {
        String p = path(url);
        Matcher m = PAGE_SUFFIX.matcher(p.substring(p.lastIndexOf('/') + 1));
        if (m.matches()) return Integer.parseInt(m.group(2));
        m = Pattern.compile("(?i)(^|&)(page|p|pg)=(\\d{1,3})(&|$)").matcher(query(url));
        if (m.find()) return Integer.parseInt(m.group(3));
        return 1;
    }

    /** True when next looks like the following page of the chapter at cur (22.html -> 22_2.html -> 22_3.html). */
    public static boolean sameChapter(String cur, String next) {
        if (cur == null || next == null) return false;
        if (normalize(cur).equals(normalize(next))) return false;
        return chapterKey(cur).equals(chapterKey(next)) && pageNo(next) == pageNo(cur) + 1;
    }

    /** Turns user input into a URL, or null when it should be treated as a search query. */
    public static String fromInput(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        if (s.matches("(?i)^https?://\\S+$")) return s;
        if (s.indexOf(' ') >= 0) return null;
        if (s.matches("(?i)^[a-z0-9\\-]+(\\.[a-z0-9\\-]+)+(:\\d+)?(/\\S*)?$")
                || s.matches("^localhost(:\\d+)?(/\\S*)?$")) {
            return "http://" + s;
        }
        return null;
    }
}
