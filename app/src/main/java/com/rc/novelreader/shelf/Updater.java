package com.rc.novelreader.shelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;

import com.rc.novelreader.App;
import com.rc.novelreader.BuildConfig;
import com.rc.novelreader.R;
import com.rc.novelreader.browser.BrowserActivity;
import com.rc.novelreader.util.AppUpdate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The app's own update: asks the GitHub API for the latest release of the project, offers a newer one and
 * installs its package. One request to api.github.com per check, never to a novel site.
 */
public final class Updater {
    private Updater() {
    }

    /** The project's releases. This is the app's own home, not a site that is sniffed or fetched. */
    private static final String API = "https://api.github.com/repos/CandyTek/OnlineNovelReader/releases/latest";
    /** Switch in the settings, on unless the user turns it off (the owner asked for the automatic check). */
    public static final String PREF_AUTO = "app_update";
    private static final String PREF_AT = "app_update_at";
    private static final String PREF_SKIP = "app_update_skip";
    private static final long EVERY = 24 * 3600_000L;
    private static final String ACTION_STATUS = BuildConfig.APPLICATION_ID + ".UPDATE_STATUS";

    private static boolean checking;
    private static boolean receiving;
    /** The release whose package is being installed, for the message when that fails. */
    private static AppUpdate.Release installing;
    private static Activity installFrom;

    /** At the start of the app: at most one check a day, silent unless there is something new. */
    public static void auto(Activity a) {
        SharedPreferences p = App.prefs();
        if (!p.getBoolean(PREF_AUTO, true)) return;
        long since = System.currentTimeMillis() - p.getLong(PREF_AT, 0);
        if (since >= 0 && since < EVERY) return;
        check(a, false);
    }

