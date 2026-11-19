package com.rc.novelreader.browser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.view.View;

import com.rc.novelreader.App;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Set;

/**
 * Small pictures of what the tabs show, for the tab grid and the swipe view. One is taken when a tab is left or
 * a tab view opens: it is drawn from the view that is on screen, so it costs no request. Those of ordinary tabs
 * are also kept in files/tabthumbs/&lt;tab id&gt;.jpg, so tabs that come back after a restart have a face before
 * they are loaded; incognito tabs keep theirs in memory only.
 */
final class TabThumbs {
    private TabThumbs() {
    }

    private static File dir() {
        File d = new File(App.I.getFilesDir(), "tabthumbs");
        d.mkdirs();
        return d;
    }

    private static File file(long id) {
        return new File(dir(), id + ".jpg");
    }

    /** Draws the view (a WebView or the start page) at half size into the tab's picture. */
    static void capture(final Tab t, View v) {
        if (t == null || v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        float s = Math.min(0.5f, 1100f / Math.max(v.getWidth(), v.getHeight()));
        final Bitmap b;
        try {
            // a new one each time: the last may still be on its way to the file
            b = Bitmap.createBitmap(Math.max(1, Math.round(v.getWidth() * s)), Math.max(1, Math.round(v.getHeight() * s)),
                    Bitmap.Config.RGB_565);
            Canvas c = new Canvas(b);
            c.drawColor(0xFFFFFFFF);
            c.scale(s, s);
            c.translate(-v.getScrollX(), -v.getScrollY());
            v.draw(c);
        } catch (Throwable e) {
            return;
        }
        t.thumb = b;
        if (t.incognito) return;
        final long id = t.id;
        App.IO.execute(() -> {
            File tmp = new File(dir(), id + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                b.compress(Bitmap.CompressFormat.JPEG, 78, out);
            } catch (Throwable e) {
                tmp.delete();
                return;
            }
            tmp.renameTo(file(id));
        });
    }

    /** Reads a stored picture; call off the main thread. */
    static Bitmap load(long id) {
        File f = file(id);
        if (!f.isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable e) {
            return null;
        }
    }

    static void delete(final long id) {
        App.IO.execute(() -> file(id).delete());
    }

    /** Removes the pictures of tabs that no longer exist. */
    static void prune(final Set<Long> keep) {
        App.IO.execute(() -> {
            File[] fs = dir().listFiles();
            if (fs == null) return;
            for (File f : fs) {
                String n = f.getName();
                int dot = n.indexOf('.');
                long id = -1;
                try {
                    id = Long.parseLong(dot < 0 ? n : n.substring(0, dot));
                } catch (NumberFormatException ignored) {
                }
                if (!keep.contains(id)) f.delete();
            }
        });
    }
}
