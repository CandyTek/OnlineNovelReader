package com.rc.novelreader.shelf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.data.Book;
import com.rc.novelreader.data.Chapter;
import com.rc.novelreader.data.Db;
import com.rc.novelreader.data.Events;
import com.rc.novelreader.data.Library;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Foreground service that caches chapters of one or more books in the background. */
public class DownloadService extends Service {
    private static final String ACTION_STOP = "stop", CHANNEL = "download";
    private static final int NOTIFY_ID = 7;
    private static final int WORKERS = 3;

    /** Progress of a running job, readable from the UI. */
    public static final class Job {
        public long bookId;
        public String title = "";
        public final AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
        public int total;
        volatile boolean cancelled;
        /** The site turned us away (verification, "too many requests"). */
        volatile boolean blocked;
    }

    private static final Map<Long, Job> JOBS = new ConcurrentHashMap<>();

    public static Job job(long bookId) {
        return JOBS.get(bookId);
    }

    /** @param count number of chapters starting at index from, or -1 for all remaining. */
    public static void start(Context c, long bookId, int from, int count) {
        Intent i = new Intent(c, DownloadService.class).putExtra("book", bookId).putExtra("from", from).putExtra("count", count);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
    private final AtomicInteger pending = new AtomicInteger();
    private NotificationManager nm;
    private long lastNotify;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel_download),
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build(getString(R.string.app_name), getString(R.string.loading), 0, 0, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFY_ID, n);
        if (intent == null) {
            if (pending.get() == 0) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            for (Job j : JOBS.values()) j.cancelled = true;
            if (pending.get() == 0) stopSelf();
            return START_NOT_STICKY;
        }
        final long bookId = intent.getLongExtra("book", -1);
        final int from = intent.getIntExtra("from", 0), count = intent.getIntExtra("count", -1);
        pending.incrementAndGet();
        jobs.execute(() -> {
            try {
                run(bookId, from, count);
            } catch (Throwable t) {
                android.util.Log.e("Download", "job", t);
            } finally {
                JOBS.remove(bookId);
                Events.post(Events.CACHE, bookId);
                if (pending.decrementAndGet() == 0) {
                    stopForeground(false);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    private void run(long bookId, int from, int count) throws InterruptedException {
        final Book book = Db.book(bookId);
        if (book == null) return;
        List<Chapter> all = Db.chapters(bookId);
        if (all.size() < 3 && !book.linked) {
            // the list may still be incomplete (opened seconds ago): fetch it first
            try {
                Library.refreshToc(book);
                all = Db.chapters(bookId);
            } catch (Exception ignored) {
            }
        }
        List<Chapter> todo = pick(all, from, count);
        if (todo.isEmpty()) {
            App.toast(getString(R.string.nothing_to_cache));
            return;
        }
        App.toast(getString(R.string.caching_started, todo.size()));
        final Job job = new Job();
        job.bookId = bookId;
        job.title = book.title;
        JOBS.put(bookId, job);
        // books without a chapter list grow as chapters are read: keep going until nothing new appears
        for (int round = 0; !todo.isEmpty() && !job.cancelled && round < 20000; round++) {
            job.total += todo.size();
            notifyProgress(job, true);
            batch(book, job, todo);
            if (!book.linked) break;
            todo = pick(Db.chapters(bookId), from, count);
        }
        int ok = job.done.get() - job.failed.get();
        Notification n = build(book.title, job.blocked ? getString(R.string.dl_blocked, book.title, ok)
                : getString(R.string.dl_done, book.title, ok, job.failed.get()), 0, 0, false);
        nm.notify((int) (1000 + bookId), n);
    }

    private static List<Chapter> pick(List<Chapter> all, int from, int count) {
        List<Chapter> todo = new ArrayList<>();
        int end = count < 0 ? all.size() : Math.min(all.size(), from + count);
        for (int i = Math.max(0, from); i < end; i++) if (!all.get(i).cached) todo.add(all.get(i));
        return todo;
    }

    private void batch(final Book book, final Job job, List<Chapter> todo) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(todo.size());
        final AtomicInteger streak = new AtomicInteger();
        for (final Chapter c : todo) {
            workers.execute(() -> {
                try {
                    if (job.cancelled) return;
                    try {
                        Library.loadChapter(book, c, false);
                        streak.set(0);
                    } catch (Throwable t) {
                        job.failed.incrementAndGet();
                        // a verification or "too many requests": every further request deepens the ban
                        if (t instanceof com.rc.novelreader.net.FetchError && ((com.rc.novelreader.net.FetchError) t).blocked()) {
                            job.blocked = true;
                            job.cancelled = true;
                        }
                        // the site is refusing us: stop instead of hammering it
                        if (streak.incrementAndGet() >= 12) job.cancelled = true;
                    }
                    job.done.incrementAndGet();
                    notifyProgress(job, false);
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        if (job.failed.get() > 0 && book.linked) job.cancelled = true;
    }

    private synchronized void notifyProgress(Job job, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotify < 500) return;
        lastNotify = now;
        nm.notify(NOTIFY_ID, build(getString(R.string.dl_title, job.title),
                getString(R.string.dl_progress, job.done.get(), job.total), job.done.get(), job.total, true));
        Events.post(Events.CACHE, job.bookId);
    }

    private Notification build(String title, String text, int done, int total, boolean ongoing) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_book).setContentTitle(title).setContentText(text).setOngoing(ongoing).setOnlyAlertOnce(true);
        b.setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, ShelfActivity.class), PendingIntent.FLAG_IMMUTABLE));
        if (ongoing) {
            b.setProgress(total, done, total == 0);
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, DownloadService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, getString(R.string.stop), stop).build());
        } else {
            b.setAutoCancel(true);
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        workers.shutdownNow();
        jobs.shutdownNow();
        super.onDestroy();
    }
}
