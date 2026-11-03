package com.local.farmreminder;

import java.util.Collections;
import java.util.List;

/** Keeps identity-only probe frames out of reminder creation, including on replay. */
final class FrameBusinessAdmission {
    static final String USAGE_PROBE = "accessibility_display_usage_probe";

    static List<Long> countdowns(String captureMethod, List<Long> raw) {
        return USAGE_PROBE.equals(captureMethod) ? Collections.emptyList() : raw;
    }
}
