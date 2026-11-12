package com.rc.novelreader.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.rc.novelreader.App;
import com.rc.novelreader.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URLDecoder;

/** File downloads through the system download manager (it survives the app, resumes and shows progress). */
final class Downloads {
    private Downloads() {}

    static String fileName(String url, String disposition, String mime) {
        String name = URLUtil.guessFileName(url, disposition, mime);
        // guessFileName falls back to ".bin" although the type is known
        if (name.endsWith(".bin") && mime != null) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null) name = name.substring(0, name.length() - 3) + ext;
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** Asks before downloading: shows the name (editable) and the size. */
    static void ask(final Activity a, final String url, final String ua, String disposition, final String mime,
                    long length, final String referer) {
        if (a.isFinishing()) return;
        if (url.startsWith("blob:")) {
            App.toast(a.getString(R.string.download_unsupported));
            return;
        }
        final EditText name = new EditText(a);
        name.setSingleLine(true);
        name.setText(url.startsWith("data:") ? "download" + dataExtension(url) : fileName(url, disposition, mime));
        FrameLayout box = new FrameLayout(a);
        int pad = App.dp(20);
        box.setPadding(pad, App.dp(8), pad, 0);
        box.addView(name);
        String size = length > 0 ? Formatter.formatFileSize(a, length) : a.getString(R.string.size_unknown);
        new AlertDialog.Builder(a).setTitle(R.string.download_file)
                .setMessage(size + (url.startsWith("data:") ? "" : "\n" + (url.length() > 120 ? url.substring(0, 120) + "…" : url)))
                .setView(box)
                .setPositiveButton(R.string.download, (d, w) -> {
                    String n = name.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
                    start(a, url, n.isEmpty() ? "download" : n, ua, mime, referer);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    static void start(final Context c, final String url, final String name, String ua, String mime, String referer) {
        if (url.startsWith("data:")) {
            App.IO.execute(() -> {
                try {
                    saveBytes(c, name, mime, decodeData(url));
                    App.toast(c.getString(R.string.download_done, name));
                } catch (Exception e) {
                    App.toast(c.getString(R.string.download_failed, String.valueOf(e.getMessage())));
                }
            });
            return;
        }
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
            r.addRequestHeader("User-Agent", ua == null ? App.UA : ua);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (referer != null && referer.startsWith("http")) r.addRequestHeader("Referer", referer);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29) r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            else r.setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, name); // needs no permission
            ((DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            App.toast(c.getString(R.string.download_started, name));
        } catch (Exception e) {
            App.toast(c.getString(R.string.download_failed, String.valueOf(e.getMessage())));
        }
    }

    private static String dataExtension(String url) {
        int end = url.indexOf(',');
        String head = end < 0 ? "" : url.substring(5, end);
        String mime = head.split(";")[0];
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return ext == null ? "" : "." + ext;
    }

    private static byte[] decodeData(String url) throws Exception {
        int comma = url.indexOf(',');
        if (comma < 0) throw new IllegalArgumentException("bad data URL");
        String head = url.substring(5, comma), body = url.substring(comma + 1);
        return head.endsWith(";base64") ? Base64.decode(body, Base64.DEFAULT) : URLDecoder.decode(body, "UTF-8").getBytes("UTF-8");
    }

    /** Writes a file into the public Downloads folder (app folder before Android 10). */
    static void saveBytes(Context c, String name, String mime, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            if (mime != null && !mime.isEmpty()) v.put(MediaStore.Downloads.MIME_TYPE, mime);
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new java.io.IOException("no storage");
            try (OutputStream out = c.getContentResolver().openOutputStream(uri)) {
                out.write(data);
            }
        } else {
            File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(data);
            }
        }
    }

    static void openSystemList(Context c) {
        try {
            c.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            App.toast(c.getString(R.string.no_app_found));
        }
    }
}
