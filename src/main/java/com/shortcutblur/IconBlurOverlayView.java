package com.shortcutblur;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

final class IconBlurOverlayView extends View {

    private static final int PAD = 32;

    private View mTarget;

    private OnLayoutChangeListener mTargetListener;
    private final Rect mTmp = new Rect();

    IconBlurOverlayView(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    void bindTarget(final View target) {

        detachTargetListener();
        mTarget = target;
        if (target == null) return;

        final java.lang.ref.WeakReference<IconBlurOverlayView> self =
                new java.lang.ref.WeakReference<IconBlurOverlayView>(this);
        mTargetListener = new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                IconBlurOverlayView me = self.get();
                if (me != null) me.syncGeometry();
            }
        };
        target.addOnLayoutChangeListener(mTargetListener);
        post(new Runnable() {
            @Override
            public void run() {
                IconBlurOverlayView me = self.get();
                if (me != null) me.syncGeometry();
            }
        });
    }

    private void detachTargetListener() {
        final View t = mTarget;
        final OnLayoutChangeListener l = mTargetListener;
        if (t != null && l != null) {
            try { t.removeOnLayoutChangeListener(l); } catch (Throwable ignored) {}
        }
        mTargetListener = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        detachTargetListener();
        mTarget = null;
    }

    private void syncGeometry() {
        final View t = mTarget;
        if (t == null) return;
        Object p = getParent();
        if (!(p instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) p;
        Object tp = t.getParent();
        if (tp != parent) return;
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) return;
        int w = t.getWidth() + PAD * 2;
        int h = t.getHeight() + PAD * 2;
        int left = t.getLeft() - PAD;
        int top = t.getTop() - PAD;
        if (lp.width == w && lp.height == h
                && getLeft() == left && getTop() == top
                && getWidth() == w && getHeight() == h) {
            return;
        }
        lp.width = w;
        lp.height = h;
        setLayoutParams(lp);

        setTranslationX(left - getLeft());
        setTranslationY(top - getTop());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final View t = mTarget;
        if (t == null) return;

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
