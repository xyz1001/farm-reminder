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
        Reminder b = record("b", 1010000, "乙", false);
        Reminder c = record("c", 1010001, "丙", false);
        Reminder d = record("d", 1020000, "丁", false);
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
        Reminder b = record("b", 609000, "乙", false);
        Reminder c = record("c", 618000, "丙", false);
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
        assertEquals("好友 · 2 条提醒", ReminderListGroups.summary(ui.pending.get(0)));
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
        assertEquals("好友 · 4 条提醒", ReminderListGroups.summary(ui.pending.get(0)));
        assertEquals("待提醒 1 组", ui.title());
    }

    @Test public void deletingOneReplansWithoutRemovingOtherRecords() {
        Reminder a = record("a", 600000, "甲", false);
        Reminder b = record("b", 609000, "乙", false);
        Reminder c = record("c", 618000, "丙", false);
        List<Reminder> records = new ArrayList<>(Arrays.asList(a, b, c));
        assertEquals(2, new ReminderListGroups(records).pending.size());
        records.remove(a);
        ReminderListGroups ui = new ReminderListGroups(records);
        assertEquals(1, ui.pending.size());
        assertEquals(Arrays.asList(b, c), ui.pending.get(0).reminders);
        assertEquals(2, records.size());
    }

    @Test public void emptySummaryNeverNeedsUsernamesAndLeadIsReadable() {
        ReminderListGroups empty = new ReminderListGroups(Collections.emptyList());
        assertEquals("待提醒 0 组", empty.title());
        assertTrue(empty.pending.isEmpty()); assertTrue(empty.history.isEmpty());
        Reminder a = record("a", 600000, null, false);
        Reminder b = record("b", 600000, "  ", false);
        ReminderListGroups ui = new ReminderListGroups(Arrays.asList(a, b));
        assertEquals("好友 · 2 条提醒", ReminderListGroups.summary(ui.pending.get(0)));
        assertEquals("提前 1 分钟提醒", ReminderListGroups.lead(a));
        a.triggerAt = a.matureAt;
        assertEquals("成熟时提醒", ReminderListGroups.lead(a));
    }

    @Test public void photoSelectionUsesEarliestValidPendingWithinGroupOnly() {
        Reminder fired = record("f", 599999, "old", true); fired.avatarRef = "f".repeat(64);
        Reminder invalid = record("a", 600000, "old", false); invalid.avatarRef = "../invalid";
        Reminder b = record("b", 600001, "old", false); b.avatarRef = "b".repeat(64);
        Reminder c = record("c", 600001, "old", false); c.avatarRef = "c".repeat(64);
        Reminder outside = record("outside", 900000, "old", false); outside.avatarRef = "d".repeat(64);
        ReminderListGroups ui = new ReminderListGroups(Arrays.asList(outside, c, b, invalid, fired));
        assertEquals(b.avatarRef, ReminderListGroups.avatarRef(ui.pending.get(0)));
        assertEquals(outside.avatarRef, ReminderListGroups.avatarRef(ui.pending.get(1)));
        ReminderGrouping.Group group = new ReminderGrouping.Group(Arrays.asList(c, fired, b, invalid));
        assertEquals(b.avatarRef, ReminderListGroups.avatarRef(group));
        assertEquals("", ReminderListGroups.avatarRef(new ReminderGrouping.Group(Arrays.asList(fired, invalid))));
        assertEquals("好友 · 4 条提醒", ReminderListGroups.summary(group));
    }

    @Test public void attachingOrChangingPhotoChangesSignatureButLegacyNicknameDoesNot() {
        Reminder r = record("a", 600000, "old", false);
        List<Reminder> records = Collections.singletonList(r);
        String empty = ReminderListGroups.signature(records, 0);
        r.avatarRef = "a".repeat(64);
        String attached = ReminderListGroups.signature(records, 0);
        assertNotEquals(empty, attached);
        r.avatarRef = "b".repeat(64);
        String changed = ReminderListGroups.signature(records, 0);
        assertNotEquals(attached, changed);
        r.friend = "different old name";
        assertEquals(changed, ReminderListGroups.signature(records, 0));
        assertNotEquals(changed, ReminderListGroups.signature(records, r.triggerAt));
    }

    @Test public void signatureIncludesOriginalRowIdentityButIgnoresLegacyCrop() {
        Reminder r = record("a", 600000, "old", false);
        List<Reminder> records = Collections.singletonList(r);
        String original = ReminderListGroups.signature(records, 0);
        r.id = "another";
        assertNotEquals(original, ReminderListGroups.signature(records, 0));
        String renamed = ReminderListGroups.signature(records, 0);
        r.crop = "crop";
        assertEquals(renamed, ReminderListGroups.signature(records, 0));
        r.crop = null;
        assertEquals(renamed, ReminderListGroups.signature(records, 0));
    }

    @Test public void mixedLegacyCropsDoNotAffectSummarySignatureOrPhotoSelection() {
        Reminder a = record("a", 600000, "old", false);
        Reminder b = record("b", 600001, "old", false);
        Reminder c = record("c", 600002, "old", false);
        a.avatarRef = "a".repeat(64);
        List<Reminder> records = Arrays.asList(a, b, c);
        String signature = ReminderListGroups.signature(records, 0);
        a.crop = "旧作物一"; b.crop = "旧作物二"; c.crop = "";
        ReminderListGroups ui = new ReminderListGroups(records);
        assertEquals("好友 · 3 条提醒", ReminderListGroups.summary(ui.pending.get(0)));
        assertEquals(signature, ReminderListGroups.signature(records, 0));
        assertEquals(a.avatarRef, ReminderListGroups.avatarRef(ui.pending.get(0)));
        assertEquals("旧作物一", a.crop); // Presentation never rewrites compatible historical data.
        a.avatarRef = "b".repeat(64);
        assertNotEquals(signature, ReminderListGroups.signature(records, 0));
    }
}
