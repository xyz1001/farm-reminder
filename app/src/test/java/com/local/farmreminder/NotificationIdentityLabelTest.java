package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationIdentityLabelTest {
    @Test public void safeNameWinsThenPositiveBoundedLevelThenEmpty() {
        assertEquals("示例", NotificationIdentityLabel.of(" 示例 ", 148).text());
        assertEquals("148级的好友", NotificationIdentityLabel.of(null, 148).text());
        assertEquals("9999级的好友", NotificationIdentityLabel.of("", 9999).text());
        for (Integer bad : new Integer[]{null, -1, 0, 10000, Integer.MAX_VALUE})
            assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityLabel.of(null, bad));
    }

    @Test public void singleNumericAndEmojiNamesAreNotDiscarded() {
        for (String value : new String[]{"甲", "123456", "👩‍🌾", "☀️", "好友甲"})
            assertEquals(value, NotificationIdentityLabel.of(value, null).text());
        assertEquals("甲", NotificationIdentityLabel.of("\u3000甲\u00a0", null).text());
    }

    @Test public void namesAreBoundedByCodePointsNotUtf16Chars() {
        assertEquals("🌱".repeat(64), NotificationIdentityLabel.of("🌱".repeat(64), null).text());
        assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityLabel.of("🌱".repeat(65), null));
        assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityLabel.of(" ", null));
        assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityLabel.of("\u200D\uFE0F", null));
    }

    @Test public void controlsBidiAndUnpairedSurrogatesFailSoftToLevel() {
        for (String bad : new String[]{"甲\n乙", "\r甲", "甲\t乙", "甲\u0000", "甲\u0085", "甲\u2028乙",
                "甲\u2029乙", "甲\u202E乙", "甲\u2066乙", "甲\u200F乙", "甲\u061C乙", "\uD800", "\uDC00"}) {
            assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityLabel.of(bad, null));
            assertEquals("148级的好友", NotificationIdentityLabel.of(bad, 148).text());
        }
    }

    @Test public void knownUiTimersPercentAndCurrencyAreNotNames() {
        for (String bad : new String[]{"回家", "经验", "QQ经典农场", "微信登录", "昵称未识别", "00:10:25",
                "12：34：56后成熟", "80%", "80％", "140万/142万", "￥12万", "$15.50", "12金币", "148级"})
            assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityLabel.of(bad, null));
    }
}
