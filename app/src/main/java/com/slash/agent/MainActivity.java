package com.slash.agent;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int YELLOW = Color.rgb(255, 193, 7);
    private static final int WHITE = Color.WHITE;
    private static final int BLACK = Color.rgb(16, 16, 16);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);
        setContentView(buildHome());
    }

    private View buildHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 0, 0, 0);
        root.setBackgroundColor(BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Slash", 24, WHITE, Typeface.SERIF);
        header.addView(title, new LinearLayout.LayoutParams(0, 81, 1));
        TextView settings = text("", 1, Color.LTGRAY, Typeface.DEFAULT);
        settings.setBackgroundColor(Color.LTGRAY);
        LinearLayout.LayoutParams gear = new LinearLayout.LayoutParams(21, 21);
        gear.rightMargin = 30;
        header.addView(settings, gear);
        root.addView(header);

        LinearLayout tip = new LinearLayout(this);
        tip.setOrientation(LinearLayout.VERTICAL);
        tip.setPadding(10, 10, 10, 10);
        tip.setBackgroundColor(YELLOW);
        TextView tipDesc = text("You can use “Hey Google, slash this for me” to\ntrigger an agent", 15, Color.BLACK, Typeface.SERIF);
        tip.addView(tipDesc, new LinearLayout.LayoutParams(-1, 0, 1));
        tip.addView(text("Tips", 12, Color.BLACK, Typeface.DEFAULT_BOLD));
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(315, 93);
        tipLp.bottomMargin = 57;
        root.addView(tip, tipLp);

        root.addView(text("Recent Activity", 12, WHITE, Typeface.DEFAULT_BOLD), new LinearLayout.LayoutParams(315, 24));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) cards.addView(card());
        scroll.addView(cards);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 93));
        return root;
    }

    private View card() {
        TextView card = text("Activity Title Here", 12, Color.BLACK, Typeface.DEFAULT_BOLD);
        card.setGravity(Gravity.BOTTOM | Gravity.LEFT);
        card.setPadding(10, 10, 10, 10);
        card.setBackgroundColor(YELLOW);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(130, 93);
        p.rightMargin = 12;
        card.setLayoutParams(p);
        return card;
    }

    private TextView text(String value, float size, int color, Typeface face) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(face);
        return v;
    }
}
