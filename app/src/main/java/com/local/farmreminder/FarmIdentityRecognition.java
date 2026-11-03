package com.local.farmreminder;

import java.util.List;

/** Local-only identity check; OCR text without a valid box is ignored. */
final class FarmIdentityRecognition {
    enum Outcome { LOADING, HOME, NO_MATCH }
    private static final String[] MENU_WORDS = {"仓库", "商店", "宠物", "图鉴", "装扮", "好友"};

    static Outcome classify(List<OcrLine> lines, int width, int height) {
        if (lines == null || width <= 0 || height <= 0) return Outcome.NO_MATCH;
        boolean fullLogo = false, logo = false, help = false, percent = false;
        boolean[] menuWords = new boolean[MENU_WORDS.length];
        for (OcrLine line : lines) {
            if (line == null || line.left < 0 || line.top < 0 || line.right <= line.left
                    || line.bottom <= line.top || line.right > width || line.bottom > height) continue;
            double x = ((double) line.left + line.right) / (2.0 * width);
            double y = ((double) line.top + line.bottom) / (2.0 * height);
            String text = normalize(line.text);
            if (x >= .2 && x <= .8 && y >= .45 && y <= .60) {
                if (text.equals("QQ经典农场")) fullLogo = true;
                if (text.equals("经典农场")) logo = true;
            }
            if (x >= .55 && y >= .15 && y <= .30 && text.equals("帮助中心")) help = true;
            if (x >= .2 && x <= .8 && y >= .60 && y <= .75
                    && text.matches("[0-9]{1,3}(\\.[0-9]+)?%")) {
                try { percent = Double.parseDouble(text.substring(0, text.length() - 1)) <= 100; }
                catch (NumberFormatException ignored) { }
            }
            for (int i = 0; i < MENU_WORDS.length; i++) {
                String menuText = i == MENU_WORDS.length - 1 ? text.replace("好友家", "\0") : text;
                if (menuText.contains(MENU_WORDS[i])) menuWords[i] = true;
            }
        }
        if (fullLogo || (logo && (help || percent))) return Outcome.LOADING;
        int count = 0;
        for (boolean found : menuWords) if (found) count++;
        if (count >= 3) return Outcome.HOME;
        return Outcome.NO_MATCH;
    }

    private static String normalize(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
            if (c == 'ｑ' || c == 'Ｑ' || c == 'q') c = 'Q';
            if (c == '％') c = '%';
            out.append(c);
        }
        return out.toString();
    }
}
