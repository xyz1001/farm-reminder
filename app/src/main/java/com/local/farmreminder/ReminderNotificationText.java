package com.local.farmreminder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Notification presentation only; all reminder records remain unchanged. */
final class ReminderNotificationText {
    private static final int MAX_EXPANDED_ITEMS = 5;
    private static final int MAX_NAME_CODE_POINTS = 64;

    private ReminderNotificationText() {}

    static String title(int count) {
        return count > 1 ? "农场提醒 · " + count + "条" : "农场提醒";
    }

    static String text(List<Reminder> reminders) {
        if (reminders.isEmpty()) return "暂无成熟提醒";
        if (reminders.size() == 1) return displayName(reminders.get(0)) + "的作物即将成熟";
        return reminders.size() + "条成熟提醒";
    }

    static String expandedText(List<Reminder> reminders) {
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        StringBuilder result = new StringBuilder();
        int shown = Math.min(reminders.size(), MAX_EXPANDED_ITEMS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) result.append('\n');
            Reminder reminder = reminders.get(i);
            result.append(i + 1).append(". ").append(displayName(reminder))
                    .append(" · ").append(time.format(new Date(reminder.matureAt))).append(" 成熟");
        }
        if (reminders.size() > shown) {
            result.append('\n').append("另").append(reminders.size() - shown).append("条");
        }
        return result.toString();
    }

    private static String displayName(Reminder reminder) {
        String name = reminder.friend;
        if (name == null || name.trim().isEmpty()) return "昵称未识别";
        // Bound notification payload without cutting a surrogate pair or changing stored names.
        if (name.codePointCount(0, name.length()) > MAX_NAME_CODE_POINTS) {
            return name.substring(0, name.offsetByCodePoints(0, MAX_NAME_CODE_POINTS)) + "…";
        }
        return name;
    }
}
