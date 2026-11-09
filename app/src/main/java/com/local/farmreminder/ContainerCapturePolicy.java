package com.local.farmreminder;

/** Main-thread per-stay single-frame admission and identity fence. */
final class ContainerCapturePolicy {
    enum State { READY, PENDING, VERIFIED, FAILED }
    enum CaptureIssue { NONE, SCREENSHOT_FAILED, SCREENSHOT_RATE_LIMIT }
    private String key, session;
    private long epoch, sequence;
    private boolean unlimited;
    private State state = State.READY;
    private CaptureIssue captureIssue = CaptureIssue.NONE;
    private static final int MAX_RATE_LIMIT_RETRIES = 2;
    private static final long RATE_LIMIT_BACKOFF_MS = 2000;
    private int rateLimitRetries;
    private long retryNotBefore;

    boolean observe(String candidateKey, boolean confirmedDeparture) {
        if (confirmedDeparture) { key = null; clearSession(); return false; }
        if (candidateKey == null || candidateKey.equals(key)) return false;
        key = candidateKey; clearSession(); return true;
    }
    void enter(String session, long epoch, boolean unlimited) {
        this.session = session; this.epoch = epoch; this.unlimited = unlimited;
        state = State.READY; sequence = 0; rateLimitRetries = 0; retryNotBefore = 0; captureIssue = CaptureIssue.NONE;
    }
    private void clearSession() { session = null; state = State.READY; captureIssue = CaptureIssue.NONE; }
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
        state = State.VERIFIED; captureIssue = CaptureIssue.NONE; return true;
    }
    void fail(String session, long epoch, long sequence) { if (pending(session, epoch, sequence)) state = State.FAILED; }
    void screenshotFailure(String session, long epoch, long sequence, CaptureIssue issue) {
        if (pending(session, epoch, sequence) && issue != null && issue != CaptureIssue.NONE) captureIssue = issue;
    }
    CaptureIssue captureIssue(String session, long epoch) {
        return matches(session, epoch) && state == State.FAILED ? captureIssue : CaptureIssue.NONE;
    }
    /** Only call for a current, foreground-trusted interval-too-short callback. */
    boolean retryRateLimit(String session, long epoch, long sequence, long now) {
        if (!pending(session, epoch, sequence)) return false;
        if (rateLimitRetries >= MAX_RATE_LIMIT_RETRIES) return false;
        rateLimitRetries++;
        long base = Math.max(now, retryNotBefore);
        retryNotBefore = base > Long.MAX_VALUE - RATE_LIMIT_BACKOFF_MS
                ? Long.MAX_VALUE : base + RATE_LIMIT_BACKOFF_MS;
        state = State.READY;
        return true;
    }
    long retryNotBefore() { return retryNotBefore; }
    State state() { return state; }
}