    /** @param manual the user asked: say also "up to date" and "failed", and offer a version that was skipped */
    public static void check(Activity a, boolean manual) {
        if (checking) return;
        checking = true;
        if (manual) App.toast(a.getString(R.string.up_checking));
        App.IO.execute(() -> {
            AppUpdate.Release found = null;
            String error = null;
            try {
                found = AppUpdate.parse(get(API));
                if (found == null) error = a.getString(R.string.up_no_release);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            AppUpdate.Release r = found;
            String err = error;
            App.MAIN.post(() -> {
                checking = false;
                if (r == null) {
                    // an automatic check that got no answer gives up; the next start asks again
                    if (manual) App.toast(a.getString(R.string.up_check_failed, err));
                    return;
                }
                App.prefs().edit().putLong(PREF_AT, System.currentTimeMillis()).apply();
                if (!AppUpdate.newer(r.tag, BuildConfig.VERSION_NAME)) {
                    if (manual) App.toast(a.getString(R.string.up_latest, BuildConfig.VERSION_NAME));
                    return;
                }
                if (!manual && r.tag.equals(App.prefs().getString(PREF_SKIP, ""))) return;
                if (a.isFinishing() || a.isDestroyed()) return;
                offer(a, r);
            });
        });
    }

    private static void offer(Activity a, AppUpdate.Release r) {
        StringBuilder msg = new StringBuilder(a.getString(R.string.up_versions, BuildConfig.VERSION_NAME, r.tag));
        if (r.size > 0) msg.append("\n").append(a.getString(R.string.up_size, r.size / 1048576f));
        if (!r.notes.isEmpty()) {
            msg.append("\n\n").append(r.notes.length() > 2000 ? r.notes.substring(0, 2000) + "…" : r.notes);
        }
        new AlertDialog.Builder(a).setTitle(R.string.up_title).setMessage(msg)
                .setPositiveButton(R.string.up_update, (d, w) -> {
                    if (r.apk.isEmpty()) openPage(a, r);
                    else download(a, r);
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.up_skip, (d, w) ->
                        App.prefs().edit().putString(PREF_SKIP, r.tag).apply())
                .show();
    }

    private static void openPage(Activity a, AppUpdate.Release r) {
        if (!r.page.startsWith("https://")) return;
        BrowserActivity.open(a, r.page);
    }

    // ------------------------------------------------------------------ download and install

    private static void download(Activity a, AppUpdate.Release r) {
        ProgressDialog pd = new ProgressDialog(a);
        pd.setTitle(a.getString(R.string.up_downloading, r.tag));
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setProgressNumberFormat(null);
        pd.setIndeterminate(r.size <= 0);
        pd.setMax(100);
        pd.setCanceledOnTouchOutside(false);
        boolean[] cancelled = {false};
        HttpURLConnection[] conn = {null};
        pd.setButton(DialogInterface.BUTTON_NEGATIVE, a.getString(R.string.cancel), (d, w) -> d.cancel());
        pd.setOnCancelListener(d -> {
            cancelled[0] = true;
            HttpURLConnection c = conn[0];
            if (c != null) App.IO.execute(c::disconnect);
        });
        pd.show();
        App.IO.execute(() -> {
            PackageInstaller installer = a.getPackageManager().getPackageInstaller();
            PackageInstaller.Session session = null;
            String error = null;
            try {
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(BuildConfig.APPLICATION_ID);
                if (r.size > 0) params.setSize(r.size);
                session = installer.openSession(installer.createSession(params));
                HttpURLConnection c = open(r.apk, "application/octet-stream");
                conn[0] = c;
                try {
                    int code = c.getResponseCode();
                    if (code != 200) throw new java.io.IOException("HTTP " + code);
                    long total = r.size > 0 ? r.size : c.getContentLength(), done = 0;
                    int shown = -1;
                    byte[] buf = new byte[32768];
                    try (InputStream in = c.getInputStream();
                         OutputStream out = session.openWrite("update.apk", 0, total > 0 ? total : -1)) {
                        for (int n; (n = in.read(buf)) > 0; ) {
                            if (cancelled[0]) throw new java.io.IOException("cancelled");
                            out.write(buf, 0, n);
                            done += n;
                            int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            if (pct != shown) {
                                shown = pct;
                                App.MAIN.post(() -> pd.setProgress(pct));
                            }
                        }
                        session.fsync(out);
                    }
                    if (total > 0 && done != total) throw new java.io.IOException(done + " / " + total);
                } finally {
                    c.disconnect();
                }
                if (cancelled[0]) throw new java.io.IOException("cancelled");
                listen();
                installing = r;
                installFrom = a;
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                Intent status = new Intent(ACTION_STATUS).setPackage(BuildConfig.APPLICATION_ID);
                session.commit(PendingIntent.getBroadcast(App.I, 1, status, flags).getIntentSender());
                session.close();
                session = null;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (session != null) {
                try {
                    session.abandon();
                } catch (Exception ignored) {
                }
            }
            String err = error;
            App.MAIN.post(() -> {
                try {
                    pd.dismiss();
                } catch (Exception ignored) {
                }
                if (err != null && !cancelled[0]) failed(a, r, err);
            });
        });
    }

    /** The download or the installation did not work: the release's page is the other way to the package. */
    private static void failed(Activity a, AppUpdate.Release r, String why) {
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            App.toast(App.I.getString(R.string.up_failed, why));
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(R.string.up_failed_title)
                .setMessage(a.getString(R.string.up_failed, why))
                .setNegativeButton(R.string.cancel, null);
        if (!r.apk.isEmpty()) b.setPositiveButton(R.string.retry, (d, w) -> download(a, r));
        if (r.page.startsWith("https://")) b.setNeutralButton(R.string.up_open_page, (d, w) -> openPage(a, r));
        b.show();
    }

    /** The installer reports through a broadcast: first that it wants the user's consent, then the outcome. */
    private static void listen() {
        if (receiving) return;
        receiving = true;
        BroadcastReceiver rc = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm == null) return;
                    try {
                        Activity a = installFrom;
                        if (a != null && !a.isFinishing() && !a.isDestroyed()) a.startActivity(confirm);
                        else App.I.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        App.toast(App.I.getString(R.string.up_failed, String.valueOf(e.getMessage())));
                    }
                    return;
                }
                // success never arrives here: the app is replaced and its process ended
                AppUpdate.Release r = installing;
                Activity a = installFrom;
                installing = null;
                installFrom = null;
                if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_FAILURE_ABORTED
                        || r == null) return;
                String why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                failed(a, r, why == null ? String.valueOf(status) : why);
            }
        };
        IntentFilter f = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) App.I.registerReceiver(rc, f, Context.RECEIVER_NOT_EXPORTED);
        else App.I.registerReceiver(rc, f);
    }

    // ------------------------------------------------------------------ http

    private static HttpURLConnection open(String url, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", accept);
        // the API refuses requests without a client name
        c.setRequestProperty("User-Agent", "NovelSniffer/" + BuildConfig.VERSION_NAME);
        return c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url, "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream in = c.getInputStream()) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 2_000_000) throw new java.io.IOException("answer too long");
                }
            }
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
