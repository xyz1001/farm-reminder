package com.local.farmreminder;

import java.io.IOException;

/** Ensures an unpublished image is removed if writing or publication fails. */
final class SupportSavePipeline {
    interface Target<T> {
        T create() throws IOException;
        void write(T target) throws IOException;
        void publish(T target) throws IOException;
        void cleanup(T target);
    }

    static <T> T save(Target<T> target) throws IOException {
        T item = target.create();
        if (item == null) throw new IOException("Image creation failed");
        boolean complete = false;
        try {
            target.write(item);
            target.publish(item);
            complete = true;
            return item;
        } finally {
            if (!complete) try { target.cleanup(item); } catch (RuntimeException ignored) { }
        }
    }

    private SupportSavePipeline() { }
}
