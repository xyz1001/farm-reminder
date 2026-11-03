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
        assertEquals(FarmIdentityRecognition.Outcome.LOADING, check(line("回家", 10, 10), line("QQ经典农场", 500, 520)));
    }
    @Test public void anyThreeDistinctMenuWordsWorkAtAnyPosition() {
        String[] words = {"仓库", "商店", "宠物", "图鉴", "装扮", "好友"};
        int combinations = 0;
        for (int i = 0; i < words.length; i++) {
            for (int j = i + 1; j < words.length; j++) {
                for (int k = j + 1; k < words.length; k++) {
                    assertEquals(words[i] + words[j] + words[k], FarmIdentityRecognition.Outcome.HOME,
                            check(line(words[i], 10, 10), line(words[j], 500, 500), line(words[k], 990, 990)));
                    combinations++;
                }
            }
        }
        assertEquals(20, combinations);
    }
    @Test public void homeRequiresThreeDistinctWordsWithoutReturnHome() {
        OcrLine warehouse = line("仓库", 100, 930), shop = line("商店", 270, 930), pet = line("宠物", 430, 930);
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(warehouse, shop, pet));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(warehouse));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(warehouse, shop));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(warehouse, warehouse, line("仓库仓库", 500, 500)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(warehouse, shop, line("宠物", 430, 899)));
    }
    @Test public void returnHomeAloneOrWithMenuWordsAtAnyPosition() {
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(line("回家", 10, 10)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(line("回 家", 500, 500)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(line("点击回家", 990, 990)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(line("回家", 10, 10), line("仓库", 100, 100)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(line("回家", 10, 10), line("仓库商店", 100, 100)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(new OcrLine("回家", -1, 0, 10, 10)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(new OcrLine("回家", 999, 999, 1001, 1000)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(new OcrLine("回家", 10, 10, 10, 20)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(new OcrLine("回家", -1, 0, 10, 10), line("仓库商店", 100, 100)));
    }
    @Test public void observedMergedMenuIsHome() {
        OcrLine warehouse = new OcrLine("仓库", 55, 2628, 176, 2679);
        OcrLine merged = new OcrLine("商店 (宠物", 266, 2632, 537, 2674);
        assertEquals(FarmIdentityRecognition.Outcome.HOME,
                FarmIdentityRecognition.classify(Arrays.asList(warehouse, merged), 1240, 2772));
    }
    @Test public void mergedMenuAllowsSeparatorsAndPreservesInput() {
        OcrLine warehouse = line("仓库", 100, 930);
        for (String text : new String[]{"商店宠物", "商店 宠物", "商店 (宠物", "商店（宠物"}) {
            OcrLine merged = new OcrLine(text, 210, 920, 440, 940);
            java.util.List<OcrLine> input = new java.util.ArrayList<>(Arrays.asList(warehouse, merged));
            assertEquals(text, FarmIdentityRecognition.Outcome.HOME, FarmIdentityRecognition.classify(input, 1000, 1000));
            assertEquals(Arrays.asList(warehouse, merged), input);
            assertEquals(text, merged.text);
        }
    }
    @Test public void mergedMenuMatchesSubstringsAnywhereButNeedsValidBoxes() {
        OcrLine warehouse = line("仓库", 100, 930);
        for (String text : new String[]{"宠物商店", "商店欢迎宠物", "广告商店宠物", "聊天：商店宠物", "商店,宠物", "商店)宠物"}) {
            assertEquals(text, FarmIdentityRecognition.Outcome.HOME,
                    check(warehouse, new OcrLine(text, 210, 920, 440, 940)));
        }
        for (String text : new String[]{"商店", "宠物"}) {
            assertEquals(text, FarmIdentityRecognition.Outcome.NO_MATCH, check(warehouse, line(text, 500, 500)));
        }
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(line("仓库商店(宠物", 500, 500)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(warehouse, new OcrLine("商店宠物", 210, 100, 440, 120)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(warehouse, new OcrLine("商店宠物", 100, 920, 440, 940)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(warehouse, new OcrLine("商店宠物", 210, 920, 600, 940)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(warehouse, new OcrLine("商店宠物", -1, -1, -1, -1)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(warehouse, new OcrLine("商店宠物", 900, 900, 1001, 1000)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(warehouse, new OcrLine("商店宠物", 10, 10, 10, 20)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(warehouse, line("商", 100, 100), line("店宠", 200, 200), line("物", 300, 300)));
        OcrLine merged = new OcrLine("商店宠物", 210, 920, 440, 940);
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(merged));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(warehouse, merged, line("回家", 850, 400)));
    }
    @Test public void rejectUnlocatedGeneralWordsAndWrongRegions() {
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, FarmIdentityRecognition.classify(Collections.emptyList(), 1000, 1000));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(new OcrLine("QQ经典农场", -1, -1, -1, -1)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("QQ经典农场", 500, 300)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("经典农场", 500, 530)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("QQ农场", 500, 530), line("8%", 500, 670)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("农场", 500, 530), line("8%", 500, 670)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("8%", 500, 670)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(line("聊天：仓库 商店 宠物", 350, 930)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("经典农场", 500, 530), line("101%", 500, 670)));
    }
    @Test public void normalizedCoordinateBoundaries() {
        assertEquals(FarmIdentityRecognition.Outcome.LOADING, check(line("QQ经典农场", 200, 450)));
        assertEquals(FarmIdentityRecognition.Outcome.NO_MATCH, check(line("QQ经典农场", 199, 450)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(line("仓库", 249, 900), line("商店", 400, 900), line("宠物", 550, 900)));
        assertEquals(FarmIdentityRecognition.Outcome.HOME, check(line("仓库", 250, 900), line("商店", 400, 900), line("宠物", 550, 900)));
    }
}
