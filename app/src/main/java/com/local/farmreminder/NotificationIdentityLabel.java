package com.local.farmreminder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Optional notification-only text; never persisted and never derived from Reminder.friend. */
public final class NotificationIdentityLabel {
    public static final NotificationIdentityLabel EMPTY = new NotificationIdentityLabel("", null);
    private static final Set<String> UI_TEXT = new HashSet<>(Arrays.asList(
            "好友", "回家", "分享", "商城", "仓库", "商店", "宠物", "图鉴", "装扮", "一键务农",
            "经验", "收入", "金币", "加载中", "登录", "微信登录", "授权登录", "同意", "取消",
            "成熟", "农场", "QQ经典农场", "昵称未识别", "头像未定位"));
    private final String name;
    private final Integer level;

    private NotificationIdentityLabel(String name, Integer level) { this.name = name; this.level = level; }

    public static NotificationIdentityLabel of(String name, Integer level) {
        String safe = safeName(name);
        if (level != null && level > 0 && level <= 9999) {
            return new NotificationIdentityLabel(safe, level);
        }
        return safe.isEmpty() ? EMPTY : new NotificationIdentityLabel(safe, null);
    }

    public String text() { return level == null ? name : (name.isEmpty() ? "好友" : name) + "(" + level + "级)"; }
    boolean hasLevel() { return level != null; }
    NotificationIdentityLabel withLevel(int value) { return hasLevel() ? this : of(name, value); }

    static String safeName(String name) {
        if (name == null || name.length() > 512) return "";
        for (int i = 0; i < name.length();) {
            char ch = name.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(i + 1))) return "";
            } else if (Character.isLowSurrogate(ch)) return "";
            int cp = name.codePointAt(i);
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp == 0x061C
                    || cp == 0x200E || cp == 0x200F || cp >= 0x202A && cp <= 0x202E
                    || cp >= 0x2066 && cp <= 0x206F) return "";
            i += Character.charCount(cp);
        }
        int first = 0, last = name.length();
        while (first < last && space(name.codePointAt(first))) first += Character.charCount(name.codePointAt(first));
        while (last > first && space(name.codePointBefore(last))) last -= Character.charCount(name.codePointBefore(last));
        String text = name.substring(first, last);
        int count = text.codePointCount(0, text.length());
        if (count < 1 || count > 64 || UI_TEXT.contains(text)) return "";
        boolean hasVisibleBase = false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i), type = Character.getType(cp);
            if (!space(cp) && type != Character.FORMAT && type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK && type != Character.ENCLOSING_MARK) hasVisibleBase = true;
            i += Character.charCount(cp);
        }
        if (!hasVisibleBase) return "";
        String compact = text.replaceAll("\\s+", "");
        if (compact.matches(".*[0-9][:：][0-9].*")
                || compact.matches(".*[0-9][%％].*")
                || compact.matches(".*[0-9]+(?:\\.[0-9]+)?万?[/／][0-9]+(?:\\.[0-9]+)?万?.*")
                || compact.matches("[¥￥$€][0-9]+(?:\\.[0-9]+)?(?:万|亿|元)?")
                || compact.matches("[0-9]+(?:\\.[0-9]+)?(?:元|金币|万|亿)")
                || compact.matches("[0-9]+级") || compact.matches("后[成戌咸]熟")) return "";
        return text;
    }

    private static boolean space(int cp) { return Character.isWhitespace(cp) || Character.isSpaceChar(cp); }
}
