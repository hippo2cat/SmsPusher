package com.hippo2cat.smspusher.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ConsoleTheme {
    public static final int BACKGROUND = Color.rgb(242, 242, 247);
    public static final int SURFACE = Color.rgb(255, 255, 255);
    public static final int SURFACE_ALT = Color.rgb(229, 229, 234);
    public static final int SURFACE_DEEP = Color.rgb(242, 242, 247);
    public static final int STROKE = Color.rgb(198, 198, 200);
    public static final int STROKE_TEAL = Color.rgb(0, 122, 255);
    public static final int TEXT_PRIMARY = Color.rgb(28, 28, 30);
    public static final int TEXT_SECONDARY = Color.rgb(99, 99, 102);
    public static final int TEXT_MUTED = Color.rgb(142, 142, 147);
    public static final int ACCENT_TEAL = Color.rgb(0, 122, 255);
    public static final int ACCENT_TEAL_DARK = Color.rgb(0, 94, 184);
    public static final int ACCENT_LIME = Color.rgb(52, 199, 89);
    public static final int ACCENT_AMBER = Color.rgb(255, 149, 0);
    public static final int ACCENT_RED = Color.rgb(255, 59, 48);

    private ConsoleTheme() {}

    public static int dp(View view, int value) {
        return (int) (value * view.getResources().getDisplayMetrics().density);
    }

    public static TextView label(View parent, String text, int sp, int color, int style) {
        TextView view = new TextView(parent.getContext());
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(parent, 2), 1.0f);
        return view;
    }

    public static LinearLayout panel(View parent) {
        return roundedPanel(parent, SURFACE, STROKE);
    }

    public static LinearLayout roundedPanel(View parent, int fillColor, int strokeColor) {
        LinearLayout panel = new LinearLayout(parent.getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        int softenedStroke = strokeColor == STROKE ? colorWithAlpha(strokeColor, 82) : strokeColor;
        panel.setBackground(rounded(parent, fillColor, 12, softenedStroke, 1));
        panel.setElevation(dp(parent, 1));
        panel.setPadding(dp(parent, 16), dp(parent, 14), dp(parent, 16), dp(parent, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(parent, 10), 0, 0);
        panel.setLayoutParams(params);
        return panel;
    }

    public static TextView chip(View parent, String text, int color) {
        TextView chip = label(parent, text, 12, color, Typeface.BOLD);
        chip.setBackground(rounded(parent, colorWithAlpha(SURFACE_ALT, 230), 8, colorWithAlpha(color, 70), 1));
        chip.setPadding(dp(parent, 8), dp(parent, 6), dp(parent, 8), dp(parent, 6));
        return chip;
    }

    public static Button action(View parent, String text) {
        return roundedButton(parent, text, false);
    }

    public static Button roundedButton(View parent, String text, boolean primary) {
        Button button = new Button(parent.getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.WHITE : TEXT_PRIMARY);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int fill = primary ? ACCENT_TEAL_DARK : SURFACE_ALT;
        int stroke = primary ? ACCENT_TEAL : STROKE;
        button.setBackground(rounded(parent, fill, 12, stroke, 1));
        button.setMinHeight(dp(parent, 56));
        button.setPadding(dp(parent, 10), dp(parent, 8), dp(parent, 10), dp(parent, 8));
        return button;
    }

    public static View statusDot(View parent, int color) {
        View dot = new View(parent.getContext());
        dot.setBackground(rounded(parent, color, 99, colorWithAlpha(color, 90), 1));
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(parent, 12), dp(parent, 12)));
        return dot;
    }

    public static View divider(View parent) {
        View divider = new View(parent.getContext());
        divider.setBackgroundColor(colorWithAlpha(STROKE, 130));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Math.max(1, dp(parent, 1))
        ));
        return divider;
    }

    public static GradientDrawable rounded(View parent, int color, int radiusDp, int strokeColor, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(parent, radiusDp));
        if (strokeWidthDp > 0) drawable.setStroke(dp(parent, strokeWidthDp), strokeColor);
        return drawable;
    }

    public static GradientDrawable gradient(View parent, int startColor, int endColor, int radiusDp, int strokeColor, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[] { startColor, startColor, endColor }
        );
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(parent, radiusDp));
        if (strokeWidthDp > 0) drawable.setStroke(dp(parent, strokeWidthDp), strokeColor);
        return drawable;
    }

    public static int colorWithAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
