package com.shortcutblur;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 键盘边缘「内发光」（真正的发光：贴边最亮，向内连续衰减；不是暗色内阴影）。
 *
 * 亮度 =「到圆角矩形边界的距离」的函数：
 * - 4 条直边：线性渐变（贴边为 alpha 峰值，向内 band 像素内衰减到 0）；
 * - 4 个圆角：径向渐变（圆心取圆角圆心，半径 r 处为峰值，半径 r-band 处为 0）。
 *
 * 为什么不能只用 x / y 两条线性渐变（上一版做法）：
 * 圆角上 45° 处的点，距离「上边」和「左边」各有 0.293·r（r=25dp≈87px 时约 26px），
 * 远大于发光带宽度 band，两条渐变在那里都已经衰减到 0，
 * 于是圆角整段熄灭（观感就是"圆角没有覆盖"）。改用径向渐变后，圆角与直边亮度
 * 连续且接缝可对齐；各块区域互不重叠，全周 alpha 一致（不会在圆角叠加出亮角）。
 *
 * 绘制次数：上/下边 1 次 + 左/右边 1 次 + 每个圆角 1 次（共 6 次；仅顶部带时 3 次），
 * 比早期的 24 / 12 层同心环更少，且几何精确。
 *
 * 两种形态：
 * - 悬浮键盘：四边环绕；
 * - 非悬浮（贴底）键盘：只保留顶部一条带（含两个上圆角）。
 */
final class ImeGlowStrokeDrawable extends Drawable {
    /** 四边环绕（悬浮键盘）。 */
    static final int MODE_ALL = 0;
    /** 仅顶部一条带（非悬浮键盘）。 */
    static final int MODE_TOP_ONLY = 1;

    /** 衰减曲线：0 = 贴边（峰值），1 = 发光带内边界（0）。 */
    private static final float[] RAMP_POS = { 0f, 0.35f, 0.70f, 1f };
    private static final float[] RAMP_VAL = { 1f, 0.50f, 0.18f, 0f };

    /** 小于该半径就不单独画圆角（半径 0 即方角，直边已覆盖）。 */
    private static final float MIN_CORNER_R = 0.5f;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mEdgePath = new Path();

    // 渐变按（尺寸 / 带宽 / 圆角半径）缓存，不变则复用。
    private LinearGradient mGradVert;
    private LinearGradient mGradHorz;
    private RadialGradient mGradCorner;
    private int mCachedW = Integer.MIN_VALUE;
    private int mCachedH = Integer.MIN_VALUE;
    private float mCachedBand = -1f;
    private float mCachedCornerR = -1f;

    private final int mBaseColor;
    private final float mBandPx;
    private float mCornerPx;
    private int mMode = MODE_ALL;

