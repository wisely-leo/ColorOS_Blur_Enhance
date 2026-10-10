package com.shortcutblur;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 键盘边缘「亮色描边」（越靠内越浅）。
 *
 * 作为 LayerDrawable 的上层叠在模糊背景之上，而 LayerDrawable 又是键盘根 View 的
 * background，因此绘制顺序永远位于键盘子 View（按键 / 候选栏）之下，不会遮挡内容。
 *
 * 渐隐实现：不用 BlurMaskFilter（硬件加速下不稳定），而是多层描边叠加——
 * 由外向内逐步内缩、逐步降低 alpha，视觉上形成柔和的向内侧淡出。
 *
 * 两种形态：
 * - 悬浮键盘：四边环绕描边，圆角与键盘一致；
 * - 非悬浮（贴底）键盘：只保留顶部一条水平线（底部贴着屏幕、左右不可见）。
 */
final class ImeGlowStrokeDrawable extends Drawable {

    /** 四边环绕（悬浮键盘）。 */
    static final int MODE_ALL = 0;
    /** 仅顶部一条线（非悬浮键盘）。 */
    static final int MODE_TOP_ONLY = 1;

    /** 渐隐层数：越多越平滑。 */
    private static final int FEATHER_LAYERS = 6;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private final int mBaseColor;
    private float mStrokePx;
    private float mFeatherPx;
    private float mCornerPx;
    private int mMode = MODE_ALL;

    ImeGlowStrokeDrawable(int color, float strokePx, float featherPx, float cornerPx) {
        mBaseColor = color;
        mStrokePx = strokePx;
        mFeatherPx = featherPx;
        mCornerPx = cornerPx;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(mStrokePx);
        mPaint.setColor(color);
    }

    void setCornerPx(float cornerPx) {
        mCornerPx = cornerPx;
        invalidateSelf();
    }

    void setMode(int mode) {
        mMode = mode;
        invalidateSelf();
    }

    /**
     * 第 i 层的 alpha（i=0 最外层、最亮；越往里越暗）。
     *
     * 用平方衰减：多层是叠加混合（alpha 会互相抬升），线性衰减在近边缘
     * 区域叠加后偏亮；平方衰减让内层掉得更快，整体更接近「外亮内淡」。
     */
    private int layerAlpha(int i) {
        int base = (mBaseColor >>> 24) & 0xFF;
        if (FEATHER_LAYERS <= 1) return base;
        float k = 1f - (float) i / (FEATHER_LAYERS - 1);   // 1.0 → 0.0
        k = k * k;
        return Math.max(0, Math.round(base * k));
    }

    private void applyLayerColor(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;

        canvas.save();
        canvas.clipRect(b);

        // 每层向内缩 step，alpha 逐层降低，形成「外亮内淡」的柔和描边。
        float step = (mFeatherPx > 0f) ? (mFeatherPx / FEATHER_LAYERS) : 0f;

        for (int i = 0; i < FEATHER_LAYERS; i++) {
            int alpha = layerAlpha(i);
            if (alpha <= 0) continue;
            applyLayerColor(alpha);

            float inset = mStrokePx * 0.5f + step * i;

            if (mMode == MODE_TOP_ONLY) {
                // 仅顶部水平线：向下（向内）逐层偏移，越往下越淡。
                float y = b.top + inset;
                if (y >= b.bottom) break;
                canvas.drawLine(b.left, y, b.right, y, mPaint);
            } else {
                mRect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
                if (mRect.width() <= 0f || mRect.height() <= 0f) break;
                float r = Math.max(0f, Math.min(mCornerPx,
                        Math.min(mRect.width(), mRect.height()) * 0.5f));
                mPath.reset();
                mPath.addRoundRect(mRect, r, r, Path.Direction.CW);
                canvas.drawPath(mPath, mPaint);
            }
        }

        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        // 外部 alpha 缩放：直接改底色位，保持「渐隐比例」不变。
        int base = (mBaseColor >>> 24) & 0xFF;
        int scaled = (base * Math.max(0, Math.min(255, alpha))) / 255;
        mPaint.setColor((mBaseColor & 0x00FFFFFF) | (scaled << 24));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mPaint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
