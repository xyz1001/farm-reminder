package com.local.farmreminder;

import java.util.List;

/** Optional full wooden plate, independently located after the unchanged portrait/scene gate. */
final class NameplateLocator {
    static final class Bounds {
        final int left, top, right, bottom;
        Bounds(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
    }

    static Bounds locate(int[] pixels, int width, int height, List<OcrLine> lines) {
        AvatarLocator.Bounds portrait = AvatarLocator.locate(pixels, width, height, lines);
        if (portrait == null) return null;
        return locatePlate(pixels, width, height, portrait);
    }

    /** Edge-confirmation stage; the public entry above supplies the unchanged friend/portrait admission. */
    static Bounds locatePlate(int[] pixels, int width, int height, AvatarLocator.Bounds portrait) {
        if (pixels == null || width <= 0 || height <= 0 || (long) width * height > pixels.length || portrait == null
                || portrait.left < 0 || portrait.top < 0 || portrait.right <= portrait.left || portrait.bottom <= portrait.top
                || portrait.right > width || portrait.bottom > height) return null;
        double d = portrait.right - portrait.left;
        int l = (int) Math.floor(portrait.left - .18 * d), t = (int) Math.floor(portrait.top - .17 * d);
        int expectedR = (int) Math.ceil(portrait.left + 2.67 * d), expectedB = (int) Math.ceil(portrait.top + 1.06 * d);
        if (l < 0 || t < 0 || expectedR >= width || expectedB >= height) return null;
        int xl = (int) (portrait.left + 1.35 * d), xr = (int) (portrait.left + 2.35 * d);
        int pad = Math.max(1, (int) Math.round(d * .025));
        int top = edge(pixels, width, height, xl, xr, (int) (portrait.top - .08 * d),
                (int) (portrait.top + .15 * d), pad, true, false, d);
        int bottom = edge(pixels, width, height, xl, xr, (int) (portrait.top + .84 * d),
                (int) (portrait.top + 1.02 * d), pad, false, false, d);
        int right = edge(pixels, width, height, (int) (portrait.top + .30 * d), (int) (portrait.top + .70 * d),
                (int) (portrait.left + 2.40 * d), (int) (portrait.left + 2.80 * d), pad, false, true, d);
        if (top < 0 || bottom < 0 || right < 0 || bottom - top < .80 * d || bottom - top > 1.12 * d) return null;
        int r = (int) Math.ceil(right + .065 * d);
        int b = Math.max(expectedB, (int) Math.ceil(bottom + .10 * d));
        if (r >= width || b >= height || r <= xr || b <= portrait.bottom) return null;
        int cornerX = right - (int) (.10 * d), upperY = top + (int) (.15 * d), lowerY = bottom - (int) (.15 * d);
        // The measured side must actually join both rounded wooden corners, not a detached warm control nearby.
        if (fraction(pixels, width, height, cornerX - pad, upperY - pad, cornerX + pad, upperY + pad, false) < .50
                || fraction(pixels, width, height, cornerX - pad, lowerY - pad, cornerX + pad, lowerY + pad, false) < .50) return null;
        // Continuous upper/lower wood joins the portrait, not just a warm text/experience rectangle.
        if (fraction(pixels, width, height, portrait.right, top + pad, right - pad, top + 3 * pad, false) < .60
                || fraction(pixels, width, height, portrait.right, bottom - 3 * pad, right - pad, bottom - pad, false) < .60
                || fraction(pixels, width, height, portrait.right, top + pad, right - pad, bottom - pad, false) < .36
                || fraction(pixels, width, height, (int) (portrait.left + 1.32 * d), (int) (portrait.top + .47 * d),
                        right - (int) (.16 * d), (int) (portrait.top + .78 * d), true) < .15) return null;
        // This observed frame skin has protruding upper decoration and lower-left cup; uncertain skins fail soft.
        if (fraction(pixels, width, height, portrait.left, (int) (portrait.top - .15 * d),
                        (int) (portrait.left + .60 * d), (int) (portrait.top + .15 * d), false) < .12
                || fraction(pixels, width, height, (int) (portrait.left - .16 * d), (int) (portrait.top + .60 * d),
                        (int) (portrait.left + .35 * d), (int) (portrait.top + .97 * d), false) < .12) return null;
        // A margin still occupied by wood indicates clipping/another panel; do not clamp or blindly expand.
        if (fraction(pixels, width, height, l, t, r, t + pad, false) > .05
                || fraction(pixels, width, height, l, b - pad, r, b, false) > .05
                || fraction(pixels, width, height, l, t, l + pad, b, false) > .05
                || fraction(pixels, width, height, r - pad, t, r, b, false) > .05) return null;
        return new Bounds(l, t, r, b);
    }

    /** Finds one coherent edge cluster, with warm interior, cool exterior and measured color contrast. */
    private static int edge(int[] p, int w, int h, int a, int z, int from, int to, int pad,
            boolean entering, boolean vertical, double d) {
        int best = -1, first = -1, last = -1;
        double bestScore = -1;
        for (int at = from; at <= to; at++) {
            int in0 = entering ? at + pad : at - 3 * pad, in1 = entering ? at + 3 * pad : at - pad;
            int out0 = entering ? at - 3 * pad : at + pad, out1 = entering ? at - pad : at + 3 * pad;
            double inside = vertical ? fraction(p, w, h, in0, a, in1, z, false) : fraction(p, w, h, a, in0, z, in1, false);
            double outside = vertical ? fraction(p, w, h, out0, a, out1, z, false) : fraction(p, w, h, a, out0, z, out1, false);
            if (inside < .65 || outside > .12) continue;
            double contrast = contrast(p, w, h, a, z, (in0 + in1) / 2, (out0 + out1) / 2, vertical);
            if (contrast < 35) continue;
            if (first < 0) first = at;
            if (last >= 0 && at - last > Math.max(2, .08 * d)) return -1;
            last = at;
            double score = inside - outside + contrast / 600.0;
            if (score > bestScore) { bestScore = score; best = at; }
        }
        return first < 0 ? -1 : best;
    }

    private static double contrast(int[] p, int w, int h, int a, int z, int inside, int outside, boolean vertical) {
        if (a < 0 || z <= a || inside < 0 || outside < 0 || (vertical ? z > h || inside >= w || outside >= w
                : z > w || inside >= h || outside >= h)) return 0;
        long total = 0; int count = 0;
        for (int k = a; k < z; k += 2) {
            int c = vertical ? p[k * w + inside] : p[inside * w + k];
            int o = vertical ? p[k * w + outside] : p[outside * w + k];
            total += Math.abs((c >> 16 & 255) - (o >> 16 & 255))
                    + Math.abs((c >> 8 & 255) - (o >> 8 & 255)) + Math.abs((c & 255) - (o & 255)); count++;
        }
        return count == 0 ? 0 : (double) total / count;
    }

    private static double fraction(int[] p, int w, int h, int l, int t, int r, int b, boolean green) {
        if (l < 0 || t < 0 || r > w || b > h || l >= r || t >= b) return 0;
        int matches = 0, total = 0;
        for (int y = t; y < b; y += 2) for (int x = l; x < r; x += 2) {
            int c = p[y * w + x], red = c >> 16 & 255, g = c >> 8 & 255, blue = c & 255;
            boolean yes = green ? g > 100 && g > red * 1.08 && g > blue * 1.25
                    : red > 80 && red > g * 1.06 && g > blue * 1.08 && red > blue * 1.15;
            total++; if (yes) matches++;
        }
        return total == 0 ? 0 : (double) matches / total;
    }

    private NameplateLocator() { }
}
