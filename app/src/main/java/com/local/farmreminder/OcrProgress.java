package com.local.farmreminder;

/** Immutable snapshot; queued and pendingBytes exclude the in-flight OCR frame. */
public final class OcrProgress {
    public final long processed, captured, dropped, pendingBytes, maxPendingBytes;
    public final int queued, maxPendingFrames;
    public final boolean recognizing;
    public final long ttlMs;
    public final boolean durableQueue, writing, capturePaused;
    public final boolean hasUnsavedCapture, retryAvailable;
    public final int failedCount, cleanupPendingCount, archivedCount;
    public final long retainedBytes;
    public final String pauseReason;

    OcrProgress(long processed, long captured, long dropped, int queued, long pendingBytes,
                boolean recognizing, int maxPendingFrames, long maxPendingBytes, long ttlMs) {
        this.processed = processed; this.captured = captured; this.dropped = dropped;
        this.queued = queued; this.pendingBytes = pendingBytes; this.recognizing = recognizing;
        this.maxPendingFrames = maxPendingFrames; this.maxPendingBytes = maxPendingBytes; this.ttlMs = ttlMs;
        this.durableQueue = false; this.writing = false; this.capturePaused = false;
        this.failedCount = 0; this.cleanupPendingCount = 0; this.archivedCount = 0; this.retainedBytes = 0; this.pauseReason = "NONE";
        this.hasUnsavedCapture = false; this.retryAvailable = false;
    }

    OcrProgress(long processed, long captured, int queued, long pendingBytes, boolean recognizing,
                int failedCount, int cleanupPendingCount, long retainedBytes, boolean writing, String pauseReason) {
        this(processed, captured, queued, pendingBytes, recognizing, failedCount, cleanupPendingCount,
                retainedBytes, writing, pauseReason, false);
    }

    OcrProgress(long processed, long captured, int queued, long pendingBytes, boolean recognizing,
                int failedCount, int cleanupPendingCount, long retainedBytes, boolean writing, String pauseReason,
                boolean hasUnsavedCapture) {
        this(processed, captured, queued, pendingBytes, recognizing, failedCount, cleanupPendingCount,
                retainedBytes, writing, pauseReason, hasUnsavedCapture, 0);
    }

    OcrProgress(long processed, long captured, int queued, long pendingBytes, boolean recognizing,
                int failedCount, int cleanupPendingCount, long retainedBytes, boolean writing, String pauseReason,
                boolean hasUnsavedCapture, int archivedCount) {
        this.processed = processed; this.captured = captured; this.dropped = 0;
        this.queued = queued; this.pendingBytes = pendingBytes; this.recognizing = recognizing;
        this.maxPendingFrames = -1; this.maxPendingBytes = -1; this.ttlMs = -1;
        this.durableQueue = true; this.failedCount = failedCount; this.cleanupPendingCount = cleanupPendingCount;
        this.archivedCount = archivedCount;
        this.retainedBytes = retainedBytes; this.writing = writing; this.pauseReason = pauseReason;
        this.capturePaused = !"NONE".equals(pauseReason);
        this.hasUnsavedCapture = hasUnsavedCapture;
        this.retryAvailable = hasUnsavedCapture || failedCount > 0;
    }
}
