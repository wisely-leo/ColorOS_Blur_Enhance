package com.shortcutblur.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class SoftUi {

    private SoftUi() {}

    public static int CANVAS         = 0xFFF2F2F7;

    public static int SURFACE        = 0xFFFFFFFF;

    public static int CARD_FILL      = SURFACE;

    public static int ACCENT         = 0xFF007AFF;

    public static int TEXT_PRIMARY   = 0xFF000000;

    public static int TEXT_SECONDARY = 0x993C3C43;

    public static int DIVIDER        = 0x5C3C3C43;

    public static int SWITCH_OFF     = 0xFFE9E9EA;

    public static int TRACK          = 0xFFE9E9EA;

    public static int KNOB           = 0xFFFFFFFF;

    public static float RADIUS      = 12f;
    public static float ROW_H       = 48f;
    public static float PAD         = 16f;
    public static float GAP         = 10f;
    public static float SIDE        = 16f;
    public static float TITLE_TOP   = 18f;
    public static float TITLE_BOTTOM= 10f;

    public static float TITLE_SIZE = 28f;
    public static float BODY_SIZE  = 16f;
    public static float SUB_SIZE   = 13f;

    public static float SWITCH_W   = 51f;
    public static float SWITCH_H   = 31f;
    public static float KNOB_PAD   = 2f;
    public static float SLIDER_W   = 160f;
    public static float TRACK_H    = 4f;
    public static float THUMB_R    = 11f;

    public static float BLUR_RADIUS   = 30f;

    public static int   GLASS_TINT    = 0x33FFFFFF;

    public static int   GLASS_EDGE    = 0x33FFFFFF;

    public static float HEADER_BLUR   = 30f;

    public static float HEADER_H      = 76f;

    public static float HEADER_GAP    = 12f;

    public static boolean DEBUG_HEADER = false;

    public static int   HEADER_TINT   = 0x33FFFFFF;

    public static int   HEADER_LINE_COLOR = 0x1F8E8E93;
    public static float HEADER_LINE_H     = 1f;

    public static float FOOTER_H   = 64f;

    public static float TAB_SIZE   = 15f;

    public static int   TAB_SEL    = 0xFF007AFF;

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static int statusBarH(Context c) {
        try {
            if (c instanceof android.app.Activity) {
                android.view.WindowInsets wi = ((android.app.Activity) c).getWindow()
                        .getDecorView().getRootWindowInsets();
                if (wi != null) {
                    int h = wi.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                    if (h > 0) return h;
                }
            }
        } catch (Throwable ignored) {}
        try {
            int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(c, 28f);
    }

    public static int navBarH(Context c) {
        try {
            if (c instanceof android.app.Activity) {
                android.view.WindowInsets wi = ((android.app.Activity) c).getWindow()
                        .getDecorView().getRootWindowInsets();
                if (wi != null) {
                    int h = wi.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                    if (h > 0) return h;
                }
            }
        } catch (Throwable ignored) {}
        try {
            int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(c, 24f);
    }

    private static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static GradientDrawable roundRect(int fill, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static class Switch extends View {
        public interface OnChange { void onChange(boolean value); }

        public interface OnToggle { void onToggle(boolean value); }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean checked;
        private float anim = 0f;
        private OnChange cb;

        public Switch(Context c) {
            super(c);
            setClickable(true);
        }

        public void setChecked(boolean v) {
            if (checked == v) return;
            checked = v;
            anim = v ? 1f : 0f;
            invalidate();
            if (cb != null) cb.onChange(checked);
        }

        public boolean isChecked() { return checked; }

        public Switch setOnChange(OnChange c) { this.cb = c; return this; }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), SWITCH_W), dp(getContext(), SWITCH_H));
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float r = h / 2f;

            int off = SWITCH_OFF, on = ACCENT;
            int trackColor = blend(off, on, anim);
            paint.setColor(trackColor);
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, r, r, paint);

            float pad = dp(getContext(), KNOB_PAD);
            float kr = r - pad;
            float cx = pad + kr + (w - 2 * (pad + kr)) * anim;
            float cy = h / 2f;
            paint.setColor(KNOB);
            cv.drawCircle(cx, cy, kr, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                checked = !checked;
                anim = checked ? 1f : 0f;
                invalidate();
                if (cb != null) cb.onChange(checked);
            }
            return true;
        }
    }

    public static class Slider extends View {
        public interface OnChange { void onChange(float value); }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX, downY;
        private boolean dragging = false;
        private float touchSlop = -1f;

        private final RectF track = new RectF();
        private float min, max, value;
        private OnChange cb;

        public Slider(Context c, float value, float min, float max) {
            super(c);
            this.min = min; this.max = max; this.value = value;
            setClickable(true);

            touchSlop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        public void setValue(float v) {
            value = Math.max(min, Math.min(max, v));
            invalidate();
        }
        public float getValue() { return value; }
        public Slider setOnChange(OnChange c) { this.cb = c; return this; }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), SLIDER_W), dp(getContext(), SWITCH_H));
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float trackH = dp(getContext(), TRACK_H);
            float th = dp(getContext(), THUMB_R);
            float cy = h / 2f;
            float left = th, right = w - th;
            float frac = (max > min) ? (value - min) / (max - min) : 0f;

            paint.setColor(TRACK);
            track.set(left, cy - trackH / 2f, right, cy + trackH / 2f);
            cv.drawRoundRect(track, trackH / 2f, trackH / 2f, paint);

            float cx = left + (right - left) * frac;
            paint.setColor(ACCENT);
            track.set(left, cy - trackH / 2f, cx, cy + trackH / 2f);
            cv.drawRoundRect(track, trackH / 2f, trackH / 2f, paint);

            paint.setColor(KNOB);
            cv.drawCircle(cx, cy, th, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(getContext(), 0.5f));
            paint.setColor(0x1F000000);
            cv.drawCircle(cx, cy, th, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:

                    downX = e.getX(); downY = e.getY();
                    dragging = false;

                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) {
                        float dx = Math.abs(e.getX() - downX);
                        float dy = Math.abs(e.getY() - downY);
                        if (dx < touchSlop && dy < touchSlop) return true;
                        if (dy > dx) {

                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                            dragging = false;
                            return false;
                        }
                        dragging = true;
                    }
                    applyX(e.getX());
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (dragging) {
                        applyX(e.getX());
                        if (cb != null) cb.onChange(value);
                    }
                    dragging = false;
                    return true;
            }
            return true;
        }

        private void applyX(float x) {
            float th = dp(getContext(), THUMB_R);
            float left = th, right = getWidth() - th;
            float frac = (right > left) ? (x - left) / (right - left) : 0f;
            frac = Math.max(0f, Math.min(1f, frac));
            value = min + (max - min) * frac;
            invalidate();
            if (cb != null) cb.onChange(value);
        }
    }

    public static class Card extends android.widget.FrameLayout {
        private final LinearLayout inner;
        private StaticGlass glass;

        public Card(Context c) {
            super(c);
            setBackground(roundRect(CARD_FILL, RADIUS, c));
            inner = new LinearLayout(c);
            inner.setOrientation(LinearLayout.VERTICAL);
            addView(inner, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            inner.setPadding(dp(c, PAD), dp(c, PAD / 2), dp(c, PAD), dp(c, PAD / 2));
        }

        @Override public void addView(View child, int index, ViewGroup.LayoutParams params) {
            if (inner != null && child != inner) { inner.addView(child, params); return; }
            super.addView(child, index, params);
        }
        @Override public void addView(View child) {
            if (inner != null && child != inner) { inner.addView(child); return; }
            super.addView(child);
        }

        public void enableGlass(float radiusDp, int tintColor) {
            if (glass != null) return;
            setBackground(null);

            glass = new StaticGlass(getContext(), radiusDp, tintColor, RADIUS);
            super.addView(glass, 0, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            glass.start();
        }
    }

    public static class Row extends LinearLayout {
        public Row(Context c, String title, View right, boolean divider) {
            super(c);
            setOrientation(VERTICAL);

            LinearLayout line = new LinearLayout(c);
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(dp(c, ROW_H));

            TextView tv = text(c, title, BODY_SIZE, TEXT_PRIMARY);
            line.addView(tv, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (right != null) line.addView(right);
            addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            if (divider) {
                View d = new View(c);
                d.setBackgroundColor(DIVIDER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
                d.setLayoutParams(lp);
                addView(d);
            }
        }

        public Row setOnToggle(final Switch.OnToggle cb) {
            final View right = findSwitch(this);
            if (right instanceof Switch) {

                ((Switch) right).setOnChange(value -> {
                    if (cb != null) cb.onToggle(value);
                });
            }

            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> {
                if (right instanceof Switch) {
                    Switch sw = (Switch) right;
                    sw.setChecked(!sw.isChecked());
                }
            });
            return this;
        }

        public void attachBelow(View below) {
            if (below == null) return;
            addView(below, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private static View findSwitch(ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof Switch) return c;
                if (c instanceof ViewGroup) {
                    View r = findSwitch((ViewGroup) c);
                    if (r != null) return r;
                }
            }
            return null;
        }

        public Row indent(Context c, float dpExtra) {
            View v = getChildAt(0);
            if (v instanceof LinearLayout) {
                LinearLayout line = (LinearLayout) v;
                if (line.getChildCount() > 0) {
                    View title = line.getChildAt(0);
                    title.setPadding(dp(c, dpExtra), title.getPaddingTop(),
                            title.getPaddingRight(), title.getPaddingBottom());
                }
            }
            return this;
        }
    }

    public static boolean blur(View v, float radiusDp) {
        if (v == null) return false;
        return Effects.selfBlur(v, radiusDp);
    }

    public static boolean blur(View v) { return blur(v, BLUR_RADIUS); }

    public static void blurBehind(View target, View sample, float radiusDp, boolean sync) {
        if (!(target instanceof ViewGroup) || sample == null) return;
        ViewGroup tg = (ViewGroup) target;
        for (int i = tg.getChildCount() - 1; i >= 0; i--) {
            if (tg.getChildAt(i) instanceof GlassView) tg.removeViewAt(i);
        }
        LiveGlass g = new LiveGlass(target.getContext(), sample, radiusDp, HEADER_TINT, 0f);
        tg.addView(g, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (sync) g.start();
    }

    public static void blurBehind(View target, View sample) {
        blurBehind(target, sample, BLUR_RADIUS, true);
    }

    public static void glass(View v, float radiusDp) {
        if (v == null) return;
        v.setBackground(new GlassDrawable(v.getContext(), radiusDp, GLASS_TINT));
    }

    public static void clearEffects(View v) {
        if (v == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try { v.setRenderEffect(null); } catch (Throwable ignored) {}
        }
    }

    static final class Backdrop {

        static android.graphics.Bitmap src;

        static View anchor;

        static final float[] MAP = new float[3];

        private Backdrop() {}

        static void setSource(android.graphics.Bitmap b, View anchorView) {
            if (src != b) {
                Effects.logDiag("壁纸登记: " + (b == null ? "null"
                        : b.getWidth() + "x" + b.getHeight()
                          + " id=" + Integer.toHexString(System.identityHashCode(b))));
            }
            src = b;
            if (anchorView != null) anchor = anchorView;
        }

        static boolean ready() {
            return src != null && !src.isRecycled();
        }

        static boolean mapping() {
            View a = anchor;
            if (a == null || !ready()) return false;
            int aw = a.getWidth(), ah = a.getHeight();
            if (aw <= 0 || ah <= 0) return false;
            int bw = src.getWidth(), bh = src.getHeight();
            if (bw <= 0 || bh <= 0) return false;
            float sc = Math.max((float) aw / bw, (float) ah / bh);
            MAP[0] = sc;
            MAP[1] = (aw - bw * sc) / 2f;
            MAP[2] = (ah - bh * sc) / 2f;
            return true;
        }

        static void drawInto(Canvas cv, int relX, int relY, Paint p) {
            if (!mapping()) return;
            cv.save();
            cv.translate(MAP[1] - relX, MAP[2] - relY);
            cv.scale(MAP[0], MAP[0]);
            cv.drawBitmap(src, 0, 0, p);
            cv.restore();
        }
    }

    public static class BackdropView extends View {

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        public BackdropView(Context c) {
            super(c);
            p.setDither(true);
        }

        public void setBitmap(android.graphics.Bitmap b) {
            Backdrop.setSource(b, this);
            invalidate();
        }

        public android.graphics.Bitmap getBitmap() { return Backdrop.src; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            if (!Backdrop.ready()) return;
            Backdrop.drawInto(cv, 0, 0, p);
        }
    }

    public static void clearBackdrop() {
        Backdrop.setSource(null, null);
    }

    public static abstract class GlassView extends android.widget.FrameLayout {

        final float radiusDp;
        final int tint;
        final float cornerDp;

        final Painter painter;

        int relX = Integer.MIN_VALUE, relY = Integer.MIN_VALUE;

        final Paint srcPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        private boolean started;

        GlassView(Context c, float radiusDp, int tint, float cornerDp) {
            super(c);
            this.radiusDp = radiusDp;
            this.tint = tint;
            this.cornerDp = cornerDp;
            srcPaint.setDither(true);

            setClipToOutline(true);
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    int w = v.getWidth(), h = v.getHeight();
                    if (w <= 0 || h <= 0) { o.setEmpty(); return; }
                    o.setRoundRect(0, 0, w, h,
                            Math.max(0f, dp(v.getContext(), GlassView.this.cornerDp)));
                }
            });

            painter = new Painter(this);
            addView(painter, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            if (((tint >>> 24) != 0) || ((GLASS_EDGE >>> 24) != 0)) {
                View skin = new View(c);
                skin.setClickable(false);
                skin.setBackground(new GlassDrawable(c, cornerDp, tint));
                addView(skin, new LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }

            applyNativeBlur();
        }

        private void applyNativeBlur() {
            if (android.os.Build.VERSION.SDK_INT < 31) {
                Effects.logDiag("API<31：系统无 RenderEffect，玻璃层降级为不模糊");
                return;
            }
            float px = Math.max(0.1f, dp(getContext(), radiusDp));
            try {
                painter.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        px, px, android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable t) {
                Effects.logDiag("setRenderEffect 失败: " + t);
            }
        }

        public void start() {
            if (started) return;
            started = true;
            Effects.logDiag("玻璃层启动: " + getClass().getSimpleName()
                    + " r=" + radiusDp + "dp corner=" + cornerDp
                    + " 原生模糊=" + (android.os.Build.VERSION.SDK_INT >= 31));
            final Runnable bind = () -> {
                View sc = scrollSource();
                GlassSync.register(this);
                GlassSync.attach(sc != null ? sc : this);
                cacheLocation();
                doTick();
            };
            if (isAttachedToWindow()) {
                bind.run();
            } else {
                addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) { bind.run(); }
                    @Override public void onViewDetachedFromWindow(View v) { }
                });
            }
            addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> doTick());
        }

        View scrollSource() {
            android.view.ViewParent p = getParent();
            while (p != null) {
                if (p instanceof android.widget.ScrollView) return (View) p;
                p = p.getParent();
            }
            return null;
        }

        final void cacheLocation() {
            try {
                View bd = Backdrop.anchor;
                if (bd == null) return;
                getLocationInWindow(GlassSync.LOC2);
                bd.getLocationInWindow(GlassSync.LOC1);
                relX = GlassSync.LOC2[0] - GlassSync.LOC1[0];
                relY = GlassSync.LOC2[1] - GlassSync.LOC1[1];
            } catch (Throwable ignored) {}
        }

        final void doTick() {
            cacheLocation();
            painter.invalidate();
        }

        void onTick() { doTick(); }

        abstract void drawSource(Canvas cv);

        static final class Painter extends View {
            private final GlassView owner;
            Painter(GlassView owner) {
                super(owner.getContext());
                this.owner = owner;
                setWillNotDraw(false);
            }
            @Override protected void onDraw(Canvas cv) { owner.drawSource(cv); }
        }
    }

    public static class StaticGlass extends GlassView {

        public StaticGlass(Context c, float radiusDp, int tint, float cornerDp) {
            super(c, radiusDp, tint, cornerDp);
        }

        @Override void drawSource(Canvas cv) {
            if (relX == Integer.MIN_VALUE) cacheLocation();
            Backdrop.drawInto(cv, relX, relY, srcPaint);
        }
    }

    public static class LiveGlass extends GlassView {

        private static final int SCALE = 4;

        private final View content;
        private final int[] locA = new int[2];
        private final int[] locB = new int[2];
        private final android.graphics.Rect dst = new android.graphics.Rect();
        private android.graphics.Bitmap buf;

        public LiveGlass(Context c, View content, float radiusDp, int tint, float cornerDp) {
            super(c, radiusDp, tint, cornerDp);
            this.content = content;
        }

        @Override View scrollSource() { return content; }

        @Override void drawSource(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (relX == Integer.MIN_VALUE) cacheLocation();

            int sw = Math.max(1, w / SCALE), sh = Math.max(1, h / SCALE);
            if (buf == null || buf.isRecycled()
                    || buf.getWidth() != sw || buf.getHeight() != sh) {
                if (buf != null) buf.recycle();
                buf = android.graphics.Bitmap.createBitmap(
                        sw, sh, android.graphics.Bitmap.Config.ARGB_8888);
            }
            buf.eraseColor(CANVAS);
            Canvas cc = new Canvas(buf);
            cc.scale(1f / SCALE, 1f / SCALE);

            if (Backdrop.ready()) Backdrop.drawInto(cc, relX, relY, srcPaint);

            if (content != null) {
                content.getLocationInWindow(locA);
                getLocationInWindow(locB);
                int tx = locA[0] - locB[0];
                int ty = locA[1] - locB[1];
                View target = content;
                if (content instanceof android.widget.ScrollView) {
                    android.widget.ScrollView sv = (android.widget.ScrollView) content;

                    tx -= sv.getScrollX();
                    ty -= sv.getScrollY();
                    if (sv.getChildCount() > 0) target = sv.getChildAt(0);
                }
                cc.save();
                cc.translate(tx, ty);
                target.draw(cc);
                cc.restore();
            }
            cc.setBitmap(null);

            dst.set(0, 0, w, h);
            cv.drawBitmap(buf, null, dst, srcPaint);
        }
    }

    static final class GlassSync {

        static final int[] LOC1 = new int[2], LOC2 = new int[2];

        private static final java.util.ArrayList<java.lang.ref.WeakReference<GlassView>> VIEWS =
                new java.util.ArrayList<>();
        private static final java.util.WeakHashMap<View, Boolean> WATCHED =
                new java.util.WeakHashMap<>();
        private static android.view.ViewTreeObserver hookedTvo;
        private static View hooked;
        private static android.view.ViewTreeObserver.OnScrollChangedListener hookListener;
        private static boolean pending;
        private static long pendingAt;
        private static int ticks;

        private GlassSync() {}

        static void register(GlassView v) {
            prune();
            for (java.lang.ref.WeakReference<GlassView> r : VIEWS) {
                if (r.get() == v) return;
            }
            VIEWS.add(new java.lang.ref.WeakReference<>(v));
        }

        static void attach(final View sc) {
            if (sc == null) return;
            hook(sc);
            if (WATCHED.put(sc, Boolean.TRUE) == null) {
                sc.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) { hook(v); }
                    @Override public void onViewDetachedFromWindow(View v) { }
                });
            }
        }

        private static void hook(final View sc) {
            android.view.ViewTreeObserver tvo = sc.getViewTreeObserver();
            if (hooked == sc && hookedTvo == tvo) return;
            if (hookedTvo != null && hookListener != null) {
                try { hookedTvo.removeOnScrollChangedListener(hookListener); } catch (Throwable ignored) {}
            }
            pending = false;
            hookListener = null;
            hooked = sc;
            hookedTvo = tvo;
            hookListener = () -> {

                long now = android.os.SystemClock.uptimeMillis();
                if (pending && now - pendingAt < 96L) return;
                pending = true;
                pendingAt = now;
                sc.post(() -> { pending = false; tick(); });
            };
            tvo.addOnScrollChangedListener(hookListener);
            Effects.logDiag("滚动监听挂载: sv@" + Integer.toHexString(System.identityHashCode(sc))
                    + " tvo@" + Integer.toHexString(System.identityHashCode(tvo)));
        }

        private static void tick() {
            prune();
            ticks++;
            int alive = 0;
            for (int i = 0; i < VIEWS.size(); i++) {
                GlassView v = VIEWS.get(i).get();
                if (v == null || !v.isAttachedToWindow()) continue;
                v.onTick();
                alive++;
            }
            if (ticks <= 3 || ticks % 120 == 0) {
                Effects.logDiag("tick#" + ticks + " 层数=" + alive + " scrollY="
                        + (hooked instanceof android.widget.ScrollView
                            ? ((android.widget.ScrollView) hooked).getScrollY() : -1));
            }
        }

        static void refresh() {
            prune();
            for (int i = 0; i < VIEWS.size(); i++) {
                GlassView v = VIEWS.get(i).get();
                if (v == null || !v.isAttachedToWindow()) continue;
                v.doTick();
            }
        }

        private static void prune() {
            for (int i = VIEWS.size() - 1; i >= 0; i--) {
                if (VIEWS.get(i).get() == null) VIEWS.remove(i);
            }
        }
    }

    static final class GlassDrawable extends android.graphics.drawable.Drawable {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rr = new RectF();
        private final float radius;

        GlassDrawable(Context c, float cornerDp, int tint) {
            radius = dp(c, cornerDp);
            fill.setColor(tint);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, dp(c, 0.5f)));
            edge.setColor(GLASS_EDGE);
        }

        @Override public void draw(Canvas cv) {
            android.graphics.Rect b = getBounds();
            rr.set(b.left, b.top, b.right, b.bottom);
            cv.drawRoundRect(rr, radius, radius, fill);
            cv.drawRoundRect(rr, radius, radius, edge);
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { fill.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    static final class Effects {

        static boolean selfBlur(View v, float r) {
            if (android.os.Build.VERSION.SDK_INT < 31) return false;
            try {
                float px = Math.max(0.1f, dp(v.getContext(), r));
                v.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        px, px, android.graphics.Shader.TileMode.CLAMP));
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        static boolean FILE_LOGGING = false;
        private static final java.util.concurrent.atomic.AtomicBoolean BUSY =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        static void logDiag(String s) {
            android.util.Log.i("SoftUi", s);
            if (!FILE_LOGGING) return;
            if (!BUSY.compareAndSet(false, true)) return;
            try {
                for (String d : new String[]{
                        "/storage/emulated/0/Download",
                        "/sdcard/Download",
                        "/storage/emulated/0"}) {
                    java.io.File dir = new java.io.File(d);
                    if (!dir.exists() || !dir.canWrite()) continue;
                    java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(
                                    new java.io.File(dir, "UiStartup.log"), true),
                            "UTF-8");
                    w.write("[fx] " + s + "\n");
                    w.flush();
                    w.close();
                    return;
                }
            } catch (Throwable ignored) {
            } finally {
                BUSY.set(false);
            }
        }
    }

    public static LinearLayout screen(Context c, String title) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CANVAS);
        root.setPadding(dp(c, SIDE), dp(c, TITLE_TOP), dp(c, SIDE), dp(c, SIDE));

        if (title != null && !title.isEmpty()) {
            TextView t = text(c, title, TITLE_SIZE, TEXT_PRIMARY);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(c, TITLE_BOTTOM);
            root.addView(t, lp);
        }
        return root;
    }

    public static void stack(LinearLayout root, View... children) {
        Context c = root.getContext();
        for (int i = 0; i < children.length; i++) {
            View v = children[i];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(c, GAP);
            root.addView(v, lp);
        }
    }

    public static Card card(Context c, View... children) {
        Card card = new Card(c);
        for (View v : children) card.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    public static View toggle(Context c, String title, boolean checked,
                              Switch.OnChange cb) {
        return toggle(c, title, checked, cb, 0f);
    }

    public static View[] toggleWithDependents(Context c, String title, boolean checked,
                                              Switch.OnToggle cb, final View... dependents) {
        final LinearLayout holder = new LinearLayout(c);
        holder.setOrientation(LinearLayout.VERTICAL);
        for (View d : dependents) {
            holder.addView(d, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        applyFold(holder, checked, false);

        Row row = (Row) toggle(c, title, checked, null);
        row.setOnToggle(v -> {
            applyFold(holder, v, true);
            if (cb != null) cb.onToggle(v);
        });
        return new View[]{ row, holder };
    }

    public static void applyFold(final View holder, boolean visible, boolean animate) {
        if (holder == null) return;
        holder.animate().cancel();

        final ViewGroup.LayoutParams lp = holder.getLayoutParams();
        if (lp == null) {

            holder.setVisibility(visible ? View.VISIBLE : View.GONE);
            return;
        }

        if (!animate) {
            lp.height = visible ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
            holder.setLayoutParams(lp);
            holder.setVisibility(visible ? View.VISIBLE : View.GONE);
            holder.setAlpha(1f);
            return;
        }

        final int targetH = measureContentHeight(holder);

        if (visible) {

            holder.setVisibility(View.VISIBLE);
            ValueAnimator va = ValueAnimator.ofInt(0, targetH);
            va.setDuration(200);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator());
            va.addUpdateListener(a -> {
                lp.height = (int) a.getAnimatedValue();
                holder.setLayoutParams(lp);
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    holder.setLayoutParams(lp);
                }
            });
            va.start();
        } else {

            int fromH = holder.getHeight() > 0 ? holder.getHeight() : measureContentHeight(holder);
            ValueAnimator va = ValueAnimator.ofInt(fromH, 0);
            va.setDuration(180);
            va.setInterpolator(new android.view.animation.AccelerateInterpolator());
            va.addUpdateListener(a -> {
                lp.height = (int) a.getAnimatedValue();
                holder.setLayoutParams(lp);
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    lp.height = 0;
                    holder.setLayoutParams(lp);
                    holder.setVisibility(View.GONE);
                }
            });
            va.start();
        }
    }

    public static int measureContentHeight(View v) {
        try {
            int w = v.getWidth();
            if (w <= 0) {
                android.view.ViewParent p = v.getParent();
                w = (p instanceof View) ? ((View) p).getWidth() : 0;
            }
            if (w <= 0) {
                w = v.getContext().getResources().getDisplayMetrics().widthPixels;
            }
            int wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST);
            int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            v.measure(wSpec, hSpec);
            int h = v.getMeasuredHeight();
            return h > 0 ? h : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static Row toggle(Context c, String title, boolean checked,
                             Switch.OnChange cb, float extraDp) {
        Switch sw = new Switch(c);
        sw.setChecked(checked);
        if (cb != null) sw.setOnChange(cb);
        Row r = new Row(c, title, sw, false);
        if (extraDp > 0f) r.indent(c, extraDp);
        return r;
    }

    public static TextView group(Context c, String title) {
        TextView t = text(c, title, SUB_SIZE, TEXT_SECONDARY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, SIDE + 4);
        lp.topMargin = dp(c, 14);
        lp.bottomMargin = dp(c, 6);
        t.setLayoutParams(lp);
        return t;
    }

    public static Row link(Context c, String title, final OnClick cb) {
        TextView arrow = text(c, "›", BODY_SIZE + 4, TEXT_SECONDARY);
        Row r = new Row(c, title, arrow, false);
        r.setOnClickListener(v -> { if (cb != null) cb.onClick(); });
        return r;
    }

    public static TextView statusText(Context c, String s) {
        return text(c, s == null ? "" : s, SUB_SIZE, TEXT_SECONDARY);
    }

    public static Row action(Context c, String title, View right, final OnClick cb) {
        Row r = new Row(c, title, right, false);
        r.setOnClickListener(v -> { if (cb != null) cb.onClick(); });
        return r;
    }

    public static View actionArrowBlock(Context c, String title, String subtitle, final OnClick cb) {
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = text(c, title == null ? "" : title, BODY_SIZE, TEXT_PRIMARY);
        t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (subtitle != null && subtitle.length() > 0) {
            TextView t2 = text(c, subtitle, SUB_SIZE, TEXT_SECONDARY);
            t2.setSingleLine(false);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.topMargin = dp(c, 2f);
            texts.addView(t2, lp2);
        }
        line.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = text(c, "›", BODY_SIZE + 4, TEXT_SECONDARY);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(c, 6f);
        line.addView(arrow, alp);

        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setMinimumHeight(dp(c, ROW_H));
        outer.setGravity(Gravity.CENTER_VERTICAL);
        outer.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.setOnClickListener(v -> { if (cb != null) cb.onClick(); });
        return outer;
    }

    public static Row actionArrow(Context c, String title, View right, final OnClick cb) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        if (right != null) wrap.addView(right);
        TextView arrow = text(c, "›", BODY_SIZE + 4, TEXT_SECONDARY);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(c, 6f);
        wrap.addView(arrow, alp);
        Row r = new Row(c, title, wrap, false);
        r.setOnClickListener(v -> { if (cb != null) cb.onClick(); });
        return r;
    }

    public static View info(Context c, String title, String value) {
        TextView v = text(c, value == null ? "" : value, SUB_SIZE, TEXT_SECONDARY);
        return new Row(c, title, v, false);
    }

    public static View infoBlock(Context c, String title, String subtitle) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setMinimumHeight(dp(c, ROW_H));
        col.setGravity(Gravity.CENTER_VERTICAL);

        TextView t1 = text(c, title == null ? "" : title, BODY_SIZE, TEXT_PRIMARY);
        col.addView(t1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (subtitle != null && subtitle.length() > 0) {
            TextView t2 = text(c, subtitle, SUB_SIZE, TEXT_SECONDARY);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.topMargin = dp(c, 2f);
            col.addView(t2, lp2);
        }
        return col;
    }

    public static View infoBlock(Context c, String title, String subtitle, final OnClick cb) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setMinimumHeight(dp(c, ROW_H));
        col.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = text(c, title == null ? "" : title, BODY_SIZE, TEXT_PRIMARY);
        texts.addView(t1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (subtitle != null && subtitle.length() > 0) {
            TextView t2 = text(c, subtitle, SUB_SIZE, TEXT_SECONDARY);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.topMargin = dp(c, 2f);
            texts.addView(t2, lp2);
        }
        line.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = text(c, "›", BODY_SIZE + 4, TEXT_SECONDARY);
        line.addView(arrow);

        col.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.setOnClickListener(v -> { if (cb != null) cb.onClick(); });
        return col;
    }

    public interface OnClick { void onClick(); }

    public static View slider(Context c, String title, float value,
                              float min, float max, final String unit,
                              Slider.OnChange cb) {
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);

        final TextView val = text(c, fmt(value) + (unit == null ? "" : unit),
                SUB_SIZE, TEXT_SECONDARY);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.rightMargin = dp(c, 8);
        right.addView(val, vlp);

        final Slider sl = new Slider(c, value, min, max);
        if (cb != null) sl.setOnChange(cb);
        sl.setOnChange(new Slider.OnChange() {
            @Override public void onChange(float v) {
                val.setText(fmt(v) + (unit == null ? "" : unit));
                if (cb != null) cb.onChange(v);
            }
        });
        right.addView(sl);

        return new Row(c, title, right, false);
    }

    private static String fmt(float v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.valueOf(Math.round(v));
    }

    public static android.widget.FrameLayout header(Context c, String title, final View sample) {

        final android.widget.FrameLayout bar = new android.widget.FrameLayout(c);
        bar.setClickable(false);
        bar.setFocusable(false);
        bar.setWillNotDraw(false);
        bar.setBackgroundColor(0x00000000);

        TextView t = text(c, title, TITLE_SIZE, TEXT_PRIMARY);
        android.widget.FrameLayout.LayoutParams tlp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.BOTTOM | Gravity.START;
        tlp.leftMargin = dp(c, SIDE);
        tlp.bottomMargin = dp(c, 10);
        bar.addView(t, tlp);

        bar.setBackgroundColor(0x00000000);

        bar.post(() -> {
            try {

                blurBehind(bar, sample, HEADER_BLUR, true);
                Effects.logDiag("header: 毛玻璃（壁纸+内容）");
                Effects.logDiag("header.post: bar=" + bar.getWidth() + "x" + bar.getHeight()
                        + " children=" + bar.getChildCount());
            } catch (Throwable t2) {
                Effects.logDiag("header.post 失败: " + t2);
            }
        });
        return bar;
    }

    public static android.widget.FrameLayout headerF(Context c, String title, final View sample) {
        return headerF(c, title, sample, statusBarH(c));
    }

    public static android.widget.FrameLayout headerF(Context c, String title, final View sample,
                                                     int padTop) {
        final android.widget.FrameLayout bar = new android.widget.FrameLayout(c);
        bar.setClickable(false);
        bar.setFocusable(false);
        bar.setWillNotDraw(false);

        bar.setBackgroundColor(0x00000000);

        final android.widget.FrameLayout inner = new android.widget.FrameLayout(c);
        inner.setPadding(0, padTop, 0, 0);
        bar.addView(inner, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView t = text(c, title, TITLE_SIZE, TEXT_PRIMARY);
        android.widget.FrameLayout.LayoutParams tlp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.BOTTOM | Gravity.START;
        tlp.leftMargin = dp(c, SIDE);
        tlp.bottomMargin = dp(c, 10);
        inner.addView(t, tlp);
        t.setTag("softui:title");

        if (HEADER_LINE_H > 0f) {
            View line = new View(c);
            line.setBackgroundColor(HEADER_LINE_COLOR);
            android.widget.FrameLayout.LayoutParams llp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(c, HEADER_LINE_H));
            llp.gravity = Gravity.BOTTOM;
            bar.addView(line, llp);
        }

        bar.post(() -> {
            try {

                blurBehind(bar, sample, HEADER_BLUR, true);
                bar.setBackgroundColor(0x00000000);
                Effects.logDiag("header.post: 毛玻璃（壁纸+内容）"
                        + " bar=" + bar.getWidth() + "x" + bar.getHeight()
                        + " children=" + bar.getChildCount());
            } catch (Throwable t2) {
                Effects.logDiag("header.post 失败: " + t2);
            }
        });
        return bar;
    }

    public static View scrollingScreen(Context c, String title) {
        return scrollingScreen(c, title, null);
    }

    public static View scrollingScreen(Context c, String title,
                                       android.graphics.Bitmap bg) {
        final android.widget.FrameLayout shell = new android.widget.FrameLayout(c);
        shell.setBackgroundColor(CANVAS);

        if (bg != null && !bg.isRecycled()) {
            BackdropView backdrop = new BackdropView(c);
            backdrop.setBitmap(bg);
            shell.addView(backdrop, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        final int sbh = statusBarH(c);

        final LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(c, SIDE), sbh + dp(c, HEADER_H) + dp(c, HEADER_GAP),
                dp(c, SIDE), dp(c, SIDE));

        content.setClipToPadding(true);

        final android.widget.ScrollView sc = new android.widget.ScrollView(c);
        sc.setVerticalScrollBarEnabled(false);
        sc.setFillViewport(false);

        sc.setClickable(true);
        sc.setFocusable(true);

        sc.addView(content, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(sc, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        final android.widget.FrameLayout bar = headerF(c, title, sc, sbh);
        android.widget.FrameLayout.LayoutParams blp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        sbh + dp(c, HEADER_H));
        blp.gravity = Gravity.TOP;
        shell.addView(bar, blp);

        Effects.logDiag("scrollingScreen 构建: HEADER_H(dp)=" + HEADER_H
                + " statusBar(px)=" + sbh
                + " padTop(px)=" + (sbh + dp(c, HEADER_H) + dp(c, HEADER_GAP))
                + " headerH(px)=" + (sbh + dp(c, HEADER_H))
                + " gap(px)=" + dp(c, HEADER_GAP));
        return shell;
    }

    public static LinearLayout contentOf(View scrollingScreen) {

        if (scrollingScreen instanceof android.view.ViewGroup) {
            android.view.ViewGroup shell = (android.view.ViewGroup) scrollingScreen;
            for (int i = 0; i < shell.getChildCount(); i++) {
                View ch = shell.getChildAt(i);
                if (ch instanceof android.widget.ScrollView) {
                    android.widget.ScrollView sc = (android.widget.ScrollView) ch;
                    if (sc.getChildCount() > 0
                            && sc.getChildAt(0) instanceof LinearLayout) {
                        return (LinearLayout) sc.getChildAt(0);
                    }
                }
            }
        }
        return null;
    }

    public interface OnTab { void onTab(int index); }

    public static class TabBar extends android.widget.FrameLayout {

        private final TextView[] labels;
        private final android.widget.FrameLayout inner;
        private int index;

        public TabBar(Context c, String[] items, final OnTab cb) {
            super(c);
            inner = new android.widget.FrameLayout(c);
            addView(inner, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            inner.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));

            int n = (items == null) ? 0 : items.length;
            labels = new TextView[n];
            for (int i = 0; i < n; i++) {
                final int idx = i;
                TextView t = text(c, items[i], TAB_SIZE, TEXT_SECONDARY);
                t.setGravity(Gravity.CENTER);
                t.setOnClickListener(v -> {
                    select(idx);
                    if (cb != null) cb.onTab(idx);
                });
                row.addView(t, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f));
                labels[i] = t;
            }
            if (n > 0) applySelection();
        }

        public void setBottomInset(int px) {
            inner.setPadding(0, 0, 0, Math.max(0, px));
        }

        public void select(int i) {
            if (labels.length == 0) return;
            index = Math.max(0, Math.min(labels.length - 1, i));
            applySelection();
        }

        public int selected() { return index; }

        private void applySelection() {
            for (int i = 0; i < labels.length; i++) {
                labels[i].setTextColor(i == index ? TAB_SEL : TEXT_SECONDARY);
            }
        }
    }

    public static void attachFooter(View shell, TabBar bar, float heightDp) {
        if (!(shell instanceof android.widget.FrameLayout) || bar == null) return;
        android.widget.FrameLayout fl = (android.widget.FrameLayout) shell;
        Context c = bar.getContext();
        int nav = navBarH(c);
        bar.setBottomInset(nav);

        if (HEADER_LINE_H > 0f) {
            View line = new View(c);
            line.setBackgroundColor(HEADER_LINE_COLOR);
            android.widget.FrameLayout.LayoutParams llp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(c, HEADER_LINE_H));
            llp.gravity = Gravity.TOP;
            bar.addView(line, llp);
        }

        int h = dp(c, heightDp) + nav;
        fl.addView(bar, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM));

        LinearLayout content = contentOf(shell);
        if (content != null) {
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), h + dp(c, SIDE));
        }

        android.widget.ScrollView sc = scrollerOf(shell);
        if (sc != null) blurBehind(bar, sc, HEADER_BLUR, true);
    }

    public static android.widget.ScrollView scrollerOf(View shell) {
        if (shell instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) shell;
            for (int i = 0; i < g.getChildCount(); i++) {
                View ch = g.getChildAt(i);
                if (ch instanceof android.widget.ScrollView) {
                    return (android.widget.ScrollView) ch;
                }
            }
        }
        return null;
    }

    public static void scrollToTop(View shell) {
        android.widget.ScrollView sc = scrollerOf(shell);
        if (sc != null) sc.scrollTo(0, 0);
    }

    public static void setTitle(View root, String title) {
        if (root == null || title == null) return;
        View t = root.findViewWithTag("softui:title");
        if (t instanceof TextView) ((TextView) t).setText(title);
    }

    public static void refreshGlass() {
        GlassSync.refresh();
    }

    private static int blend(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.argb(a, r, g, b);
    }
}