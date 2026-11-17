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

    /**
     * Makes the keypad's Enter do in a one-line field what the main Enter does: the field's own action (go,
     * search). Older systems do not treat the key as Enter at all, newer ones hand it to the listener under
     * its own key code, which the listeners written for Enter do not recognise.
     */
    public static void numpadEnter(final android.widget.TextView field) {
        field.setOnKeyListener((v, code, e) -> {
            if (code != android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                int action = field.getImeOptions() & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                field.onEditorAction(action == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
                        ? android.view.inputmethod.EditorInfo.IME_ACTION_GO : action);
            }
            // the release too: left to the field it would move the focus on
            return true;
        });
    }
}
