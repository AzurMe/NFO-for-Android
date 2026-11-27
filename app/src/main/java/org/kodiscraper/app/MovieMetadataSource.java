package org.kodiscraper.app;

import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

interface MovieMetadataSource {
    enum Provider {
        TMDB("tmdb", "TMDB"), TVDB("tvdb", "TheTVDB");

        final String idType;
        final String label;

        Provider(String idType, String label) {
            this.idType = idType;
            this.label = label;
        }
    }

    default Provider provider() { return Provider.TMDB; }

    void validate() throws IOException;
    void cancel();
    int findMovieByImdb(String imdbId) throws IOException;
    List<TmdbClient.Candidate> search(String title, int year) throws IOException;
    JSONObject details(int id) throws IOException;
    String artwork(JSONObject movie, String type);
    List<String> alternateFanart(JSONObject movie, int limit);
    String collectionArtwork(JSONObject movie, String type);
    String castProfile(JSONObject person);
    byte[] image(String path, String size) throws IOException;

    default int findMovieByTmdb(int id) throws IOException { return id; }

    class HttpError extends IOException {
        final int statusCode;

        HttpError(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
