package com.rc.novelreader.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.rc.novelreader.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Everything the user has made in the app as one zip: the database (shelf, chapter lists, progress, bookmarks,
 * history), all settings, reader bookmarks, notes, statistics and word list, imported fonts and theme pictures,
 * and optionally the cached chapter text. Passwords and keys are left out.
 */
public final class Backup {
    private Backup() {}

    public static final String FILE_NAME = "novelsniffer-backup.zip";

    /** Settings files; "" is the default one. */
    private static final String[] PREFS = {"", "reader_marks", "reader_notes", "reader_stats", "reader_vocab", "reader_ai", "site_tests"};
    /** Never written to a backup and never overwritten by one. */
    private static final String[] SECRET = {"webdav_pass", "ai_key"};
    private static final String[] DIRS = {"fonts", "themes"};

    private static SharedPreferences prefs(Context c, String name) {
        return name.isEmpty() ? App.prefs() : c.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static boolean secret(String key) {
        for (String s : SECRET) if (s.equals(key)) return true;
        return false;
    }

    // ------------------------------------------------------------------ writing

    public static void write(Context c, OutputStream to, boolean withText) throws IOException {
        File db = c.getDatabasePath("novel.db");
        try (ZipOutputStream zip = new ZipOutputStream(to)) {
            // closed so that everything is in the one file, not in a write-ahead log beside it
            synchronized (Db.class) {
                Db.closeAll();
                if (db.exists()) put(zip, "db/novel.db", db);
            }
            for (String name : PREFS) {
                zip.putNextEntry(new ZipEntry("prefs/" + (name.isEmpty() ? "default" : name) + ".json"));
                zip.write(export(prefs(c, name)).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            for (String d : DIRS) tree(zip, new File(c.getFilesDir(), d), "files/" + d);
            if (withText) tree(zip, new File(c.getFilesDir(), "books"), "files/books");
        }
    }

    private static void put(ZipOutputStream zip, String name, File f) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) zip.write(buf, 0, n);
        }
        zip.closeEntry();
    }

