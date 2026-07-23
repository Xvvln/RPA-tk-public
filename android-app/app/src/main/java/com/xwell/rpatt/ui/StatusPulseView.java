package com.xwell.rpatt.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/** Stable status core with a restrained expanding halo when the device is ready. */
public final class StatusPulseView extends View {
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator animator;
    private float phase;
    private boolean ready;

    public StatusPulseView(Context context) {
        super(context);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1700L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            invalidate();
        });
    }

    public void setReady(boolean ready) {
        this.ready = ready;
        core.setColor(ready ? RpaPalette.GREEN : RpaPalette.RED);
        halo.setColor(ready ? RpaPalette.GREEN : RpaPalette.RED);
        if (ready && !animator.isStarted()) animator.start();
        if (!ready) stop();
        invalidate();
    }

    public void stop() {
        animator.cancel();
        phase = 0f;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float density = getResources().getDisplayMetrics().density;
        if (ready) {
            halo.setAlpha(Math.max(0, Math.round(72 * (1f - phase))));
            canvas.drawCircle(cx, cy, density * (4.5f + 4.5f * phase), halo);
        }
        core.setAlpha(255);
        canvas.drawCircle(cx, cy, density * 4.2f, core);
    }
}
