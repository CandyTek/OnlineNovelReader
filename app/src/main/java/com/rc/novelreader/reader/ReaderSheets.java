package com.rc.novelreader.reader;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.data.Chapter;
import com.rc.novelreader.data.Library;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The reader's dialogs: everything behind "More" in the reading menu. */
final class ReaderSheets {
    interface Get {
        int get();
    }

    interface Set {
        void set(int v);
    }

    interface Text {
        void set(String s);
    }

    private final ReaderActivity a;
    ReaderTools tools;
    /** Receives the path of the picture the user picked for a theme. */
    private Text pendingImage;

    ReaderSheets(ReaderActivity a) {
        this.a = a;
    }

    // ------------------------------------------------------------------ building blocks

    AlertDialog.Builder dlg(int title) {
        return new AlertDialog.Builder(a).setTitle(title);
    }

    LinearLayout column() {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(App.dp(22), App.dp(10), App.dp(22), App.dp(6));
        return col;
    }

    View scroll(View v) {
        ScrollView s = new ScrollView(a);
        s.addView(v);
        return s;
    }

    TextView chip(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(13);
        t.setTextColor(0xFFE8ECE9);
        t.setBackgroundResource(R.drawable.chip_bg);
        t.setPadding(App.dp(12), 0, App.dp(12), 0);
        t.setMinWidth(App.dp(44));
        return t;
    }

    TextView note(LinearLayout col, int text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(0xFF8C9792);
        t.setPadding(0, App.dp(6), 0, App.dp(8));
        col.addView(t);
        return t;
    }

