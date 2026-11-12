package com.rc.novelreader;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.webkit.WebSettings;
import android.widget.Toast;

import com.rc.novelreader.browser.AdBlocker;
import com.rc.novelreader.data.Library;
import com.rc.novelreader.data.RuleStore;
import com.rc.novelreader.sniff.Sniffer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {
    public static App I;
    /** Shared by the WebView and background fetches so sites see one consistent client. */
    public static volatile String UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    public static final ExecutorService IO = Executors.newCachedThreadPool();
    public static final Sniffer SNIFFER = new Sniffer();

    @Override
    public void onCreate() {
        super.onCreate();
        I = this;
        try {
            // drop the WebView marker: many sites nag "open in app" or serve degraded pages to "wv"
            UA = WebSettings.getDefaultUserAgent(this).replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", "");
        } catch (Throwable ignored) {
        }
        RuleStore.load(this);
        AdBlocker.init(this);
        IO.execute(Library::cleanup);
    }

    public static SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(I);
    }

    public static void toast(final String s) {
        MAIN.post(() -> Toast.makeText(I, s, Toast.LENGTH_SHORT).show());
    }

    public static int dp(float v) {
        return Math.round(v * I.getResources().getDisplayMetrics().density);
    }
}
