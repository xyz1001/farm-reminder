package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class NotificationBadgePolicyTest {
    private NotificationIdentityParser.TextRow row(String text, int left) {
        OcrLine line = new OcrLine(text, left, 10, 100, 80);
        return new NotificationIdentityParser.TextRow(line, Collections.singletonList(line));
    }
    @Test public void exactSyntaxAndMargins() {
        assertEquals(Integer.valueOf(148), NotificationBadgePolicy.level(Collections.singletonList(row("148", 3)), 246, 210));
        assertEquals(Integer.valueOf(168), NotificationBadgePolicy.level(Collections.singletonList(row("168级", 3)), 246, 210));
        for (String bad : Arrays.asList("048", "0", "148/200", "148万", "148:2", "148金币", " 148", "148?"))
            assertNull(bad, NotificationBadgePolicy.level(Collections.singletonList(row(bad, 3)), 246, 210));
        assertNull(NotificationBadgePolicy.level(Collections.singletonList(row("148", 0)), 246, 210));
        assertNull(NotificationBadgePolicy.level(Arrays.asList(row("148", 3), row("168", 3)), 246, 210));
    }
    @Test public void childrenMustAgreeWithParent() {
        OcrLine parent = new OcrLine("148", 3, 3, 100, 80);
        OcrLine child = new OcrLine("168", 3, 3, 100, 80);
        assertNull(NotificationBadgePolicy.level(Collections.singletonList(new NotificationIdentityParser.TextRow(parent, Collections.singletonList(child))), 246, 210));
    }
    @Test public void fixedSlotIsOutwardRoundedAndBounded() {
        AvatarLocator.Bounds avatar = new AvatarLocator.Bounds(44, 164, 200, 316);
        assertArrayEquals(new int[]{150, 232, 232, 302}, NotificationBadgePolicy.crop(avatar, 1000, 1000));
        assertNull(NotificationBadgePolicy.crop(avatar, 231, 1000));
        assertNull(NotificationBadgePolicy.crop(null, 1000, 1000));
        assertNull(NotificationBadgePolicy.crop(new AvatarLocator.Bounds(0, 0, 1000, 1000), 2000, 2000));
    }
    @Test public void plateScalingChoosesLargestBoundedFactor() {
        assertEquals(5, NotificationBadgePolicy.plateScale(92,79));
        assertEquals(5, NotificationBadgePolicy.plateScale(95,90));
        assertEquals(4, NotificationBadgePolicy.plateScale(125,100));
        assertEquals(3, NotificationBadgePolicy.plateScale(150,150));
        assertEquals(2, NotificationBadgePolicy.plateScale(200,200));
        assertEquals(1, NotificationBadgePolicy.plateScale(300,300));
        assertEquals(0, NotificationBadgePolicy.plateScale(501,500));
        assertEquals(0, NotificationBadgePolicy.plateScale(Integer.MAX_VALUE,Integer.MAX_VALUE));
        assertEquals(0, NotificationBadgePolicy.plateScale(0,90));
    }
    @Test public void labelKeepsTypedName() {
        assertEquals("Alice(148级)", NotificationIdentityLabel.of("Alice", null).withLevel(148).text());
        assertEquals("好友(148级)", NotificationIdentityLabel.EMPTY.withLevel(148).text());
        assertEquals("Alice(168级)", NotificationIdentityLabel.of("Alice", 168).withLevel(148).text());
    }
    @Test public void lateBadgeDoesNotDiscardNameOrAddLevel() {
        NotificationIdentityLabel original = NotificationIdentityLabel.of("Alice", null);
        assertEquals("Alice(148级)", NotificationBadgePolicy.finish(original, 148, 2500, () -> 2499).text());
        assertSame(original, NotificationBadgePolicy.finish(original, 148, 2500, () -> 2500));
        assertEquals("Alice", NotificationBadgePolicy.finish(original, 148, 2500, () -> 2501).text());
    }
}
