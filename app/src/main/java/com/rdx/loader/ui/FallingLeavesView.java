package com.rdx.loader.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * High-performance 120 FPS Falling Leaves & Floating Petals Particle View.
 * Renders smooth floating and rotating monochrome leaves drifting across the background.
 */
public class FallingLeavesView extends View {

    private static final int LEAF_COUNT = 16;
    private final List<LeafParticle> mLeaves = new ArrayList<>();
    private final Paint mLeafPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mLeafPath = new Path();
    private final Random mRandom = new Random();

    private boolean mIsInitialized = false;

    public FallingLeavesView(Context context) {
        super(context);
        init();
    }

    public FallingLeavesView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FallingLeavesView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mLeafPaint.setStyle(Paint.Style.FILL);

        // Pre-build smooth organic leaf petal silhouette centered at (0, 0)
        mLeafPath.reset();
        mLeafPath.moveTo(0, -1.0f);
        mLeafPath.cubicTo(0.55f, -0.55f, 0.65f, 0.35f, 0, 1.0f);
        mLeafPath.cubicTo(-0.65f, 0.35f, -0.55f, -0.55f, 0, -1.0f);
        mLeafPath.close();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && !mIsInitialized) {
            initParticles(w, h);
            mIsInitialized = true;
        }
    }

    private void initParticles(int width, int height) {
        mLeaves.clear();
        for (int i = 0; i < LEAF_COUNT; i++) {
            LeafParticle leaf = new LeafParticle();
            resetLeaf(leaf, width, height, true);
            mLeaves.add(leaf);
        }
    }

    private void resetLeaf(LeafParticle leaf, int width, int height, boolean scatterFullHeight) {
        leaf.x = mRandom.nextFloat() * width;
        leaf.y = scatterFullHeight ? (mRandom.nextFloat() * height) : (-20f - mRandom.nextFloat() * 60f);
        leaf.size = 8f + mRandom.nextFloat() * 10f; // 8dp - 18dp subtle petals
        leaf.speedY = 0.8f + mRandom.nextFloat() * 1.5f;
        leaf.swayAmp = 0.8f + mRandom.nextFloat() * 1.6f;
        leaf.swayFreq = 0.012f + mRandom.nextFloat() * 0.02f;
        leaf.swayPhase = mRandom.nextFloat() * (float) Math.PI * 2f;
        leaf.rotation = mRandom.nextFloat() * 360f;
        leaf.rotSpeed = -0.8f + mRandom.nextFloat() * 1.6f;
        // Soft translucent foliage tones (matching realistic leaves in branch)
        int alpha = 30 + mRandom.nextInt(45); // alpha 30 - 75
        int tone = 50 + mRandom.nextInt(35);
        leaf.color = Color.argb(alpha, tone, tone + 6, tone + 12);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        for (int i = 0; i < mLeaves.size(); i++) {
            LeafParticle leaf = mLeaves.get(i);

            // Update physics
            leaf.y += leaf.speedY;
            leaf.swayPhase += leaf.swayFreq;
            leaf.x += (float) Math.sin(leaf.swayPhase) * leaf.swayAmp;
            leaf.rotation += leaf.rotSpeed;

            // Reset if out of bounds
            if (leaf.y > h + 50f || leaf.x < -60f || leaf.x > w + 60f) {
                resetLeaf(leaf, w, h, false);
            }

            // Draw leaf
            canvas.save();
            canvas.translate(leaf.x, leaf.y);
            canvas.rotate(leaf.rotation);
            canvas.scale(leaf.size, leaf.size * 1.35f);

            mLeafPaint.setColor(leaf.color);
            canvas.drawPath(mLeafPath, mLeafPaint);

            canvas.restore();
        }

        // 120 FPS frame request
        postInvalidateOnAnimation();
    }

    private static class LeafParticle {
        float x;
        float y;
        float size;
        float speedY;
        float swayAmp;
        float swayFreq;
        float swayPhase;
        float rotation;
        float rotSpeed;
        int color;
    }
}
