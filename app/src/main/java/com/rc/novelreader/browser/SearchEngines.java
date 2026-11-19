package com.rc.novelreader.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.util.Urls;

/**
 * The search engines the address bar can use (pref "search_engine", the same list as in the settings) and the
 * drop-down under the engine's icon that switches between them.
 *
 * The icon is the site icon the browser already has from a visit to the engine, else a letter on a colour:
 * nothing is fetched for it.
 */
final class SearchEngines {
    private SearchEngines() {
    }

    private static final String[] IDS = {"bing", "baidu", "google", "ddg", "sogou", "custom"};
    private static final String[] URLS = {"https://www.bing.com/search?q=%s", "https://www.baidu.com/s?wd=%s",
            "https://www.google.com/search?q=%s", "https://duckduckgo.com/?q=%s", "https://www.sogou.com/web?query=%s"};
    private static final String[] LETTERS = {"B", "百", "G", "D", "搜"};
    private static final int[] COLORS = {0xFF0E8A8A, 0xFF3A5BD9, 0xFF4A8AF4, 0xFFDE5833, 0xFFF0782A, 0xFF6B5B95};

    static String current() {
        String id = App.prefs().getString("search_engine", IDS[0]);
        return index(id) < 0 ? IDS[0] : id;
    }

    private static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    private static String custom() {
        String c = App.prefs().getString("search_custom", "").trim();
        return c.contains("%s") && c.startsWith("http") ? c : null;
    }

    /** The address a search goes to, with %s where the words belong. */
    static String searchUrl() {
        int i = index(current());
        if (i < URLS.length) return URLS[i];
        String c = custom();
        return c == null ? URLS[0] : c;
    }

    private static String name(Context c, int i) {
        return c.getResources().getStringArray(R.array.search_engine_names)[i];
    }

    static String name(Context c) {
        return name(c, index(current()));
    }

    static Bitmap icon(String id, int size) {
        int i = Math.max(0, index(id));
        String url = i < URLS.length ? URLS[i] : custom();
        Bitmap have = url == null ? null : Favicons.get(url);
        if (have != null) return have;
        String letter;
        if (i < LETTERS.length) letter = LETTERS[i];
        else {
            String host = url == null ? "" : Urls.host(url);
            if (host.startsWith("www.")) host = host.substring(4);
            letter = host.isEmpty() ? "?" : host.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(COLORS[i]);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(size * 0.56f);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics m = p.getFontMetrics();
        c.drawText(letter, size / 2f, size / 2f - (m.ascent + m.descent) / 2, p);
        return b;
    }

    /** The drop-down under the icon: every engine with its icon, the one in use ticked. */
    static void pick(final Activity a, View anchor, final Runnable changed) {
        final ListPopupWindow pop = new ListPopupWindow(a);
        final String now = current();
        pop.setAnchorView(anchor);
        pop.setWidth(App.dp(216));
        pop.setModal(true);
        pop.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
        pop.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return IDS.length;
            }

            @Override
            public Object getItem(int position) {
                return IDS[position];
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(App.dp(16), 0, App.dp(14), 0);
                row.setMinimumHeight(App.dp(48));
                ImageView icon = new ImageView(a);
                icon.setImageBitmap(icon(IDS[position], App.dp(24)));
                row.addView(icon, new LinearLayout.LayoutParams(App.dp(24), App.dp(24)));
                TextView name = new TextView(a);
                name.setText(name(a, position));
                name.setTextSize(15);
                name.setTextColor(a.getColor(R.color.text_primary));
                name.setPadding(App.dp(14), 0, 0, 0);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                TextView tick = new TextView(a);
                tick.setText(IDS[position].equals(now) ? "✓" : "");
                tick.setTextSize(16);
                tick.setTextColor(a.getColor(R.color.brand));
                row.addView(tick);
                return row;
            }
        });
        pop.setOnItemClickListener((p, v, pos, id) -> {
            pop.dismiss();
            if (pos == IDS.length - 1 && custom() == null) {
                askCustom(a, changed);
                return;
            }
            App.prefs().edit().putString("search_engine", IDS[pos]).apply();
            changed.run();
        });
        pop.show();
    }

    /** "Custom" without an address yet: ask for it here instead of sending the user to the settings. */
    private static void askCustom(final Activity a, final Runnable changed) {
        final EditText in = new EditText(a);
        in.setSingleLine(true);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        in.setHint("https://example.com/search?q=%s");
        in.setText(App.prefs().getString("search_custom", ""));
        LinearLayout box = new LinearLayout(a);
        box.setPadding(App.dp(20), App.dp(8), App.dp(20), 0);
        box.addView(in, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(a).setTitle(R.string.pref_search_custom).setMessage(R.string.pref_search_custom_sum).setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String c = in.getText().toString().trim();
                    if (!c.contains("%s") || !c.startsWith("http")) {
                        App.toast(a.getString(R.string.search_custom_bad));
                        return;
                    }
                    App.prefs().edit().putString("search_custom", c).putString("search_engine", "custom").apply();
                    changed.run();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }
}
