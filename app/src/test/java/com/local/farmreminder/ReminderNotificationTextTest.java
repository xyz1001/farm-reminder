package com.local.farmreminder;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ReminderNotificationTextTest {
    @Test public void emptyInputHasNoExpandedLines() {
        assertEquals("农场提醒", ReminderNotificationText.title(0));
        assertEquals("暂无成熟提醒", ReminderNotificationText.text(Collections.emptyList()));
        assertEquals("", ReminderNotificationText.expandedText(Collections.emptyList()));
    }

    @Test public void singleReminderKeepsRealNickname() {
        List<Reminder> reminders = Collections.singletonList(reminder("123🌱", 3, 4, 5));
        assertEquals("农场提醒", ReminderNotificationText.title(1));
        assertEquals("123🌱的作物即将成熟", ReminderNotificationText.text(reminders));
        assertEquals("1. 123🌱 · 03:04:05 成熟", ReminderNotificationText.expandedText(reminders));
    }

    @Test public void unknownNamesUseFallback() {
        for (String name : Arrays.asList(null, "", " \t\n")) {
            List<Reminder> reminders = Collections.singletonList(reminder(name, 3, 4, 5));
            assertEquals("昵称未识别的作物即将成熟", ReminderNotificationText.text(reminders));
            assertEquals("1. 昵称未识别 · 03:04:05 成熟", ReminderNotificationText.expandedText(reminders));
        }
    }

    @Test public void multipleEntriesCountRecordsNotPeopleAndKeepOrder() {
        Reminder later = reminder("示例甲", 3, 4, 6);
        Reminder earlier = reminder("示例甲", 3, 4, 5);
        List<Reminder> reminders = Collections.unmodifiableList(Arrays.asList(later, earlier));
        assertEquals("农场提醒 · 2条", ReminderNotificationText.title(2));
        assertEquals("2条成熟提醒", ReminderNotificationText.text(reminders));
        assertEquals("1. 示例甲 · 03:04:06 成熟\n2. 示例甲 · 03:04:05 成熟",
                ReminderNotificationText.expandedText(reminders));
        assertSame(later, reminders.get(0));
        assertEquals("示例甲", later.friend);
        assertFalse(later.fired);
        assertEquals(1L, later.triggerAt);
        assertEquals(reminder("示例甲", 3, 4, 6).matureAt, later.matureAt);
    }

    @Test public void expandedPreviewIsBoundedWithoutDroppingRecords() {
        List<Reminder> reminders = new ArrayList<>();
        for (int i = 0; i < 8; i++) reminders.add(reminder("示例" + i, 3, 4, i));
        String expanded = ReminderNotificationText.expandedText(reminders);
        assertEquals("8条成熟提醒", ReminderNotificationText.text(reminders));
        assertEquals(6, expanded.split("\n").length);
        assertTrue(expanded.contains("5. 示例4 · 03:04:04 成熟\n另3条"));
        assertFalse(expanded.contains("示例5"));
        assertFalse(expanded.contains("fixture-id"));
        assertFalse(expanded.contains("fixture-crop"));
        assertEquals(8, reminders.size());
    }

    @Test public void longNameIsOnlyShortenedForPresentationWithoutSplittingEmoji() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 70; i++) name.append("🌱");
        Reminder reminder = reminder(name.toString(), 3, 4, 5);
        String text = ReminderNotificationText.text(Collections.singletonList(reminder));
        assertEquals(name.substring(0, 128) + "…的作物即将成熟", text);
        assertEquals(name.toString(), reminder.friend);
    }

    private static Reminder reminder(String friend, int hour, int minute, int second) {
        Calendar localTime = Calendar.getInstance();
        localTime.clear();
        localTime.set(2026, Calendar.JANUARY, 15, hour, minute, second);
        Reminder reminder = new Reminder();
        reminder.id = "fixture-id";
        reminder.crop = "fixture-crop";
        reminder.friend = friend;
        reminder.matureAt = localTime.getTimeInMillis();
        reminder.triggerAt = 1L;
        return reminder;
    }
}
