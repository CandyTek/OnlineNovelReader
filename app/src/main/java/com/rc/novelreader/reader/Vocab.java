package com.rc.novelreader.reader;

import android.content.Context;

import com.rc.novelreader.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Words and phrases the reader looked up, kept with the sentence they came from and reviewed at growing intervals. */
final class Vocab {
    static final class Word {
        String word = "", context = "", book = "", meaning = "";
        long at, due;
        /** How many reviews in a row were known; picks the next interval. */
        int level;
    }

    /** Days until the next review after 0, 1, 2... correct answers. */
    private static final int[] DAYS = {1, 2, 4, 7, 15, 30, 60};
    private static final long DAY = 24 * 3600 * 1000L;

    private Vocab() {}

    private static List<Word> words;

    static synchronized List<Word> all() {
        if (words == null) {
            words = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(App.I.getSharedPreferences("reader_vocab", Context.MODE_PRIVATE).getString("w", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Word w = new Word();
                    w.word = o.getString("w");
                    w.context = o.optString("x");
                    w.book = o.optString("b");
                    w.meaning = o.optString("m");
                    w.at = o.optLong("at");
                    w.due = o.optLong("due");
                    w.level = o.optInt("lv");
                    words.add(w);
                }
            } catch (Exception ignored) {
            }
        }
        return words;
    }

    static synchronized void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Word w : all()) {
                arr.put(new JSONObject().put("w", w.word).put("x", w.context).put("b", w.book).put("m", w.meaning)
                        .put("at", w.at).put("due", w.due).put("lv", w.level));
            }
        } catch (Exception ignored) {
        }
        App.I.getSharedPreferences("reader_vocab", Context.MODE_PRIVATE).edit().putString("w", arr.toString()).apply();
    }

    /** Adds the word, or moves a known one back to the front of the queue. */
    static synchronized Word add(String word, String context, String book) {
        word = word.trim();
        long now = System.currentTimeMillis();
        for (Word w : all()) {
            if (w.word.equalsIgnoreCase(word)) {
                w.due = now;
                w.level = 0;
                if (!context.isEmpty()) w.context = context;
                save();
                return w;
            }
        }
        Word w = new Word();
        w.word = word;
        w.context = context;
        w.book = book;
        w.at = w.due = now;
        all().add(w);
        save();
        return w;
    }

    static synchronized List<Word> due() {
        List<Word> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Word w : all()) if (w.due <= now) out.add(w);
        return out;
    }

    static synchronized void reviewed(Word w, boolean known) {
        w.level = known ? Math.min(DAYS.length - 1, w.level + 1) : 0;
        w.due = System.currentTimeMillis() + (known ? DAYS[w.level] * DAY : 10 * 60 * 1000L);
        save();
    }

    static synchronized String export() {
        StringBuilder sb = new StringBuilder();
        for (Word w : all()) {
            sb.append(w.word);
            if (!w.meaning.isEmpty()) sb.append("\t").append(w.meaning.replace('\n', ' '));
            if (!w.context.isEmpty()) sb.append("\t").append(w.context.replace('\n', ' '));
            sb.append("\n");
        }
        return sb.toString();
    }
}
