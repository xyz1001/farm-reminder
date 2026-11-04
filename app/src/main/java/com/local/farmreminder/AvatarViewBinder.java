package com.local.farmreminder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Visible-row-only loader. Owns loaded bitmaps; never decodes or builds asset paths on the UI thread. */
final class AvatarViewBinder {
    private static final int MAX_PIXELS = 65536, CACHE_SIZE = 12, VISIBLE_LIMIT = 8, REQUEST_LIMIT = 8;
    private final Context context;
    private View host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AvatarBindingPolicy policy = new AvatarBindingPolicy(REQUEST_LIMIT);
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(REQUEST_LIMIT - 1), runnable -> new Thread(runnable, "reminder-avatars"),
            new ThreadPoolExecutor.AbortPolicy());
    private final Map<ImageView, Binding> bindings = new LinkedHashMap<>();
    // null values also cache absent/invalid assets; a missing photo is not retried every ticker refresh.
    private final LinkedHashMap<String, Bitmap> cache = new LinkedHashMap<>(16, .75f, true);
    private volatile boolean closed;
    private boolean refreshPosted;
    private final Rect visible = new Rect();
    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnScrollChangedListener onScroll = this::schedule;
    private final ViewTreeObserver.OnGlobalLayoutListener onLayout = this::schedule;
    private final View.OnAttachStateChangeListener onAttach = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { watch(); schedule(); }
        @Override public void onViewDetachedFromWindow(View view) { unwatch(); }
    };
    private final Runnable refresh = () -> { refreshPosted = false; refreshVisible(); };

    private static final class Binding {
        final long token;
        final String ref;
        boolean showing;
        Binding(long token, String ref) { this.token = token; this.ref = ref; }
    }

    AvatarViewBinder(Context context, View host) {
        this.context = context.getApplicationContext(); this.host = host;
        host.addOnAttachStateChangeListener(onAttach);
        if (host.isAttachedToWindow()) watch();
    }

    void bind(ImageView image, String ref) {
        if (closed) return;
        String safe = AvatarRefPolicy.normalize(ref);
        bindings.put(image, new Binding(policy.bind(safe), safe));
        placeholder(image); schedule();
    }

    private void watch() {
        unwatch(); if (host == null) return;
        observer = host.getViewTreeObserver();
        observer.addOnScrollChangedListener(onScroll); observer.addOnGlobalLayoutListener(onLayout);
    }

    private void unwatch() {
        if (observer != null && observer.isAlive()) {
            observer.removeOnScrollChangedListener(onScroll); observer.removeOnGlobalLayoutListener(onLayout);
        }
        observer = null;
    }

    void schedule() {
        if (!closed && !refreshPosted) { refreshPosted = true; main.postDelayed(refresh, 48); }
    }

    private boolean visible(ImageView image) {
        return image.isShown() && image.isAttachedToWindow() && image.getGlobalVisibleRect(visible)
                && visible.width() > 0 && visible.height() > 0;
    }

    private void refreshVisible() {
        if (closed) return;
        int shown = 0;
        for (Map.Entry<ImageView, Binding> item : bindings.entrySet()) {
            ImageView image = item.getKey(); Binding binding = item.getValue();
            if (binding.ref.isEmpty() || !visible(image) || shown++ >= VISIBLE_LIMIT) {
                if (binding.showing) { placeholder(image); binding.showing = false; }
                continue;
            }
            if (cache.containsKey(binding.ref)) {
                Bitmap bitmap = cache.get(binding.ref);
                if (bitmap == null || !bitmap.isRecycled()) { display(image, binding, bitmap); continue; }
                cache.remove(binding.ref); placeholder(image); binding.showing = false;
            }
            AvatarBindingPolicy.Request decision = policy.request(binding.token, binding.ref);
            if (decision != AvatarBindingPolicy.Request.START) continue;
            final String ref = binding.ref;
            try {
                worker.execute(() -> {
                    Bitmap loaded;
                    try { loaded = AvatarStore.load(context, ref, MAX_PIXELS); }
                    catch (RuntimeException | OutOfMemoryError ignored) { loaded = null; }
                    final Bitmap result = loaded;
                    if (closed) { discardUnused(result); return; }
                    main.post(() -> completed(ref, result));
                });
            } catch (RejectedExecutionException ignored) { policy.complete(ref); }
        }
        trim();
    }

    private void completed(String ref, Bitmap bitmap) {
        if (closed) { discardUnused(bitmap); return; }
        Set<Long> waiting = policy.complete(ref);
        cache.put(ref, bitmap);
        int shown = 0;
        for (Map.Entry<ImageView, Binding> item : bindings.entrySet()) {
            Binding binding = item.getValue();
            if (binding.ref.isEmpty() || !visible(item.getKey()) || shown++ >= VISIBLE_LIMIT) continue;
            if (waiting.contains(binding.token) && policy.current(binding.token, ref))
                display(item.getKey(), binding, bitmap);
        }
        refreshVisible();
    }

    private void display(ImageView image, Binding binding, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            if (binding.showing) placeholder(image);
            binding.showing = false; return;
        }
        if (binding.showing) return;
        image.clearColorFilter();
        image.setImageBitmap(bitmap);
        image.setContentDescription("好友头像");
        binding.showing = true;
    }

    private void trim() {
        if (cache.size() <= CACHE_SIZE) return;
        for (String ref : new ArrayList<>(cache.keySet())) {
            boolean pinned = false;
            for (Binding binding : bindings.values()) if (binding.showing && binding.ref.equals(ref)) { pinned = true; break; }
            if (!pinned) cache.remove(ref);
            if (cache.size() <= CACHE_SIZE) break;
        }
        // Displayed/cached bitmaps are released to GC, never recycle()d while a View/display-list can own them.
    }

    private static void placeholder(ImageView image) {
        image.setImageResource(android.R.drawable.ic_menu_gallery);
        image.setColorFilter(0xFF89968A);
        image.setContentDescription("好友头像未定位");
    }

    private static void discardUnused(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); // Never assigned to a view/cache.
    }

    void clearRows() {
        for (ImageView image : bindings.keySet()) { image.setImageDrawable(null); image.clearColorFilter(); }
        bindings.clear(); policy.clearBindings(); trim();
    }

    void close() {
        if (closed) return;
        closed = true; main.removeCallbacks(refresh); unwatch();
        if (host != null) host.removeOnAttachStateChangeListener(onAttach);
        host = null; clearRows(); policy.close(); cache.clear(); worker.shutdownNow();
    }
}
