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

/**
 * 键盘边缘「亮色描边」（由外向内连续渐隐）。
 *
 * 作为 LayerDrawable 的上层叠在模糊背景之上，而 LayerDrawable 又是键盘根 View 的
 * background，因此绘制顺序永远位于键盘子 View（按键 / 候选栏）之下，不会遮挡内容。
 *
 * 渐隐实现：用【线性渐变 Shader】一次绘制完成，而不是把描边带切成 N 个同心环。
 * 之前用 12 个同心环模拟渐变，在 8dp 羽化宽度下（560dpi ≈ 28px）每层约 2.3px，
 * 叠加后能看到明显台阶（"过渡突兀"）；而且每层都要 reset + addRoundRect×2 重建路径。
 * 改为渐变后：过渡严格线性（无台阶），绘制次数从 12 次降到 2 次（四边）/ 1 次（顶部）。
 *
 * 四边环绕时，亮度由「垂直渐变 + 水平渐变」两次绘制叠加而成：
 * - 垂直渐变负责上 / 下两条边（中间透明）；
 * - 水平渐变负责左 / 右两条边（中间透明）；
 * - 四个圆角同时被两者覆盖，因此略亮于正边（视觉上强化圆角轮廓）。
 *
 * 两种形态：
 * - 悬浮键盘：四边环绕，圆角与键盘一致；
 * - 非悬浮（贴底）键盘：只保留顶部一条水平带。
 */
final class ImeGlowStrokeDrawable extends Drawable {

    /** 四边环绕（悬浮键盘）。 */
    static final int MODE_ALL = 0;
    /** 仅顶部一条带（非悬浮键盘）。 */
    static final int MODE_TOP_ONLY = 1;

    /**
     * 方向渐变各自使用的 alpha 权重。
     * 两条渐变在圆角处叠加，权重略小于 1 可避免圆角明显亮于正边。
     */
    private static final float DIR_WEIGHT = 0.75f;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mBand = new Path();
    private final RectF mOuter = new RectF();
    private final RectF mInner = new RectF();

    // 渐变按 bounds 缓存（键盘尺寸通常不变；只有尺寸 / 羽化宽度变化时才重建）。
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

    /** 把 alpha 与 base 的 RGB 合成为一个渐变用的颜色。 */
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

        // 上/下两端亮、中间透明
        mGradVert = new LinearGradient(0f, b.top, 0f, b.bottom,
                new int[]{ withAlpha(av), clear, clear, withAlpha(av) },
                new float[]{ 0f, fv, 1f - fv, 1f },
                Shader.TileMode.CLAMP);

        // 左/右两端亮、中间透明
        mGradHorz = new LinearGradient(b.left, 0f, b.right, 0f,
                new int[]{ withAlpha(ah), clear, clear, withAlpha(ah) },
                new float[]{ 0f, fh, 1f - fh, 1f },
                Shader.TileMode.CLAMP);

        // 顶部单侧带：上满下透明
        mGradTopOnly = new LinearGradient(0f, b.top, 0f, b.top + f,
                new int[]{ withAlpha(at), clear },
                new float[]{ 0f, 1f },
                Shader.TileMode.CLAMP);
    }

    /** 构造「描边带」= 外圆角矩形 − 内圆角矩形（内缩 feather）。 */
    private void buildBand(Rect b, float f) {
        mOuter.set(b.left, b.top, b.right, b.bottom);
        mInner.set(b.left + f, b.top + f, b.right - f, b.bottom - f);
        float maxR = Math.min(mOuter.width(), mOuter.height()) * 0.5f;
        float rOuter = Math.max(0f, Math.min(mCornerPx, maxR));
        // 内框圆角 = 外框圆角 − 环宽，保证带宽在拐角处均匀。
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