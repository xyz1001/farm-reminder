package com.local.farmreminder;

/** Local query fence. Access is serialized by the owning gate. */
final class GateState {
    static final long TTL = 1500;
    private long token;
    private boolean inFlight;

    long begin() {
        if (inFlight) return -1;
        inFlight = true;
        return ++token;
    }

    void invalidate() { token++; }

    boolean finish(long query, long age) {
        inFlight = false;
        return query == token && age >= 0 && age < TTL;
    }

    boolean current(long query) { return inFlight && query == token; }
    boolean busy() { return inFlight; }
    static boolean fresh(long age) { return age >= 0 && age < TTL; }
}
