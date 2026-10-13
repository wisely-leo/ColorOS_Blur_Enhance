package com.shortcutblur;

import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 快捷菜单卡片（一级 & 二级）统一的 Oplus 私有背景模糊。
 *
 * <p>走 ColorOS 官方给第三方开放的 whitelist API：{@code com.oplus.view.ViewRootManager}
 * （与 {@link ImeBlurHook} 同源），而非 {@code OplusViewBackgroundRenderEffect.setBackgroundRenderEffect}
 * 那条 RenderEffect 路径。两者底层实现不同，前者由 SurfaceFlinger 合成（川濑/高斯），
 * 观感更接近系统原生；一级与二级共用本类同一组参数，从而保证视觉一致。
 *
 * <p>用法：{@link #apply(View)} / {@link #apply(View, float)}。内部自动延迟重试（View 未 attach
 * 时 {@code getBackgroundBlurDrawable()} 返回 null），并弱引用缓存 mgr。
 */
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

    // ⚠️ value 必须弱引用：ViewRootManager 强引用其 View，若此处强引用会导致 WeakHashMap
    // 的 key 永远不可达、entry 永久滞留（典型泄漏）。被 GC 后 apply 会按需重建。
    private static final Map<View, WeakReference<Object>> sMgrOf = new WeakHashMap<>();

    // 发热优化：6 → 4。原 6 次 × 260ms = 1.56s 重试窗口；每次重试都 new ViewRootManager
    // 并反射 getDrawable。4 次（1.04s）足以覆盖常见"容器未附着"时序。
    private static final int MAX_RETRY = 4;
    private static final long RETRY_MS = 260L;

    private OplusCardBlur() {}

    // 发热优化：最近一次已应用的参数签名（避免重复设置同一组参数引发重绘）。
    private static final Map<View, Long> sAppliedSig = new WeakHashMap<>();

    private static boolean ensureReflection() {
        if (sReady) return true;
        if (sTried && !sReady) return false;
        synchronized (OplusCardBlur.class) {
            // 双检：热路径（每次 apply）在 sReady 为 true 时不再进 monitor。
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

    /** 用默认半径（FeatureFlags.SHORTCUT_BLUR_RADIUS）挂模糊。 */
    static void apply(View v) {
        apply(v, -1f);
    }

    /**
     * 给已 attach 的 View 挂 ColorOS 背景模糊（自动重试）。
     *
     * @param radiusPx 半径（px）；&lt;0 时取 FeatureFlags.SHORTCUT_BLUR_RADIUS
     */
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
            // 发热优化：记录 mgr 是否来自缓存（首次必须完整应用一次）。
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

            // 圆角：使模糊区域与卡片面板圆角对齐，避免直角糊斑露出圆角外。
            // 以配置的 dp 为准（见 resolveCornerPx 注释）。
            float cornerPx = resolveCornerPx(v);

            // 发热优化：幂等短路。
            // 二级菜单 hook 会多次调度（0 / 80ms）且在切换时重复进入本方法；
            // 参数完全一致时再设一遍 radius/color/corner/background 只会白白触发重绘。
            // 仅在 mgr 来自缓存（非首次）时才允许跳过，保证首次一定完整应用。
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

    /**
     * 解析目标 View 的圆角（px），用于让模糊 Drawable 的圆角与卡片面板对齐。
     *
     * <p>以 {@link FeatureFlags#CARD_BLUR_CORNER_DP} 为准（按密度换算）。
     * 原因：COUI {@code RoundFrameLayout} 自身圆角（约 30dp/105px）与卡片观感不匹配，
     * 之前"优先读字段"会让 dp 配置永远失效，故改为直接采用配置值。
     * 配置 &lt;= 0 时退回读取 {@code RoundFrameLayout} 的 f / e 字段（smali: f != 0 ? f : e）。
     */
    private static float resolveCornerPx(View v) {
        // ① 首选：配置的 dp 值
        try {
            float dp = FeatureFlags.CARD_BLUR_CORNER_DP;
            if (dp > 0f) {
                float px = dp * v.getResources().getDisplayMetrics().density;
                return clampCorner(v, px);
            }
        } catch (Throwable ignored) {}
        // ② 配置禁用（<=0）时，退回读取 RoundFrameLayout 自身圆角
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

    /** 圆角不得超过短边一半（否则 native 路径可能越界）。 */
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