package com.local.farmreminder;

/** Deadline shared by resolve, decode, native submission and await. */
final class NotificationOcrBudget {
    interface Clock { long elapsed(); }
    interface Await<T> { T get(long milliseconds) throws Exception; }
    interface Start<T> { T run(); }
    private NotificationOcrBudget() { }
    static long remaining(long deadline, Clock clock) { return deadline - clock.elapsed(); }
    static <T> T startIfTime(long deadline, Clock clock, Start<T> start) {
        return remaining(deadline, clock) > 0 ? start.run() : null;
    }
    static <T> T await(long deadline, Clock clock, Await<T> await) throws Exception {
        long remaining = remaining(deadline, clock);
        if (remaining <= 0) return null;
        try { return await.get(remaining); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
    }
}
