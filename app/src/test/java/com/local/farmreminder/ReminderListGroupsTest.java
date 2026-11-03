package com.local.farmreminder;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ReminderListGroupsTest {
    private Reminder record(String id, long time, String friend, boolean fired) {
        Reminder r = new Reminder();
        r.id = id; r.matureAt = time; r.triggerAt = time - 60000;
        r.friend = friend; r.fired = fired;
        return r;
    }

    @Test public void strictWindowAndFixedAnchorMatchDeliveryPlan() {
        Reminder a = record("a", 1000000, "甲", false);
        Reminder b = record("b", 1119999, "乙", false);
        Reminder c = record("c", 1120000, "丙", false);
        Reminder d = record("d", 1239998, "丁", false);
        ReminderListGroups ui = new ReminderListGroups(Arrays.asList(d, c, b, a));
        assertEquals(2, ui.pending.size());
        assertEquals(Arrays.asList(a, b), ui.pending.get(0).reminders);
        assertEquals(Arrays.asList(c, d), ui.pending.get(1).reminders);
        assertEquals("待提醒 2 组", ui.title());
        List<ReminderGrouping.Group> delivery = ReminderGrouping.plan(Arrays.asList(d, c, b, a));
        for (int i = 0; i < delivery.size(); i++)
            assertEquals(delivery.get(i).identity, ui.pending.get(i).identity);
    }

    @Test public void neverChainsAcrossFirstAnchor() {
        Reminder a = record("a", 600000, "甲", false);
        Reminder b = record("b", 690000, "乙", false);
        Reminder c = record("c", 780000, "丙", false);
        ReminderListGroups ui = new ReminderListGroups(Arrays.asList(c, b, a));
        assertEquals(Arrays.asList(a, b), ui.pending.get(0).reminders);
        assertEquals(Collections.singletonList(c), ui.pending.get(1).reminders);
    }

    @Test public void friendsAndAllRecordsRemainWhileHistoryIsSeparate() {
        Reminder a = record("a", 600000, "甲", false);
        Reminder b = record("b", 600001, "乙", false);
        Reminder old = record("old", 600000, "甲", true);
        List<Reminder> records = Arrays.asList(old, b, a);
        ReminderListGroups ui = new ReminderListGroups(records);
        assertEquals(Arrays.asList(a, b), ui.pending.get(0).reminders);
        assertEquals(Collections.singletonList(old), ui.history);
        assertEquals("2 条提醒 · 甲、乙", ReminderListGroups.summary(ui.pending.get(0)));
        assertEquals(3, records.size());
        assertTrue(old.fired);
        assertFalse(a.fired);
    }

    @Test public void tiesAreStableAndSummaryCountsRecordsNotFriends() {
        Reminder a = record("a", 600000, "甲", false);
        Reminder b = record("b", 600000, "甲", false);
        Reminder c = record("c", 600000, "乙", false);
        Reminder d = record("d", 600000, "丙", false);
        ReminderListGroups ui = new ReminderListGroups(Arrays.asList(d, b, c, a));
        assertEquals(Arrays.asList(a, b, c, d), ui.pending.get(0).reminders);
        assertEquals("4 条提醒 · 甲、乙 等", ReminderListGroups.summary(ui.pending.get(0)));
        assertEquals("待提醒 1 组", ui.title());
    }

    @Test public void deletingOneReplansWithoutRemovingOtherRecords() {
        Reminder a = record("a", 600000, "甲", false);
        Reminder b = record("b", 690000, "乙", false);
        Reminder c = record("c", 780000, "丙", false);
        List<Reminder> records = new ArrayList<>(Arrays.asList(a, b, c));
        assertEquals(2, new ReminderListGroups(records).pending.size());
        records.remove(a);
        ReminderListGroups ui = new ReminderListGroups(records);
        assertEquals(1, ui.pending.size());
        assertEquals(Arrays.asList(b, c), ui.pending.get(0).reminders);
        assertEquals(2, records.size());
    }

    @Test public void emptyUnknownNickAndLeadAreReadable() {
        ReminderListGroups empty = new ReminderListGroups(Collections.emptyList());
        assertEquals("待提醒 0 组", empty.title());
        assertTrue(empty.pending.isEmpty()); assertTrue(empty.history.isEmpty());
        Reminder a = record("a", 600000, null, false);
        Reminder b = record("b", 600000, "  ", false);
        ReminderListGroups ui = new ReminderListGroups(Arrays.asList(a, b));
        assertEquals("2 条提醒 · 昵称未识别", ReminderListGroups.summary(ui.pending.get(0)));
        assertEquals("提前 1 分钟提醒", ReminderListGroups.lead(a));
        a.triggerAt = a.matureAt;
        assertEquals("成熟时提醒", ReminderListGroups.lead(a));
    }
}
