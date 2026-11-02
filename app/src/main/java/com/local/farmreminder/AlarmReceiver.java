package com.local.farmreminder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "maturity_short_vibration";

    @Override public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra("id"); if (id == null) return;
        ReminderStore store = new ReminderStore(context);
        Reminder r = null;
        if (!"test".equals(id)) {
            for (Reminder item : store.list()) if (id.equals(item.id) && !item.fired) { r = item; break; }
        }
        if (!"test".equals(id) && r == null) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            if (!manager.areNotificationsEnabled()) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "成熟提醒·短振动", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("农作物成熟时无声短振动一次");
            channel.setSound(null, null);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 200});
            manager.createNotificationChannel(channel);
            channel = manager.getNotificationChannel(CHANNEL_ID);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("农场提醒").setContentText(r == null ? "测试提醒" : r.friend + "的农作物即将成熟")
                    .setContentIntent(open).setAutoCancel(true).build();
            manager.notify(id.hashCode(), n);
            if (r != null) store.markFired(id);
        } catch (SecurityException ignored) { }
    }
}
