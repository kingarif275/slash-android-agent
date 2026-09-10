package com.slash.agent;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;

/** Slash-owned, non-interactive activity border shown while an agent run is active. */
public final class AgentBorderOverlay {
    private final Context context;
    private final WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BorderView view;

    public AgentBorderOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isAvailable() {
        return Settings.canDrawOverlays(context);
    }

    public void show() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::show);
            return;
        }
        showOnMain();
    }

    private void showOnMain() {
        if (!isAvailable() || view != null) return;
        view = new BorderView(context);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.setTitle("Slash agent activity");
        try { windowManager.addView(view, params); }
        catch (RuntimeException ignored) { view = null; }
    }

    public void hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::hide);
            return;
        }
        hideOnMain();
    }

    private void hideOnMain() {
        if (view == null) return;
        BorderView current = view;
        view = null;
        current.stop();
        try { windowManager.removeView(current); } catch (RuntimeException ignored) { }
    }

    private static final class BorderView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final Path borderPath = new Path();
        private final Path highlightPath = new Path();
        private final PathMeasure pathMeasure = new PathMeasure();
        private final ValueAnimator animator;
        private float distance;

        BorderView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(5));
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(2400L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> { distance = (Float) a.getAnimatedValue(); invalidate(); });
            animator.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Keep the stroke inside the physical display bounds so all four edges remain visible.
            float inset = dp(1);
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            int gold = Color.rgb(255, 193, 0);
            borderPath.reset();
            borderPath.addRect(bounds, android.graphics.Path.Direction.CW);
            pathMeasure.setPath(borderPath, true);
            float length = pathMeasure.getLength();

            // Fixed, dim frame keeps all four edges visible at all times.
            paint.setShader(null);
            paint.setColor(gold);
            paint.setAlpha(90);
            canvas.drawPath(borderPath, paint);

            // AGI-like motion: only the luminous highlight travels around the fixed perimeter.
            float head = distance * length;
            float tail = Math.max(0f, head - length * .18f);
            highlightPath.reset();
            pathMeasure.getSegment(tail, head, highlightPath, true);
            paint.setAlpha(255);
            canvas.drawPath(highlightPath, paint);
        }

        void stop() { animator.cancel(); }
        private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    }
}
