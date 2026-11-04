package com.local.farmreminder;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.Assert.*;

public class NameplateAssetPolicyTest {
    @Test public void strictRefsAndSeparateKeyNamespace() throws Exception {
        String key = NameplateRefPolicy.key("frame");
        assertEquals(64, key.length()); assertEquals(key, NameplateRefPolicy.normalize(key));
        assertEquals("", NameplateRefPolicy.normalize(key.toUpperCase(java.util.Locale.ROOT)));
        assertEquals("", NameplateRefPolicy.normalize("../unsafe"));
        byte[] avatar = MessageDigest.getInstance("SHA-256").digest("avatar-v1:frame".getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder(); for (byte b : avatar) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        assertNotEquals(value.toString(), key); assertEquals("", NameplateRefPolicy.key(""));
    }
    @Test public void storedCapsWideAspectAndNoUpscale() {
        assertTrue(NameplateAssetPolicy.stored(434, 188)); assertFalse(NameplateAssetPolicy.stored(256, 256));
        assertFalse(NameplateAssetPolicy.stored(769, 200)); assertFalse(NameplateAssetPolicy.wide(100, 0));
        NameplateAssetPolicy.Size small = NameplateAssetPolicy.fit(200, 100, 768, 768, 262144);
        assertEquals(200, small.width); assertEquals(100, small.height);
        NameplateAssetPolicy.Size large = NameplateAssetPolicy.fit(1600, 600, 768, 768, 262144);
        assertTrue(large.width <= 768); assertTrue((long) large.width * large.height <= 262144);
    }
    @Test public void notificationFitsFullPlateInsideCanvas() {
        NameplateAssetPolicy.Size fit = NameplateAssetPolicy.notificationFit(434, 188);
        assertTrue(fit.width <= 344); assertTrue(fit.height <= 164);
        assertTrue(Math.abs((double) fit.width / fit.height - 434.0 / 188.0) < .03);
        assertEquals(64800, NameplateAssetPolicy.NOTIFY_WIDTH * NameplateAssetPolicy.NOTIFY_HEIGHT);
        assertTrue(64800 <= 65536);
    }
}
