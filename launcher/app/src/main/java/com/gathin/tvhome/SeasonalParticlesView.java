package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/** Seasonal effects drawn between the Home backdrop and controls. */
@SuppressLint("ViewConstructor") // Created in code with the enabled effects.
final class SeasonalParticlesView extends View {
    private static final int FRAME_MS = 32;
    private static final float[] CRYSTAL = {
            0f, -1f, 0f, 1f,
            -.866f, -.5f, .866f, .5f,
            -.866f, .5f, .866f, -.5f
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Particle[] particles;
    private final Path petalShape;
    private final float density;
    private long lastFrame;

    SeasonalParticlesView(Context context, boolean snow, boolean petals) {
        super(context);
        setVisibility(INVISIBLE);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        density = getResources().getDisplayMetrics().density;
        paint.setStrokeCap(Paint.Cap.ROUND);
        petalShape = petals ? makePetalShape() : null;
        int snowCount = snow ? 48 : 0;
        particles = new Particle[snowCount + (petals ? 28 : 0)];
        Random random = new Random();
        for (int i = 0; i < particles.length; i++) {
            particles[i] = new Particle(random, i >= snowCount, (i % 5) == 0);
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
        for (Particle particle : particles) {
            float radius = particle.radius * density;
            float margin = 1.1f * radius;
            float travel = height + 2 * margin;
            particle.fall = advanceFall(particle.fall, particle.speed * density * seconds / travel);
            float y = particle.fall * travel - margin;
            float sway = (particle.petal ? 34 : 12) * density;
            float x = particle.x * width + sway * (float) Math.sin(8 * particle.fall + particle.x * 6);
            paint.setColor(particle.color);
            if (particle.petal) {
                float turn = y / density + particle.x * 360f;
                float widthScale = .3f + .7f * Math.abs((float) Math.cos(turn / 38f));
                canvas.save();
                canvas.translate(x, y);
                canvas.rotate(turn / 2f);
                canvas.scale(radius * widthScale, radius);
                canvas.drawPath(petalShape, paint);
                canvas.restore();
            } else if (particle.crystal) {
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

    /** Travel starts above the screen and wraps once the particle has left the bottom. */
    static float advanceFall(float progress, float distance) {
        return (progress + distance) % 1f;
    }

    private static Path makePetalShape() {
        Path path = new Path();
        path.moveTo(0f, .9f);
        path.cubicTo(-.77f, .47f, -1f, -.5f, -.55f, -.82f);
        path.quadTo(-.2f, -1f, -.02f, -.58f);
        path.quadTo(.27f, -1f, .62f, -.8f);
        path.cubicTo(1f, -.41f, .69f, .49f, 0f, .9f);
        path.close();
        return path;
    }

    private static final class Particle {
        final float x;
        final float radius;
        final float speed;
        final int color;
        final boolean petal;
        final boolean crystal;
        float fall;

        Particle(Random random, boolean petal, boolean crystal) {
            this.petal = petal;
            this.crystal = crystal;
            x = random.nextFloat();
            fall = random.nextFloat();
            if (petal) {
                radius = 6f + 4f * random.nextFloat();
                speed = 24f + 24f * random.nextFloat();
                int pink = random.nextBoolean() ? 0xFFABC9 : 0xFFD8E6;
                color = ((160 + random.nextInt(76)) << 24) | pink;
            } else {
                radius = crystal ? 4f + 3f * random.nextFloat() : 1f + 1.5f * random.nextFloat();
                speed = 20f + radius * 5f + 15f * random.nextFloat();
                color = ((100 + random.nextInt(116)) << 24) | 0xFFFFFF;
            }
        }
    }
}
