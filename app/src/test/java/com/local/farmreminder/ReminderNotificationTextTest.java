package com.local.farmreminder;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ReminderNotificationTextTest {
    @Test public void emptyInputHasNoExpandedLinesOrAvatar() {
        assertEquals("农场提醒", ReminderNotificationText.title(0));
        assertEquals("暂无成熟提醒", ReminderNotificationText.text(Collections.emptyList()));
        assertEquals("", ReminderNotificationText.expandedText(Collections.emptyList()));
        assertEquals("", ReminderNotificationText.avatarRef(Collections.emptyList()));
        assertEquals("", ReminderNotificationText.avatarRef(null));
    }

    @Test public void singleReminderShowsSecondsAndHonestAdvanceNoticeWithoutPrivateFields() {
        Reminder r = reminder("a", "旧昵称🌱", 3, 4, 5);
        List<Reminder> reminders = Collections.singletonList(r);
        assertEquals("农场提醒", ReminderNotificationText.title(1));
        assertEquals("成熟时间 03:04:05 · 提前1分钟提醒", ReminderNotificationText.text(reminders));
        assertEquals("1. 成熟时间 1月15日 03:04:05 · 提前1分钟提醒", ReminderNotificationText.expandedText(reminders));
        assertEquals("旧昵称🌱", r.friend);
        assertEquals("fixture-crop", r.crop);
        assertFalse(r.fired);
    }

    @Test public void namesAndCropsCannotChangePresentationOrCreateMissingNamePlaceholder() {
        Reminder r = reminder("a", null, 3, 4, 5);
        List<Reminder> reminders = Collections.singletonList(r);
        String compact = ReminderNotificationText.text(reminders);
        String expanded = ReminderNotificationText.expandedText(reminders);
        for (String name : Arrays.asList("", " \t\n", "历史昵称", "🌱".repeat(200))) {
            r.friend = name; r.crop = "历史作物";
            assertEquals(compact, ReminderNotificationText.text(reminders));
            assertEquals(expanded, ReminderNotificationText.expandedText(reminders));
        }
        assertFalse(compact.contains("昵称未识别"));
        assertFalse(expanded.contains("历史作物"));
    }

    @Test public void multipleEntriesCountRecordsNotPeopleAndSortWithoutMutatingInput() {
        Reminder later = reminder("b", "同名", 3, 4, 6);
        Reminder earlier = reminder("a", "同名", 3, 4, 5);
        List<Reminder> reminders = Collections.unmodifiableList(Arrays.asList(later, earlier));
        assertEquals("农场提醒 · 2条", ReminderNotificationText.title(2));
        assertEquals("2条提醒 · 最早成熟时间 03:04:05", ReminderNotificationText.text(reminders));
        assertEquals("1. 成熟时间 1月15日 03:04:05 · 提前1分钟提醒\n"
                + "2. 成熟时间 1月15日 03:04:06 · 提前1分钟提醒", ReminderNotificationText.expandedText(reminders));
        assertSame(later, reminders.get(0));
        assertEquals("同名", later.friend);
        assertFalse(later.fired);
        assertEquals(60000L, later.matureAt - later.triggerAt);
    }

    @Test public void expandedPreviewIsBoundedWithoutDroppingRecords() {
        List<Reminder> reminders = new ArrayList<>();
        for (int i = 0; i < 8; i++) reminders.add(reminder("id" + i, "private" + i, 3, 4, i));
        String expanded = ReminderNotificationText.expandedText(reminders);
        assertEquals("8条提醒 · 最早成熟时间 03:04:00", ReminderNotificationText.text(reminders));
        assertEquals(6, expanded.split("\n").length);
        assertTrue(expanded.contains("5. 成熟时间 1月15日 03:04:04 · 提前1分钟提醒\n另3条"));
        assertFalse(expanded.contains("03:04:05"));
        assertFalse(expanded.contains("private"));
        assertFalse(expanded.contains("fixture-crop"));
        assertEquals(8, reminders.size());
    }

    @Test public void zeroAndSecondsLeadDoNotClaimAlreadyMature() {
        Reminder r = reminder("a", "old", 3, 4, 5);
        r.triggerAt = r.matureAt;
        assertEquals("成熟时间 03:04:05 · 成熟时提醒", ReminderNotificationText.text(Collections.singletonList(r)));
        r.triggerAt = r.matureAt - 15000;
        assertEquals("成熟时间 03:04:05 · 提前15秒提醒", ReminderNotificationText.text(Collections.singletonList(r)));
        assertFalse(ReminderNotificationText.expandedText(Collections.singletonList(r)).contains("已成熟"));
    }

    @Test public void avatarUsesEarliestValidPendingReferenceWithStableIdTieBreak() {
        Reminder fired = reminder("f", "old", 3, 4, 1); fired.fired = true; fired.avatarRef = "f".repeat(64);
        Reminder invalid = reminder("a", "old", 3, 4, 2); invalid.avatarRef = "../not-a-key";
        Reminder blank = reminder("b", "old", 3, 4, 2); blank.avatarRef = "";
        Reminder chosen = reminder("c", "old", 3, 4, 3); chosen.avatarRef = "c".repeat(64);
        Reminder tie = reminder("d", "old", 3, 4, 3); tie.avatarRef = "d".repeat(64);
        List<Reminder> input = Collections.unmodifiableList(Arrays.asList(tie, blank, fired, chosen, invalid));
        assertEquals(chosen.avatarRef, ReminderNotificationText.avatarRef(input));
        assertSame(tie, input.get(0));
        assertTrue(fired.fired);
        assertEquals("", ReminderNotificationText.avatarRef(Arrays.asList(fired, invalid, blank)));
    }

    @Test public void avatarNeverDerivesFromNamesOrCropsAndStaysWithinProvidedGroup() {
        Reminder r = reminder("a", "a".repeat(64), 3, 4, 5); r.crop = "b".repeat(64);
        assertEquals("", ReminderNotificationText.avatarRef(Collections.singletonList(r)));
        Reminder outside = reminder("b", "old", 3, 4, 1); outside.avatarRef = "c".repeat(64);
        assertEquals(outside.avatarRef, ReminderNotificationText.avatarRef(Collections.singletonList(outside)));
        assertEquals("", ReminderNotificationText.avatarRef(Collections.singletonList(r)));
    }

    private static Reminder reminder(String id, String friend, int hour, int minute, int second) {
        Calendar localTime = Calendar.getInstance(); localTime.clear();
        localTime.set(2026, Calendar.JANUARY, 15, hour, minute, second);
        Reminder r = new Reminder(); r.id = id; r.crop = "fixture-crop"; r.friend = friend;
        r.matureAt = localTime.getTimeInMillis(); r.triggerAt = r.matureAt - 60000;
        return r;
    }
}
