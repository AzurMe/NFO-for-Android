package org.kodiscraper.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

final class DebugLog {
    private static final long SEGMENT_LIMIT = 1024 * 1024;
    private static volatile boolean enabled;
    private static File current;
    private static File previous;

    private DebugLog() {}

    static synchronized void configure(Context context, boolean record) {
        current = new File(context.getFilesDir(), "debug-current.log");
        previous = new File(context.getFilesDir(), "debug-previous.log");
        enabled = record;
    }

    static void event(String category, String details) {
        if (!enabled) return;
        write(category, details);
    }

    private static synchronized void write(String category, String details) {
        if (!enabled || current == null) return;
        // Callers supply only structured fields. Never use request URLs, exception messages or file paths.
        byte[] line = (Instant.now() + " [" + category + "] " + details + "\n").getBytes(StandardCharsets.UTF_8);
        if (current.length() + line.length > SEGMENT_LIMIT) {
            if ((previous.exists() && !previous.delete()) || !current.renameTo(previous)) return;
        }
        try (FileOutputStream file = new FileOutputStream(current, true)) {
            file.write(line);
        } catch (IOException ignored) {
            // A logging failure must not interrupt the operation being diagnosed.
        }
    }

    static void http(String host, int status, long started) {
        if (!enabled) return;
        event("http", "host=" + host + " status=" + status + " duration_ms=" + elapsed(started));
    }

    static void failure(String category, Throwable problem) {
        if (!enabled) return;
        StringBuilder trace = new StringBuilder(problem.getClass().getSimpleName());
        int frames = 0;
        for (StackTraceElement frame : problem.getStackTrace()) {
            if (!frame.getClassName().startsWith("org.kodiscraper.app.")) continue;
            trace.append(" at ").append(frame.getClassName()).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (++frames == 5) break;
        }
        if (problem.getCause() != null) trace.append(" cause=").append(problem.getCause().getClass().getSimpleName());
        event(category, trace.toString());
    }

    static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    static synchronized byte[] snapshot() throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        if (previous != null && previous.isFile()) result.write(Files.readAllBytes(previous.toPath()));
        if (current != null && current.isFile()) result.write(Files.readAllBytes(current.toPath()));
        return result.toByteArray();
    }
}
