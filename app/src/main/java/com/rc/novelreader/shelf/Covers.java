package com.rc.novelreader.shelf;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;
import android.widget.ImageView;

import com.rc.novelreader.App;
import com.rc.novelreader.data.Book;
import com.rc.novelreader.net.Http;

import java.io.File;
import java.io.FileOutputStream;

/** Cover images: memory + disk cache, with a generated title cover as placeholder and fallback. */
public final class Covers {
    private Covers() {}

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public static void load(final ImageView view, final Book b) {
        final String url = b.cover;
        view.setTag(url);
        Bitmap hit = url.isEmpty() ? null : MEM.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(new TitleCover(b.title));
        if (url.isEmpty()) return;
        App.IO.execute(() -> {
            final Bitmap bmp = fetch(url, b.detailUrl);
            if (bmp == null) return;
            MEM.put(url, bmp);
            App.MAIN.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bmp);
            });
        });
    }

    private static Bitmap fetch(String url, String referer) {
        try {
            File dir = new File(App.I.getCacheDir(), "covers");
            File f = new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length());
            if (!f.exists()) {
                if (!App.prefs().getBoolean("load_covers", false)) return null;
                byte[] data = Http.getBytes(url, referer);
                if (data.length < 100) return null;
                dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / sample > 600) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
            if (bmp == null) f.delete();
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A plain book cover drawn from the title. */
    static final class TitleCover extends Drawable {
        private static final int[] COLORS = {0xFF2F6F62, 0xFF7A4E3A, 0xFF3F5B8C, 0xFF6B4C7E, 0xFF8A6A2B, 0xFF4E6E3F, 0xFF8C3F4D};
        private final String title;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        TitleCover(String title) {
            this.title = title == null ? "" : title;
            bg.setColor(COLORS[Math.abs(this.title.hashCode()) % COLORS.length]);
            text.setColor(0xFFF4EFE4);
            text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        }

        @Override
        public void draw(Canvas c) {
            Rect r = getBounds();
            c.drawRect(r, bg);
            Paint line = new Paint();
            line.setColor(0x33FFFFFF);
            c.drawRect(r.left + r.width() * 0.07f, r.top, r.left + r.width() * 0.09f, r.bottom, line);
            text.setTextSize(r.width() * 0.15f);
            int w = (int) (r.width() * 0.72f);
            if (w <= 0) return;
            StaticLayout l = StaticLayout.Builder.obtain(title, 0, title.length(), text, w)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(4)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END).build();
            c.save();
            c.translate(r.left + r.width() * 0.17f, r.top + Math.max(r.height() * 0.12f, (r.height() * 0.8f - l.getHeight()) / 2f));
            l.draw(c);
            c.restore();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
