package com.local.farmreminder;

import java.util.List;

/** Select and revalidate the same representative for image and identity. */
final class NotificationIdentityBinding {
    static final class Snapshot {
        final String group, id, source, avatar;
        Snapshot(String group, String id, String source, String avatar) {
            this.group = group; this.id = id; this.source = source; this.avatar = avatar;
        }
    }
    private NotificationIdentityBinding() { }
    static Snapshot select(String group, List<Reminder> reminders) {
        Reminder first = null, withAvatar = null;
        for (Reminder r : reminders) {
            if (r == null || r.fired || r.id == null) continue;
            if (first == null || before(r, first)) first = r;
            if (!AvatarRefPolicy.normalize(r.avatarRef).isEmpty() && (withAvatar == null || before(r, withAvatar))) withAvatar = r;
        }
        Reminder r = withAvatar == null ? first : withAvatar;
        return r == null ? null : new Snapshot(group, r.id, SourceFrameRefPolicy.normalize(r.sourceFrameRef), AvatarRefPolicy.normalize(r.avatarRef));
    }
    private static boolean before(Reminder a, Reminder b) {
        return a.matureAt < b.matureAt || (a.matureAt == b.matureAt && a.id.compareTo(b.id) < 0);
    }
    static boolean samePrimary(Snapshot a, Snapshot b) {
        return a != null && b != null && a.group.equals(b.group) && a.id.equals(b.id);
    }
    static boolean sameAvatar(Snapshot a, Snapshot b) { return samePrimary(a, b) && !a.avatar.isEmpty() && a.avatar.equals(b.avatar); }
    static boolean sameSource(Snapshot a, Snapshot b) { return samePrimary(a, b) && !a.source.isEmpty() && a.source.equals(b.source); }
}
