package com.local.farmreminder;

/** Main-thread per-stay single-frame admission and identity fence. */
final class ContainerCapturePolicy {
    enum State { READY, PENDING, VERIFIED, FAILED }
    private String key, session;
    private long epoch, sequence;
    private boolean unlimited;
    private State state = State.READY;

    boolean observe(String candidateKey, boolean confirmedDeparture) {
        if (confirmedDeparture) { key = null; clearSession(); return false; }
        if (candidateKey == null || candidateKey.equals(key)) return false;
        key = candidateKey; clearSession(); return true;
    }
    void enter(String session, long epoch, boolean unlimited) {
        this.session = session; this.epoch = epoch; this.unlimited = unlimited;
        state = State.READY; sequence = 0;
    }
    private void clearSession() { session = null; state = State.READY; }
    void clear() { key = null; clearSession(); }
    boolean matches(String session, long epoch) { return this.session != null && this.session.equals(session) && this.epoch == epoch; }
    boolean canRequest() { return session != null && (unlimited || state == State.READY || state == State.VERIFIED); }
    boolean begin(String session, long epoch, long sequence) {
        if (!matches(session, epoch) || !canRequest()) return false;
        if (!unlimited && state == State.READY) { this.sequence = sequence; state = State.PENDING; }
        return true;
    }
    boolean pending(String session, long epoch, long sequence) {
        return matches(session, epoch) && !unlimited && state == State.PENDING && this.sequence == sequence;
    }
    boolean permitsHandoff(String session, long epoch, long sequence) {
        return matches(session, epoch) && (unlimited || state == State.VERIFIED || pending(session, epoch, sequence));
    }
    boolean result(String session, long epoch, long sequence, boolean committed, boolean replay, boolean trusted, boolean identity) {
        if (!pending(session, epoch, sequence)) return false;
        if (!committed || replay || !trusted || !identity) { state = State.FAILED; return false; }
        state = State.VERIFIED; return true;
    }
    void fail(String session, long epoch, long sequence) { if (pending(session, epoch, sequence)) state = State.FAILED; }
    State state() { return state; }
}
