package com.local.farmreminder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure geometry/context parser for fresh OCR mapped into the associated original screenshot. */
final class NotificationIdentityParser {
    static final class TextRow {
        final OcrLine line;
        final List<OcrLine> elements;
        TextRow(OcrLine line, List<OcrLine> elements) {
            this.line = line;
            this.elements = Collections.unmodifiableList(elements == null ? new ArrayList<>() : new ArrayList<>(elements));
        }
    }

    /** Compatibility only: a flat element must have exactly one containing parent, never an invented row. */
    static NotificationIdentityLabel parse(List<OcrLine> lines, List<OcrLine> elements,
            AvatarLocator.Bounds avatar, int width, int height, boolean friendScene, boolean completeBadge) {
        List<TextRow> rows = new ArrayList<>();
        List<List<OcrLine>> children = new ArrayList<>();
        if (lines != null) for (OcrLine ignored : lines) children.add(new ArrayList<>());
        boolean unambiguous = true;
        if (elements != null) for (OcrLine element : elements) {
            int parent = -1;
            if (valid(element, width, height) && lines != null) for (int i = 0; i < lines.size(); i++) {
                OcrLine line = lines.get(i);
                if (valid(line, width, height) && contains(line, element)) {
                    if (parent != -1) { parent = -2; break; }
                    parent = i;
                }
            }
            if (parent < 0) unambiguous = false;
            else children.get(parent).add(element);
        }
        if (lines != null) for (int i = 0; i < lines.size(); i++) rows.add(new TextRow(lines.get(i), children.get(i)));
        return parseRows(rows, avatar, width, height, friendScene, completeBadge && unambiguous);
    }

    static NotificationIdentityLabel parseRows(List<TextRow> rows, AvatarLocator.Bounds avatar,
            int width, int height, boolean friendScene, boolean completeBadge) {
        if (!friendScene || width <= 0 || height <= 0 || avatar == null || avatar.left < 0 || avatar.top < 0
                || avatar.right <= avatar.left || avatar.bottom <= avatar.top
                || avatar.right > width || avatar.bottom > height) return NotificationIdentityLabel.EMPTY;
        double d = (double) avatar.right - avatar.left;
        Region name = new Region(avatar.left + .95 * d, avatar.top + .10 * d,
                avatar.left + 2.65 * d, avatar.top + .50 * d);
        Set<String> names = new HashSet<>();
        if (name.inside(width, height) && rows != null) for (TextRow row : rows) {
            if (row == null || !valid(row.line, width, height) || !name.contains(row.line)) continue;
            String safe = NotificationIdentityLabel.safeName(row.line.text);
            if (!safe.isEmpty()) names.add(safe);
        }
        String selected = names.size() == 1 ? names.iterator().next() : null;
        Region badge = new Region(avatar.left + .68 * d, avatar.top + .44 * d,
                avatar.left + 1.20 * d, avatar.top + .88 * d);
        Set<Integer> levels = new HashSet<>();
        if (completeBadge && badge.inside(width, height) && rows != null) for (TextRow row : rows) {
            if (row == null || !valid(row.line, width, height) || !badge.intersects(row.line)) continue;
            if (row.elements.isEmpty()) {
                // Whole independent lines remain usable, but a wide merged row cannot donate a prefix.
                if (badge.contains(row.line) && !crossesAnotherRow(row.line, row, rows, width, height)) {
                    Integer value = level(row.line.text);
                    if (value != null) levels.add(value);
                }
                continue;
            }
            if (!consistent(row, rows, width, height)) continue;
            String parent = compact(row.line.text);
            // In a wholly contained badge row the entire syntax must itself be a valid level.
            if (badge.contains(row.line) && level(parent) == null) continue;
            for (int i = 0; i < row.elements.size(); i++) {
                OcrLine element = row.elements.get(i);
                Integer value = level(element.text);
                if (value == null || !badge.contains(element)) continue;
                if (independent(i, row.elements, badge)) levels.add(value);
            }
        }
        return NotificationIdentityLabel.of(selected, levels.size() == 1 ? levels.iterator().next() : null);
    }

