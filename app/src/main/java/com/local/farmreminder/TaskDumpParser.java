package com.local.farmreminder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative parser for the activity-activities dump; it never returns unrelated titles. */
final class TaskDumpParser {
    enum State { MATCH, NON_MATCH, UNKNOWN }
    static final class Result {
        final State state;
        final String reason, key, component;
        Result(State state, String reason, String key, String component) {
            this.state = state; this.reason = reason; this.key = key; this.component = component;
        }
    }
    private static final Pattern RECORD = Pattern.compile("ActivityRecord\\{([^ ]+) u(\\d+) ([^ ]+) t(\\d+)(?: [^}]*)?}");
    private static final Pattern FOCUS = Pattern.compile("^\\s*mCurrentFocus=Window\\{([^ ]+) u(\\d+) ([^ }]+)(?: [^}]*)?}\\s*$");
    private static final Pattern TASK = Pattern.compile("^\\s*\\* Task\\{[^ ]+ #(\\d+) ([^}]*)}\\s*$");
    private static final Pattern DISPLAY = Pattern.compile("^Display #(\\d+) \\(activities from top to bottom\\):\\s*$");
    private static final Pattern FOCUS_DISPLAY = Pattern.compile("^\\s*Display: mDisplayId=(\\d+)(?:\\s.*)?$");
    private static final Pattern LABEL = Pattern.compile("^\\s*taskDescription: label=(null|\"[^\"]*\").*$");
    private static final Pattern COMPONENT = Pattern.compile("^\\s*mActivityComponent=([^\\s]+).*$");
    private static final Pattern WINDOW = Pattern.compile("Window\\{([^ ]+) u(\\d+) ([^ }]+)(?: [^}]*)?}");
    private static final Pattern STATE = Pattern.compile("^\\s*state=([^ ]+).*\\bfinishing=(true|false)(?:\\s.*)?$");
    private static final Pattern VISIBLE = Pattern.compile("^\\s*mVisibleRequested=(true|false) mVisible=(true|false)(?:\\s.*)?$");
    private static final String APPBRAND = "com.tencent.mm/com.tencent.mm.plugin.appbrand.ui.AppBrandUI";

    private static final class Record {
        final String id, user, component, task;
        Record(Matcher m) { id = m.group(1); user = m.group(2); component = normalize(m.group(3)); task = m.group(4); }
        boolean same(Record other) { return other != null && id.equals(other.id) && user.equals(other.user)
                && component.equals(other.component) && task.equals(other.task); }
    }
    private static String normalize(String component) {
        int slash = component.indexOf('/');
        if (slash < 1) return "";
        String pkg = component.substring(0, slash), cls = component.substring(slash + 1);
        return pkg + "/" + (cls.startsWith(".") ? pkg + cls : cls);
    }
    private static Result unknown(String reason) { return new Result(State.UNKNOWN, reason, null, null); }

    /** Caller must establish that the input is complete before using this trusted-input overload. */
    static Result parse(String dump) { return parse(dump, true); }

