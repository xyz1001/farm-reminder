package com.local.farmreminder;

enum SaveOutcome {
    ADDED, DUPLICATE, FAILED;
    static SaveOutcome fromCommit(boolean committed) { return committed ? ADDED : FAILED; }
}
