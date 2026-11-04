package com.local.farmreminder;

import java.util.List;

/** Select and revalidate the same representative for image and identity. */
final class NotificationIdentityBinding {
    static final class Snapshot {
        final String group, id, source, avatar, plate;
        Snapshot(String group, String id, String source, String avatar, String plate) {
            this.group = group; this.id = id; this.source = source; this.avatar = avatar; this.plate = plate;
        }
    }
    private NotificationIdentityBinding() { }
    static Snapshot select(String group, List<Reminder> reminders) {
        Reminder first = null, withAvatar = null, withPlate = null;
        for (Reminder r : reminders) {
            if (r == null || r.fired || r.id == null) continue;
            if (first == null || before(r, first)) first = r;
            if (!AvatarRefPolicy.normalize(r.avatarRef).isEmpty() && (withAvatar == null || before(r, withAvatar))) withAvatar = r;
            if (!NameplateRefPolicy.normalize(r.plateRef).isEmpty() && (withPlate == null || before(r, withPlate))) withPlate = r;
        }
        Reminder r = withPlate != null ? withPlate : withAvatar != null ? withAvatar : first;
        return r == null ? null : new Snapshot(group, r.id, SourceFrameRefPolicy.normalize(r.sourceFrameRef), AvatarRefPolicy.normalize(r.avatarRef), NameplateRefPolicy.normalize(r.plateRef));
    }
    private static boolean before(Reminder a, Reminder b) {
        return a.matureAt < b.matureAt || (a.matureAt == b.matureAt && a.id.compareTo(b.id) < 0);
    }
    static boolean samePrimary(Snapshot a, Snapshot b) {
        return a != null && b != null && a.group.equals(b.group) && a.id.equals(b.id);
    }
    static boolean sameAvatar(Snapshot a, Snapshot b) { return samePrimary(a, b) && !a.avatar.isEmpty() && a.avatar.equals(b.avatar); }
    static boolean samePlate(Snapshot a, Snapshot b) { return samePrimary(a, b) && !a.plate.isEmpty() && a.plate.equals(b.plate); }
    static boolean sameSource(Snapshot a, Snapshot b) { return samePrimary(a, b) && !a.source.isEmpty() && a.source.equals(b.source); }
}
