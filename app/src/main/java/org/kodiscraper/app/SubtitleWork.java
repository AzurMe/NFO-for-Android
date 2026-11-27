package org.kodiscraper.app;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

final class SubtitleWork implements AutoCloseable {
    private final CompletableFuture<SubtitleQuery> matched = new CompletableFuture<>();
    private final FutureTask<SubtitleScraper.Outcome> result;
    private final Thread thread;

    SubtitleWork(SubtitleScraper scraper, SubtitleQuery local) {
        result = new FutureTask<>(() -> scraper.download(local, () -> await(matched)));
        thread = new Thread(result, "MovieSubtitles");
        thread.start();
    }

    void matched(SubtitleQuery query) {
        matched.complete(query);
    }

    SubtitleScraper.Outcome result() throws IOException {
        return await(result);
    }

    private static <T> T await(Future<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("操作已停止");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("字幕处理失败", cause);
        }
    }

    @Override
    public void close() {
        if (!result.isDone()) {
            thread.interrupt();
            SubtitleHttp.cancel(thread);
        }
        // Wait for any in-progress file transaction before allowing another task to start.
        boolean interrupted = Thread.interrupted();
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException cancelled) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
