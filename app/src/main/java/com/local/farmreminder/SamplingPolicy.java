package com.local.farmreminder;

/** Main-thread sampling state, measured from the last actual screenshot request. */
final class SamplingPolicy {
    static final long MIN_INTERVAL = 500;
    private long lastCapture = -MIN_INTERVAL;
    private int generation;

    void invalidate() { generation++; }
    int generation() { return generation; }
    boolean valid(int token, boolean running, boolean foreground) {
        return token == generation && running && foreground;
    }
    long next(long now) { return Math.max(now, lastCapture + MIN_INTERVAL); }
    void captured(long now) { lastCapture = now; }
}
