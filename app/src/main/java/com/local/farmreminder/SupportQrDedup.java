package com.local.farmreminder;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Byte-exact comparison and reuse decision, independent of Android storage APIs. */
final class SupportQrDedup {
    interface Open<T> { InputStream open(T candidate) throws IOException; }
    interface Action<T, R> { R run(T candidate) throws IOException; }
    interface Create<R> { R run() throws IOException; }

    static <T, R> R reuseOrCreate(Iterable<T> candidates, Open<T> open,
            Open<T> original, Action<T, R> reuse, Create<R> create) throws IOException {
        for (T candidate : candidates) {
            boolean same;
            try (InputStream input = open.open(candidate);
                 InputStream expected = original.open(candidate)) {
                same = input != null && expected != null && equal(input, expected);
            } catch (IOException | SecurityException e) {
                continue;
            }
            if (same) return reuse.run(candidate);
        }
        return create.run();
    }

    private static boolean equal(InputStream a, InputStream b) throws IOException {
        BufferedInputStream left = new BufferedInputStream(a);
        BufferedInputStream right = new BufferedInputStream(b);
        int value;
        while ((value = right.read()) != -1) {
            if (left.read() != value) return false;
        }
        return left.read() == -1;
    }

    private SupportQrDedup() { }
}
