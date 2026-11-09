package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;
import static com.local.farmreminder.HeaderLayoutFixture.*;

public class AvatarLocatorTest {
    @Test public void validatedFunctionalHeaderAtScalesAndBlackBands() {
        for(int s:new int[]{1,2,4}) for(int band:new int[]{0,12*s}) {
            int[] p=scene(s,band,0,0,BLUE); AvatarLocator.Bounds b=AvatarLocator.locate(p,W*s,H*s+2*band,home(s,band));
            assertNotNull(b); assertTrue(Math.abs(b.left-11*s)<=4*s); assertTrue(Math.abs(b.top-(band+41*s))<=4*s);
            assertTrue(Math.abs(b.right-50*s)<=4*s); assertTrue(Math.abs(b.bottom-(band+79*s))<=4*s);
        }
    }
    @Test public void visualNeedsNoOcrButStrictFriendSceneRemainsSeparate() {
        int[] p=scene(1,0,0,0,PURPLE);
        assertNotNull(AvatarLocator.locateVisual(p,W,H));
        assertNull(AvatarLocator.locate(p,W,H,null));
        assertNull(AvatarLocator.locate(p,W,H,Collections.emptyList()));
        assertNull(AvatarLocator.locate(p,W,H,Arrays.asList(new OcrLine("回家",1,1,30,15))));
        assertNull(AvatarLocator.locate(p,W,H,Arrays.asList(new OcrLine("好友回家",265,550,290,560))));
        rect(p,W,0,601,W,652,SKY);
        assertNull(AvatarLocator.locate(p,W,H,home(1,0)));
        assertNotNull(AvatarLocator.locateVisual(p,W,H));
    }
    @Test public void translatedWholeHeaderIsNotACompetingTextPatch() {
        int[] p=scene(1,0,12,15,BLUE); AvatarLocator.Bounds b=AvatarLocator.locateVisual(p,W,H);
        assertNotNull(b); assertTrue(Math.abs(b.left-23)<=4); assertTrue(Math.abs(b.top-56)<=4);
        assertTrue(Math.abs(b.right-62)<=4); assertTrue(Math.abs(b.bottom-94)<=4);
    }
    @Test public void textLikeCircularPatchCannotMoveRealPortraitAnchor() {
        int[] p=scene(1,0,0,0,BLUE);
        // A high-contrast text-like ring inside the name/level area is not a portrait.
        for(int y=51;y<=67;y++) for(int x=58;x<=74;x++) {
            int q=(x-66)*(x-66)+(y-59)*(y-59);
            if(q<=64 && q>=36) p[y*W+x]=0xfff2f2f2;
        }
        AvatarLocator.Bounds b=AvatarLocator.locateVisual(p,W,H);
        assertNotNull(b); assertTrue(Math.abs(b.left-11)<=4); assertTrue(Math.abs(b.top-41)<=4);
        assertTrue(Math.abs(b.right-50)<=4); assertTrue(Math.abs(b.bottom-79)<=4);
    }
    @Test public void circleAloneAndOccludedPortraitAreNotCompleteHeaders() {
        int[] p=scene(1,0,0,0,BLUE); rect(p,W,50,35,120,85,SKY);
        assertNull(AvatarLocator.locateVisual(p,W,H));
        p=scene(1,0,0,0,BLUE); rect(p,W,10,38,50,82,0xff202020);
        assertNull(AvatarLocator.locateVisual(p,W,H));
    }
    @Test public void invalidAndAmbiguousSideBarsFailClosed() {
        int[] p=scene(1,0,0,0,BLUE); rect(p,W,0,0,2,H,0xff000000);
        assertNull(AvatarLocator.locateVisual(p,W,H));
        assertNull(AvatarLocator.locateVisual(null,W,H));
        assertNull(AvatarLocator.locate(new int[3],Integer.MAX_VALUE,Integer.MAX_VALUE,home(1,0)));
        assertNull(AvatarLocator.locate(scene(1,0,0,0,BLUE),W,H,Arrays.asList(new OcrLine("回家",-1,0,20,10))));
    }
}
