package com.shortcutblur;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

final class ImeGlowStrokeDrawable extends Drawable {

    static final int MODE_ALL = 0;

    static final int MODE_TOP_ONLY = 1;

    private static final float DIR_WEIGHT = 0.75f;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mBand = new Path();
    private final RectF mOuter = new RectF();
    private final RectF mInner = new RectF();

    private LinearGradient mGradVert;
    private LinearGradient mGradHorz;
    private LinearGradient mGradTopOnly;
    private int mCachedW = Integer.MIN_VALUE;
    private int mCachedH = Integer.MIN_VALUE;
    private float mCachedFeather = -1f;

    private final int mBaseColor;
    private float mFeatherPx;
    private float mCornerPx;
    private int mMode = MODE_ALL;

    ImeGlowStrokeDrawable(int color, float featherPx, float cornerPx) {
        mBaseColor = color;
        mFeatherPx = featherPx;
        mCornerPx = cornerPx;
        mFill.setStyle(Paint.Style.FILL);
    }

    void setCornerPx(float cornerPx) {
        mCornerPx = cornerPx;
        invalidateSelf();
    }

    void setMode(int mode) {
        mMode = mode;
        invalidateSelf();
    }

    private int alphaOf(float weight) {
        int base = (mBaseColor >>> 24) & 0xFF;
        return Math.max(0, Math.min(255, Math.round(base * weight)));
    }

    private int withAlpha(int alpha) {
        return (alpha << 24) | (mBaseColor & 0x00FFFFFF);
    }

    private void ensureGradients(Rect b, float f) {
        int w = b.width(), h = b.height();
        if (mGradVert != null && mCachedW == w && mCachedH == h && mCachedFeather == f) return;
        mCachedW = w;
        mCachedH = h;
        mCachedFeather = f;

        int av = alphaOf(DIR_WEIGHT);
        int ah = alphaOf(DIR_WEIGHT);
        int at = alphaOf(1f);
        int clear = mBaseColor & 0x00FFFFFF;

        float fv = Math.max(0.0001f, f / Math.max(1f, (float) h));
        float fh = Math.max(0.0001f, f / Math.max(1f, (float) w));

        mGradVert = new LinearGradient(0f, b.top, 0f, b.bottom,
                new int[]{ withAlpha(av), clear, clear, withAlpha(av) },
                new float[]{ 0f, fv, 1f - fv, 1f },
                Shader.TileMode.CLAMP);

        mGradHorz = new LinearGradient(b.left, 0f, b.right, 0f,
                new int[]{ withAlpha(ah), clear, clear, withAlpha(ah) },
                new float[]{ 0f, fh, 1f - fh, 1f },
                Shader.TileMode.CLAMP);

        mGradTopOnly = new LinearGradient(0f, b.top, 0f, b.top + f,
                new int[]{ withAlpha(at), clear },
                new float[]{ 0f, 1f },
                Shader.TileMode.CLAMP);
    }

    private void buildBand(Rect b, float f) {
        mOuter.set(b.left, b.top, b.right, b.bottom);
        mInner.set(b.left + f, b.top + f, b.right - f, b.bottom - f);
        float maxR = Math.min(mOuter.width(), mOuter.height()) * 0.5f;
        float rOuter = Math.max(0f, Math.min(mCornerPx, maxR));

        float rInner = Math.max(0f, rOuter - f);
        mBand.reset();
        mBand.setFillType(Path.FillType.EVEN_ODD);
        mBand.addRoundRect(mOuter, rOuter, rOuter, Path.Direction.CW);
        if (mInner.width() > 0f && mInner.height() > 0f) {
            mBand.addRoundRect(mInner, rInner, rInner, Path.Direction.CW);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty() || mFeatherPx <= 0f) return;

        canvas.save();
        canvas.clipRect(b);

        float f = Math.min(mFeatherPx, Math.min(b.width(), b.height()) * 0.5f);
        ensureGradients(b, f);

        if (mMode == MODE_TOP_ONLY) {
            mFill.setShader(mGradTopOnly);
            canvas.drawRect(b.left, b.top, b.right, b.top + f, mFill);
        } else {
            buildBand(b, f);
            mFill.setShader(mGradVert);
            canvas.drawPath(mBand, mFill);
            mFill.setShader(mGradHorz);
            canvas.drawPath(mBand, mFill);
        }
        mFill.setShader(null);

        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mFill.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
