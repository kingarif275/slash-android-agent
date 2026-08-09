package com.slash.agent;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public final class EdgeGlowActivity extends Activity {
    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { finish(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setDimAmount(0f);
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.setDecorFitsSystemWindows(false);
        registerReceiver(stopReceiver, new IntentFilter(SlashListeningService.HIDE_GLOW), Context.RECEIVER_NOT_EXPORTED);
        setContentView(new GlowView(this));
    }

    @Override protected void onDestroy() {
        unregisterReceiver(stopReceiver);
        super.onDestroy();
    }

    private static final class GlowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlowView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float edge = Math.max(28f, getResources().getDisplayMetrics().density * 22f);
            float width = getWidth();
            float height = getHeight();

            paint.setShader(new LinearGradient(0, 0, 0, edge,
                    Color.argb(150, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, edge, paint);
            paint.setShader(new LinearGradient(0, height, 0, height - edge,
                    Color.argb(130, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(0, height - edge, width, height, paint);
            paint.setShader(new LinearGradient(0, 0, edge, 0,
                    Color.argb(120, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, edge, height, paint);
            paint.setShader(new LinearGradient(width, 0, width - edge, 0,
                    Color.argb(120, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(width - edge, 0, width, height, paint);
            paint.setShader(null);
        }
    }
}
