package com.rc.novelreader.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import com.rc.novelreader.App;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Page backgrounds: the pictures of paper in assets/paper and the user's own pictures.
 * The folder is read, not listed here: every picture dropped into it becomes a theme (see LICENSE.txt there for
 * where the present ones come from). What a picture is, is told from the picture: one whose opposite edges
 * continue each other is a <b>tile</b>, repeated over the page at the screen's density, and only its grain is
 * used (the colour is the theme's, so a tile serves any colour); anything else is a <b>sheet</b>, one page with
 * edges of its own, shown as it is and stretched to the page. One bitmap is kept, for the theme and size shown last.
 */
final class Backgrounds {
    private static final String DIR = "paper";
    /** The tiles are stored for a screen of this density; the page view scales them by density / this. */
    static final float TILE_DENSITY = 2.25f;

    private Backgrounds() {}

    /** One picture of the folder, with what was measured on it. */
    static final class Paper {
        final String file;
        /** Average colour. */
        final int color;
        final boolean tile;

        Paper(String file, int color, boolean tile) {
            this.file = file;
            this.color = color;
            this.tile = tile;
        }

        /** The file name without its ending: the only name a picture has. */
        String name() {
            int dot = file.lastIndexOf('.');
            return dot > 0 ? file.substring(0, dot) : file;
        }
    }

    private static List<Paper> papers;
    private static String key = "";
    private static Bitmap bitmap;
    private static String pending = "";

    /**
     * The pictures in the folder, by name. Measuring them means decoding each one (small), so the result is kept
     * in a preferences file until the app is installed again.
     */
    static synchronized List<Paper> papers() {
        if (papers != null) return papers;
        List<Paper> out = new ArrayList<>();
        long stamp = 0;
        try {
            stamp = App.I.getPackageManager().getPackageInfo(App.I.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) {
        }
        SharedPreferences sp = App.I.getSharedPreferences("papers", Context.MODE_PRIVATE);
        JSONObject known = new JSONObject();
        try {
            if (sp.getLong("stamp", -1) == stamp) known = new JSONObject(sp.getString("info", "{}"));
        } catch (Exception ignored) {
        }
        JSONObject now = new JSONObject();
        try {
            String[] names = App.I.getAssets().list(DIR);
            if (names == null) names = new String[0];
            Arrays.sort(names);
            for (String name : names) {
                String low = name.toLowerCase(Locale.US);
                if (!low.endsWith(".jpg") && !low.endsWith(".jpeg") && !low.endsWith(".webp") && !low.endsWith(".png")) continue;
                JSONObject o = known.optJSONObject(name);
                Paper p = o != null ? new Paper(name, o.getInt("c"), o.getBoolean("t")) : measure(name);
                if (p == null) continue;
                out.add(p);
                now.put(name, new JSONObject().put("c", p.color).put("t", p.tile));
            }
        } catch (Exception ignored) {
        }
        if (now.length() != known.length()) sp.edit().putLong("stamp", stamp).putString("info", now.toString()).apply();
        papers = out;
        return out;
    }

    /** null for "" and for a picture that is no longer in the folder. */
    static Paper paper(String file) {
        if (file == null || file.isEmpty()) return null;
        for (Paper p : papers()) if (p.file.equals(file)) return p;
        return null;
    }

    private static Bitmap decode(String file, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = App.I.getAssets().open(DIR + "/" + file)) {
            BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
        int s = 1;
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = s;
        try (InputStream in = App.I.getAssets().open(DIR + "/" + file)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Average colour, and whether the picture repeats: across a seam (last column against the first, last row
     * against the first) a tile changes no more than it does between two neighbouring lines inside, while a
     * sheet with worn or shaded edges, or any ordinary photograph, changes several times as much.
     */
    private static Paper measure(String file) {
        Bitmap b = decode(file, 96, 96);
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        if (w < 8 || h < 8) return null;
        long sr = 0, sg = 0, sb = 0;
        float[] l = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            int r = (px[i] >> 16) & 255, g = (px[i] >> 8) & 255, bl = px[i] & 255;
            sr += r;
            sg += g;
            sb += bl;
            l[i] = (r * 3 + g * 6 + bl) / 10f;
        }
        int n = px.length;
        float seamX = 0, seamY = 0, innerX = 0, innerY = 0;
        for (int y = 0; y < h; y++) {
            seamX += Math.abs(l[y * w] - l[y * w + w - 1]);
            for (int k = 1; k <= 3; k++) innerX += Math.abs(l[y * w + k * w / 4] - l[y * w + k * w / 4 + 1]);
        }
        for (int x = 0; x < w; x++) {
            seamY += Math.abs(l[x] - l[(h - 1) * w + x]);
            for (int k = 1; k <= 3; k++) innerY += Math.abs(l[(k * h / 4) * w + x] - l[(k * h / 4 + 1) * w + x]);
        }
        seamX /= h;
        innerX /= h * 3;
        seamY /= w;
        innerY /= w * 3;
        boolean tile = seamX <= innerX * 1.6f + 0.5f && seamY <= innerY * 1.6f + 0.5f;
        return new Paper(file, Color.rgb((int) (sr / n), (int) (sg / n), (int) (sb / n)), tile);
    }

    /** Whether the theme's background is a tile to repeat and not one picture for the whole page. */
    static boolean tiled(ReaderPrefs.Theme t) {
        Paper p = t.image.isEmpty() ? paper(t.paper) : null;
        return p != null && p.tile;
    }

    /** Whether the background is a sheet from the folder: it keeps all four edges, so it is stretched to fit. */
    static boolean sheet(ReaderPrefs.Theme t) {
        Paper p = t.image.isEmpty() ? paper(t.paper) : null;
        return p != null && !p.tile;
    }

    /** The background for the theme at this size, or null: none needed, or still being made (ready runs then). */
    static Bitmap get(final ReaderPrefs.Theme t, final int w, final int h, final Runnable ready) {
        final Paper paper = t.image.isEmpty() ? paper(t.paper) : null;
        if (w <= 0 || h <= 0 || (paper == null && t.image.isEmpty())) return null;
        final boolean tile = paper != null && paper.tile;
        final String k = t.key + "|" + t.bg + "|" + t.paper + "|" + t.image + "|" + (tile ? "" : w + "x" + h);
        synchronized (Backgrounds.class) {
            if (k.equals(key)) return bitmap;
            if (k.equals(pending)) return null;
            pending = k;
        }
        final ReaderPrefs.Theme theme = t.copy();
        App.IO.execute(() -> {
            Bitmap b = null;
            try {
                b = paper == null ? picture(theme.image, w, h) : preview(paper, theme.bg, w, h);
            } catch (Throwable ignored) {
            }
            synchronized (Backgrounds.class) {
                if (!k.equals(pending)) return;
                pending = "";
                key = k;
                bitmap = b;
            }
            if (ready != null) App.MAIN.post(ready);
        });
        return null;
    }

    static void forget() {
        synchronized (Backgrounds.class) {
            key = "";
            bitmap = null;
        }
    }

    private static Bitmap picture(String path, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = s;
        return BitmapFactory.decodeFile(path, o);
    }

    /** What the paper gives for a theme of this colour: the tile in that colour, or the sheet for this size. */
    static Bitmap preview(Paper p, int base, int w, int h) {
        return p.tile ? tile(p, base) : decode(p.file, w, h);
    }

    private static Bitmap tile(Paper p, int base) {
        Bitmap src = decode(p.file, Integer.MAX_VALUE, Integer.MAX_VALUE);
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight(), n = w * h;
        int[] px = new int[n];
        src.getPixels(px, 0, w, 0, 0, w, h);
        src.recycle();
        float mr = Math.max(1, Color.red(p.color)), mg = Math.max(1, Color.green(p.color)), mb = Math.max(1, Color.blue(p.color));
        int r0 = Color.red(base), g0 = Color.green(base), b0 = Color.blue(base);
        // each channel keeps its deviation from the picture's average, so the average comes out as the theme
        // colour; the floor keeps the grain visible on dark themes, where a share of the colour would be nothing
        float ar = Math.max(r0, 96) / mr, ag = Math.max(g0, 96) / mg, ab = Math.max(b0, 96) / mb;
        for (int i = 0; i < n; i++) {
            int c = px[i];
            px[i] = 0xFF000000 | (clamp(r0 + (((c >> 16) & 255) - mr) * ar) << 16)
                    | (clamp(g0 + (((c >> 8) & 255) - mg) * ag) << 8) | clamp(b0 + ((c & 255) - mb) * ab);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }
}
