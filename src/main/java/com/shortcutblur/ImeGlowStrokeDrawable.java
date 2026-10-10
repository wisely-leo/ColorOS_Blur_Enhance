package com.shortcutblur;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 键盘边缘「内侧发光」描边。
 *
 * 作为 LayerDrawable 的上层叠在模糊背景之上，而 LayerDrawable 又是键盘根 View 的
 * background，因此绘制顺序永远位于键盘子 View（按键 / 候选栏）之下，不会遮挡内容。
 *
 * 圆角与键盘保持一致（悬浮键盘走圆角，贴底键盘为直角）。
 */
final class ImeGlowStrokeDrawable extends Drawable {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();

    private float mStrokePx;
    private float mBlurPx;
    private float mCornerPx;

    ImeGlowStrokeDrawable(int color, float strokePx, float blurPx, float cornerPx) {
        mStrokePx = strokePx;
        mBlurPx = blurPx;
        mCornerPx = cornerPx;

        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(mStrokePx);
        mPaint.setColor(color);
        if (mBlurPx > 0f) {
            mPaint.setMaskFilter(new BlurMaskFilter(mBlurPx, BlurMaskFilter.Blur.INNER));
        }
    }

    void setCornerPx(float cornerPx) {
        mCornerPx = cornerPx;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;

        // 路径内缩半个线宽，避免外侧一半被 bounds 裁掉
        float inset = mStrokePx * 0.5f;
        mRect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        float r = Math.max(0f, Math.min(mCornerPx,
                Math.min(mRect.width(), mRect.height()) * 0.5f));

        mPath.reset();
        mPath.addRoundRect(mRect, r, r, Path.Direction.CW);

        canvas.save();
        canvas.clipRect(b);
        canvas.drawPath(mPath, mPaint);
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
