package com.rc.novelreader.net;

import java.io.IOException;

/**
 * A fetch that got an answer, but not the page that was asked for. The kind lets the reader say what happened
 * in plain words; the message stays technical.
 */
public class FetchError extends IOException {
    /** The site answered with an error status ({@link #code}). */
    public static final int HTTP = 1;
    /** The answer came from another site (an ad took the place of the page); {@link #detail} is that host. */
    public static final int OFFSITE = 2;
    /** The page is the site's own error or "loading" message; {@link #detail} is its heading. */
    public static final int ERROR_PAGE = 3;
    /** Nothing readable on the page. */
    public static final int NO_TEXT = 4;

    public final int kind;
    public final int code;
    public final String detail;

    public FetchError(int kind, int code, String detail) {
        super(kind == HTTP ? "HTTP " + code
                : kind == OFFSITE ? "Redirected to another site (" + detail + ")"
                : kind == ERROR_PAGE ? "The site answered with an error page" + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")")
                : "No readable text on the page");
        this.kind = kind;
        this.code = code;
        this.detail = detail == null ? "" : detail;
    }
}
