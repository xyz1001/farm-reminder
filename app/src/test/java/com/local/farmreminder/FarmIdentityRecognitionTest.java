package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class FarmIdentityRecognitionTest {
    private static OcrLine line(String text, int x, int y) {
        return new OcrLine(text, x - 1, y - 1, x + 1, y + 1);
    }
    private static FarmIdentityRecognition.Outcome check(OcrLine... lines) {
        return FarmIdentityRecognition.classify(Arrays.asList(lines), 1000, 1000);
    }
    @Test public void loadingVariantsAndAuxiliaryFeatures() {
        assertEquals(FarmIdentityRecognition.Outcome.LOADING, check(line("ＱＱ 经典农场", 500, 520)));
        assertEquals(FarmIdentityRecognition.Outcome.LOADING, check(line("经典农场", 500, 530), line("帮助中心", 850, 220), line("8.54%", 500, 670)));
        assertEquals(FarmIdentityRecognition.Outcome.LOADING, check(line("经典农场", 500, 530), line("8.54%", 500, 670)));
        assertEquals(FarmIdentityRecognition.Outcome.LOADING, check(line("经典农场", 500, 530), line("帮助中心", 850, 220)));
    }
    @Test public void homeRequiresAllBottomItemsAndNoFriendEvidence() {
        OcrLine warehouse = line("仓库", 100, 930), shop = line("商店", 270, 930), pet = line("宠物", 430, 930);
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(warehouse, shop, pet));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(warehouse));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(warehouse, shop, pet, line("回家", 850, 400)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(warehouse, shop, line("宠物", 430, 899)));
    }
    @Test public void rejectUnlocatedGeneralWordsAndWrongRegions() {
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, FarmIdentityRecognition.classify(Collections.emptyList(), 1000, 1000));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(new OcrLine("QQ经典农场", -1, -1, -1, -1)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("QQ经典农场", 500, 300)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("经典农场", 500, 530)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("QQ农场", 500, 530), line("8%", 500, 670)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("农场", 500, 530), line("8%", 500, 670)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("8%", 500, 670)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("聊天：仓库 商店 宠物", 350, 930)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("经典农场", 500, 530), line("101%", 500, 670)));
    }
    @Test public void normalizedCoordinateBoundaries() {
        assertEquals(FarmIdentityRecognition.Outcome.LOADING, check(line("QQ经典农场", 200, 450)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("QQ经典农场", 199, 450)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(line("仓库", 249, 900), line("商店", 400, 900), line("宠物", 550, 900)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("仓库", 250, 900), line("商店", 400, 900), line("宠物", 550, 900)));
    }
}
