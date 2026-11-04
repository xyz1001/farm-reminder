package com.local.farmreminder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Explicit asset kind is selected by its source ref, never inferred from a bitmap aspect ratio. */
final class ReminderImagePresentation {
    static final int CACHE_ENTRIES = 4, REQUEST_LIMIT = 4, PLATE_MAX_PIXELS = 262144;
    enum Kind { PORTRAIT, PLATE }
    enum Style { TEXT, BIG_PICTURE }
    static String key(Kind kind, String ref) { return kind.name() + ":" + ref; }
    static Style style(boolean realReminder, boolean validPlate) { return realReminder && validPlate ? Style.BIG_PICTURE : Style.TEXT; }
    static String plateRef(List<Reminder> records) {
        List<Reminder> ordered = new ArrayList<>(records);
        ordered.sort(Comparator.comparingLong((Reminder r) -> r.matureAt).thenComparing(r -> r.id));
        for (Reminder r : ordered) {
            String ref = NameplateRefPolicy.normalize(r.plateRef);
            if (!r.fired && !ref.isEmpty()) return ref;
        }
        return "";
    }
    static String signature(List<Reminder> records) {
        StringBuilder out = new StringBuilder();
        for (Reminder r : records) {
            String ref = NameplateRefPolicy.normalize(r.plateRef);
            out.append(r.id.length()).append(':').append(r.id).append('|').append(ref.length()).append(':').append(ref).append(';');
        }
        return out.toString();
    }
    static String summary(List<Reminder> records) {
        String text = ReminderNotificationText.expandedText(records);
        int newline = text.indexOf('\n');
        String first = newline < 0 ? text : text.substring(0, newline);
        if (first.startsWith("1. ")) first = first.substring(3);
        return records.size() + "条提醒 · " + first;
    }
    static int[] fit(int width, int height, int maxWidth, int maxHeight) {
        if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) return new int[]{0, 0};
        double scale = Math.min((double) maxWidth / width, (double) maxHeight / height);
        return new int[]{Math.max(1, (int) Math.floor(width * scale)), Math.max(1, (int) Math.floor(height * scale))};
    }
    private ReminderImagePresentation() { }
}
