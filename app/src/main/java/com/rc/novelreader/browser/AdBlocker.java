package com.rc.novelreader.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebResourceResponse;

import com.rc.novelreader.App;
import com.rc.novelreader.data.RuleStore;
import com.rc.novelreader.util.Urls;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Ad blocking in three layers:
 *  1. network: requests to known ad/tracker hosts and ad-looking third-party URLs are answered empty;
 *  2. strict mode, only on hosts the sniffer has recognised as novel sites: third-party scripts and
 *     frames are blocked too (that is where pirate sites load their rotating ad networks from);
 *  3. cosmetic: an injected script hides ad containers, floating boxes and click-hijacking overlays.
 */
public final class AdBlocker {
    private AdBlocker() {}

    private static volatile Set<String> hosts = Collections.emptySet();
    private static volatile String cosmeticJs = "";
    private static final Set<String> siteOff = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static volatile boolean enabled = true, cosmetic = true, strict = true;

    private static final Pattern AD_PATH = Pattern.compile(
            "(?i)[/._\\-](ads?|adv|adverts?|advertising|adserver|adsense|adframe|popunder|popads?|banner_?ads?|ad_?code|adjs|gg)[/._\\-]");
    /** Third parties that novel sites legitimately depend on (libraries, captchas, anti-bot checks). */
    private static final Pattern CDN = Pattern.compile(
            "(?i)jquery|bootcdn|bootcss|cloudflare|jsdelivr|googleapis|gstatic|unpkg|staticfile|cdnjs|baomitu|recaptcha|hcaptcha"
                    + "|geetest|aliyuncs|qiniu|alicdn|bdimg|bdstatic|fonts\\.|polyfill|disqus|gravatar|wp\\.com");

