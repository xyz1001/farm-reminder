package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class OcrFrameTest {
    private OcrLine line(String text, int l, int t, int r, int b) { return new OcrLine(text, l, t, r, b); }
    private List<OcrLine> scene(String... text) {
        java.util.ArrayList<OcrLine> lines = new java.util.ArrayList<>();
        lines.add(line("木林林木", 155, 133, 275, 162));
        lines.add(line("148", 290, 133, 330, 162));
        lines.add(line("88万/142万", 155, 178, 290, 202));
        lines.add(line("回家", 760, 1780, 830, 1825));
        int y = 700;
        for (String s : text) { lines.add(line(s, 300, y, 530, y + 30)); y += 45; }
        return lines;
    }
    @Test public void screenshotsAndBareNickname() {
        List<OcrLine> a = scene("05:32:03后成熟");
        List<OcrLine> b = scene("小叶子 01:40:41", "05:31:05后成熟");
        List<OcrLine> c = scene("小叶子 01:40:46");
        assertEquals("木林林木", OcrFrameParser.friend(a, 895, 2000));
        assertEquals(Long.valueOf(19923000), OcrFrameParser.countdown(a));
        assertEquals(Long.valueOf(19865000), OcrFrameParser.countdown(b));
        assertNull(OcrFrameParser.countdown(c));
    }
    @Test public void unrestrictedNamesAndMultipleCandidates() {
        for (String name : Arrays.asList("木", "148", "😀", "!", "成熟回家", "88万"))
            assertEquals(name, OcrFrameParser.friend(Arrays.asList(line(name,800,1600,850,1630)),895,2000));
        assertEquals("木林林木", OcrFrameParser.friend(Arrays.asList(line("木林林木",155,133,275,162)),895,2000));
        List<OcrLine> two = scene("05:32:03后成熟"); two.add(line("小明",310,133,360,162));
        assertEquals("木林林木", OcrFrameParser.friend(two,895,2000));
        assertEquals("!", OcrFrameParser.friend(Arrays.asList(line("其他",100,100,200,130), line("好友:!",700,1700,800,1730)),895,2000));
        assertNull(OcrFrameParser.friend(Arrays.asList(line("05:32:03",100,700,250,730),line("后成熟",600,1200,700,1230)),895,2000));
    }
    @Test public void splitLinesAndMultipleMaturities() {
        assertEquals(Long.valueOf(19923000), OcrFrameParser.countdown(Arrays.asList(line("05:32:03",300,700,450,730),line("后成熟",455,700,530,730))));
        assertEquals(Long.valueOf(19923000), OcrFrameParser.countdown(Arrays.asList(line("05:32:03",300,700,450,730),line("后成熟",300,733,390,763))));
        assertEquals(Long.valueOf(19923000), OcrFrameParser.countdown(Arrays.asList(line("05:32:03",100,700,250,730),line("后成熟",600,1200,700,1230))));
        assertNull(OcrFrameParser.countdown(Arrays.asList(line("小叶子 01:40:46",100,700,300,730),line("后成熟",310,700,380,730))));
        assertEquals(Arrays.asList(0L, 3723000L), OcrFrameParser.countdowns(Arrays.asList(line("0:0:0后成熟",1,10,100,20),line("1:2:03后成熟",1,40,100,50),line("1:2:03后成熟",1,60,100,70))));
        assertEquals(Arrays.asList(19923000L), OcrFrameParser.countdowns(Arrays.asList(line("小叶子:01:40:41",1,10,100,20),line("05:32",1,30,100,40),line(":03",1,80,100,90),line("后",1,130,100,140),line("成熟",1,180,100,190))));
    }
    @Test public void numericNamesAndCountersDoNotPolluteTimers() {
        OcrLine name = line("123", 10, 10, 60, 20);
        OcrLine level = line("148", 70, 10, 120, 20);
        assertEquals("123", OcrFrameParser.friend(Arrays.asList(name, level), 895, 2000));
        assertEquals(Arrays.asList(32345000L), OcrFrameParser.countdowns(Arrays.asList(name, level,
                line("08:59:05 后成熟", 10, 100, 200, 120))));
        assertEquals(Arrays.asList(32345000L), OcrFrameParser.countdowns(Arrays.asList(name, level,
                line("08:59:05", 10, 100, 150, 120), line("后成熟", 10, 200, 100, 220))));
        assertEquals(Arrays.asList(32345000L), OcrFrameParser.countdowns(Arrays.asList(name, level,
                line("08", 10, 100, 50, 110), line(":", 10, 120, 20, 130),
                line("59", 10, 140, 50, 150), line(":", 10, 160, 20, 170),
                line("05", 10, 180, 50, 190), line("后成熟", 10, 200, 100, 220))));
        assertEquals(Arrays.asList(1000L, 32345000L), OcrFrameParser.countdowns(Arrays.asList(
                line("00:00:01后成熟", 10, 10, 200, 20), line("148", 10, 40, 60, 50),
                line("08:59:05后成熟", 10, 100, 200, 120))));
    }
    @Test public void inlineMaturityIsNotBlockedByStageText() {
        assertEquals(Arrays.asList(19865000L), OcrFrameParser.countdowns(Arrays.asList(
                line("小叶子：01:40:41 05:31:05后成熟", 10, 10, 400, 30))));
        assertTrue(OcrFrameParser.countdowns(Arrays.asList(
                line("小叶子:01:40:41", 10, 10, 200, 30))).isEmpty());
        assertEquals(Arrays.asList(19865000L, 21600000L), OcrFrameParser.countdowns(Arrays.asList(
                line("小叶子:01:40:41 05:31:05后成熟 06:00:00后成熟", 10, 10, 600, 30))));
        assertTrue(OcrFrameParser.countdowns(Arrays.asList(
                line("小叶子:01:40:41", 10, 10, 200, 30), line("后成熟", 10, 50, 100, 70))).isEmpty());
    }
    @Test public void noStaleNameAfterSceneSwitch() {
        RecognitionLogic logic = new RecognitionLogic();
        List<OcrLine> good = scene("05:32:03后成熟");
        assertNull(logic.observe(OcrFrameParser.friend(good,895,2000), OcrFrameParser.countdown(good), 1000));
        assertNull(logic.observe(null, OcrFrameParser.countdown(good), 2000));
        assertNull(logic.observe(OcrFrameParser.friend(good,895,2000), OcrFrameParser.countdown(good), 3000));
        assertNotNull(logic.observe(OcrFrameParser.friend(good,895,2000), OcrFrameParser.countdown(good), 4000));
    }
}
