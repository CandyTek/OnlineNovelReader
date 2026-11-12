package com.rc.novelreader.data;

public final class Chapter {
    public long id;
    public long bookId;
    public int idx;
    /** Empty until known (chapters discovered through "next" links get their title when loaded). */
    public String title = "";
    public String url = "";
    public String urlKey = "";
    public boolean cached;

    public String displayTitle() {
        return title == null || title.isEmpty() ? "# " + (idx + 1) : title;
    }
}
