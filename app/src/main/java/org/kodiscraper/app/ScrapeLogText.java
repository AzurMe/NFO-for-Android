package org.kodiscraper.app;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ScrapeLogText {
    private ScrapeLogText() {}

    static CharSequence render(List<ScrapeHistory.Entry> newestFirst, boolean history, int indent) {
        List<Block> display = new ArrayList<>();
        if (history) {
            int start = 0;
            for (int index = 0; index < newestFirst.size(); index++) {
                if (newestFirst.get(index).startsTask()) {
                    addTask(display, newestFirst.subList(start, index + 1), true, true);
                    start = index + 1;
                }
            }
            // Old string-only history predates explicit task boundaries. Preserve its display order.
            addTask(display, newestFirst.subList(start, newestFirst.size()), true, false);
        } else {
            addTask(display, newestFirst, false, true);
        }

        SpannableStringBuilder text = new SpannableStringBuilder();
        List<Range> indented = new ArrayList<>();
        List<Range> headings = new ArrayList<>();
        for (Block block : display) {
            boolean movie = !block.heading().isEmpty();
            if (movie) headings.add(append(text, block.heading()));
            for (String row : block.rows()) {
                Range range = append(text, row);
                if (movie) indented.add(range);
            }
        }
        for (Range heading : headings) {
            text.setSpan(new StyleSpan(Typeface.BOLD), heading.start(), heading.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (Range row : indented) {
            int end = Math.min(text.length(), row.end() + 1);
            text.setSpan(new LeadingMarginSpan.Standard(indent, indent), row.start(), end,
                    Spanned.SPAN_PARAGRAPH);
        }
        return text;
    }

    private static void addTask(List<Block> display, List<ScrapeHistory.Entry> entries, boolean history,
                                boolean chronological) {
        Map<String, Block> movies = new HashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            ScrapeHistory.Entry entry = entries.get(chronological ? entries.size() - 1 - index : index);
            String message = history ? entry.detail() : entry.concise();
            if (message.isEmpty()) continue;
            if (!entry.isMovie()) {
                display.add(new Block("", Collections.singletonList(message)));
                continue;
            }

            Block movie = movies.get(entry.moviePath());
            if (movie == null) {
                String title = history || entry.movieTitle().isEmpty() ? entry.moviePath() : entry.movieTitle();
                movie = new Block(title, new ArrayList<>());
                movies.put(entry.moviePath(), movie);
                display.add(movie);
            }
            movie.rows().add(message);
        }
    }

    private static Range append(SpannableStringBuilder text, String value) {
        if (text.length() > 0) text.append('\n');
        int start = text.length();
        text.append(value);
        return new Range(start, text.length());
    }

    private record Block(String heading, List<String> rows) {}
    private record Range(int start, int end) {}
}
