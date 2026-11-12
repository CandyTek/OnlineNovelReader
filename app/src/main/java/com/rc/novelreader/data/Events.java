package com.rc.novelreader.data;

import com.rc.novelreader.App;

import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny in-process event bus; listeners are always called on the main thread. */
public final class Events {
    private Events() {}

    /** A book's chapter list or metadata changed. */
    public static final int BOOK = 1;
    /** Chapters were cached or the download state changed. */
    public static final int CACHE = 2;
    /** The set of books on the shelf changed. */
    public static final int SHELF = 3;

    public interface Listener {
        void onEvent(int type, long bookId);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void add(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void remove(Listener l) {
        LISTENERS.remove(l);
    }

    public static void post(final int type, final long bookId) {
        App.MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onEvent(type, bookId);
        });
    }
}
