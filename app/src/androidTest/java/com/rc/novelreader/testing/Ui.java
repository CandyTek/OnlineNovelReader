package com.rc.novelreader.testing;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.SystemClock;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** What a screen test needs: start an activity, act on the main thread, wait for something to become true. */
public final class Ui {
    private Ui() {
    }

    @SuppressWarnings("unchecked")
    public static <T extends Activity> T launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        T a = (T) SuiteRunner.I.startActivitySync(i);
        SuiteRunner.I.waitForIdleSync();
        return a;
    }

    public static <T extends Activity> T launch(Class<T> c) {
        return launch(new Intent(SuiteRunner.I.getTargetContext(), c));
    }

    public static void main(Runnable r) {
        SuiteRunner.I.runOnMainSync(r);
    }

    public static <V> V get(final Callable<V> c) {
        final AtomicReference<V> out = new AtomicReference<>();
        final AtomicReference<Throwable> err = new AtomicReference<>();
        SuiteRunner.I.runOnMainSync(() -> {
            try {
                out.set(c.call());
            } catch (Throwable t) {
                err.set(t);
            }
        });
        if (err.get() != null) throw new AssertionError("on the main thread", err.get());
        return out.get();
    }

    /** Asks on the main thread every 100 ms. @return whether it became true in time */
    public static boolean waitFor(long ms, Callable<Boolean> what) {
        long end = SystemClock.uptimeMillis() + ms;
        while (true) {
            if (Boolean.TRUE.equals(get(what))) return true;
            if (SystemClock.uptimeMillis() > end) return false;
            SystemClock.sleep(100);
        }
    }

    public static void sleep(long ms) {
        SystemClock.sleep(ms);
    }

    /** Turns the screen to and fro; ends as the user had it. */
    public static void rotate(final Activity a, int times) {
        for (int i = 0; i < times; i++) {
            final int o = i % 2 == 0 ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            main(() -> a.setRequestedOrientation(o));
            sleep(700);
            SuiteRunner.I.waitForIdleSync();
        }
        main(() -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        sleep(700);
        SuiteRunner.I.waitForIdleSync();
    }

    public static void finish(final Activity a) {
        if (a == null) return;
        main(a::finish);
        sleep(500);
        SuiteRunner.I.waitForIdleSync();
    }
}
