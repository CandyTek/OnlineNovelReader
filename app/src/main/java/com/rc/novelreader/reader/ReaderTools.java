package com.rc.novelreader.reader;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.browser.BrowserActivity;
import com.rc.novelreader.data.Chapter;
import com.rc.novelreader.data.Library;
import com.rc.novelreader.util.Hanzi;
import com.rc.novelreader.util.Texts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The reader's study and assistant dialogs: notes, statistics, word list, look-up, AI, gestures, speed reading. */
final class ReaderTools {
    interface Job {
        String run(ReaderSheets.Text progress) throws Exception;
    }

    private final ReaderActivity a;
    private final ReaderSheets s;

    ReaderTools(ReaderActivity a, ReaderSheets s) {
        this.a = a;
        this.s = s;
    }

    private void share(String text) {
        try {
            a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), a.getString(R.string.share)));
        } catch (RuntimeException ignored) {
        }
    }

    private void copyText(String text) {
        ((android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(android.content.ClipData.newPlainText("text", text));
        App.toast(a.getString(R.string.rd_copied));
    }

    /** A long text to read, with copy and share. */
    void showText(String title, final String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(15);
        t.setLineSpacing(0, 1.35f);
        t.setTextIsSelectable(true);
        t.setPadding(App.dp(22), App.dp(12), App.dp(22), App.dp(8));
        ScrollView sv = new ScrollView(a);
        sv.addView(t);
        new AlertDialog.Builder(a).setTitle(title).setView(sv).setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.rd_copy, (d, w) -> copyText(text))
                .setNegativeButton(R.string.share, (d, w) -> share(text)).show();
    }

    // ------------------------------------------------------------------ actions for zones and gestures

    private static final int[] ACTION_NAMES = {R.string.rd_zone_menu, R.string.rd_zone_prev, R.string.rd_zone_next,
            R.string.rd_zone_none, R.string.rt_act_prev_chapter, R.string.rt_act_next_chapter, R.string.contents,
            R.string.rt_act_night, R.string.rd_bookmark_add, R.string.read_aloud, R.string.rd_auto_page,
            R.string.rd_search, R.string.rt_act_back};

    String actionName(int action) {
        return a.getString(ACTION_NAMES[Math.max(0, Math.min(ACTION_NAMES.length - 1, action))]);
    }

    void pickAction(int title, int current, final ReaderSheets.Set done) {
        String[] labels = new String[ACTION_NAMES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = actionName(i);
        s.dlg(title).setSingleChoiceItems(labels, current, (d, w) -> {
            d.dismiss();
            done.set(w);
        }).setNegativeButton(R.string.cancel, null).show();
    }

    void gestures() {
        final int[] g = ReaderPrefs.gestures.clone();
        final int[] names = {R.string.rt_swipe_up, R.string.rt_swipe_down, R.string.rt_double_tap};
        LinearLayout col = s.column();
        final TextView[] rows = new TextView[3];
        final Runnable show = () -> {
            for (int i = 0; i < 3; i++) rows[i].setText(a.getString(names[i]) + "  ·  " + actionName(g[i]));
        };
        for (int i = 0; i < 3; i++) {
            final int which = i;
            rows[i] = new TextView(a);
            rows[i].setTextSize(15);
            rows[i].setGravity(Gravity.CENTER_VERTICAL);
            rows[i].setOnClickListener(v -> pickAction(names[which], g[which], act -> {
                g[which] = act;
                show.run();
            }));
            col.addView(rows[i], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, App.dp(48)));
        }
        final boolean[] edge = {ReaderPrefs.edgeBright};
        s.toggle(col, R.string.rt_edge_bright, edge[0], v -> edge[0] = v == 1);
        s.note(col, R.string.rt_gestures_note);
        show.run();
        s.dlg(R.string.rt_gestures).setView(s.scroll(col)).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    ReaderPrefs.gestures = g;
                    ReaderPrefs.edgeBright = edge[0];
                    a.changed();
                }).show();
    }

    // ------------------------------------------------------------------ highlights and notes

    /** Colour and note of one highlight; a new one is only stored when saved. */
    void editNote(final Notes.Note n, final boolean isNew) {
        LinearLayout col = s.column();
        TextView quote = new TextView(a);
        quote.setText(n.text.length() > 160 ? n.text.substring(0, 160) + "…" : n.text);
        quote.setTextSize(13.5f);
        quote.setTextColor(0xFF8C9792);
        quote.setPadding(0, 0, 0, App.dp(8));
        col.addView(quote);
        final EditText note = new EditText(a);
        note.setHint(R.string.rt_note_hint);
        note.setText(n.note);
        note.setMaxLines(6);
        col.addView(note);
        LinearLayout colors = new LinearLayout(a);
        final View[] dots = new View[ReaderPrefs.NOTE_COLORS.length];
        final int[] chosen = {n.color};
        final Runnable show = () -> {
            for (int i = 0; i < dots.length; i++) {
                android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
                d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                d.setColor(ReaderPrefs.NOTE_COLORS[i]);
                d.setStroke(App.dp(i == chosen[0] ? 3 : 0), 0xFF3B8BEB);
                dots[i].setBackground(d);
            }
        };
        for (int i = 0; i < dots.length; i++) {
            final int which = i;
            dots[i] = new View(a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(App.dp(34), App.dp(34));
            lp.setMargins(App.dp(6), App.dp(10), App.dp(6), App.dp(4));
            colors.addView(dots[i], lp);
            dots[i].setOnClickListener(v -> {
                chosen[0] = which;
                show.run();
            });
        }
        col.addView(colors);
        show.run();
        AlertDialog.Builder b = s.dlg(isNew ? R.string.rt_note : R.string.rt_note_edit).setView(s.scroll(col))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    n.note = note.getText().toString().trim();
                    n.color = chosen[0];
                    ReaderPrefs.noteColor = chosen[0];
                    ReaderPrefs.save();
                    if (isNew) a.notes.all().add(n);
                    a.notes.save();
                    a.notesChanged();
                });
        if (!isNew) {
            b.setNeutralButton(R.string.rd_delete, (d, w) -> {
                a.notes.all().remove(n);
                a.notes.save();
                a.notesChanged();
            });
        }
        b.show();
    }

    void notesList() {
        final List<Notes.Note> list = new ArrayList<>(a.notes.all());
        if (list.isEmpty()) {
            App.toast(a.getString(R.string.rt_notes_empty));
            return;
        }
        java.util.Collections.sort(list, (x, y) -> x.idx != y.idx ? x.idx - y.idx : x.a - y.a);
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) {
            Notes.Note n = list.get(i);
            labels[i] = n.chapterTitle + "\n" + s.shortened(n.text) + (n.note.isEmpty() ? "" : "\n✎ " + s.shortened(n.note));
        }
        final AlertDialog d = s.dlg(R.string.rt_notes).setItems(labels, (x, w) -> {
            Notes.Note n = list.get(w);
            int idx = n.idx;
            for (Chapter c : a.chapters) if (c.id == n.chapterId) idx = c.idx;
            a.jump(idx, n.a);
        }).setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.share, (x, w) -> share(a.notes.export(a.book.title))).create();
        d.getListView().setOnItemLongClickListener((p, v, pos, id) -> {
            d.dismiss();
            editNote(list.get(pos), false);
            return true;
        });
        d.show();
    }

    // ------------------------------------------------------------------ statistics

    private static final class Bars extends View {
        private final long[] days;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bars(Context c, long[] days) {
            super(c);
            this.days = days;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            long max = 60000;
            for (long v : days) max = Math.max(max, v);
            float slot = w / days.length, base = h - 18 * d;
            paint.setTextSize(10.5f * d);
            paint.setTextAlign(Paint.Align.CENTER);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.DAY_OF_YEAR, -(days.length - 1));
            for (int i = 0; i < days.length; i++) {
                float x = slot * i + slot / 2, bar = (base - 16 * d) * days[i] / max;
                paint.setColor(i == days.length - 1 ? 0xFF6FBFAE : 0xFF4E7F75);
                c.drawRect(x - slot * 0.3f, base - Math.max(bar, days[i] > 0 ? 2 * d : 0), x + slot * 0.3f, base, paint);
                paint.setColor(0xFF8C9792);
                c.drawText(String.valueOf(cal.get(java.util.Calendar.DAY_OF_MONTH)), x, h - 4 * d, paint);
                if (days[i] >= 60000) c.drawText(String.valueOf(days[i] / 60000), x, base - bar - 4 * d, paint);
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
        }
    }

    private String duration(long ms) {
        long min = ms / 60000;
        return min >= 60 ? a.getString(R.string.rt_hours_minutes, (int) (min / 60), (int) (min % 60)) : s.minutes((int) min);
    }

    void stats() {
        LinearLayout col = s.column();
        long[] days = Stats.lastDays(14);
        col.addView(new Bars(a, days), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, App.dp(150)));
        int speed = Stats.speed();
        StringBuilder sb = new StringBuilder();
        sb.append(a.getString(R.string.rt_stat_today)).append("  ").append(duration(days[days.length - 1])).append('\n');
        sb.append(a.getString(R.string.rt_stat_streak)).append("  ").append(a.getString(R.string.rt_days, Stats.streak())).append('\n');
        sb.append(a.getString(R.string.rt_stat_total)).append("  ").append(duration(Stats.totalMs())).append('\n');
        sb.append(a.getString(R.string.rt_stat_book)).append("  ").append(duration(Stats.bookMs(a.bookId))).append('\n');
        if (speed > 0) {
            sb.append(a.getString(R.string.rt_stat_speed)).append("  ").append(a.getString(R.string.rt_per_minute, speed)).append('\n');
            String text = a.shownText(a.page.currentIdx());
            if (text != null) {
                int left = Math.max(0, text.length() - a.page.currentOffset());
                sb.append(a.getString(R.string.rt_stat_chapter_left)).append("  ").append(s.minutes(Math.max(1, left / speed))).append('\n');
            }
        }
        TextView t = new TextView(a);
        t.setText(sb.toString().trim());
        t.setTextSize(15);
        t.setLineSpacing(0, 1.5f);
        t.setPadding(0, App.dp(12), 0, 0);
        col.addView(t);
        s.note(col, R.string.rt_stat_note);
        s.dlg(R.string.rt_stats).setView(s.scroll(col)).setPositiveButton(R.string.ok, null).show();
    }

    // ------------------------------------------------------------------ look-up, translation, word list

    /** The sentence around a selection, to remember a word by. */
    String sentenceAround(int idx, int start, int end) {
        String t = a.shownText(idx);
        if (t == null) return "";
        int from = Math.max(0, Math.min(start, t.length())), to = Math.max(from, Math.min(end, t.length()));
        while (from > 0 && "\n。！？.!?".indexOf(t.charAt(from - 1)) < 0 && start - from < 120) from--;
        while (to < t.length() && "\n。！？.!?".indexOf(t.charAt(to)) < 0 && to - end < 120) to++;
        if (to < t.length() && t.charAt(to) != '\n') to++;
        return t.substring(from, to).trim();
    }

    private boolean processText(String text) {
        Intent i = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        try {
            a.startActivity(Intent.createChooser(i, a.getString(R.string.rt_other_apps)));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    void lookup(final String sel, final String context) {
        final Vocab.Word word = sel.length() <= 40 ? Vocab.add(sel, context, a.book.title) : null;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add(a.getString(R.string.rt_search_web));
        acts.add(() -> BrowserActivity.open(a, sel));
        labels.add(a.getString(R.string.rt_other_apps));
        acts.add(() -> {
            if (!processText(sel)) App.toast(a.getString(R.string.rt_no_app));
        });
        if (Ai.configured()) {
            labels.add(a.getString(R.string.rt_ai_explain));
            acts.add(() -> run(R.string.rt_ai_explain, p -> Ai.ask(Ai.SYSTEM,
                    "Explain this word or phrase as it is used in the sentence, in two or three sentences.\n\nPhrase: "
                            + sel + "\n\nSentence: " + context), text -> {
                if (word != null) {
                    word.meaning = text;
                    Vocab.save();
                }
                showText(sel, text);
            }));
        }
        s.dlg(R.string.rt_lookup).setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton(R.string.cancel, null).show();
        if (word != null) App.toast(a.getString(R.string.rt_word_saved));
    }

    void translate(final String sel) {
        if (Ai.configured()) {
            final String lang = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH);
            run(R.string.rt_translate, p -> Ai.ask("You are a translator. Output only the translation, nothing else.",
                    "Translate into " + lang + ":\n\n" + sel), text -> showText(a.getString(R.string.rt_translate), text));
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                a.startActivity(new Intent(Intent.ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, sel));
                return;
            } catch (RuntimeException ignored) {
            }
        }
        if (!processText(sel)) App.toast(a.getString(R.string.rt_no_app));
    }

    void vocab() {
        final List<Vocab.Word> words = new ArrayList<>(Vocab.all());
        java.util.Collections.reverse(words);
        String[] labels = new String[words.size()];
        for (int i = 0; i < labels.length; i++) {
            Vocab.Word w = words.get(i);
            labels[i] = w.word + (w.context.isEmpty() ? "" : "\n" + s.shortened(w.context));
        }
        final int due = Vocab.due().size();
        AlertDialog.Builder b = s.dlg(R.string.rt_vocab).setNegativeButton(R.string.cancel, null);
        if (labels.length == 0) b.setMessage(R.string.rt_vocab_empty);
        else {
            b.setItems(labels, (d, w) -> word(words.get(w)));
            b.setPositiveButton(a.getString(R.string.rt_review, due), (d, w) -> review(Vocab.due(), 0));
            b.setNeutralButton(R.string.share, (d, w) -> share(Vocab.export()));
        }
        b.show();
    }

    private void word(final Vocab.Word w) {
        LinearLayout col = s.column();
        TextView ctx = new TextView(a);
        ctx.setText(w.context + (w.book.isEmpty() ? "" : "\n— " + w.book));
        ctx.setTextSize(14);
        ctx.setTextIsSelectable(true);
        col.addView(ctx);
        final EditText meaning = new EditText(a);
        meaning.setHint(R.string.rt_meaning);
        meaning.setText(w.meaning);
        meaning.setMaxLines(8);
        col.addView(meaning);
        new AlertDialog.Builder(a).setTitle(w.word).setView(s.scroll(col))
                .setPositiveButton(R.string.save, (d, x) -> {
                    w.meaning = meaning.getText().toString().trim();
                    Vocab.save();
                })
                .setNegativeButton(R.string.rt_lookup, (d, x) -> lookup(w.word, w.context))
                .setNeutralButton(R.string.rd_delete, (d, x) -> {
                    Vocab.all().remove(w);
                    Vocab.save();
                    vocab();
                }).show();
    }

    /** One card after another: the word first, the sentence and meaning on demand, then "knew it" or "again". */
    private void review(final List<Vocab.Word> due, final int at) {
        if (at >= due.size()) {
            App.toast(a.getString(at == 0 ? R.string.rt_review_none : R.string.rt_review_done));
            return;
        }
        final Vocab.Word w = due.get(at);
        final TextView back = new TextView(a);
        back.setTextSize(15);
        back.setLineSpacing(0, 1.35f);
        back.setPadding(App.dp(24), App.dp(14), App.dp(24), App.dp(10));
        back.setText(R.string.rt_review_show);
        back.setOnClickListener(v -> back.setText(w.context + (w.meaning.isEmpty() ? "" : "\n\n" + w.meaning)));
        new AlertDialog.Builder(a).setTitle(w.word + "   " + (at + 1) + "/" + due.size()).setView(back)
                .setPositiveButton(R.string.rt_review_known, (d, x) -> {
                    Vocab.reviewed(w, true);
                    review(due, at + 1);
                })
                .setNegativeButton(R.string.rt_review_again, (d, x) -> {
                    Vocab.reviewed(w, false);
                    review(due, at + 1);
                })
                .setNeutralButton(R.string.stop, null).show();
    }

    // ------------------------------------------------------------------ AI assistant

    /** Runs a blocking job with a cancellable progress dialog, then hands its text to done on the main thread. */
    void run(int title, final Job job, final ReaderSheets.Text done) {
        final boolean[] cancelled = {false};
        final AlertDialog d = s.dlg(title).setMessage(R.string.rt_ai_working)
                .setNegativeButton(R.string.cancel, (x, w) -> cancelled[0] = true).setCancelable(false).create();
        d.show();
        App.IO.execute(() -> {
            String text = null, error = null;
            try {
                text = job.run(msg -> App.MAIN.post(() -> {
                    if (d.isShowing()) d.setMessage(msg);
                }));
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final String out = text, err = error;
            App.MAIN.post(() -> {
                if (d.isShowing()) d.dismiss();
                if (cancelled[0] || a.isDestroyed()) return;
                if (out != null) done.set(out);
                else showText(a.getString(R.string.rt_ai_failed), err);
            });
        });
    }

    void ai() {
        if (!Ai.configured()) {
            aiSettings();
            return;
        }
        String[] items = {a.getString(R.string.rt_ai_summary), a.getString(R.string.rt_ai_recap),
                a.getString(R.string.rt_ai_ask), a.getString(R.string.settings)};
        s.dlg(R.string.rt_ai).setItems(items, (d, w) -> {
            if (w == 0) summary(false);
            else if (w == 1) recap();
            else if (w == 2) question();
            else aiSettings();
        }).setNegativeButton(R.string.cancel, null).show();
    }

    void aiSettings() {
        LinearLayout col = s.column();
        final int[] format = {Ai.format()};
        final TextView kind = s.chip("");
        final EditText url = new EditText(a), key = new EditText(a), model = new EditText(a);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(App.prefs().getString("ai_url", ""));
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setHint(R.string.rt_ai_key);
        key.setText(Ai.key());
        model.setSingleLine(true);
        model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        model.setText(App.prefs().getString("ai_model", ""));
        final Runnable show = () -> {
            boolean claude = format[0] == Ai.CLAUDE;
            kind.setText(claude ? R.string.rt_ai_format_claude : R.string.rt_ai_format_openai);
            url.setHint(claude ? Ai.CLAUDE_URL : a.getString(R.string.rt_ai_url_hint));
            model.setHint(claude ? Ai.CLAUDE_MODEL : a.getString(R.string.rt_ai_model));
        };
        kind.setOnClickListener(v -> {
            format[0] = 1 - format[0];
            show.run();
        });
        kind.setPadding(App.dp(12), App.dp(9), App.dp(12), App.dp(9));
        col.addView(kind, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(url);
        col.addView(key);
        col.addView(model);
        s.note(col, R.string.rt_ai_note);
        show.run();
        s.dlg(R.string.rt_ai).setView(s.scroll(col)).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> App.prefs().edit().putInt("ai_format", format[0])
                        .putString("ai_url", url.getText().toString().trim())
                        .putString("ai_key", key.getText().toString().trim())
                        .putString("ai_model", model.getText().toString().trim()).apply()).show();
    }

    private void summary(boolean again) {
        final int idx = a.page.currentIdx();
        final String text = a.shownText(idx);
        if (text == null || idx >= a.chapters.size()) {
            App.toast(a.getString(R.string.loading));
            return;
        }
        final Chapter c = a.chapters.get(idx);
        final String title = a.chapterTitle(idx);
        String have = Ai.summary(c.id);
        if (!have.isEmpty() && !again) {
            new AlertDialog.Builder(a).setTitle(title).setMessage(have).setPositiveButton(R.string.ok, null)
                    .setNeutralButton(R.string.rt_ai_again, (d, w) -> summary(true)).show();
            return;
        }
        run(R.string.rt_ai_summary, p -> Ai.summarize(title, text), out -> {
            Ai.putSummary(c.id, out);
            showText(title, out);
        });
    }

    /** Summaries of the chapters before this one, oldest first, made on the way when the text is cached. */
    private String earlier(int cur, int window, int maxNew, ReaderSheets.Text progress, int[] counts) throws Exception {
        StringBuilder sb = new StringBuilder();
        List<Chapter> all = new ArrayList<>(a.chapters);
        int made = 0;
        for (int i = Math.max(0, cur - window); i < cur && i < all.size(); i++) {
            Chapter c = all.get(i);
            String sum = Ai.summary(c.id);
            if (sum.isEmpty() && made < maxNew) {
                String raw = Library.cachedText(c);
                if (raw != null) {
                    progress.set(a.getString(R.string.rt_ai_summarising, c.displayTitle()));
                    sum = Ai.summarize(c.displayTitle(), a.display(c, raw));
                    Ai.putSummary(c.id, sum);
                    made++;
                }
            }
            if (sum.isEmpty()) {
                counts[1]++;
                continue;
            }
            counts[0]++;
            sb.append(c.displayTitle()).append(": ").append(sum).append("\n");
        }
        return sb.toString();
    }

    /** The part of the open chapter the reader has got through (to the end of the page on screen, roughly). */
    private String readSoFar(int idx) {
        String t = a.shownText(idx);
        if (t == null) return "";
        int end = Math.min(t.length(), a.page.currentOffset() + 600);
        while (end < t.length() && t.charAt(end) != '\n') end++;
        return t.substring(0, end);
    }

    private void recap() {
        final int idx = a.page.currentIdx();
        final String part = readSoFar(idx);
        run(R.string.rt_ai_recap, p -> {
            int[] counts = new int[2];
            String sums = earlier(idx, 80, 8, p, counts);
            p.set(a.getString(R.string.rt_ai_working));
            String out = Ai.ask(Ai.SYSTEM, "Below are summaries of the chapters read so far, then the part of the current chapter "
                    + "already read. Write \"the story so far\" for someone picking the book up again: the main threads, where the "
                    + "characters stand, what is unresolved. Use only this material.\n\n" + sums
                    + "\nCurrent chapter, as far as read:\n" + part);
            return out + "\n\n" + a.getString(R.string.rt_ai_used, counts[0], counts[1]);
        }, out -> showText(a.getString(R.string.rt_ai_recap), out));
    }

    private void question() {
        final EditText q = new EditText(a);
        q.setHint(R.string.rt_ai_ask_hint);
        q.setMaxLines(4);
        LinearLayout col = s.column();
        col.addView(q);
        s.note(col, R.string.rt_ai_ask_note);
        final int idx = a.page.currentIdx();
        final String part = readSoFar(idx);
        s.dlg(R.string.rt_ai_ask).setView(col).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    final String ask = q.getText().toString().trim();
                    if (ask.isEmpty()) return;
                    run(R.string.rt_ai_ask, p -> {
                        int[] counts = new int[2];
                        // no new summaries here: a question should cost one request
                        String sums = earlier(idx, 300, 0, p, counts);
                        String out = Ai.ask(Ai.SYSTEM, "Answer the reader's question from the material below only: summaries of the "
                                + "chapters read so far and the part of the current chapter already read. If it does not say, say so.\n\n"
                                + sums + "\nCurrent chapter, as far as read:\n" + part + "\n\nQuestion: " + ask);
                        return out + "\n\n" + a.getString(R.string.rt_ai_used, counts[0], counts[1]);
                    }, out -> showText(ask, out));
                }).show();
    }

    // ------------------------------------------------------------------ text conversion, night schedule, online voice

    void convert() {
        String[] labels = {s.onOff(false), a.getString(R.string.rt_to_traditional), a.getString(R.string.rt_to_simplified)};
        s.dlg(R.string.rt_convert).setSingleChoiceItems(labels, ReaderPrefs.convert, (d, w) -> {
            d.dismiss();
            ReaderPrefs.convert = w;
            ReaderPrefs.save();
            a.reprocess();
        }).setNegativeButton(R.string.cancel, null).show();
    }

    static String clock(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    void nightSchedule() {
        final int[] v = {ReaderPrefs.nightFrom < 0 ? 22 * 60 : ReaderPrefs.nightFrom, ReaderPrefs.nightTo < 0 ? 6 * 60 : ReaderPrefs.nightTo};
        final boolean[] on = {ReaderPrefs.nightFrom >= 0};
        LinearLayout col = s.column();
        s.toggle(col, R.string.rt_night_schedule, on[0], x -> on[0] = x == 1);
        final TextView[] rows = new TextView[2];
        final int[] names = {R.string.rt_night_from, R.string.rt_night_to};
        final Runnable show = () -> {
            for (int i = 0; i < 2; i++) rows[i].setText(a.getString(names[i]) + "  ·  " + clock(v[i]));
        };
        for (int i = 0; i < 2; i++) {
            final int which = i;
            rows[i] = new TextView(a);
            rows[i].setTextSize(15);
            rows[i].setGravity(Gravity.CENTER_VERTICAL);
            rows[i].setOnClickListener(x -> new TimePickerDialog(a, (picker, h, m) -> {
                v[which] = h * 60 + m;
                show.run();
            }, v[which] / 60, v[which] % 60, true).show());
            col.addView(rows[i], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, App.dp(48)));
        }
        s.note(col, R.string.rt_night_note);
        show.run();
        s.dlg(R.string.rt_night_schedule).setView(col).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    ReaderPrefs.nightFrom = on[0] ? v[0] : -1;
                    ReaderPrefs.nightTo = on[0] ? v[1] : -1;
                    ReaderPrefs.save();
                    a.applySchedule(true);
                }).show();
    }

    void onlineVoice() {
        LinearLayout col = s.column();
        final boolean[] on = {ReaderPrefs.ttsOnline};
        s.toggle(col, R.string.rt_voice_online, on[0], x -> on[0] = x == 1);
        final EditText url = new EditText(a);
        url.setHint(R.string.rt_voice_url_hint);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        url.setMaxLines(5);
        url.setText(ReaderPrefs.ttsUrl);
        col.addView(url);
        s.note(col, R.string.rt_voice_note);
        final AlertDialog d = s.dlg(R.string.rt_voice_online).setView(s.scroll(col)).setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.rt_voice_test, null)
                .setPositiveButton(R.string.save, (x, w) -> {
                    ReaderPrefs.ttsUrl = url.getText().toString().trim();
                    ReaderPrefs.ttsOnline = on[0] && !ReaderPrefs.ttsUrl.isEmpty();
                    ReaderPrefs.save();
                }).create();
        d.show();
        // the test must not close the dialog
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            final String template = url.getText().toString().trim();
            if (template.isEmpty()) return;
            App.toast(a.getString(R.string.loading));
            App.IO.execute(() -> {
                try {
                    java.io.File f = Speaker.fetchAudio(a, template, a.getString(R.string.rt_voice_sample));
                    final android.media.MediaPlayer mp = new android.media.MediaPlayer();
                    mp.setDataSource(f.getPath());
                    mp.setOnCompletionListener(android.media.MediaPlayer::release);
                    mp.prepare();
                    mp.start();
                } catch (Exception e) {
                    App.toast(a.getString(R.string.rt_voice_failed, String.valueOf(e.getMessage())));
                }
            });
        });
    }

    // ------------------------------------------------------------------ speed reading

    /** Next piece to flash: a Latin word, or a few Chinese characters up to a punctuation mark. @return its end */
    private static int chunkEnd(String t, int from) {
        int n = t.length(), i = from;
        while (i < n && Character.isWhitespace(t.charAt(i))) i++;
        int start = i, cjk = 0;
        while (i < n) {
            char ch = t.charAt(i);
            if (Character.isWhitespace(ch)) break;
            boolean han = Texts.isCjk(ch);
            if (han && i > start && !Texts.isCjk(t.charAt(i - 1)) && Character.isLetterOrDigit(t.charAt(i - 1))) break;
            i++;
            if (han && ++cjk >= 4) break;
            if (!Character.isLetterOrDigit(ch) && !han) {
                // punctuation closes the piece; take a run of it along
                while (i < n && !Character.isLetterOrDigit(t.charAt(i)) && !Texts.isCjk(t.charAt(i)) && !Character.isWhitespace(t.charAt(i))) i++;
                break;
            }
        }
        return Math.max(i, Math.min(n, from + 1));
    }

    /** Shows the chapter piece by piece at a fixed pace, from the reading position; leaving it moves the reader along. */
    void speedRead() {
        final int idx = a.page.currentIdx();
        final String text = a.shownText(idx);
        if (text == null) {
            App.toast(a.getString(R.string.loading));
            return;
        }
        final int[] pos = {a.page.currentOffset()};
        final int[] speed = {Math.max(100, App.prefs().getInt("r_rsvp", 400))};
        final boolean[] playing = {true};
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(App.dp(16), App.dp(8), App.dp(16), App.dp(8));
        final TextView word = new TextView(a);
        word.setTextSize(34);
        word.setGravity(Gravity.CENTER);
        word.setSingleLine(true);
        col.addView(word, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, App.dp(150)));
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER);
        TextView minus = s.chip("−"), plus = s.chip("+");
        final TextView value = new TextView(a), toggle = s.chip("");
        value.setGravity(Gravity.CENTER);
        row.addView(minus, new LinearLayout.LayoutParams(App.dp(44), App.dp(38)));
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(plus, new LinearLayout.LayoutParams(App.dp(44), App.dp(38)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(App.dp(76), App.dp(38));
        lp.setMargins(App.dp(10), 0, 0, 0);
        row.addView(toggle, lp);
        col.addView(row);
        final AlertDialog d = s.dlg(R.string.rt_speed_read).setView(col).setPositiveButton(R.string.ok, null).create();
        final Runnable[] tick = new Runnable[1];
        final Runnable show = () -> {
            value.setText(a.getString(R.string.rt_per_minute, speed[0]));
            toggle.setText(playing[0] ? R.string.rd_pause : R.string.rd_resume);
        };
        tick[0] = () -> {
            if (!d.isShowing() || !playing[0]) return;
            if (pos[0] >= text.length()) {
                playing[0] = false;
                show.run();
                return;
            }
            int end = chunkEnd(text, pos[0]);
            String piece = text.substring(pos[0], end).trim();
            pos[0] = end;
            if (piece.isEmpty()) {
                App.MAIN.post(tick[0]);
                return;
            }
            word.setText(piece);
            // a Chinese character counts as one unit, a Latin word as two and a half; a pause at punctuation
            float units = 0;
            for (int i = 0; i < piece.length(); i++) if (Texts.isCjk(piece.charAt(i))) units++;
            if (units == 0) units = 2.5f;
            char last = piece.charAt(piece.length() - 1);
            if (!Character.isLetterOrDigit(last) && !Texts.isCjk(last)) units += 1.5f;
            App.MAIN.postDelayed(tick[0], Math.max(90, (long) (units * 60000 / speed[0])));
        };
        minus.setOnClickListener(v -> {
            speed[0] = Math.max(100, speed[0] - 50);
            show.run();
        });
        plus.setOnClickListener(v -> {
            speed[0] = Math.min(2000, speed[0] + 50);
            show.run();
        });
        toggle.setOnClickListener(v -> {
            playing[0] = !playing[0];
            show.run();
            App.MAIN.removeCallbacks(tick[0]);
            if (playing[0]) tick[0].run();
        });
        d.setOnDismissListener(x -> {
            App.MAIN.removeCallbacks(tick[0]);
            App.prefs().edit().putInt("r_rsvp", speed[0]).apply();
            a.jump(idx, Math.min(pos[0], Math.max(0, text.length() - 1)));
        });
        show.run();
        d.show();
        App.MAIN.postDelayed(tick[0], 600);
    }

    /** The table for simplified / traditional conversion, read once. */
    private static Hanzi hanzi;

    static synchronized Hanzi hanzi() {
        if (hanzi == null) {
            try {
                hanzi = new Hanzi(App.I.getAssets().open("hanzi.txt"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return hanzi;
    }
}
