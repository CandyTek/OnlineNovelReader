package com.rc.novelreader.sniff;

import java.util.ArrayList;
import java.util.List;

/** Everything the sniffing engine learned about one page. */
public final class PageResult {
    public enum Type { NONE, DETAIL, CHAPTER }

    public static final class BookInfo {
        public String title = "";
        public String author = "";
        public String cover = "";
        public String intro = "";
    }

    public static final class ChapterRef {
        public final String title;
        public final String url;

        public ChapterRef(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    public static final class ChapterContent {
        public String title = "";
        public String bookTitle = "";
        /** Cleaned text, one paragraph per line. */
        public String text = "";
        public String nextUrl;
        public String prevUrl;
        public String tocUrl;
        /** nextUrl is a continuation page of this same chapter (paginated chapter). */
        public boolean nextInChapter;
        /** The page has a "next" control whose target is another site (an ad swapped in): fetch again for the real link. */
        public boolean nextHijacked;
        /** Weighted length of text (see Texts.wlen). */
        public int wlen;
    }

    public String url = "";
    public Type type = Type.NONE;
    public int chapterScore;
    public int detailScore;
    /** "heuristic" or "rule:<name>". */
    public String engine = "heuristic";

    /** Filled whenever a chapter list was found, even if type != DETAIL. */
    public BookInfo book = new BookInfo();
    public List<ChapterRef> toc = new ArrayList<>();
    /** Further pages of a paginated chapter list (from a page selector). */
    public List<String> tocPages = new ArrayList<>();
    /** "Next page" of a paginated chapter list. */
    public String tocNextUrl;
    /** Link to the complete chapter list when this page only shows part of it. */
    public String moreTocUrl;

    /** Filled whenever a main text block was found, even if type != CHAPTER. */
    public ChapterContent chapter;

    /** Share of the page's text that sits in the main text block (0..1). */
    public double contentShare;

    /** CSS selectors of what the heuristics picked; used to learn per-site rules. */
    public String contentSelector;
    public String tocSelector;

    public boolean isNovel() {
        return type != Type.NONE;
    }

    public int confidence() {
        return Math.min(100, type == Type.CHAPTER ? chapterScore : type == Type.DETAIL ? detailScore : 0);
    }
}
