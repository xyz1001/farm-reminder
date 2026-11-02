package com.local.farmreminder;

/** Immutable snapshot; queued and pendingBytes exclude the in-flight OCR frame. */
public final class OcrProgress {
    public final long processed, captured, dropped, pendingBytes, maxPendingBytes;
    public final int queued, maxPendingFrames;
    public final boolean recognizing;
    public final long ttlMs;

    OcrProgress(long processed, long captured, long dropped, int queued, long pendingBytes,
                boolean recognizing, int maxPendingFrames, long maxPendingBytes, long ttlMs) {
        this.processed = processed; this.captured = captured; this.dropped = dropped;
        this.queued = queued; this.pendingBytes = pendingBytes; this.recognizing = recognizing;
        this.maxPendingFrames = maxPendingFrames; this.maxPendingBytes = maxPendingBytes; this.ttlMs = ttlMs;
    }
}
