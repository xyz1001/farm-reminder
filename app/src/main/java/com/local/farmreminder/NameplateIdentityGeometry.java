package com.local.farmreminder;

/** Direct regions of an unpadded stored plate, not a reconstructed portrait. */
final class NameplateIdentityGeometry {
    final NotificationIdentityParser.Region name, badge;
    private NameplateIdentityGeometry(int w, int h) {
        name = new NotificationIdentityParser.Region(.40 * w, .15 * h, .995 * w, .57 * h);
        badge = new NotificationIdentityParser.Region(.28 * w, .40 * h, .49 * w, .85 * h);
    }
    static NameplateIdentityGeometry of(int w, int h) {
        // Covers the canonical 112:50 crop and the observed 436:174 crop, not arbitrary wide images.
        if (!NameplateAssetPolicy.stored(w, h) || (long) w * 100 < (long) h * 215 - 200
                || (long) w * 100 > (long) h * 260 + 200) return null;
        return new NameplateIdentityGeometry(w, h);
    }
}
