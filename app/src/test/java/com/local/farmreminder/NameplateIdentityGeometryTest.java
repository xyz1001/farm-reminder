package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class NameplateIdentityGeometryTest {
    private static NotificationIdentityParser.TextRow row(OcrLine line, OcrLine... elements) {
        return new NotificationIdentityParser.TextRow(line, Arrays.asList(elements));
    }
    @Test public void boundsCoverObservedRowsAndExcludeExperienceFromIsolatedCrop() {
        for (int[] size : new int[][]{{112,50},{113,51},{448,200},{436,174},{218,87}})
            assertNotNull(NameplateIdentityGeometry.of(size[0],size[1]));
        for (int[] size : new int[][]{{480,100},{112,40},{0,50},{112,0}})
            assertNull(NameplateIdentityGeometry.of(size[0],size[1]));
        int[] a=NotificationBadgePolicy.cropRegion(NameplateIdentityGeometry.of(436,174).badge,436,174);
        assertTrue(a[0]<132 && a[1]<81 && a[2]>205 && a[2]<219 && a[3]>121);
        int[] b=NotificationBadgePolicy.cropRegion(NameplateIdentityGeometry.of(448,200).badge,448,200);
        assertTrue(b[0]<134 && b[1]<106 && b[2]>208 && b[2]<226 && b[3]>146);
    }
    @Test public void observedMergedRowsKeepNameButLeadingZeroNeverBecomesLevel() {
        checkObserved(436,174,new OcrLine("示例",190,40,334,74),
                new OcrLine("05462万/156万",132,81,390,127),new OcrLine("054",132,81,205,121),
                new OcrLine("62万/156万",219,84,390,127));
        checkObserved(448,200,new OcrLine("示例",198,52,398,97),
                new OcrLine("03030万/60万",134,106,395,149),new OcrLine("030",134,106,208,146),
                new OcrLine("30万/60万",226,108,395,148));
    }
    private static void checkObserved(int w,int h,OcrLine name,OcrLine merged,OcrLine number,OcrLine experience) {
        NameplateIdentityGeometry g=NameplateIdentityGeometry.of(w,h);
        assertEquals("示例",NotificationIdentityParser.parsePlateRows(Arrays.asList(row(name,name),row(merged,number,experience)),g,w,h,true).text());
        assertEquals("示例",NotificationIdentityParser.parsePlateRows(Arrays.asList(row(name,name),row(merged,number,experience)),g,w,h,false).text());
        assertSame(NotificationIdentityLabel.EMPTY,NotificationIdentityParser.parsePlateRows(Collections.singletonList(row(merged,number,experience)),g,w,h,true));
    }
    @Test public void independentBadgeAndAmbiguousOrClippedRowsRemainGuarded() {
        NameplateIdentityGeometry g=NameplateIdentityGeometry.of(436,174);
        OcrLine name=new OcrLine("示例",190,40,334,74), level=new OcrLine("154",140,87,195,120);
        assertEquals("示例(154级)",NotificationIdentityParser.parsePlateRows(Arrays.asList(row(name),row(level)),g,436,174,true).text());
        assertEquals("好友(154级)",NotificationIdentityParser.parsePlateRows(Collections.singletonList(row(level)),g,436,174,true).text());
        assertEquals("示例",NotificationIdentityParser.parsePlateRows(Arrays.asList(row(name),row(level)),g,436,174,false).text());
        OcrLine conflict=new OcrLine("155",140,122,195,143);
        assertEquals("示例",NotificationIdentityParser.parsePlateRows(Arrays.asList(row(name),row(level),row(conflict)),g,436,174,true).text());
    }
    @Test public void isolatedBadgeOcrRequiresCompleteUnambiguousSyntax() {
        for(String value:new String[]{"154","130"}) {
            OcrLine line=new OcrLine(value,3,3,100,80);
            assertEquals(Integer.valueOf(value),NotificationBadgePolicy.level(Collections.singletonList(row(line,line)),270,230));
            assertEquals("好友("+value+"级)",NotificationIdentityLabel.EMPTY.withLevel(Integer.parseInt(value)).text());
        }
        for(String value:new String[]{"054","030","15462万/156万","154/200"}) {
            OcrLine line=new OcrLine(value,3,3,100,80);
            assertNull(NotificationBadgePolicy.level(Collections.singletonList(row(line,line)),270,230));
        }
        OcrLine clipped=new OcrLine("154",1,3,100,80);
        assertNull(NotificationBadgePolicy.level(Collections.singletonList(row(clipped,clipped)),270,230));
    }
}
