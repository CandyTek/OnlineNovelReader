package com.rc.novelreader.testing;

import android.app.Instrumentation;
import android.os.Bundle;

import com.rc.novelreader.App;
import com.rc.novelreader.data.Db;

import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the on-device tests with plain JUnit 4 (the usual runner is AndroidX, which this project does not use).
 *
 * <pre>
 * adb -s DEVICE shell am instrument -w [-e class com.rc.novelreader.data.LibraryTest[,...]] \
 *     com.rc.novelreader.test/com.rc.novelreader.testing.SuiteRunner
 * </pre>
 *
 * The tests write books, history and settings into the app's own data, so the runner refuses to start on a
 * device where the app already holds any (clear it first with "pm clear"; never on a phone that is in use).
 */
public class SuiteRunner extends Instrumentation {
    /** Every test class; "-e class" picks some of them by full name. */
    private static final String[] ALL = {
            "com.rc.novelreader.net.HttpTest",
            "com.rc.novelreader.net.PageFetcherTest",
            "com.rc.novelreader.data.DbTest",
            "com.rc.novelreader.data.LibraryTest",
            "com.rc.novelreader.data.LocalBooksTest",
            "com.rc.novelreader.data.BackupTest",
            "com.rc.novelreader.reader.LayoutTest",
            "com.rc.novelreader.reader.ReaderScreenTest",
            "com.rc.novelreader.browser.BrowserScreenTest",
            "com.rc.novelreader.ScreensTest",
    };

    public static Instrumentation I;
    private Bundle args;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        args = arguments == null ? new Bundle() : arguments;
        I = this;
        start();
    }

    private void say(String line) {
        Bundle b = new Bundle();
        b.putString(Instrumentation.REPORT_KEY_STREAMRESULT, line + "\n");
        sendStatus(0, b);
    }

    @Override
    public void onStart() {
        final StringBuilder summary = new StringBuilder();
        int code = 0;
        try {
            if (Db.count("SELECT COUNT(*) FROM books") > 0 || Db.count("SELECT COUNT(*) FROM history") > 0) {
                summary.append("REFUSED: the app holds books or history on this device. The tests write into the app's data;"
                        + " run them only after \"pm clear com.rc.novelreader\" on a test device.\n");
            } else {
                // no request leaves the device: the tests talk to a site inside the app
                App.prefs().edit().putBoolean("app_update", false).commit();
                List<Class<?>> classes = new ArrayList<>();
                String only = args.getString("class");
                for (String n : only == null ? ALL : only.split(",")) classes.add(Class.forName(n.trim()));
                JUnitCore core = new JUnitCore();
                core.addListener(new RunListener() {
                    long at;
                    boolean failed;

                    @Override
                    public void testStarted(Description d) {
                        at = System.currentTimeMillis();
                        failed = false;
                    }

                    @Override
                    public void testFailure(Failure f) {
                        failed = true;
                        StringWriter w = new StringWriter();
                        f.getException().printStackTrace(new PrintWriter(w));
                        String trace = w.toString();
                        say("FAIL " + f.getDescription().getDisplayName() + "\n"
                                + (trace.length() > 3000 ? trace.substring(0, 3000) : trace));
                    }

                    @Override
                    public void testFinished(Description d) {
                        if (!failed) say("ok   " + d.getDisplayName() + "  " + (System.currentTimeMillis() - at) + " ms");
                    }
                });
                Result r = core.run(Request.classes(classes.toArray(new Class<?>[0])));
                summary.append(r.wasSuccessful() ? "OK" : "FAILURES").append(": ").append(r.getRunCount()).append(" tests, ")
                        .append(r.getFailureCount()).append(" failed, ").append(r.getIgnoreCount()).append(" ignored, ")
                        .append(r.getRunTime() / 1000).append(" s\n");
                for (Failure f : r.getFailures()) summary.append("  failed: ").append(f.getDescription().getDisplayName()).append("\n");
                code = r.wasSuccessful() ? -1 : 0;
            }
        } catch (Throwable t) {
            StringWriter w = new StringWriter();
            t.printStackTrace(new PrintWriter(w));
            summary.append("RUNNER ERROR\n").append(w);
        }
        Bundle b = new Bundle();
        b.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n" + summary);
        finish(code, b);
    }
}
