package org.kodiscraper.app;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

record ReleaseFilter(int height, VideoFormat format, long maxBytesExclusive, int minSeeders, String keyword) {
    static final ReleaseFilter ALL = new ReleaseFilter(-1, VideoFormat.ANY, 0, 0, "");
    static final ReleaseFilter UHD_HDR10_UNDER_40_GB =
            new ReleaseFilter(2160, VideoFormat.HDR10, 40_000_000_000L, 0, "");

    private static final Pattern DECIMAL = Pattern.compile("(?:[0-9]+(?:[.,][0-9]+)?|[.,][0-9]+)");

    enum VideoFormat {
        ANY("不限"), HDR10("HDR10"), HDR10_PLUS("HDR10+"), DOLBY_VISION("杜比视界");

        final String label;

        VideoFormat(String label) {
            this.label = label;
        }
    }

    ReleaseFilter {
        if (height < -1 || maxBytesExclusive < 0 || minSeeders < 0) {
            throw new IllegalArgumentException("筛选范围无效");
        }
        Objects.requireNonNull(format, "format");
        keyword = keyword == null ? "" : keyword.trim();
    }

    boolean active() {
        return !equals(ALL);
    }

    boolean matches(PirateBayReleaseSource.Candidate candidate) {
        if (height >= 0 && candidate.height != height) {
            return false;
        }
        if (maxBytesExclusive > 0 && (candidate.bytes <= 0 || candidate.bytes >= maxBytesExclusive)) {
            return false;
        }
        if (minSeeders > 0 && candidate.seeders < minSeeders) {
            return false;
        }
        boolean matchesFormat = switch (format) {
            case ANY -> true;
            case HDR10 -> candidate.hdr10;
            case HDR10_PLUS -> candidate.hdr10Plus;
            case DOLBY_VISION -> candidate.dolbyVision;
        };
        return matchesFormat && (keyword.isEmpty()
                || candidate.name.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)));
    }

    static long parseMaxGigabytes(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return 0;
        }
        if (!DECIMAL.matcher(text).matches()) {
            throw new NumberFormatException("容量上限须为正数");
        }
        try {
            long bytes = new BigDecimal(text.replace(',', '.')).movePointRight(9).longValueExact();
            if (bytes <= 0) {
                throw new NumberFormatException("容量上限须大于零");
            }
            return bytes;
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("容量上限超出支持范围", invalid);
        }
    }

    static int parseMinimumSeeders(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return 0;
        }
        int seeders = Integer.parseInt(text);
        if (seeders < 0) {
            throw new IllegalArgumentException("最少做种数不能为负数");
        }
        return seeders;
    }
}
