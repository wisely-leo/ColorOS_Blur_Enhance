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

    public static int DISABLED_TRACK  = 0xFFE0E0E0;
    public static int DISABLED_ACCENT = 0xFFBDBDBD;
    public static int DISABLED_KNOB   = 0xFFF0F0F0;

    public static int INPUT_FILL      = 0x14000000;
    public static int INPUT_STROKE    = 0x33808080;
    public static float RADIUS      = 22f;
    public static float ROW_H       = 48f;
    public static float PAD         = 16f;

    public static final int ROW_TITLE_ID = 0x7F00A001;
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
    public static int   HEADER_TINT   = 0x33FFFFFF;
    public static int   HEADER_LINE_COLOR = 0x1F8E8E93;
    public static float HEADER_LINE_H     = 1f;

    public static int   PRESS_FILL        = 0x14000000;
    public static int   PRESS_FILL_STRONG = 0x1F000000;
    public static float PRESS_RADIUS      = 22f;
    public static float FOOTER_H   = 64f;
    public static float TAB_SIZE   = 15f;
    public static int   TAB_SEL    = 0xFF007AFF;

    public static int   DIALOG_FILL = 0xF2FFFFFF;

    public static int   SCRIM       = 0x4D000000;

    private static final int EXTRA_PAD_TAG = 0x7F00B101;

    public static int   BTN_SUBTLE      = 0x14000000;
    public static int   BTN_SUBTLE_PRESS = 0x28000000;

    public static boolean DARK = false;

    public static void initTheme(Context c) {
        initTheme(c, 0);
    }

    public static void initTheme(Context c, int mode) {
        if (mode == 1) { applyTheme(false); return; }
        if (mode == 2) { applyTheme(true); return; }
        int sys = c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        applyTheme(sys == android.content.res.Configuration.UI_MODE_NIGHT_YES);
    }

    public static boolean resolveDark(Context c, int mode) {
        if (mode == 1) return false;
        if (mode == 2) return true;
        int sys = c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return sys == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public static void applyTheme(boolean dark) {
        DARK = dark;
        if (dark) {
            CANVAS         = 0xFF000000;
            SURFACE        = 0xFF1C1C1E;
            CARD_FILL      = SURFACE;
            ACCENT         = 0xFF0A84FF;
            TEXT_PRIMARY   = 0xFFE6E6EB;
            TEXT_SECONDARY = 0x99A8A8AE;
            DIVIDER        = 0x5C545458;
            SWITCH_OFF     = 0xFF39393D;
            TRACK          = 0xFF39393D;
            KNOB           = 0xFFFFFFFF;
            DISABLED_TRACK  = 0xFF2C2C2E;
            DISABLED_ACCENT = 0xFF48484A;
            DISABLED_KNOB   = 0xFF636366;
            INPUT_FILL      = 0x1FFFFFFF;
            INPUT_STROKE    = 0x44FFFFFF;
            GLASS_TINT     = 0x33000000;
            GLASS_EDGE     = 0x33FFFFFF;
            HEADER_TINT    = 0x33000000;
            HEADER_LINE_COLOR = 0x1FFFFFFF;
            PRESS_FILL        = 0x1AFFFFFF;
            PRESS_FILL_STRONG = 0x2EFFFFFF;
            TAB_SEL        = 0xFF0A84FF;
            DIALOG_FILL    = 0xF21C1C1E;
            SCRIM          = 0x66000000;
            BTN_SUBTLE     = 0x1AFFFFFF;
            BTN_SUBTLE_PRESS = 0x33FFFFFF;
        } else {
            CANVAS         = 0xFFF2F2F7;
            SURFACE        = 0xFFFFFFFF;
            CARD_FILL      = SURFACE;
            ACCENT         = 0xFF007AFF;
            TEXT_PRIMARY   = 0xFF000000;
            TEXT_SECONDARY = 0x993C3C43;
            DIVIDER        = 0x5C3C3C43;
            SWITCH_OFF     = 0xFFE9E9EA;
            TRACK          = 0xFFE9E9EA;
            KNOB           = 0xFFFFFFFF;
            DISABLED_TRACK  = 0xFFE0E0E0;
            DISABLED_ACCENT = 0xFFB0B0B5;
            DISABLED_KNOB   = 0xFFCCCCCF;
            INPUT_FILL      = 0x14000000;
            INPUT_STROKE    = 0x33808080;
            GLASS_TINT     = 0x33FFFFFF;
            GLASS_EDGE     = 0x33FFFFFF;
            HEADER_TINT    = 0x33FFFFFF;
            HEADER_LINE_COLOR = 0x1F8E8E93;
            PRESS_FILL        = 0x14000000;
            PRESS_FILL_STRONG = 0x1F000000;
            TAB_SEL        = 0xFF007AFF;
            DIALOG_FILL    = 0xF2FFFFFF;
            SCRIM          = 0x4D000000;
            BTN_SUBTLE     = 0x14000000;
            BTN_SUBTLE_PRESS = 0x28000000;
        }
    }
    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static void pressFeedback(View v) {
        pressFeedback(v, PRESS_FILL, PRESS_RADIUS, true, true);
    }

    public static void pressFeedback(View v, int fillColor, float radiusDp,
                                     boolean roundTop, boolean roundBottom) {
        if (v == null) return;

        final Context c = v.getContext();
        final int ex = dp(c, PAD);
        final int topEx = roundTop ? dp(c, PAD / 2f) : 0;
        final int botEx = roundBottom ? dp(c, PAD / 2f) : 0;
        final float rt = roundTop ? radiusDp : 0f;
        final float rb = roundBottom ? radiusDp : 0f;
        android.graphics.drawable.StateListDrawable sl =
                new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed},
                inset(pressShape(c, fillColor, rt, rb), -ex, -topEx, -ex, -botEx));
        sl.addState(new int[]{android.R.attr.state_focused},
                inset(pressShape(c, fillColor, rt, rb), -ex, -topEx, -ex, -botEx));
        sl.addState(new int[]{},
                inset(pressShape(c, 0x00000000, rt, rb), -ex, -topEx, -ex, -botEx));
        v.setBackground(sl);
        v.setClickable(true);
        v.setFocusable(true);

