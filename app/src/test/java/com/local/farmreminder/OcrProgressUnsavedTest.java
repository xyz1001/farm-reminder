package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class OcrProgressUnsavedTest {
    private OcrProgress progress(int failed, boolean writing, boolean unsaved, String reason) {
        return new OcrProgress(0, 0, 0, 0, false, failed, 0, 0, writing, reason, unsaved);
    }

    @Test public void stoppedWriteFailureWithoutArtifactsRemainsRetryable() {
        OcrProgress p = progress(0, false, true, "STOPPED");
        assertFalse(p.writing);
        assertTrue(p.hasUnsavedCapture);
        assertTrue(p.retryAvailable);
    }

    @Test public void writingAndPersistedSnapshotsAreDistinct() {
        OcrProgress writing = progress(0, true, true, "WRITE_FAILED");
        assertTrue(writing.hasUnsavedCapture);
        assertTrue(writing.retryAvailable);
        OcrProgress persisted = progress(0, false, false, "STOPPED");
        assertFalse(persisted.hasUnsavedCapture);
        assertFalse(persisted.retryAvailable);
    }

    @Test public void failedFramesRemainRetryableWithoutUnsavedCapture() {
        OcrProgress p = progress(1, false, false, "STOPPED");
        assertFalse(p.hasUnsavedCapture);
        assertTrue(p.retryAvailable);
    }

    @Test public void existingConstructorsDefaultUnsavedToFalse() {
        OcrProgress durable = new OcrProgress(0, 0, 0, 0, false, 1, 0, 0, false, "STOPPED");
        assertFalse(durable.hasUnsavedCapture);
        assertTrue(durable.retryAvailable);
        OcrProgress legacy = new OcrProgress(0, 0, 0, 0, 0, false, 12, 100, 8000);
        assertFalse(legacy.hasUnsavedCapture);
        assertFalse(legacy.retryAvailable);
    }
}
