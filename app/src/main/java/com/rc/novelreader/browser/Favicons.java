package com.rc.novelreader.browser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import com.rc.novelreader.App;
import com.rc.novelreader.data.Db;
import com.rc.novelreader.util.Urls;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Site icons, one per host, shared by bookmarks, history, start-page tiles, suggestions and the tab list.
 * The icons are the ones WebView has already downloaded with a page ({@code onReceivedIcon}); this class never
 * touches the network, so a site that was never visited simply has no icon.
 */
final class Favicons {
    private static final int MAX_PX = 96;
    /** Hosts known to have no file, so lists do not hit the disk again for every row. */
    private static final Set<String> MISSING = new HashSet<>();
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(120) {
    };

    private Favicons() {
    }

    private static File dir() {
        File d = new File(App.I.getFilesDir(), "favicons");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static String key(String url) {
        String h = url == null ? "" : Urls.host(url);
        if (h == null || h.isEmpty() || h.length() > 120) return null;
        return h.toLowerCase().replaceAll("[^a-z0-9.-]", "_");
    }

    /** Remembers the icon of the site {@code url} belongs to. Main thread. */
    static void put(String url, Bitmap icon) {
        final String k = key(url);
        if (k == null || icon == null || icon.getWidth() < 8 || icon.getHeight() < 8) return;
        Bitmap old = MEM.get(k);
        if (old != null && old.getWidth() >= icon.getWidth()) return;
        int side = Math.max(icon.getWidth(), icon.getHeight());
        final Bitmap small = side <= MAX_PX ? icon : Bitmap.createScaledBitmap(icon,
                Math.max(1, icon.getWidth() * MAX_PX / side), Math.max(1, icon.getHeight() * MAX_PX / side), true);
        MEM.put(k, small);
        synchronized (MISSING) {
            MISSING.remove(k);
        }
        App.IO.execute(() -> {
            File tmp = new File(dir(), k + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                small.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                tmp.delete();
                return;
            }
            tmp.renameTo(new File(dir(), k + ".png"));
        });
    }

    /** @return the stored icon of the site, or null. Reads a small file on first use, then serves from memory. */
    static Bitmap get(String url) {
        String k = key(url);
        if (k == null) return null;
        Bitmap b = MEM.get(k);
        if (b != null) return b;
        synchronized (MISSING) {
            if (MISSING.contains(k)) return null;
        }
        File f = new File(dir(), k + ".png");
        b = f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
        if (b != null) MEM.put(k, b);
        else synchronized (MISSING) {
            MISSING.add(k);
        }
        return b;
    }

    /** Shows the site icon in {@code view}, or the tinted fallback drawable when there is none. */
    static void show(ImageView view, String url, int fallbackRes, int fallbackTint) {
        Bitmap b = get(url);
        if (b != null) {
            view.setImageTintList(null);
            view.setImageBitmap(b);
        } else {
            view.setImageResource(fallbackRes);
            view.setImageTintList(android.content.res.ColorStateList.valueOf(fallbackTint));
        }
    }

    /** History was cleared: icons are a trace of visited sites too. Those of bookmarked sites stay. */
    static void clearUnbookmarked() {
        MEM.evictAll();
        synchronized (MISSING) {
            MISSING.clear();
        }
        App.IO.execute(() -> {
            Set<String> keep = new HashSet<>();
            for (String[] b : Db.bookmarks()) {
                String k = key(b[0]);
                if (k != null) keep.add(k + ".png");
            }
            File[] files = dir().listFiles();
            if (files == null) return;
            for (File f : files) if (!keep.contains(f.getName())) f.delete();
        });
    }
}
