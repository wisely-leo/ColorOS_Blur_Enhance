package com.shortcutblur;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

final class ImeGlowStrokeDrawable extends Drawable {

    static final int MODE_ALL = 0;

    static final int MODE_TOP_ONLY = 1;

    private static final int LAYERS = 24;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint mTop = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path mPath = new Path();
    private final RectF mOuter = new RectF();
    private final RectF mInner = new RectF();
    private final int mBaseColor;
    private float mFeatherPx;
    private float mCornerPx;
    private int mMode = MODE_ALL;

    ImeGlowStrokeDrawable(int color, float featherPx, float cornerPx) {
        mBaseColor = color;
        mFeatherPx = featherPx;
        mCornerPx = cornerPx;
        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor(color);
        mTop.setStyle(Paint.Style.FILL);
        mTop.setColor(color);
    }

    void setCornerPx(float cornerPx) {
        mCornerPx = cornerPx;
        invalidateSelf();
    }

    void setMode(int mode) {
        mMode = mode;
        invalidateSelf();
    }

    private int layerAlpha(int i) {
        int base = (mBaseColor >>> 24) & 0xFF;
        float k = 1f - (float) i / LAYERS;
        k = k * k;
        return Math.max(0, Math.round(base * k));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty() || mFeatherPx <= 0f) return;

        canvas.save();
        canvas.clipRect(b);

        float step = mFeatherPx / LAYERS;

        if (mMode == MODE_TOP_ONLY) {

            for (int i = 0; i < LAYERS; i++) {
                int alpha = layerAlpha(i);
                if (alpha <= 0) continue;
                mTop.setAlpha(alpha);
                float top = b.top + step * i;
                float bottom = top + step + 0.5f;
                if (top >= b.bottom) break;
                canvas.drawRect(b.left, top, b.right, Math.min(bottom, b.bottom), mTop);
            }
        } else {

            for (int i = 0; i < LAYERS; i++) {
                int alpha = layerAlpha(i);
                if (alpha <= 0) continue;
                mFill.setAlpha(alpha);

                float outerInset = step * i;
                float innerInset = step * (i + 1);

                mOuter.set(b.left + outerInset, b.top + outerInset,
                           b.right - outerInset, b.bottom - outerInset);
                if (mOuter.width() <= 0f || mOuter.height() <= 0f) break;

                mInner.set(b.left + innerInset, b.top + innerInset,
                           b.right - innerInset, b.bottom - innerInset);

                float maxR = Math.min(mOuter.width(), mOuter.height()) * 0.5f;
                float rOuter = Math.max(0f, Math.min(mCornerPx, maxR));

                float rInner = Math.max(0f, rOuter - step);

                mPath.reset();
                mPath.setFillType(Path.FillType.EVEN_ODD);
                mPath.addRoundRect(mOuter, rOuter, rOuter, Path.Direction.CW);
                if (mInner.width() > 0f && mInner.height() > 0f) {
                    mPath.addRoundRect(mInner, rInner, rInner, Path.Direction.CW);
                }
                canvas.drawPath(mPath, mFill);
            }
        }

        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mFill.setColorFilter(cf);
        mTop.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
