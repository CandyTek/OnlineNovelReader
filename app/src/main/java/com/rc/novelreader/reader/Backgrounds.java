package com.rc.novelreader.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import com.rc.novelreader.App;

import java.util.Random;

/**
 * Page backgrounds: paper textures computed here (cloudy mottling, grain, darker edges, stains, fibres), so the
 * app ships no picture files and nothing has a licence to track, and the user's own pictures.
 * One bitmap is kept, for the theme and size shown last.
 */
final class Backgrounds {
    static final int NONE = 0, PARCHMENT = 1, AGED = 2, RICE = 3, KRAFT = 4;
    static final int KINDS = 5;

    private Backgrounds() {}

    private static String key = "";
    private static Bitmap bitmap;
    private static String pending = "";

    /** The background for the theme at this size, or null: none needed, or still being made (ready runs then). */
    static Bitmap get(final ReaderPrefs.Theme t, final int w, final int h, final Runnable ready) {
        if (w <= 0 || h <= 0 || (t.texture == NONE && t.image.isEmpty())) return null;
        final String k = t.key + "|" + t.bg + "|" + t.texture + "|" + t.image + "|" + w + "x" + h;
        synchronized (Backgrounds.class) {
            if (k.equals(key)) return bitmap;
            if (k.equals(pending)) return null;
            pending = k;
        }
        final ReaderPrefs.Theme theme = t.copy();
        App.IO.execute(() -> {
            Bitmap b = null;
            try {
                b = theme.image.isEmpty() ? texture(theme.texture, theme.bg, w, h) : picture(theme.image, w, h);
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

    /** Smooth random field: random values on a coarse grid, blended in between. */
    private static float[] field(Random rnd, int w, int h, float cellX, float cellY) {
        int gw = (int) (w / cellX) + 3, gh = (int) (h / cellY) + 3;
        float[] g = new float[gw * gh];
        for (int i = 0; i < g.length; i++) g[i] = rnd.nextFloat();
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            float fy = y / cellY;
            int y0 = (int) fy;
            float ty = fy - y0;
            ty = ty * ty * (3 - 2 * ty);
            for (int x = 0; x < w; x++) {
                float fx = x / cellX;
                int x0 = (int) fx;
                float tx = fx - x0;
                tx = tx * tx * (3 - 2 * tx);
                int i = y0 * gw + x0;
                float a = g[i] + (g[i + 1] - g[i]) * tx, b = g[i + gw] + (g[i + gw + 1] - g[i + gw]) * tx;
                out[y * w + x] = a + (b - a) * ty;
            }
        }
        return out;
    }

    static Bitmap texture(int kind, int base, int fullW, int fullH) {
        // computed at reduced size (soft detail survives the enlargement) to stay around 200k pixels
        int scale = Math.max(2, (int) Math.ceil(Math.sqrt(fullW * (double) fullH / 220000)));
        int w = Math.max(8, fullW / scale), h = Math.max(8, fullH / scale);
        Random rnd = new Random(977L * kind + 13);
        float min = Math.min(w, h);
        float[] big = field(rnd, w, h, min / 2.2f, min / 2.2f);
        float[] mid = field(rnd, w, h, min / 9f, min / 9f);
        // kraft paper has a direction: long horizontal streaks
        float[] fine = kind == KRAFT ? field(rnd, w, h, min / 3f, 1.6f) : field(rnd, w, h, min / 40f, min / 40f);
        float mottle = kind == AGED ? 0.20f : kind == RICE ? 0.05f : kind == KRAFT ? 0.10f : 0.11f;
        float grain = kind == KRAFT ? 0.07f : kind == RICE ? 0.025f : 0.04f;
        float edge = kind == AGED ? 0.34f : kind == RICE ? 0.05f : kind == KRAFT ? 0.10f : 0.17f;
        float fineAmp = kind == KRAFT ? 0.10f : 0.05f;
        int r0 = Color.red(base), g0 = Color.green(base), b0 = Color.blue(base);
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            float ey = Math.min(y, h - 1 - y) / (h * 0.5f);
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float ex = Math.min(x, w - 1 - x) / (w * 0.5f);
                // darker towards the edges, unevenly so it does not look like a frame
                float d = Math.min(ex, ey) + (mid[i] - 0.5f) * 0.25f;
                float v = d < 0.5f ? (0.5f - Math.max(0, d)) * 2 : 0;
                float f = 1 + (big[i] - 0.5f) * mottle * 1.4f + (mid[i] - 0.5f) * mottle + (fine[i] - 0.5f) * fineAmp
                        + (rnd.nextFloat() - 0.5f) * grain - v * v * edge;
                // old paper goes brown, not grey: blue falls faster than red
                float fb = f < 1 ? 1 - (1 - f) * 1.35f : f;
                px[i] = 0xFF000000 | (clamp(r0 * f) << 16) | (clamp(g0 * (f + fb) / 2) << 8) | clamp(b0 * fb);
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (kind == AGED) {
            // foxing: a few soft brown stains
            for (int i = 0; i < 9; i++) {
                float cx = rnd.nextFloat() * w, cy = rnd.nextFloat() * h, rad = min * (0.04f + rnd.nextFloat() * 0.16f);
                p.setShader(new RadialGradient(cx, cy, rad, new int[]{0x2A5A3A12, 0x145A3A12, 0x005A3A12},
                        new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, rad, p);
            }
            p.setShader(null);
        }
        if (kind == RICE || kind == KRAFT) {
            // fibres pressed into the sheet
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.8f);
            for (int i = 0; i < (kind == RICE ? 260 : 120); i++) {
                float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h, len = 3 + rnd.nextFloat() * (kind == RICE ? 12 : 8);
                double a = rnd.nextDouble() * Math.PI;
                p.setColor(rnd.nextInt(4) == 0 ? 0x30FFFFFF : kind == RICE ? 0x2C8A7A5C : 0x305A3D1C);
                c.drawLine(x, y, x + (float) Math.cos(a) * len, y + (float) Math.sin(a) * len, p);
            }
        }
        return bmp;
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }
}
