package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/** Falling snow drawn between the Home backdrop and controls. */
final class SnowView extends View {
    private static final int FRAME_MS = 32;
    private static final float[] CRYSTAL = {
            0f, -1f, 0f, 1f,
            -.866f, -.5f, .866f, .5f,
            -.866f, .5f, .866f, -.5f
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Flake[] flakes = new Flake[48];
    private final float density;
    private long lastFrame;

    SnowView(Context context) {
        super(context);
        setVisibility(INVISIBLE);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        density = getResources().getDisplayMetrics().density;
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeCap(Paint.Cap.ROUND);
        Random random = new Random();
        for (int i = 0; i < flakes.length; i++) {
            flakes[i] = new Flake(random, (i % 5) == 0);
        }
    }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        lastFrame = SystemClock.uptimeMillis();
        if (visible) invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float seconds = Math.min(now - lastFrame, 100L) / 1000f;
        lastFrame = now;
        int width = getWidth();
        int height = getHeight();
        for (Flake flake : flakes) {
            float radius = flake.radius * density;
            float margin = 1.1f * radius;
            float travel = height + 2 * margin;
            flake.fall = advanceFall(flake.fall, flake.speed * density * seconds / travel);
            float y = flake.fall * travel - margin;
            float x = flake.x * width + 12 * density * (float) Math.sin(8 * flake.fall + flake.x * 6);
            paint.setAlpha(flake.alpha);
            if (flake.crystal) {
                canvas.save();
                canvas.translate(x, y);
                canvas.rotate(y / (8 * density));
                canvas.scale(radius, radius);
                paint.setStrokeWidth(.18f);
                canvas.drawLines(CRYSTAL, paint);
                canvas.restore();
            } else {
                canvas.drawCircle(x, y, radius, paint);
            }
        }
        postInvalidateDelayed(FRAME_MS);
    }

    /** Travel starts above the screen and wraps once the flake has left the bottom. */
    static float advanceFall(float progress, float distance) {
        return (progress + distance) % 1f;
    }

    private static final class Flake {
        final float x;
        final float radius;
        final float speed;
        final int alpha;
        final boolean crystal;
        float fall;

        Flake(Random random, boolean crystal) {
            this.crystal = crystal;
            x = random.nextFloat();
            fall = random.nextFloat();
            radius = crystal ? 4f + 3f * random.nextFloat() : 1f + 1.5f * random.nextFloat();
            speed = 20f + radius * 5f + 15f * random.nextFloat();
            alpha = 100 + random.nextInt(116);
        }
    }
}
