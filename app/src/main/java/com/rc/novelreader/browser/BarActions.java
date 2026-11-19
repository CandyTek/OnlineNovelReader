package com.rc.novelreader.browser;

import android.app.Activity;
import android.app.AlertDialog;

import com.rc.novelreader.App;
import com.rc.novelreader.R;

/**
 * What a long press on the five buttons of the bottom bar and on the reload button does. Each button holds one
 * action of the user's choice (pref "long_" + button); the ids are what is stored, so they must not change.
 * {@link BrowserActivity#runAction} carries them out.
 */
public final class BarActions {
    static final String NONE = "none";

    private static final String[] BUTTONS = {"back", "forward", "home", "tabs", "menu", "reload"};
    private static final int[] BUTTON_NAMES = {R.string.back, R.string.forward, R.string.start_page, R.string.tabs,
            R.string.menu, R.string.refresh};
    private static final String[] DEFAULTS = {"close_tab", "history", "shelf", "new_tab", NONE, NONE};

    /** Tabs, moving about, the page, lists and switches, then what belongs to reading novels. */
    private static final String[] IDS = {NONE,
            "close_tab", "new_tab", "incognito_tab", "reopen_closed", "tab_list",
            "back", "forward", "back_list", "home", "reload", "page_top", "page_bottom",
            "history", "bookmarks", "downloads", "offline_pages",
            "bookmark", "find", "share", "copy_link",
            "fullscreen", "desktop", "night", "no_images",
            "settings", "clear_data", "exit",
            "shelf", "reading_mode", "detect_novel", "continue_reading", "scan_qr"};
    private static final int[] NAMES = {R.string.none,
            R.string.act_close_tab, R.string.new_tab, R.string.incognito_tab, R.string.reopen_closed, R.string.act_tab_list,
            R.string.back, R.string.forward, R.string.act_back_list, R.string.start_page, R.string.refresh,
            R.string.act_page_top, R.string.act_page_bottom,
            R.string.history, R.string.bookmarks, R.string.downloads, R.string.offline_pages,
            R.string.add_bookmark, R.string.find_in_page, R.string.share, R.string.copy_link,
            R.string.fullscreen, R.string.desktop_mode, R.string.night_mode, R.string.no_images,
            R.string.settings, R.string.clear_data, R.string.exit,
            R.string.bookshelf, R.string.reading_mode, R.string.detect_novel, R.string.act_continue, R.string.scan_qr};

    private BarActions() {
    }

    /** The action chosen for a button ("back", "forward", "home", "tabs", "menu", "reload"). */
    static String of(String button) {
        String def = NONE;
        for (int i = 0; i < BUTTONS.length; i++) if (BUTTONS[i].equals(button)) def = DEFAULTS[i];
        String id = App.prefs().getString("long_" + button, def);
        // an id from a newer version, or a damaged value: the button's own default
        return index(id) < 0 ? def : id;
    }

    private static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    /** The settings dialog: the six buttons with what each does now; a tap on one offers every action. */
    public static void configure(final Activity a) {
        CharSequence[] rows = new CharSequence[BUTTONS.length];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = a.getString(BUTTON_NAMES[i]) + "  →  " + a.getString(NAMES[index(of(BUTTONS[i]))]);
        }
        new AlertDialog.Builder(a).setTitle(R.string.pref_long_press)
                .setItems(rows, (d, which) -> choose(a, which))
                .setNeutralButton(R.string.act_defaults, (d, w) -> {
                    android.content.SharedPreferences.Editor e = App.prefs().edit();
                    for (String b : BUTTONS) e.remove("long_" + b);
                    e.apply();
                    configure(a);
                })
                .setPositiveButton(R.string.ok, null).show();
    }

    private static void choose(final Activity a, final int button) {
        CharSequence[] names = new CharSequence[IDS.length];
        for (int i = 0; i < names.length; i++) names[i] = a.getString(NAMES[i]);
        new AlertDialog.Builder(a).setTitle(BUTTON_NAMES[button])
                .setSingleChoiceItems(names, index(of(BUTTONS[button])), (d, which) -> {
                    App.prefs().edit().putString("long_" + BUTTONS[button], IDS[which]).apply();
                    d.dismiss();
                    configure(a);
                })
                .setNegativeButton(R.string.cancel, (d, w) -> configure(a)).show();
    }
}
