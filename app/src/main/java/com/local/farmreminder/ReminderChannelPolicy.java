package com.local.farmreminder;

/** Channel migration decisions; Android importance values are NONE=0, DEFAULT=3. */
final class ReminderChannelPolicy {
    static final String LEGACY_ID = "maturity_short_vibration";
    static final String HEADS_UP_ID = "maturity_heads_up_v2";

    static final class Plan {
        final String channelId;
        final boolean createChannel;
        final boolean vibrate;

        Plan(String channelId, boolean createChannel, boolean vibrate) {
            this.channelId = channelId;
            this.createChannel = createChannel;
            this.vibrate = vibrate;
        }
    }

    static Plan select(Integer legacyImportance, boolean legacyUserSetImportance,
            boolean legacyVibrates, boolean headsUpExists) {
        // Never use the new channel to bypass a disabled legacy reminder channel.
        if (legacyImportance != null && legacyImportance == 0) return new Plan(null, false, false);
        // Once created, the new channel belongs to the user; do not reconfigure it.
        if (headsUpExists) return new Plan(HEADS_UP_ID, false, false);
        // Preserve an explicit importance choice. On API 26–28, a lower level is
        // also enough evidence not to migrate, even without hasUserSetImportance().
        if (legacyImportance != null && (legacyUserSetImportance || legacyImportance < 3)) {
            return new Plan(LEGACY_ID, false, legacyVibrates);
        }
        return new Plan(HEADS_UP_ID, true, legacyImportance == null || legacyVibrates);
    }

    /** Read-only settings target, including the blocked legacy channel that suppresses delivery. */
    static String settingsChannelId(Integer legacyImportance, Plan plan) {
        return legacyImportance != null && legacyImportance == 0 ? LEGACY_ID : plan.channelId;
    }

    private ReminderChannelPolicy() { }
}
