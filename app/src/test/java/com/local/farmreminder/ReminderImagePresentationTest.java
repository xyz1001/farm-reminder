package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Calendar;
import static org.junit.Assert.*;

public class ReminderImagePresentationTest {
    @Test public void fitsCompletePlateToCardWidthWithoutOldWidthOrHeightCaps() {
        assertArrayEquals(new int[]{244,111},ReminderImagePresentation.fitCard(434,188,244,8,8));
        assertArrayEquals(new int[]{336,151},ReminderImagePresentation.fitCard(434,188,336,8,8));
        assertArrayEquals(new int[]{0,0},ReminderImagePresentation.fitCard(0,188,336,8,8));
    }

    @Test public void widerAndNarrowerLayoutsPreserveRatioAtEachDisplayDensity() {
        for(int density:new int[]{1,2,3}) for(int width:new int[]{244,284,336,500}) {
            int[] size=ReminderImagePresentation.fitCard(434,188,width*density,8*density,8*density);
            assertEquals(width*density,size[0]);
            double expected=(double)188*(size[0]-8*density)/434;
            assertTrue(size[1]-8*density>=expected && size[1]-8*density<expected+1);
        }
        int[] wide=ReminderImagePresentation.fitCard(434,188,1008,24,24);
        int[] narrow=ReminderImagePresentation.fitCard(434,188,732,24,24);
        assertTrue(wide[0]>narrow[0]); assertTrue(wide[1]>narrow[1]);
    }

    @Test public void tinyInvalidAndOverflowDimensionsDoNotProduceNegativeLayoutSizes() {
        assertArrayEquals(new int[]{0,0},ReminderImagePresentation.fitCard(434,188,8,8,8));
        assertArrayEquals(new int[]{0,0},ReminderImagePresentation.fitCard(434,188,-1,0,0));
        assertArrayEquals(new int[]{1,1},ReminderImagePresentation.fitCard(434,188,1,0,0));
        assertArrayEquals(new int[]{0,0},ReminderImagePresentation.fitCard(1,Integer.MAX_VALUE,Integer.MAX_VALUE,0,0));
    }

    @Test public void fullPlateAndLegacyPortraitHaveDistinctCoalescingAndStaleBindingKeys() {
        String ref="a".repeat(64);
        String portrait=ReminderImagePresentation.key(ReminderImagePresentation.Kind.PORTRAIT,ref);
        String plate=ReminderImagePresentation.key(ReminderImagePresentation.Kind.PLATE,ref);
        assertNotEquals(portrait,plate);
        AvatarBindingPolicy bindings=new AvatarBindingPolicy(2);
        long old=bindings.bind(portrait); bindings.request(old,portrait); bindings.clearBindings();
        long fresh=bindings.bind(plate);
        assertFalse(bindings.current(fresh,portrait)); assertTrue(bindings.complete(portrait).isEmpty());
        assertEquals(AvatarBindingPolicy.Request.START,bindings.request(fresh,plate));
    }

    @Test public void nativePictureRequiresExplicitRealReminderPlateAndOtherwiseUsesText() {
        assertEquals(ReminderImagePresentation.Style.BIG_PICTURE,ReminderImagePresentation.style(true,true));
        assertEquals(ReminderImagePresentation.Style.TEXT,ReminderImagePresentation.style(true,false));
        assertEquals(ReminderImagePresentation.Style.TEXT,ReminderImagePresentation.style(false,true));
    }

    @Test public void cachePendingAndHandoffOwnedArgbBudgetStaysBelowTwelveMiB() {
        long bytes = (ReminderImagePresentation.CACHE_ENTRIES + ReminderImagePresentation.REQUEST_LIMIT + 1L)
                * ReminderImagePresentation.PLATE_MAX_PIXELS * 4;
        assertTrue(bytes <= 12L * 1024 * 1024);
    }

    @Test public void selectionAndSignatureUseExplicitPendingPlateNotPortraitOrNames() {
        Reminder a=record("a",1000), b=record("b",1001), fired=record("f",900);
        a.avatarRef="a".repeat(64); a.plateRef="../invalid";
        b.plateRef="b".repeat(64); fired.plateRef="f".repeat(64); fired.fired=true;
        assertEquals(b.plateRef,ReminderImagePresentation.plateRef(Arrays.asList(b,fired,a)));
        String before=ReminderImagePresentation.signature(Arrays.asList(a,b));
        a.plateRef="c".repeat(64); assertNotEquals(before,ReminderImagePresentation.signature(Arrays.asList(a,b)));
        assertEquals(a.plateRef,ReminderImagePresentation.plateRef(Arrays.asList(b,a)));
    }

    @Test public void expandedPictureSummaryPreservesSecondsLeadAndRecordCountWithoutPrivateFields() {
        Calendar local=Calendar.getInstance();local.clear();local.set(2026,Calendar.JANUARY,15,3,4,5);
        Reminder a=record("a",local.getTimeInMillis()),b=record("b",local.getTimeInMillis()+1000); a.friend="private-name";a.crop="private-crop";
        String text=ReminderImagePresentation.summary(Arrays.asList(b,a));
        assertTrue(text.startsWith("2条提醒 · 成熟时间")); assertTrue(text.contains("03:04:05"));
        assertTrue(text.contains("提前1分钟提醒")); assertFalse(text.contains("private"));
        assertEquals(2,ReminderNotificationActions.available(true).length);
        assertEquals(ReminderNotificationActions.Target.APP,ReminderNotificationActions.contentTarget());
    }

    private static Reminder record(String id,long time) {
        Reminder r=new Reminder();r.id=id;r.matureAt=time;r.triggerAt=time-60000;return r;
    }
}
