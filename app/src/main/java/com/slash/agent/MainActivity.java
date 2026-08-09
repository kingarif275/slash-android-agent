package com.slash.agent;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int YELLOW = Color.rgb(255, 193, 7);
    private static final int WHITE = Color.WHITE;
    private static final int BLACK = Color.rgb(16, 16, 16);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(BLACK);
        window.setNavigationBarColor(BLACK);
        window.setDecorFitsSystemWindows(false);
        setContentView(buildHome());
    }

    private View buildHome() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BLACK);
        FrameLayout design = new FrameLayout(this);
        design.setBackgroundColor(BLACK);
        root.addView(design, new FrameLayout.LayoutParams(dp(375), dp(812)));

        // Coordinates intentionally mirror the 375x812 Figma frame.
        add(design, text("Slash", 24, WHITE, Typeface.SERIF), 30, 60, 167, 21);

        View settings = new View(this);
        settings.setBackgroundColor(Color.LTGRAY);
        add(design, settings, 324, 60, 21, 21);

        View tipBackground = rounded(YELLOW, 10);
        add(design, tipBackground, 30, 137, 315, 93);

        TextView tipDescription = text("You can use “Hey Google, slash this for me” to\ntrigger an agent", 15, Color.BLACK, Typeface.SERIF);
        tipDescription.setGravity(Gravity.LEFT | Gravity.TOP);
        add(design, tipDescription, 40, 147, 295, 51);

        TextView tips = text("Tips", 12, Color.BLACK, Typeface.DEFAULT_BOLD);
        add(design, tips, 40, 208, 295, 12);

        tipDescription.bringToFront();
        tips.bringToFront();

        add(design, text("Recent Activity", 12, WHITE, Typeface.DEFAULT_BOLD), 30, 290, 315, 12);

        HorizontalScrollView activityViewport = new HorizontalScrollView(this);
        activityViewport.setHorizontalScrollBarEnabled(false);
        activityViewport.setClipChildren(true);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setClipChildren(false);
        for (int i = 0; i < 3; i++) {
            TextView card = text("Activity Title Here", 12, Color.BLACK, Typeface.DEFAULT_BOLD);
            card.setGravity(Gravity.LEFT | Gravity.BOTTOM);
            card.setPadding(10, 10, 10, 10);
            card.setBackground(roundedDrawable(YELLOW, 10));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(130), dp(93));
            cardParams.rightMargin = dp(12);
            cards.addView(card, cardParams);
        }
        activityViewport.addView(cards, new FrameLayout.LayoutParams(dp(414), dp(93)));
        add(design, activityViewport, 30, 314, 315, 93);

        root.post(() -> {
            float widthScale = root.getWidth() / (float) design.getWidth();
            float heightScale = root.getHeight() / (float) design.getHeight();
            float scale = Math.min(widthScale, heightScale);
            design.setScaleX(scale);
            design.setScaleY(scale);
            design.setTranslationX((root.getWidth() - design.getWidth() * scale) / 2f);
        });

        return root;
    }

    private void add(FrameLayout parent, View child, int x, int y, int width, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(width), dp(height));
        params.leftMargin = dp(x);
        params.topMargin = dp(y);
        parent.addView(child, params);
    }

    private TextView text(String value, float size, int color, Typeface face) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setIncludeFontPadding(false);
        return view;
    }

    private View rounded(int color, int radius) {
        View view = new View(this);
        view.setBackground(roundedDrawable(color, radius));
        return view;
    }

    private GradientDrawable roundedDrawable(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
