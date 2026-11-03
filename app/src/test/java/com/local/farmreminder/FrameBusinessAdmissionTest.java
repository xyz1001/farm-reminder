package com.local.farmreminder;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class FrameBusinessAdmissionTest {
    @Test public void probeIsIdentityOnlyAndDoesNotMutateInput() {
        List<Long> raw = new ArrayList<>(Arrays.asList(1000L, 2000L));
        assertEquals("accessibility_display_usage_probe", FrameBusinessAdmission.USAGE_PROBE);
        assertTrue(FrameBusinessAdmission.countdowns(FrameBusinessAdmission.USAGE_PROBE, raw).isEmpty());
        assertEquals(Arrays.asList(1000L, 2000L), raw);
    }

    @Test public void normalAndLegacyFramesRetainCountdowns() {
        List<Long> raw = Arrays.asList(1000L, 2000L);
        assertSame(raw, FrameBusinessAdmission.countdowns("accessibility_display_usage", raw));
        assertSame(raw, FrameBusinessAdmission.countdowns("accessibility_display_shizuku", raw));
        assertSame(raw, FrameBusinessAdmission.countdowns(null, raw));
    }
}
