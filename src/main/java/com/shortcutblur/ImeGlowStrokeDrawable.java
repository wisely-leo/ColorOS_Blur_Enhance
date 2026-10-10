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
 * 键盘边缘「亮色描边」。
 *
 * 作为 LayerDrawable 的上层叠在模糊背景之上，而 LayerDrawable 又是键盘根 View 的
 * background，因此绘制顺序永远位于键盘子 View（按键 / 候选栏）之下，不会遮挡内容。
 *
 * 两种形态：
 * - 悬浮键盘：四边环绕描边，圆角与键盘一致；
 * - 非悬浮（贴底）键盘：只保留顶部一条水平线（底部贴着屏幕、左右不可见）。
 *
 * 无内阴影（早期版本用 BlurMaskFilter.INNER，硬件加速下不稳定，已移除）。
 */
final class ImeGlowStrokeDrawable extends Drawable {

    /** 四边环绕（悬浮键盘）。 */
    static final int MODE_ALL = 0;
    /** 仅顶部一条线（非悬浮键盘）。 */
    static final int MODE_TOP_ONLY = 1;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private float mStrokePx;
    private float mCornerPx;
    private int mMode = MODE_ALL;

    ImeGlowStrokeDrawable(int color, float strokePx, float cornerPx) {
        mStrokePx = strokePx;
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

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;

        canvas.save();
        canvas.clipRect(b);

        if (mMode == MODE_TOP_ONLY) {
            // 仅顶部一条水平线，画在 bounds 内沿（上半线宽不溢出）。
            float y = b.top + mStrokePx * 0.5f;
            canvas.drawLine(b.left, y, b.right, y, mPaint);
        } else {
            // 四边环绕，路径内缩半个线宽，避免外侧一半被 bounds 裁掉。
            float inset = mStrokePx * 0.5f;
            mRect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            float r = Math.max(0f, Math.min(mCornerPx,
                    Math.min(mRect.width(), mRect.height()) * 0.5f));
            mPath.reset();
            mPath.addRoundRect(mRect, r, r, Path.Direction.CW);
            canvas.drawPath(mPath, mPaint);
        }

        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
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
