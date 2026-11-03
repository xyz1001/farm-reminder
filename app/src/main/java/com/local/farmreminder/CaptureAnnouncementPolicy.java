package com.local.farmreminder;

/** Suppresses duplicate announcements across uncertain foreground observations. */
final class CaptureAnnouncementPolicy {
    private boolean announced;
    void confirmedDeparture() { announced = false; }
    void reset() { announced = false; }
    boolean onAcceptedFrame() {
        if (announced) return false;
        announced = true;
        return true;
    }
}
