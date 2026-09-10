package com.slash.agent;

import android.content.Context;
import android.graphics.Rect;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.util.Log;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;

/** Visible Agent Mode pointer. It is click-through and only appears around grounded actions. */
public final class AgentPointerOverlay {
    private final Context context;
    private final WindowManager windowManager;
    private final ImageView pointer;
    private final WindowManager.LayoutParams params;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean attached;
    private ValueAnimator movement;

    public AgentPointerOverlay(Context context) {
        this.context = context.getApplicationContext();
        windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        pointer = new ImageView(this.context);
        pointer.setImageResource(R.drawable.mouse_pointer);
        pointer.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        pointer.setAlpha(0f);
        int size = (int) (96 * context.getResources().getDisplayMetrics().density);
        params = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
    }

    public boolean available() { return Settings.canDrawOverlays(context); }

    public void moveTo(Rect bounds, boolean click) {
        if (!available() || bounds == null || bounds.isEmpty()) return;
        Rect target = new Rect(bounds);
        main.post(() -> moveToOnMain(target, click));
    }

    private void moveToOnMain(Rect bounds, boolean click) {
        int targetX = bounds.centerX() - params.width / 2;
        int targetY = bounds.centerY() - params.height / 2;
        if (!attached) {
            params.x = targetX;
            params.y = targetY;
            try { windowManager.addView(pointer, params); attached = true; }
            catch (RuntimeException error) { Log.e("SlashPointer", "POINTER_OVERLAY_ADD_FAILED", error); return; }
        }
        pointer.animate().cancel();
        if (movement != null) movement.cancel();
        pointer.setAlpha(1f);
        int startX = params.x;
        int startY = params.y;
        movement = ValueAnimator.ofFloat(0f, 1f);
        movement.setDuration(attached && (startX != targetX || startY != targetY) ? 420 : 1);
        movement.setInterpolator(new DecelerateInterpolator());
        movement.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            params.x = startX + Math.round((targetX - startX) * fraction);
            params.y = startY + Math.round((targetY - startY) * fraction);
            if (attached) try { windowManager.updateViewLayout(pointer, params); }
            catch (RuntimeException error) { Log.w("SlashPointer", "POINTER_MOVE_FAILED", error); }
        });
        movement.start();
        if (click) {
            pointer.postDelayed(() -> pointer.animate().scaleX(0.82f).scaleY(0.82f).setDuration(90)
                    .withEndAction(() -> pointer.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start(),
                    startX == targetX && startY == targetY ? 20 : 430);
        }
    }

    public void hide() {
        main.post(() -> pointer.animate().alpha(0f).setDuration(160).start());
    }

    public void close() {
        main.post(this::closeOnMain);
    }

    private void closeOnMain() {
        pointer.animate().cancel();
        if (movement != null) movement.cancel();
        if (attached) { try { windowManager.removeView(pointer); } catch (RuntimeException ignored) { } attached = false; }
    }
}
