package com.local.farmreminder;

/** Presentation gates only; eligibility is consumed atomically by SupportPromptStore. */
final class SupportPageEntryPolicy {
    private SupportPageEntryPolicy() {}

    static boolean isNormalPage(String page) {
        return "home".equals(page) || "settings".equals(page);
    }

    static boolean canOffer(boolean resumed, boolean closing, boolean normalPage,
            boolean setupVisible, boolean requiredReady, boolean dialogShowing, boolean busy) {
        return resumed && !closing && normalPage && !setupVisible && requiredReady
                && !dialogShowing && !busy;
    }

    static boolean canOpenWeChat(boolean resumed, boolean closing, boolean initiatingActivity) {
        return resumed && !closing && initiatingActivity;
    }
}
