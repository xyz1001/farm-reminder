package com.local.farmreminder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Frame-local interpretation of OCR text and geometry. */
public final class OcrFrameParser {
    private OcrFrameParser() { }

    public static String friend(List<OcrLine> lines, int width, int height) {
        String best = null;
        boolean bestExplicit = false;
        double bestScore = Double.POSITIVE_INFINITY;
        for (OcrLine line : lines) {
            String text = line.text.trim();
            String compact = text.replaceAll("\\s+", "");
            if (compact.isEmpty() || compact.matches("[0-9:：]+") && compact.matches(".*[:：].*") ||
                    compact.equals("后成熟") ||
                    compact.matches("[0-9:：]+后成熟")) continue;
            boolean prefix = text.matches("^好友\\s*[:：].*");
            boolean suffix = text.endsWith("的农场");
            String name = text.replaceFirst("^好友\\s*[:：]\\s*", "").replaceFirst("的农场$", "").trim();
            if (name.isEmpty()) continue;
            boolean explicit = prefix || suffix;
            // Geometry ranks candidates; it never excludes one.
            double score = (double) line.top / Math.max(1, height) +
                    (double) line.left / Math.max(1, width) * .1;
            if (best == null || explicit && !bestExplicit || explicit == bestExplicit && score < bestScore) {
                best = name;
                bestExplicit = explicit;
                bestScore = score;
            }
        }
        return best;
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
                remaining = remaining.replaceAll("(?<![0-9])[0-9]+\\s*[:：]\\s*[0-9]+\\s*[:：]\\s*[0-9]+\\s*后\\s*成\\s*熟", " ");
            }
            String text = remaining.replaceAll("\\s+", "");
            if (text.isEmpty()) continue;
            // A stage-labelled countdown is not an unlabelled maturity timer.
            if (text.contains("小叶子")) { pending.setLength(0); continue; }
            if (text.matches("[0-9:：后成熟]+")) {
                // A complete timer starts independently. Separate digit-only OCR
                // tokens also start anew unless a colon connects the fragments.
                if (text.matches("[0-9]+[:：][0-9]+[:：][0-9]+.*") ||
                        pending.length() > 0 && Character.isDigit(pending.charAt(pending.length() - 1)) &&
                                Character.isDigit(text.charAt(0))) pending.setLength(0);
                pending.append(text);
                if (pending.indexOf("后成熟") >= 0) {
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
