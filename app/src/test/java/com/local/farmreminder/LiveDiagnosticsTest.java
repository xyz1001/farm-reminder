package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiveDiagnosticsTest {
    @Test public void unchangedPublishedStateStillShowsLiveProgress() {
        LiveDiagnostics d = new LiveDiagnostics();
        d.published(100);
        d.sample(200);
        d.deviceRead(200);
        String output = d.serviceSnapshot(250);
        assertTrue(output.contains("sampleCount=1"));
        assertTrue(output.contains("lastSampleAge=50"));
        assertTrue(output.contains("publishedAge=150"));
        assertTrue(output.contains("deviceStateReadAge=50"));
        assertEquals("QUERY_TIMEOUT", LiveDiagnostics.invalidateReason("查询超时"));
    }
    @Test public void unknownAgesAndUntrustedValuesAreSafe() {
        assertEquals("unknown", LiveDiagnostics.age(10, -1));
        assertEquals("unknown", LiveDiagnostics.age(9, 10));
        assertEquals("IllegalStateException", LiveDiagnostics.exceptionName(new IllegalStateException("secret")));
        assertEquals("REDACTED", LiveDiagnostics.remoteReason("private task name"));
        assertEquals("query failed", LiveDiagnostics.remoteReason("query failed"));
    }
}
