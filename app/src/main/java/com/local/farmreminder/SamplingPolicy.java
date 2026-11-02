package com.local.farmreminder;

/** Main-thread sampling state. Events during an existing burst are coalesced. */
final class SamplingPolicy {
    static final long MIN_INTERVAL = 400;
    private long lastCapture = -MIN_INTERVAL;
    private long burstAt = -1;
    private long lastBurst = -2000;
    private int remaining;
    private int generation;

    void invalidate() { generation++; clearBurst(); }
    void clearBurst() { remaining = 0; burstAt = -1; }
    int generation() { return generation; }
    boolean valid(int token, boolean running, boolean foreground) {
        return token == generation && running && foreground;
    }
    void event(long now, boolean farm) {
        if (farm && remaining == 0 && now - lastBurst >= 2000) { remaining = 3; burstAt = now + 200; lastBurst = now; }
    }
    long next(long now, boolean farm) {
        long desired = farm && remaining > 0 ? burstAt : lastCapture + (farm ? 700 : 2000);
        return Math.max(now, Math.max(desired, lastCapture + MIN_INTERVAL));
    }
    void captured(long now) {
        lastCapture = now;
        if (remaining > 0) { remaining--; burstAt = now + MIN_INTERVAL; }
    }
}
