package com.rc.novelreader.sniff;

import com.rc.novelreader.util.Texts;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds text at the start and end of a chapter that is not the story: author notes, begging for votes, thanks
 * to donors, group and account plugs, "end of chapter" marks, and lines a site repeats in every chapter.
 * Only the first and last few lines are ever looked at, and only an unbroken run from the edge is removed, so
 * a sentence in the middle of the story that mentions a vote stays. One instance per book (it remembers the
 * edge lines of the chapters it has seen).
 */
public final class FillerFilter {
    /** Unmistakable on their own. */
    private static final Pattern STRONG = Pattern.compile(
            "求(一下|个|点|些|大家|各位)?.{0,6}(推荐票?|月票|收藏|订阅|打赏|票票|首订|追读|评论|三连|关注)"
                    + "|跪求|(感谢|谢谢|多谢).{0,40}(打赏|月票|推荐票|盟主|舵主|飘红|捧场|礼物)"
                    + "|(加|进)入?(书友|读者|粉丝)?群|[Qq]{2}群|书友群|读者群|粉丝群|群号|公众号|微信号|微博[:\\uFF1A@]"
                    + "|新书.{0,14}(已发|发布|上传|开张|起航|求)|(推荐|推)(一下|一本|本|几本).{0,8}(书|小说)|朋友的新?书"
                    + "|上架感言|请假条|第[一二两三四五]更|[一二两三四五]更(送到|奉上|到)"
                    + "|patreon|ko-?fi\\b|discord|support (me|us|the (author|translator))|please (vote|rate|review|leave a)"
                    + "|thanks? (for|to) .{0,30}(reading|support|patrons?)|advanced? chapters?|power stones?",
            Pattern.CASE_INSENSITIVE);
    /** Words of the trade: they count only in a short line that also talks to the reader. */
    private static final Pattern WEAK = Pattern.compile(
            "月票|推荐票|打赏|订阅|加更|爆更|补更|欠更|[一二两三四五]更|更新|请假|首发|正版|收藏|书评|码字|存稿|卡文|上架|推荐位|票");
    private static final Pattern ADDRESS = Pattern.compile(
            "大家|各位|兄弟|书友|读者|作者|小弟|本书|拜谢|抱歉|对不起|见谅|明天|今天|今晚|晚上|稍后|谢谢|感谢|拜托");
    /** A line that opens an author's note: everything after it at the end of a chapter belongs to the note. */
    private static final Pattern NOTE = Pattern.compile(
            "^[(\\uFF08\\[\\u3010]?\\s*(pps?|p\\.s\\.?)\\s*[:\\uFF1A.\\uFF0C,\\s]"
                    + "|^[(\\uFF08\\[\\u3010]?\\s*(作者(的话|有话要?说|留言|感言|按)|题外话|写在(前面|后面|最后)|本章说|章末感言|后记\\s*[:\\uFF1A])"
                    + "|^(author'?s? note|a/n\\b|t/n\\b|translator'?s? note)", Pattern.CASE_INSENSITIVE);
    private static final Pattern END = Pattern.compile(
            "^[(\\uFF08\\u3010\\[]?\\s*(本章完|未完待续|待续|本卷完|end of chapter)\\s*[)\\uFF09\\u3011\\]]?[\\u3002.!\\uFF01]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RULE = Pattern.compile("^[-\\u2014=*_~\\u00B7.\\s]{4,}$");

    private final Map<String, Set<Long>> edges = new HashMap<>();

    private static boolean filler(String s) {
        if (s.length() > 200) return false;
        if (END.matcher(s).find() || NOTE.matcher(s).find() || RULE.matcher(s).matches()) return true;
        if (s.length() <= 120 && STRONG.matcher(s).find()) return true;
        return s.length() <= 60 && WEAK.matcher(s).find() && ADDRESS.matcher(s).find();
    }

    private static String edgeKey(String line) {
        String b = Texts.bare(line);
        return b.length() >= 8 && b.length() <= 80 ? b : null;
    }

    private boolean repeated(String line) {
        String k = edgeKey(line);
        Set<Long> seen = k == null ? null : edges.get(k);
        return seen != null && seen.size() >= 3;
    }

    private void learn(long chapterId, String[] lines) {
        int n = lines.length;
        for (int i = 0; i < n; i++) {
            if (i >= 2 && i < n - 3) continue;
            String k = edgeKey(lines[i]);
            if (k == null) continue;
            Set<Long> seen = edges.get(k);
            if (seen == null) edges.put(k, seen = new HashSet<>());
            if (seen.size() < 4) seen.add(chapterId);
        }
    }

    /** Stateless: no knowledge of other chapters. */
    public static String strip(String text) {
        return new FillerFilter().strip(0, text);
    }

    /** The chapter text without the filler at its two ends; the text itself when nothing was found. */
    public synchronized String strip(long chapterId, String text) {
        if (text == null || text.isEmpty()) return text;
        String[] lines = text.split("\n");
        int n = lines.length;
        learn(chapterId, lines);
        if (n < 4) return text;
        int head = 0, tail = n;
        int headZone = Math.min(5, n / 4), tailZone = Math.min(12, n * 35 / 100);
        while (head < headZone && (filler(lines[head]) || repeated(lines[head]))) head++;
        // an author's note at the end runs to the end
        for (int i = n - tailZone; i < n; i++) {
            if (i > head && NOTE.matcher(lines[i]).find()) {
                tail = i;
                break;
            }
        }
        int floor = Math.max(head, n - tailZone);
        while (tail > floor && (filler(lines[tail - 1]) || repeated(lines[tail - 1]))) tail--;
        if (head == 0 && tail == n) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = head; i < tail; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        // a chapter that is mostly "filler" was misjudged
        if (tail - head < 3 || sb.length() * 10 < text.length() * 6) return text;
        return sb.toString();
    }
}
