package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class NameplateLocatorTest {
    private static final int W = 1240, H = 2772, SKY = 0xff52b8e1, WOOD = 0xffc17d4d;
    private static final AvatarLocator.Bounds FRAME = new AvatarLocator.Bounds(44,164,196,316);
    private static List<OcrLine> home(int top) {
        return Collections.singletonList(new OcrLine("回家", 1078, 2246 + top, 1165, 2290 + top));
    }
    private static void rect(int[] p, int w, int l, int t, int r, int b, int color) {
        for (int y = Math.max(0,t); y < Math.min(p.length/w,b); y++)
            for (int x = Math.max(0,l); x < Math.min(w,r); x++) p[y*w+x]=color;
    }
    private static int[] scene(int dx, int dy, int top) {
        int[] p = new int[W*(H+2*top)]; Arrays.fill(p, 0xff000000);
        rect(p,W,0,top,W,H+top,SKY); rect(p,W,0,top+2400,W,top+2605,WOOD);
        dy += top;
        rect(p,W,120+dx,164+dy,442+dx,310+dy,WOOD);
        rect(p,W,235+dx,237+dy,420+dx,280+dy,0xff80dc30);
        // Synthetic name-row texture: no private text or image data.
        for(int y=190;y<230;y+=8) rect(p,W,205+dx,y+dy,355+dx,y+dy+2,0xfff3e8d3);
        for(int y=164;y<=316;y++) for(int x=44;x<=196;x++) {
            int distance=(x-120)*(x-120)+(y-240)*(y-240);
            if(distance<=76*76) p[(y+dy)*W+x+dx]=distance>=64*64 ? WOOD
                    : ((x/12+y/12)%2==0 ? 0xfff2e3c5 : 0xff344f77);
        }
        rect(p,W,50+dx,145+dy,120+dx,189+dy,WOOD);
        rect(p,W,23+dx,255+dy,95+dx,310+dy,WOOD);
        return p;
    }

    @Test public void fullPlateIsSeparateFromUnchangedPortraitAnchor() {
        int[] p=scene(0,0,0);
        AvatarLocator.Bounds a=AvatarLocator.locate(p,W,H,home(0)); assertNotNull(a);
        NameplateLocator.Bounds b=NameplateLocator.locatePlate(p,W,H,FRAME); assertNotNull(b);
        assertTrue(b.left<=23 && b.top<=145 && b.right>442 && b.bottom>316);
        assertTrue(b.right-b.left > 2*(a.right-a.left));
        AvatarLocator.Bounds after=AvatarLocator.locate(p,W,H,home(0));
        assertEquals(a.left,after.left); assertEquals(a.right,after.right);
    }

    @Test public void halfScaleAndBlackBandOffsetsPreserveCompleteGeometry() {
        for(int top:new int[]{0,24}) {
            int[] p=scene(0,0,top); NameplateLocator.Bounds full=NameplateLocator.locatePlate(p,W,H+2*top,
                    new AvatarLocator.Bounds(44,164+top,196,316+top));
            assertNotNull(full);
            int w=W/2,h=(H+2*top)/2; int[] half=new int[w*h];
            for(int y=0;y<h;y++) for(int x=0;x<w;x++) half[y*w+x]=p[(2*y)*W+2*x];
            NameplateLocator.Bounds b=NameplateLocator.locatePlate(half,w,h,
                    new AvatarLocator.Bounds(22,(164+top)/2,98,(316+top)/2));
            assertNotNull(b); assertTrue(b.left<=12 && b.right>221 && b.top<= (145+top)/2 && b.bottom>(316+top)/2);
        }
    }

    @Test public void translatesWithDetectedFrameRatherThanFixedScreenRectangle() {
        int[] p=scene(12,12,0);
        NameplateLocator.Bounds b=NameplateLocator.locatePlate(p,W,H,new AvatarLocator.Bounds(56,176,208,328)); assertNotNull(b);
        assertTrue(b.left<=35 && b.top<=157 && b.right>454 && b.bottom>328);
    }

    @Test public void completeVisualPlateDoesNotRequireSceneButMissingExperienceFailsSoft() {
        int[] p=scene(0,0,0); assertNull(AvatarLocator.locate(p,W,H,Collections.emptyList()));
        assertNotNull(NameplateLocator.locate(p,W,H,Collections.emptyList()));
        assertNotNull(NameplateLocator.locate(p,W,H,null));
        assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityParser.parse(Collections.emptyList(), Collections.emptyList(),
                AvatarLocator.locateVisual(p,W,H), W, H, false, false));
        rect(p,W,235,237,420,280,WOOD);
        assertNull(NameplateLocator.locatePlate(p,W,H,FRAME));
    }

    @Test public void missingRightOrBottomBorderAndOcclusionAreNotBlindlyExpanded() {
        int[] right=scene(0,0,0); rect(right,W,420,164,700,310,WOOD); // No complete right edge within the bounded search.
        assertNull(NameplateLocator.locatePlate(right,W,H,FRAME));
        int[] bottom=scene(0,0,0); rect(bottom,W,200,290,442,500,WOOD); // No complete bottom edge within the bounded search.
        assertNull(NameplateLocator.locatePlate(bottom,W,H,FRAME));
        int[] occluded=scene(0,0,0); rect(occluded,W,200,160,445,300,0xff222222);
        assertNull(NameplateLocator.locatePlate(occluded,W,H,FRAME));
        assertNull(NameplateLocator.locate(occluded,W,H,Collections.emptyList()));
    }

    @Test public void competingPanelEdgesAndOccupiedPaddingAreRejected() {
        int[] p=scene(0,0,0); rect(p,W,450,195,466,280,WOOD);
        assertNull(NameplateLocator.locatePlate(p,W,H,FRAME));
        int[] padding=scene(0,0,0); rect(padding,W,8,140,22,320,WOOD);
        assertNull(NameplateLocator.locatePlate(padding,W,H,FRAME));
    }

    @Test public void invalidInputNeverProducesPartialBounds() {
        assertNull(NameplateLocator.locate(null,W,H,home(0)));
        assertNull(NameplateLocator.locate(new int[1],Integer.MAX_VALUE,Integer.MAX_VALUE,home(0)));
        assertNull(NameplateLocator.locate(new int[W*H],W,H,Collections.emptyList()));
    }

    @Test public void sourceTruncationIsRejectedRatherThanClampedToAnIncompletePlate() {
        int[] p=scene(0,0,0), narrow=new int[430*H];
        for(int y=0;y<H;y++) System.arraycopy(p,y*W,narrow,y*430,430);
        assertNull(NameplateLocator.locatePlate(narrow,430,H,FRAME));
        assertNull(NameplateLocator.locate(narrow,430,H,Collections.emptyList()));
        assertNull(NameplateLocator.locatePlate(Arrays.copyOf(p,W*320),W,320,FRAME));
    }

    private static int[] lowGreenScene(int dx,int dy,int top) {
        int[] p=scene(dx,dy,top);
        rect(p,W,235+dx,237+dy+top,420+dx,280+dy+top,WOOD);
        // Synthetic lower-field digit-like strokes: geometry/contrast, no user text or private pixels.
        for(int x=245;x<412;x+=15) {
            rect(p,W,x+dx,244+dy+top,x+dx+4,274+dy+top,0xfffbefd4);
            rect(p,W,x+dx+4,244+dy+top,x+dx+10,248+dy+top,0xff503525);
        }
        rect(p,W,60+dx,333+dy+top,140+dx,430+dy+top,WOOD); // Separate next control must never join the plate.
        return p;
    }

    @Test public void completeLowGreenPanelSurvivesCoarsePortraitSeedWithoutChangingItsSemantics() {
        int[] p=lowGreenScene(0,0,0);
        AvatarLocator.Bounds a=AvatarLocator.locate(p,W,H,home(0)); assertNotNull(a);
        NameplateLocator.Bounds b=NameplateLocator.locate(p,W,H,home(0)); assertNotNull(b);
        assertTrue(b.left<=23 && b.top<=145 && b.right>442 && b.bottom>316 && b.bottom<=333);
        AvatarLocator.Bounds after=AvatarLocator.locate(p,W,H,home(0));
        assertEquals(a.left,after.left);assertEquals(a.top,after.top);assertEquals(a.right,after.right);assertEquals(a.bottom,after.bottom);
        // Numeric coarse seed from the failed runtime frame; whole plate cannot be inferred by fixed D padding.
        NameplateLocator.Bounds coarse=NameplateLocator.locatePlate(p,W,H,new AvatarLocator.Bounds(64,172,192,300));
        assertNotNull(coarse); assertTrue(coarse.left<=23 && coarse.right>442 && coarse.bottom<=333);
    }

    @Test public void lowGreenPanelWorksAtHalfScaleAndTranslatedBlackBandOffsets() {
        for(int top:new int[]{0,24}) {
            int[] p=lowGreenScene(12,12,top);
            NameplateLocator.Bounds full=NameplateLocator.locate(p,W,H+2*top,home(top));assertNotNull(full);
            assertTrue(full.left<=35 && full.top<=157+top && full.right>454 && full.bottom<=345+top);
            int w=W/2,h=(H+2*top)/2; int[] half=new int[w*h];
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)half[y*w+x]=p[2*y*W+2*x];
            NameplateLocator.Bounds b=NameplateLocator.locate(half,w,h,Collections.singletonList(
                    new OcrLine("回家",539,(2246+top)/2,582,(2290+top)/2)));
            assertNotNull(b); assertTrue(b.right>227 && b.bottom<=(345+top)/2);
        }
    }

    @Test public void lowGreenRequiresActualFieldStructureButNotRemoteScene() {
        assertNotNull(NameplateLocator.locate(lowGreenScene(0,0,0),W,H,Collections.emptyList()));
        int[] p=scene(0,0,0);rect(p,W,200,232,442,290,WOOD);
        assertNull(NameplateLocator.locate(p,W,H,home(0))); // Plain wood cannot stand in for an experience field.
    }
}
