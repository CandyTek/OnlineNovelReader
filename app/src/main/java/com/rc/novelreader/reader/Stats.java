package com.rc.novelreader.reader;

import android.content.Context;
import android.content.SharedPreferences;

import com.rc.novelreader.App;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Reading time and amount, per day and per book. Counted only while the reader is in front and not resting. */
public final class Stats {
    private Stats() {}

    private static long pendingMs, pendingChars, pendingBook;

    private static SharedPreferences p() {
        return App.I.getSharedPreferences("reader_stats", Context.MODE_PRIVATE);
    }

    private static String day(long time) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(time));
    }

    /** Called about once a second; written out every half minute and when the reader is left. */
    static void add(long bookId, long ms, long chars) {
        if (pendingBook != bookId) flush();
        pendingBook = bookId;
        pendingMs += ms;
        pendingChars += chars;
        if (pendingMs >= 30000) flush();
    }

    static void flush() {
        if (pendingMs <= 0 && pendingChars <= 0) return;
        SharedPreferences p = p();
        String d = day(System.currentTimeMillis());
        p.edit().putLong("s" + d, p.getLong("s" + d, 0) + pendingMs)
                .putLong("c" + d, p.getLong("c" + d, 0) + pendingChars)
                .putLong("b" + pendingBook, p.getLong("b" + pendingBook, 0) + pendingMs)
                .putLong("total_ms", p.getLong("total_ms", 0) + pendingMs)
                .putLong("total_chars", p.getLong("total_chars", 0) + pendingChars).apply();
        pendingMs = pendingChars = 0;
    }

    /** Milliseconds read on each of the last n days, oldest first; today is the last entry. */
    public static long[] lastDays(int n) {
        flush();
        long[] out = new long[n];
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -(n - 1));
        for (int i = 0; i < n; i++) {
            out[i] = p().getLong("s" + day(c.getTimeInMillis()), 0);
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return out;
    }

    public static long totalMs() {
        flush();
        return p().getLong("total_ms", 0);
    }

    public static long totalChars() {
        return p().getLong("total_chars", 0);
    }

    public static long bookMs(long bookId) {
        flush();
        return p().getLong("b" + bookId, 0);
    }

    /** Days with at least a minute of reading, counted back from today (or yesterday, if today has none yet). */
    public static int streak() {
        flush();
        Calendar c = Calendar.getInstance();
        int n = 0;
        for (int i = 0; i < 3650; i++) {
            boolean read = p().getLong("s" + day(c.getTimeInMillis()), 0) >= 60000;
            if (read) n++;
            else if (i > 0) break;
            c.add(Calendar.DAY_OF_YEAR, -1);
        }
        return n;
    }

    /** Characters per minute over everything recorded; 0 until there is enough to go by. */
    public static int speed() {
        long ms = totalMs(), chars = totalChars();
        return ms < 120000 || chars < 500 ? 0 : (int) (chars * 60000 / ms);
    }
}
