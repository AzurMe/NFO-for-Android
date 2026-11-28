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
    private static final String CURRENT_ONLY = "current_only";
    private static final int LIMIT = 200;

    enum Tone { NEUTRAL, SUCCESS, FAILURE }

    record Entry(String message, String moviePath, String movieTitle, Tone tone) {
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
        synchronized (preferences) {
            if (!preferences.contains(CURRENT_ONLY)) save(currentTask());
        }
    }

    List<Entry> currentTask() {
        synchronized (preferences) {
            List<Entry> entries = new ArrayList<>();
            try {
                JSONArray saved = new JSONArray(preferences.getString(KEY, "[]"));
                int count = Math.min(LIMIT, Math.min(saved.length(), preferences.getInt(CURRENT_COUNT, 0)));
                for (int index = 0; index < count; index++) {
                    Object value = saved.opt(index);
                    if (value instanceof String text && !text.isEmpty()) {
                        entries.add(new Entry(text, "", "", Tone.NEUTRAL));
                    } else if (value instanceof JSONObject object) {
                        String message = object.optString("message",
                                object.optString("concise", object.optString("detail", "")));
                        if (!message.isEmpty()) {
                            entries.add(new Entry(message, object.optString("moviePath", ""),
                                    object.optString("movieTitle", ""), tone(object.optString("tone"))));
                        }
                    }
                }
            } catch (JSONException | ClassCastException invalidRecords) {
                // An unreadable record should not prevent a new run.
            }
            return entries;
        }
    }

    List<Entry> append(String message, boolean startsTask, Tone tone) {
        return append(new Entry(message, "", "", tone), startsTask);
    }

    List<Entry> appendMovie(String moviePath, String movieTitle, String message, Tone tone) {
        return append(new Entry(message, moviePath, movieTitle, tone), false);
    }

    private List<Entry> append(Entry entry, boolean startsTask) {
        synchronized (preferences) {
            List<Entry> entries = startsTask ? new ArrayList<>() : currentTask();
            if (entries.size() == LIMIT) entries.remove(LIMIT - 1);
            entries.add(0, entry);
            save(entries);
            return entries;
        }
    }

    private void save(List<Entry> entries) {
        JSONArray saved = new JSONArray();
        for (Entry entry : entries) saved.put(encode(entry));
        // Finish the write before showing the entry so it survives immediate process termination.
        preferences.edit().putString(KEY, saved.toString()).putInt(CURRENT_COUNT, entries.size())
                .putBoolean(CURRENT_ONLY, true).commit();
    }

    private static JSONObject encode(Entry entry) {
        try {
            JSONObject value = new JSONObject().put("message", entry.message()).put("tone", entry.tone().name());
            if (entry.isMovie()) value.put("moviePath", entry.moviePath()).put("movieTitle", entry.movieTitle());
            return value;
        } catch (JSONException invalid) {
            throw new IllegalStateException("无法创建刮削记录", invalid);
        }
    }

    private static Tone tone(String value) {
        try {
            return Tone.valueOf(value);
        } catch (IllegalArgumentException invalidTone) {
            return Tone.NEUTRAL;
        }
    }
}
