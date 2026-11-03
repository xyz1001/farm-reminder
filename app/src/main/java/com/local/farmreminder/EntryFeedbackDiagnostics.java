package com.local.farmreminder;

import java.util.ArrayDeque;

/** Debug-only, process-memory bookkeeping. No identities, frame contents or remote reads. */
final class EntryFeedbackDiagnostics {
    enum Event { RUN_START, RUN_STOP, STAY_ENTER, DEPARTURE_RESET, CHANGE_RESET, PROBE,
        VERIFY_PASS, VERIFY_REJECT, VERIFY_FAIL, ANNOUNCEMENT_RESET, SUPPRESSED_BY_POLICY,
        TOAST_REQUESTED, TOAST_SHOWN, TOAST_HIDDEN, TOAST_EXCEPTION }
    enum Reason { NONE, NO_IDENTITY, UNTRUSTED_FOREGROUND, COMMIT_OR_REPLAY, SCREENSHOT_FAILURE,
        WRITE_FAILURE, OCR_FAILURE, STALE_RESULT, OTHER_FAILURE }
    private static final class Record {
        final Event event; final long time, run, stay, request;
        Record(Event event, long time, long run, long stay, long request) {
            this.event = event; this.time = time; this.run = run; this.stay = stay; this.request = request;
        }
    }
    private final ArrayDeque<Record> events = new ArrayDeque<>();
    private boolean enabled, foreground, requested, shown, hidden, callbackSupported, suppressionRecorded, budgetConsumed;
    private long run, stay, nextRequest, currentRequest, attempts, passes, rejects, failures;
    private long stayAt = -1, probeAt = -1, resultAt = -1, toastAt = -1;
    private String mode = "STOPPED", state = "READY", classifier = "NOT_RUN", exception = "none";
    private Reason reason = Reason.NONE;
    synchronized void enable(boolean enabled) { this.enabled = enabled; }
    private void event(Event event, long now, long eventRun, long eventStay, long request) {
        if (!enabled) return;
        if (events.size() == 12) events.removeFirst();
        events.addLast(new Record(event, now, eventRun, eventStay, request));
    }
    synchronized void start(String mode, long now) {
        if (!enabled) return;
        run++; this.mode = mode; foreground = false; state = "READY"; resetCurrent();
        event(Event.RUN_START, now, run, stay, 0);
    }
    synchronized void stop(long now) {
        if (!enabled) return;
        event(Event.RUN_STOP, now, run, stay, 0); mode = "STOPPED"; foreground = false; state = "READY"; currentRequest = 0;
    }
    private void resetCurrent() {
        currentRequest = 0; requested = shown = hidden = false; callbackSupported = false;
        exception = "none"; classifier = "NOT_RUN"; reason = Reason.NONE;
        suppressionRecorded = false;
        stayAt = probeAt = resultAt = toastAt = -1;
    }
    synchronized void enter(String state, long now) {
        if (!enabled) return;
        stay++; resetCurrent(); stayAt = now; this.state = state;
        event(Event.STAY_ENTER, now, run, stay, 0);
    }
    synchronized void foreground(boolean foreground, String state) {
        if (!enabled) return;
        this.foreground = foreground; this.state = state;
    }
    synchronized void reset(Event why, long now) {
        if (why == Event.ANNOUNCEMENT_RESET || why == Event.DEPARTURE_RESET || why == Event.CHANGE_RESET) budgetConsumed = false;
        if (why == Event.SUPPRESSED_BY_POLICY) {
            budgetConsumed = true;
            if (suppressionRecorded) return;
            suppressionRecorded = true;
        }
        event(why, now, run, stay, 0);
    }
    synchronized void budgetReset() { if (enabled) budgetConsumed = false; }
    synchronized void probe(String state, long now) {
        if (!enabled) return;
        attempts++; probeAt = now; this.state = state; classifier = "NOT_RUN";
        event(Event.PROBE, now, run, stay, 0);
    }
    synchronized void result(boolean pass, Reason reason, String classifier, String state, long now) {
        if (!enabled) return;
        if (pass) passes++; else rejects++;
        this.reason = reason; this.classifier = classifier; this.state = state; resultAt = now;
        event(pass ? Event.VERIFY_PASS : Event.VERIFY_REJECT, now, run, stay, 0);
    }
    synchronized void failure(Reason reason, String state, long now) {
        if (!enabled) return;
        failures++; this.reason = reason; this.state = state; resultAt = now;
        event(Event.VERIFY_FAIL, now, run, stay, 0);
    }
    synchronized long request(boolean supported, long now) {
        if (!enabled) return 0;
        currentRequest = ++nextRequest; requested = true; shown = hidden = false; budgetConsumed = true;
        callbackSupported = supported; toastAt = now; exception = "none";
        event(Event.TOAST_REQUESTED, now, run, stay, currentRequest); return currentRequest;
    }
    synchronized void callback(long request, long requestRun, long requestStay, boolean isShown, long now) {
        if (!enabled) return;
        event(isShown ? Event.TOAST_SHOWN : Event.TOAST_HIDDEN, now, requestRun, requestStay, request);
        if (run == requestRun && stay == requestStay && currentRequest == request) {
            if (isShown) shown = true; else hidden = true;
        }
    }
    synchronized void exception(long request, Throwable error, long now) {
        if (!enabled) return;
        if (currentRequest == request) exception = LiveDiagnostics.exceptionName(error);
        event(Event.TOAST_EXCEPTION, now, run, stay, request);
    }
    synchronized long run() { return run; }
    synchronized long stay() { return stay; }
    synchronized String snapshot(long now) {
        StringBuilder out = new StringBuilder("entryFeedback reset=process_memory mode=").append(mode)
                .append(" run=").append(run).append(" stay=").append(stay).append(" state=").append(state)
                .append(" foreground=").append(foreground).append(" attempts=").append(attempts)
                .append(" pass=").append(passes).append(" reject=").append(rejects).append(" fail=").append(failures)
                .append(" lastReason=").append(reason).append(" classifier=").append(classifier)
                .append(" announcementBudget=").append(budgetConsumed ? "CONSUMED" : "ARMED")
                .append(" toastRequested=").append(requested).append(" toastShown=").append(shown)
                .append(" toastHidden=").append(hidden).append(" callback=").append(callbackSupported ? "supported" : "unsupported_or_not_requested")
                .append(" exceptionClass=").append(exception).append(" stayAge=").append(LiveDiagnostics.age(now, stayAt))
                .append(" probeAge=").append(LiveDiagnostics.age(now, probeAt)).append(" resultAge=").append(LiveDiagnostics.age(now, resultAt))
                .append(" toastAge=").append(LiveDiagnostics.age(now, toastAt)).append(" events=[");
        boolean first = true;
        for (Record e : events) {
            if (!first) out.append(','); first = false;
            out.append(e.event).append('@').append(LiveDiagnostics.age(now, e.time)).append("ms:r")
                    .append(e.run).append(":s").append(e.stay).append(":q").append(e.request);
        }
        return out.append(']').toString();
    }
}
