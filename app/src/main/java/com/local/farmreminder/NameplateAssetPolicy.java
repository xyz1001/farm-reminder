package com.local.farmreminder;

/** Bounds and fit math shared by saving and loading; no pixels or Android dependencies. */
final class NameplateAssetPolicy {
    static final int MAX_EDGE = 768, MAX_PIXELS = 262144, WORK_PIXELS = 4_000_000;
    static final int NOTIFY_WIDTH = 360, NOTIFY_HEIGHT = 180, NOTIFY_INSET = 8;
    static final class Size {
        final int width, height;
        Size(int width, int height) { this.width = width; this.height = height; }
    }
    private NameplateAssetPolicy() { }
    static boolean wide(int w, int h) { return w > 0 && h > 0 && (long) w * 2 >= (long) h * 3 && (long) w * 2 <= (long) h * 9; }
    static boolean stored(int w, int h) { return wide(w, h) && w <= MAX_EDGE && h <= MAX_EDGE && (long) w * h <= MAX_PIXELS; }
    static Size fit(int w, int h, int maxW, int maxH, int maxPixels) {
        if (w <= 0 || h <= 0 || maxW <= 0 || maxH <= 0 || maxPixels <= 0) return null;
        double factor = Math.min(1, Math.min((double) maxW / w, Math.min((double) maxH / h, Math.sqrt((double) maxPixels / ((long) w * h)))));
        int nw = Math.max(1, (int) Math.floor(w * factor)), nh = Math.max(1, (int) Math.floor(h * factor));
        return new Size(nw, nh);
    }
    static Size notificationFit(int w, int h) {
        return fit(w, h, NOTIFY_WIDTH - 2 * NOTIFY_INSET, NOTIFY_HEIGHT - 2 * NOTIFY_INSET, 65536);
    }
}
