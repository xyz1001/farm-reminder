package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class AvatarLocatorTest {
    private static final int W = 310, H = 694;
    private static final int SKY = 0xff52b8e1, WOOD = 0xffc17d4d;
    private static List<OcrLine> home(int scale, int top) {
        return Arrays.asList(new OcrLine("回家", 265 * scale, top + 550 * scale, 290 * scale, top + 560 * scale));
    }
    private static int[] scene(int scale, int top, boolean board, boolean texture) {
        int w = W * scale, h = H * scale + 2 * top;
        int[] p = new int[w * h];
        Arrays.fill(p, 0xff000000);
        rect(p, w, 0, top, w, top + H * scale, SKY);
        rect(p, w, 0, top + 601 * scale, w, top + 652 * scale, WOOD);
        circle(p, w, 30 * scale, top + 60 * scale, 17 * scale, scale, board, texture);
        return p;
    }
    private static void rect(int[] p, int w, int l, int t, int r, int b, int c) {
        for (int y = Math.max(0, t); y < Math.min(p.length / w, b); y++)
            for (int x = Math.max(0, l); x < Math.min(w, r); x++) p[y * w + x] = c;
    }
    private static void circle(int[] p, int w, int cx, int cy, int r, int s, boolean board, boolean textured) {
        if (board) rect(p, w, cx + r, cy - r / 2, cx + 2 * r, cy + r / 2, WOOD);
        for (int y = Math.max(0, cy - r); y < Math.min(p.length / w, cy + r + 1); y++)
            for (int x = Math.max(0, cx - r); x < Math.min(w, cx + r + 1); x++) {
                int d = (x - cx) * (x - cx) + (y - cy) * (y - cy);
                if (d <= r * r) p[y * w + x] = d >= (r - 3 * s) * (r - 3 * s) ? WOOD
                        : textured && ((x / (3 * s) + y / (3 * s)) % 2 == 0) ? 0xfff2e3c5 : 0xff344f77;
            }
    }
    @Test public void frameAtTwoScalesAndBlackBands() {
        for (int scale = 1; scale <= 2; scale++) for (int top : new int[]{0, 12 * scale}) {
            AvatarLocator.Bounds b = AvatarLocator.locate(scene(scale, top, true, true), W * scale,
                    H * scale + 2 * top, home(scale, top));
            assertNotNull(b);
            assertTrue(b.left >= 0 && b.right <= 55 * scale);
            assertTrue(b.top >= top && b.bottom <= top + 90 * scale);
        }
    }
    @Test public void requiresFriendContextAndTextureAndBoard() {
        assertNull(AvatarLocator.locate(scene(1, 0, true, true), W, H, Collections.emptyList()));
        assertNull(AvatarLocator.locate(scene(1, 0, false, true), W, H, home(1, 0)));
        assertNull(AvatarLocator.locate(scene(1, 0, true, false), W, H, home(1, 0)));
        assertNull(AvatarLocator.locate(scene(1, 0, true, true), W, H,
                Arrays.asList(new OcrLine("回家", 1, 1, 30, 15))));
        assertNull(AvatarLocator.locate(scene(1, 0, true, true), W, H,
                Arrays.asList(new OcrLine("好友回家", 265, 550, 290, 560))));
    }
    @Test public void actualReturnHomeGeometryIgnoresUnrelatedInvalidBoxes() {
        int[] frame = Arrays.copyOf(scene(4, 0, true, true), 1240 * 2772);
        List<OcrLine> lines = Arrays.asList(null, new OcrLine("", -1, 0, 10, 10),
                new OcrLine("回家", 1078, 2246, 1165, 2290));
        assertNotNull(AvatarLocator.locate(frame, 1240, 2772, lines));
    }
    @Test public void translatedFrameAndAmbiguousFrames() {
        int[] translated = scene(1, 0, true, true);
        rect(translated, W, 0, 20, 120, 110, SKY);
        circle(translated, W, 42, 75, 17, 1, true, true);
        assertNotNull(AvatarLocator.locate(translated, W, H, home(1, 0)));
        int[] ambiguous = scene(1, 0, true, true);
        rect(ambiguous, W, 0, 20, 120, 110, SKY);
        circle(ambiguous, W, 20, 42, 13, 1, true, true);
        circle(ambiguous, W, 47, 80, 13, 1, true, true);
        assertNull(AvatarLocator.locate(ambiguous, W, H, home(1, 0)));
    }
    @Test public void truncatedFrameIsNotAnAvatar() {
        int[] frame = scene(1, 0, true, true);
        rect(frame, W, 0, 20, 120, 110, SKY);
        circle(frame, W, 5, 60, 17, 1, true, true);
        assertNull(AvatarLocator.locate(frame, W, H, home(1, 0)));
    }
    @Test public void rejectsOccludedPortraitAndAmbiguousSideBars() {
        int[] frame = scene(1, 0, true, true);
        rect(frame, W, 12, 40, 48, 82, 0xff202020);
        assertNull(AvatarLocator.locate(frame, W, H, home(1, 0)));
        int[] sideBar = scene(1, 0, true, true);
        rect(sideBar, W, 0, 0, 2, H, 0xff000000);
        assertNull(AvatarLocator.locate(sideBar, W, H, home(1, 0)));
    }
    @Test public void rejectsDarkOrInvalidFrames() {
        int[] dark = scene(1, 0, true, true);
        for (int i = 0; i < dark.length; i++) dark[i] = 0xff000000 | ((dark[i] & 0xfefefe) >>> 2);
        assertNull(AvatarLocator.locate(dark, W, H, home(1, 0)));
        assertNull(AvatarLocator.locate(new int[3], Integer.MAX_VALUE, Integer.MAX_VALUE, home(1, 0)));
        assertNull(AvatarLocator.locate(scene(1, 0, true, true), W, H,
                Arrays.asList(new OcrLine("回家", -1, 0, 20, 10))));
    }
}
