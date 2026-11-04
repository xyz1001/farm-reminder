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
    // Four cached + four pending full plates are at most 8 MiB of owned ARGB pixels, before GC.
    private static final int MAX_PIXELS = 65536, PLATE_PIXELS = ReminderImagePresentation.PLATE_MAX_PIXELS,
            CACHE_SIZE = ReminderImagePresentation.CACHE_ENTRIES, VISIBLE_LIMIT = CACHE_SIZE,
            REQUEST_LIMIT = ReminderImagePresentation.REQUEST_LIMIT;
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
        final String key;
        final ReminderImagePresentation.Kind kind;
        final Runnable loaded, missing;
        boolean showing;
        boolean resolved;
        Binding(long token, String ref, String key, ReminderImagePresentation.Kind kind, Runnable loaded, Runnable missing) {
            this.token = token; this.ref = ref; this.key = key; this.kind = kind; this.loaded = loaded; this.missing = missing;
        }
    }

    AvatarViewBinder(Context context, View host) {
        this.context = context.getApplicationContext(); this.host = host;
        host.addOnAttachStateChangeListener(onAttach);
        if (host.isAttachedToWindow()) watch();
    }

    void bind(ImageView image, String ref) {
        bind(image, ref, ReminderImagePresentation.Kind.PORTRAIT, null, null);
    }

    void bindPlate(ImageView image, String ref, Runnable loaded, Runnable missing) {
        bind(image, ref, ReminderImagePresentation.Kind.PLATE, loaded, missing);
    }

    private void bind(ImageView image, String ref, ReminderImagePresentation.Kind kind, Runnable loaded, Runnable missing) {
        if (closed) return;
        String safe = kind == ReminderImagePresentation.Kind.PLATE ? NameplateRefPolicy.normalize(ref) : AvatarRefPolicy.normalize(ref);
        String key = ReminderImagePresentation.key(kind, safe);
        bindings.put(image, new Binding(policy.bind(key), safe, key, kind, loaded, missing));
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
            if (cache.containsKey(binding.key)) {
                Bitmap bitmap = cache.get(binding.key);
                if (bitmap == null || !bitmap.isRecycled()) { display(image, binding, bitmap); continue; }
                cache.remove(binding.key); placeholder(image); binding.showing = false;
            }
            AvatarBindingPolicy.Request decision = policy.request(binding.token, binding.key);
            if (decision != AvatarBindingPolicy.Request.START) continue;
            final String ref = binding.ref;
            final String key = binding.key;
            final ReminderImagePresentation.Kind kind = binding.kind;
            try {
                worker.execute(() -> {
                    Bitmap loaded;
                    try { loaded = kind == ReminderImagePresentation.Kind.PLATE
                            ? NameplateStore.load(context, ref, PLATE_PIXELS) : AvatarStore.load(context, ref, MAX_PIXELS); }
                    catch (RuntimeException | OutOfMemoryError ignored) { loaded = null; }
                    final Bitmap result = loaded;
                    if (closed) { discardUnused(result); return; }
                    main.post(() -> completed(key, result));
                });
            } catch (RejectedExecutionException ignored) { policy.complete(key); }
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
            binding.showing = false;
            if (!binding.resolved && binding.missing != null) binding.missing.run();
            binding.resolved = true; return;
        }
        if (binding.kind == ReminderImagePresentation.Kind.PLATE) fitPlate(image, bitmap);
        if (binding.showing) return;
        image.clearColorFilter();
        image.setImageBitmap(bitmap);
        image.setContentDescription(binding.kind == ReminderImagePresentation.Kind.PLATE ? "好友完整铭牌" : "好友头像");
        binding.showing = true;
        binding.resolved = true;
        if (binding.loaded != null) binding.loaded.run();
    }

    private void fitPlate(ImageView image, Bitmap bitmap) {
        View parent = image.getParent() instanceof View ? (View) image.getParent() : null;
        if (parent == null || parent.getWidth() <= 0) return;
        int available = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        int insetX = image.getPaddingLeft() + image.getPaddingRight(), insetY = image.getPaddingTop() + image.getPaddingBottom();
        int[] size = ReminderImagePresentation.fitCard(bitmap.getWidth(), bitmap.getHeight(), available, insetX, insetY);
        android.view.ViewGroup.LayoutParams lp = image.getLayoutParams();
        if (size[0] > 0 && (lp.width != size[0] || lp.height != size[1])) {
            lp.width = size[0]; lp.height = size[1]; image.setLayoutParams(lp);
        }
    }

    private void trim() {
        if (cache.size() <= CACHE_SIZE) return;
        for (String ref : new ArrayList<>(cache.keySet())) {
            boolean pinned = false;
            for (Binding binding : bindings.values()) if (binding.showing && binding.key.equals(ref)) { pinned = true; break; }
            if (!pinned) cache.remove(ref);
            if (cache.size() <= CACHE_SIZE) break;
        }
        // Displayed/cached bitmaps are released to GC, never recycle()d while a View/display-list can own them.
    }

    private static void placeholder(ImageView image) {
        image.setImageResource(android.R.drawable.ic_menu_gallery);
        image.setColorFilter(0xFF89968A);
        image.setContentDescription("好友图片未定位");
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
