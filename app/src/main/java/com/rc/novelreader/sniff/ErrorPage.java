package com.rc.novelreader.sniff;

import com.rc.novelreader.util.Texts;

import java.util.regex.Pattern;

/**
 * Tells a site's "something went wrong" page from a chapter. Such pages come with any status (often 200), sit
 * in the reader's own template with working navigation, and so pass for a very short chapter: a few words
 * under a heading. What gives them away is that the heading, or all of the little text there is, speaks about
 * the page itself (not found, failed to load, try again, still loading) instead of telling a story.
 */
public final class ErrorPage {
    private ErrorPage() {}

    private static final int CI = Pattern.CASE_INSENSITIVE;

    /** The page says it failed. */
    private static final Pattern FAILED = Pattern.compile(
            "not found|page (?:not|cannot be|can.?t be|could not be) found|forbidden|access denied|bad gateway"
                    + "|service (?:temporarily )?unavailable|gateway time-?out|too many requests|internal server error"
                    + "|webpage not available|this site can.?t be reached|net::ERR_|an error occurred|something went wrong"
                    + "|(?:failed|unable) to load"
                    + "|(?:页面|网页|頁面|網頁|网站|章节|章節|内容|內容|文章|该页|此页)(?:不存在|未找到|找不到|丢失|走丢|出错|出錯"
                    + "|已(?:被|经)?(?:删除|刪除|下架)|(?:加载|加載|载入|获取|读取)(?:失败|失敗|出错|错误)"
                    + "|(?:无法|無法|不能)(?:访问|訪問|显示|顯示|打开|打開|加载|加載))"
                    + "|(?:加载|加載|载入|获取|读取)(?:失败|失敗|出错|出錯|错误)"
                    + "|找不到(?:该|此|这个|您|你)?.{0,6}(?:页面|网页|頁面|章节|章節|内容)"
                    + "|出错了|出错啦|出錯了|(?:访问|訪問|请求|請求|操作)(?:过于|過於|太)(?:频繁|頻繁)"
                    + "|(?:服务器|伺服器)(?:错误|錯誤|繁忙|开小差|出错)|系统(?:错误|繁忙)|系統(?:錯誤|繁忙)"
                    + "|稍[后後](?:再试|再試|重试|重試|访问|訪問)|(?:刷新|重新加载|重新加載)(?:页面|頁面|重试|重試|一下|后|後)", CI);
    /** The page says it is not there yet: the frame of a script-built reader before its text arrived. */
    private static final Pattern PENDING = Pattern.compile(
            "(?:加载|加載|载入|載入)中|正在(?:加载|加載|载入|載入|读取|获取)|请稍[候后等]|請稍[候後等]|^\\W*loading\\b|please wait", CI);
    /** A status code leading the heading: "404", "HTTP 502", "Error 500 - ...". */
    private static final Pattern STATUS = Pattern.compile(
            "^\\W*(?:HTTP\\s*)?(?:error\\s*)?(?:40[0134]|410|429|50[0234]|52[0-6])(?:\\D|$)", CI);

    /**
     * @param docTitle the document title
     * @param heading  what the engine took for the chapter title
     * @param text     the cleaned main text
     */
    public static boolean looksLike(String docTitle, String heading, String text) {
        int wl = Texts.wlen(text);
        // a page with this much to say is content, whatever its heading
        if (wl >= 600) return false;
        // "Chapter 404", "第12章 系统错误" are chapters
        boolean named = Texts.isChapterTitle(heading);
        if (!named && (about(heading) || about(docTitle))) return true;
        // an ordinary heading over a few words that are only about the page
        if (wl >= 120 || text == null) return false;
        return text.trim().split("\n").length <= 3 && (FAILED.matcher(text).find() || PENDING.matcher(text).find());
    }

    private static boolean about(String title) {
        if (title == null || title.isEmpty()) return false;
        return STATUS.matcher(title).find() || FAILED.matcher(title).find() || PENDING.matcher(title).find();
    }
}
