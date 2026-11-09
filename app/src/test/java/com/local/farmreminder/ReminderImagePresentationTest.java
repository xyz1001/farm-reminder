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

    @Test public void absentOrFailedPixelsAlwaysKeepTheSameWidePlaceholder() {
        int[] expected = {336,151};
        assertArrayEquals(expected, ReminderImagePresentation.listSlot(0,0,336,8,8));
        assertArrayEquals(expected, ReminderImagePresentation.listSlot(-1,-1,336,8,8));
        assertArrayEquals(expected, ReminderImagePresentation.listSlot(434,0,336,8,8));
        assertArrayEquals(expected, ReminderImagePresentation.listSlot(0,188,336,8,8));
    }

    @Test public void oldAvatarOnlyAndEmptyRecordsDoNotSelectAPortraitForTheListSlot() {
        Reminder empty=record("empty",1000), old=record("old",1001), invalid=record("invalid",1002);
        old.avatarRef="a".repeat(64); invalid.plateRef="../invalid";
        for (Reminder r : Arrays.asList(empty,old,invalid)) {
            assertEquals("", ReminderImagePresentation.plateRef(Arrays.asList(r)));
            assertArrayEquals(new int[]{336,151}, ReminderImagePresentation.listSlot(0,0,336,8,8));
        }
        old.fired=true;
        assertEquals("", ReminderImagePresentation.plateRef(Arrays.asList(old)));
    }

    @Test public void placeholderRespondsToFirstMeasureResizeAndDensityWithoutNegativeDimensions() {
        assertArrayEquals(new int[]{0,0},ReminderImagePresentation.listSlot(0,0,0,8,8));
        for (int density : new int[]{1,2,3}) for (int width : new int[]{244,336,640}) {
            int[] size=ReminderImagePresentation.listSlot(0,0,width*density,8*density,8*density);
            assertEquals(width*density,size[0]);
            double expected=(width-8)*density/2.3;
            assertTrue(size[1]-8*density>=expected-0.000001);
            assertTrue(size[1]-8*density<expected+1.000001);
            assertTrue(size[0]>size[1]);
        }
        assertArrayEquals(new int[]{0,0},ReminderImagePresentation.listSlot(0,0,8,8,8));
        assertArrayEquals(new int[]{1,1},ReminderImagePresentation.listSlot(0,0,1,0,0));
    }

    @Test public void loadedAndMissingSlotsHaveIdenticalFramesRegardlessOfBitmapAspect() {
        for (int density : new int[]{1,2,3}) for (int width : new int[]{1,8,244,336,640}) {
            int available=width*density, inset=8*density;
            int[] empty=ReminderImagePresentation.listSlot(0,0,available,inset,inset);
            for (int[] bitmap : new int[][]{{434,188},{443,190},{900,300},{60,60},{100,400}}) {
                assertArrayEquals(empty,
                        ReminderImagePresentation.listSlot(bitmap[0],bitmap[1],available,inset,inset));
            }
        }
        assertArrayEquals(new int[]{0,0},ReminderImagePresentation.listSlot(439,179,0,8,8));
        ReminderImagePresentation.listSlot(900,300,640,8,8);
        assertArrayEquals(new int[]{336,151},ReminderImagePresentation.listSlot(0,0,336,8,8));
    }

    @Test public void stalePlateCompletionDoesNotOwnAReboundEmptySlot() {
        AvatarBindingPolicy policy=new AvatarBindingPolicy(4);
        String oldKey=ReminderImagePresentation.key(ReminderImagePresentation.Kind.PLATE,"a".repeat(64));
        long old=policy.bind(oldKey); policy.request(old,oldKey);
        String emptyKey=ReminderImagePresentation.key(ReminderImagePresentation.Kind.PLATE,"");
        long fresh=policy.bind(emptyKey);
        assertFalse(policy.complete(oldKey).contains(fresh));
        assertFalse(policy.current(fresh,oldKey)); assertTrue(policy.current(fresh,emptyKey));
        policy.clearBindings();
        assertFalse(policy.current(fresh,emptyKey));
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

    @Test public void selectionAndSignatureUseDisplayPlateIncludingFiredNotPortraitOrNames() {
        Reminder a=record("a",1000), b=record("b",1001), fired=record("f",900);
        a.avatarRef="a".repeat(64); a.plateRef="../invalid";
        b.plateRef="b".repeat(64); fired.plateRef="f".repeat(64); fired.fired=true;
        assertEquals(fired.plateRef,ReminderImagePresentation.plateRef(Arrays.asList(b,fired,a)));
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
