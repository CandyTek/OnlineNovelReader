package com.rc.novelreader.reader;

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

import com.rc.novelreader.R;

/**
 * Keeps the app alive while the book is read aloud with the screen off (a cached app is frozen by the system
 * and would stop after the passage it was on). It does nothing itself: the reader's {@link Speaker} talks.
 */
public class SpeakService extends Service {
    private static final String CHANNEL = "speak", ACTION_STOP = "stop";
    private static final int NOTIFY_ID = 3;

    static void start(Context c) {
        try {
            Intent i = new Intent(c, SpeakService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException ignored) {
            // not allowed from the background: speech still works while the reader is on screen
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, SpeakService.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                    new NotificationChannel(CHANNEL, getString(R.string.read_aloud), NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_book).setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.read_aloud)).setOngoing(true).setOnlyAlertOnce(true);
        b.setContentIntent(PendingIntent.getActivity(this, 0,
                new Intent(this, ReaderActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SpeakService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, getString(R.string.stop), stop).build());
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFY_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFY_ID, b.build());
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || ACTION_STOP.equals(intent.getAction()) || Speaker.current == null) {
            if (Speaker.current != null) Speaker.current.stop();
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
