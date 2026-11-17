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

    /** A page look: colours, plus optionally a paper from the assets folder or the user's own picture. */
    public static final class Theme {
        /** "b0".."b4" for the flat ones, "a&lt;file&gt;" for a paper of the assets folder, "c&lt;time&gt;" for the user's. */
        public String key = "";
        public String name = "";
        public int bg, fg;
        /** File name of a paper in the assets folder (see {@link Backgrounds}), "" for a flat colour. */
        public String paper = "";
        /** Path of a background picture, "" for none. Wins over the paper. */
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
            t.paper = paper;
            t.image = image;
            return t;
        }
    }

    /** {background, text}: paper, white, green, dark grey, black. The papers of the assets folder follow them. */
    private static final int[][] BUILT_IN = {
            {0xFFF3E9D2, 0xFF3B3226},
            {0xFFFFFFFF, 0xFF222222},
            {0xFFCFE6D0, 0xFF22352A},
            {0xFF2A2C2B, 0xFFA7ADA9},
            {0xFF000000, 0xFF7C827E},
    };
    private static final String NIGHT_KEY = "b3";

    // tap zone actions; the screen is a 3x3 grid, row by row
    public static final int Z_MENU = 0, Z_PREV = 1, Z_NEXT = 2, Z_NONE = 3;
    public static final int[] ZONES_DEFAULT = {Z_PREV, Z_PREV, Z_NEXT, Z_PREV, Z_MENU, Z_NEXT, Z_PREV, Z_NEXT, Z_NEXT};
    public static final int[] ZONES_ALL_NEXT = {Z_NEXT, Z_NEXT, Z_NEXT, Z_NEXT, Z_MENU, Z_NEXT, Z_NEXT, Z_NEXT, Z_NEXT};
    /** More things a zone or a gesture can do; the page view passes these on to the activity. */
    public static final int A_PREV_CHAPTER = 4, A_NEXT_CHAPTER = 5, A_CONTENTS = 6, A_NIGHT = 7, A_BOOKMARK = 8,
            A_SPEAK = 9, A_AUTO = 10, A_SEARCH = 11, A_BACK = 12, A_COUNT = 13;
    /** Gestures: swipe up, swipe down (paged modes only), double tap. */
    public static final int G_UP = 0, G_DOWN = 1, G_DOUBLE = 2;
    public static final int[] GESTURES_DEFAULT = {Z_NONE, Z_NONE, Z_NONE};

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

    public static int[] gestures = GESTURES_DEFAULT.clone();
    /** A vertical drag along the left edge changes the brightness. */
    public static boolean edgeBright = false;
    /** Two pages side by side when the screen is wide. */
    public static boolean twoPage = true;
    /** Paged modes, automatic reading: a line uncovers the next page (true) or the page is turned when its time is up. */
    public static boolean autoSweep = true;
    /** Dim all but a movable band of lines. */
    public static boolean ruler = false;
    /** Shaking the device starts or pauses reading aloud. */
    public static boolean shake = false;
    /** One of the {@link com.rc.novelreader.util.Hanzi} directions. */
    public static int convert = 0;
    /** Automatic scrolling in scroll mode, dp per second. */
    public static int autoSpeed = 30;
    /** Night theme between these minutes of the day; -1 = no schedule. */
    public static int nightFrom = -1, nightTo = -1;
    /** Speech from a web service instead of the system engine: the address with {{speakText}} in it. */
    public static String ttsUrl = "";
    public static boolean ttsOnline = false;
    /** Index of the highlight colour used last. */
    public static int noteColor = 0;
    public static final int[] NOTE_COLORS = {0xFFF2C14E, 0xFF7BC96F, 0xFF6FB7E8, 0xFFE88A9A, 0xFFB79CED};

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
        zones = parseActions(p.getString("r_zones", ""), ZONES_DEFAULT);
        gestures = parseActions(p.getString("r_gestures", ""), GESTURES_DEFAULT);
        edgeBright = p.getBoolean("r_edge_bright", false);
        twoPage = p.getBoolean("r_two_page", true);
        autoSweep = p.getBoolean("r_auto_sweep", true);
        ruler = p.getBoolean("r_ruler", false);
        shake = p.getBoolean("r_shake", false);
        convert = p.getInt("r_convert", 0);
        autoSpeed = Math.max(4, p.getInt("r_auto_speed", 30));
        nightFrom = p.getInt("r_night_from", -1);
        nightTo = p.getInt("r_night_to", -1);
        ttsUrl = p.getString("r_tts_url", "");
        ttsOnline = p.getBoolean("r_tts_online", false);
        noteColor = Math.max(0, Math.min(NOTE_COLORS.length - 1, p.getInt("r_note_color", 0)));
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
                t.paper = o.optString("p");
                // themes saved while textures were numbered: the number picks a paper so the theme keeps one
                int kind = o.optInt("x");
                List<Backgrounds.Paper> papers = Backgrounds.papers();
                if (t.paper.isEmpty() && kind > 0 && !papers.isEmpty()) t.paper = papers.get((kind - 1) % papers.size()).file;
                if (Backgrounds.paper(t.paper) == null) t.paper = "";
                t.image = o.optString("i");
                custom.add(t);
            }
        } catch (Exception ignored) {
        }
        if (find(themeKey) == null) themeKey = "b0";
        if (find(dayKey) == null || find(dayKey).dark()) dayKey = "b0";
    }

    public static void save() {
        StringBuilder z = new StringBuilder(), g = new StringBuilder();
        for (int v : zones) z.append(Character.forDigit(v, 36));
        for (int v : gestures) g.append(Character.forDigit(v, 36));
        JSONArray arr = new JSONArray();
        try {
            for (Theme t : custom) {
                arr.put(new JSONObject().put("k", t.key).put("n", t.name).put("b", t.bg).put("f", t.fg)
                        .put("p", t.paper).put("i", t.image));
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
                .putString("r_themes", arr.toString())
                .putString("r_gestures", g.toString()).putBoolean("r_edge_bright", edgeBright)
                .putBoolean("r_two_page", twoPage).putBoolean("r_auto_sweep", autoSweep).putBoolean("r_ruler", ruler).putBoolean("r_shake", shake)
                .putInt("r_convert", convert).putInt("r_auto_speed", autoSpeed).putInt("r_night_from", nightFrom)
                .putInt("r_night_to", nightTo).putString("r_tts_url", ttsUrl).putBoolean("r_tts_online", ttsOnline)
                .putInt("r_note_color", noteColor).apply();
    }

    /** One base-36 digit per action; anything unreadable gives the default. */
    private static int[] parseActions(String s, int[] def) {
        if (s.length() != def.length) return def.clone();
        int[] z = new int[def.length];
        for (int i = 0; i < z.length; i++) {
            int v = Character.digit(s.charAt(i), 36);
            z[i] = v < 0 || v >= A_COUNT ? def[i] : v;
        }
        return z;
    }

    /** Whether the night schedule says it is night now; false without a schedule. */
    public static boolean scheduledNight() {
        if (nightFrom < 0 || nightTo < 0 || nightFrom == nightTo) return false;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        return nightFrom < nightTo ? now >= nightFrom && now < nightTo : now >= nightFrom || now < nightTo;
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
            out.add(t);
        }
        for (Backgrounds.Paper p : Backgrounds.papers()) {
            Theme t = new Theme();
            t.key = "a" + p.file;
            t.bg = p.color;
            t.paper = p.file;
            // the picture decides: dark text on light paper, light on dark
            t.fg = 0xFF2E2A25;
            if (t.dark()) t.fg = 0xFFA7ADA9;
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
