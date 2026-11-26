package org.kodiscraper.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MoviePosterLoader implements AutoCloseable {
    private static final String IMAGE_HOST = "image.tmdb.org";
    private static final int MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CACHE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SOURCE_EDGE = 4096;
    private static final int MAX_SOURCE_PIXELS = 8_000_000;
    private static final int MAX_BITMAP_EDGE = 768;
    private static final int MAX_BITMAP_PIXELS = 330_000;
    private static final int MAX_ACTIVE_REQUESTS = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(MAX_ACTIVE_REQUESTS);
    private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
    private final Map<ImageView, Binding> bindings = new WeakHashMap<>();
    private final Set<String> active = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private final Rect visibleBounds = new Rect();
    private final LruCache<String, Bitmap> cache = new LruCache<>(MAX_CACHE_BYTES) {
        @Override
        protected int sizeOf(String url, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };

    private long nextSequence;
    private long failureGeneration;
    private volatile boolean closed;

    // These methods manage ImageView bindings on the main thread.
    void bind(ImageView view, String posterPath) {
        if (closed) return;
        String url = TmdbClient.moviePosterUrl(posterPath);
        Binding binding = bindings.get(view);
        if (binding != null && !binding.url.equals(url)) {
            if (alreadyDisplayed(view, binding)) view.setImageDrawable(null);
            bindings.remove(view);
            binding = null;
        }
        if (url == null || url.isEmpty()) return;
        if (binding == null) {
            binding = new Binding(url);
            bindings.put(view, binding);
        }
        binding.sequence = ++nextSequence;
        binding.attached |= view.isAttachedToWindow();
        if (alreadyDisplayed(view, binding)) return;

        Bitmap cached = cache.get(url);
        if (cached != null) {
            display(view, binding, cached);
            return;
        }
        schedule();
    }

    void resetFailures() {
        if (closed) return;
        failureGeneration++;
        failed.clear();
        schedule();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        workers.shutdownNow();
        for (Thread thread : workerThreads) {
            SubtitleHttp.cancel(thread);
        }
        active.clear();
        failed.clear();
        bindings.clear();
        cache.evictAll();
    }

    private void schedule() {
        while (!closed && active.size() < MAX_ACTIVE_REQUESTS) {
            Binding next = nextVisibleRequest();
            if (next == null) return;
            active.add(next.url);
            String url = next.url;
            long generation = failureGeneration;
            workers.execute(() -> load(url, generation));
        }
    }

    private Binding nextVisibleRequest() {
        Binding latest = null;
        for (Map.Entry<ImageView, Binding> entry : bindings.entrySet()) {
            ImageView view = entry.getKey();
            Binding binding = entry.getValue();
            if (view == null || !isVisible(view, binding) || alreadyDisplayed(view, binding)) continue;

            Bitmap cached = cache.get(binding.url);
            if (cached != null) {
                display(view, binding, cached);
            } else if (!active.contains(binding.url) && !failed.contains(binding.url)
                    && (latest == null || binding.sequence > latest.sequence)) {
                latest = binding;
            }
        }
        return latest;
    }

    private boolean isVisible(ImageView view, Binding binding) {
        if (!view.isAttachedToWindow()) return !binding.attached;
        binding.attached = true;
        return !view.isLaidOut() || view.getGlobalVisibleRect(visibleBounds);
    }

    private void load(String url, long generation) {
        Thread worker = Thread.currentThread();
        workerThreads.add(worker);
        Bitmap bitmap = null;
        try {
            if (!closed) {
                byte[] bytes = SubtitleHttp.get(url, null, null, MAX_DOWNLOAD_BYTES, IMAGE_HOST);
                bitmap = decode(bytes);
            }
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            // Missing and unreadable posters keep their placeholders until the next refresh.
        } finally {
            workerThreads.remove(worker);
        }
        if (!closed) {
            Bitmap result = bitmap;
            main.post(() -> complete(url, generation, result));
        }
    }

    private void complete(String url, long generation, Bitmap bitmap) {
        if (closed) return;
        active.remove(url);
        if (bitmap != null) {
            failed.remove(url);
            cache.put(url, bitmap);
        } else if (generation == failureGeneration) {
            failed.add(url);
        }
        schedule();
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        int width = options.outWidth;
        int height = options.outHeight;
        if (width <= 0 || height <= 0 || Math.max(width, height) > MAX_SOURCE_EDGE
                || (long) width * height > MAX_SOURCE_PIXELS) {
            return null;
        }

        int sample = 1;
        while (exceedsBitmapLimit(roundedDimension(width, sample), roundedDimension(height, sample))) {
            sample *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap != null && exceedsBitmapLimit(bitmap.getWidth(), bitmap.getHeight())) {
            bitmap.recycle();
            return null;
        }
        return bitmap;
    }

    private static int roundedDimension(int size, int sample) {
        return (size + sample - 1) / sample;
    }

    private static boolean exceedsBitmapLimit(int width, int height) {
        return Math.max(width, height) > MAX_BITMAP_EDGE || (long) width * height > MAX_BITMAP_PIXELS;
    }

    private static boolean alreadyDisplayed(ImageView view, Binding binding) {
        Bitmap bitmap = binding.displayed == null ? null : binding.displayed.get();
        Drawable current = view.getDrawable();
        return bitmap != null && current instanceof BitmapDrawable
                && ((BitmapDrawable) current).getBitmap() == bitmap;
    }

    private static void display(ImageView view, Binding binding, Bitmap bitmap) {
        binding.displayed = new WeakReference<>(bitmap);
        view.setImageBitmap(bitmap);
    }

    private static final class Binding {
        final String url;
        long sequence;
        boolean attached;
        WeakReference<Bitmap> displayed;

        Binding(String url) {
            this.url = url;
        }
    }
}
