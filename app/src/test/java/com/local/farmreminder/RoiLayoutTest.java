package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class RoiLayoutTest {
    @Test public void proportionsAndMapping() {
        RoiLayout l = new RoiLayout(1240,2772);
        RoiLayout.Tile a = l.tiles.get(0), b = l.tiles.get(1), c = l.tiles.get(2);
        assertEquals(124, a.left); assertEquals(69, a.top); assertEquals(695, a.right);
        assertEquals(0, b.left); assertEquals(887, b.top); assertEquals(1240, b.right);
        assertEquals(917, c.left); assertEquals(1995, c.top); assertEquals(1240, c.right);
        OcrLine mapped = l.map("x",32+387,b.atlasTop+502,32+628,b.atlasTop+533);
        assertEquals(387,mapped.left); assertEquals(1389,mapped.top); assertEquals(628,mapped.right);
    }
    @Test public void seamsAndOutOfBoundsAreRejected() {
        RoiLayout l = new RoiLayout(895,2000); RoiLayout.Tile a=l.tiles.get(0), b=l.tiles.get(1);
        int end=a.atlasTop+a.bottom-a.top;
        assertNull(l.map("x",32,end-1,40,end+1));
        assertNull(l.map("x",32,end,40,b.atlasTop));
        assertNull(l.map("x",31,a.atlasTop,40,a.atlasTop+1));
        assertNull(l.map("x",32,a.atlasTop,32,a.atlasTop+1));
        assertNull(l.map("x",32+a.right-a.left,a.atlasTop,33+a.right-a.left,a.atlasTop+1));
    }
    @Test public void tinyCropAndParserStayConservative() {
        RoiLayout tiny = new RoiLayout(1,1); assertEquals(3,tiny.tiles.size());
        for (RoiLayout.Tile t:tiny.tiles) { assertEquals(0,t.left); assertEquals(1,t.right); assertEquals(0,t.top); assertEquals(1,t.bottom); }
        RoiLayout l = new RoiLayout(895,2000); RoiLayout.Tile info=l.tiles.get(1);
        OcrLine stage=l.map("小叶子 01:40:46",332,info.atlasTop+60,532,info.atlasTop+90);
        assertNull(OcrFrameParser.countdown(Arrays.asList(stage)));
        assertNull(OcrFrameParser.friend(Arrays.asList(stage),895,2000));
        OcrLine first=l.map("05:32:03",32,info.atlasTop,150,info.atlasTop+20);
        RoiLayout.Tile home=l.tiles.get(2);
        OcrLine second=l.map("后成熟",32,home.atlasTop,100,home.atlasTop+20);
        assertNull(OcrFrameParser.countdown(Arrays.asList(first,second)));
    }
    @Test(expected=IllegalArgumentException.class) public void emptySourceRejected() { new RoiLayout(0,100); }
}
