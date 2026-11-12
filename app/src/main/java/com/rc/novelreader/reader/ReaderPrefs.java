package com.rc.novelreader.reader;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;

import com.rc.novelreader.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Reader appearance and behaviour, persisted in the default shared preferences. */
public final class ReaderPrefs {
    private ReaderPrefs() {}

    /** A page look: colours, plus optionally a generated paper texture or the user's own picture. */
    public static final class Theme {
        /** "b0".."b8" for the built-in ones, "c&lt;time&gt;" for the user's. */
        public String key = "";
        public String name = "";
        public int bg, fg;
        /** One of the {@link Backgrounds} kinds, 0 for a flat colour. */
        public int texture;
        /** Path of a background picture, "" for none. Wins over the texture. */
        public String image = "";

        public boolean custom() {
            return key.startsWith("c");
        }

        public boolean dark() {
            return light(fg) > light(bg);
        }

        private static int light(int c) {
            return Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c);
        }

        Theme copy() {
            Theme t = new Theme();
            t.key = key;
            t.name = name;
            t.bg = bg;
            t.fg = fg;
            t.texture = texture;
            t.image = image;
            return t;
        }
    }

    /** {background, text, texture}: paper, white, green, dark grey, black, then four paper textures. */
    private static final int[][] BUILT_IN = {
            {0xFFF3E9D2, 0xFF3B3226, 0},
            {0xFFFFFFFF, 0xFF222222, 0},
            {0xFFCFE6D0, 0xFF22352A, 0},
            {0xFF2A2C2B, 0xFFA7ADA9, 0},
            {0xFF000000, 0xFF7C827E, 0},
            {0xFFE9D8AE, 0xFF3A2D1C, Backgrounds.PARCHMENT},
            {0xFFDCC48F, 0xFF33261A, Backgrounds.AGED},
            {0xFFF1EBDD, 0xFF2E2A24, Backgrounds.RICE},
            {0xFFC9A873, 0xFF2B1F12, Backgrounds.KRAFT},
    };
    private static final String NIGHT_KEY = "b3";

    // tap zone actions; the screen is a 3x3 grid, row by row
    public static final int Z_MENU = 0, Z_PREV = 1, Z_NEXT = 2, Z_NONE = 3;
    public static final int[] ZONES_DEFAULT = {Z_PREV, Z_PREV, Z_NEXT, Z_PREV, Z_MENU, Z_NEXT, Z_PREV, Z_NEXT, Z_NEXT};
    public static final int[] ZONES_ALL_NEXT = {Z_NEXT, Z_NEXT, Z_NEXT, Z_NEXT, Z_MENU, Z_NEXT, Z_NEXT, Z_NEXT, Z_NEXT};

    public static int textSp = 19;
    /** Line height multiplier x100. */
    public static int linePct = 155;
    /** Space between paragraphs, percent of the default. */
    public static int paraPct = 100;
    /** Letter spacing in hundredths of an em. */
    public static int letterPct = 0;
    /** Page margins in dp; top and bottom are added to the room the header and footer take. */
    public static int marginL = 22, marginR = 22, marginT = 0, marginB = 0;
    public static boolean indent = true, bold = false, header = true, footer = true;
    /** "" system, "serif", "mono", or the path of an imported font file. */
    public static String font = "";
    public static String themeKey = "b0", dayKey = "b0";
    public static int mode = PageView.COVER;
    /** 0..100, or -1 to follow the system. */
    public static int brightness = -1;
    /** Minutes the screen stays on after the last touch: -1 always, 0 leaves it to the system. */
    public static int screenMin = 10;
    public static boolean volumeKeys = true;
    public static int[] zones = ZONES_DEFAULT.clone();
    /** Forced break: after restEvery minutes of reading the reader locks for restFor minutes. 0 = off. */
    public static int restEvery = 0, restFor = 5;
    /** Hide author notes and promotion at the start and end of chapters. */
    public static boolean filler = true;
    /** 0 follows the sensor, 1 portrait, 2 landscape. */
    public static int orient = 0;
    /** Strength of the warm overlay, 0..80. */
    public static int eye = 0;
    /** Seconds per page for automatic page turning. */
    public static int autoSec = 15;
    /** Speech rate and pitch x100. */
    public static int ttsRate = 100, ttsPitch = 100;

    private static List<Theme> custom = new ArrayList<>();
    private static Typeface face;
    private static String faceKey;

    public static void load() {
        SharedPreferences p = App.prefs();
        textSp = p.getInt("r_text", 19);
        linePct = p.getInt("r_line", 155);
        paraPct = p.getInt("r_para", 100);
        letterPct = p.getInt("r_letter", 0);
        int old = p.getInt("r_margin", 22);
        marginL = p.getInt("r_ml", old);
        marginR = p.getInt("r_mr", old);
        marginT = p.getInt("r_mt", 0);
        marginB = p.getInt("r_mb", 0);
        indent = p.getBoolean("r_indent", true);
        bold = p.getBoolean("r_bold", false);
        header = p.getBoolean("r_header", true);
        footer = p.getBoolean("r_footer", true);
        font = p.getString("r_font", "");
        themeKey = p.getString("r_theme_key", "b" + p.getInt("r_theme", 0));
        dayKey = p.getString("r_day_key", "b" + p.getInt("r_day_theme", 0));
        mode = p.getInt("r_mode", PageView.COVER);
        brightness = p.getInt("r_bright", -1);
        // the old switch was "always on" / "system"
        screenMin = p.getInt("r_screen_min", p.getBoolean("r_keep_on", true) ? 10 : 0);
        volumeKeys = p.getBoolean("r_vol", true);
        zones = parseZones(p.getString("r_zones", ""));
        restEvery = p.getInt("r_rest_every", 0);
        restFor = Math.max(1, p.getInt("r_rest_for", 5));
        filler = p.getBoolean("r_filler", true);
        orient = p.getInt("r_orient", 0);
        eye = p.getInt("r_eye", 0);
        autoSec = Math.max(2, p.getInt("r_auto_sec", 15));
        ttsRate = p.getInt("r_tts_rate", 100);
        ttsPitch = p.getInt("r_tts_pitch", 100);
        custom = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p.getString("r_themes", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Theme t = new Theme();
                t.key = o.getString("k");
                t.name = o.optString("n");
                t.bg = o.getInt("b");
                t.fg = o.getInt("f");
                t.texture = o.optInt("x");
                t.image = o.optString("i");
                custom.add(t);
            }
        } catch (Exception ignored) {
        }
        if (find(themeKey) == null) themeKey = "b0";
        if (find(dayKey) == null || find(dayKey).dark()) dayKey = "b0";
    }

    public static void save() {
        StringBuilder z = new StringBuilder();
        for (int v : zones) z.append(v);
        JSONArray arr = new JSONArray();
        try {
            for (Theme t : custom) {
                arr.put(new JSONObject().put("k", t.key).put("n", t.name).put("b", t.bg).put("f", t.fg)
                        .put("x", t.texture).put("i", t.image));
            }
        } catch (Exception ignored) {
        }
        App.prefs().edit().putInt("r_text", textSp).putInt("r_line", linePct).putInt("r_para", paraPct)
                .putInt("r_letter", letterPct).putInt("r_ml", marginL).putInt("r_mr", marginR).putInt("r_mt", marginT)
                .putInt("r_mb", marginB).putBoolean("r_indent", indent).putBoolean("r_bold", bold)
                .putBoolean("r_header", header).putBoolean("r_footer", footer).putString("r_font", font)
                .putString("r_theme_key", themeKey).putString("r_day_key", dayKey).putInt("r_mode", mode)
                .putInt("r_bright", brightness).putInt("r_screen_min", screenMin).putBoolean("r_vol", volumeKeys)
                .putString("r_zones", z.toString()).putInt("r_rest_every", restEvery).putInt("r_rest_for", restFor)
                .putBoolean("r_filler", filler).putInt("r_orient", orient).putInt("r_eye", eye)
                .putInt("r_auto_sec", autoSec).putInt("r_tts_rate", ttsRate).putInt("r_tts_pitch", ttsPitch)
                .putString("r_themes", arr.toString()).apply();
    }

    private static int[] parseZones(String s) {
        if (s.length() != 9) return ZONES_DEFAULT.clone();
        int[] z = new int[9];
        for (int i = 0; i < 9; i++) z[i] = Math.max(0, Math.min(3, s.charAt(i) - '0'));
        return z;
    }

    // ------------------------------------------------------------------ themes

    /** Built-in themes followed by the user's. */
    public static List<Theme> themes() {
        List<Theme> out = new ArrayList<>();
        for (int i = 0; i < BUILT_IN.length; i++) {
            Theme t = new Theme();
            t.key = "b" + i;
            t.bg = BUILT_IN[i][0];
            t.fg = BUILT_IN[i][1];
            t.texture = BUILT_IN[i][2];
            out.add(t);
        }
        out.addAll(custom);
        return out;
    }

    private static Theme find(String key) {
        for (Theme t : themes()) if (t.key.equals(key)) return t;
        return null;
    }

    public static Theme theme() {
        Theme t = find(themeKey);
        return t != null ? t : themes().get(0);
    }

    public static void select(Theme t) {
        themeKey = t.key;
        if (!t.dark()) dayKey = t.key;
    }

    /** Adds the theme or replaces the one with the same key. */
    public static void put(Theme t) {
        for (int i = 0; i < custom.size(); i++) {
            if (custom.get(i).key.equals(t.key)) {
                custom.set(i, t);
                return;
            }
        }
        custom.add(t);
    }

    public static void remove(Theme t) {
        for (int i = 0; i < custom.size(); i++) if (custom.get(i).key.equals(t.key)) custom.remove(i--);
        if (!t.image.isEmpty()) new File(t.image).delete();
        if (themeKey.equals(t.key)) themeKey = "b0";
        if (dayKey.equals(t.key)) dayKey = "b0";
    }

    public static boolean isNight() {
        return theme().dark();
    }

    public static void toggleNight() {
        if (isNight()) themeKey = find(dayKey) == null ? "b0" : dayKey;
        else themeKey = NIGHT_KEY;
    }

    public static int bg() {
        return theme().bg;
    }

    public static int fg() {
        return theme().fg;
    }

    // ------------------------------------------------------------------ fonts

    public static File fontDir() {
        File d = new File(App.I.getFilesDir(), "fonts");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** The typeface for body text; an unreadable font file falls back to the system face. */
    public static Typeface typeface() {
        if (face != null && font.equals(faceKey)) return face;
        Typeface t = null;
        if (font.equals("serif")) t = Typeface.SERIF;
        else if (font.equals("mono")) t = Typeface.MONOSPACE;
        else if (!font.isEmpty()) t = fromFile(new File(font));
        if (t == null) t = Typeface.DEFAULT;
        face = t;
        faceKey = font;
        return t;
    }

    /** null when the file is not a font Android can use. */
    public static Typeface fromFile(File f) {
        if (!f.isFile()) return null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) return new Typeface.Builder(f).build();
            return Typeface.createFromFile(f);
        } catch (Throwable e) {
            return null;
        }
    }
}
