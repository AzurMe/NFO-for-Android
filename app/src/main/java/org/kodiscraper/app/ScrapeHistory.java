package org.kodiscraper.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

final class ScrapeHistory {
    private static final String KEY = "entries";
    private static final String CURRENT_COUNT = "current_count";
    private static final int LIMIT = 200;
    private static final int MAX_ENTRY_LENGTH = 2000;

    private final SharedPreferences preferences;

    ScrapeHistory(Context context) {
        this(context, "scraper_history");
    }

    ScrapeHistory(Context context, String namespace) {
        preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE);
    }

    List<String> newestFirst() {
        List<String> entries = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(preferences.getString(KEY, "[]"));
            for (int index = 0; index < Math.min(saved.length(), LIMIT); index++) {
                Object entry = saved.opt(index);
                if (entry instanceof String text) {
                    entries.add(limitLength(text));
                }
            }
        } catch (JSONException | ClassCastException invalidHistory) {
            // An unreadable history should not prevent a new run.
        }
        return entries;
    }

    List<String> currentTask() {
        synchronized (preferences) {
            List<String> entries = newestFirst();
            return entries.subList(0, currentCount(entries.size()));
        }
    }

    List<String> append(String message, boolean startsTask) {
        synchronized (preferences) {
            List<String> entries = newestFirst();
            int count = startsTask ? 1 : Math.min(LIMIT, currentCount(entries.size()) + 1);
            if (entries.size() == LIMIT) {
                entries.remove(LIMIT - 1);
            }
            entries.add(0, limitLength(message));
            // Finish the write before showing the entry so it survives immediate process termination.
            preferences.edit().putString(KEY, new JSONArray(entries).toString())
                    .putInt(CURRENT_COUNT, count).commit();
            return entries.subList(0, count);
        }
    }

    private int currentCount(int size) {
        try {
            return Math.max(0, Math.min(size, preferences.getInt(CURRENT_COUNT, 0)));
        } catch (ClassCastException invalidCount) {
            return 0;
        }
    }

    private static String limitLength(String message) {
        if (message.length() <= MAX_ENTRY_LENGTH) {
            return message;
        }
        int end = MAX_ENTRY_LENGTH - 1;
        if (Character.isHighSurrogate(message.charAt(end - 1))) {
            end--;
        }
        return message.substring(0, end) + "…";
    }
}
