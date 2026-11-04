package com.local.farmreminder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
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
    private static final int PLATE_PIXELS = ReminderImagePresentation.PLATE_MAX_PIXELS,
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
        boolean showing;
        int width, height;
        Binding(long token, String ref, String key) {
            this.token = token; this.ref = ref; this.key = key;
        }
    }

    AvatarViewBinder(Context context, View host) {
        this.context = context.getApplicationContext(); this.host = host;
        host.addOnAttachStateChangeListener(onAttach);
        if (host.isAttachedToWindow()) watch();
    }

    void bindPlate(ImageView image, String ref) {
        if (closed) return;
        String safe = NameplateRefPolicy.normalize(ref);
        String key = ReminderImagePresentation.key(ReminderImagePresentation.Kind.PLATE, safe);
        bindings.put(image, new Binding(policy.bind(key), safe, key));
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
            // Empty refs also participate in layout, including first measure and orientation changes.
            fitSlot(image, binding);
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
            try {
                worker.execute(() -> {
                    Bitmap loaded;
                    try { loaded = NameplateStore.load(context, ref, PLATE_PIXELS); }
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
            binding.width = binding.height = 0;
            fitSlot(image, binding);
            return;
        }
        binding.width = bitmap.getWidth(); binding.height = bitmap.getHeight();
        fitSlot(image, binding);
        if (binding.showing) return;
        image.clearColorFilter();
        image.setImageBitmap(bitmap);
        image.setContentDescription("好友完整铭牌");
        binding.showing = true;
    }

    private void fitSlot(ImageView image, Binding binding) {
        View parent = image.getParent() instanceof View ? (View) image.getParent() : null;
        if (parent == null || parent.getWidth() <= 0) return;
        int available = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        int insetX = image.getPaddingLeft() + image.getPaddingRight(), insetY = image.getPaddingTop() + image.getPaddingBottom();
        int[] size = ReminderImagePresentation.listSlot(binding.width, binding.height, available, insetX, insetY);
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
        image.clearColorFilter();
        image.setImageDrawable(new PlatePlaceholder(12 * image.getResources().getDisplayMetrics().scaledDensity));
        image.setContentDescription("暂无铭牌");
    }

    /** No intrinsic square icon: draw quiet text at the actual wide slot's center. */
    private static final class PlatePlaceholder extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PlatePlaceholder(float textSize) {
            paint.setColor(0xFF657367); paint.setTextSize(textSize); paint.setTextAlign(Paint.Align.CENTER);
        }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawText("暂无铭牌", bounds.exactCenterX(),
                    bounds.exactCenterY() - (paint.ascent() + paint.descent()) / 2, paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
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
