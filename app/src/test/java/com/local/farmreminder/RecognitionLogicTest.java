package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class RecognitionLogicTest {
    @Test public void maturityAndStageTimes() {
        assertEquals(Long.valueOf(19923000), RecognitionLogic.parseCountdown(Collections.singletonList("05:32:03后成熟")));
        assertEquals(Long.valueOf(19865000), RecognitionLogic.parseCountdown(Arrays.asList("小叶子 01:40:41", "05:31:05 后 成熟")));
        assertNull(RecognitionLogic.parseCountdown(Collections.singletonList("小叶子 01:40:46")));
        assertEquals(Long.valueOf(19923000), RecognitionLogic.parseCountdown(Collections.singletonList("05 ： 32：03 后成熟")));
    }
    @Test public void observedMaturityCharacterConfusion() {
        for (String time : Arrays.asList("07:51:43", "06:42:15", "07:01:45", "00:17:13")) {
            assertEquals(RecognitionLogic.parseCountdown(Collections.singletonList(time + "后成熟")),
                    RecognitionLogic.parseCountdown(Collections.singletonList(time + " 后戌熟")));
            assertNotNull(RecognitionLogic.parseCountdown(Collections.singletonList(time + " 后戌熟")));
        }
        assertNull(RecognitionLogic.parseCountdown(Collections.singletonList("发芽 01:02:03")));
        assertNull(RecognitionLogic.parseCountdown(Collections.singletonList("01:02:03后戊熟")));
    }
    @Test public void neverJoinRegions() {
        assertNull(RecognitionLogic.parseCountdown(Arrays.asList("05:32:03", "后成熟")));
        assertNull(RecognitionLogic.parseCountdown(Arrays.asList("05:32", ":03后成熟")));
        assertEquals(Long.valueOf(19923000), RecognitionLogic.parseCountdown(Arrays.asList("05:32:03后成熟", "05:31:05后成熟")));
        assertEquals(Long.valueOf(3723000), RecognitionLogic.parseCountdown(Collections.singletonList("1:2:03后成熟")));
        assertEquals(Long.valueOf(0), RecognitionLogic.parseCountdown(Collections.singletonList("0:0:0后成熟")));
    }
    @Test public void confirmationAndSwitch() {
        RecognitionLogic logic = new RecognitionLogic();
        assertNull(logic.observe(null, 10000L, 1000));
        assertNull(logic.observe("小明", 10000L, 1000));
        assertNull(logic.observe("小红", 9000L, 2000));
        assertEquals(Long.valueOf(11000), logic.observe("小红", 8000L, 3000));
        assertNull(logic.observe(null, 7000L, 4000));
        assertNull(logic.observe("小红", 6000L, 5000));
        assertNull(logic.observe("小红", 50000L, 6000));
    }
    @Test public void duplicateTolerance() {
        Reminder r = new Reminder(); r.friend = "小红"; r.matureAt = 100000;
        assertTrue(RecognitionLogic.isDuplicate(Collections.singletonList(r), "小红", 105000));
        assertFalse(RecognitionLogic.isDuplicate(Collections.singletonList(r), "小红", 105001));
        assertFalse(RecognitionLogic.isDuplicate(Collections.singletonList(r), "小红", 150000));
        assertFalse(RecognitionLogic.isDuplicate(Collections.singletonList(r), "小明", 100000));
    }
}
