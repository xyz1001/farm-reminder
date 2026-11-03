package com.local.farmreminder;

import java.util.List;

/** Conservative, local-only identity check; OCR text without a valid box is ignored. */
final class FarmIdentityRecognition {
    enum Outcome { LOADING, HOME, NO_MATCH }

    static Outcome classify(List<OcrLine> lines, int width, int height) {
        if (lines == null || width <= 0 || height <= 0) return Outcome.NO_MATCH;
        boolean fullLogo = false, logo = false, help = false, percent = false;
        boolean warehouse = false, shop = false, pet = false, otherFarm = false;
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
            if (text.contains("回家") || text.contains("好友家")) otherFarm = true;
            if (y >= .9) {
                if (x < .25 && text.equals("仓库")) warehouse = true;
                if (x >= .15 && x <= .4 && text.equals("商店")) shop = true;
                if (x >= .3 && x <= .55 && text.equals("宠物")) pet = true;
            }
        }
        if (fullLogo || (logo && (help || percent))) return Outcome.LOADING;
        if (!otherFarm && warehouse && shop && pet) return Outcome.HOME;
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
