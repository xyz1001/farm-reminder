package com.local.farmreminder;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;
import static com.local.farmreminder.NotificationIdentityParser.TextRow;

public class NotificationIdentityParserTest {
    private static final AvatarLocator.Bounds FRAME = new AvatarLocator.Bounds(44, 164, 196, 316);
    private static OcrLine name(String text) { return new OcrLine(text, 200, 189, 372, 234); }
    private static OcrLine level(String text) { return new OcrLine(text, 159, 241, 217, 278); }
    private static TextRow row(OcrLine parent, OcrLine... children) { return new TextRow(parent, Arrays.asList(children)); }
    private static TextRow grade(String text) { return row(level(text), level(text)); }
    private static NotificationIdentityLabel parse(TextRow... rows) {
        return NotificationIdentityParser.parseRows(Arrays.asList(rows), FRAME, 1240, 2772, true, true);
    }
    private static NotificationIdentityLabel flat(List<OcrLine> lines, List<OcrLine> elements) {
        return NotificationIdentityParser.parse(lines, elements, FRAME, 1240, 2772, true, true);
    }

    @Test public void redactedRealGeometryCombinesNameAndComplete148Badge() {
        assertEquals("示例姓名(148级)", parse(row(name("示例姓名")), grade("148")).text());
    }

