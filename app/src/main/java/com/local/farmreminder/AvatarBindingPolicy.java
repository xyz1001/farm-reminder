package com.local.farmreminder;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Main-thread binding generations and bounded, coalesced decode requests; no Android objects. */
final class AvatarBindingPolicy {
    enum Request { START, JOIN, DEFER, INVALID }
    private long generation;
    private final int maxRequests;
    private final Map<Long, String> bindings = new HashMap<>();
    private final Map<String, Set<Long>> requests = new HashMap<>();

    AvatarBindingPolicy(int maxRequests) { this.maxRequests = maxRequests; }

    long bind(String ref) {
        long token = ++generation;
        bindings.put(token, ref);
        return token;
    }

    boolean current(long token, String ref) { return ref != null && ref.equals(bindings.get(token)); }

    Request request(long token, String ref) {
        if (ref == null || ref.isEmpty() || !current(token, ref)) return Request.INVALID;
        Set<Long> waiting = requests.get(ref);
        if (waiting != null) { waiting.add(token); return Request.JOIN; }
        if (requests.size() >= maxRequests) return Request.DEFER;
        waiting = new LinkedHashSet<>(); waiting.add(token); requests.put(ref, waiting);
        return Request.START;
    }

    Set<Long> complete(String ref) {
        Set<Long> waiting = requests.remove(ref);
        Set<Long> valid = new LinkedHashSet<>();
        if (waiting != null) for (Long token : waiting) if (current(token, ref)) valid.add(token);
        return valid;
    }

    void clearBindings() {
        bindings.clear();
        // In-flight work remains coalesced, but no obsolete row is retained as a waiter.
        for (Set<Long> waiting : requests.values()) waiting.clear();
    }

    void close() { clearBindings(); requests.clear(); }
}
