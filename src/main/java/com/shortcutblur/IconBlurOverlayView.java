package com.shortcutblur;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

/**
 * 图标模糊覆盖层。
 *
 * <p>背景：图标模糊动画需要把「图标本身」送进 {@code RenderEffect}。上一版直接往图标的父容器里
 * 插了一个 {@code MATCH_PARENT} 的空 {@link View} 并模糊它，结果模糊的是<b>父容器的整个矩形</b>。
 * PopupContainer 的矩形会一直向上延伸到状态栏，于是状态栏那一带的桌面被模糊 + 硬裁断
 * （表现为「状态栏区域被直接截断」）。
 *
 * <p>这一版把这个覆盖层做成「紧贴图标」的自绘 View：
 * <ul>
 *   <li>尺寸 = 图标自身宽高（由 {@link #bindTarget} 在 layout 时同步）；</li>
 *   <li>位置 = 图标在父容器中的坐标（同样由 bindTarget 每帧同步，抗图标重排）；</li>
 *   <li>绘制内容 = 图标当前的绘制结果，等于把图标「抄」一份到自己身上再交给 RenderEffect 模糊。</li>
 * </ul>
 *
 * <p>另外，模糊半径越大，边缘越需要额外留白，否则模糊核会被覆盖层自身的边界切掉，表现为
 * 「阴影/模糊在大半径时被截断」。因此本层在内容四周各留 {@link #PAD}，并把绘制内容向中心收缩同样
 * 的距离，模糊核就有完整的作用空间。
 */
final class IconBlurOverlayView extends View {

    /** 四周留白（px）：给模糊核留出作用空间，避免大半径时边缘被硬切。 */
    private static final int PAD = 32;

    private View mTarget;
    private final Rect mTmp = new Rect();

    IconBlurOverlayView(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    /**
     * 绑定要「抄绘」的目标 View。除记录引用外，还会挂一个 layout 监听，
     * 让本层在目标每次重排后都能跟上它的位置和大小。
     */
    void bindTarget(final View target) {
        mTarget = target;
        if (target == null) return;
        target.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                syncGeometry();
            }
        });
        post(new Runnable() {
            @Override
            public void run() {
                syncGeometry();
            }
        });
    }

    /** 把本层的布局参数对齐到目标图标的坐标与尺寸（含给模糊核预留的 PAD）。 */
    private void syncGeometry() {
        final View t = mTarget;
        if (t == null) return;
        Object p = getParent();
        if (!(p instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) p;
        Object tp = t.getParent();
        if (tp != parent) return;               // 目标换了父容器，交给上层重建
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) return;
        int w = t.getWidth() + PAD * 2;
        int h = t.getHeight() + PAD * 2;
        int left = t.getLeft() - PAD;
        int top = t.getTop() - PAD;
        if (lp.width == w && lp.height == h
                && getLeft() == left && getTop() == top
                && getWidth() == w && getHeight() == h) {
            return;                             // 已在目标位置，避免无谓的 requestLayout
        }
        lp.width = w;
        lp.height = h;
        setLayoutParams(lp);
        if (this instanceof View) {
            // 依赖父容器的 absolute 定位：用 translation 把本层搬到图标左上角 - PAD。
            setTranslationX(left - getLeft());
            setTranslationY(top - getTop());
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final View t = mTarget;
        if (t == null) return;
        // 把图标的绘制内容画到自己的中心区域：四周各收缩 PAD，正好对应给模糊核留的白。
        mTmp.set(PAD, PAD, getWidth() - PAD, getHeight() - PAD);
        int save = canvas.save();
        canvas.clipRect(mTmp);
        canvas.translate(PAD, PAD);
        canvas.scale(
                t.getWidth() > 0 ? (float) t.getWidth() / getWidth() : 1f,
                t.getHeight() > 0 ? (float) t.getHeight() / getHeight() : 1f);
        try {
            t.draw(canvas);
        } catch (Throwable ignored) {
        }
        canvas.restoreToCount(save);
    }
}
