package com.rc.novelreader.reader;

import android.content.Context;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.rc.novelreader.App;
import com.rc.novelreader.util.Texts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the book aloud with the system's text-to-speech engine: one passage (a paragraph, or a few sentences of
 * a long one) at a time, so the page can follow and the passage being spoken can be marked.
 */
final class Speaker {
    interface Host {
        /** The passage [a, b) of chapter idx is being spoken; a = b for the heading. */
        void onSpeakPassage(int idx, int a, int b);

        /** The voice reached this offset (not all engines report it). */
        void onSpeakProgress(int idx, int offset);

        /** The chapter is finished: move to the next one and call {@link #speak} again, or {@link #stop}. */
        void onSpeakChapterEnd(int idx);

        /** Started, paused, resumed or stopped. */
        void onSpeakState();

        void onSpeakError(int reason);
    }

    static final int NO_ENGINE = 1, NO_VOICE = 2;

    /** The speaker that is talking, for the notification's stop button. */
    static Speaker current;

    private final Context ctx;
    private final Host host;
    private TextToSpeech tts;
    private boolean ready, active, paused, waiting;
    private int idx, pos, seq, spoken, failures;
    private String title = "", text = "";
    private final List<int[]> passages = new ArrayList<>();
    private Locale locale;
    private final Runnable sleep = this::stop;
    private long sleepAt;

    Speaker(Context c, Host h) {
        ctx = c.getApplicationContext();
        host = h;
    }

    boolean active() {
        return active;
    }

    boolean paused() {
        return paused;
    }

    /** Waiting for the next chapter's text. */
    boolean waiting() {
        return active && waiting;
    }

    /** Starts (or continues with) chapter idx at the text offset. */
    void speak(int idx, String title, String text, int offset) {
        this.idx = idx;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        waiting = false;
        resumeAt = 0;
        split();
        pos = 0;
        while (pos < passages.size() - 1 && passages.get(pos)[1] <= offset) pos++;
        // the heading is announced when the chapter is read from its start
        if (offset <= 0 && !this.title.isEmpty()) pos = -1;
        if (!active) {
            active = true;
            paused = false;
            current = this;
            SpeakService.start(ctx);
        }
        host.onSpeakState();
        if (tts == null) {
            tts = new TextToSpeech(ctx, status -> App.MAIN.post(() -> onInit(status)));
            return;
        }
        if (ready) next();
    }

    private void onInit(int status) {
        if (tts == null) return;
        if (status != TextToSpeech.SUCCESS) {
            host.onSpeakError(NO_ENGINE);
            stop();
            return;
        }
        ready = true;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(final String id) {
                App.MAIN.post(() -> done(id, false));
            }

            @Override
            public void onError(final String id) {
                App.MAIN.post(() -> done(id, true));
            }

            @Override
            public void onRangeStart(final String id, final int start, int end, int frame) {
                App.MAIN.post(() -> {
                    if (!active || !id.equals("u" + seq) || pos < 0 || pos >= passages.size()) return;
                    spoken = start;
                    host.onSpeakProgress(idx, passages.get(pos)[0] + resumeAt + start);
                });
            }
        });
        if (active && !paused) next();
    }

    /** Characters of the current passage already spoken when it was paused. */
    private int resumeAt;

    private void split() {
        passages.clear();
        int n = text.length(), p = 0;
        while (p < n) {
            int nl = text.indexOf('\n', p);
            if (nl < 0) nl = n;
            int a = p;
            while (a < nl) {
                int b = nl;
                if (nl - a > 160) {
                    // a long paragraph goes sentence by sentence, a few at a time
                    b = a + 60;
                    while (b < nl && "。！？!?…".indexOf(text.charAt(b)) < 0) b++;
                    while (b < nl && "。！？!?…”’」』\"".indexOf(text.charAt(b)) >= 0) b++;
                    if (nl - b < 20) b = nl;
                }
                passages.add(new int[]{a, b});
                a = b;
            }
            p = nl + 1;
        }
    }

    private void next() {
        if (!active || paused || !ready) return;
        if (pos >= passages.size()) {
            waiting = true;
            host.onSpeakChapterEnd(idx);
            return;
        }
        String say;
        if (pos < 0) {
            say = title;
            host.onSpeakPassage(idx, 0, 0);
        } else {
            int[] p = passages.get(pos);
            say = text.substring(Math.min(p[1], p[0] + resumeAt), p[1]);
            host.onSpeakPassage(idx, p[0], p[1]);
        }
        Locale want = Texts.mostlyCjk(say.length() > 20 ? say : text) ? Locale.CHINA : Locale.getDefault();
        if (!want.equals(locale)) {
            locale = want;
            try {
                int r = tts.setLanguage(want);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) host.onSpeakError(NO_VOICE);
            } catch (Exception ignored) {
            }
        }
        tts.setSpeechRate(ReaderPrefs.ttsRate / 100f);
        tts.setPitch(ReaderPrefs.ttsPitch / 100f);
        spoken = 0;
        seq++;
        if (say.trim().isEmpty()) {
            done("u" + seq, false);
            return;
        }
        if (say.length() > TextToSpeech.getMaxSpeechInputLength()) say = say.substring(0, TextToSpeech.getMaxSpeechInputLength());
        tts.speak(say, TextToSpeech.QUEUE_FLUSH, null, "u" + seq);
    }

    private void done(String id, boolean error) {
        if (!active || paused || !id.equals("u" + seq)) return;
        if (error) {
            // one bad passage is skipped; an engine that fails on everything is given up on
            if (++failures >= 5) {
                host.onSpeakError(NO_VOICE);
                stop();
                return;
            }
        } else failures = 0;
        resumeAt = 0;
        pos++;
        next();
    }

    void pause() {
        if (!active || paused) return;
        paused = true;
        resumeAt += spoken;
        seq++;
        if (tts != null) tts.stop();
        host.onSpeakState();
    }

    void resume() {
        if (!active || !paused) return;
        paused = false;
        host.onSpeakState();
        if (!waiting) next();
    }

    /** New rate or pitch: say the rest of the passage with it. */
    void retune() {
        if (!active || paused || waiting || tts == null) return;
        resumeAt += spoken;
        seq++;
        tts.stop();
        next();
    }

    void stop() {
        App.MAIN.removeCallbacks(sleep);
        sleepAt = 0;
        if (!active) return;
        active = paused = waiting = false;
        resumeAt = 0;
        seq++;
        if (tts != null) tts.stop();
        if (current == this) current = null;
        SpeakService.stop(ctx);
        host.onSpeakState();
    }

    /** Stops by itself after this many minutes; 0 cancels the timer. */
    void sleepIn(int minutes) {
        App.MAIN.removeCallbacks(sleep);
        sleepAt = 0;
        if (minutes > 0) {
            sleepAt = System.currentTimeMillis() + minutes * 60000L;
            App.MAIN.postDelayed(sleep, minutes * 60000L);
        }
    }

    /** Minutes until the sleep timer fires, 0 when it is not set. */
    int sleepLeft() {
        return sleepAt == 0 ? 0 : (int) Math.max(1, (sleepAt - System.currentTimeMillis() + 59999) / 60000);
    }

    void release() {
        stop();
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
        ready = false;
    }

    /** Opens the system screen where the engine, voice and language data are chosen. */
    static void openSystemSettings(Context c) {
        try {
            c.startActivity(new android.content.Intent("com.android.settings.TTS_SETTINGS")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    c.startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            }
        }
    }
}
