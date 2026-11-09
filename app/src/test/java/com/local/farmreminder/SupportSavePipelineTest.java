package com.local.farmreminder;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class SupportSavePipelineTest {
    private static final class Target implements SupportSavePipeline.Target<String> {
        final List<String> events = new ArrayList<>();
        boolean failWrite, failPublish;
        public String create() { events.add("create"); return "new-row"; }
        public void write(String item) throws IOException {
            events.add("write"); if (failWrite) throw new IOException("write");
        }
        public void publish(String item) throws IOException {
            events.add("publish"); if (failPublish) throw new IOException("publish");
        }
        public void cleanup(String item) { assertEquals("new-row", item); events.add("cleanup"); }
    }

    @Test public void writesBeforePublish() throws Exception {
        Target target = new Target();
        assertEquals("new-row", SupportSavePipeline.save(target));
        assertEquals(Arrays.asList("create", "write", "publish"), target.events);
    }

    @Test public void writeFailureNeverPublishesAndCleansUp() {
        Target target = new Target(); target.failWrite = true;
        try { SupportSavePipeline.save(target); fail(); } catch (IOException expected) { }
        assertEquals(Arrays.asList("create", "write", "cleanup"), target.events);
    }

    @Test public void publicationFailureCleansUp() {
        Target target = new Target(); target.failPublish = true;
        try { SupportSavePipeline.save(target); fail(); } catch (IOException expected) { }
        assertEquals(Arrays.asList("create", "write", "publish", "cleanup"), target.events);
    }

    @Test public void legacyPermissionOnlyNeededThrough28() {
        assertTrue(SupportQrStore.needsLegacyPermission(28, false));
        assertFalse(SupportQrStore.needsLegacyPermission(28, true));
        assertFalse(SupportQrStore.needsLegacyPermission(29, false));
        assertFalse(SupportQrStore.needsLegacyPermission(35, false));
    }
}
