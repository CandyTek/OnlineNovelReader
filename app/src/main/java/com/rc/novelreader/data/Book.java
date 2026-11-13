package com.rc.novelreader.data;

public final class Book {
    public long id;
    /** Identity: normalised contents-page URL, or "chap:<url>" for books known only by a chapter. */
    public String urlKey = "";
    public String title = "";
    public String author = "";
    public String cover = "";
    public String intro = "";
    /** Page the book was found on. */
    public String detailUrl = "";
    /** Page holding the chapter list (may equal detailUrl). */
    public String tocUrl = "";
    public boolean inShelf;
    public boolean pinned;
    /** No usable chapter list: chapters are discovered one by one through "next chapter" links. */
    public boolean linked;
    public long addedAt;
    public long lastReadAt;
    public int curIdx;
    public int curOffset;
    public int chapterCount;
    public int newCount;
    public String lastChapter = "";
    public long lastCheckAt;
    public long updatedAt;

    /** Imported from a file: there is no site to fetch anything from, the stored text is the book. */
    public boolean local() {
        return urlKey.startsWith("local:");
    }
}
