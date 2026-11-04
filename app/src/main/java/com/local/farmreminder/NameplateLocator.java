package com.local.farmreminder;

import java.util.List;
import java.util.ArrayList;

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
        Bounds previous = legacyPlate(pixels, width, height, portrait);
        return previous != null ? previous : connectedPlate(pixels, width, height, portrait);
    }

    private static Bounds legacyPlate(int[] pixels, int width, int height, AvatarLocator.Bounds portrait) {
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

    /** Uses the portrait only as a search seed. Output scale and decoration extents come from pixels. */
    private static Bounds connectedPlate(int[] p, int w, int h, AvatarLocator.Bounds a) {
        if (p == null || w <= 0 || h <= 0 || (long) w * h > p.length || a == null || a.left < 0 || a.top < 0
                || a.right <= a.left || a.bottom <= a.top || a.right > w || a.bottom > h) return null;
        double d = a.right - a.left;
        int left = Math.max(0, (int) Math.floor(a.left - .55 * d));
        int top = Math.max(0, (int) Math.floor(a.top - .50 * d));
        int right = Math.min(w, (int) Math.ceil(a.left + 3.80 * d));
        int bottom = Math.min(h, (int) Math.ceil(a.top + 1.50 * d));
        int rw = right - left, rh = bottom - top;
        if (rw <= 0 || rh <= 0 || (long) rw * rh > 1_000_000) return null;
        byte[] mask = new byte[rw * rh];
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++)
            if (foreground(p[y * w + x])) mask[(y - top) * rw + x - left] = 1;
        List<Component> components = components(mask, rw, rh, left, top);
        Component body = null;
        for (Component c : components) {
            if (c.area < .70 * d * d || c.right < a.right + .80 * d || c.left > a.right
                    || c.top > a.top + .25 * d || c.bottom < a.bottom - .15 * d
                    || c.right - c.left < 2.20 * d || c.right - c.left > 4.20 * d
                    || c.bottom - c.top < .80 * d || c.bottom - c.top > 1.70 * d) continue;
            if (body != null) return null;
            body = c;
        }
        if (body == null || body.left <= left || body.top <= top || body.right >= right || body.bottom >= bottom) return null;
        int extentL = body.left, extentT = body.top, extentR = body.right, extentB = body.bottom;
        // Small separated warm/gold details may join through neutral outlines. Never collect lower controls.
        for (Component c : components) {
            if (c == body || c.area < Math.max(2, d * d * .0002) || c.area > body.area * .25
                    || c.right > a.right + .30 * d || c.top < body.top - .25 * d
                    || c.bottom > body.bottom + .06 * d || gap(c, body) > .08 * d) continue;
            extentL = Math.min(extentL, c.left); extentT = Math.min(extentT, c.top);
            extentR = Math.max(extentR, c.right); extentB = Math.max(extentB, c.bottom);
        }
        int pad = Math.max(1, (int) Math.round(d * .025));
        int sampleL = Math.max(a.right + (int) (.25 * d), body.left + (body.right - body.left) / 2);
        int sampleR = body.right - (int) (.16 * d);
        int upper = edge(p, w, h, sampleL, sampleR, Math.max(top, body.top),
                Math.min(bottom - 3 * pad, (int) (a.top + .20 * d)), pad, true, false, d);
        int lower = edge(p, w, h, sampleL, sampleR, Math.max(top + 3 * pad, body.bottom - (int) (.25 * d)),
                Math.min(bottom - 3 * pad, body.bottom + (int) (.10 * d)), pad, false, false, d);
        int side = edge(p, w, h, a.top + (int) (.30 * d), a.top + (int) (.70 * d),
                body.right - (int) (.12 * d), Math.min(w - 3 * pad - 1, body.right + (int) (.12 * d)),
                pad, false, true, d);
        if (upper < 0 || lower < 0 || side < 0 || lower <= upper || side <= sampleL) return null;
        double panelHeight = lower - upper;
        if (panelHeight < .75 * d || panelHeight > 1.30 * d || Math.abs(side - body.right) > .10 * d) return null;
        int cornerX = side - (int) (.10 * panelHeight);
        int cornerTop = upper + (int) (.15 * panelHeight), cornerBottom = lower - (int) (.15 * panelHeight);
        if (fraction(p, w, h, cornerX - pad, cornerTop - pad, cornerX + pad, cornerTop + pad, false) < .50
                || fraction(p, w, h, cornerX - pad, cornerBottom - pad, cornerX + pad, cornerBottom + pad, false) < .50
                || fraction(p, w, h, a.right, upper + pad, side - pad, upper + 3 * pad, false) < .60
                || fraction(p, w, h, a.right, lower - 3 * pad, side - pad, lower - pad, false) < .60
                || fraction(p, w, h, a.right, upper + pad, side - pad, lower - pad, false) < .36) return null;
        int fieldL = Math.max(a.right + (int) (.30 * d), body.left + (int) ((body.right - body.left) * .48));
        int fieldR = side - (int) (.08 * panelHeight);
        int fieldT = upper + (int) (.48 * panelHeight), fieldB = lower - (int) (.12 * panelHeight);
        // Text/progress structure, not a required green skin. Flat wood is insufficient evidence.
        if (fieldEdges(p, w, h, fieldL, fieldT, fieldR, fieldB) < .045
                || fraction(p, w, h, a.right + pad, upper + (int) (.12 * panelHeight),
                        fieldR, upper + (int) (.43 * panelHeight), false) < .30) return null;
        int small = Math.max(2, (int) Math.ceil(panelHeight * .05) + 1);
        int shadow = Math.max(3, (int) Math.ceil(panelHeight * .10) + 1);
        int l = extentL - small, t = extentT - small, r = Math.max(extentR, side) + shadow, b = extentB + shadow;
        for (Component c : components) {
            if (c == body || c.top < extentB || c.left >= r || c.right <= l || c.area < .08 * d * d) continue;
            // A separate lower control is not plate shadow. Require a real gap and leave it outside the crop.
            if (c.top - extentB < .08 * d) return null;
            b = Math.min(b, c.top - pad);
        }
        if (b - extentB < pad) return null;
        if (l < 0 || t < 0 || r >= w || b >= h || r >= right || b >= bottom) return null;
        if (fraction(p, w, h, l, t, r, t + pad, false) > .05
                || fraction(p, w, h, l, b - pad, r, b, false) > .05
                || fraction(p, w, h, l, t, l + pad, b, false) > .05
                || fraction(p, w, h, r - pad, t, r, b, false) > .05) return null;
        return new Bounds(l, t, r, b);
    }

    private static boolean foreground(int c) {
        int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
        return r > 55 && g > 45 && b * 100 < Math.min(r, g) * 94;
    }

    private static final class Component {
        int left, top, right, bottom, area;
        Component(int x, int y) { left = x; right = x + 1; top = y; bottom = y + 1; }
        void add(int x, int y) { left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x + 1); bottom = Math.max(bottom, y + 1); area++; }
    }

    private static List<Component> components(byte[] mask, int w, int h, int left, int top) {
        List<Component> found = new ArrayList<>(); int[] queue = new int[mask.length];
        for (int start = 0; start < mask.length; start++) {
            if (mask[start] != 1) continue;
            int head = 0, tail = 0; queue[tail++] = start; mask[start] = 2;
            Component c = new Component(left + start % w, top + start / w);
            while (head < tail) {
                int at = queue[head++], x = at % w, y = at / w; c.add(left + x, top + y);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int next = ny * w + nx;
                    if (mask[next] == 1) { mask[next] = 2; queue[tail++] = next; }
                }
            }
            found.add(c);
            if (found.size() > 4096) return new ArrayList<>(); // Excessive fragmented noise is not a trusted panel.
        }
        return found;
    }

    private static int gap(Component a, Component b) {
        return Math.max(Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right)),
                Math.max(0, Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom)));
    }

    private static double fieldEdges(int[] p, int w, int h, int l, int t, int r, int b) {
        if (l < 0 || t < 0 || r >= w || b >= h || r - l < 4 || b - t < 4) return 0;
        int edges = 0, total = 0;
        for (int y = t; y < b - 2; y += 2) for (int x = l; x < r - 2; x += 2) {
            int v = luma(p[y * w + x]);
            if (Math.abs(v - luma(p[y * w + x + 2])) >= 35 || Math.abs(v - luma(p[(y + 2) * w + x])) >= 35) edges++;
            total++;
        }
        return total == 0 ? 0 : (double) edges / total;
    }

    private static int luma(int c) { return ((c >> 16 & 255) * 3 + (c >> 8 & 255) * 6 + (c & 255)) / 10; }

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
