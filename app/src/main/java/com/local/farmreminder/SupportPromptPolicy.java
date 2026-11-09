package com.local.farmreminder;

final class SupportPromptPolicy {
    static boolean eligible(boolean eligible, boolean hasExistingReminder, boolean shown) {
        return (eligible || hasExistingReminder) && !shown;
    }

    static boolean recognitionQualifies(boolean committed, boolean replay, boolean confirmed) {
        return committed && !replay && confirmed;
    }

    private SupportPromptPolicy() { }
}
