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
 * 键盘边缘「亮色描边」（由外向内连续渐隐）。
 *
 * 作为 LayerDrawable 的上层叠在模糊背景之上，而 LayerDrawable 又是键盘根 View 的
 * background，因此绘制顺序永远位于键盘子 View（按键 / 候选栏）之下，不会遮挡内容。
 *
 * 渐隐实现（不用 BlurMaskFilter：硬件加速下不稳定）：
 * 把「描边带」切成 N 个同心环，每个环用 EVEN_ODD 填充绘制，
 * 环宽 = feather/N，相邻环边界严丝合缝，alpha 由外向内逐层递减，
 * 叠加后即为一条无台阶、由外向内自然淡出的柔和描边。
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

    /** 渐隐层数：足够密才能连续无阶梯。 */
    private static final int LAYERS = 24;

    /** 环使用的填充画笔（EVEN_ODD 挖空内层）。 */
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 顶部带使用的直画笔。 */
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

    /**
     * 第 i 层 alpha（i=0 最外层=最亮，越往里越暗）。
     * 用平方衰减抵消多层叠加带来的亮度抬升。
     */
    private int layerAlpha(int i) {
        int base = (mBaseColor >>> 24) & 0xFF;
        float k = 1f - (float) i / LAYERS;      // 1.0 → 1/N
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
            // 顶部横向带：从顶边向下，共 featherPx 高；上亮下淡。
            for (int i = 0; i < LAYERS; i++) {
                int alpha = layerAlpha(i);
                if (alpha <= 0) continue;
                mTop.setAlpha(alpha);
                float top = b.top + step * i;
                float bottom = top + step + 0.5f;   // 轻微重叠，消除亚像素缝隙
                if (top >= b.bottom) break;
                canvas.drawRect(b.left, top, b.right, Math.min(bottom, b.bottom), mTop);
            }
        } else {
            // 四边环绕：以「同心环」拼接，环宽 = step，相邻环严丝合缝。
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
                // 内框圆角 = 外框圆角 - 环宽，保证环宽在拐角处均匀。
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
