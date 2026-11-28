package org.kodiscraper.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class ScrapeHistory {
    private static final String KEY = "entries";
    private static final String CURRENT_COUNT = "current_count";
    private static final int LIMIT = 200;

    record Entry(String detail, String concise, String moviePath, String movieTitle, boolean startsTask) {
        boolean isMovie() {
            return !moviePath.isEmpty();
        }
    }

    private final SharedPreferences preferences;

    ScrapeHistory(Context context) {
        this(context, "scraper_history");
    }

    ScrapeHistory(Context context, String namespace) {
        preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE);
    }

    List<Entry> newestFirst() {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(preferences.getString(KEY, "[]"));
            for (int index = 0; index < Math.min(saved.length(), LIMIT); index++) {
                Object value = saved.opt(index);
                if (value instanceof String text) {
                    entries.add(new Entry(text, text, "", "", false));
                } else if (value instanceof JSONObject object) {
                    String detail = object.optString("detail", "");
                    entries.add(new Entry(detail, object.optString("concise", detail),
                            object.optString("moviePath", ""), object.optString("movieTitle", ""),
                            object.optBoolean("startsTask")));
                }
            }
        } catch (JSONException | ClassCastException invalidHistory) {
            // An unreadable history should not prevent a new run.
        }
        return entries;
    }

    List<Entry> currentTask() {
        synchronized (preferences) {
            List<Entry> entries = newestFirst();
            return entries.subList(0, currentCount(entries.size()));
        }
    }

    List<Entry> append(String message, boolean startsTask) {
        return append(new Entry(message, message, "", "", startsTask));
    }

    List<Entry> appendMovie(String moviePath, String movieTitle, String concise, String detail) {
        return append(new Entry(detail, concise, moviePath, movieTitle, false));
    }

    private List<Entry> append(Entry entry) {
        synchronized (preferences) {
            List<Entry> entries = newestFirst();
            int count = entry.startsTask() ? 1 : Math.min(LIMIT, currentCount(entries.size()) + 1);
            if (entries.size() == LIMIT) {
                entries.remove(LIMIT - 1);
            }
            entries.add(0, entry);
            JSONArray saved = new JSONArray();
            for (Entry item : entries) saved.put(encode(item));
            // Finish the write before showing the entry so it survives immediate process termination.
            preferences.edit().putString(KEY, saved.toString()).putInt(CURRENT_COUNT, count).commit();
            return entries.subList(0, count);
        }
    }

    private static Object encode(Entry entry) {
        if (!entry.isMovie() && !entry.startsTask()) return entry.detail();
        try {
            JSONObject value = new JSONObject().put("detail", entry.detail());
            if (entry.isMovie()) {
                value.put("concise", entry.concise()).put("moviePath", entry.moviePath())
                        .put("movieTitle", entry.movieTitle());
            }
            if (entry.startsTask()) value.put("startsTask", true);
            return value;
        } catch (JSONException invalid) {
            throw new IllegalStateException("无法创建刮削记录", invalid);
        }
    }

    private int currentCount(int size) {
        try {
            return Math.max(0, Math.min(size, preferences.getInt(CURRENT_COUNT, 0)));
        } catch (ClassCastException invalidCount) {
            return 0;
        }
    }
}
