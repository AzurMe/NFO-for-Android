package org.kodiscraper.app;

import java.util.ArrayList;
import java.util.List;

final class ScrapeHistory {
    private static final int LIMIT = 200;

    enum Tone { NEUTRAL, SUCCESS, FAILURE }

    record Entry(String message, String moviePath, String movieTitle, Tone tone) {
        boolean isMovie() {
            return !moviePath.isEmpty();
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    List<Entry> currentTask() {
        return entries;
    }

    void clear() {
        entries.clear();
    }

    void append(String message, Tone tone) {
        append(new Entry(message, "", "", tone));
    }

    void appendMovie(String moviePath, String movieTitle, String message, Tone tone) {
        append(new Entry(message, moviePath, movieTitle, tone));
    }

    private void append(Entry entry) {
        if (entries.size() == LIMIT) {
            // Keep scan and result counts visible when a large library fills the log.
            int oldestMovie = entries.size() - 1;
            while (oldestMovie >= 0 && !entries.get(oldestMovie).isMovie()) oldestMovie--;
            entries.remove(oldestMovie >= 0 ? oldestMovie : entries.size() - 1);
        }
        entries.add(0, entry);
    }
}
