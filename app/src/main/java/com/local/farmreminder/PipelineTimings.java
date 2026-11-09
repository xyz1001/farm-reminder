package com.local.farmreminder;

/** Bounded anonymous, process-local diagnostic events; no image or OCR data. */
final class PipelineTimings {
    enum Event { ENQUEUE, DISK_ARRIVAL, PUBLISH_COMPLETE, NATIVE_FRAME, NATIVE_START, NATIVE_RETURN,
        NATIVE_WATCHDOG, NATIVE_CALLBACK, CAPTURE_REQUEST, CAPTURE_CALLBACK, CAPTURE_COPY, CAPTURE_WATCHDOG }
    private final String[] events = new String[64];
    private long count;
    synchronized void record(Event event, long id, long elapsed, long uptime, long value) {
        events[(int) (count++ % events.length)] = event + " id=" + id + " elapsed=" + elapsed
                + " uptime=" + uptime + " value=" + value;
    }
    synchronized String snapshot() {
        StringBuilder out = new StringBuilder();
        for (long i = Math.max(0, count - events.length); i < count; i++)
            out.append(events[(int) (i % events.length)]).append('\n');
        return out.toString();
    }
}