    static Result parse(String dump, boolean complete) {
        if (!complete || dump == null || !dump.contains("ACTIVITY MANAGER ACTIVITIES")) return unknown("incomplete");
        String[] lines = dump.split("\n", -1);
        int displays = 0, focusCount = 0, focusedCount = 0, resumedCount = 0, resumedDeclarationCount = 0, histCount = 0, visibleActivities = 0, histIndent = -1;
        String focusId = null, focusUser = null, focusComponent = null;
        Record focused = null, resumed = null, declaredResumed = null, hist = null;
        String taskLine = null, taskId = null, currentTask = null, currentTaskLine = null;
        String label = null, component = null, state = null, finishing = null, visible = null, windowId = null, windowUser = null, windowComponent = null;
        boolean inHist = false, targetHist = false, duplicateField = false, inActivities = false, focusDisplayZero = false, focusRegionValid = false;
        for (String raw : lines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            String trimmed = line.trim();
            int indent = 0;
            while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) indent++;
            if (inHist && !trimmed.isEmpty() && indent <= histIndent) { inHist = false; targetHist = false; }
            Matcher d = DISPLAY.matcher(line);
            if (d.matches()) { displays++; inActivities = true; focusDisplayZero = false; if (!"0".equals(d.group(1))) return unknown("multiple displays"); }
            if (trimmed.startsWith("Display:")) {
                Matcher fd = FOCUS_DISPLAY.matcher(line);
                focusDisplayZero = fd.matches() && "0".equals(fd.group(1));
            } else if (trimmed.startsWith("Display #")) focusDisplayZero = false;
            if (trimmed.startsWith("mCurrentFocus=")) {
                focusCount++; focusRegionValid = focusDisplayZero;
                Matcher f = FOCUS.matcher(line);
                if (f.matches()) { focusId = f.group(1); focusUser = f.group(2); focusComponent = normalize(f.group(3)); }
            }
            if (line.trim().startsWith("mFocusedApp=")) {
                focusedCount++; Matcher m = RECORD.matcher(line); if (m.find()) focused = new Record(m);
            }
            if (inActivities && line.trim().startsWith("topResumedActivity=")) {
                resumedCount++; Matcher m = RECORD.matcher(line); if (m.find()) resumed = new Record(m);
            }
            if (trimmed.startsWith("ResumedActivity:")) {
                resumedDeclarationCount++; Matcher m = RECORD.matcher(line); if (m.find()) declaredResumed = new Record(m);
                inActivities = false; inHist = false; targetHist = false;
            }
            if (!inActivities) continue;
            Matcher t = TASK.matcher(line);
            if (t.matches()) { currentTask = t.group(1); currentTaskLine = t.group(2); inHist = false; targetHist = false; }
            else if (trimmed.startsWith("* Task")) { currentTask = null; currentTaskLine = null; inHist = false; targetHist = false; }
            if (trimmed.startsWith("* Hist")) {
                inHist = true; targetHist = false; histIndent = indent;
                Matcher m = RECORD.matcher(line);
                if (m.find() && resumed != null && resumed.same(new Record(m))) {
                    histCount++; hist = new Record(m); taskId = currentTask; taskLine = currentTaskLine; targetHist = true;
                }
                continue;
            }
            if (inHist) {
                Matcher activityVisible = VISIBLE.matcher(line);
                if (activityVisible.matches() && "true".equals(activityVisible.group(2))) visibleActivities++;
            }
            if (!inHist || !targetHist) continue;
            Matcher m = LABEL.matcher(line);
            if (m.matches()) { if (label != null) duplicateField = true; label = m.group(1); }
            m = COMPONENT.matcher(line);
            if (m.matches()) { if (component != null) duplicateField = true; component = normalize(m.group(1)); }
            m = STATE.matcher(line);
            if (m.matches()) { if (state != null) duplicateField = true; state = m.group(1); finishing = m.group(2); }
            m = VISIBLE.matcher(line);
            if (m.matches()) { if (visible != null) duplicateField = true; visible = "true".equals(m.group(1)) && "true".equals(m.group(2)) ? "true" : "false"; }
            if (line.trim().startsWith("windows=[")) {
                m = WINDOW.matcher(line);
                while (m.find()) { if (windowId != null) duplicateField = true; windowId = m.group(1); windowUser = m.group(2); windowComponent = normalize(m.group(3)); }
            }
            if (line.contains("mLastReportedMultiWindowMode=true") || line.contains("mLastReportedPictureInPictureMode=true")) duplicateField = true;
        }
        if (displays != 1 || focusCount != 1 || !focusRegionValid || focusedCount != 1 || resumedCount != 1 || resumedDeclarationCount != 1 || histCount != 1 || visibleActivities != 1 || duplicateField
                || focused == null || resumed == null || hist == null || !focused.same(resumed) || !focused.same(hist)
                || !focused.same(declaredResumed)
                || !focused.task.equals(taskId) || taskLine == null || !taskLine.matches(".*\\bU=" + focused.user + "\\b.*")
                || !taskLine.matches(".*\\bvisible=true\\b.*") || !taskLine.matches(".*\\bvisibleRequested=true\\b.*") || !taskLine.matches(".*\\bmode=fullscreen\\b.*")
                || component == null || !component.equals(focused.component) || !"RESUMED".equals(state) || !"false".equals(finishing)
                || !"true".equals(visible) || focusId == null || !focusId.equals(windowId) || !focused.user.equals(focusUser)
                || !focusUser.equals(windowUser) || !focused.component.equals(focusComponent) || !focusComponent.equals(windowComponent)) return unknown("ambiguous foreground");
        if (!focused.component.startsWith(APPBRAND)) return new Result(State.NON_MATCH, "other activity", null, null);
        String suffix = focused.component.substring(APPBRAND.length());
        if (!suffix.matches("[0-9]*") || label == null || "null".equals(label)) return unknown("unsupported activity");
        if (!"\"QQ经典农场\"".equals(label)) return new Result(State.NON_MATCH, "other miniapp", null, null);
        return new Result(State.MATCH, "target", focused.user + ":" + focused.task + ":" + focused.id + ":" + focused.component, focused.component);
    }
}
