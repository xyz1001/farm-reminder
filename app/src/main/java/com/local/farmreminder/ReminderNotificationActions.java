package com.local.farmreminder;

/** Stable identities used by the renderer's direct Activity PendingIntents. */
final class ReminderNotificationActions {
    static final String TAG = "maturity_reminder";
    enum Target {
        WECHAT(701, "打开微信"), APP(702, "打开应用");
        final int requestCode;
        final String label;
        Target(int requestCode, String label) { this.requestCode = requestCode; this.label = label; }
    }

    static Target[] available(boolean wechatAvailable) {
        return wechatAvailable ? new Target[]{Target.WECHAT, Target.APP} : new Target[]{Target.APP};
    }

    static Target contentTarget() { return Target.APP; }

    private ReminderNotificationActions() { }
}
