package com.local.farmreminder;

import java.util.Collections;
import java.util.List;

/** Identifies the header's name field, without interpreting other HUD fields as names. */
final class FriendNameResolver {
    static final class Region {
        final int left, top, right, bottom;
        Region(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
    }

    static final class Resolution {
        final String text, source;
        final boolean needsAssistance;
        final Region region;
        Resolution(String text, String source, boolean needsAssistance, Region region) {
            this.text = text; this.source = source; this.needsAssistance = needsAssistance; this.region = region;
        }
    }

    static Resolution resolve(List<OcrLine> fullLines, int width, int height) {
        return resolve(fullLines, Collections.emptyList(), width, height);
    }

    static Resolution resolve(List<OcrLine> fullLines, List<OcrLine> assistedLines, int width, int height) {
        int w = Math.max(1, width), h = Math.max(1, height);
        OcrLine bar = null;
        boolean hud = false;
        for (OcrLine line : fullLines) {
            String compact = line.text.replaceAll("\\s+", "");
            // An experience fraction is a HUD field only in its lower-header position.
            if (line.top >= h * .083 && line.top < h * .13 && line.left < w * .5 &&
                    compact.matches(".*[0-9]+(?:\\.[0-9]+)?万?[/／][0-9]+(?:\\.[0-9]+)?万?.*")) {
                if (bar == null || line.top < bar.top) bar = line;
            }
            if (line.top > h * .3 && (compact.equals("回家") || compact.equals("打个招呼") ||
                    compact.contains("后成熟") || compact.contains("后戌熟") || compact.contains("小叶子"))) hud = true;
        }
        hud |= bar != null;
        int left = clamp((int) Math.floor(w * .14), 0, w - 1);
        int right = clamp((int) Math.ceil(w * .56), left + 1, w);
        int top = clamp((int) Math.floor(h * .045), 0, h - 1);
        int bottom = clamp((int) Math.ceil(h * .085), top + 1, h);
        if (bar != null) bottom = clamp(Math.min(bottom, bar.top), top + 1, h);
        Region region = new Region(left, top, right, bottom);
        String name = select(fullLines, region, w, h, hud);
        if (name != null) return new Resolution(name, "full", false, region);
        name = select(assistedLines, region, w, h, true);
        if (name != null) return new Resolution(name, "header", false, region);
        if (!hud) {
            name = select(fullLines, region, w, h, false);
            if (name != null) return new Resolution(name, "full", false, region);
        }
        return new Resolution(null, "unknown", true, region);
    }

    private static String select(List<OcrLine> lines, Region region, int w, int h, boolean headerOnly) {
        String best = null;
        boolean bestExplicit = false;
        double bestScore = Double.POSITIVE_INFINITY;
        for (OcrLine line : lines) {
            if (headerOnly && (line.left >= region.right || line.right <= region.left ||
                    line.top < region.top || line.bottom > region.bottom || line.bottom <= line.top)) continue;
            String text = line.text.trim();
            String compact = text.replaceAll("\\s+", "");
            if (compact.isEmpty() || compact.matches("[0-9:：]+") && compact.matches(".*[:：].*") ||
                    compact.matches("后[成戌]熟") || compact.matches("[0-9:：]+后[成戌]熟")) continue;
            boolean explicit = text.matches("^好友\\s*[:：].*") || text.endsWith("的农场");
            String name = text.replaceFirst("^好友\\s*[:：]\\s*", "").replaceFirst("的农场$", "").trim();
            if (name.isEmpty()) continue;
            double score = (double) line.top / h + (double) line.left / w * .1;
            if (best == null || explicit && !bestExplicit || explicit == bestExplicit && score < bestScore) {
                best = name; bestExplicit = explicit; bestScore = score;
            }
        }
        return best;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
