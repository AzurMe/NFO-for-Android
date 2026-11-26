package org.kodiscraper.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

final class SubtitleScraper {
    private static final Pattern WEB_ADDRESS = Pattern.compile("(?i)https?://[^\\s\\u3000<>\"'，；）]+");
    private final List<Provider> providers = new ArrayList<>();
    private final List<String> credentials = new ArrayList<>(2);

    SubtitleScraper(String assrtToken, String subdlKey) {
        providers.add(new Provider("射手影音", new ShooterSubtitleSource()));
        if (assrtToken != null && !assrtToken.trim().isEmpty()) {
            credentials.add(assrtToken.trim());
            providers.add(new Provider("伪射手", new AssrtSubtitleSource(assrtToken)));
        }
        if (subdlKey != null && !subdlKey.trim().isEmpty()) {
            credentials.add(subdlKey.trim());
            providers.add(new Provider("SubDL", new SubdlSubtitleSource(subdlKey)));
        }
    }

    Outcome download(SubtitleQuery query) throws IOException {
        List<ProviderFailure> problems = new ArrayList<>();
        for (Provider provider : providers) {
            ScrapeEngine.checkCancelled();
            List<SubtitleFiles.Track> tracks;
            try {
                tracks = provider.source.find(query);
            } catch (IOException problem) {
                ScrapeEngine.checkCancelled();
                problems.add(new ProviderFailure(provider.name, safeMessage(problem, "字幕查询失败")));
                continue;
            }
            if (!tracks.isEmpty()) {
                try {
                    SubtitleFiles.Result saved = SubtitleFiles.save(query.storage, query.movie, tracks);
                    return new Outcome(provider.name, saved.writtenFiles, saved.duplicates, problems,
                            writeFailure(saved.failure));
                } catch (IOException failure) {
                    ScrapeEngine.checkCancelled();
                    return new Outcome(provider.name, Collections.emptyList(), 0, problems, writeFailure(failure));
                }
            }
        }
        if (!problems.isEmpty()) {
            List<String> messages = new ArrayList<>(problems.size());
            for (ProviderFailure problem : problems) {
                messages.add(problem.source + " · " + problem.message);
            }
            throw new IOException(String.join("；", messages));
        }
        return new Outcome("", Collections.emptyList(), 0, problems, null);
    }

    private IOException writeFailure(IOException failure) {
        return failure == null ? null : new IOException(safeMessage(failure, "无法写入字幕文件"));
    }

    private String safeMessage(IOException failure, String fallback) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return fallback;
        }
        message = WEB_ADDRESS.matcher(message).replaceAll("请求地址");
        for (String credential : credentials) {
            message = message.replace(credential, "[已隐藏]")
                    .replace(SubtitleHttp.encode(credential), "[已隐藏]");
        }
        return message;
    }

    private static final class Provider {
        final String name;
        final SubtitleSource source;

        Provider(String name, SubtitleSource source) {
            this.name = name;
            this.source = source;
        }
    }

    record ProviderFailure(String source, String message) {}

    static final class Outcome {
        final String source;
        final int written;
        final int duplicates;
        final List<String> writtenFiles;
        final List<ProviderFailure> providerFailures;
        final IOException writeFailure;

        Outcome(String source, List<String> writtenFiles, int duplicates, List<ProviderFailure> providerFailures,
                IOException writeFailure) {
            this.source = source;
            this.written = writtenFiles.size();
            this.duplicates = duplicates;
            this.writtenFiles = writtenFiles;
            this.providerFailures = Collections.unmodifiableList(new ArrayList<>(providerFailures));
            this.writeFailure = writeFailure;
        }

        boolean found() {
            return written > 0 || duplicates > 0;
        }
    }
}
