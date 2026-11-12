package com.rc.novelreader.reader;

import android.content.SharedPreferences;

import com.rc.novelreader.App;

/** Reader appearance and behaviour, persisted in the default shared preferences. */
public final class ReaderPrefs {
    private ReaderPrefs() {}

    /** {background, text} per theme: paper, white, green, grey-dark, black. */
    public static final int[][] THEMES = {
            {0xFFF3E9D2, 0xFF3B3226},
            {0xFFFFFFFF, 0xFF222222},
            {0xFFCFE6D0, 0xFF22352A},
            {0xFF2A2C2B, 0xFFA7ADA9},
            {0xFF000000, 0xFF7C827E},
    };
    public static final int NIGHT_THEME = 3;

    public static int textSp = 19;
    /** Line height multiplier x100. */
    public static int linePct = 155;
    public static int marginDp = 22;
    public static int theme = 0;
    public static int dayTheme = 0;
    public static int mode = PageView.COVER;
    /** 0..100, or -1 to follow the system. */
    public static int brightness = -1;
    public static boolean keepOn = true;
    public static boolean volumeKeys = true;

    public static void load() {
        SharedPreferences p = App.prefs();
        textSp = p.getInt("r_text", 19);
        linePct = p.getInt("r_line", 155);
        marginDp = p.getInt("r_margin", 22);
        theme = Math.max(0, Math.min(THEMES.length - 1, p.getInt("r_theme", 0)));
        dayTheme = Math.max(0, Math.min(2, p.getInt("r_day_theme", 0)));
        mode = p.getInt("r_mode", PageView.COVER);
        brightness = p.getInt("r_bright", -1);
        keepOn = p.getBoolean("r_keep_on", true);
        volumeKeys = p.getBoolean("r_vol", true);
    }

    public static void save() {
        App.prefs().edit().putInt("r_text", textSp).putInt("r_line", linePct).putInt("r_margin", marginDp)
                .putInt("r_theme", theme).putInt("r_day_theme", dayTheme).putInt("r_mode", mode)
                .putInt("r_bright", brightness).putBoolean("r_keep_on", keepOn).putBoolean("r_vol", volumeKeys).apply();
    }

    public static boolean isNight() {
        return theme >= NIGHT_THEME;
    }

    public static int bg() {
        return THEMES[theme][0];
    }

    public static int fg() {
        return THEMES[theme][1];
    }
}
