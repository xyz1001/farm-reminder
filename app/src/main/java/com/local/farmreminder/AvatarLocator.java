package com.local.farmreminder;

import java.util.List;

/** Conservative, optional portrait-frame detector. It never inspects name text. */
final class AvatarLocator {
    static final class Bounds {
        final int left, top, right, bottom;
        Bounds(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
    }

    private AvatarLocator() { }

    static Bounds locate(int[] argb, int width, int height, List<OcrLine> lines) {
        return locateInternal(argb, width, height, lines, true);
    }

    /** Visual crop seed only; never establishes friend identity or a trusted scene. */
    static Bounds locateVisual(int[] argb, int width, int height) {
        return locateInternal(argb, width, height, null, false);
    }

    private static Bounds locateInternal(int[] argb, int width, int height, List<OcrLine> lines, boolean needScene) {
        if (argb == null || (needScene && lines == null) || width < 160 || height < width
                || (long) width * height > argb.length) return null;
        // Side bars are currently ambiguous: do not reinterpret their coordinates.
        if (blackColumn(argb, width, height, 0) || blackColumn(argb, width, height, width - 1)) return null;
        // Only remove genuinely uniform black exterior bands, not dark rounded corners.
        int top = 0, bottom = height;
        while (top < height / 5 && blackRow(argb, width, top)) top++;
        while (bottom > top && height - bottom < height / 5 && blackRow(argb, width, bottom - 1)) bottom--;
        int vh = bottom - top;
        if (vh < width || vh > width * 3L) return null;
        if (needScene) {
            boolean home = false;
            for (OcrLine l : lines) {
                if (l != null && l.left >= 0 && l.top >= 0 && l.right > l.left && l.bottom > l.top
                        && l.right <= width && l.bottom <= height
                        && "回家".equals(l.text.trim()) && l.left >= width * .75
                        && l.top >= top + vh * .70 && l.bottom <= top + vh * .90
                        && l.right - l.left <= width * .16 && l.bottom - l.top <= width * .10) home = true;
            }
            if (!home) return null;
            // The friend selector strip and bright sky are independent, non-name scene anchors.
            if (warmFraction(argb, width, height, width / 20, top + (int) (vh * .865),
                    width * 19 / 20, top + (int) (vh * .94), 8) < .35
                    || brightness(argb, width, width / 3, top + width / 30, width * 2 / 3,
                    top + width / 12, 8) < 105) return null;
        }

        // Normalize the search once; width-based geometry keeps circles circular.
        int sw = 310, sh = Math.max(1, (int) ((long) vh * sw / width));
        int[] pix = new int[sw * sh];
        for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++)
            pix[y * sw + x] = argb[(top + (int) ((long) y * vh / sh)) * width + (int) ((long) x * width / sw)];
        Candidate best = null, second = null;
        for (int cy = 25; cy <= 99; cy += 3) for (int cx = 13; cx <= 56; cx += 3)
            for (int r = 13; r <= 28; r += 2) {
                Candidate c = score(pix, sw, sh, cx, cy, r);
                if (c == null) continue;
                if (best == null || c.score > best.score) {
                    if (best != null && distinct(c, best) && (second == null || best.score > second.score)) second = best;
                    best = c;
                    if (second != null && !distinct(best, second)) second = null;
                } else if (distinct(c, best) && (second == null || c.score > second.score)) second = c;
            }
        if (best == null || (second != null && second.score > best.score - .08)) return null;
        // Re-evaluate larger concentric frame hypotheses against actual pixels. The
        // original upper-left edge remains anchored; right and bottom grow only if
        // the complete larger circle still passes all independent frame tests and
        // its score remains close to the best coarse candidate. This is not padding.
        Candidate refined = best;
        for (int radius = best.r + 1; radius <= Math.min(28, best.r * 5 / 4); radius++) {
            int shift = radius - best.r;
            Candidate c = score(pix, sw, sh, best.x + shift, best.y + shift, radius);
            if (c != null && c.score >= best.score - .15) refined = c;
        }
        best = refined;
        int left = (int) Math.floor((best.x - best.r) * (double) width / sw);
        int right = (int) Math.ceil((best.x + best.r) * (double) width / sw);
        int y0 = top + (int) Math.floor((best.y - best.r) * (double) vh / sh);
        int y1 = top + (int) Math.ceil((best.y + best.r) * (double) vh / sh);
        if (left < 0 || y0 < top || right > width || y1 > bottom) return null;
        return new Bounds(left, y0, right, y1);
    }

