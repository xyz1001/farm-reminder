package com.local.farmreminder;

/** One worker and one coalesced wakeup; a stuck transport cannot accumulate work. */
final class AsyncControl {
    private final Runnable work;
    private boolean pending;

    AsyncControl(Runnable work) {
        this.work = work;
        Thread thread = new Thread(this::run, "shizuku-control");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void wake() { pending = true; notifyAll(); }

    private void run() {
        for (;;) {
            synchronized (this) {
                while (!pending) {
                    try { wait(); } catch (InterruptedException ignored) { }
                }
                pending = false;
            }
            try { work.run(); } catch (RuntimeException ignored) { }
        }
    }
}
