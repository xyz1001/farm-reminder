package com.local.farmreminder;

import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, conservative parsing and temporal confirmation. One string is one OCR line. */
public final class RecognitionLogic {
    private static final Pattern MATURITY = Pattern.compile("(?<![0-9])([0-9]+)\\s*[:：]\\s*([0-9]+)\\s*[:：]\\s*([0-9]+)\\s*后\\s*([成戌咸])\\s*熟");
    private static final Pattern STAGE_PREFIX = Pattern.compile("小叶子\\s*[:：]?\\s*$");
    private String candidateFriend;
    private long candidateMaturity;
    private int count;

    public static Long parseCountdown(List<String> lines) {
        List<Long> found = parseCountdowns(lines);
        return found.isEmpty() ? null : found.get(0);
    }

    public static List<Long> parseCountdowns(List<String> lines) {
        List<Long> found = new ArrayList<>();
        for (String line : lines) {
            if (line == null) continue;
            Matcher m = MATURITY.matcher(line);
            while (m.find()) {
                if ("咸".equals(m.group(4)) && STAGE_PREFIX.matcher(line.substring(0, m.start())).find()) continue;
                try {
                    long h = Long.parseLong(m.group(1)), min = Long.parseLong(m.group(2)), sec = Long.parseLong(m.group(3));
                    if (min >= 60 || sec >= 60) continue;
                    long value = Math.multiplyExact(Math.addExact(Math.addExact(Math.multiplyExact(h, 3600L), min * 60L), sec), 1000L);
                    if (!found.contains(value)) found.add(value);
                } catch (NumberFormatException | ArithmeticException ignored) { }
            }
        }
        return found;
    }

    public Long observe(String friend, Long countdownMs, long capturedAt) {
        if (friend == null || friend.trim().isEmpty() || countdownMs == null || countdownMs <= 0) { reset(); return null; }
        friend = friend.trim();
        long maturity = capturedAt + countdownMs;
        if (!friend.equals(candidateFriend) || Math.abs(maturity - candidateMaturity) > 5000) {
            candidateFriend = friend; candidateMaturity = maturity; count = 1; return null;
        }
        candidateMaturity = maturity;
        return ++count >= 2 ? maturity : null;
    }

    public void reset() { candidateFriend = null; candidateMaturity = 0; count = 0; }

    public static boolean isDuplicate(List<Reminder> reminders, String friend, long matureAt) {
        for (Reminder r : reminders) if (friend.equals(r.friend) && Math.abs(r.matureAt - matureAt) <= 5000) return true;
        return false;
    }
}
