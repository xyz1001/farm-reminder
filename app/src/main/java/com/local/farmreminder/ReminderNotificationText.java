package com.local.farmreminder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Notification presentation only; all reminder records remain unchanged. */
final class ReminderNotificationText {
    private static final int MAX_EXPANDED_ITEMS = 5;

    private ReminderNotificationText() {}

    static String title(int count) {
        return count > 1 ? "农场提醒 · " + count + "条" : "农场提醒";
    }

    static String title(int count, NotificationIdentityLabel identity) {
        String label = identityText(identity);
        // The validated name is bounded to 64 code points. Let native layout ellipsize it;
        // manual UTF-16/code-point slicing could cut an emoji ZWJ/variation sequence.
        return label + (count > 1 ? " · " + count + "条提醒" : " · 农场提醒");
    }

    static String text(List<Reminder> reminders, NotificationIdentityLabel identity) {
        // Identity belongs in the title; keep maturity time readable in the compact body.
        return text(reminders);
    }

    static String expandedText(List<Reminder> reminders, NotificationIdentityLabel identity) {
        String detail = expandedText(reminders);
        if (reminders.isEmpty()) return detail;
        return identityText(identity) + "\n" + detail;
    }

    static String text(List<Reminder> reminders) {
        if (reminders.isEmpty()) return "暂无成熟提醒";
        Reminder first = ordered(reminders).get(0);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(first.matureAt));
        if (reminders.size() == 1) return "成熟时间 " + time;
        return reminders.size() + "条提醒 · 最早成熟时间 " + time;
    }

    static String expandedText(List<Reminder> reminders) {
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
        List<Reminder> ordered = ordered(reminders);
        StringBuilder result = new StringBuilder();
        int shown = Math.min(reminders.size(), MAX_EXPANDED_ITEMS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) result.append('\n');
            Reminder reminder = ordered.get(i);
            result.append(i + 1).append(". 成熟时间 ").append(time.format(new Date(reminder.matureAt)));
        }
        if (reminders.size() > shown) {
            result.append('\n').append("另").append(reminders.size() - shown).append("条");
        }
        return result.toString();
    }

    static String avatarRef(List<Reminder> reminders) {
        if (reminders == null) return "";
        for (Reminder reminder : ordered(reminders)) {
            String ref = AvatarRefPolicy.normalize(reminder.avatarRef);
            if (!reminder.fired && !ref.isEmpty()) return ref;
        }
        return "";
    }

    private static List<Reminder> ordered(List<Reminder> reminders) {
        List<Reminder> ordered = new ArrayList<>(reminders);
        ordered.sort(Comparator.comparingLong((Reminder r) -> r.matureAt).thenComparing(r -> r.id));
        return ordered;
    }

    private static String identityText(NotificationIdentityLabel identity) {
        String label = identity == null ? "" : identity.text();
        return label.isEmpty() ? "好友" : label;
    }
}
