package com.local.farmreminder;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

final class NotificationSettingsNavigator {
    static NotificationSettingsPolicy.State read(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            if (!manager.areNotificationsEnabled()) return NotificationSettingsPolicy.State.APP_BLOCKED;
            String id = ReminderChannels.settingsChannelId(manager, ReminderChannels.plan(manager));
            NotificationChannel channel = id == null ? null : manager.getNotificationChannel(id);
            return NotificationSettingsPolicy.state(true, channel == null ? null : channel.getImportance(),
                    channel != null && channel.shouldVibrate());
        } catch (RuntimeException e) {
            // Only channel/settings queries are in this scope (including OEM binder failures).
            return NotificationSettingsPolicy.State.UNAVAILABLE;
        }
    }

    /** Called only on an explicit tap. Missing channels use the exact delivery defaults. */
    static NotificationSettingsPolicy.Target open(Activity activity) {
        NotificationManager manager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        String id = null;
        try {
            ReminderChannelPolicy.Plan plan = ReminderChannels.plan(manager);
            ReminderChannels.createIfNeeded(manager, plan);
            String selected = ReminderChannels.settingsChannelId(manager, plan);
            if (selected != null && manager.getNotificationChannel(selected) != null) id = selected;
        } catch (RuntimeException e) {
            // A settings/channel operation failed; still offer the app's system settings.
        }
        final String channelId = id;
        return NotificationSettingsPolicy.open(channelId != null, target -> {
            Intent intent;
            switch (target) {
                case CHANNEL:
                    intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())
                            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
                    break;
                case APP:
                    intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
                    break;
                default:
                    intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName()));
            }
            try { activity.startActivity(intent); return true; }
            catch (ActivityNotFoundException | SecurityException e) { return false; }
        });
    }

    private NotificationSettingsNavigator() { }
}
