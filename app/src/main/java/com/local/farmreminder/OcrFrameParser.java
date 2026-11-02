package com.local.farmreminder;

import java.util.ArrayList;
import java.util.List;

/** Conservative, frame-local interpretation of OCR text and geometry. */
public final class OcrFrameParser {
    private OcrFrameParser() { }
    public static String friend(List<OcrLine> lines, int width, int height) {
        if (width <= 0 || height <= 0) return null;
        boolean farm = false;
        for (OcrLine line : lines) {
            String s = line.text.replaceAll("\\s+", "");
            if (s.equals("回家") && line.left >= width * .65 && line.top >= height * .65) farm = true;
        }
        if (!farm) return null;
        String candidate = null;
        for (OcrLine line : lines) {
            if (line.left < width * .14 || line.right > width * .50 || line.top < height * .035 || line.bottom > height * .12) continue;
            String name = line.text.replaceAll("\\s+", "").replaceFirst("^好友[:：]", "").replaceFirst("的农场$", "");
            if (!name.matches("[\\p{L}\\p{N}_·]{2,16}") || !name.matches(".*[\\p{L}].*") ||
                    name.matches("[0-9]+[万亿级]?") || name.matches(".*(农场|好友|成熟|等级|返回|回家|收获|种植|金币|经验|分钟|小时).*")) continue;
            if (candidate != null) return null;
            candidate = name;
        }
        return candidate;
    }

    public static Long countdown(List<OcrLine> lines) {
        List<String> candidates = new ArrayList<>();
        for (OcrLine line : lines) candidates.add(line.text);
        for (OcrLine time : lines) {
            if (!time.text.trim().matches("[0-9]{1,2}\\s*[:：]\\s*[0-9]{2}\\s*[:：]\\s*[0-9]{2}")) continue;
            for (OcrLine suffix : lines) {
                if (!suffix.text.replaceAll("\\s+", "").equals("后成熟") || !near(time, suffix)) continue;
                candidates.add(time.text + "后成熟");
            }
        }
        return RecognitionLogic.parseCountdown(candidates);
    }
    private static boolean near(OcrLine a, OcrLine b) {
        int h = Math.max(1, Math.max(a.bottom - a.top, b.bottom - b.top));
        int verticalOverlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        int horizontalOverlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        boolean sameRow = verticalOverlap > 0 && b.left >= a.right && b.left - a.right <= h;
        boolean nextRow = horizontalOverlap > 0 && b.top >= a.bottom && b.top - a.bottom <= h / 2;
        return sameRow || nextRow;
    }
}
