package com.local.farmreminder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Frame-local interpretation of OCR text and geometry. */
public final class OcrFrameParser {
    private OcrFrameParser() { }

    public static String friend(List<OcrLine> lines, int width, int height) {
        return FriendNameResolver.resolve(lines, width, height).text;
    }

    public static String reminderName(List<OcrLine> lines, int width, int height) {
        String name = friend(lines, width, height);
        return name == null || name.trim().isEmpty() ? "昵称未识别" : name;
    }

    public static Long countdown(List<OcrLine> lines) {
        List<Long> values = countdowns(lines);
        return values.isEmpty() ? null : values.get(0);
    }

    public static List<Long> countdowns(List<OcrLine> lines) {
        List<OcrLine> ordered = new ArrayList<>(lines);
        Collections.sort(ordered, Comparator.comparingInt((OcrLine line) -> line.top)
                .thenComparingInt(line -> line.left));
        List<Long> values = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (OcrLine line : ordered) {
            // Parse complete matches before interpreting any remaining fragments;
            // keep whitespace here so adjacent timers do not merge their digits.
            List<Long> inline = RecognitionLogic.parseCountdowns(Collections.singletonList(line.text));
            addDistinct(values, inline);
            String remaining = line.text;
            if (!inline.isEmpty()) {
                remaining = remaining.replaceAll("(?<![0-9])[0-9]+\\s*[:：]\\s*[0-9]+\\s*[:：]\\s*[0-9]+\\s*后\\s*[成戌]\\s*熟", " ");
            }
            String text = remaining.replaceAll("\\s+", "");
            if (text.isEmpty()) continue;
            // A stage-labelled countdown is not an unlabelled maturity timer.
            if (text.contains("小叶子")) { pending.setLength(0); continue; }
            if (text.matches("[0-9:：后成熟戌]+")) {
                // A complete timer starts independently. Separate digit-only OCR
                // tokens also start anew unless a colon connects the fragments.
                if (text.matches("[0-9]+[:：][0-9]+[:：][0-9]+.*") ||
                        pending.length() > 0 && Character.isDigit(pending.charAt(pending.length() - 1)) &&
                                Character.isDigit(text.charAt(0))) pending.setLength(0);
                pending.append(text);
                if (pending.indexOf("后成熟") >= 0 || pending.indexOf("后戌熟") >= 0) {
                    addDistinct(values, RecognitionLogic.parseCountdowns(Collections.singletonList(pending.toString())));
                    pending.setLength(0);
                }
            }
        }
        return values;
    }

    private static void addDistinct(List<Long> values, List<Long> additions) {
        for (Long value : additions) if (!values.contains(value)) values.add(value);
    }
}
