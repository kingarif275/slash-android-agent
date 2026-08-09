package com.slash.agent;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int YELLOW = Color.rgb(255, 193, 7);
    private static final int WHITE = Color.WHITE;
    private static final int BLACK = Color.rgb(16, 16, 16);
    private Typeface ntype82;
    private Typeface inter;
    private Typeface interSemiBold;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(BLACK);
        window.setNavigationBarColor(BLACK);
        window.setDecorFitsSystemWindows(false);
        ntype82 = Typeface.createFromAsset(getAssets(), "fonts/NType82-Regular.otf");
        inter = Typeface.createFromAsset(getAssets(), "fonts/Inter-Regular.otf");
        interSemiBold = Typeface.createFromAsset(getAssets(), "fonts/Inter-SemiBold.otf");
        setContentView(buildHome());
    }

    private View buildHome() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BLACK);
        FrameLayout design = new FrameLayout(this);
        design.setBackgroundColor(BLACK);
        root.addView(design, new FrameLayout.LayoutParams(dp(375), dp(812)));

        // Coordinates intentionally mirror the 375x812 Figma frame.
        add(design, text("Slash", 24, WHITE, ntype82), 30, 60, 167, 32);

        View settings = new View(this);
        settings.setBackgroundColor(Color.LTGRAY);
        add(design, settings, 324, 60, 21, 21);

        View tipBackground = rounded(YELLOW, 10);
        add(design, tipBackground, 30, 137, 315, 93);

        TextView tipDescription = text("You can use “Hey Google, slash this for me” to trigger an agent", 14, Color.BLACK, ntype82);
        tipDescription.setGravity(Gravity.LEFT | Gravity.TOP);
        add(design, tipDescription, 40, 147, 295, 51);

        TextView tips = text("Tips", 10, Color.BLACK, interSemiBold);
        add(design, tips, 40, 208, 295, 12);

        tipDescription.bringToFront();
        tips.bringToFront();

        add(design, text("Recent Activity", 10, WHITE, interSemiBold), 30, 290, 315, 12);

        HorizontalScrollView activityViewport = new HorizontalScrollView(this);
        activityViewport.setHorizontalScrollBarEnabled(false);
        activityViewport.setClipChildren(true);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setClipChildren(false);
        for (int i = 0; i < 3; i++) {
            TextView card = text("Activity Title Here", 10, Color.BLACK, interSemiBold);
            card.setGravity(Gravity.LEFT | Gravity.BOTTOM);
            card.setPadding(10, 10, 10, 10);
            card.setBackground(roundedDrawable(YELLOW, 10));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(130), dp(93));
            cardParams.rightMargin = dp(12);
            cards.addView(card, cardParams);
        }
        activityViewport.addView(cards, new FrameLayout.LayoutParams(dp(414), dp(93)));
        add(design, activityViewport, 30, 314, 345, 93);

        root.post(() -> {
            Insets systemInsets = Insets.NONE;
            WindowInsets windowInsets = root.getRootWindowInsets();
            if (windowInsets != null) {
                systemInsets = windowInsets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
            int usableWidth = root.getWidth() - systemInsets.left - systemInsets.right;
            int usableHeight = root.getHeight() - systemInsets.top - systemInsets.bottom;
            float widthScale = usableWidth / (float) design.getWidth();
            float heightScale = usableHeight / (float) design.getHeight();
            float scale = Math.min(widthScale, heightScale);
            design.setPivotX(0);
            design.setPivotY(0);
            design.setScaleX(scale);
            design.setScaleY(scale);
            design.setTranslationX(systemInsets.left + (usableWidth - design.getWidth() * scale) / 2f);
            design.setTranslationY(systemInsets.top);
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
