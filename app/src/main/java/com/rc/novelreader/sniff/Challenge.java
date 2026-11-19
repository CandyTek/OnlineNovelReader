package com.rc.novelreader.sniff;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.regex.Pattern;

/**
 * Tells a "prove that you are human" page from content: the check a site (or the protection service in front
 * of it) puts in the way of a visitor it suspects of being a program. Only a person in a visible browser gets
 * past it, so a fetch that meets one must stop and say so instead of asking again.
 *
 * Callers ask only about pages that did not turn out to be what was wanted: a chapter that mentions a
 * verification code is a chapter.
 */
public final class Challenge {
    private Challenge() {}

    private static final int CI = Pattern.CASE_INSENSITIVE;

    /**
     * The check's own machinery in the markup, whatever the page says: these are the names the widespread
     * verification widgets and protection services give their scripts, frames and form fields.
     */
    private static final Pattern WIDGET = Pattern.compile(
            "captcha|challenge-platform|cf-browser-verification|cf[-_]chl|turnstile|geetest|slide-?verify|verify-?slider"
                    + "|nc[-_]wrapper|waf[-_]?(?:verify|challenge)", CI);

    /** What such a page tells the visitor. */
    private static final Pattern WORDS = Pattern.compile(
            "just a moment|checking (?:your|if the) (?:browser|site connection)|verify(?:ing)? (?:that )?you are (?:a )?human"
                    + "|are you a robot|not a robot|human verification|security check|complete the (?:security )?(?:check|verification)"
                    + "|人机验证|人機驗證|真人验证|真人驗證|安全验证|安全驗證|滑动验证|滑動驗證|拖动滑块|拖動滑塊|滑块验证|滑塊驗證"
                    + "|完成验证|完成驗證|进行验证|進行驗證|点击验证|點擊驗證|验证码|驗證碼|验证后继续|驗證後繼續"
                    + "|请证明您?不是机器人|不是机器人|不是機器人", CI);

    /** A page with more to read than this is not an interstitial, whatever words occur in it. */
    private static final int SHORT = 1500;

    public static boolean looksLike(String html) {
        if (html == null || html.isEmpty()) return false;
        Document d;
        try {
            d = Jsoup.parse(html);
        } catch (RuntimeException e) {
            return false;
        }
        // what the visitor reads, without the scripts
        String text = d.body() == null ? "" : d.body().text();
        if (text.length() > SHORT) return false;
        if (WORDS.matcher(text).find() || WORDS.matcher(d.title()).find()) return true;
        // the widget without a word about it (the text is drawn by its script): only on a page that says next to nothing
        return text.length() < 400 && WIDGET.matcher(html).find();
    }
}
