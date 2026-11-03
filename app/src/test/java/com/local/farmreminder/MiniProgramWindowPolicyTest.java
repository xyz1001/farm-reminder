package com.local.farmreminder;

import static org.junit.Assert.*;
import org.junit.Test;

public class MiniProgramWindowPolicyTest {
    private static final String WX = "com.tencent.mm";
    private static final String MINI = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI1";
    private static final String LAUNCHER = "com.tencent.mm.ui.LauncherUI";

    private static void focus(MiniProgramWindowPolicy p, int id) { p.onFocusedWindow(WX, id, true, false); }
    private static void event(MiniProgramWindowPolicy p, String name, int id) { p.onWindowEvent(WX, name, id, "event"); }

    @Test public void positiveWindowNeedsUserStartNotOcr() {
        MiniProgramWindowPolicy p = new MiniProgramWindowPolicy();
        event(p, MINI, 42); focus(p, 42);
        assertTrue(p.present()); assertTrue(p.canCapture(true)); assertFalse(p.canCapture(false));
        event(p, MINI, 42); focus(p, 42); assertTrue(p.present());
        event(p, "android.widget.FrameLayout", 42); focus(p, 42); assertTrue(p.present());
        event(p, LAUNCHER, 42); focus(p, 42); assertFalse(p.present());
        event(p, LAUNCHER, 42); focus(p, 42); assertFalse(p.present());
    }

    @Test public void newWindowNeedsItsOwnEvidence() {
        MiniProgramWindowPolicy p = new MiniProgramWindowPolicy();
        event(p, MINI, 42); focus(p, 42); assertTrue(p.present());
        focus(p, 97); assertFalse(p.canCapture(true));
        focus(p, 42); assertFalse(p.present());
        event(p, "com.tencent.mm.plugin.appbrand.ui.AppBrandUI2", 97); focus(p, 97);
        assertTrue(p.present()); assertEquals(97, p.windowId());
    }

    @Test public void locksOtherAppsAndUnknownWechatAreNotMini() {
        MiniProgramWindowPolicy p = new MiniProgramWindowPolicy();
        event(p, "android.widget.FrameLayout", 9); focus(p, 9); assertFalse(p.present());
        event(p, LAUNCHER, 9); focus(p, 9); assertFalse(p.present());
        event(p, MINI, 9); focus(p, 9); p.onFocusedWindow("other.app", 10, true, false); assertFalse(p.present());
        event(p, MINI, 9); focus(p, 9); p.onFocusedWindow(WX, 9, true, true); assertFalse(p.present());
        focus(p, 9); assertFalse(p.present());
        event(p, MINI, 9); focus(p, 9); p.onFocusedWindow(WX, 9, false, false); assertFalse(p.present());
    }

    @Test public void classFamilyIsExact() {
        assertTrue(MiniProgramWindowPolicy.isMiniClass("com.tencent.mm.plugin.appbrand.ui.AppBrandUI"));
        assertTrue(MiniProgramWindowPolicy.isMiniClass(MINI));
        assertFalse(MiniProgramWindowPolicy.isMiniClass(MINI + "Extra"));
        assertFalse(MiniProgramWindowPolicy.isMiniClass(LAUNCHER));
        assertFalse(MiniProgramWindowPolicy.isMiniClass("android.widget.FrameLayout"));
    }
}
