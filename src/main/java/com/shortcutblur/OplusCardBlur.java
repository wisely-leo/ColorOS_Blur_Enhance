package com.shortcutblur;

import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

final class OplusCardBlur {

    private static final String TAG = "CARDBLUR";
    private static final String CLS_VRM = "com.oplus.view.ViewRootManager";

    private static volatile Constructor<?> sCtor;
    private static volatile Method sGetDrawable;
    private static volatile Method sSetRadius;
    private static volatile Method sSetColor;
    private static volatile Method sSetCorner;
    private static volatile boolean sReady;
    private static volatile boolean sTried;

    private static final Map<View, WeakReference<Object>> sMgrOf = new WeakHashMap<>();

    private static final int MAX_RETRY = 4;
    private static final long RETRY_MS = 260L;

    private OplusCardBlur() {}

    private static final Map<View, Long> sAppliedSig = new WeakHashMap<>();

    private static boolean ensureReflection() {
        if (sReady) return true;
        if (sTried && !sReady) return false;
        synchronized (OplusCardBlur.class) {

            if (sReady) return true;
            if (sTried && !sReady) return false;
            sTried = true;
            try {
                Class<?> cls = Reflect.loadClass(CLS_VRM, null);
                if (cls == null) cls = Class.forName(CLS_VRM);
                sCtor = cls.getConstructor(View.class);
                sGetDrawable = cls.getMethod("getBackgroundBlurDrawable");
                sSetRadius = cls.getMethod("setBlurRadius", int.class);
                try { sSetColor = cls.getMethod("setColor", int.class); } catch (Throwable ignored) {}
                try { sSetCorner = cls.getMethod("setCornerRadius", float.class); } catch (Throwable ignored) {}
                sReady = (sCtor != null && sGetDrawable != null && sSetRadius != null);
                ModuleLog.d(TAG, "OplusCardBlur reflection ready=" + sReady
                        + " setColor=" + (sSetColor != null)
                        + " setCorner=" + (sSetCorner != null));
            } catch (Throwable t) {
                sReady = false;
                ModuleLog.e(TAG, "OplusCardBlur ensureReflection fail", t);
            }
            return sReady;
        }
    }

    static void apply(View v) {
        apply(v, -1f);
    }

    static void apply(final View v, final float radiusPx) {
        if (v == null || !FeatureFlags.SHORTCUT_BLUR) return;
        v.post(new Runnable() {
            @Override public void run() {
                doApply(v, radiusPx, 0);
            }
        });
    }

    private static void doApply(final View v, final float radiusPx, final int retry) {
        if (v == null || !FeatureFlags.SHORTCUT_BLUR) return;
        if (!ensureReflection()) return;
        if (retry > 0) {
            try { if (!v.isAttachedToWindow()) return; } catch (Throwable ignore) {}
        }
        try {
            Object mgr = null;
            WeakReference<Object> wr = sMgrOf.get(v);
            if (wr != null) mgr = wr.get();

            final boolean cachedMgr = (mgr != null);
            Drawable d;
            if (mgr == null) {
                mgr = sCtor.newInstance(v);
                d = (Drawable) sGetDrawable.invoke(mgr);
                if (d == null) {
                    if (retry < MAX_RETRY) {
                        ModuleLog.dv(TAG, "OplusCardBlur drawable null, retry#" + (retry + 1)
                                + " cls=" + v.getClass().getSimpleName());
                        v.postDelayed(new Runnable() {
                            @Override public void run() { doApply(v, radiusPx, retry + 1); }
                        }, RETRY_MS);
                    } else {
                        ModuleLog.dv(TAG, "OplusCardBlur give up (not attached?) cls="
                                + v.getClass().getSimpleName());
                    }
                    return;
                }
                sMgrOf.put(v, new WeakReference<>(mgr));
            } else {
                d = (Drawable) sGetDrawable.invoke(mgr);
                if (d == null) return;
            }

            float r = radiusPx;
            if (r < 0f) r = FeatureFlags.SHORTCUT_BLUR_RADIUS;
            int ri = (int) r;
            if (ri < 0) ri = 0;
            if (ri > 500) ri = 500;
            int color = isDark(v) ? FeatureFlags.CARD_BLUR_COLOR_DARK
                                  : FeatureFlags.CARD_BLUR_COLOR_LIGHT;

            float cornerPx = resolveCornerPx(v);

            long sig = ri;
            sig = sig * 31 + color;
            sig = sig * 31 + Float.floatToIntBits(cornerPx);
            if (cachedMgr) {
                Long prev;
                synchronized (sAppliedSig) { prev = sAppliedSig.get(v); }
                if (prev != null && prev.longValue() == sig) return;
            }

            sSetRadius.invoke(mgr, ri);

            if (sSetColor != null) {
                try { sSetColor.invoke(mgr, color); } catch (Throwable ignored) {}
            }

            if (sSetCorner != null) {
                try { sSetCorner.invoke(mgr, cornerPx); } catch (Throwable ignored) {}
            }

            v.setBackground(d);
            v.invalidate();
            synchronized (sAppliedSig) { sAppliedSig.put(v, sig); }
            ModuleLog.dv(TAG, "OplusCardBlur applied r=" + ri + " corner=" + cornerPx
                    + " cls=" + v.getClass().getSimpleName()
                    + " " + v.getWidth() + "x" + v.getHeight());
        } catch (Throwable t) {
            ModuleLog.e(TAG, "OplusCardBlur doApply failed", t);
        }
    }

    private static boolean isDark(View v) {
        try {
            int mode = v.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private static float resolveCornerPx(View v) {

        try {
            float dp = FeatureFlags.CARD_BLUR_CORNER_DP;
            if (dp > 0f) {
                float px = dp * v.getResources().getDisplayMetrics().density;
                return clampCorner(v, px);
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> c = v.getClass();
            while (c != null && c != View.class) {
                if ("com.coui.appcompat.poplist.RoundFrameLayout".equals(c.getName())) {
                    float f = readFloatField(c, v, "f");
                    float e = readFloatField(c, v, "e");
                    float r = (f != 0f) ? f : e;
                    if (r > 0f) return clampCorner(v, r);
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return 0f;
    }

    private static float readFloatField(Class<?> cls, Object target, String name) {
        try {
            java.lang.reflect.Field f = Reflect.field(cls, name);
            if (f == null) return 0f;
            return f.getFloat(target);
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static float clampCorner(View v, float r) {
        int w = v.getWidth(), h = v.getHeight();
        if (w > 0 && h > 0) {
            float maxSafe = Math.min(w, h) / 2f - 1f;
            if (maxSafe < 0f) maxSafe = 0f;
            if (r > maxSafe) r = maxSafe;
        }
        return r;
    }
}
