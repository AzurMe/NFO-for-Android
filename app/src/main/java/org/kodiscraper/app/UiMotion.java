package org.kodiscraper.app;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

final class UiMotion {
    static final Interpolator EASE_OUT = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final Interpolator EASE_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);

    private UiMotion() {}

    static boolean enabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    static void reveal(View view) {
        reset(view);
        if (!enabled()) return;
        view.setAlpha(0f);
        view.setTranslationY(10f * view.getResources().getDisplayMetrics().density);
        view.animate().alpha(1f).translationY(0f).setInterpolator(EASE_OUT).setDuration(220).start();
    }

    static void reset(View view) {
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationY(0f);
    }

}
