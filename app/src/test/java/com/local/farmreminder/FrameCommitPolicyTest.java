package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class FrameCommitPolicyTest {
    private FrameCommitPolicy.Checkpoint frame(FrameCommitPolicy.Checkpoint previous, String id, String session,
            int window, long sequence, long uptime, long wall, String friend, Long countdown) {
        return FrameCommitPolicy.reduce(previous, id, session, window, sequence, uptime, wall, friend, countdown);
    }

    @Test public void checkpointReopensAndUsesCaptureTimeNotProcessingTime() throws Exception {
        FrameCommitPolicy.Checkpoint first = frame(null, "1", "s", 1, 1, 100, 1000, "friend", 10000L);
        FrameCommitPolicy.Checkpoint reopened = FrameCommitPolicy.Checkpoint.decode(first.encode());
        FrameCommitPolicy.Checkpoint second = frame(reopened, "2", "s", 1, 2, 200, 1100, "friend", 9900L);
        assertEquals(2, second.count);
        assertEquals(11000, second.maturity); // even if OCR completes after this time
    }

    @Test public void discontinuitiesDoNotAffectIndependentObservations() {
        FrameCommitPolicy.Checkpoint first = frame(null, "1", "s", 1, 1, 100, 1000, "friend", 10000L);
        assertEquals(2, frame(first, "2", "other", 1, 2, 200, 1100, "friend", 9900L).count);
        assertEquals(2, frame(first, "2", "s", 2, 2, 200, 1100, "friend", 9900L).count);
        assertEquals(2, frame(first, "3", "s", 1, 3, 200, 1100, "friend", 9900L).count);
        assertEquals(2, frame(first, "2", "s", 1, 2, 20100, 1100, "friend", 9900L).count);
        assertEquals(2, frame(first, "2", "s", 1, 2, 99, 1100, "friend", 9900L).count);
        assertEquals(2, frame(first, "2", "s", 1, 2, 200, 1100, "other", 9900L).count);
        assertEquals(2, frame(first, "2", "s", 1, 2, 200, 1100, "friend", 16000L).count);
        assertEquals(2, frame(first, "old", "s", 1, 1, 50, 900, "friend", 10100L).count);
        assertEquals(2, frame(first, "2", "s", 1, 2, 200, 1100, null, 9900L).count);
        assertEquals(2, frame(first, "2", "s", 1, 2, 200, 1100, " ", 9900L).count);
        assertEquals(0, frame(first, "2", "s", 1, 2, 200, 1100, "friend", null).count);
        assertEquals(2, frame(first, "2", "s", 1, 2, 200, 1100, "friend", 0L).count);
        FrameCommitPolicy.Checkpoint empty = frame(first, "2", "s", 1, 2, 200, 1100, null, null);
        assertEquals(2, frame(empty, "3", "s", 1, 3, 300, 1200, "friend", 9800L).count);
    }

    @Test public void failedCommitIsNotTrustedUntilFullRetrySucceeds() {
        FrameCommitPolicy.Durability durability = new FrameCommitPolicy.Durability();
        boolean[] cachedReceipt = {false};
        assertFalse(durability.commit(() -> { cachedReceipt[0] = true; return false; }));
        assertTrue(cachedReceipt[0]); assertTrue(durability.dirty);
        assertFalse(durability.commit(() -> false)); assertTrue(durability.dirty);
        assertTrue(durability.commit(() -> true)); assertFalse(durability.dirty);
    }
}
