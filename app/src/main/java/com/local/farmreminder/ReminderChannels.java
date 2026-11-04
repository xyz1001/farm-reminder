package com.local.farmreminder;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/** Shared channel selection/defaults for delivery and the user-initiated settings entry. */
final class ReminderChannels {
    static ReminderChannelPolicy.Plan plan(NotificationManager manager) {
        NotificationChannel legacy = manager.getNotificationChannel(ReminderChannelPolicy.LEGACY_ID);
        return ReminderChannelPolicy.select(legacy == null ? null : legacy.getImportance(),
                legacy != null && Build.VERSION.SDK_INT >= 29 && legacy.hasUserSetImportance(),
                legacy != null && legacy.shouldVibrate(),
                manager.getNotificationChannel(ReminderChannelPolicy.HEADS_UP_ID) != null);
    }

    static String settingsChannelId(NotificationManager manager, ReminderChannelPolicy.Plan plan) {
        NotificationChannel legacy = manager.getNotificationChannel(ReminderChannelPolicy.LEGACY_ID);
        return ReminderChannelPolicy.settingsChannelId(legacy == null ? null : legacy.getImportance(), plan);
    }

    static void createIfNeeded(NotificationManager manager, ReminderChannelPolicy.Plan plan) {
        if (!plan.createChannel || plan.channelId == null) return;
        NotificationChannel created = new NotificationChannel(plan.channelId, "成熟提醒·横幅", NotificationManager.IMPORTANCE_HIGH);
        created.setDescription("农作物成熟时请求横幅提醒，无声音；短振动沿用原设置");
        created.setSound(null, null);
        if (plan.vibrate) created.setVibrationPattern(new long[]{0, 200});
        created.enableVibration(plan.vibrate);
        manager.createNotificationChannel(created);
    }

    private ReminderChannels() { }
}
