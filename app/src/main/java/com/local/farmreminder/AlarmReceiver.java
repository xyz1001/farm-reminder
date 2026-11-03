package com.local.farmreminder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import java.util.List;

public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Uri data = intent.getData();
        if (data == null || !"farmreminder".equals(data.getScheme()) || data.getPathSegments().size() != 1) return;
        String id = data.getLastPathSegment();
        if ("alarm".equals(data.getAuthority())) {
            if ("test".equals(id)) { try { notify(context, id, null); } catch (RuntimeException ignored) { } }
            else new AlarmScheduler(context).rescheduleAll(); // Legacy broadcasts must never dispatch individually.
            return;
        }
        if (!"group".equals(data.getAuthority())) return;
        ReminderStore store = new ReminderStore(context);
        synchronized (ReminderStore.LOCK) { AlarmScheduler.delivered(id); AlarmDispatchPolicy.dispatch(ReminderStore.LOCK, new AlarmDispatchPolicy.Adapter() {
            public boolean ready() { return store.ready(); }
            public List<Reminder> list() { return store.list(); }
            public boolean notifyGroup(ReminderGrouping.Group group) { return AlarmReceiver.notify(context, group.identity, group.reminders); }
            public boolean markFired(List<Reminder> reminders) { return store.markGroupFired(reminders); }
            public void reconcile() { new AlarmScheduler(context).rescheduleAll(); }
        }, id, System.currentTimeMillis()); }
    }

    private static boolean notify(Context context, String id, List<Reminder> reminders) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        if (!manager.areNotificationsEnabled()) return false;
        NotificationChannel legacy = manager.getNotificationChannel(ReminderChannelPolicy.LEGACY_ID);
        ReminderChannelPolicy.Plan plan = ReminderChannelPolicy.select(
                legacy == null ? null : legacy.getImportance(),
                legacy != null && Build.VERSION.SDK_INT >= 29 && legacy.hasUserSetImportance(),
                legacy != null && legacy.shouldVibrate(),
                manager.getNotificationChannel(ReminderChannelPolicy.HEADS_UP_ID) != null);
        if (plan.channelId == null) return false;
        if (plan.createChannel) {
            NotificationChannel created = new NotificationChannel(plan.channelId, "成熟提醒·横幅", NotificationManager.IMPORTANCE_HIGH);
            created.setDescription("农作物成熟时请求横幅提醒，无声音；短振动沿用原设置");
            created.setSound(null, null);
            if (plan.vibrate) created.setVibrationPattern(new long[]{0, 200});
            created.enableVibration(plan.vibrate);
            manager.createNotificationChannel(created);
        }
        NotificationChannel channel = manager.getNotificationChannel(plan.channelId);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        boolean canOpenWechat = launch != null;
        if (launch == null) launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = reminders == null ? "农场提醒" : ReminderNotificationText.title(reminders.size());
        String text = reminders == null ? "测试提醒" : ReminderNotificationText.text(reminders);
        Notification.Builder builder = new Notification.Builder(context, plan.channelId).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true);
        if (canOpenWechat) builder.addAction(new Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_menu_view), "打开微信", open).build());
        if (reminders != null) builder.setStyle(new Notification.BigTextStyle().bigText(ReminderNotificationText.expandedText(reminders)));
        manager.notify(id.hashCode(), builder.build());
        return true;
    }
}
