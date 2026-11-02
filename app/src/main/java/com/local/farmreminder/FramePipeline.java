package com.local.farmreminder;

import java.util.ArrayDeque;

/** Main-thread-only bounded FIFO; in-flight work keeps its own resource until completion. */
final class FramePipeline<T> {
    interface Disposer<T> { void dispose(T value); }
    static final int MAX_COUNT = 3;
    static final long MAX_BYTES = 32L * 1024 * 1024, TTL_MS = 3000, REQUEST_TIMEOUT_MS = 12000;
    static final class Frame<T> {
        final T value; final long bytes, wall, uptime, request; final int generation, window;
        Frame(T value, long bytes, long wall, long uptime, int generation, int window, long request) {
            this.value=value; this.bytes=bytes; this.wall=wall; this.uptime=uptime;
            this.generation=generation; this.window=window; this.request=request;
        }
    }
    private final Disposer<T> disposer;
    private final ArrayDeque<Frame<T>> pending = new ArrayDeque<>();
    private long nextId, captureId, ocrId, bytes, captureStarted, ocrStarted;
    private long timedOutCapture, timedOutOcr;
    private Frame<T> activeOcr;
    long dropFull, dropExpired, dropInvalidated, lastQueueWaitMs;
    int maxQueueObserved;
    FramePipeline(Disposer<T> disposer) { this.disposer=disposer; }
    long beginCapture(long now) { if (!startAllowed() || captureId != 0) return 0; captureStarted=now; return captureId=++nextId; }
    boolean endCapture(long id) { if (id == 0 || captureId != id) return false; captureId=0; if (timedOutCapture == id) timedOutCapture=0; return true; }
    boolean captureUsable(long id) { return id != 0 && captureId == id && timedOutCapture != id; }
    boolean ocrUsable(long id) { return id != 0 && ocrId == id && timedOutOcr != id; }
    boolean startAllowed() { return timedOutCapture == 0 && timedOutOcr == 0; }
    boolean captureTimedOut() { return timedOutCapture != 0; }
    boolean ocrTimedOut() { return timedOutOcr != 0; }
    long captureId() { return captureId; }
    long ocrId() { return ocrId; }
    long captureStarted() { return captureStarted; }
    long ocrStarted() { return ocrStarted; }
    boolean timeoutCapture(long id, long now) {
        if (id == 0 || captureId != id || timedOutCapture == id || now < captureStarted || now-captureStarted < REQUEST_TIMEOUT_MS) return false;
        timedOutCapture=id; return true;
    }
    boolean timeoutOcr(long id, long now) {
        if (id == 0 || ocrId != id || timedOutOcr == id || now < ocrStarted || now-ocrStarted < REQUEST_TIMEOUT_MS) return false;
        timedOutOcr=id; return true;
    }
    boolean updateTimeouts(long now) {
        boolean capture=timeoutCapture(captureId, now), ocr=timeoutOcr(ocrId, now);
        return capture || ocr;
    }
    boolean captureBusy() { return captureId != 0; }
    boolean ocrBusy() { return ocrId != 0; }
    int pendingCount() { return pending.size(); }
    long pendingBytes() { return bytes; }
    void offer(Frame<T> f, long now) {
        expire(now);
        if (f.bytes <= 0 || f.bytes > MAX_BYTES) { dropFull++; disposer.dispose(f.value); return; }
        while (pending.size() >= MAX_COUNT || bytes > MAX_BYTES-f.bytes) { dropFull++; remove(); }
        pending.addLast(f); bytes += f.bytes; maxQueueObserved=Math.max(maxQueueObserved,pending.size());
    }
    Frame<T> beginOcr(long now) {
        if (!startAllowed() || ocrBusy()) return null;
        expire(now);
        Frame<T> frame=pending.pollFirst();
        if (frame == null) return null;
        bytes-=frame.bytes; lastQueueWaitMs=Math.max(0,now-frame.uptime); ocrId=frame.request; ocrStarted=now; activeOcr=frame; return frame;
    }
    private boolean endOcr(long id) { if (id == 0 || ocrId != id) return false; ocrId=0; activeOcr=null; if (timedOutOcr == id) timedOutOcr=0; return true; }
    boolean finishOcr(long id) {
        Frame<T> frame=activeOcr;
        if (!endOcr(id)) return false;
        disposer.dispose(frame.value); return true;
    }
    void clear() { while (!pending.isEmpty()) { dropInvalidated++; remove(); } }
    private void expire(long now) {
        while (!pending.isEmpty() && (now < pending.peekFirst().uptime || now-pending.peekFirst().uptime > TTL_MS)) {
            dropExpired++; remove();
        }
    }
    private void remove() { Frame<T> f=pending.removeFirst(); bytes-=f.bytes; disposer.dispose(f.value); }
    static long captureUptime(long timestamp, long requestUptime, long callbackUptime) {
        if (requestUptime >= 0 && callbackUptime >= requestUptime && timestamp >= requestUptime && timestamp <= callbackUptime) return timestamp;
        return Math.max(0, requestUptime <= callbackUptime ? requestUptime : callbackUptime);
    }
    static long captureWall(long timestamp, long requestUptime, long requestWall, long callbackUptime, long callbackWall) {
        if (requestUptime >= 0 && callbackUptime >= requestUptime && timestamp >= requestUptime && timestamp <= callbackUptime)
            return callbackWall-(callbackUptime-timestamp);
        return requestWall;
    }
}
