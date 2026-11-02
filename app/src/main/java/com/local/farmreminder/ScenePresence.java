package com.local.farmreminder;

import java.util.List;

/** Evidence of a farm-like scene only, independent of friend and countdown acceptance. */
final class ScenePresence {
    enum Change { NONE, ENTER, EXIT }
    private boolean present;
    private int hits, misses;
    boolean present() { return present; }
    void breakStreak() { hits = 0; misses = 0; }
    Change observe(boolean farmEvidence) {
        if (farmEvidence) {
            misses = 0;
            if (!present && ++hits >= 2) { present = true; hits = 0; return Change.ENTER; }
        } else {
            hits = 0;
            if (present && ++misses >= 3) return leave();
        }
        return Change.NONE;
    }
    Change leave() {
        boolean wasPresent = present;
        present = false; hits = 0; misses = 0;
        return wasPresent ? Change.EXIT : Change.NONE;
    }
    static boolean isFarmScene(List<OcrLine> lines, int width, int height) {
        if (width <= 0 || height <= 0) return false;
        boolean header = false, home = false, sign = false;
        int bottomFlags = 0;
        String[] labels = {"仓库", "商店", "宠物", "图鉴", "装扮", "好友"};
        for (OcrLine line : lines) {
            String s = line.text.replaceAll("\\s+", "");
            if (s.isEmpty()) continue;
            if (line.left >= width * .10 && line.right <= width * .56 && line.top >= height * .025 && line.bottom <= height * .14) header = true;
            if (s.equals("回家") && line.left >= width * .65 && line.top >= height * .65) home = true;
            if (s.equals("劳动光荣") && line.left >= width * .10 && line.right <= width * .65 && line.top >= height * .28 && line.bottom <= height * .55) sign = true;
            if (line.top >= height * .85) for (int i = 0; i < labels.length; i++) if (s.equals(labels[i])) bottomFlags |= 1 << i;
        }
        return header && (home || sign || Integer.bitCount(bottomFlags) >= 2);
    }
}
