package com.local.farmreminder;

import java.util.ArrayList;
import java.util.List;

/** Pixel-preserving vertical atlas. Rectangles are half-open; seams cannot map. */
public final class RoiLayout {
    public static final int MARGIN = 32;
    public final int width, height;
    public final List<Tile> tiles = new ArrayList<>();

    public static final class Tile {
        public final int left, top, right, bottom, atlasTop;
        Tile(int left, int top, int right, int bottom, int atlasTop) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom; this.atlasTop = atlasTop;
        }
    }
    public RoiLayout(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) throw new IllegalArgumentException("empty source");
        int y = MARGIN, maxWidth = 0;
        double[][] regions = {{.10,.025,.56,.14},{0,.32,1,.71},{.74,.72,1,.87}};
        for (double[] r : regions) {
            int l = clamp((int)Math.floor(r[0] * sourceWidth), sourceWidth);
            int t = clamp((int)Math.floor(r[1] * sourceHeight), sourceHeight);
            int right = clamp((int)Math.ceil(r[2] * sourceWidth), sourceWidth);
            int bottom = clamp((int)Math.ceil(r[3] * sourceHeight), sourceHeight);
            if (right <= l || bottom <= t) continue;
            tiles.add(new Tile(l,t,right,bottom,y));
            maxWidth = Math.max(maxWidth, right-l);
            y += bottom-t+MARGIN;
        }
        width = maxWidth + 2*MARGIN; height = y;
    }
    private static int clamp(int value, int max) { return Math.max(0, Math.min(max, value)); }
    public OcrLine map(String text, int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) return null;
        for (Tile tile : tiles) {
            int w = tile.right-tile.left, h = tile.bottom-tile.top;
            if (left >= MARGIN && right <= MARGIN+w && top >= tile.atlasTop && bottom <= tile.atlasTop+h) {
                return new OcrLine(text, tile.left+left-MARGIN, tile.top+top-tile.atlasTop,
                        tile.left+right-MARGIN, tile.top+bottom-tile.atlasTop);
            }
        }
        return null;
    }
}
