package org.kodiscraper.app;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ScrapeLogText {
    private ScrapeLogText() {}

    static CharSequence render(List<ScrapeHistory.Entry> newestFirst, int indent, boolean dark) {
        List<Block> display = new ArrayList<>();
        Map<String, Block> movies = new HashMap<>();
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            ScrapeHistory.Entry entry = newestFirst.get(index);
            if (entry.message().isEmpty()) continue;
            if (!entry.isMovie()) {
                display.add(new Block("", Collections.singletonList(entry)));
                continue;
            }
            Block movie = movies.get(entry.moviePath());
            if (movie == null) {
                String title = entry.movieTitle().isEmpty() ? entry.moviePath() : entry.movieTitle();
                movie = new Block(title, new ArrayList<>());
                movies.put(entry.moviePath(), movie);
                display.add(movie);
            }
            movie.rows().add(entry);
        }

        SpannableStringBuilder text = new SpannableStringBuilder();
        List<Range> indented = new ArrayList<>();
        for (Block block : display) {
            boolean movie = !block.heading().isEmpty();
            if (movie) {
                Range heading = append(text, block.heading());
                text.setSpan(new StyleSpan(Typeface.BOLD), heading.start(), heading.end(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            for (ScrapeHistory.Entry entry : block.rows()) {
                Range row = append(text, entry.message());
                if (movie) indented.add(row);
                if (entry.tone() != ScrapeHistory.Tone.NEUTRAL) {
                    text.setSpan(new ForegroundColorSpan(color(entry.tone(), dark, 0)), row.start(), row.end(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        for (Range row : indented) {
            int end = Math.min(text.length(), row.end() + 1);
            text.setSpan(new LeadingMarginSpan.Standard(indent, indent), row.start(), end,
                    Spanned.SPAN_PARAGRAPH);
        }
        return text;
    }

    static int color(ScrapeHistory.Tone tone, boolean dark, int neutral) {
        return switch (tone) {
            case SUCCESS -> dark ? 0xff79dda6 : 0xff176841;
            case FAILURE -> dark ? 0xffffa6a0 : 0xffb3262d;
            case NEUTRAL -> neutral;
        };
    }

    private static Range append(SpannableStringBuilder text, String value) {
        if (text.length() > 0) text.append('\n');
        int start = text.length();
        text.append(value);
        return new Range(start, text.length());
    }

    private record Block(String heading, List<ScrapeHistory.Entry> rows) {}
    private record Range(int start, int end) {}
}