    private static boolean consistent(TextRow row, List<TextRow> rows, int width, int height) {
        List<OcrLine> ordered = new ArrayList<>(row.elements);
        for (OcrLine element : ordered) {
            if (!valid(element, width, height) || !contains(row.line, element)
                    || crossesAnotherRow(element, row, rows, width, height)) return false;
        }
        ordered.sort(Comparator.comparingInt((OcrLine line) -> line.left).thenComparingInt(line -> line.top));
        StringBuilder reconstructed = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            String token = compact(ordered.get(i).text);
            if (token == null || token.isEmpty()) return false;
            reconstructed.append(token);
            for (int j = 0; j < i; j++) if (overlap(ordered.get(i), ordered.get(j))) return false;
        }
        String parent = compact(row.line.text);
        // Missing punctuation, changed digits or contradictory parent/child metadata are not guessed.
        return parent != null && parent.contentEquals(reconstructed);
    }

    private static boolean crossesAnotherRow(OcrLine element, TextRow owner, List<TextRow> rows, int width, int height) {
        for (TextRow other : rows) if (other != null && other != owner && valid(other.line, width, height)
                && overlap(other.line, element)) return true;
        return false;
    }

    private static boolean independent(int candidate, List<OcrLine> elements, Region badge) {
        boolean[] connected = new boolean[elements.size()];
        connected[candidate] = true;
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < elements.size(); i++) if (connected[i]) {
                for (int j = 0; j < elements.size(); j++) if (!connected[j] && adjacent(elements.get(i), elements.get(j))) {
                    connected[j] = true; changed = true;
                }
            }
        } while (changed);
        OcrLine number = elements.get(candidate);
        int suffixes = 0;
        for (int i = 0; i < elements.size(); i++) {
            if (i == candidate || !connected[i] && !badge.intersects(elements.get(i))) continue;
            OcrLine other = elements.get(i);
            // A separate, attached 级 is the only permitted syntactic neighbor inside the badge.
            boolean suffix = connected[i] && badge.contains(other) && "级".equals(compact(other.text))
                    && number.text.trim().matches("[1-9][0-9]{0,3}") && other.left >= number.right;
            if (!suffix || ++suffixes > 1) return false;
        }
        return true;
    }

    private static boolean adjacent(OcrLine a, OcrLine b) {
        long minHeight = Math.min((long) a.bottom - a.top, (long) b.bottom - b.top);
        long vertical = Math.min(a.bottom, b.bottom) - (long) Math.max(a.top, b.top);
        long gap = Math.max(a.left, b.left) - (long) Math.min(a.right, b.right);
        // Width-independent baseline/gap evidence scales with the actual text height, not screen pixels.
        return vertical >= minHeight * .45 && gap <= Math.max(1, minHeight * .35);
    }

    private static Integer level(String text) {
        if (text == null) return null;
        String token = text.trim();
        if (!token.matches("[1-9][0-9]{0,3}(?:级)?")) return null;
        return Integer.parseInt(token.endsWith("级") ? token.substring(0, token.length() - 1) : token);
    }

    private static String compact(String text) {
        if (text == null || text.length() > 4096) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length();) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) return null;
            } else if (Character.isLowSurrogate(ch)) return null;
            int cp = text.codePointAt(i);
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp == 0x061C
                    || cp == 0x200E || cp == 0x200F || cp >= 0x202A && cp <= 0x202E
                    || cp >= 0x2066 && cp <= 0x206F) return null;
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) out.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static boolean valid(OcrLine line, int width, int height) {
        return line != null && line.text != null && line.left >= 0 && line.top >= 0
                && line.right > line.left && line.bottom > line.top && line.right <= width && line.bottom <= height;
    }

    private static boolean contains(OcrLine outer, OcrLine inner) {
        return inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom;
    }

    private static boolean overlap(OcrLine a, OcrLine b) {
        return a.right > b.left && a.left < b.right && a.bottom > b.top && a.top < b.bottom;
    }

    private static final class Region {
        final double left, top, right, bottom;
        Region(double left, double top, double right, double bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        boolean inside(int width, int height) { return left >= 0 && top >= 0 && right <= width && bottom <= height; }
        boolean contains(OcrLine line) {
            return line.left >= left && line.top >= top && line.right <= right && line.bottom <= bottom;
        }
        boolean intersects(OcrLine line) {
            return line.right > left && line.left < right && line.bottom > top && line.top < bottom;
        }
    }
}
