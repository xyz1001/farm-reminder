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
    @Test public void numericNamesAmbiguityAndWrongSceneRejected() {
        assertNull(OcrFrameParser.friend(Arrays.asList(line("148",155,133,230,160), line("回家",760,1780,830,1825)),895,2000));
        assertNull(OcrFrameParser.friend(Arrays.asList(line("88万",155,133,230,160), line("回家",760,1780,830,1825)),895,2000));
        assertNull(OcrFrameParser.friend(Arrays.asList(line("木林林木",155,133,275,162)),895,2000));
        List<OcrLine> two = scene("05:32:03后成熟"); two.add(line("小明",310,133,360,162));
        assertNull(OcrFrameParser.friend(two,895,2000));
    }
    @Test public void adjacentLinesOnly() {
        assertEquals(Long.valueOf(19923000), OcrFrameParser.countdown(Arrays.asList(line("05:32:03",300,700,450,730),line("后成熟",455,700,530,730))));
        assertEquals(Long.valueOf(19923000), OcrFrameParser.countdown(Arrays.asList(line("05:32:03",300,700,450,730),line("后成熟",300,733,390,763))));
        assertNull(OcrFrameParser.countdown(Arrays.asList(line("05:32:03",100,700,250,730),line("后成熟",600,1200,700,1230))));
        assertNull(OcrFrameParser.countdown(Arrays.asList(line("小叶子 01:40:46",100,700,300,730),line("后成熟",310,700,380,730))));
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