    ImeGlowStrokeDrawable(int color, float bandPx, float cornerPx) {
        mBaseColor = color;
        mBandPx = bandPx;
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

    private int rgb() {
        return mBaseColor & 0x00FFFFFF;
    }

    private int peakAlpha() {
        return (mBaseColor >>> 24) & 0xFF;
    }

    private int alphaAt(float weight) {
        int a = Math.round(peakAlpha() * weight);
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return a;
    }

    private int withAlpha(int alpha) {
        return (alpha << 24) | rgb();
    }

    private void ensureShaders(Rect b, float band, float r) {
        int w = b.width(), h = b.height();
        if (mGradVert != null && mGradHorz != null
                && mCachedW == w && mCachedH == h
                && mCachedBand == band && mCachedCornerR == r) {
            return;
        }
        mCachedW = w;
        mCachedH = h;
        mCachedBand = band;
        mCachedCornerR = r;

        int peak = peakAlpha();
        int mid = alphaAt(RAMP_VAL[1]);
        int tail = alphaAt(RAMP_VAL[2]);
        int clear = rgb();

        float fv = band / Math.max(1f, (float) h);
        if (fv > 0.5f) fv = 0.5f;
        float fh = band / Math.max(1f, (float) w);
        if (fh > 0.5f) fh = 0.5f;

        int[] colors = {
                withAlpha(peak), withAlpha(mid), withAlpha(tail), clear,
                clear, withAlpha(tail), withAlpha(mid), withAlpha(peak)
        };

        // 上 / 下两条边（中间透明）
        mGradVert = new LinearGradient(0f, b.top, 0f, b.bottom, colors.clone(),
                new float[] { 0f, RAMP_POS[1] * fv, RAMP_POS[2] * fv, fv,
                        1f - fv, 1f - RAMP_POS[2] * fv, 1f - RAMP_POS[1] * fv, 1f },
                Shader.TileMode.CLAMP);

        // 左 / 右两条边（中间透明）
        mGradHorz = new LinearGradient(b.left, 0f, b.right, 0f, colors.clone(),
                new float[] { 0f, RAMP_POS[1] * fh, RAMP_POS[2] * fh, fh,
                        1f - fh, 1f - RAMP_POS[2] * fh, 1f - RAMP_POS[1] * fh, 1f },
                Shader.TileMode.CLAMP);

        // 圆角：以圆心为原点缓存一份径向渐变，绘制时 translate 到各圆角复用。
        if (r <= MIN_CORNER_R) {
            mGradCorner = null;
        } else {
            float fr = Math.min(band, r) / r;
            mGradCorner = new RadialGradient(0f, 0f, r,
                    new int[] { clear, clear, withAlpha(tail), withAlpha(mid), withAlpha(peak) },
                    new float[] { 0f, 1f - fr, 1f - RAMP_POS[2] * fr, 1f - RAMP_POS[1] * fr, 1f },
                    Shader.TileMode.CLAMP);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty() || mBandPx <= 0f) return;

        float band = Math.min(mBandPx, Math.min(b.width(), b.height()) * 0.5f);
        if (band <= 0f) return;

        // 圆角半径既不能超过短边一半，也要给直边留出位置。
        float r = Math.min(mCornerPx, Math.min(b.width(), b.height()) * 0.5f - band);
        if (r < 0f) r = 0f;

        canvas.save();
        canvas.clipRect(b);
        ensureShaders(b, band, r);

        final float left = b.left, top = b.top, right = b.right, bottom = b.bottom;
        final boolean topOnly = (mMode == MODE_TOP_ONLY);

        // ---- 上 / 下边 ----
        mEdgePath.reset();
        mEdgePath.addRect(left + r, top, right - r, top + band, Path.Direction.CW);
        if (!topOnly) {
            mEdgePath.addRect(left + r, bottom - band, right - r, bottom, Path.Direction.CW);
        }
        mFill.setShader(mGradVert);
        canvas.drawPath(mEdgePath, mFill);

        // ---- 左 / 右边（仅四边环绕时）----
        if (!topOnly) {
            mEdgePath.reset();
            mEdgePath.addRect(left, top + r, left + band, bottom - r, Path.Direction.CW);
            mEdgePath.addRect(right - band, top + r, right, bottom - r, Path.Direction.CW);
            mFill.setShader(mGradHorz);
            canvas.drawPath(mEdgePath, mFill);
        }

        // ---- 圆角 ----
        if (mGradCorner != null) {
            mFill.setShader(mGradCorner);
            final float cx0 = left + r, cy0 = top + r;
            final float cx1 = right - r, cy1 = bottom - r;
            // 仅顶部带时，上圆角只保留落到带内的那一截。
            final float topCornersBottom = topOnly ? top + band : cy0;
            drawCorner(canvas, cx0, cy0, r, left, top, cx0, topCornersBottom);
            drawCorner(canvas, cx1, cy0, r, cx1, top, right, topCornersBottom);
            if (!topOnly) {
                drawCorner(canvas, cx0, cy1, r, left, cy1, cx0, bottom);
                drawCorner(canvas, cx1, cy1, r, cx1, cy1, right, bottom);
            }
        }

        mFill.setShader(null);
        canvas.restore();
    }

    /**
     * 画一个圆角的发光弧：先把画布裁到该圆角所在的象限（必要时再截到顶部带内），
     * 再把径向渐变平移到圆心，画圆（圆内远离边界处 alpha 自然为 0）。
     */
    private void drawCorner(Canvas canvas, float cx, float cy, float r,
                            float clipL, float clipT, float clipR, float clipB) {
        if (clipR <= clipL || clipB <= clipT) return;
        canvas.save();
        canvas.clipRect(clipL, clipT, clipR, clipB);
        canvas.translate(cx, cy);
        canvas.drawCircle(0f, 0f, r, mFill);
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
