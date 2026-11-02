package com.local.farmreminder;

/** Called only on the serial disk executor; an active frame must never be recovered over. */
final class DurableFrameRecovery {
    interface Attempt { void run() throws Exception; }
    static boolean attempt(DurableFrameFence fence, Attempt operation) {
        if (!fence.canRecover()) return false;
        try { operation.run(); return true; } catch (Exception e) { return false; }
    }
}