    /** "label   − value +"; zero is the word for 0 when 0 means "off". */
    void stepper(LinearLayout col, int label, final int min, final int max, final int step,
                         final Get get, final Set set, final String unit, final String zero) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(a);
        l.setText(label);
        l.setTextSize(14);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView minus = chip("−");
        final TextView value = new TextView(a);
        value.setGravity(Gravity.CENTER);
        value.setTextSize(14);
        TextView plus = chip("+");
        row.addView(minus, new LinearLayout.LayoutParams(App.dp(48), App.dp(36)));
        row.addView(value, new LinearLayout.LayoutParams(App.dp(76), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(plus, new LinearLayout.LayoutParams(App.dp(48), App.dp(36)));
        final Runnable show = () -> value.setText(get.get() == 0 && zero != null ? zero : get.get() + unit);
        minus.setOnClickListener(v -> {
            set.set(Math.max(min, get.get() - step));
            show.run();
        });
        plus.setOnClickListener(v -> {
            set.set(Math.min(max, get.get() + step));
            show.run();
        });
        show.run();
        col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, App.dp(48)));
    }

    void toggle(LinearLayout col, int label, boolean on, final Set set) {
        Switch s = new Switch(a);
        s.setText(label);
        s.setTextSize(14);
        s.setChecked(on);
        s.setOnCheckedChangeListener((b, checked) -> set.set(checked ? 1 : 0));
        col.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, App.dp(48)));
    }

    void number(int title, int initial, final int min, final int max, final Set done) {
        final EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(initial));
        e.setSelectAllOnFocus(true);
        LinearLayout col = column();
        col.addView(e);
        dlg(title).setView(col).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ok, (d, w) -> {
            try {
                done.set(Math.max(min, Math.min(max, Integer.parseInt(e.getText().toString().trim()))));
            } catch (NumberFormatException ignored) {
            }
        }).show();
    }

    String minutes(int n) {
        return a.getString(R.string.rd_minutes, n);
    }

    String onOff(boolean on) {
        return a.getString(on ? R.string.rd_on : R.string.rd_off);
    }

    // ------------------------------------------------------------------ the "More" list

    private void menu(int title, final List<String> labels, final List<Runnable> acts) {
        dlg(title).setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private String state(int label, String value) {
        return a.getString(label) + " · " + value;
    }

    void more() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (!a.book.local()) {
            labels.add(a.getString(R.string.cache));
            acts.add(a::cacheMenu);
        }
        labels.add(a.getString(R.string.rd_bookmark_add));
        acts.add(this::addBookmark);
        labels.add(a.getString(R.string.bookmarks));
        acts.add(this::bookmarks);
        labels.add(a.getString(R.string.rt_notes));
        acts.add(tools::notesList);
        labels.add(a.getString(R.string.rd_search));
        acts.add(this::search);
        labels.add(a.getString(R.string.rd_auto_page));
        acts.add(this::autoPage);
        labels.add(a.getString(R.string.rt_speed_read));
        acts.add(tools::speedRead);
        labels.add(a.getString(R.string.rt_stats));
        acts.add(tools::stats);
        labels.add(a.getString(R.string.rt_vocab));
        acts.add(tools::vocab);
        labels.add(a.getString(R.string.rt_ai));
        acts.add(tools::ai);
        labels.add(a.getString(R.string.rt_group_look));
        acts.add(this::appearance);
        labels.add(a.getString(R.string.rt_group_controls));
        acts.add(this::controls);
        labels.add(a.getString(R.string.rt_group_text));
        acts.add(this::textOptions);
        labels.add(a.getString(R.string.rd_net_test));
        acts.add(this::netTest);
        labels.add(a.getString(R.string.settings));
        acts.add(() -> a.startActivity(new android.content.Intent(a, com.rc.novelreader.settings.SettingsActivity.class)));
        menu(R.string.more, labels, acts);
    }

    private void appearance() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add(a.getString(R.string.font));
        acts.add(this::fonts);
        labels.add(a.getString(R.string.rd_new_theme));
        acts.add(() -> editTheme(null));
        labels.add(a.getString(R.string.page_layout));
        acts.add(this::layout);
        labels.add(state(R.string.rt_two_page, onOff(ReaderPrefs.twoPage)));
        acts.add(() -> {
            ReaderPrefs.twoPage = !ReaderPrefs.twoPage;
            a.changed();
            App.toast(state(R.string.rt_two_page, onOff(ReaderPrefs.twoPage)));
        });
        labels.add(state(R.string.rt_ruler, onOff(ReaderPrefs.ruler)));
        acts.add(() -> {
            ReaderPrefs.ruler = !ReaderPrefs.ruler;
            a.changed();
            App.toast(a.getString(ReaderPrefs.ruler ? R.string.rt_ruler_tip : R.string.rt_ruler) + (ReaderPrefs.ruler ? "" : " · " + onOff(false)));
        });
        labels.add(state(R.string.rd_eye, ReaderPrefs.eye == 0 ? onOff(false) : ReaderPrefs.eye + "%"));
        acts.add(this::eye);
        labels.add(state(R.string.rt_night_schedule, ReaderPrefs.nightFrom < 0 ? onOff(false)
                : ReaderTools.clock(ReaderPrefs.nightFrom) + " - " + ReaderTools.clock(ReaderPrefs.nightTo)));
        acts.add(tools::nightSchedule);
        labels.add(a.getString(R.string.rd_orientation));
        acts.add(this::orientation);
        menu(R.string.rt_group_look, labels, acts);
    }

    private void controls() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add(a.getString(R.string.rd_tap_zones));
        acts.add(this::zones);
        labels.add(a.getString(R.string.rt_gestures));
        acts.add(tools::gestures);
        labels.add(state(R.string.rd_screen_on, screenLabel(ReaderPrefs.screenMin)));
        acts.add(this::screenOn);
        labels.add(state(R.string.rd_rest, ReaderPrefs.restEvery == 0 ? onOff(false) : minutes(ReaderPrefs.restEvery)));
        acts.add(this::rest);
        labels.add(state(R.string.rt_shake, onOff(ReaderPrefs.shake)));
        acts.add(() -> {
            ReaderPrefs.shake = !ReaderPrefs.shake;
            a.changed();
            App.toast(state(R.string.rt_shake, onOff(ReaderPrefs.shake)));
        });
        labels.add(a.getString(R.string.rd_keys));
        acts.add(() -> dlg(R.string.rd_keys).setMessage(R.string.rd_keys_help).setPositiveButton(R.string.ok, null).show());
        menu(R.string.rt_group_controls, labels, acts);
    }

    private void textOptions() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add(state(R.string.rd_filler, onOff(ReaderPrefs.filler)));
        acts.add(() -> {
            ReaderPrefs.filler = !ReaderPrefs.filler;
            ReaderPrefs.save();
            a.reprocess();
            App.toast(state(R.string.rd_filler, onOff(ReaderPrefs.filler)));
        });
        labels.add(a.getString(R.string.rd_replacements));
        acts.add(this::replacements);
        labels.add(state(R.string.rt_convert, a.getString(ReaderPrefs.convert == 1 ? R.string.rt_to_traditional
                : ReaderPrefs.convert == 2 ? R.string.rt_to_simplified : R.string.rd_off)));
        acts.add(tools::convert);
        labels.add(state(R.string.rt_voice_online, onOff(ReaderPrefs.ttsOnline)));
        acts.add(tools::onlineVoice);
        menu(R.string.rt_group_text, labels, acts);
    }

    void voiceMenu() {
        String[] items = {a.getString(R.string.rt_voice_system), state(R.string.rt_voice_online, onOff(ReaderPrefs.ttsOnline))};
        dlg(R.string.rd_voice).setItems(items, (d, w) -> {
            if (w == 0) Speaker.openSystemSettings(a);
            else tools.onlineVoice();
        }).show();
    }

    // ------------------------------------------------------------------ bookmarks

    private JSONArray marks() {
        try {
            return new JSONArray(a.getSharedPreferences("reader_marks", 0).getString("b" + a.bookId, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveMarks(JSONArray arr) {
        a.getSharedPreferences("reader_marks", 0).edit().putString("b" + a.bookId, arr.toString()).apply();
    }

    void addBookmark() {
        int idx = a.page.currentIdx(), off = a.page.currentOffset();
        if (idx >= a.chapters.size()) return;
        Chapter c = a.chapters.get(idx);
        String text = a.shownText(idx);
        String excerpt = text == null ? "" : text.substring(Math.min(off, text.length()), Math.min(text.length(), off + 60)).replace('\n', ' ');
        try {
            JSONArray arr = marks();
            arr.put(new JSONObject().put("c", c.id).put("i", idx).put("o", off).put("t", c.displayTitle())
                    .put("x", excerpt).put("at", System.currentTimeMillis()));
            saveMarks(arr);
            App.toast(a.getString(R.string.bookmark_added));
        } catch (Exception ignored) {
        }
    }

    void bookmarks() {
        final JSONArray arr = marks();
        if (arr.length() == 0) {
            App.toast(a.getString(R.string.bookmarks_empty));
            return;
        }
        final List<String> labels = new ArrayList<>();
        // newest first
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            labels.add(o.optString("t") + "\n" + o.optString("x"));
        }
        final AlertDialog d = dlg(R.string.bookmarks).setItems(labels.toArray(new String[0]), (x, w) -> {
            JSONObject o = arr.optJSONObject(arr.length() - 1 - w);
            int idx = o.optInt("i");
            for (Chapter c : a.chapters) if (c.id == o.optLong("c")) idx = c.idx;
            a.jump(idx, o.optInt("o"));
        }).setNegativeButton(R.string.cancel, null).create();
        d.getListView().setOnItemLongClickListener((p, v, pos, id) -> {
            arr.remove(arr.length() - 1 - pos);
            saveMarks(arr);
            App.toast(a.getString(R.string.bookmark_removed));
            d.dismiss();
            bookmarks();
            return true;
        });
        d.show();
        App.toast(a.getString(R.string.rd_hold_to_delete));
    }

    // ------------------------------------------------------------------ search in the book

    // The search outlives its window. Going to a hit folds the window into a small bar in the top right corner
    // of the page (previous hit, "3/57", next hit, end); the bar's middle opens the window again as it was left,
    // so a list of hits can be walked through without searching anew each time.
    private String sWord = "";
    private final List<int[]> sHits = new ArrayList<>();
    private final List<String> sLabels = new ArrayList<>();
    /** The hit gone to last, the first row the list showed, and the number of the search running. */
    private int sAt = -1, sTop, sRun;
    private AlertDialog sDialog;
    private LinearLayout sPill;
    private TextView sPillText;

    void search() {
        search(null);
    }

    /** @param preset text to look for at once (a selection); null opens the search as it was left */
    void search(final String preset) {
        if (sDialog != null && sDialog.isShowing()) return;
        showPill(false);
        LinearLayout col = column();
        final EditText q = new EditText(a);
        q.setHint(R.string.rd_search_hint);
        q.setSingleLine(true);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setText(preset != null ? preset.replace('\n', ' ').trim() : sWord);
        q.setSelection(q.length());
        col.addView(q);
        final TextView info = note(col, R.string.rd_search_note);
        final boolean resumed = preset == null && !sHits.isEmpty();
        if (resumed) info.setText(a.getString(R.string.rd_search_found, sHits.size()));
        final ListView list = new ListView(a);
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, App.dp(320)));
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(a, android.R.layout.simple_list_item_1, sLabels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                // where the reader is now
                v.setBackgroundColor(position == sAt ? 0x333B8BEB : 0);
                return v;
            }
        };
        list.setAdapter(adapter);
        final boolean[] ended = {false};
        final AlertDialog d = dlg(R.string.rd_search).setView(col)
                .setNegativeButton(R.string.rd_search_end, (x, w) -> {
                    ended[0] = true;
                    endSearch();
                })
                .setPositiveButton(R.string.rd_search_fold, null).create();
        final Runnable run = () -> {
            final String word = q.getText().toString().trim();
            if (word.isEmpty()) return;
            final int mine = ++sRun;
            final List<Chapter> all = new ArrayList<>(a.chapters);
            info.setText(R.string.loading);
            App.IO.execute(() -> {
                final List<int[]> found = new ArrayList<>();
                final List<String> names = new ArrayList<>();
                String needle = word.toLowerCase(Locale.ROOT);
                for (Chapter c : all) {
                    if (found.size() >= 300) break;
                    String raw = Library.cachedText(c);
                    if (raw == null) continue;
                    String text = a.display(c, raw), low = text.toLowerCase(Locale.ROOT);
                    for (int at = low.indexOf(needle); at >= 0 && found.size() < 300; at = low.indexOf(needle, at + needle.length())) {
                        found.add(new int[]{c.idx, at, needle.length()});
                        int from = Math.max(0, at - 14), to = Math.min(text.length(), at + needle.length() + 26);
                        names.add(c.displayTitle() + "\n\u2026" + text.substring(from, to).replace('\n', ' ') + "\u2026");
                    }
                }
                App.MAIN.post(() -> {
                    if (mine != sRun || sDialog != d) return;
                    sWord = word;
                    sAt = -1;
                    sHits.clear();
                    sHits.addAll(found);
                    sLabels.clear();
                    sLabels.addAll(names);
                    adapter.notifyDataSetChanged();
                    info.setText(a.getString(R.string.rd_search_found, found.size()));
                });
            });
        };
        App.numpadEnter(q);
        q.setOnEditorActionListener((v, action, ev) -> {
            run.run();
            return true;
        });
        list.setOnItemClickListener((p, v, pos, id) -> {
            sTop = list.getFirstVisiblePosition();
            d.dismiss();
            goHit(pos);
        });
        d.setOnDismissListener(x -> {
            if (sDialog == d) sDialog = null;
            if (ended[0]) return;
            sTop = list.getFirstVisiblePosition();
            // folded, not closed: the hits stay a tap away
            showPill(!sHits.isEmpty());
        });
        // a search that comes back with its hits is for picking, not for typing
        if (!resumed && preset == null) d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        else d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        sDialog = d;
        d.show();
        if (resumed) list.setSelection(Math.max(sTop, sAt < 0 ? 0 : Math.min(sAt, Math.max(sTop, sAt - 3))));
        else if (preset != null) run.run();
        else q.requestFocus();
    }

    private void goHit(int pos) {
        if (pos < 0 || pos >= sHits.size()) return;
        sAt = pos;
        int[] h = sHits.get(pos);
        a.jump(h[0], h[1]);
        a.flash(h[0], h[1], h[1] + h[2]);
        showPill(true);
    }

    private void endSearch() {
        sRun++;
        sHits.clear();
        sLabels.clear();
        sAt = -1;
        sTop = 0;
        showPill(false);
    }

    private TextView pillButton(String text, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(17);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(App.dp(38));
        t.setOnClickListener(l);
        sPill.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, App.dp(38)));
        return t;
    }

    /** The folded search: [previous] [3/57, opens the window] [next] [end], floating over the page's corner. */
    private void showPill(boolean on) {
        if (!on) {
            if (sPill != null) sPill.setVisibility(View.GONE);
            return;
        }
        if (sPill == null) {
            sPill = new LinearLayout(a);
            sPill.setGravity(Gravity.CENTER_VERTICAL);
            sPill.setBackgroundResource(R.drawable.tip_bg);
            sPill.setPadding(App.dp(2), 0, App.dp(2), 0);
            sPill.setElevation(App.dp(4));
            pillButton("\u2039", v -> goHit(sAt <= 0 ? 0 : sAt - 1));
            sPillText = pillButton("", v -> search(null));
            sPillText.setTextSize(13.5f);
            sPillText.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
            sPillText.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            sPillText.setCompoundDrawablePadding(App.dp(4));
            sPillText.setPadding(App.dp(4), 0, App.dp(6), 0);
            sPillText.setContentDescription(a.getString(R.string.rd_search));
            pillButton("\u203A", v -> goHit(sAt + 1));
            pillButton("\u00D7", v -> endSearch()).setContentDescription(a.getString(R.string.rd_search_end));
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            lp.setMargins(0, App.dp(40), App.dp(10), 0);
            // over the page and its tint, under the menus
            a.addOverPage(sPill, lp);
        }
        sPillText.setText(sAt >= 0 ? (sAt + 1) + "/" + sHits.size() : String.valueOf(sHits.size()));
        sPill.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ automatic page turning

    void autoPage() {
        LinearLayout col = column();
        stepper(col, R.string.rd_auto_interval, 2, 120, 1, () -> ReaderPrefs.autoSec, v -> ReaderPrefs.autoSec = v,
                " s", null);
        stepper(col, R.string.rt_auto_speed, 4, 300, 4, () -> ReaderPrefs.autoSpeed, v -> ReaderPrefs.autoSpeed = v, "", null);
        toggle(col, R.string.rd_auto_sweep, ReaderPrefs.autoSweep, v -> ReaderPrefs.autoSweep = v != 0);
        note(col, R.string.rt_auto_speed_note);
        note(col, R.string.rd_auto_note);
        dlg(R.string.rd_auto_page).setView(col).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.rd_start, (d, w) -> {
                    ReaderPrefs.save();
                    a.startAuto();
                }).show();
    }

    // ------------------------------------------------------------------ tap zones

    void zones() {
        final int[] z = ReaderPrefs.zones.clone();
        LinearLayout col = column();
        note(col, R.string.rd_zones_note);
        final TextView[] cells = new TextView[9];
        final Runnable show = () -> {
            for (int i = 0; i < 9; i++) {
                cells[i].setText(tools.actionName(z[i]));
                cells[i].setSelected(z[i] == ReaderPrefs.Z_MENU);
            }
        };
        for (int r = 0; r < 3; r++) {
            LinearLayout row = new LinearLayout(a);
            for (int c = 0; c < 3; c++) {
                final int i = r * 3 + c;
                cells[i] = chip("");
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, App.dp(72), 1);
                lp.setMargins(App.dp(3), App.dp(3), App.dp(3), App.dp(3));
                row.addView(cells[i], lp);
                cells[i].setOnClickListener(v -> tools.pickAction(R.string.rd_tap_zones, z[i], act -> {
                    z[i] = act;
                    show.run();
                }));
            }
            col.addView(row);
        }
        LinearLayout presets = new LinearLayout(a);
        TextView def = chip(a.getString(R.string.rd_zone_default)), all = chip(a.getString(R.string.rd_zone_all_next));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, App.dp(36), 1);
        lp.setMargins(App.dp(3), App.dp(10), App.dp(3), 0);
        presets.addView(def, lp);
        presets.addView(all, lp);
        col.addView(presets);
        def.setOnClickListener(v -> {
            System.arraycopy(ReaderPrefs.ZONES_DEFAULT, 0, z, 0, 9);
            show.run();
        });
        all.setOnClickListener(v -> {
            System.arraycopy(ReaderPrefs.ZONES_ALL_NEXT, 0, z, 0, 9);
            show.run();
        });
        show.run();
        dlg(R.string.rd_tap_zones).setView(scroll(col)).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    boolean menu = false;
                    for (int v : z) menu |= v == ReaderPrefs.Z_MENU;
                    if (!menu) {
                        // without a menu zone there would be no way back to these settings
                        z[4] = ReaderPrefs.Z_MENU;
                        App.toast(a.getString(R.string.rd_zone_need_menu));
                    }
                    ReaderPrefs.zones = z;
                    a.changed();
                }).show();
    }

    // ------------------------------------------------------------------ fonts

    void fonts() {
        final List<String> keys = new ArrayList<>(), names = new ArrayList<>();
        keys.add("");
        names.add(a.getString(R.string.rd_font_system));
        keys.add("serif");
        names.add(a.getString(R.string.rd_font_serif));
        keys.add("mono");
        names.add(a.getString(R.string.rd_font_mono));
        File[] files = ReaderPrefs.fontDir().listFiles();
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) {
                keys.add(f.getPath());
                names.add(f.getName().replaceAll("\\.[A-Za-z0-9]{2,5}$", ""));
            }
        }
        final AlertDialog d = dlg(R.string.font)
                .setSingleChoiceItems(names.toArray(new String[0]), Math.max(0, keys.indexOf(ReaderPrefs.font)), (x, w) -> {
                    ReaderPrefs.font = keys.get(w);
                    a.changed();
                    x.dismiss();
                })
                .setNeutralButton(R.string.rd_font_import, (x, w) -> a.pick(ReaderActivity.REQ_FONT))
                .setNegativeButton(R.string.cancel, null).create();
        d.getListView().setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 3) return false;
            new File(keys.get(pos)).delete();
            if (ReaderPrefs.font.equals(keys.get(pos))) {
                ReaderPrefs.font = "";
                a.changed();
            }
            d.dismiss();
            fonts();
            return true;
        });
        d.show();
        if (keys.size() > 3) App.toast(a.getString(R.string.rd_hold_to_delete));
    }

    static String nameOf(ReaderActivity a, Uri uri, String fallback) {
        try (android.database.Cursor c = a.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.getString(i) != null) return c.getString(i).replaceAll("[\\\\/:*?\"<>|]", "_");
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    static void copy(ReaderActivity a, Uri from, File to) throws java.io.IOException {
        try (InputStream in = a.getContentResolver().openInputStream(from); OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new java.io.IOException("unreadable");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
    }

    void importFont(final Uri uri) {
        App.IO.execute(() -> {
            final File f = new File(ReaderPrefs.fontDir(), nameOf(a, uri, "font-" + System.currentTimeMillis() + ".ttf"));
            boolean ok = false;
            try {
                copy(a, uri, f);
                ok = ReaderPrefs.fromFile(f) != null;
            } catch (Exception ignored) {
            }
            if (!ok) f.delete();
            final boolean good = ok;
            App.MAIN.post(() -> {
                if (a.isDestroyed()) return;
                if (!good) {
                    App.toast(a.getString(R.string.rd_font_bad));
                    return;
                }
                ReaderPrefs.font = f.getPath();
                a.changed();
            });
        });
    }

    // ------------------------------------------------------------------ themes

    void importImage(final Uri uri) {
        final Text target = pendingImage;
        pendingImage = null;
        if (target == null) return;
        App.IO.execute(() -> {
            File dir = new File(a.getFilesDir(), "themes");
            dir.mkdirs();
            final File f = new File(dir, System.currentTimeMillis() + ".jpg");
            boolean ok = false;
            try {
                // stored no larger than a screen needs
                File tmp = new File(dir, "import.tmp");
                copy(a, uri, tmp);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(tmp.getPath(), o);
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1600) s *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = s;
                Bitmap b = BitmapFactory.decodeFile(tmp.getPath(), o);
                tmp.delete();
                if (b != null) {
                    try (OutputStream out = new FileOutputStream(f)) {
                        ok = b.compress(Bitmap.CompressFormat.JPEG, 90, out);
                    }
                }
            } catch (Throwable ignored) {
            }
            final boolean good = ok;
            App.MAIN.post(() -> {
                if (good) target.set(f.getPath());
                else App.toast(a.getString(R.string.rd_image_bad));
            });
        });
    }

    /** Creates a theme (existing == null) or edits one of the user's. */
    void editTheme(ReaderPrefs.Theme existing) {
        final boolean isNew = existing == null;
        final ReaderPrefs.Theme t = isNew ? new ReaderPrefs.Theme() : existing.copy();
        if (isNew) {
            t.key = "c" + System.currentTimeMillis();
            t.bg = ReaderPrefs.bg();
            t.fg = ReaderPrefs.fg();
        }
        final List<Backgrounds.Paper> papers = Backgrounds.papers();
        LinearLayout col = column();
        final EditText name = new EditText(a);
        name.setHint(R.string.rd_theme_name);
        name.setSingleLine(true);
        name.setText(t.name);
        col.addView(name);
        final TextView preview = new TextView(a);
        preview.setText(R.string.rd_theme_sample);
        preview.setTextSize(16);
        preview.setPadding(App.dp(16), App.dp(22), App.dp(16), App.dp(22));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, App.dp(8), 0, App.dp(8));
        col.addView(preview, plp);
        final TextView bgBtn = chip(a.getString(R.string.rd_bg_color)), fgBtn = chip(a.getString(R.string.rd_text_color));
        final TextView texBtn = chip(""), picBtn = chip("");
        LinearLayout r1 = new LinearLayout(a), r2 = new LinearLayout(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, App.dp(38), 1);
        lp.setMargins(App.dp(3), App.dp(4), App.dp(3), App.dp(4));
        r1.addView(bgBtn, lp);
        r1.addView(fgBtn, lp);
        r2.addView(texBtn, lp);
        r2.addView(picBtn, lp);
        col.addView(r1);
        col.addView(r2);
        note(col, R.string.rd_theme_note);
        final Runnable show = () -> {
            preview.setTextColor(t.fg);
            final Backgrounds.Paper paper = Backgrounds.paper(t.paper);
            texBtn.setText(a.getString(R.string.rd_texture) + " · " + (paper == null ? a.getString(R.string.rd_tex_none) : paper.name()));
            picBtn.setText(t.image.isEmpty() ? R.string.rd_picture : R.string.rd_picture_remove);
            Bitmap b = null;
            try {
                if (!t.image.isEmpty()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 4;
                    b = BitmapFactory.decodeFile(t.image, o);
                } else if (paper != null) b = Backgrounds.preview(paper, t.bg, 360, 540);
            } catch (Throwable ignored) {
            }
            if (b != null) {
                // no size of its own: a background's size is the least its view may be, and a tile is large
                BitmapDrawable d = !t.image.isEmpty() ? new BitmapDrawable(a.getResources(), b)
                        : new BitmapDrawable(a.getResources(), b) {
                            @Override
                            public int getIntrinsicWidth() {
                                return -1;
                            }

                            @Override
                            public int getIntrinsicHeight() {
                                return -1;
                            }
                        };
                if (Backgrounds.tiled(t)) {
                    // the tile is made for one density; saying so lets the drawable scale it as the page does
                    b.setDensity(Math.round(Backgrounds.TILE_DENSITY * 160));
                    d.setTargetDensity(a.getResources().getDisplayMetrics());
                    d.setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
                }
                preview.setBackground(d);
            } else preview.setBackgroundColor(t.bg);
        };
        bgBtn.setOnClickListener(v -> color(R.string.rd_bg_color, t.bg, c -> {
            t.bg = c;
            show.run();
        }));
        fgBtn.setOnClickListener(v -> color(R.string.rd_text_color, t.fg, c -> {
            t.fg = c;
            show.run();
        }));
        texBtn.setOnClickListener(v -> {
            // none, then every paper of the folder in turn
            int at = papers.indexOf(Backgrounds.paper(t.paper)) + 1;
            t.paper = at >= papers.size() ? "" : papers.get(at).file;
            show.run();
        });
        picBtn.setOnClickListener(v -> {
            if (!t.image.isEmpty()) {
                t.image = "";
                show.run();
                return;
            }
            pendingImage = path -> {
                t.image = path;
                show.run();
            };
            a.pick(ReaderActivity.REQ_IMAGE);
        });
        show.run();
        AlertDialog.Builder b = dlg(isNew ? R.string.rd_new_theme : R.string.rd_edit_theme).setView(scroll(col))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    t.name = name.getText().toString().trim();
                    ReaderPrefs.put(t);
                    ReaderPrefs.select(t);
                    Backgrounds.forget();
                    a.themesChanged();
                });
        if (!isNew) {
            b.setNeutralButton(R.string.rd_delete, (d, w) -> {
                ReaderPrefs.remove(t);
                Backgrounds.forget();
                a.themesChanged();
            });
        }
        b.show();
    }

    private void color(int title, int initial, final Set done) {
        final float[] hsv = new float[3];
        Color.colorToHSV(initial, hsv);
        LinearLayout col = column();
        final View swatch = new View(a);
        col.addView(swatch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, App.dp(56)));
        final EditText hex = new EditText(a);
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final SeekBar[] bars = new SeekBar[3];
        final boolean[] busy = {false};
        final Runnable show = () -> {
            int c = Color.HSVToColor(hsv);
            GradientDrawable g = new GradientDrawable();
            g.setColor(c);
            g.setCornerRadius(App.dp(8));
            g.setStroke(App.dp(1), 0x55FFFFFF);
            swatch.setBackground(g);
            busy[0] = true;
            if (!hex.hasFocus()) hex.setText(String.format("#%06X", c & 0xFFFFFF));
            bars[0].setProgress(Math.round(hsv[0]));
            bars[1].setProgress(Math.round(hsv[1] * 100));
            bars[2].setProgress(Math.round(hsv[2] * 100));
            busy[0] = false;
        };
        int[] labels = {R.string.rd_hue, R.string.rd_saturation, R.string.rd_lightness};
        for (int i = 0; i < 3; i++) {
            final int which = i;
            TextView l = new TextView(a);
            l.setText(labels[i]);
            l.setTextSize(12.5f);
            l.setPadding(0, App.dp(10), 0, 0);
            col.addView(l);
            bars[i] = new SeekBar(a);
            bars[i].setMax(i == 0 ? 360 : 100);
            col.addView(bars[i]);
            bars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (!fromUser || busy[0]) return;
                    hsv[which] = which == 0 ? progress : progress / 100f;
                    hex.clearFocus();
                    show.run();
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
        }
        col.addView(hex);
        hex.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                if (busy[0]) return;
                String v = e.toString().trim();
                if (!v.matches("#?[0-9a-fA-F]{6}")) return;
                Color.colorToHSV(Color.parseColor(v.startsWith("#") ? v : "#" + v), hsv);
                show.run();
            }
        });
        show.run();
        dlg(title).setView(scroll(col)).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> done.set(Color.HSVToColor(hsv))).show();
    }

    // ------------------------------------------------------------------ page layout

    void layout() {
        LinearLayout col = column();
        final Runnable ch = a::changed;
        stepper(col, R.string.rd_margin_left, 0, 120, 2, () -> ReaderPrefs.marginL, v -> {
            ReaderPrefs.marginL = v;
            ch.run();
        }, "", null);
        stepper(col, R.string.rd_margin_right, 0, 120, 2, () -> ReaderPrefs.marginR, v -> {
            ReaderPrefs.marginR = v;
            ch.run();
        }, "", null);
        stepper(col, R.string.rd_margin_top, 0, 160, 2, () -> ReaderPrefs.marginT, v -> {
            ReaderPrefs.marginT = v;
            ch.run();
        }, "", null);
        stepper(col, R.string.rd_margin_bottom, 0, 160, 2, () -> ReaderPrefs.marginB, v -> {
            ReaderPrefs.marginB = v;
            ch.run();
        }, "", null);
        stepper(col, R.string.line_spacing, 100, 300, 5, () -> ReaderPrefs.linePct, v -> {
            ReaderPrefs.linePct = v;
            ch.run();
        }, "%", null);
        stepper(col, R.string.rd_para_spacing, 0, 400, 10, () -> ReaderPrefs.paraPct, v -> {
            ReaderPrefs.paraPct = v;
            ch.run();
        }, "%", null);
        stepper(col, R.string.rd_letter_spacing, 0, 30, 1, () -> ReaderPrefs.letterPct, v -> {
            ReaderPrefs.letterPct = v;
            ch.run();
        }, "", null);
        toggle(col, R.string.rd_indent, ReaderPrefs.indent, v -> {
            ReaderPrefs.indent = v == 1;
            ch.run();
        });
        toggle(col, R.string.rd_justify, ReaderPrefs.justify, v -> {
            ReaderPrefs.justify = v == 1;
            ch.run();
        });
        toggle(col, R.string.rd_bold, ReaderPrefs.bold, v -> {
            ReaderPrefs.bold = v == 1;
            ch.run();
        });
        toggle(col, R.string.rd_header, ReaderPrefs.header, v -> {
            ReaderPrefs.header = v == 1;
            ch.run();
        });
        toggle(col, R.string.rd_footer, ReaderPrefs.footer, v -> {
            ReaderPrefs.footer = v == 1;
            ch.run();
        });
        AlertDialog d = dlg(R.string.page_layout).setView(scroll(col)).setPositiveButton(R.string.ok, null).create();
        // the page behind stays visible so every step can be judged
        d.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        d.getWindow().setGravity(Gravity.BOTTOM);
        d.show();
        d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(App.dp(380),
                a.getResources().getDisplayMetrics().heightPixels * 55 / 100));
    }

    // ------------------------------------------------------------------ screen, breaks, eyes, orientation

    private String screenLabel(int min) {
        return min < 0 ? a.getString(R.string.rd_screen_always) : min == 0 ? a.getString(R.string.system) : minutes(min);
    }

    void screenOn() {
        final int[] values = {0, 2, 5, 10, 30, -1};
        String[] labels = new String[values.length + 1];
        int checked = values.length;
        for (int i = 0; i < values.length; i++) {
            labels[i] = screenLabel(values[i]);
            if (values[i] == ReaderPrefs.screenMin) checked = i;
        }
        labels[values.length] = a.getString(R.string.rd_custom)
                + (checked == values.length ? " · " + minutes(ReaderPrefs.screenMin) : "");
        dlg(R.string.rd_screen_on).setSingleChoiceItems(labels, checked, (d, w) -> {
            d.dismiss();
            if (w < values.length) {
                ReaderPrefs.screenMin = values[w];
                a.changed();
            } else {
                number(R.string.rd_screen_custom, Math.max(1, ReaderPrefs.screenMin), 1, 600, v -> {
                    ReaderPrefs.screenMin = v;
                    a.changed();
                });
            }
        }).setNegativeButton(R.string.cancel, null).show();
    }

    void rest() {
        LinearLayout col = column();
        stepper(col, R.string.rd_rest_every, 0, 240, 5, () -> ReaderPrefs.restEvery, v -> ReaderPrefs.restEvery = v,
                " min", onOff(false));
        stepper(col, R.string.rd_rest_for, 1, 60, 1, () -> ReaderPrefs.restFor, v -> ReaderPrefs.restFor = v, " min", null);
        note(col, R.string.rd_rest_note);
        dlg(R.string.rd_rest).setView(col).setPositiveButton(R.string.ok, (d, w) -> a.changed()).show();
    }

    void eye() {
        LinearLayout col = column();
        SeekBar s = new SeekBar(a);
        s.setMax(80);
        s.setProgress(ReaderPrefs.eye);
        col.addView(s);
        note(col, R.string.rd_eye_note);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                ReaderPrefs.eye = progress;
                a.applyWindowPrefs();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                ReaderPrefs.save();
            }
        });
        AlertDialog d = dlg(R.string.rd_eye).setView(col).setPositiveButton(R.string.ok, null).create();
        d.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        d.show();
    }

    void orientation() {
        String[] labels = {a.getString(R.string.rd_orient_auto), a.getString(R.string.rd_orient_portrait), a.getString(R.string.rd_orient_landscape)};
        dlg(R.string.rd_orientation).setSingleChoiceItems(labels, ReaderPrefs.orient, (d, w) -> {
            ReaderPrefs.orient = w;
            d.dismiss();
            a.changed();
        }).setNegativeButton(R.string.cancel, null).show();
    }

    void sleepTimer(final Speaker sp) {
        final int[] values = {0, 15, 30, 60, 90};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i] == 0 ? onOff(false) : minutes(values[i]);
        dlg(R.string.rd_sleep_timer).setItems(labels, (d, w) -> {
            sp.sleepIn(values[w]);
            a.bindSpeech();
        }).show();
    }

    // ------------------------------------------------------------------ replacements

    String shortened(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }

    void replacements() {
        // a screen of its own: the list can be long
        ReplaceActivity.open(a, a.bookId);
    }

    /** @param preset the text to find when a new rule is made from a selection */
    void editRule(final TextRules.Rule existing, String preset) {
        ReplaceActivity.edit(a, a.bookId, existing, preset, () -> {
            ReplaceActivity.changed = false;
            a.reprocess();
        });
    }

    // ------------------------------------------------------------------ connection test

    /** Well-known addresses that tell "the network is down" from "the site is down" (and whether a proxy is on). */
    private static final String[][] NET_TARGETS = {{"Bing", "https://www.bing.com/"}, {"Google", "https://www.google.com/"}};

    /**
     * One request to each of two search engines, made when the reader asks for it and again on "Test again".
     * Nothing goes to the site of the book.
     */
    void netTest() {
        LinearLayout col = column();
        final TextView[] out = new TextView[NET_TARGETS.length];
        for (int i = 0; i < NET_TARGETS.length; i++) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(a);
            name.setText(NET_TARGETS[i][0]);
            name.setTextSize(15);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            row.addView(name, new LinearLayout.LayoutParams(App.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));
            out[i] = new TextView(a);
            out[i].setTextSize(14);
            row.addView(out[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setMinimumHeight(App.dp(48));
        }
        note(col, R.string.rd_net_note);
        final AlertDialog d = dlg(R.string.rd_net_test).setView(col).setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.rd_net_again, null).create();
        final int[] round = {0};
        final Runnable run = () -> {
            final int mine = ++round[0];
            for (int i = 0; i < NET_TARGETS.length; i++) {
                final int k = i;
                out[k].setTextColor(0xFF8C9792);
                out[k].setText(R.string.rd_net_testing);
                App.IO.execute(() -> {
                    final long t0 = android.os.SystemClock.elapsedRealtime();
                    int code = 0;
                    String error = null;
                    java.net.HttpURLConnection c = null;
                    try {
                        c = (java.net.HttpURLConnection) new java.net.URL(NET_TARGETS[k][1]).openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(8000);
                        c.setRequestProperty("User-Agent", App.UA);
                        code = c.getResponseCode();
                    } catch (Throwable t) {
                        error = a.explain(t)[0];
                    } finally {
                        if (c != null) c.disconnect();
                    }
                    final long ms = android.os.SystemClock.elapsedRealtime() - t0;
                    final int status = code;
                    final String err = error;
                    App.MAIN.post(() -> {
                        // an answer to an earlier round, or to a window that is gone
                        if (mine != round[0] || !d.isShowing()) return;
                        out[k].setTextColor(err == null ? 0xFF2E9E5B : 0xFFD9534F);
                        out[k].setText(err == null ? a.getString(R.string.rd_net_ok, ms, status) : a.getString(R.string.rd_net_fail, ms / 1000f, err));
                    });
                });
            }
        };
        d.show();
        // the button must not close the window
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> run.run());
        run.run();
    }
}
