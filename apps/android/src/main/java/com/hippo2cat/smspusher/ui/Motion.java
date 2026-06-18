package com.hippo2cat.smspusher.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

public final class Motion {
    public static final long SCREEN_ENTER_MS = 180L;
    public static final long STAGGER_STEP_MS = 45L;
    public static final long PRESS_MS = 90L;
    public static final long ERROR_SHAKE_MS = 260L;
    public static final long PULSE_MS = 1400L;
    public static final long BREATHE_MS = 420L;

    private static final DecelerateInterpolator STANDARD = new DecelerateInterpolator(1.7f);
    private static final OvershootInterpolator POP = new OvershootInterpolator(1.2f);

    private Motion() {}

    private static boolean animationsEnabled(View view) {
        if (view == null) return false;
        try {
            return Settings.Global.getFloat(
                view.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f;
        } catch (Exception ignored) {
            return true;
        }
    }

    public static void fadeInUp(View view) {
        fadeInUp(view, 0L);
    }

    public static void staggerFadeInUp(View view, int index) {
        fadeInUp(view, Math.min(180L, Math.max(0, index) * STAGGER_STEP_MS));
    }

    private static void fadeInUp(View view, long delayMs) {
        if (view == null) return;
        view.animate().cancel();
        if (!animationsEnabled(view)) {
            view.setAlpha(1f);
            view.setTranslationY(0f);
            return;
        }
        view.setAlpha(0f);
        view.setTranslationY(dp(view, 10));
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(SCREEN_ENTER_MS)
            .setInterpolator(STANDARD)
            .start();
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void applyPressScale(View view) {
        if (view == null) return;
        view.setOnTouchListener((pressedView, event) -> {
            if (!animationsEnabled(pressedView)) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                pressedView.animate()
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(PRESS_MS)
                    .setInterpolator(STANDARD)
                    .start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                pressedView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(PRESS_MS)
                    .setInterpolator(POP)
                    .start();
            }
            return false;
        });
    }

    public static void pulse(View view) {
        if (view == null || !animationsEnabled(view)) return;
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.06f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.06f, 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.86f, 1f);
        for (ObjectAnimator animator : new ObjectAnimator[] { scaleX, scaleY, alpha }) {
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);
            animator.setDuration(PULSE_MS);
            animator.setInterpolator(STANDARD);
        }
        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY, alpha);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View attachedView) {}

            @Override
            public void onViewDetachedFromWindow(View detachedView) {
                set.cancel();
                detachedView.removeOnAttachStateChangeListener(this);
            }
        });
        set.start();
    }

    public static void breathe(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setAlpha(1f);
        if (!animationsEnabled(view)) return;
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.08f, 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.78f, 1f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY, alpha);
        set.setDuration(BREATHE_MS);
        set.setInterpolator(STANDARD);
        set.start();
    }

    public static void shake(View view) {
        if (view == null) return;
        if (!animationsEnabled(view)) {
            view.setTranslationX(0f);
            return;
        }
        ObjectAnimator shake = ObjectAnimator.ofFloat(
            view,
            "translationX",
            0f,
            -dp(view, 8),
            dp(view, 8),
            -dp(view, 6),
            dp(view, 6),
            -dp(view, 3),
            dp(view, 3),
            0f
        );
        shake.setDuration(ERROR_SHAKE_MS);
        shake.setInterpolator(STANDARD);
        shake.start();
    }

    public static void focusPop(View view) {
        if (view == null || !animationsEnabled(view)) return;
        view.animate().cancel();
        view.animate()
            .scaleX(1.04f)
            .scaleY(1.04f)
            .setDuration(PRESS_MS)
            .setInterpolator(POP)
            .withEndAction(() -> view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(PRESS_MS)
                .setInterpolator(STANDARD)
                .start())
            .start();
    }

    private static int dp(View view, int value) {
        return (int) (value * view.getResources().getDisplayMetrics().density);
    }
}
