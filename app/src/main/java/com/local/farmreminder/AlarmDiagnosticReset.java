package com.local.farmreminder;

/** Best-effort replacement of diagnostic extras after a failed alarm update. */
final class AlarmDiagnosticReset {
    static RuntimeException afterFailure(boolean failed, boolean hasSample, Runnable clearExtras) {
        if (!failed || !hasSample) return null;
        try { clearExtras.run(); return null; }
        catch (RuntimeException error) { return error; }
    }
}
