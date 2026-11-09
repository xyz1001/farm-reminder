package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;
import static com.local.farmreminder.HeaderLayoutFixture.*;

public class NameplateLocatorTest {
    @Test public void storedPlateGeometrySurvivesRealCropRoundingAndTranslations() {
        for(int skin:new int[]{BLUE,PURPLE,GRAY}) for(int s:new int[]{1,2,4})
            for(int band:new int[]{0,12*s}) for(int[] d:new int[][]{{0,0},{3,3},{12,15}}) {
                int[] p=scene(s,band,d[0],d[1],skin);
                NameplateLocator.Bounds b=NameplateLocator.locate(p,W*s,H*s+2*band,null);
                assertNotNull(b);
                AvatarLocator.Bounds a=NameplateLocator.portraitInStoredPlate(b.right-b.left,b.bottom-b.top);
                assertNotNull(a);
                assertEquals(8*s,a.left,1); assertEquals(8*s,a.top,1);
                assertEquals(47*s,a.right,2); assertEquals(46*s,a.bottom,2);
            }
        for(int[] size:new int[][]{{113,51},{447,201},{337,151},{224,100}})
            assertNotNull(NameplateLocator.portraitInStoredPlate(size[0],size[1]));
        for(int[] size:new int[][]{{480,100},{112,40},{0,50},{112,0}})
            assertNull(NameplateLocator.portraitInStoredPlate(size[0],size[1]));
    }
    @Test public void plateLocalRowsPreserveNameAndIndependentLevelButRejectAmbiguity() {
        AvatarLocator.Bounds a=NameplateLocator.portraitInStoredPlate(448,200);
        OcrLine name=new OcrLine("示例",188,57,360,102), level=new OcrLine("148",147,109,205,146);
        assertEquals("示例(148级)",NotificationIdentityParser.parse(Arrays.asList(name,level),Collections.emptyList(),a,448,200,true,true).text());
        OcrLine merged=new OcrLine("148140万/142万",140,102,398,148);
        assertEquals("示例",NotificationIdentityParser.parse(Arrays.asList(name,merged),Collections.emptyList(),a,448,200,true,true).text());
        assertEquals("示例",NotificationIdentityParser.parse(Arrays.asList(name,level),Collections.emptyList(),a,448,200,true,false).text());
    }
    @Test public void functionalRectangleIncludesAllRegionsAndExcludesLowerControl() {
        for(int skin:new int[]{BLUE,PURPLE,GRAY}) for(int s:new int[]{1,2,4}) for(int band:new int[]{0,12*s}) {
            int[] p=scene(s,band,0,0,skin); NameplateLocator.Bounds b=NameplateLocator.locate(p,W*s,H*s+2*band,null);
            assertNotNull(b); assertTrue(b.left<=11*s); assertTrue(b.top<=band+41*s);
            assertTrue(b.right>103*s); assertTrue(b.bottom>band+79*s); assertTrue(b.bottom<=band+90*s);
            assertNotNull(NameplateLocator.locate(p,W*s,H*s+2*band,Collections.emptyList()));
        }
    }
    @Test public void translatedWholeHeaderIsContained() {
        for(int[] d:new int[][]{{3,3},{12,15}}) {
            int[] p=scene(1,0,d[0],d[1],PURPLE); NameplateLocator.Bounds b=NameplateLocator.locate(p,W,H,null);
            assertNotNull(b); assertTrue(b.left<=11+d[0]); assertTrue(b.top<=41+d[1]);
            assertTrue(b.right>=110+d[0]); assertTrue(b.bottom>=79+d[1]); assertTrue(b.bottom<=90+d[1]);
        }
    }
    @Test public void missingFrameBordersAndOcclusionFailClosed() {
        int[] p=scene(1,0,0,0,BLUE); rect(p,W,105,41,130,77,BLUE); assertNull(NameplateLocator.locate(p,W,H,null));
        p=scene(1,0,0,0,BLUE); rect(p,W,50,74,110,100,BLUE); assertNull(NameplateLocator.locate(p,W,H,null));
        p=scene(1,0,0,0,BLUE); rect(p,W,50,41,110,77,0xff222222); assertNull(NameplateLocator.locate(p,W,H,null));
    }
    @Test public void absentExperienceStructureAndFlatContentAreNotAHeader() {
        int[] p=scene(1,0,0,0,BLUE); rect(p,W,50,60,105,72,BLUE); assertNull(NameplateLocator.locate(p,W,H,null));
        p=scene(1,0,0,0,BLUE); rect(p,W,50,46,105,72,BLUE); assertNull(NameplateLocator.locate(p,W,H,null));
    }
    @Test public void competingCompleteHeadersFailButNearbyControlAloneDoesNot() {
        int[] p=scene(1,0,0,0,BLUE); assertNotNull(NameplateLocator.locate(p,W,H,null));
        header(p,W,1,0,0,45,PURPLE); assertNull(NameplateLocator.locate(p,W,H,null));
    }
    @Test public void clippedImageInvalidInputAndWrongPortraitFail() {
        int[] p=scene(1,0,0,0,BLUE), narrow=new int[108*H];
        for(int y=0;y<H;y++) System.arraycopy(p,y*W,narrow,y*108,108);
        assertNull(NameplateLocator.locate(narrow,108,H,null));
        assertNull(NameplateLocator.locate(null,W,H,null));
        assertNull(NameplateLocator.locate(new int[1],Integer.MAX_VALUE,Integer.MAX_VALUE,null));
        assertNull(NameplateLocator.locate(new int[W*H],W,H,null));
        assertNull(NameplateLocator.locatePlate(p,W,H,new AvatarLocator.Bounds(120,48,150,78)));
    }
    @Test public void validSizedSourceCropsCannotReturnPartialHeaders() {
        int w=1240,h=2772; int[] p=scene(4,0,0,0,BLUE);
        int cutTop=180, croppedHeight=h-cutTop; int[] topCrop=new int[w*croppedHeight];
        System.arraycopy(p,cutTop*w,topCrop,0,topCrop.length);
        assertNull(NameplateLocator.locate(topCrop,w,croppedHeight,null));
        assertNull(AvatarLocator.locateVisual(topCrop,w,croppedHeight));
        int cutLeft=100, croppedWidth=w-cutLeft; int[] leftCrop=new int[croppedWidth*h];
        for(int y=0;y<h;y++) System.arraycopy(p,y*w+cutLeft,leftCrop,y*croppedWidth,croppedWidth);
        assertNull(NameplateLocator.locate(leftCrop,croppedWidth,h,null));
        assertNull(AvatarLocator.locateVisual(leftCrop,croppedWidth,h));
    }
    @Test public void wholeHeaderOutsideTranslationEnvelopeIsNotGuessed() {
        int[] p=scene(1,0,40,0,PURPLE);
        assertNull(NameplateLocator.locate(p,W,H,null));
        assertNull(AvatarLocator.locateVisual(p,W,H));
    }
    @Test public void detectedAnchorPreservesIndependentLevelAndRejectsMergedOrClippedBadge() {
        int[] p=scene(4,0,0,0,BLUE); AvatarLocator.Bounds a=AvatarLocator.locateVisual(p,1240,2772); assertNotNull(a);
        OcrLine name=new OcrLine("示例",200,189,372,234), level=new OcrLine("148",159,241,217,278);
        assertEquals("示例(148级)",NotificationIdentityParser.parse(Arrays.asList(name,level),Collections.emptyList(),a,1240,2772,true,true).text());
        OcrLine merged=new OcrLine("148140万/142万",152,234,410,280);
        assertEquals("示例",NotificationIdentityParser.parse(Arrays.asList(name,merged),Collections.emptyList(),a,1240,2772,true,true).text());
        assertEquals("示例",NotificationIdentityParser.parse(Arrays.asList(name,level),Collections.emptyList(),a,1240,2772,true,false).text());
    }
}