    public static void init(final Context ctx) {
        reload();
        siteOff.addAll(App.prefs().getStringSet("adblock_off_hosts", new HashSet<String>()));
        App.IO.execute(() -> {
            try {
                Set<String> set = new HashSet<>();
                for (String line : RuleStore.readAsset(ctx, "adhosts.txt").split("\n")) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) set.add(line);
                }
                builtIn = set;
                cosmeticJs = RuleStore.readAsset(ctx, "cosmetic.js");
            } catch (Exception e) {
                android.util.Log.e("AdBlocker", "assets", e);
            }
            loadSubscribed();
        });
    }

    private static volatile Set<String> builtIn = Collections.emptySet();
    private static volatile int subscribedCount;

    private static java.io.File listFile() {
        return new java.io.File(App.I.getFilesDir(), "adhosts_subscribed.txt");
    }

    /** Merges the bundled list with the downloaded one (if any). */
    private static void loadSubscribed() {
        Set<String> all = new HashSet<>(builtIn);
        int n = 0;
        java.io.File f = listFile();
        if (f.exists()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (all.add(line)) n++;
                }
            } catch (Exception e) {
                android.util.Log.w("AdBlocker", e);
            }
        }
        subscribedCount = n;
        hosts = all;
    }

    /** {bundled hosts, extra hosts from the subscribed list}. */
    public static int[] counts() {
        return new int[]{builtIn.size(), subscribedCount};
    }

    private static final Pattern LIST_LINE = Pattern.compile(
            "^(?:(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1)\\s+|\\|\\|)?([a-z0-9][a-z0-9\\-_.]*\\.[a-z]{2,})(?:\\^(?:\\$third-party)?)?(?:\\s+#.*)?$");

    /**
     * Downloads a block list and replaces the subscribed one. Understands hosts files, plain domain lists and the
     * domain-only rules ("||example.com^") of Adblock-style lists. Returns the number of hosts; call off the main thread.
     */
    public static int updateList(String url) throws java.io.IOException {
        byte[] data = com.rc.novelreader.net.Http.getBytes(url, null);
        Set<String> found = parseList(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        if (found.isEmpty()) throw new java.io.IOException("no hosts found in list");
        try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(listFile()), "UTF-8")) {
            for (String h : found) w.write(h + "\n");
        }
        loadSubscribed();
        return found.size();
    }

    /** Host names from a hosts file, a plain domain list or the domain-only rules of an Adblock list. */
    static Set<String> parseList(String text) {
        Set<String> found = new java.util.LinkedHashSet<>();
        for (String line : text.split("\n")) {
            line = line.trim().toLowerCase(java.util.Locale.ROOT);
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("[") || line.startsWith("@@")) continue;
            java.util.regex.Matcher m = LIST_LINE.matcher(line);
            if (m.matches() && !m.group(1).equals("localhost.localdomain")) found.add(m.group(1));
        }
        return found;
    }

    public static void removeList() {
        listFile().delete();
        loadSubscribed();
    }

    public static Set<String> sitesOff() {
        return new java.util.TreeSet<>(siteOff);
    }

    /** Re-reads the switches from preferences. */
    public static void reload() {
        SharedPreferences p = App.prefs();
        enabled = p.getBoolean("adblock", true);
        cosmetic = p.getBoolean("cosmetic", true);
        strict = p.getBoolean("aggressive", true);
    }

    public static boolean isOn(String pageHost) {
        return enabled && !siteOff.contains(pageHost);
    }

    public static boolean isSiteOff(String pageHost) {
        return siteOff.contains(pageHost);
    }

    public static void setSiteOff(String pageHost, boolean off) {
        if (off) siteOff.add(pageHost);
        else siteOff.remove(pageHost);
        App.prefs().edit().putStringSet("adblock_off_hosts", new HashSet<>(siteOff)).apply();
    }

    /** Strict rules apply on this page (a recognised novel site with blocking enabled). */
    public static boolean isStrict(String pageHost) {
        return strict && isOn(pageHost) && RuleStore.isNovelHost(pageHost);
    }

    /** Strict mode would apply here if the site turned out to be a novel site. */
    public static boolean strictPossible(String pageHost) {
        return strict && isOn(pageHost);
    }

    public static boolean isAdHost(String host) {
        Set<String> set = hosts;
        String h = host;
        while (true) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false;
            h = h.substring(dot + 1);
        }
    }

    /** Decision for a sub-resource request (never blocks the main document). */
    public static boolean shouldBlock(String url, String pageHost, boolean mainFrame, Map<String, String> headers) {
        if (mainFrame || !isOn(pageHost) || !url.startsWith("http")) return false;
        String host = Urls.host(url);
        if (host.isEmpty()) return false;
        if (isAdHost(host)) return true;
        boolean thirdParty = !Urls.registrable(host).equals(Urls.registrable(pageHost));
        if (!thirdParty) return false;
        String path = Urls.path(url);
        if (AD_PATH.matcher(path).find()) return true;
        if (strict && RuleStore.isNovelHost(pageHost)) {
            if (CDN.matcher(host).find()) return false;
            String p = path.toLowerCase(java.util.Locale.ROOT);
            if (p.endsWith(".js")) return true;
            String accept = headers == null ? null : headers.get("Accept");
            if (accept != null && accept.startsWith("text/html")) return true; // third-party frame document
        }
        return false;
    }

    /** Decision for a top-level navigation the user did not directly ask for. */
    public static boolean shouldBlockNavigation(String url, String pageUrl, boolean hasGesture, boolean isRedirect) {
        String pageHost = Urls.host(pageUrl);
        if (!isOn(pageHost)) return false;
        String host = Urls.host(url);
        if (isAdHost(host)) return true;
        return isStrict(pageHost) && !hasGesture && !isRedirect && !Urls.sameSite(url, pageUrl);
    }

    public static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    /** Script to inject into a page, or null when cosmetic filtering is off for it. */
    public static String cosmeticScript(String pageHost) {
        if (!cosmetic || !isOn(pageHost) || cosmeticJs.isEmpty()) return null;
        return cosmeticJs.replace("__STRICT__", isStrict(pageHost) ? "true" : "false");
    }
}
