package com.local.farmreminder;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TaskDumpParserTest {
    private static String fixture(String component, String label) {
        String full = component.replace("/.", "/com.tencent.mm.");
        return "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
                + "Display #0 (activities from top to bottom):\n"
                + "  * Task{abc #42 type=standard U=0 visible=true visibleRequested=true mode=fullscreen}\n"
                + "    topResumedActivity=ActivityRecord{rec u0 " + component + " t42}\n"
                + "    * Hist  #0: ActivityRecord{rec u0 " + component + " t42}\n"
                + "      mActivityComponent=" + component + "\n"
                + "      taskDescription: label=" + label + " icon=null\n"
                + "      state=RESUMED delayedResume=false finishing=false\n"
                + "      windows=[Window{win u0 " + full + "}]\n"
                + "      mVisibleRequested=true mVisible=true mClientVisible=true\n"
                + "  ResumedActivity: ActivityRecord{rec u0 " + component + " t42}\n"
                + "  Display: mDisplayId=0 (organized)\n"
                + "  mCurrentFocus=Window{win u0 " + full + "}\n"
                + "  mFocusedApp=ActivityRecord{rec u0 " + component + " t42}\n";
    }
    private static String target() { return fixture("com.tencent.mm/.plugin.appbrand.ui.AppBrandUI37", "\"QQ经典农场\""); }
    @Test public void targetAndOtherMiniapp() {
        assertEquals(TaskDumpParser.State.MATCH, TaskDumpParser.parse(target()).state);
        assertEquals(TaskDumpParser.State.NON_MATCH, TaskDumpParser.parse(target().replace("QQ经典农场", "其他")).state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(target().replace("\"QQ经典农场\"", "null")).state);
    }
    @Test public void rejectsAmbiguities() {
        String s = target();
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(s.replace("mCurrentFocus=Window{win", "mCurrentFocus=Window{different")).state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(s.replace("mFocusedApp=ActivityRecord{rec u0", "mFocusedApp=ActivityRecord{rec u1")).state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(s.replace("state=RESUMED", "state=STOPPED")).state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(s.replace("mode=fullscreen", "mode=multi-window")).state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(s + "Display #1 (activities from top to bottom):\n").state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(s, false).state);
        assertEquals(TaskDumpParser.State.MATCH, TaskDumpParser.parse(s.substring(0, s.length() - 1), true).state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse("ACTIVITY MANAGER ACTIVITIES\n", true).state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(s + "  mCurrentFocus=Window{win u0 com.tencent.mm/com.tencent.mm.plugin.appbrand.ui.AppBrandUI37}\n").state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(s.replace("mFocusedApp=ActivityRecord{rec u0 com.tencent.mm/.plugin.appbrand.ui.AppBrandUI37 t42", "mFocusedApp=ActivityRecord{rec u0 com.tencent.mm/.plugin.appbrand.ui.AppBrandUI37 t43")).state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(s.replace("  ResumedActivity:", "    * Hist  #1: ActivityRecord{other u0 com.tencent.mm/.plugin.appbrand.ui.AppBrandUI37 t43}\n      mVisibleRequested=true mVisible=true mClientVisible=true\n  ResumedActivity:")).state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(s.replace("mCurrentFocus=Window{win u0 com.tencent.mm/com.tencent.mm.plugin.appbrand.ui.AppBrandUI37}", "mCurrentFocus=null")).state);
    }
    @Test public void stoppedFarmLabelDoesNotLeakIntoCurrentActivity() {
        String s = target().replace("\"QQ经典农场\"", "\"其他\"")
                .replace("  ResumedActivity:", "    * Hist  #1: ActivityRecord{old u0 com.tencent.mm/.plugin.appbrand.ui.AppBrandUI37 t42}\n"
                        + "      taskDescription: label=\"QQ经典农场\" icon=null\n"
                        + "      state=STOPPED delayedResume=false finishing=false\n"
                        + "      mVisibleRequested=false mVisible=false mClientVisible=false\n  ResumedActivity:");
        assertEquals(TaskDumpParser.State.NON_MATCH, TaskDumpParser.parse(s).state);
    }
    @Test public void histScopeCannotBorrowParentOrUnknownTaskFields() {
        String noLabel = target().replace("      taskDescription: label=\"QQ经典农场\" icon=null\n", "");
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(noLabel.replace("  ResumedActivity:", "  taskDescription: label=\"QQ经典农场\" icon=null\n  ResumedActivity:")).state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(noLabel.replace("  ResumedActivity:", "  * Task{unrecognized}\n      taskDescription: label=\"QQ经典农场\" icon=null\n  ResumedActivity:")).state);
    }
    @Test public void focusDeclarationsAndRegionMustBeUnambiguous() {
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(target() + "  mCurrentFocus=null\n").state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(target() + "  mCurrentFocus=unknown\n").state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(target().replace("mDisplayId=0", "mDisplayId=7")).state);
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(target().replace("  Display: mDisplayId=0 (organized)\n", "")).state);
    }
    @Test public void resumedRecordMustMatchAllIdentityFields() {
        for (String replacement : new String[] {"other u0", "rec u1", "rec u0"}) {
            String s = target();
            String old = "ResumedActivity: ActivityRecord{rec u0 com.tencent.mm/.plugin.appbrand.ui.AppBrandUI37 t42}";
            String changed = "ResumedActivity: ActivityRecord{" + replacement + " com.tencent.mm/.plugin.appbrand.ui.AppBrandUI37 t" + (replacement.equals("rec u0") ? "43" : "42") + "}";
            assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(s.replace(old, changed)).state);
        }
    }
    @Test public void activityRequestedFalseIsRejected() {
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(target().replace("mVisibleRequested=true", "mVisibleRequested=false")).state);
    }
    @Test public void activityVisibleFalseIsRejected() {
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(target().replace("mVisible=true", "mVisible=false")).state);
    }
    @Test public void finishingActivityIsRejected() {
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(target().replace("finishing=false", "finishing=true")).state);
    }
    @Test public void taskRequestedFalseIsRejected() {
        assertEquals(TaskDumpParser.State.UNKNOWN, TaskDumpParser.parse(target().replace("visibleRequested=true mode=", "visibleRequested=false mode=")).state);
    }
}
