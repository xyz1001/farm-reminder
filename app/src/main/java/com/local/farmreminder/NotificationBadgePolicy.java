package com.local.farmreminder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Isolated fixed badge slot for the known header layout; unknown layouts fail softly. */
final class NotificationBadgePolicy {
    static final int SCALE = 3;
    private static final long MAX_SCALED_PIXELS = 250_000;
    static int[] crop(AvatarLocator.Bounds avatar, int width, int height) {
        if (avatar == null || avatar.left < 0 || avatar.top < 0 || avatar.right > width
                || avatar.bottom > height || avatar.right <= avatar.left || avatar.bottom <= avatar.top) return null;
        double d = avatar.right - avatar.left;
        double left = avatar.left + .68 * d, top = avatar.top + .44 * d;
        double right = avatar.left + 1.20 * d, bottom = avatar.top + .88 * d;
        return cropRegion(new NotificationIdentityParser.Region(left, top, right, bottom), width, height);
    }
    static int[] cropRegion(NotificationIdentityParser.Region region, int width, int height) {
        if (region == null) return null;
        double left = region.left, top = region.top, right = region.right, bottom = region.bottom;
        if (left < 0 || top < 0 || right > width || bottom > height) return null;
        int l = (int) Math.floor(left), t = (int) Math.floor(top);
        int r = (int) Math.ceil(right), b = (int) Math.ceil(bottom);
        if (r <= l || b <= t || (long) (r-l) * (b-t) * SCALE * SCALE > MAX_SCALED_PIXELS) return null;
        return new int[]{l, t, r, b};
    }
    static int plateScale(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        long pixels = (long) width * height;
        for (int scale = 5; scale >= 1; scale--)
            if (pixels <= MAX_SCALED_PIXELS / (scale * scale)) return scale;
        return 0;
    }
    static Integer level(List<NotificationIdentityParser.TextRow> rows, int width, int height) {
        if (rows == null || rows.size() != 1) return null;
        NotificationIdentityParser.TextRow row = rows.get(0);
        if (row == null || !safe(row.line, width, height) || !syntax(row.line.text)) return null;
        if (!row.elements.isEmpty()) {
            List<OcrLine> ordered = new ArrayList<>(row.elements);
            ordered.sort(Comparator.comparingInt(a -> a.left));
            StringBuilder joined = new StringBuilder();
            int right = -1;
            for (OcrLine child : ordered) {
                if (!safe(child, width, height) || child.left < row.line.left || child.top < row.line.top
                        || child.right > row.line.right || child.bottom > row.line.bottom || child.left < right) return null;
                joined.append(child.text); right = child.right;
            }
            if (!row.line.text.equals(joined.toString())) return null;
        }
        String text = row.line.text;
        return Integer.valueOf(text.endsWith("级") ? text.substring(0, text.length()-1) : text);
    }
    static NotificationIdentityLabel finish(NotificationIdentityLabel label, Integer level,
            long parseDeadline, NotificationOcrBudget.Clock clock) {
        return level == null || NotificationOcrBudget.remaining(parseDeadline, clock) <= 0
                ? label : label.withLevel(level);
    }
    private static boolean syntax(String text) { return text != null && text.matches("[1-9][0-9]{0,3}(?:级)?"); }
    private static boolean safe(OcrLine line, int w, int h) {
        return line != null && line.text != null && line.left >= 2 && line.top >= 2
                && line.right > line.left && line.bottom > line.top && line.right <= w-2 && line.bottom <= h-2;
    }
    private NotificationBadgePolicy() { }
}
