package com.rc.novelreader.reader;

import android.content.Context;

import com.rc.novelreader.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Highlights and notes, per book. A note remembers the passage itself, not only where it was: the shown text
 * shifts when the filler filter or a replacement changes, and the passage is then looked up again.
 */
final class Notes {
    static final class Note {
        long chapterId;
        /** Chapter index when the note was made; only a fallback for the list. */
        int idx;
        int a, b;
        String text = "", note = "", chapterTitle = "";
        int color;
        long at;
    }

    private final long bookId;
    private final List<Note> all = new ArrayList<>();

    Notes(long bookId) {
        this.bookId = bookId;
        try {
            JSONArray arr = new JSONArray(App.I.getSharedPreferences("reader_notes", Context.MODE_PRIVATE).getString("b" + bookId, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Note n = new Note();
                n.chapterId = o.getLong("c");
                n.idx = o.optInt("i");
                n.a = o.getInt("a");
                n.b = o.getInt("b");
                n.text = o.optString("x");
                n.note = o.optString("n");
                n.chapterTitle = o.optString("t");
                n.color = o.optInt("k");
                n.at = o.optLong("at");
                all.add(n);
            }
        } catch (Exception ignored) {
        }
    }

    List<Note> all() {
        return all;
    }

    void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Note n : all) {
                arr.put(new JSONObject().put("c", n.chapterId).put("i", n.idx).put("a", n.a).put("b", n.b).put("x", n.text)
                        .put("n", n.note).put("t", n.chapterTitle).put("k", n.color).put("at", n.at));
            }
        } catch (Exception ignored) {
        }
        App.I.getSharedPreferences("reader_notes", Context.MODE_PRIVATE).edit().putString("b" + bookId, arr.toString()).apply();
    }

    /**
     * The chapter's highlights as {start, end, colour} triples for the page view, re-anchored to the text as it
     * is shown now. A passage that is no longer in the text is left out (it stays in the list).
     */
    int[] ranges(long chapterId, String shown) {
        List<int[]> out = new ArrayList<>();
        for (Note n : all) {
            if (n.chapterId != chapterId || shown == null || n.text.isEmpty()) continue;
            if (!shown.startsWith(n.text, n.a)) {
                // nearest occurrence to where it used to be
                int before = shown.lastIndexOf(n.text, n.a), after = shown.indexOf(n.text, n.a);
                int at = before < 0 ? after : after < 0 ? before : (n.a - before <= after - n.a ? before : after);
                if (at < 0) continue;
                n.a = at;
                n.b = at + n.text.length();
            }
            int c = ReaderPrefs.NOTE_COLORS[Math.max(0, Math.min(ReaderPrefs.NOTE_COLORS.length - 1, n.color))];
            out.add(new int[]{n.a, n.b, c});
        }
        int[] flat = new int[out.size() * 3];
        for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, flat, i * 3, 3);
        return flat;
    }

    Note find(long chapterId, int start) {
        for (Note n : all) if (n.chapterId == chapterId && n.a == start) return n;
        return null;
    }

    /** All notes as plain text, in reading order, for sharing. */
    String export(String bookTitle) {
        List<Note> sorted = new ArrayList<>(all);
        java.util.Collections.sort(sorted, (x, y) -> x.idx != y.idx ? x.idx - y.idx : x.a - y.a);
        StringBuilder sb = new StringBuilder(bookTitle).append("\n");
        String last = null;
        for (Note n : sorted) {
            if (!n.chapterTitle.equals(last)) sb.append("\n## ").append(n.chapterTitle).append("\n");
            last = n.chapterTitle;
            sb.append("\n> ").append(n.text.replace("\n", "\n> ")).append("\n");
            if (!n.note.isEmpty()) sb.append(n.note).append("\n");
        }
        return sb.toString();
    }
}