    @Test public void failedNameFallsBackToIndependent148WithoutCorrectingOldOcr() {
        assertEquals("好友(148级)", parse(row(name("经验")), grade("148")).text());
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(name("经验")),
                row(new OcrLine("048140万/142万", 152, 234, 410, 280))));
    }

    @Test public void unknownInputsHaveNoPlaceholder() {
        assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityParser.parseRows(null, FRAME, 1240, 2772, true, true));
        assertSame(NotificationIdentityLabel.EMPTY, parse(grade("048")));
    }

    @Test public void ownFarmOrMissingFrameCannotUseEvenPerfectHeaderText() {
        List<TextRow> rows = Arrays.asList(row(name("示例")), grade("148"));
        assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityParser.parseRows(rows, FRAME, 1240, 2772, false, true));
        assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityParser.parseRows(rows, null, 1240, 2772, true, true));
    }

    @Test public void invalidDimensionsBoundsAndOutOfImageOcrAreRejected() {
        for (AvatarLocator.Bounds bad : new AvatarLocator.Bounds[]{new AvatarLocator.Bounds(-1, 164, 196, 316),
                new AvatarLocator.Bounds(44, 164, 44, 316), new AvatarLocator.Bounds(44, 164, 196, 164),
                new AvatarLocator.Bounds(44, 164, 1241, 316), new AvatarLocator.Bounds(44, 164, 196, 2773)})
            assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityParser.parseRows(Collections.singletonList(row(name("示例"))),
                    bad, 1240, 2772, true, true));
        assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityParser.parseRows(Collections.singletonList(row(name("示例"))),
                FRAME, 0, 2772, true, true));
        assertSame(NotificationIdentityLabel.EMPTY, parse(null, row(new OcrLine("示例", -1, 189, 372, 234))));
    }

    @Test public void multipleDistinctNamesAreUnknownButDuplicateOcrLinesAreNotAmbiguous() {
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(name("示例甲")), row(name("示例乙"))));
        assertEquals("示例甲", parse(row(name("示例甲")), row(name("示例甲"))).text());
        assertEquals("好友(148级)", parse(row(name("示例甲")), row(name("示例乙")), grade("148")).text());
    }

    @Test public void topTitlesBottomNamesProgressAndTimersCannotBecomeHeaderName() {
        assertSame(NotificationIdentityLabel.EMPTY, parse(
                row(new OcrLine("示例甲的农场", 10, 10, 300, 50)), row(new OcrLine("好友：示例乙", 200, 2500, 600, 2540)),
                row(new OcrLine("140万/142万", 235, 240, 420, 279)), row(name("00:10:25"))));
    }

    @Test public void wholeLineContainmentDoesNotAcceptPartialHeaderOverlap() {
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(new OcrLine("示例", 180, 189, 372, 234))));
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(new OcrLine("示例", 200, 189, 372, 245))));
    }

    @Test public void singleCharacterNumericAndEmojiHeaderNamesAreAccepted() {
        for (String value : new String[]{"甲", "148", "👩‍🌾", "☀️"})
            assertEquals(value, parse(row(name(value))).text());
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(name("甲\u202E乙"))));
    }

    @Test public void wholeIndependentLineCanSupplyLevelButNotWhenItsParentIsAmbiguous() {
        assertEquals("好友(148级)", parse(row(level("148级"))).text());
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(level("148")),
                row(new OcrLine("048140万/142万", 152, 234, 410, 280))));
    }

    @Test public void levelDoesNotSplitCorrectPrefixesOrAcceptNonAsciiAndUiUnits() {
        for (String bad : new String[]{"048", "0", "10000", "148/200", "148.0", "148%", "148万", "148x", "148:00", "１４８", "1 48"})
            assertSame(NotificationIdentityLabel.EMPTY, parse(grade(bad)));
        assertEquals("好友(9999级)", parse(grade("9999")).text());
    }

    @Test public void conflictingSeparateLevelsAreUnknown() {
        assertSame(NotificationIdentityLabel.EMPTY, parse(
                row(new OcrLine("148", 159, 233, 190, 258), new OcrLine("148", 159, 233, 190, 258)),
                row(new OcrLine("149", 159, 265, 190, 290), new OcrLine("149", 159, 265, 190, 290))));
    }

    @Test public void clipped14IsUnknownButComplete14IsNotBlacklistedAndNameSurvivesClipping() {
        assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityParser.parseRows(Collections.singletonList(grade("14")),
                FRAME, 1240, 2772, true, false));
        assertEquals("好友(14级)", parse(grade("14")).text());
        assertEquals("示例", NotificationIdentityParser.parseRows(Arrays.asList(row(name("示例")), grade("14")),
                FRAME, 1240, 2772, true, false).text());
    }

    @Test public void levelsOutsideBadgeOrFromIncompleteImageAreUnknown() {
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(new OcrLine("148", 159, 241, 230, 278))));
        assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityParser.parseRows(Collections.singletonList(grade("148")),
                FRAME, 220, 2772, true, true));
    }

    @Test public void uniformScaleAndViewportOffsetPreserveParserResult() {
        AvatarLocator.Bounds half = new AvatarLocator.Bounds(22, 82, 98, 158);
        OcrLine halfGrade = new OcrLine("148", 80, 121, 108, 139);
        assertEquals("示例(148级)", NotificationIdentityParser.parseRows(Arrays.asList(
                row(new OcrLine("示例", 100, 95, 186, 117)), row(halfGrade, halfGrade)),
                half, 620, 1386, true, true).text());
        assertEquals("好友(148级)", NotificationIdentityParser.parseRows(Collections.singletonList(row(halfGrade, halfGrade)),
                half, 620, 1386, true, true).text());
        assertEquals("好友(148级)", NotificationIdentityParser.parseRows(Collections.singletonList(
                row(new OcrLine("148 30/60", 80, 121, 205, 139), halfGrade,
                        new OcrLine("30/60", 120, 122, 205, 139))), half, 620, 1386, true, true).text());
        assertSame(NotificationIdentityLabel.EMPTY, NotificationIdentityParser.parseRows(Collections.singletonList(
                row(new OcrLine("14%", 80, 121, 110, 139), new OcrLine("14", 80, 121, 93, 139),
                        new OcrLine("%", 94, 121, 110, 139))), half, 620, 1386, true, true));
        AvatarLocator.Bounds shifted = new AvatarLocator.Bounds(64, 264, 216, 416);
        OcrLine shiftedGrade = new OcrLine("148", 179, 341, 237, 378);
        assertEquals("好友(148级)", NotificationIdentityParser.parseRows(Collections.singletonList(row(shiftedGrade, shiftedGrade)),
                shifted, 1280, 2972, true, true).text());
    }

    @Test public void splitPercentProgressTimeDecimalAndUnitsInsideBadgeNeverDonateNumericPrefix() {
        for (String suffix : new String[]{"%", "％", "/100", ":30", ".5", "万", "元", "金币", "秒", "分钟", "经验"}) {
            OcrLine number = new OcrLine("14", 159, 241, 185, 278);
            OcrLine unit = new OcrLine(suffix, 187, 241, 220, 278);
            assertSame(suffix, NotificationIdentityLabel.EMPTY, parse(row(new OcrLine("14" + suffix, 159, 241, 220, 278), number, unit)));
        }
    }

    @Test public void originalFlatPercentCounterexampleIsAlsoRejectedThroughUniqueRealParent() {
        OcrLine parent = new OcrLine("14%", 159, 241, 220, 278);
        assertSame(NotificationIdentityLabel.EMPTY, flat(Collections.singletonList(parent), Arrays.asList(
                new OcrLine("14", 159, 241, 185, 278), new OcrLine("%", 187, 241, 220, 278))));
    }

    @Test public void separateLevelAndOutsideExperienceWithinSameWideParentAreAllowed() {
        OcrLine parent = new OcrLine("148 30/60", 159, 241, 410, 278);
        OcrLine experience = new OcrLine("30/60", 240, 244, 410, 278);
        assertEquals("好友(148级)", parse(row(parent, level("148"), experience)).text());
        assertEquals("好友(148级)", flat(Collections.singletonList(parent), Arrays.asList(level("148"), experience)).text());
        // Outside percentages are not a blanket veto when the true level is spatially separate.
        assertEquals("好友(148级)", parse(row(new OcrLine("148 30%", 159, 241, 410, 278), level("148"),
                new OcrLine("30%", 240, 244, 410, 278))).text());
    }

    @Test public void adjacentOutsideSymbolsAndSuffixChainsStillBelongToTheNumericSyntax() {
        OcrLine number = new OcrLine("14", 190, 241, 216, 278);
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(new OcrLine("14%", 190, 241, 240, 278), number,
                new OcrLine("%", 228, 241, 240, 278))));
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(new OcrLine("14级%", 159, 241, 235, 278),
                new OcrLine("14", 159, 241, 194, 278), new OcrLine("级", 196, 241, 216, 278),
                new OcrLine("%", 219, 241, 235, 278))));
    }

    @Test public void explicitSeparateLevelSuffixIsAllowedButNotArbitraryUnits() {
        assertEquals("好友(148级)", parse(row(new OcrLine("148级", 159, 241, 222, 278),
                new OcrLine("148", 159, 241, 201, 278), new OcrLine("级", 203, 241, 222, 278))).text());
    }

    @Test public void badGradeDoesNotDiscardGoodParentLineName() {
        assertEquals("示例", parse(row(name("示例")), row(new OcrLine("14%", 159, 241, 220, 278),
                new OcrLine("14", 159, 241, 185, 278), new OcrLine("%", 187, 241, 220, 278))).text());
    }

    @Test public void absentOrMultipleFlatParentsCannotBeReplacedWithInventedElementRows() {
        assertSame(NotificationIdentityLabel.EMPTY, flat(null, Collections.singletonList(level("148"))));
        assertSame(NotificationIdentityLabel.EMPTY, flat(Arrays.asList(level("148"), new OcrLine("148", 150, 235, 225, 285)),
                Collections.singletonList(level("148"))));
        assertEquals("示例", flat(Collections.singletonList(name("示例")), Collections.singletonList(level("148"))).text());
    }

    @Test public void childCrossOwnershipOverlapOrContradictoryMetadataAreUnknown() {
        TextRow owner = grade("148");
        assertSame(NotificationIdentityLabel.EMPTY, parse(owner, row(new OcrLine("148", 150, 235, 225, 285), level("148"))));
        assertSame(NotificationIdentityLabel.EMPTY, parse(owner, row(new OcrLine("other", 170, 240, 240, 280))));
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(level("148"), level("148"), level("148"))));
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(level("14%"), level("14"))));
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(level("148"), new OcrLine("148", 158, 241, 217, 278))));
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(level("148"), (OcrLine) null)));
        assertSame(NotificationIdentityLabel.EMPTY, parse(row(level("148\u202E"), level("148\u202E"))));
    }

    @Test public void textRowDefensivelyCopiesAndExposesNoMutableChildrenList() {
        List<OcrLine> source = new ArrayList<>(Collections.singletonList(level("148")));
        TextRow row = new TextRow(level("148"), source);
        source.clear();
        assertEquals("好友(148级)", parse(row).text());
        try { row.elements.clear(); fail("must be immutable"); } catch (UnsupportedOperationException expected) { }
        assertTrue(new TextRow(level("148"), null).elements.isEmpty());
    }
}
