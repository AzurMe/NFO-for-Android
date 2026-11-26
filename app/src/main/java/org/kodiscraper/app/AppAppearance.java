package org.kodiscraper.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

final class AppAppearance {
    private static final String SETTINGS = "scraper";
    private static final String PREFERENCE = "appearance";

    enum Mode {
        SYSTEM("system", "跟随系统"),
        LIGHT("light", "浅色"),
        DARK("dark", "深色");

        final String label;
        private final String setting;

        Mode(String setting, String label) {
            this.setting = setting;
            this.label = label;
        }
    }

    final int background;
    final int surface;
    final int foreground;
    final int muted;
    final int border;
    final int tint;
    final int primary;
    final int onPrimary;
    final int accent;
    final int disabled;
    final int ripple;
    final int scrim;

    private AppAppearance(Context context) {
        background = context.getColor(R.color.appearance_background);
        surface = context.getColor(R.color.appearance_surface);
        foreground = context.getColor(R.color.appearance_foreground);
        muted = context.getColor(R.color.appearance_muted);
        border = context.getColor(R.color.appearance_border);
        tint = context.getColor(R.color.appearance_tint);
        primary = context.getColor(R.color.appearance_primary);
        onPrimary = context.getColor(R.color.appearance_on_primary);
        accent = context.getColor(R.color.appearance_accent);
        disabled = context.getColor(R.color.appearance_disabled);
        ripple = context.getColor(R.color.appearance_ripple);
        scrim = context.getColor(R.color.appearance_scrim);
    }

    static AppAppearance from(Context context) {
        return new AppAppearance(context);
    }

    static Context wrap(Context base) {
        Mode mode = read(base.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE));
        if (mode == Mode.SYSTEM) {
            return base;
        }

        // Override the night bits only so this context still receives rotation, font, and locale changes.
        Configuration override = new Configuration();
        override.uiMode = mode == Mode.DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        return base.createConfigurationContext(override);
    }

    static Mode read(SharedPreferences preferences) {
        try {
            String value = preferences.getString(PREFERENCE, Mode.SYSTEM.setting);
            for (Mode candidate : Mode.values()) {
                if (candidate.setting.equals(value)) {
                    return candidate;
                }
            }
        } catch (ClassCastException invalidPreference) {
            // Ignore an invalid value from a preferences migration.
        }
        return Mode.SYSTEM;
    }

    static void save(SharedPreferences preferences, Mode mode) {
        preferences.edit().putString(PREFERENCE, mode.setting).apply();
    }
}
