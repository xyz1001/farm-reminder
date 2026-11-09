package com.local.farmreminder;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SupportQrDedupTest {
    private static final byte[] ORIGINAL = {1, 2, 3, 4};

    @Test public void exactPreviousCandidateIsReusedWithoutCreation() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        String result = SupportQrDedup.reuseOrCreate(Arrays.asList(new byte[]{1, 2}, ORIGINAL),
                ByteArrayInputStream::new, ignored -> new ByteArrayInputStream(ORIGINAL),
                ignored -> "existing", () -> { creates.incrementAndGet(); return "new"; });
        assertEquals("existing", result);
        assertEquals(0, creates.get());
    }

    @Test public void missingMismatchAndExtraBytesCreate() throws Exception {
        for (java.util.List<byte[]> candidates : Arrays.asList(Collections.<byte[]>emptyList(),
                Arrays.asList(new byte[]{1, 2, 3, 5}, new byte[]{1, 2, 3, 4, 5}))) {
            assertEquals("new", SupportQrDedup.reuseOrCreate(candidates, ByteArrayInputStream::new,
                    ignored -> new ByteArrayInputStream(ORIGINAL), ignored -> "existing", () -> "new"));
        }
    }

    @Test public void unreadableCandidateFallsBack() throws Exception {
        assertEquals("new", SupportQrDedup.reuseOrCreate(Collections.singletonList("deleted"),
                ignored -> { throw new IOException("unreadable"); },
                ignored -> new ByteArrayInputStream(ORIGINAL), ignored -> "existing", () -> "new"));
    }

    @Test public void failedCreationIsNotCached() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            try {
                SupportQrDedup.reuseOrCreate(Collections.<byte[]>emptyList(), ByteArrayInputStream::new,
                        ignored -> new ByteArrayInputStream(ORIGINAL), ignored -> "existing",
                        () -> { creates.incrementAndGet(); throw new IOException("failed"); });
                fail("expected failure");
            } catch (IOException expected) { assertEquals("failed", expected.getMessage()); }
        }
        assertEquals(2, creates.get());
    }
}
