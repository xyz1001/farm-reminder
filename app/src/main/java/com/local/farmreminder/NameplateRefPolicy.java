package com.local.farmreminder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Opaque independent key; never a path or a legacy portrait key. */
final class NameplateRefPolicy {
    private NameplateRefPolicy() { }
    static String normalize(String value) { return value != null && value.matches("[0-9a-f]{64}") ? value : ""; }
    static String key(String frameKey) {
        if (frameKey == null || frameKey.isEmpty() || frameKey.length() > 512) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("nameplate-v1:" + frameKey).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { return ""; }
    }
}
