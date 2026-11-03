package com.local.farmreminder;

/** Tracks positive component evidence separately from the currently focused application window. */
final class MiniProgramWindowPolicy {
    private static final String WECHAT = "com.tencent.mm";
    private static final String APP_BRAND = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI";
    private int confirmedId = -1, pendingId = -1, focusedId = -1;
    private boolean present;
    private String lastWindowClass = "", positiveSource = "none";

    static boolean isMiniClass(String name) {
        if (name == null || !name.startsWith(APP_BRAND)) return false;
        String suffix = name.substring(APP_BRAND.length());
        for (int i = 0; i < suffix.length(); i++)
            if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') return false;
        return true;
    }

    void onWindowEvent(String pkg, String name, int id, String source) {
        if (!WECHAT.equals(pkg) || name == null || id < 0) return;
        // Only component class names are retained, never arbitrary window titles.
        if (name.startsWith("com.tencent.mm.") && name.matches("[A-Za-z0-9_.$]+UI[0-9]*")) lastWindowClass = name;
        if (isMiniClass(name)) {
            pendingId = id;
            positiveSource = source;
        } else if (name.startsWith("com.tencent.mm.") && name.matches("[A-Za-z0-9_.$]+UI[0-9]*")) {
            if (confirmedId == id) confirmedId = -1;
            if (pendingId == id) pendingId = -1;
        }
    }

    void onFocusedWindow(String pkg, int id, boolean interactive, boolean locked) {
        if (!interactive || locked || !WECHAT.equals(pkg) || id < 0) {
            confirmedId = pendingId = focusedId = -1;
            present = false;
            return;
        }
        if (id != focusedId) {
            confirmedId = -1;
            focusedId = id;
            if (pendingId != id) pendingId = -1;
        }
        if (pendingId == id) { confirmedId = id; pendingId = -1; }
        present = confirmedId == id;
    }

    boolean canCapture(boolean running) { return running && present; }
    boolean present() { return present; }
    int windowId() { return present ? focusedId : -1; }
    String lastWindowClass() { return lastWindowClass; }
    String positiveSource() { return positiveSource; }
    void reset() { confirmedId = pendingId = focusedId = -1; present = false; positiveSource = "none"; }
}
