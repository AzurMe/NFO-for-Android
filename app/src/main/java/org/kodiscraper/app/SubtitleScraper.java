package org.kodiscraper.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class SubtitleScraper {
    private final List<Provider> providers = new ArrayList<>();

    SubtitleScraper(String assrtToken, String subdlKey) {
        providers.add(new Provider("射手影音", new ShooterSubtitleSource()));
        if (!assrtToken.isEmpty()) {
            providers.add(new Provider("伪射手", new AssrtSubtitleSource(assrtToken)));
        }
        if (!subdlKey.isEmpty()) {
            providers.add(new Provider("SubDL", new SubdlSubtitleSource(subdlKey)));
        }
    }

    Outcome download(SubtitleQuery query) throws IOException {
        List<String> problems = new ArrayList<>();
        for (Provider provider : providers) {
            ScrapeEngine.checkCancelled();
            List<SubtitleFiles.Track> tracks;
            try {
                tracks = provider.source.find(query);
            } catch (IOException problem) {
                ScrapeEngine.checkCancelled();
                problems.add(provider.name + " · " + problem.getMessage());
                continue;
            }
            if (!tracks.isEmpty()) {
                SubtitleFiles.Result saved = SubtitleFiles.save(query.storage, query.movie, tracks);
                return new Outcome(provider.name, saved.written, saved.duplicates);
            }
        }
        if (!problems.isEmpty()) {
            throw new IOException(String.join("；", problems));
        }
        return new Outcome("", 0, 0);
    }

    private static final class Provider {
        final String name;
        final SubtitleSource source;

        Provider(String name, SubtitleSource source) {
            this.name = name;
            this.source = source;
        }
    }

    static final class Outcome {
        final String source;
        final int written;
        final int duplicates;

        Outcome(String source, int written, int duplicates) {
            this.source = source;
            this.written = written;
            this.duplicates = duplicates;
        }

        boolean found() {
            return written > 0 || duplicates > 0;
        }
    }
}
