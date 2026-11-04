package com.local.farmreminder;

/** Platform-independent status precedence and settings fallback, used by the Android adapter. */
final class NotificationSettingsPolicy {
    enum State {
        APP_BLOCKED("应用通知已关闭"), CHANNEL_MISSING("渠道尚未创建"),
        CHANNEL_BLOCKED("渠道已关闭"), VIBRATION_OFF("渠道振动未开启"),
        VIBRATION_ON("渠道振动已开启"), UNAVAILABLE("暂时无法读取通知设置");
        final String label;
        State(String label) { this.label = label; }
    }

    static State state(boolean appEnabled, Integer importance, boolean vibrates) {
        if (!appEnabled) return State.APP_BLOCKED;
        if (importance == null) return State.CHANNEL_MISSING;
        if (importance == 0) return State.CHANNEL_BLOCKED;
        return vibrates ? State.VIBRATION_ON : State.VIBRATION_OFF;
    }

    enum Target { CHANNEL, APP, DETAILS }
    interface Opener { boolean open(Target target); }

    /** null means all supported settings pages failed. Never changes a system preference. */
    static Target open(boolean channelAvailable, Opener opener) {
        if (channelAvailable && opener.open(Target.CHANNEL)) return Target.CHANNEL;
        if (opener.open(Target.APP)) return Target.APP;
        return opener.open(Target.DETAILS) ? Target.DETAILS : null;
    }

    private NotificationSettingsPolicy() { }
}
