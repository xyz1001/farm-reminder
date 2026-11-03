package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImageEncodingPolicyTest {
    @Test public void versionSelectsLosslessEncodingEffort() {
        assertEquals("image.png", ImageEncodingPolicy.fileName(29));
        assertEquals(100, ImageEncodingPolicy.quality(29));
        assertEquals("image.webp", ImageEncodingPolicy.fileName(30));
        assertEquals(0, ImageEncodingPolicy.quality(30));
        assertEquals("image.webp", ImageEncodingPolicy.fileName(35));
        assertEquals(0, ImageEncodingPolicy.quality(35));
    }
}
