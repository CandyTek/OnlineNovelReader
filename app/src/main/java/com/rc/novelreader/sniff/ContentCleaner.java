package com.rc.novelreader.sniff;

import com.rc.novelreader.util.Texts;

import java.util.List;
import java.util.regex.Pattern;

/** Turns raw extracted chapter text into clean paragraphs: strips site watermarks, nav crumbs and inline ads. */
public final class ContentCleaner {
    private ContentCleaner() {}

    private static final String TLD = "(com|net|org|cc|xyz|top|info|la|me|tv|cn|vip|app|biz|co|io|site|club|live|pro|tw|hk)";
    private static final Pattern URLISH = Pattern.compile(
            "(https?://|www\\.)\\S+|[a-zA-Z0-9\\-]{2,}\\s*[.\\uFF0E。]\\s*" + TLD + "\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AD_CN = Pattern.compile(
            "(记住|收藏|牢记|保存)(本站|本书|我们|网址|域名|地址|一下)|一秒记住|天才一秒|最新(网址|域名|地址)"
                    + "|手机(版|用户|端)?(阅读|请|访问|看书|浏览)|本章未完|点击(下一页|下一章|继续)|继续阅读(下一页|后面)?[!\\uFF01。]?$"
                    + "|加入书签|章节(报错|错误)|举报本章|无弹窗|最快更新|更新最快|首发(网址|域名)|笔趣阁|全文免费阅读|免费阅读全文"
                    + "|下载[本该]?(app|APP|客户端)|看最新章节|小说网.{0,6}(首发|更新)|请到.{0,12}(阅读|观看)|喜欢.{1,20}请大家收藏"
                    + "|最新章节(全文)?阅读|txt下载|TXT下载|温馨提示|安卓.{0,4}苹果|转码(页面|阅读)|退出(转码|阅读模式)|畅读模式");
    private static final Pattern AD_EN = Pattern.compile(
            "^(advertisement|sponsored( content)?|report( this)? chapter|read (the )?(latest|more|next) chapters? (at|on)"
                    + "|visit .{0,40}for (the )?(latest|best|more)|if you find any errors|tip: you can use|please (bookmark|support)"
                    + "|this chapter is updated by|find authorized novels|translator:|editor:)|unauthorized (reproduction|usage|copy)"
                    + "|stolen (from|content)|has been (stolen|taken without)|read (it )?on (royal road|amazon|the original)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAV_ONLY = Pattern.compile(
            "^((上|下)一?[章页頁节節篇]|目录|目錄|返回目录|章节目录|章节列表|加入书[签架]|投?推荐票?|书签|首页|书架|换源|报错|设置|关灯|护眼|字体|[大中小]|"
                    + "previous( chapter)?|prev|next( chapter)?|index|contents|table of contents|bookmark|[\\s|←→<>«»\\-—·/\\[\\]【】()\\uFF08\\uFF09]+)+$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_AD = Pattern.compile(
            "[(\\uFF08【\\[][^)\\uFF09】\\]]{0,40}(www\\.|https?://|\\.com|\\.net|\\.cc|\\.org|记住本站|最新网址|本章未完|笔趣阁)[^)\\uFF09】\\]]{0,40}[)\\uFF09】\\]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern END_MARK = Pattern.compile("^[(\\uFF08]?本章完[)\\uFF09]?$|^[(\\uFF08]?未完待续[)\\uFF09]?[。.]?$");
    private static final Pattern PAGE_MARK = Pattern.compile("\\s*[(\\uFF08]?第?\\s*\\d+\\s*/\\s*\\d+\\s*页?[)\\uFF09]?\\s*$|\\s*[(\\uFF08]第\\d+页[)\\uFF09]\\s*$");

    /** @param raw text with '\n' between blocks; @param title chapter title (removed if repeated at the top). */
    public static String clean(String raw, String title, List<Pattern> purify) {
        if (raw == null) return "";
        raw = INLINE_AD.matcher(raw).replaceAll("");
        raw = stripWatermark(raw);
        if (purify != null) {
            for (Pattern p : purify) raw = p.matcher(raw).replaceAll("");
        }
        String bareTitle = Texts.bare(stripPageMark(title));
        String[] lines = raw.split("\n");
        java.util.List<String> keptLines = new java.util.ArrayList<>();
        java.util.List<Boolean> indented = new java.util.ArrayList<>();
        int kept = 0;
        for (String line : lines) {
            String s = Texts.trim(line).replaceAll("[ \\t\\u00a0\\u3000]{2,}", " ");
            if (s.isEmpty()) continue;
            if (isJunk(s)) continue;
            if (kept < 2 && !bareTitle.isEmpty()) {
                String b = Texts.bare(stripPageMark(s));
                if (b.equals(bareTitle) || (b.length() < bareTitle.length() + 12 && b.endsWith(bareTitle))
                        || (bareTitle.length() > 3 && bareTitle.endsWith(b) && b.length() > 3 && s.length() < 40)) continue;
            }
            keptLines.add(s);
            indented.add(line.length() > 1 && Texts.isSpace(line.charAt(0)) && Texts.isSpace(line.charAt(1)));
            kept++;
        }
        keptLines = reflow(keptLines, indented);
        StringBuilder sb = new StringBuilder(raw.length());
        for (String s : keptLines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    private static final Pattern TAIL_TOKEN = Pattern.compile("(?<=[^\\x00-\\x7f])([A-Za-z0-9]{3,12})\\s*$");

    /**
     * Anti-copy watermark: the same short Latin token glued to the end of many paragraphs of a non-Latin text
     * (invisible on the page through styling, so it only shows once the text is extracted). Removed when one
     * token ends at least 4 paragraphs and a fifth of all of them.
     */
    static String stripWatermark(String raw) {
        String[] lines = raw.split("\n", -1);
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        int filled = 0;
        String top = null;
        for (String line : lines) {
            if (Texts.trim(line).isEmpty()) continue;
            filled++;
            java.util.regex.Matcher m = TAIL_TOKEN.matcher(line);
            if (!m.find()) continue;
            int n = seen.merge(m.group(1), 1, Integer::sum);
            if (top == null || n > seen.get(top)) top = m.group(1);
        }
        if (top == null || seen.get(top) < 4 || seen.get(top) * 5 < filled) return raw;
        Pattern cut = Pattern.compile("(?<=[^\\x00-\\x7f])" + Pattern.quote(top) + "\\s*$");
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(cut.matcher(lines[i]).replaceFirst(""));
        }
        return sb.toString();
    }

    /**
     * Old-style pages break every line at a fixed width with &lt;br&gt;. When most lines are equally
     * "full", they are re-joined into paragraphs: a paragraph ends at a short line or before an indented one.
     */
    /** Width in half-width cells: everything East Asian (ideographs, kana, full-width punctuation) counts 2. */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += s.charAt(i) >= 0x2E80 ? 2 : 1;
        return w;
    }

    static java.util.List<String> reflow(java.util.List<String> lines, java.util.List<Boolean> indented) {
        int n = lines.size();
        if (n < 12) return lines;
        int[] w = new int[n];
        int[] sorted = new int[n];
        for (int i = 0; i < n; i++) sorted[i] = w[i] = displayWidth(lines.get(i));
        java.util.Arrays.sort(sorted);
        int full = sorted[(int) (n * 0.85)];
        if (full < 40 || full > 200) return lines;
        int near = 0, ended = 0;
        for (int i = 0; i < n; i++) {
            if (w[i] < full - 6 || w[i] > full + 6) continue;
            near++;
            String s = lines.get(i);
            if ("。！？.!?”\"…」』".indexOf(s.charAt(s.length() - 1)) >= 0) ended++;
        }
        // real paragraphs vary in length and end with a full stop; wrapped lines are one screen line wide and end mid-sentence
        if (full > 110 || near < n * 0.5 || ended > near * 0.5) return lines;
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String s = lines.get(i);
            if (cur.length() > 0 && indented.get(i)) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0 && !Texts.isCjk(cur.charAt(cur.length() - 1)) && s.charAt(0) < 0x2e80
                    && Character.isLetterOrDigit(cur.charAt(cur.length() - 1))) cur.append(' ');
            cur.append(s);
            if (w[i] < full - 6) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    public static String stripPageMark(String title) {
        return title == null ? "" : PAGE_MARK.matcher(title).replaceAll("").trim();
    }

    static boolean isJunk(String s) {
        int w = Texts.wlen(s);
        if (END_MARK.matcher(s).find()) return true;
        if (w <= 60 && NAV_ONLY.matcher(s).matches()) return true;
        if (w <= 130) {
            if (URLISH.matcher(s).find()) return true;
            if (AD_CN.matcher(s).find()) return true;
            if (AD_EN.matcher(s).find()) return true;
        } else if (w <= 260 && URLISH.matcher(s).find() && (AD_CN.matcher(s).find() || AD_EN.matcher(s).find())) {
            return true;
        }
        return false;
    }
}
