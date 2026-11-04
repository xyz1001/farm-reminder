package com.local.farmreminder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;
import java.util.EnumMap;
import java.util.List;

/** Synchronous native presentation only; no asset I/O or store locks. Caller retains bitmap ownership. */
final class ReminderNotifier {
    static boolean post(Context context, String id, List<Reminder> reminders, Bitmap preparedAvatar) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        if (!manager.areNotificationsEnabled()) return false;
        ReminderChannelPolicy.Plan plan = ReminderChannels.plan(manager);
        if (plan.channelId == null) return false;
        ReminderChannels.createIfNeeded(manager, plan);
        NotificationChannel channel = manager.getNotificationChannel(plan.channelId);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;

        Intent wechat = context.getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        Intent app = new Intent(context, MainActivity.class)
                .setAction("com.local.farmreminder.OPEN_REMINDER_APP")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (wechat != null) wechat.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        EnumMap<ReminderNotificationActions.Target, PendingIntent> actions = new EnumMap<>(ReminderNotificationActions.Target.class);
        for (ReminderNotificationActions.Target target : ReminderNotificationActions.available(wechat != null)) {
            Intent destination = target == ReminderNotificationActions.Target.WECHAT ? wechat : app;
            actions.put(target, PendingIntent.getActivity(context, target.requestCode, destination,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        Notification.Builder builder = new Notification.Builder(context, plan.channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(reminders == null ? "农场提醒" : ReminderNotificationText.title(reminders.size()))
                .setContentText(reminders == null ? "测试提醒" : ReminderNotificationText.text(reminders))
                .setContentIntent(actions.get(ReminderNotificationActions.contentTarget()))
                .setAutoCancel(true);
        for (ReminderNotificationActions.Target target : ReminderNotificationActions.available(wechat != null)) {
            int icon = target == ReminderNotificationActions.Target.WECHAT
                    ? android.R.drawable.ic_menu_view : android.R.drawable.ic_menu_myplaces;
            builder.addAction(new Notification.Action.Builder(Icon.createWithResource(context, icon),
                    target.label, actions.get(target)).build());
        }
        if (reminders != null) {
            builder.setStyle(new Notification.BigTextStyle().bigText(ReminderNotificationText.expandedText(reminders)));
            // Keep a small local avatar legible in the native template, not a stretched BigPicture poster.
            if (preparedAvatar != null && !preparedAvatar.isRecycled()) builder.setLargeIcon(preparedAvatar);
        }
        manager.notify(ReminderNotificationActions.TAG, id.hashCode(), builder.build());
        return true;
    }

    private ReminderNotifier() { }
}
