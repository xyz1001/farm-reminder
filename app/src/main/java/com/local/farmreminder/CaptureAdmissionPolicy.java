package com.local.farmreminder;

final class CaptureAdmissionPolicy {
    private CaptureAdmissionPolicy() { }
    static boolean allowed(boolean usable, boolean alive, boolean running, boolean foreground,
                           String expectedSession, String currentSession) {
        return usable && alive && running && foreground && expectedSession != null
                && expectedSession.equals(currentSession);
    }
}