    private static void tree(ZipOutputStream zip, File dir, String name) throws IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) tree(zip, f, name + "/" + f.getName());
            else if (!f.getName().endsWith(".tmp")) put(zip, name + "/" + f.getName(), f);
        }
    }

    /** Each value with its type, so it comes back as what it was: {"key": ["b"|"i"|"l"|"f"|"s"|"S", value]}. */
    private static String export(SharedPreferences p) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                Object v = e.getValue();
                if (secret(e.getKey()) || v == null) continue;
                JSONArray a = new JSONArray();
                if (v instanceof Boolean) a.put("b").put(v);
                else if (v instanceof Integer) a.put("i").put(v);
                else if (v instanceof Long) a.put("l").put(v);
                else if (v instanceof Float) a.put("f").put(String.valueOf(v));
                else if (v instanceof Set) a.put("S").put(new JSONArray((Set<?>) v));
                else a.put("s").put(String.valueOf(v));
                o.put(e.getKey(), a);
            }
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    // ------------------------------------------------------------------ reading

    /**
     * Replaces the app's data with the backup. The process must be restarted afterwards: activities and
     * caches still hold the old state.
     */
    public static void restore(Context c, InputStream from) throws IOException {
        File stage = new File(c.getCacheDir(), "restore");
        deleteTree(stage);
        boolean hasDb = false, hasText = false;
        try (ZipInputStream zip = new ZipInputStream(from)) {
            byte[] buf = new byte[65536];
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                // only the entries this class writes, and nothing that climbs out of the folder
                if (e.isDirectory() || name.contains("..") || name.startsWith("/")
                        || !(name.startsWith("db/") || name.startsWith("prefs/") || name.startsWith("files/"))) continue;
                File out = new File(stage, name);
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    for (int n; (n = zip.read(buf)) > 0; ) o.write(buf, 0, n);
                }
                hasDb |= name.equals("db/novel.db");
                hasText |= name.startsWith("files/books/");
            }
        }
        if (!hasDb) {
            deleteTree(stage);
            throw new IOException("This file is not a backup of this app");
        }
        File db = c.getDatabasePath("novel.db");
        synchronized (Db.class) {
            Db.closeAll();
            new File(db.getPath() + "-wal").delete();
            new File(db.getPath() + "-shm").delete();
            new File(db.getPath() + "-journal").delete();
            copy(new File(stage, "db/novel.db"), db);
        }
        for (String name : PREFS) {
            File f = new File(stage, "prefs/" + (name.isEmpty() ? "default" : name) + ".json");
            if (f.exists()) apply(prefs(c, name), new String(readAll(f), StandardCharsets.UTF_8));
        }
        for (String d : DIRS) moveTree(new File(stage, "files/" + d), new File(c.getFilesDir(), d));
        File books = new File(c.getFilesDir(), "books");
        // chapter ids come from the restored database: text of the old one would be shown under wrong chapters
        deleteTree(books);
        if (hasText) moveTree(new File(stage, "files/books"), books);
        else {
            Db.get().execSQL("UPDATE chapters SET cached=0 WHERE bookId NOT IN (SELECT id FROM books WHERE urlKey LIKE 'local:%')");
            Db.closeAll();
        }
        deleteTree(stage);
    }

    @SuppressWarnings("ApplySharedPref")
    private static void apply(SharedPreferences p, String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor e = p.edit();
            for (String key : new HashSet<>(p.getAll().keySet())) if (!secret(key)) e.remove(key);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String key = it.next();
                if (secret(key)) continue;
                JSONArray a = o.getJSONArray(key);
                switch (a.getString(0)) {
                    case "b":
                        e.putBoolean(key, a.getBoolean(1));
                        break;
                    case "i":
                        e.putInt(key, a.getInt(1));
                        break;
                    case "l":
                        e.putLong(key, a.getLong(1));
                        break;
                    case "f":
                        e.putFloat(key, Float.parseFloat(a.getString(1)));
                        break;
                    case "S": {
                        Set<String> set = new HashSet<>();
                        JSONArray arr = a.getJSONArray(1);
                        for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
                        e.putStringSet(key, set);
                        break;
                    }
                    default:
                        e.putString(key, a.getString(1));
                }
            }
            e.commit();
        } catch (org.json.JSONException | NumberFormatException ex) {
            throw new IOException("Damaged settings in the backup");
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void copy(File from, File to) throws IOException {
        File parent = to.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
    }

    private static void moveTree(File from, File to) throws IOException {
        File[] fs = from.listFiles();
        if (fs == null) return;
        to.mkdirs();
        for (File f : fs) {
            File t = new File(to, f.getName());
            if (f.isDirectory()) moveTree(f, t);
            else if (!f.renameTo(t)) copy(f, t);
        }
    }

    private static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }

    // ------------------------------------------------------------------ WebDAV (only when the user presses the button)

    private static HttpURLConnection dav(String folder, String user, String pass, String method) throws IOException {
        String url = folder.trim();
        if (!url.endsWith("/")) url += "/";
        HttpURLConnection c = (HttpURLConnection) new URL(url + FILE_NAME).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestMethod(method);
        if (!user.isEmpty()) {
            String token = Base64.encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            c.setRequestProperty("Authorization", "Basic " + token);
        }
        return c;
    }

    public static void upload(String folder, String user, String pass, File zip) throws IOException {
        HttpURLConnection c = dav(folder, user, pass, "PUT");
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(zip.length());
            c.setRequestProperty("Content-Type", "application/zip");
            try (OutputStream out = c.getOutputStream(); InputStream in = new FileInputStream(zip)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
        } finally {
            c.disconnect();
        }
    }

    public static void download(String folder, String user, String pass, File to) throws IOException {
        HttpURLConnection c = dav(folder, user, pass, "GET");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }
}
