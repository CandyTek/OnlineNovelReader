package com.rc.novelreader.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java text helpers: chapter-title patterns, Chinese numerals, weighted lengths. */
public final class Texts {
    private Texts() {}

    private static final String CN_NUM = "0-9\\uFF10-\\uFF19零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟";

    /** Strong chapter-title evidence. */
    private static final Pattern STRONG = Pattern.compile(
            "(第\\s*[" + CN_NUM + "]+\\s*[章节節回卷集部篇话話幕折])"
                    + "|(^\\s*(chapter|chap\\.?|ch\\.?|episode|ep\\.?|part|vol\\.?|volume)\\s*[0-9]+\\b)"
                    + "|(\\b(chapter|episode)\\s+[0-9ivxlc]+\\b)"
                    + "|(^\\s*(序章|楔子|引子|序言|序幕|前言|后记|後記|尾声|尾聲|番外|终章|終章|完本感言|上架感言))"
                    + "|(^\\s*(prologue|epilogue|afterword|foreword|interlude|preface)\\b)",
            Pattern.CASE_INSENSITIVE);
    /** Weak evidence: "12. Title", "12、标题". */
    private static final Pattern WEAK = Pattern.compile("^\\s*[0-9]{1,5}\\s*[.、:\\uFF1A\\s]\\s*\\S");

    private static final Pattern NUM_CN = Pattern.compile("第\\s*([" + CN_NUM + "]+)\\s*[章节節回集篇话話幕折]");
    private static final Pattern NUM_EN = Pattern.compile("(?i)\\b(chapter|chap|ch|episode|ep|part)\\.?\\s*([0-9]+)");
    private static final Pattern NUM_LEAD = Pattern.compile("^\\s*([0-9]{1,6})(?![0-9])");

    public static boolean isChapterTitle(String s) {
        return s != null && STRONG.matcher(s).find();
    }

    public static boolean isWeakChapterTitle(String s) {
        return s != null && WEAK.matcher(s).find();
    }

    /** Start index of the chapter pattern inside s, or -1. */
    public static int chapterTitleStart(String s) {
        if (s == null) return -1;
        Matcher m = STRONG.matcher(s);
        return m.find() ? m.start() : -1;
    }

    /** Chapter ordinal parsed from a title, or -1. */
    public static int chapterNumber(String title) {
        if (title == null) return -1;
        Matcher m = NUM_CN.matcher(title);
        if (m.find()) return parseCnNumber(m.group(1));
        m = NUM_EN.matcher(title);
        if (m.find()) return parseIntSafe(m.group(2));
        m = NUM_LEAD.matcher(title);
        if (m.find()) return parseIntSafe(m.group(1));
        return -1;
    }

    private static int parseIntSafe(String s) {
        try {
            return s.length() > 9 ? -1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int cnDigit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= '\uFF10' && c <= '\uFF19') return c - '\uFF10';
        switch (c) {
            case '零': case '〇': return 0;
            case '一': case '壹': return 1;
            case '二': case '贰': case '两': return 2;
            case '三': case '叁': return 3;
            case '四': case '肆': return 4;
            case '五': case '伍': return 5;
            case '六': case '陆': return 6;
            case '七': case '柒': return 7;
            case '八': case '捌': return 8;
            case '九': case '玖': return 9;
            default: return -1;
        }
    }

    private static int cnUnit(char c) {
        switch (c) {
            case '十': case '拾': return 10;
            case '百': case '佰': return 100;
            case '千': case '仟': return 1000;
            case '万': return 10000;
            default: return 0;
        }
    }

    /** Parses "一百二十三", "123", "一二三", "十五" ... Returns -1 when unparseable. */
    public static int parseCnNumber(String s) {
        if (s == null || s.isEmpty()) return -1;
        boolean hasUnit = false;
        for (int i = 0; i < s.length(); i++) if (cnUnit(s.charAt(i)) > 0) hasUnit = true;
        long total = 0;
        if (!hasUnit) {
            for (int i = 0; i < s.length(); i++) {
                int d = cnDigit(s.charAt(i));
                if (d < 0) return -1;
                total = total * 10 + d;
                if (total > Integer.MAX_VALUE) return -1;
            }
            return (int) total;
        }
        long section = 0, num = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = cnDigit(c);
            if (d >= 0) {
                num = num * 10 + d;
                continue;
            }
            int u = cnUnit(c);
            if (u == 0) return -1;
            if (u == 10000) {
                total += (section + num) * 10000;
                section = 0;
            } else {
                if (num == 0 && u == 10) num = 1;
                section += num * u;
            }
            num = 0;
        }
        total += section + num;
        return total > Integer.MAX_VALUE ? -1 : (int) total;
    }

    public static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF);
    }

    /** Text length where CJK characters weigh 2 and whitespace 0 (a CJK char carries ~2 latin chars of content). */
    public static int wlen(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == ' ' || c == '　') continue;
            n += isCjk(c) ? 2 : 1;
        }
        return n;
    }

    public static boolean mostlyCjk(String s) {
        int cjk = 0, other = 0;
        int lim = Math.min(s.length(), 2000);
        for (int i = 0; i < lim; i++) {
            char c = s.charAt(i);
            if (isCjk(c)) cjk++;
            else if (Character.isLetter(c)) other++;
        }
        return cjk * 3 > other;
    }

    /** Trims ASCII/Unicode whitespace including nbsp and ideographic space. */
    public static String trim(String s) {
        if (s == null) return "";
        int a = 0, b = s.length();
        while (a < b && isSpace(s.charAt(a))) a++;
        while (b > a && isSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    public static boolean isSpace(char c) {
        return c <= ' ' || c == ' ' || c == '　' || c == '​' || c == '﻿'
                || c == ' ' || c == ' ' || Character.isWhitespace(c);
    }

    /** Comparison form: letters and digits only, lower-case. */
    public static String bare(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    public static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    public static String or(String a, String b) {
        return empty(a) ? b : a;
    }
}