    private static final class Candidate {
        final int x, y, r; final double score;
        Candidate(int x, int y, int r, double score) { this.x = x; this.y = y; this.r = r; this.score = score; }
    }
    private static boolean distinct(Candidate a, Candidate b) {
        int dx = a.x - b.x, dy = a.y - b.y;
        double separation = a.r + b.r;
        return dx * dx + dy * dy > separation * separation;
    }
    private static Candidate score(int[] p, int w, int h, int x, int y, int r) {
        if (x - r < 0 || y - r < 0 || x + 2 * r >= w || y + r >= h) return null;
        int warm = 0, edge = 0;
        for (int i = 0; i < 32; i++) {
            double a = i * Math.PI / 16;
            int ix = (int) Math.round(x + Math.cos(a) * r), iy = (int) Math.round(y + Math.sin(a) * r);
            int ox = (int) Math.round(x + Math.cos(a) * (r + 2)), oy = (int) Math.round(y + Math.sin(a) * (r + 2));
            if (ox < 0 || oy < 0 || ox >= w || oy >= h) continue;
            int pixel = p[iy * w + ix];
            if (warm(pixel)) warm++;
            if (Math.abs(luma(pixel) - luma(p[oy * w + ox])) >= 12) edge++;
        }
        double board = warmFraction(p, w, h, x + r, y - r / 2, x + r * 2, y + r / 2, 2);
        double texture = texture(p, w, x, y, r);
        if (warm < 15 || edge < 8 || board < .43 || texture < 16) return null;
        return new Candidate(x, y, r, warm / 32.0 + edge / 64.0 + board * .35 + Math.min(texture, 50) / 150.0);
    }
    private static double texture(int[] p, int w, int x, int y, int r) {
        double sum = 0, sq = 0; int n = 0;
        for (int dy = -r / 2; dy <= r / 2; dy += 3) for (int dx = -r / 2; dx <= r / 2; dx += 3) {
            int v = luma(p[(y + dy) * w + x + dx]); sum += v; sq += v * v; n++;
        }
        return Math.sqrt(Math.max(0, sq / n - sum * sum / n / n));
    }
    private static int luma(int c) { return (((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255)) / 10; }
    private static boolean warm(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return r > 85 && r * 100 > g * 104 && g * 100 > b * 112 && r * 10 > b * 13;
    }
    private static double warmFraction(int[] p, int w, int h, int l, int t, int r, int b, int step) {
        if (l < 0 || t < 0 || r > w || b > h || l >= r || t >= b) return 0;
        int n = 0, matches = 0;
        for (int y = t; y < b; y += step) for (int x = l; x < r; x += step) { n++; if (warm(p[y * w + x])) matches++; }
        return n == 0 ? 0 : (double) matches / n;
    }
    private static int brightness(int[] p, int w, int l, int t, int r, int b, int step) {
        int n = 0; long sum = 0;
        for (int y = t; y < b; y += step) for (int x = l; x < r; x += step) { n++; sum += luma(p[y * w + x]); }
        return n == 0 ? 0 : (int) (sum / n);
    }
    private static boolean blackRow(int[] p, int w, int y) {
        for (int x = 0; x < w; x++) if ((p[y * w + x] & 0x00ffffff) != 0) return false;
        return true;
    }
    private static boolean blackColumn(int[] p, int w, int h, int x) {
        for (int y = 0; y < h; y++) if ((p[y * w + x] & 0x00ffffff) != 0) return false;
        return true;
    }
}
