package com.local.farmreminder;

/** Encoding choice is fixed when a capture is accepted, including across retries. */
final class ImageEncodingPolicy {
    static String fileName(int sdk) { return sdk >= 30 ? "image.webp" : "image.png"; }
    static int quality(int sdk) { return sdk >= 30 ? 0 : 100; }
}
