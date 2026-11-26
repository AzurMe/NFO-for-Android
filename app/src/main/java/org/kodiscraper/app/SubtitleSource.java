package org.kodiscraper.app;

import java.io.IOException;
import java.util.List;

interface SubtitleSource {
    int MAX_TRACKS = 3;

    List<SubtitleFiles.Track> find(SubtitleQuery query) throws IOException;
}
