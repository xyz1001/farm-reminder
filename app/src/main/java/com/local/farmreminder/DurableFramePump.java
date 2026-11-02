package com.local.farmreminder;

import java.util.concurrent.Executor;

/** Coalesces pump requests and always enqueues the next iteration. */
final class DurableFramePump {
    private final Executor executor;
    private boolean queued;
    DurableFramePump(Executor executor) { this.executor = executor; }
    synchronized void request(Runnable work) {
        if (queued) return;
        queued = true;
        executor.execute(() -> {
            synchronized (DurableFramePump.this) { queued = false; }
            work.run();
        });
    }
}
