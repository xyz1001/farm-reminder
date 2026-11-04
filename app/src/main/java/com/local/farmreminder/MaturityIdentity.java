package com.local.farmreminder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic fixed-anchor time identity, including fired historical records. */
final class MaturityIdentity {
    static final long WINDOW_MS = 10000;
    private final List<Reminder> anchors = new ArrayList<>();
    static List<List<Reminder>> partition(List<Reminder> records) {
        List<Reminder> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparingLong((Reminder r) -> r.matureAt).thenComparing(r -> r.id));
        List<List<Reminder>> groups = new ArrayList<>();
        for (Reminder r : sorted) {
            if (groups.isEmpty() || !within(groups.get(groups.size() - 1).get(0).matureAt, r.matureAt)) groups.add(new ArrayList<>());
            groups.get(groups.size() - 1).add(r);
        }
        return groups;
    }
    MaturityIdentity(List<Reminder> records) { for (List<Reminder> group : partition(records)) anchors.add(group.get(0)); }
    static boolean within(long a, long b) {
        return a <= b ? b - a >= 0 && b - a <= WINDOW_MS : a - b >= 0 && a - b <= WINDOW_MS;
    }
    Reminder match(long maturity) {
        for (Reminder r : anchors) if (within(r.matureAt, maturity)) return r;
        return null;
    }
    void addAnchor(Reminder r) {
        anchors.add(r);
        anchors.sort(Comparator.comparingLong((Reminder item) -> item.matureAt).thenComparing(item -> item.id));
    }
    static List<Long> maturities(long wall, List<Long> countdowns) {
        List<Long> values = new ArrayList<>();
        if (wall < 0 || countdowns == null) return values;
        for (Long value : countdowns) if (value != null && value >= 0 && wall <= Long.MAX_VALUE - value) values.add(wall + value);
        java.util.Collections.sort(values); return values;
    }
}