try { v.setTag("softui_press".hashCode(), Boolean.TRUE); } catch (Throwable ignored) {}
    }

    private static android.graphics.drawable.Drawable inset(
            android.graphics.drawable.Drawable d, int l, int t, int r, int b) {
        return new android.graphics.drawable.InsetDrawable(d, l, t, r, b);
    }

    private static android.graphics.drawable.GradientDrawable pressShape(
            Context c, int color, float rTopDp, float rBottomDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(color);
        int rt = dp(c, rTopDp), rb = dp(c, rBottomDp);
        g.setCornerRadii(new float[]{
                rt, rt,
                rt, rt,
                rb, rb,
                rb, rb
        });
        return g;
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
        private ValueAnimator animator;
        private boolean dragging = false;
        private float downX = 0f;
        private float touchSlop;
        private float pressScale = 1f;
        private ValueAnimator scaleAnimator;
        public Switch(Context c) {
            super(c);
            setClickable(true);
            touchSlop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }
        public void setChecked(boolean v) {
            if (checked == v) return;
            checked = v;
            animateTo(v ? 1f : 0f);
            if (cb != null) cb.onChange(checked);
        }
        public void setCheckedImmediate(boolean v) {
            if (animator != null) { animator.cancel(); animator = null; }
            checked = v;
            anim = v ? 1f : 0f;
            invalidate();
        }
        public boolean isChecked() { return checked; }
        public Switch setOnChange(OnChange c) { this.cb = c; return this; }

        private void animatePress(boolean down) {
            final float target = down ? 1.12f : 1.0f;
            if (scaleAnimator != null) scaleAnimator.cancel();
            scaleAnimator = ValueAnimator.ofFloat(pressScale, target);
            scaleAnimator.setDuration(down ? 130L : 180L);
            scaleAnimator.setInterpolator(down
                    ? new android.view.animation.DecelerateInterpolator()
                    : new android.view.animation.OvershootInterpolator(1.6f));
            scaleAnimator.addUpdateListener(a -> {
                pressScale = (Float) a.getAnimatedValue();
                invalidate();
            });
            scaleAnimator.start();
        }
        private void animateTo(float target) {
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(anim, target);
            animator.setDuration(180L);
            animator.addUpdateListener(a -> {
                anim = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }
        private void snapTo(float target) {
            if (animator != null) { animator.cancel(); animator = null; }
            anim = target;
            invalidate();
        }
        private void commitFromAnim() {
            boolean target = anim >= 0.5f;
            snapTo(target ? 1f : 0f);
            if (target != checked) {
                checked = target;
                if (cb != null) cb.onChange(checked);
            }
        }
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
            cv.drawCircle(cx, cy, kr * pressScale, paint);
        }
        private float knobMinX() {
            float h = getHeight();
            float kr = h / 2f - dp(getContext(), KNOB_PAD);
            return dp(getContext(), KNOB_PAD) + kr;
        }
        private float knobTravel() {
            float w = getWidth(), h = getHeight();
            float r = h / 2f;
            float pad = dp(getContext(), KNOB_PAD);
            float kr = r - pad;
            return w - 2 * (pad + kr);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (animator != null) { animator.cancel(); animator = null; }
                    dragging = false;
                    downX = e.getX();
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    animatePress(true);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX;
                    if (!dragging && Math.abs(dx) > touchSlop) {
                        dragging = true;
                    }
                    if (dragging) {
                        float travel = knobTravel();
                        if (travel > 0f) {
                            float raw = (e.getX() - knobMinX()) / travel;
                            anim = Math.max(0f, Math.min(1f, raw));
                            invalidate();
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    if (dragging) {
                        commitFromAnim();
                    } else {
                        checked = !checked;
                        animateTo(checked ? 1f : 0f);
                        if (cb != null) cb.onChange(checked);
                    }
                    animatePress(false);
                    dragging = false;
                    return true;
                }
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
        private float pressScale = 0.82f;
        private ValueAnimator scaleAnimator;
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
        public Slider setOnChange(OnChange c) { this.cb = c; return this; }

        private void animatePress(boolean active) {
            final float target = active ? 1.12f : 0.82f;
            if (scaleAnimator != null) scaleAnimator.cancel();
            scaleAnimator = ValueAnimator.ofFloat(pressScale, target);
            scaleAnimator.setDuration(active ? 130L : 200L);
            scaleAnimator.setInterpolator(active
                    ? new android.view.animation.DecelerateInterpolator()
                    : new android.view.animation.OvershootInterpolator(1.4f));
            scaleAnimator.addUpdateListener(a -> {
                pressScale = (Float) a.getAnimatedValue();
                invalidate();
            });
            scaleAnimator.start();
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), SLIDER_W), dp(getContext(), SWITCH_H));
        }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float trackH = dp(getContext(), TRACK_H);
            float maxTh = dp(getContext(), THUMB_R) * 1.12f;
            float th = dp(getContext(), THUMB_R) * pressScale;
            float cy = h / 2f;
            float left = maxTh, right = w - maxTh;
            float frac = (max > min) ? (value - min) / (max - min) : 0f;
            boolean en = isEnabled();
            int cTrack = en ? TRACK : DISABLED_TRACK;
            int cAccent = en ? ACCENT : DISABLED_ACCENT;
            int cKnob = en ? KNOB : DISABLED_KNOB;
            paint.setColor(cTrack);
            track.set(left, cy - trackH / 2f, right, cy + trackH / 2f);
            cv.drawRoundRect(track, trackH / 2f, trackH / 2f, paint);
            float cx = left + (right - left) * frac;
            paint.setColor(cAccent);
            track.set(left, cy - trackH / 2f, cx, cy + trackH / 2f);
            cv.drawRoundRect(track, trackH / 2f, trackH / 2f, paint);
            paint.setColor(cKnob);
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
                    animatePress(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) {
                        float dx = Math.abs(e.getX() - downX);
                        float dy = Math.abs(e.getY() - downY);
                        if (dx < touchSlop && dy < touchSlop) return true;
                        if (dy > dx) {
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                            dragging = false;
                            animatePress(false);
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
                    animatePress(false);
                    return true;
            }
            return true;
        }
        private void applyX(float x) {
            float th = dp(getContext(), THUMB_R) * 1.12f;
            float left = th, right = getWidth() - th;
            float frac = (right > left) ? (x - left) / (right - left) : 0f;
            frac = Math.max(0f, Math.min(1f, frac));
            value = min + (max - min) * frac;
            invalidate();
            if (cb != null) cb.onChange(value);
        }
    }

    public static class ColorSlider extends View {
        public interface OnColorChange { void onChange(int color, float hue01); }
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.graphics.LinearGradient rainbow;
        private float downX, downY;
        private boolean dragging = false;
        private float touchSlop = -1f;
        private final RectF track = new RectF();

        private float frac = 0f;
        private OnColorChange cb;
        private float pressScale = 0.82f;
        private ValueAnimator scaleAnimator;
        private boolean enabledColor = true;

        public ColorSlider(Context c, float startFrac) {
            super(c);
            this.frac = Math.max(0f, Math.min(1f, startFrac));
            setClickable(true);
            touchSlop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        public void setFrac(float f) { frac = Math.max(0f, Math.min(1f, f)); invalidate(); }
        public ColorSlider setOnChange(OnColorChange c) { this.cb = c; return this; }

        public int currentColor() {
            if (!enabledColor) return 0x9E9E9E;
            return android.graphics.Color.HSVToColor(new float[]{ frac * 360f, 0.72f, 0.88f });
        }

        private void animatePress(boolean active) {
            final float target = active ? 1.12f : 0.82f;
            if (scaleAnimator != null) scaleAnimator.cancel();
            scaleAnimator = ValueAnimator.ofFloat(pressScale, target);
            scaleAnimator.setDuration(active ? 130L : 200L);
            scaleAnimator.addUpdateListener(a -> { pressScale = (Float) a.getAnimatedValue(); invalidate(); });
            scaleAnimator.start();
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), SLIDER_W), dp(getContext(), SWITCH_H));
        }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float trackH = dp(getContext(), Math.max(TRACK_H, 8f));
            float maxTh = dp(getContext(), THUMB_R) * 1.12f;
            float th = dp(getContext(), THUMB_R) * pressScale;
            float cy = h / 2f;
            float left = maxTh, right = w - maxTh;
            track.set(left, cy - trackH / 2f, right, cy + trackH / 2f);
            if (rainbow == null) {
                int[] colors = new int[37];
                for (int i = 0; i <= 36; i++) colors[i] = android.graphics.Color.HSVToColor(new float[]{ i * 10f, 0.72f, 0.88f });
                rainbow = new android.graphics.LinearGradient(left, 0, right, 0, colors, null,
                        android.graphics.Shader.TileMode.CLAMP);
            }
            paint.setShader(enabledColor ? rainbow : null);
            if (!enabledColor) paint.setColor(0xFFD0D0D0);
            float r = trackH / 2f;
            cv.drawRoundRect(track, r, r, paint);
            paint.setShader(null);

            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(getContext(), 0.7f));
            border.setColor(0x22000000);
            cv.drawRoundRect(track, r, r, border);

            float cx = left + (right - left) * frac;
            if (enabledColor) {
                paint.setColor(android.graphics.Color.HSVToColor(new float[]{ frac * 360f, 0.72f, 0.88f }));
            } else {
                paint.setColor(0xFFBDBDBD);
            }
            cv.drawCircle(cx, cy, th, paint);
            border.setStrokeWidth(dp(getContext(), 1.5f));
            border.setColor(0xFFFFFFFF);
            cv.drawCircle(cx, cy, th, border);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY();
                    dragging = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    animatePress(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) {
                        float dx = Math.abs(e.getX() - downX);
                        float dy = Math.abs(e.getY() - downY);
                        if (dx < touchSlop && dy < touchSlop) return true;
                        if (dy > dx) {
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                            dragging = false; animatePress(false); return false;
                        }
                        dragging = true;
                    }
                    applyX(e.getX());
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (dragging) { applyX(e.getX()); fire(); }
                    dragging = false;
                    animatePress(false);
                    return true;
            }
            return true;
        }
        private void applyX(float x) {
            float th = dp(getContext(), THUMB_R) * 1.12f;
            float left = th, right = getWidth() - th;
            float f = (right > left) ? (x - left) / (right - left) : 0f;
            frac = Math.max(0f, Math.min(1f, f));
            invalidate();
            fire();
        }
        private void fire() {
            if (cb != null) cb.onChange(currentColor(), frac);
        }
    }

    public static View colorSlider(Context c, String title, float startFrac,
                                   ColorSlider.OnColorChange cb) {
        ColorSlider cs = new ColorSlider(c, startFrac);
        if (cb != null) cs.setOnChange(cb);
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(cs);
        Row row = new Row(c, title, right, false);
        row.post(() -> {
            android.view.ViewParent p = row.getParent();
            while (p instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) p;
                g.setClipChildren(false); g.setClipToPadding(false);
                p = g.getParent();
            }
        });
        return row;
    }

    public static ColorSlider findColorSlider(View root) {
        if (root instanceof ColorSlider) return (ColorSlider) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                ColorSlider s = findColorSlider(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    public static Switch findSwitchView(View root) {
        if (root instanceof Switch) return (Switch) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                Switch sw = findSwitchView(g.getChildAt(i));
                if (sw != null) return sw;
            }
        }
        return null;
    }

    public static void setToggleChecked(View row, boolean checked, boolean fireCallback) {
        if (row == null) return;
        Switch sw = findSwitchView(row);
        if (sw == null) return;
        if (fireCallback) sw.setChecked(checked);
        else sw.setCheckedImmediate(checked);
    }

    public static void setToggleChecked(View row, boolean checked) {
        setToggleChecked(row, checked, false);
    }

    public interface ColorInputCb { void onColor(int rgb, boolean valid); }

    public static android.widget.EditText findEditText(View root) {
        if (root instanceof android.widget.EditText) return (android.widget.EditText) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.EditText e = findEditText(g.getChildAt(i));
                if (e != null) return e;
            }
        }
        return null;
    }

    public static void colorPickerDialog(final View anchor, String title, int initialRgb,
                                         final ColorInputCb cb) {
        showPanel(anchor, title, 16f, new PanelBuilder() {
            @Override public void build(ViewGroup host, final Context ctx,
                                        final LinearLayout inner, android.widget.FrameLayout panel,
                                        final Runnable dismiss) {

                LinearLayout line = new LinearLayout(ctx);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                final View preview = new View(ctx);
                final android.graphics.drawable.GradientDrawable pv =
                        new android.graphics.drawable.GradientDrawable();
                pv.setCornerRadius(dp(ctx, 12f));
                pv.setColor(0xFF000000 | (initialRgb & 0xFFFFFF));
                pv.setStroke(dp(ctx, 1f), INPUT_STROKE);
                preview.setBackground(pv);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(ctx, 48f), dp(ctx, 48f));
                plp.rightMargin = dp(ctx, 12f);
                line.addView(preview, plp);

                final android.widget.EditText et = new android.widget.EditText(ctx);
                et.setText(String.format("%06X", initialRgb & 0xFFFFFF));
                et.setTextSize(BODY_SIZE + 4f);
                et.setTextColor(TEXT_PRIMARY);
                et.setSingleLine(true);
                et.setGravity(Gravity.CENTER);
                et.setHint("RRGGBB");
                et.setHintTextColor(TEXT_SECONDARY);
                et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
                final android.graphics.drawable.GradientDrawable etBg =
                        new android.graphics.drawable.GradientDrawable();
                etBg.setCornerRadius(dp(ctx, 12f));
                etBg.setColor(INPUT_FILL);
                etBg.setStroke(dp(ctx, 1.6f), ACCENT);
                et.setBackground(etBg);
                int ep = (int) dp(ctx, 14f);
                et.setPadding(ep, ep, ep, ep);
                et.setFilters(new android.text.InputFilter[]{
                        new android.text.InputFilter.LengthFilter(7),
                        new android.text.InputFilter() {
                            @Override public CharSequence filter(CharSequence src, int st, int en,
                                                                 android.text.Spanned dst, int dstart, int dend) {
                                StringBuilder sb = new StringBuilder();
                                for (int i2 = st; i2 < en; i2++) {
                                    char ch = Character.toUpperCase(src.charAt(i2));
                                    if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F') || ch == '#') sb.append(ch);
                                }
                                return sb.toString();
                            }
                        }
                });
                line.addView(et, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                LinearLayout.LayoutParams lnlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lnlp.bottomMargin = dp(ctx, 12f);
                inner.addView(line, lnlp);

                final int[] result = { initialRgb & 0xFFFFFF, 0 };
                final java.util.function.Consumer<String> eval = raw0 -> {
                    String raw = raw0.trim();
                    if (raw.startsWith("#")) raw = raw.substring(1);
                    if (raw.length() == 6) {
                        try {
                            int rgb = (int) Long.parseLong(raw, 16) & 0xFFFFFF;
                            result[0] = rgb; result[1] = 1;
                            pv.setColor(0xFF000000 | rgb);
                            preview.setBackground(pv);
                            return;
                        } catch (Throwable ignored) {}
                    }
                    result[1] = 0;
                    pv.setColor(0xFF9E9E9E);
                    preview.setBackground(pv);
                };
                et.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s2, int a, int b, int c2) {}
                    @Override public void onTextChanged(CharSequence s2, int a, int b, int c2) {}
                    @Override public void afterTextChanged(android.text.Editable s2) { eval.accept(s2.toString()); }
                });

                LinearLayout btnRow = new LinearLayout(ctx);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                btnRow.setGravity(Gravity.BOTTOM);
                TextView btnCancel = dialogButton(ctx, "取消", TEXT_SECONDARY, false, true);
                btnCancel.setOnClickListener(v -> dismiss.run());
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(ctx, 46f), 1f);
                clp.rightMargin = dp(ctx, 8f);
                btnRow.addView(btnCancel, clp);
                TextView btnOk = dialogButton(ctx, "确定", ACCENT, true, true);
                btnOk.setOnClickListener(v -> {
                    dismiss.run();
                    eval.accept(et.getText().toString());
                    if (result[1] == 1 && cb != null) cb.onColor(result[0], true);
                });
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, dp(ctx, 46f), 1f);
                olp.leftMargin = dp(ctx, 8f);
                btnRow.addView(btnOk, olp);
                LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                brlp.topMargin = dp(ctx, 12f);
                inner.addView(btnRow, brlp);

                et.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            et.requestFocus();
                            et.setSelection(et.getText().length());
                            android.view.inputmethod.InputMethodManager imm =
                                    (android.view.inputmethod.InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null) imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        } catch (Throwable ignored) {}
                    }
                }, 260);
            }
        });
    }

    public static void setRowTitle(View row, String title) {
        if (row == null || title == null) return;
        View v = findRowTitle(row);
        if (v instanceof TextView) ((TextView) v).setText(title);
    }

    private static View findRowTitle(View v) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof TextView && c.getId() == ROW_TITLE_ID) return c;
                View r = findRowTitle(c);
                if (r != null) return r;
            }
        }
        return null;
    }

    public static Slider findSlider(View root) {
        if (root instanceof Slider) return (Slider) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                Slider s = findSlider(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    public static void setRowEnabled(View row, boolean enabled) {
        if (row == null) return;
        Slider s = findSlider(row);
        if (s != null) s.setEnabled(enabled);
        ColorSlider cs = findColorSlider(row);
        if (cs != null) cs.setEnabled(enabled);

        setEnabledRecursive(row, enabled);
        row.setAlpha(enabled ? 1f : 0.45f);
    }

    private static void setEnabledRecursive(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                setEnabledRecursive(g.getChildAt(i), enabled);
            }
        }
    }

    public static void setRowValue(View row, float v) {
        if (row == null) return;
        Slider s = findSlider(row);
        if (s != null) s.setValue(v);
    }

    public static class Card extends android.widget.FrameLayout {
        private final LinearLayout inner;
        private StaticGlass glass;
        public Card(Context c) {
            super(c);
            setBackground(roundRect(CARD_FILL, RADIUS, c));

            setClipToOutline(true);
            inner = new LinearLayout(c);
            inner.setOrientation(LinearLayout.VERTICAL);
            addView(inner, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            inner.setPadding(dp(c, PAD), dp(c, PAD / 2f), dp(c, PAD), dp(c, PAD / 2f));

            inner.setClipToPadding(false);
        }
        private static final int TAG_PRESS = "softui_press".hashCode();

        public void applyEdgeInsets(float insetDp) {
            final int n = inner.getChildCount();
            for (int i = 0; i < n; i++) {
                View ch = inner.getChildAt(i);
                Object tag = ch.getTag(TAG_PRESS);
                if (!(tag instanceof Boolean) || !((Boolean) tag)) continue;
                boolean t = (i == 0), b = (i == n - 1);
                String pos = (t && b) ? "single" : (t ? "first" : (b ? "last" : "mid"));
                applyPressShape(ch, pos);

                View p = (ch instanceof ViewGroup) ? ch : null;
                if (p != null) {
                    ((ViewGroup) p).setClipChildren(false);
                    ((ViewGroup) p).setClipToPadding(false);
                }
                android.view.ViewParent vp = ch.getParent();
                while (vp instanceof ViewGroup && vp != inner) {
                    ((ViewGroup) vp).setClipChildren(false);
                    ((ViewGroup) vp).setClipToPadding(false);
                    vp = ((View) vp).getParent();
                }
            }
            inner.setClipChildren(false);
        }

        static void applyPressShape(View v, String pos) {
            int fill = SoftUi.PRESS_FILL;
            float r = SoftUi.PRESS_RADIUS;
            boolean rt, rb;
            switch (pos) {
                case "single": rt = true;  rb = true;  break;
                case "first":  rt = true;  rb = false; break;
                case "last":   rt = false; rb = true;  break;
                default:       rt = false; rb = false; break;
            }
            SoftUi.pressFeedback(v, fill, r, rt, rb);
            v.setTag(TAG_PRESS, Boolean.TRUE);
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
            tv.setId(ROW_TITLE_ID);
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
        public Row setRowClickSwitch(final Switch sw) {
            if (sw == null) return this;
            setClickable(true);
            setFocusable(true);
            pressFeedback(this);
            setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
            return this;
        }

        public Switch switchView() {
            View v = findSwitch(this);
            return (v instanceof Switch) ? (Switch) v : null;
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
            pressFeedback(this);
            setOnClickListener(v -> {
                if (right instanceof Switch) {
                    Switch sw = (Switch) right;
                    sw.setChecked(!sw.isChecked());
                }
            });
            return this;
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
    static final class Backdrop {
        static android.graphics.Bitmap src;
        // anchor 必须弱引用：它是从 Activity/View 树传入的 View，
        // 若强引用会在 Activity 销毁后仍钉住整棵旧 View 树（静态字段 = GC root）。
        static java.lang.ref.WeakReference<View> anchor;
        static final float[] MAP = new float[3];
        private Backdrop() {}
        static void setSource(android.graphics.Bitmap b, View anchorView) {
            if (src != b) {
                Effects.logDiag("壁纸登记: " + (b == null ? "null"
                        : b.getWidth() + "x" + b.getHeight()
                          + " id=" + Integer.toHexString(System.identityHashCode(b))));
            }
            src = b;
            if (anchorView != null) anchor = new java.lang.ref.WeakReference<>(anchorView);
        }
        static boolean ready() {
            return src != null && !src.isRecycled();
        }
        static boolean mapping() {
            View a = anchor == null ? null : anchor.get();
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

    /** 供宿主 Activity 在销毁时调用：解除 GlassSync 的静态滚动监听与 View 引用。 */
    public static void GlassSyncUnhook() {
        try { GlassSync.unhookIfDetached(); } catch (Throwable ignored) {}
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
                View bd = Backdrop.anchor == null ? null : Backdrop.anchor.get();
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
        // hooked / hookListener 都是静态 GC root，必须保证 Activity 销毁后能被解开：
        //   - hooked 用弱引用（原先强引用会钉住最后一个 ScrollView → 整棵 View 树）
        //   - hookListener 会在 detach 时显式 remove 并置空（见 unhook()）
        private static java.lang.ref.WeakReference<View> hooked;
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
                    @Override public void onViewDetachedFromWindow(View v) {
                        // 当前观察的 View 脱离窗口 → 解除静态监听与引用，避免泄漏 View 树。
                        View cur = hooked == null ? null : hooked.get();
                        if (cur == v) unhook();
                    }
                });
            }
        }
        /** 解除当前挂载的滚动监听并清空静态引用（Activity 销毁/detach 时调用）。 */
        static void unhook() {
            if (hookedTvo != null && hookListener != null) {
                try { hookedTvo.removeOnScrollChangedListener(hookListener); } catch (Throwable ignored) {}
            }
            hookListener = null;
            hookedTvo = null;
            hooked = null;
            pending = false;
            ticks = 0;
        }

        /**
         * 仅当"当前观察的 View 已脱离窗口"时才解除。
         * 用于 Activity.onDestroy 兜底：避免旧 Activity 的 onDestroy 误清新 Activity
         * （recreate 时新 Activity 已先 attach）刚挂上的监听。
         */
        static void unhookIfDetached() {
            View cur = hooked == null ? null : hooked.get();
            if (cur == null) return;                 // 已被 GC/清空，无需处理
            if (!cur.isAttachedToWindow()) unhook(); // 只有真的脱离才解
        }
        private static void hook(final View sc) {
            android.view.ViewTreeObserver tvo = sc.getViewTreeObserver();
            View cur = hooked == null ? null : hooked.get();
            if (cur == sc && hookedTvo == tvo) return;
            if (hookedTvo != null && hookListener != null) {
                try { hookedTvo.removeOnScrollChangedListener(hookListener); } catch (Throwable ignored) {}
            }
            pending = false;
            hookListener = null;
            hooked = new java.lang.ref.WeakReference<>(sc);
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
                View h = hooked == null ? null : hooked.get();
                Effects.logDiag("tick#" + ticks + " 层数=" + alive + " scrollY="
                        + (h instanceof android.widget.ScrollView
                            ? ((android.widget.ScrollView) h).getScrollY() : -1));
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
        for (View v : children) {

            int lm = 0, rm = 0;
            ViewGroup.LayoutParams old = v.getLayoutParams();
            if (old instanceof LinearLayout.LayoutParams) {
                lm = ((LinearLayout.LayoutParams) old).leftMargin;
                rm = ((LinearLayout.LayoutParams) old).rightMargin;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = lm;
            lp.rightMargin = rm;
            card.addView(v, lp);
        }

        card.applyEdgeInsets(-1f);
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
        cancelFoldAnim(holder);
        final ViewGroup.LayoutParams lp = holder.getLayoutParams();
        if (lp == null) {
            holder.setVisibility(visible ? View.VISIBLE : View.GONE);
            return;
        }
        if (!animate) {

            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            holder.setLayoutParams(lp);
            holder.setVisibility(visible ? View.VISIBLE : View.GONE);
            holder.setAlpha(1f);
            reapplyEdgeInsets(holder);
            return;
        }
        final int targetH = measureContentHeight(holder);
        if (visible) {

            holder.setVisibility(View.VISIBLE);
            reapplyEdgeInsets(holder);
            holder.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                            holder.getWidth() > 0 ? holder.getWidth()
                                    : android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.View.MeasureSpec.AT_MOST),
                    android.view.View.MeasureSpec.makeMeasureSpec(0,
                            android.view.View.MeasureSpec.UNSPECIFIED));
            final int fullH = Math.max(measureContentHeight(holder), holder.getMeasuredHeight());
            final int fromH = Math.max(0, holder.getHeight());
            ValueAnimator va = ValueAnimator.ofInt(fromH, fullH);
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
            holder.setTag(FOLD_ANIM_TAG, va);
            va.start();
        } else {

            reapplyEdgeInsets(holder);
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

                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    holder.setLayoutParams(lp);
                    holder.setVisibility(View.GONE);
                }
            });
            holder.setTag(FOLD_ANIM_TAG, va);
            va.start();
        }
    }
    private static final int FOLD_ANIM_TAG = "softui_fold_anim".hashCode();

    private static void reapplyEdgeInsets(View v) {
        try {
            android.view.ViewParent p = v == null ? null : v.getParent();
            while (p instanceof android.view.View) {
                if (p instanceof Card) { ((Card) p).applyEdgeInsets(-1f); return; }
                p = ((android.view.View) p).getParent();
            }
        } catch (Throwable ignored) {}
    }
    public interface ConfirmCb {
        void onConfirm();
    }

    public static void infoDialog(View anchor, String title, String message, String closeText) {
        final String msg = message;
        final String closeLabel = (closeText == null ? "关闭" : closeText);
        showPanel(anchor, title, 12f, new PanelBuilder() {
            @Override public void build(ViewGroup host, final Context ctx,
                                        final LinearLayout inner, android.widget.FrameLayout panel,
                                        final Runnable dismiss) {
                if (msg != null && msg.length() > 0) {
                    TextView tvM = text(ctx, msg, SUB_SIZE + 2f, TEXT_SECONDARY);
                    tvM.setGravity(Gravity.START);
                    tvM.setLineSpacing(dp(ctx, 5f), 1f);
                    final android.widget.ScrollView msgScroll = new android.widget.ScrollView(ctx);
                    msgScroll.setVerticalFadingEdgeEnabled(true);
                    msgScroll.setFadingEdgeLength(dp(ctx, 20f));
                    msgScroll.setVerticalScrollBarEnabled(false);
                    msgScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                    msgScroll.addView(tvM, new android.view.ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    mlp.bottomMargin = dp(ctx, 16f);
                    inner.addView(msgScroll, mlp);

                    try {
                        int panelW = ctx.getResources().getDisplayMetrics().widthPixels - dp(ctx, 24f);
                        int contentW = panelW - dp(ctx, (PAD + 6f) * 2f);
                        tvM.measure(
                                View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.AT_MOST),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        int maxMsgH = Math.round(ctx.getResources()
                                .getDisplayMetrics().heightPixels * 0.55f);
                        int th = tvM.getMeasuredHeight();
                        if (th > maxMsgH) {
                            mlp.height = maxMsgH;
                            msgScroll.setLayoutParams(mlp);
                        }
                    } catch (Throwable ignored) {}
                }
                LinearLayout btnRow = new LinearLayout(ctx);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                TextView btnClose = dialogButton(ctx, closeLabel, ACCENT, true, true);
                btnClose.setOnClickListener(v -> dismiss.run());
                btnRow.addView(btnClose, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 46f)));
                LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                brlp.topMargin = dp(ctx, 12f);
                inner.addView(btnRow, brlp);
            }
        });
    }
    public static void confirm(View anchor, String title, String message,
                               String okText, String cancelText,
                               final ConfirmCb onConfirm) {
        confirm(anchor, title, message, okText, cancelText, onConfirm, null);
    }
    public static void confirm(View anchor, String title, String message,
                               String okText, String cancelText,
                               final ConfirmCb onConfirm, final ConfirmCb onCancel) {
        final String msg = message;
        final String cancelLabel = (cancelText == null ? "取消" : cancelText);
        final String okLabel = (okText == null ? "确定" : okText);
        showPanel(anchor, title, 12f, new PanelBuilder() {
            @Override public void build(ViewGroup host, final Context ctx,
                                        final LinearLayout inner, android.widget.FrameLayout panel,
                                        final Runnable dismiss) {
                if (msg != null && msg.length() > 0) {
                    TextView tvM = text(ctx, msg, SUB_SIZE + 2f, TEXT_SECONDARY);
                    tvM.setGravity(Gravity.CENTER);
                    tvM.setLineSpacing(dp(ctx, 5f), 1f);
                    final android.widget.ScrollView msgScroll = new android.widget.ScrollView(ctx);
                    msgScroll.setVerticalFadingEdgeEnabled(true);
                    msgScroll.setFadingEdgeLength(dp(ctx, 20f));
                    msgScroll.setVerticalScrollBarEnabled(false);
                    msgScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                    msgScroll.addView(tvM, new android.view.ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    mlp.bottomMargin = dp(ctx, 16f);
                    inner.addView(msgScroll, mlp);

                    try {
                        int panelW = ctx.getResources().getDisplayMetrics().widthPixels - dp(ctx, 24f);
                        int contentW = panelW - dp(ctx, (PAD + 6f) * 2f);
                        tvM.measure(
                                View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.AT_MOST),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        int maxMsgH = Math.round(ctx.getResources()
                                .getDisplayMetrics().heightPixels * 0.55f);
                        int th = tvM.getMeasuredHeight();
                        if (th > maxMsgH) {
                            mlp.height = maxMsgH;
                            msgScroll.setLayoutParams(mlp);
                        }
                    } catch (Throwable ignored) {}
                }
                LinearLayout btnRow = new LinearLayout(ctx);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                btnRow.setGravity(Gravity.BOTTOM);
                TextView btnCancel = dialogButton(ctx, cancelLabel, TEXT_SECONDARY, false, true);
                btnCancel.setOnClickListener(v -> {
                    dismiss.run();
                    if (onCancel != null) {
                        try { onCancel.onConfirm(); } catch (Throwable ignored) {}
                    }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(ctx, 46f), 1f);
                clp.rightMargin = dp(ctx, 8f);
                btnRow.addView(btnCancel, clp);
                TextView btnOk = dialogButton(ctx, okLabel, ACCENT, true, true);
                btnOk.setOnClickListener(v -> {
                    dismiss.run();
                    if (onConfirm != null) onConfirm.onConfirm();
                });
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, dp(ctx, 46f), 1f);
                olp.leftMargin = dp(ctx, 8f);
                btnRow.addView(btnOk, olp);
                LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                brlp.topMargin = dp(ctx, 12f);
                inner.addView(btnRow, brlp);
            }
        });
    }

    public interface PanelBuilder {
        void build(ViewGroup host, Context ctx, LinearLayout inner, android.widget.FrameLayout panel, Runnable dismiss);
    }

    public static void showPanel(final View anchor, String title, float titleBottomMarginDp,
                                 final PanelBuilder builder) {
        try {

            ViewGroup host = null;
            View v0 = anchor;
            while (v0 instanceof View) {
                if (v0 instanceof ViewGroup) host = (ViewGroup) v0;
                android.view.ViewParent pp = v0.getParent();
                if (!(pp instanceof View)) break;
                v0 = (View) pp;
            }
            if (host == null) host = resolveHost(anchor);
            if (host == null) return;
            final ViewGroup fhost = host;
            final Context ctx = host.getContext();
            final android.widget.ScrollView sample = scrollerOf(host);

            final android.widget.FrameLayout overlay = new android.widget.FrameLayout(ctx);
            overlay.setBackgroundColor(SCRIM);
            overlay.setClickable(true);
            overlay.setFocusable(true);
            final android.widget.FrameLayout panel = new android.widget.FrameLayout(ctx);
            panel.setClickable(true);
            final float corner = 28f;
            panel.setClipToOutline(true);
            panel.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    int w = v.getWidth(), h = v.getHeight();
                    if (w <= 0 || h <= 0) { o.setEmpty(); return; }
                    o.setRoundRect(0, 0, w, h, dp(v.getContext(), corner));
                }
            });
            final Runnable[] adjustPanelHeight = new Runnable[1];
            adjustPanelHeight[0] = () -> {
                try {
                    int w = panel.getWidth();
                    if (w <= 0) w = ctx.getResources().getDisplayMetrics().widthPixels - dp(ctx, 24f);
                    panel.measure(
                            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    int targetH = panel.getMeasuredHeight();

                    int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
                    int maxPanelH = screenH - dp(ctx, 40f) * 2 - navBarH(ctx);
                    if (maxPanelH < dp(ctx, 120f)) maxPanelH = screenH - dp(ctx, 40f) * 2;
                    if (targetH > maxPanelH) targetH = maxPanelH;
                    android.widget.FrameLayout.LayoutParams p2 =
                            (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
                    if (p2 != null) {
                        p2.height = targetH;
                        panel.setLayoutParams(p2);
                    }
                } catch (Throwable ignored) {}
            };

            final LinearLayout inner = new LinearLayout(ctx);
            inner.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(ctx, PAD + 6f);
            inner.setPadding(pad, pad, pad, pad);
            if (title != null && title.length() > 0) {
                TextView tvT = text(ctx, title, BODY_SIZE + 2f, TEXT_PRIMARY);
                tvT.setTypeface(tvT.getTypeface(), android.graphics.Typeface.BOLD);
                tvT.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.bottomMargin = dp(ctx, titleBottomMarginDp);
                inner.addView(tvT, tlp);
            }

            final LiveGlass[] glassRef = new LiveGlass[1];
            final Runnable dismiss = () -> dismissPanelRaw(fhost, overlay, panel, ctx, glassRef[0]);

            if (builder != null) {
                builder.build(fhost, ctx, inner, panel, dismiss);
            }

            panel.addView(inner, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final android.widget.FrameLayout.LayoutParams plp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.BOTTOM;
            plp.leftMargin = dp(ctx, 12f);
            plp.rightMargin = dp(ctx, 12f);
            final int baseBottom = dp(ctx, 40f);
            plp.bottomMargin = baseBottom;
            overlay.addView(panel, plp);

            final int[] curMargin = { baseBottom };
            overlay.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                    int ime = 0;
                    try { ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom; } catch (Throwable ignored) {}
                    final int toMargin = (ime > 0) ? ime + dp(ctx, 12f) : baseBottom;
                    if (toMargin == curMargin[0]) return insets;
                    android.animation.ValueAnimator va =
                            android.animation.ValueAnimator.ofInt(curMargin[0], toMargin);
                    curMargin[0] = toMargin;
                    va.setDuration(200);
                    va.setInterpolator(new android.view.animation.DecelerateInterpolator());
                    va.addUpdateListener(a -> {
                        plp.bottomMargin = (Integer) a.getAnimatedValue();
                        panel.setLayoutParams(plp);
                        LiveGlass g = glassRef[0];
                        if (g != null) g.doTick();
                    });
                    va.start();
                    return insets;
                }
            });

            panel.post(new Runnable() {
                @Override public void run() {
                    adjustPanelHeight[0].run();
                    if (sample != null) {
                        try {
                            LiveGlass g = new LiveGlass(ctx, sample, BLUR_RADIUS, GLASS_TINT, corner);
                            g.setClickable(false);
                            glassRef[0] = g;
                            panel.addView(g, 0, new android.widget.FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                            g.start();
                        } catch (Throwable t2) {
                            com.shortcutblur.ModuleLog.e("SoftUi", "showPanel glass failed", t2);
                            panel.setBackground(roundRect(CARD_FILL, corner, ctx));
                        }
                    } else {
                        panel.setBackground(roundRect(DIALOG_FILL, corner, ctx));
                    }
                }
            });

            overlay.requestApplyInsets();
            fhost.addView(overlay, new android.view.ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(180).start();
            panel.setTranslationY(dp(ctx, 80f));
            final android.animation.ValueAnimator enter =
                    android.animation.ValueAnimator.ofFloat(dp(ctx, 80f), 0f);
            enter.setDuration(240);
            enter.setInterpolator(new android.view.animation.DecelerateInterpolator());
            enter.addUpdateListener(a -> {
                panel.setTranslationY((Float) a.getAnimatedValue());
                LiveGlass g = glassRef[0];
                if (g != null) g.doTick();
            });
            enter.start();
        } catch (Throwable t) {
            com.shortcutblur.ModuleLog.e("SoftUi", "showPanel failed", t);
        }
    }

    private static void dismissPanelRaw(final ViewGroup host, final View overlayView,
                                        final View panelView, final Context ctx, final LiveGlass glass) {
        try {
            if (overlayView.getParent() == null) return;
            panelView.animate().translationY(dp(ctx, 80f)).setDuration(200)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .setUpdateListener(a -> { if (glass != null) glass.doTick(); })
                    .start();
            overlayView.animate().alpha(0f).setDuration(200)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator a) {
                            try { host.removeView(overlayView); } catch (Throwable ignored) {}
                        }
                    }).start();
        } catch (Throwable ignored) {}
    }

    private static ViewGroup resolveHost(View anchor) {
        if (anchor instanceof ViewGroup) return (ViewGroup) anchor;
        if (anchor != null) {
            android.view.ViewParent p = anchor.getParent();
            while (p != null) {
                if (p instanceof ViewGroup) return (ViewGroup) p;
                p = p.getParent();
            }
        }
        return null;
    }

    public static View segmentRow(Context c, String title, final String[] labels,
                                  int selected, final IntCb cb) {
        final SegmentedView seg = new SegmentedView(c, labels, selected, cb);
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(c, ROW_H));
        if (title != null && title.length() > 0) {
            TextView tv = text(c, title, BODY_SIZE, TEXT_PRIMARY);
            line.addView(tv, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            line.addView(new View(c), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                dp(c, 192f), dp(c, 32f));
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.addView(seg, slp);

        line.post(() -> {
            android.view.ViewParent p = line.getParent();
            while (p instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) p;
                g.setClipChildren(false);
                g.setClipToPadding(false);
                p = g.getParent();
            }
        });
        return line;
    }

    public interface IntCb { void run(int index); }

    public static class SegmentedView extends View {
        private final String[] labels;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final android.graphics.RectF thumb = new RectF();
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint txp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private int selected;
        private float anim;
        private ValueAnimator animator;
        private float pressScale = 1f;
        private ValueAnimator scaleAnimator;
        private final IntCb cb;
        public SegmentedView(Context c, String[] labels, int selected, IntCb cb) {
            super(c);
            this.labels = labels;
            this.selected = Math.max(0, Math.min(labels.length - 1, selected));
            this.anim = this.selected;
            this.cb = cb;
            setClickable(true);
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(getLayoutParams() != null && getLayoutParams().width > 0
                    ? getLayoutParams().width : dp(getContext(), 192f),
                    getLayoutParams() != null && getLayoutParams().height > 0
                            ? getLayoutParams().height : dp(getContext(), 32f));
        }
        private void select(int idx, boolean notify) {
            if (idx < 0 || idx >= labels.length) return;
            if (idx == selected) return;
            if (animator != null) animator.cancel();
            final int target = idx;
            animator = ValueAnimator.ofFloat(anim, idx);
            animator.setDuration(220L);
            animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animator.addUpdateListener(a -> { anim = (Float) a.getAnimatedValue(); invalidate(); });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    anim = target;
                    invalidate();

                    if (notify && cb != null) cb.run(target);
                }
            });
            animator.start();
            selected = idx;
        }

        private void animatePress(boolean down) {
            final float target = down ? 1.12f : 1.0f;
            if (scaleAnimator != null) scaleAnimator.cancel();
            scaleAnimator = ValueAnimator.ofFloat(pressScale, target);
            scaleAnimator.setDuration(down ? 130L : 180L);
            scaleAnimator.setInterpolator(down
                    ? new android.view.animation.DecelerateInterpolator()
                    : new android.view.animation.OvershootInterpolator(1.6f));
            scaleAnimator.addUpdateListener(a -> {
                pressScale = (Float) a.getAnimatedValue();
                invalidate();
            });
            scaleAnimator.start();
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            float segW = getWidth() / (float) labels.length;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:

                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = true;
                    downX = e.getX();
                    moved = false;
                    animatePress(true);

                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        if (Math.abs(e.getX() - downX) > dp(getContext(), 4f)) moved = true;
                        if (moved) snapToIndex(e.getX(), segW, false);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (dragging) {
                        dragging = false;
                        final int idx = indexAt(e.getX(), segW);
                        commitTo(idx);
                    }
                    animatePress(false);
                    performClick();
                    return true;
            }
            return true;
        }
        private boolean dragging = false;
        private float downX = 0f;
        private boolean moved = false;

        private void snapToIndex(float x, float segW, boolean notify) {
            if (animator != null) { animator.cancel(); animator = null; }
            float t = Math.max(0f, Math.min(labels.length - 1, x / segW));
            anim = t;
            invalidate();
        }
        private int indexAt(float x, float segW) {
            return Math.max(0, Math.min(labels.length - 1, (int) (x / segW)));
        }

        private void commitTo(final int idx) {
            final int clamped = Math.max(0, Math.min(labels.length - 1, idx));
            final boolean changed = (clamped != selected);
            selected = clamped;
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(anim, selected);
            animator.setDuration(180L);
            animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animator.addUpdateListener(a -> { anim = (Float) a.getAnimatedValue(); invalidate(); });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    anim = selected;
                    invalidate();
                    if (changed && cb != null) cb.run(selected);
                }
            });
            animator.start();
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float r = h / 2f;

            paint.setColor(DARK ? 0x66000000 : 0x16000000);
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, r, r, paint);

            float segW = w / labels.length;
            float pad = dp(getContext(), 2f);
            float left = pad + segW * anim;
            thumb.set(left, pad, left + (segW - pad * 2), h - pad);

            if (pressScale != 1f) {
                float cxT = thumb.centerX(), cyT = thumb.centerY();
                float hw = thumb.width() / 2f * pressScale;
                float hh = thumb.height() / 2f * pressScale;
                thumb.set(cxT - hw, cyT - hh, cxT + hw, cyT + hh);
            }
            tp.setColor(DARK ? 0xFF636366 : 0xFFFFFFFF);
            tp.setShadowLayer(dp(getContext(), 3f), 0f, dp(getContext(), 1f),
                    DARK ? 0x40000000 : 0x22000000);
            cv.drawRoundRect(thumb, thumb.height() / 2f, thumb.height() / 2f, tp);
            tp.clearShadowLayer();

            txp.setTextSize(dp(getContext(), 13.5f));
            txp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            txp.setTextAlign(Paint.Align.CENTER);
            float baseline = h / 2f - (txp.descent() + txp.ascent()) / 2f;
            for (int i = 0; i < labels.length; i++) {
                float cx = segW * i + segW / 2f;

                boolean on = Math.abs(anim - i) < 0.5f;
                txp.setColor(on ? (DARK ? 0xFFFFFFFF : 0xFF000000) : TEXT_SECONDARY);
                cv.drawText(labels[i], cx, baseline, txp);
            }
        }
    }
    private static TextView dialogButton(Context c, String label, int color,
                                         boolean filled, boolean stretch) {
        TextView t = text(c, label, BODY_SIZE, filled ? 0xFFFFFFFF : color);
        t.setGravity(Gravity.CENTER);
        final float radius = dp(c, 24f);
        int ph = stretch ? dp(c, 8f) : dp(c, 22f), pv = dp(c, 10f);
        t.setPadding(ph, pv, ph, pv);
        final int baseColor = filled ? ACCENT : BTN_SUBTLE;
        final int pressColor = filled ? darken(ACCENT, 0.85f) : BTN_SUBTLE_PRESS;
        final android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(radius);
        bg.setColor(baseColor);
        t.setBackground(bg);
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    bg.setColor(pressColor);
                    v.invalidate();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    bg.setColor(baseColor);
                    v.invalidate();
                    break;
            }
            return false;
        });
        return t;
    }
    private static int darken(int color, float k) {
        int a = (color >>> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * k);
        int g = (int) (((color >> 8) & 0xFF) * k);
        int b = (int) ((color & 0xFF) * k);
        return (a << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
    }
    private static int clamp8(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
    private static void cancelFoldAnim(View holder) {
        try {
            Object t = holder.getTag(FOLD_ANIM_TAG);
            if (t instanceof ValueAnimator) {
                ((ValueAnimator) t).cancel();
            }
            holder.setTag(FOLD_ANIM_TAG, null);
        } catch (Throwable ignored) {}
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
        return toggle(c, title, checked, cb, extraDp, true);
    }
    public static Row toggle(Context c, String title, boolean checked,
                             Switch.OnChange cb, float extraDp, boolean rowClickable) {
        Switch sw = new Switch(c);
        sw.setCheckedImmediate(checked);
        if (cb != null) sw.setOnChange(cb);
        Row r = new Row(c, title, sw, false);
        if (extraDp > 0f) r.indent(c, extraDp);
        if (rowClickable) r.setRowClickSwitch(sw);

        r.post(() -> {
            android.view.ViewParent p = r.getParent();
            while (p instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) p;
                g.setClipChildren(false);
                g.setClipToPadding(false);
                p = g.getParent();
            }
        });
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
        pressFeedback(r);
        r.setOnClickListener(v -> { if (cb != null) cb.onClick(); });
        return r;
    }
    public static TextView statusText(Context c, String s) {
        return text(c, s == null ? "" : s, SUB_SIZE, TEXT_SECONDARY);
    }
    public static Row action(Context c, String title, View right, final OnClick cb) {
        Row r = new Row(c, title, right, false);
        pressFeedback(r);
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
        pressFeedback(outer);
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
        pressFeedback(r);
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
        col.setClickable(true);
        col.setFocusable(true);
        pressFeedback(col);
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
        Row row = new Row(c, title, right, false);

        row.post(() -> {
            android.view.ViewParent p = row.getParent();
            while (p instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) p;
                g.setClipChildren(false);
                g.setClipToPadding(false);
                p = g.getParent();
            }
        });
        return row;
    }
    private static String fmt(float v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.valueOf(Math.round(v));
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

        final int basePadBottom = dp(c, SIDE);
        sc.setOnApplyWindowInsetsListener((v2, insets) -> {
            int ime = 0;
            try { ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom; } catch (Throwable ignored) {}

            int extra = 0;
            try {
                Object ex = content.getTag(EXTRA_PAD_TAG);
                if (ex instanceof Integer) extra = (Integer) ex;
            } catch (Throwable ignored) {}
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), basePadBottom + extra + ime);
            return insets;
        });
        sc.post(() -> { try { sc.requestApplyInsets(); } catch (Throwable ignored) {} });

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
        final LinearLayout content = contentOf(shell);
        if (content != null) {

            content.setTag(EXTRA_PAD_TAG, h);
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), h + dp(c, SIDE));
            android.widget.ScrollView sc0 = scrollerOf(shell);
            if (sc0 != null) sc0.post(() -> { try { sc0.requestApplyInsets(); } catch (Throwable ignored) {} });
        }
        android.widget.ScrollView sc = scrollerOf(shell);
        if (sc != null) blurBehind(bar, sc, HEADER_BLUR, true);
    }
    public static android.widget.ScrollView scrollerOf(View shell) {
        return scrollerOf(shell, 0);
    }
    private static android.widget.ScrollView scrollerOf(View shell, int depth) {
        if (depth > 8 || !(shell instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) shell;
        for (int i = 0; i < g.getChildCount(); i++) {
            View ch = g.getChildAt(i);
            if (ch instanceof android.widget.ScrollView) {
                return (android.widget.ScrollView) ch;
            }
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            View ch = g.getChildAt(i);
            android.widget.ScrollView r = scrollerOf(ch, depth + 1);
            if (r != null) return r;
        }
        return null;
    }
    public static void scrollToTop(View shell) {
        android.widget.ScrollView sc = scrollerOf(shell);
        if (sc != null) sc.scrollTo(0, 0);
    }

    public static int scrollY(View shell) {
        android.widget.ScrollView sc = scrollerOf(shell);
        return sc != null ? sc.getScrollY() : 0;
    }

    public static void scrollToY(View shell, int y) {
        android.widget.ScrollView sc = scrollerOf(shell);
        if (sc != null) sc.scrollTo(0, Math.max(0, y));
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
