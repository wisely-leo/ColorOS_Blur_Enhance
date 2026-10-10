package com.shortcutblur;

import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

public final class ImeBlurHook {

    private static final String TAG = "IMEBLUR";

    private static final String[] IMV_BASE_CANDIDATES = { "fx" };

    private static final String[] THEME_BG_CANDIDATES = { "xu" };

    private static volatile String sImvBaseName = null;
    private static volatile String sThemeBgName = null;

    private static volatile java.lang.ref.WeakReference<View> sLastView =
            new java.lang.ref.WeakReference<>(null);

    private static final String CLS_VRM = "com.oplus.view.ViewRootManager";

    private static volatile boolean sReflReady = false;

    // ---- 亮色描边 ----
    // 颜色：白，alpha 40%（半透明）；由外向内渐隐深度 8dp，无独立线宽
    // （整条描边带本身就是渐变，最外侧即最亮处，不存在"粗线"）。
    private static final int GLOW_COLOR = 0x66FFFFFF;
    private static final float GLOW_FEATHER_DP = 8f;

    // 系统模糊 drawable 按 View 缓存（必须复用：每次新建会导致 Aggregator
    // Add/Remove 抖动并与系统动画抢绘制）。用弱引用，避免泄漏 View。
    private static final java.util.WeakHashMap<View, Drawable> sBlurOf =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, Object> sMgrOf =
            new java.util.WeakHashMap<>();
    private static Constructor<?> sCtorViewRootManager;
    private static Method sGetBlurDrawable;
    private static Method sSetBlurRadius;
    private static Method sSetColor;
    private static Method sSetCornerRadius;

    private static volatile int sLastUiMode = -1;
    private static volatile boolean sLastDark = false;

    // ---- 悬浮键盘判据（读取语燕自己的设置，稳定、不随动画变化）----
    // 链路：Lh7;->k(静态单例) -> Lh7;->c:Ld7 -> Ld7;->l/.m:Ljz -> Ljz;->g():Boolean
    //       l = keyboard_mode_float（竖屏），m = keyboard_mode_float_landscape（横屏）
    private static volatile boolean sFloatProbeReady = false;
    private static volatile ClassLoader sHostCl = null;
    private static java.lang.reflect.Field sFldH7k;
    private static java.lang.reflect.Field sFldD7c;
    private static java.lang.reflect.Field sFldD7l;
    private static java.lang.reflect.Field sFldD7m;
    private static Method sMtdJzGet;

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Method> sSkbRootCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.Set<Class<?>> sSkbRootMissing =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>());

    private static final Object sPendingLock = new Object();
    private static volatile java.lang.ref.WeakReference<View> sPendingView =
            new java.lang.ref.WeakReference<>(null);

    private static synchronized boolean ensureReflection() {
        if (sReflReady) return true;
        try {
            Class<?> mgrCls = Class.forName(CLS_VRM);
            sCtorViewRootManager = mgrCls.getConstructor(View.class);
            sGetBlurDrawable = mgrCls.getMethod("getBackgroundBlurDrawable");
            sSetBlurRadius = mgrCls.getMethod("setBlurRadius", int.class);
            try {
                sSetColor = mgrCls.getMethod("setColor", int.class);
            } catch (Throwable ignored) {}
            try {
                sSetCornerRadius = mgrCls.getMethod("setCornerRadius", float.class);
            } catch (Throwable ignored) {}
            sReflReady = (sCtorViewRootManager != null && sGetBlurDrawable != null
                    && sSetBlurRadius != null);
            ModuleLog.d(TAG, "reflection cache ready=" + sReflReady
                    + " setColor=" + (sSetColor != null)
                    + " setCorner=" + (sSetCornerRadius != null));
            return sReflReady;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "ensureReflection fail", t);
            return false;
        }
    }

    private ImeBlurHook() {}

    // ---- 悬浮键盘判据 ----
    // 读取语燕自身的「键盘悬浮」设置（SharedPreferences 持久化值），
    // 该值与上滑手势动画无关，天然稳定，不会造成圆角抖动。
    private static synchronized void ensureFloatProbe() {
        if (sFloatProbeReady) return;
        ClassLoader cl = sHostCl;
        if (cl == null) return;   // 宿主 ClassLoader 尚未记录，下次再试
        try {
            Class<?> clsH7 = Class.forName("h7", false, cl);
            Class<?> clsD7 = Class.forName("d7", false, cl);
            Class<?> clsJz = Class.forName("jz", false, cl);
            sFldH7k = clsH7.getField("k");
            sFldD7c = clsH7.getField("c");
            sFldD7l = clsD7.getField("l");
            sFldD7m = clsD7.getField("m");
            sMtdJzGet = clsJz.getMethod("g");
            sFloatProbeReady = true;
        } catch (Throwable t) {
            sFloatProbeReady = false;
            ModuleLog.d(TAG, "float probe unavailable: " + t);
        }
        ModuleLog.d(TAG, "float probe ready=" + sFloatProbeReady);
    }

    /** 当前是否为「悬浮键盘」模式；取不到时按非悬浮处理（即不设圆角）。 */
    private static boolean isFloatKeyboard(View v) {
        if (!sFloatProbeReady) {
            ensureFloatProbe();
            if (!sFloatProbeReady) return false;
        }
        try {
            Object h7 = sFldH7k.get(null);
            if (h7 == null) return false;
            Object d7 = sFldD7c.get(h7);
            if (d7 == null) return false;
            boolean landscape = isLandscape(v);
            Object jz = (landscape ? sFldD7m : sFldD7l).get(d7);
            if (jz == null) return false;
            Object b = sMtdJzGet.invoke(jz);
            return (b instanceof Boolean) && (Boolean) b;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isLandscape(View v) {
        try {
            android.content.Context ctx = v.getContext();
            if (ctx == null) return false;
            return ctx.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Class<?> findImvBase(ClassLoader cl) {
        for (String name : IMV_BASE_CANDIDATES) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                if (c == null) continue;
                if (!android.widget.RelativeLayout.class.isAssignableFrom(c)) {
                    ModuleLog.d(TAG, "candidate " + name + " is not RelativeLayout, skip");
                    continue;
                }
                boolean hasAttach = false;
                boolean hasVis = false;
                for (Method m : c.getDeclaredMethods()) {
                    if ("onAttachedToWindow".equals(m.getName()) && m.getParameterCount() == 0) hasAttach = true;
                    if ("onVisibilityChanged".equals(m.getName()) && m.getParameterCount() == 2) hasVis = true;
                }
                if (hasAttach && hasVis) {
                    sImvBaseName = name;
                    ModuleLog.d(TAG, "imv base resolved: " + name);
                    return c;
                }
                ModuleLog.d(TAG, "candidate " + name + " missing required methods, skip");
            } catch (Throwable t) {
                ModuleLog.d(TAG, "candidate " + name + " load fail: " + t);
            }
        }
        return null;
    }

    private static Class<?> findThemeBg(ClassLoader cl, Class<?> imvBase) {
        for (String name : THEME_BG_CANDIDATES) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                if (c == null) continue;
                if (imvBase != null && !imvBase.isAssignableFrom(c)) {
                    ModuleLog.d(TAG, "candidate " + name + " is not subclass of imv base, skip");
                    continue;
                }
                for (Method m : c.getDeclaredMethods()) {
                    if ("u".equals(m.getName()) && m.getParameterCount() == 0) {
                        sThemeBgName = name;
                        ModuleLog.d(TAG, "theme bg resolved: " + name);
                        return c;
                    }
                }
                ModuleLog.d(TAG, "candidate " + name + " has no u(), skip");
            } catch (Throwable t) {
                ModuleLog.d(TAG, "candidate " + name + " load fail: " + t);
            }
        }
        return null;
    }

    public static void install(final BlurEnhanceModule mod,
                               XposedModuleInterface.PackageReadyParam param) {
        final ClassLoader cl = param.getClassLoader();
        if (cl == null) return;

        installYuyan(mod, cl);
    }

    private static void installYuyan(final BlurEnhanceModule mod, final ClassLoader cl) {
        try {
            sHostCl = cl;   // 供悬浮键盘判据反射使用
            Class<?> base = findImvBase(cl);
            if (base == null) {
                ModuleLog.d(TAG, "imv base class not found (tried " + IMV_BASE_CANDIDATES.length + " candidates)");
                return;
            }
            final String baseName = sImvBaseName;

            Executable target = null;
            for (Method m : base.getDeclaredMethods()) {
                if ("onAttachedToWindow".equals(m.getName()) && m.getParameterCount() == 0) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                ModuleLog.d(TAG, "onAttachedToWindow not found in " + baseName);
                return;
            }

            mod.hook(target)
                    .setId("ime.attach")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof View) {
                                    ModuleLog.d(TAG, "onAttachedToWindow -> applyBlur");
                                    applyBlur((View) self);
                                }
                            } catch (Throwable t) {
                                ModuleLog.e(TAG, "applyBlur fail", t);
                            }
                            return r;
                        }
                    });
            ModuleLog.d(TAG, "hooked " + baseName + ".onAttachedToWindow");

            // 键盘根 View 脱离窗口时清理：把缓存的系统模糊 drawable 作废
            // （setVisible(false) → Aggregator 自行 remove），并清空缓存。
            // 否则 drawable 会一直挂在 ViewRootImpl.mBlurRegionAggregator 里，
            // 连同其 RenderNode 与整棵 View 树引用一起滞留。
            Executable detachTarget = null;
            for (Method m : base.getDeclaredMethods()) {
                if ("onDetachedFromWindow".equals(m.getName()) && m.getParameterCount() == 0) {
                    detachTarget = m;
                    break;
                }
            }
            if (detachTarget != null) {
                mod.hook(detachTarget)
                        .setId("ime.detach")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object r = chain.proceed();
                                try {
                                    Object self = chain.getThisObject();
                                    if (self instanceof View) {
                                        ModuleLog.d(TAG, "onDetachedFromWindow -> clearBlur");
                                        clearBlur();
                                    }
                                } catch (Throwable t) {
                                    ModuleLog.e(TAG, "clearBlur fail", t);
                                }
                                return r;
                            }
                        });
                ModuleLog.d(TAG, "hooked " + baseName + ".onDetachedFromWindow");
            } else {
                ModuleLog.d(TAG, "onDetachedFromWindow not found, skip");
            }

            Executable visTarget = null;
            for (Method m : base.getDeclaredMethods()) {
                if ("onVisibilityChanged".equals(m.getName()) && m.getParameterCount() == 2) {
                    visTarget = m;
                    break;
                }
            }
            if (visTarget != null) {
                final Executable vt = visTarget;
                mod.hook(vt)
                        .setId("ime.visibility")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object r = chain.proceed();
                                try {
                                    Object self = chain.getThisObject();
                                    Object a0 = chain.getArg(0);
                                    Object a1 = chain.getArg(1);
                                    int vis = (a1 instanceof Integer) ? (Integer) a1 : -1;

                                    boolean selfChanged = (a0 == self);
                                    if (self instanceof View && vis == View.VISIBLE && selfChanged) {
                                        ModuleLog.d(TAG, "onVisibilityChanged(VISIBLE) -> applyBlur");
                                        applyBlur((View) self);
                                    }
                                } catch (Throwable t) {
                                    ModuleLog.e(TAG, "visibility applyBlur fail", t);
                                }
                                return r;
                            }
                        });
                ModuleLog.d(TAG, "hooked " + baseName + ".onVisibilityChanged");
            } else {
                ModuleLog.d(TAG, "onVisibilityChanged not found, skip");
            }

            try {
                Class<?> xu = findThemeBg(cl, base);
                if (xu == null) {
                    throw new ClassNotFoundException("theme bg class not found");
                }
                Executable bgTarget = null;
                for (Method m : xu.getDeclaredMethods()) {
                    if ("u".equals(m.getName()) && m.getParameterCount() == 0) {
                        bgTarget = m;
                        break;
                    }
                }
                if (bgTarget != null) {
                    mod.hook(bgTarget)
                            .setId("ime.theme.bg")
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object r = chain.proceed();
                                    try {
                                        Object self = chain.getThisObject();
                                        if (self instanceof View) {
                                            ModuleLog.d(TAG, sThemeBgName + ".u() -> re-applyBlur after theme bg");
                                            applyBlur((View) self);
                                        }
                                    } catch (Throwable t) {
                                        ModuleLog.e(TAG, "theme bg applyBlur fail", t);
                                    }
                                    return r;
                                }
                            });
                    ModuleLog.d(TAG, "hooked " + sThemeBgName + ".u (theme bg)");
                } else {
                    ModuleLog.d(TAG, sThemeBgName + ".u not found, skip");
                }
            } catch (Throwable t) {
                ModuleLog.d(TAG, "hook " + sThemeBgName + ".u failed: " + t);
            }

            } catch (Throwable t) {
            ModuleLog.e(TAG, "install failed", t);
        }
    }

    private static void applyBlur(final View v) {
        if (v == null) return;

        sLastView = new java.lang.ref.WeakReference<>(v);

        synchronized (sPendingLock) {
            if (sPendingView.get() == v) {
                ModuleLog.d(TAG, "applyBlur: coalesced (same view pending)");
                return;
            }
            sPendingView = new java.lang.ref.WeakReference<>(v);
        }

        v.post(new Runnable() {
            @Override
            public void run() {
                synchronized (sPendingLock) {
                    if (sPendingView.get() == v) {
                        sPendingView = new java.lang.ref.WeakReference<>(null);
                    }
                }
                View root = v;

                View target = tryGetSkbRoot(root);
                if (target == null) target = root;
                doBlur(target);
            }
        });
    }

    public static void reapply() {
        View v = sLastView == null ? null : sLastView.get();
        if (v == null) {
            ModuleLog.d(TAG, "reapply: no cached view (input method not shown yet)");
            return;
        }
        ModuleLog.d(TAG, "reapply: re-applying blur to cached view");
        applyBlur(v);
    }

    public static void clearBlur() {
        // 1) 主动「作废」每个缓存的系统模糊 drawable。
        //    背景置 null 只是解除了 View 的引用，但 drawable 仍挂在
        //    ViewRootImpl.mBlurRegionAggregator.mDrawables 里（因为
        //    alpha>0 && blurRadius>0 && visible 仍成立），而 Aggregator
        //    强引用 ViewRootImpl（→ DecorView → 整棵 View 树），同时
        //    drawable 自身持有 RenderNode（native GPU 资源）。
        //    setVisible(false) 会触发 onBlurDrawableUpdated → shouldBeDrawn=false
        //    → 系统自行从 mDrawables 移除，彻底断开这条链。
        for (View v : sBlurOf.keySet()) {
            try {
                Drawable d = sBlurOf.get(v);
                if (d != null) d.setVisible(false, false);
            } catch (Throwable ignored) {}
        }
        // 2) 摘下键盘背景。
        try {
            View v = sLastView == null ? null : sLastView.get();
            if (v != null) {
                View target = tryGetSkbRoot(v);
                if (target == null) target = v;
                target.setBackground(null);
                target.invalidate();
            }
        } catch (Throwable t) {
            ModuleLog.e(TAG, "clearBlur: detach background fail", t);
        }
        // 3) 清空缓存：否则 value（ViewRootManager/drawable 链）会继续强引用
        //    ViewRootImpl，拖慢键盘 View 的回收。
        try {
            sBlurOf.clear();
            sMgrOf.clear();
            sLastView = new java.lang.ref.WeakReference<>(null);
            sPendingView = new java.lang.ref.WeakReference<>(null);
        } catch (Throwable ignored) {}
        ModuleLog.d(TAG, "clearBlur: detached background + invalidated cached drawables");
    }

    private static View tryGetSkbRoot(View v) {
        if (v == null) return null;
        try {
            Class<?> cls = v.getClass();

            if (sSkbRootMissing.contains(cls)) return null;
            Method m = sSkbRootCache.get(cls);
            if (m == null) {
                try {
                    m = cls.getMethod("getMSkbRoot");
                    if (m != null && View.class.isAssignableFrom(m.getReturnType())) {
                        sSkbRootCache.put(cls, m);
                    } else {
                        sSkbRootMissing.add(cls);
                        return null;
                    }
                } catch (Throwable noSuch) {
                    sSkbRootMissing.add(cls);
                    return null;
                }
            }
            Object r = m.invoke(v);
            if (r instanceof View) return (View) r;
        } catch (Throwable ignored) {}
        return null;
    }

    private static void doBlur(View v) {
        doBlur(v, 0);
    }

    private static void doBlur(final View v, final int retry) {
        if (v == null || !FeatureFlags.IME_BLUR) return;
        if (!ensureReflection()) {
            ModuleLog.d(TAG, "doBlur: reflection not ready, abort");
            return;
        }
        try {
            // 系统模糊 drawable 只创建一次并复用（缓存在弱键表中）。
            // 每次新建会挂到 ViewRootImpl 的 BlurRegionAggregator 上造成 Add/Remove
            // 抖动，与系统动画抢绘制，导致边缘闪烁。
            // ViewRootManager 与 drawable 必须成对缓存：参数是设置在 mgr 上的。
            Drawable d = sBlurOf.get(v);
            Object mgr = sMgrOf.get(v);
            if (d == null || mgr == null) {
                mgr = sCtorViewRootManager.newInstance(v);
                d = (Drawable) sGetBlurDrawable.invoke(mgr);
                if (d == null) {
                    if (retry < 5) {
                        ModuleLog.d(TAG, "doBlur: drawable null, retry#" + (retry + 1));
                        v.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                doBlur(v, retry + 1);
                            }
                        }, 300);
                    } else {
                        ModuleLog.d(TAG, "doBlur: drawable still null after retries, give up");
                    }
                    return;
                }
                sBlurOf.put(v, d);
                sMgrOf.put(v, mgr);
            }

            int radius = FeatureFlags.IME_BLUR_RADIUS;
            sSetBlurRadius.invoke(mgr, radius);

            int color = isDark(v) ? FeatureFlags.IME_BLUR_COLOR_DARK
                                  : FeatureFlags.IME_BLUR_COLOR_LIGHT;

            color = applyMaskAlpha(color, FeatureFlags.IME_BLUR_MASK_ALPHA);
            if (sSetColor != null) {
                try {
                    sSetColor.invoke(mgr, color);
                } catch (Throwable ignored) {}
            }

            // 圆角：仅「悬浮键盘」需要（悬浮时四角可见）；非悬浮（贴底）键盘不设圆角。
            // 判据来自语燕自身的 keyboard_mode_float 设置，不随上滑动画变化，故不会抖动。
            // 注意：必须显式下发 0 —— mgr 是缓存复用的，悬浮时设过圆角后若不重置，
            // 切回非悬浮会残留旧圆角（表现为「遗留圆角」）。
            float cornerDp = FeatureFlags.IME_BLUR_CORNER_DP;
            float cornerPx = 0f;
            boolean floating = isFloatKeyboard(v);
            if (cornerDp > 0f && sSetCornerRadius != null) {
                cornerPx = floating
                        ? cornerDp * v.getResources().getDisplayMetrics().density
                        : 0f;
                try {
                    sSetCornerRadius.invoke(mgr, cornerPx);
                } catch (Throwable ignored) {}
            }

            // 亮色描边：叠在模糊背景之上；因整体作为 View 的 background，
            // 绘制顺序仍在键盘子 View 之下，不会遮挡按键 / 候选栏。
            // 悬浮键盘四边环绕；非悬浮键盘只保留顶部一条线。
            // 描边层 / 合成层不缓存：它们只持有复用的 d，不会触发 Aggregator 抖动；
            // 缓存它们反而会因 Drawable 持有 View 回调而阻止 View 回收。
            float density = v.getResources().getDisplayMetrics().density;
            ImeGlowStrokeDrawable glow = new ImeGlowStrokeDrawable(
                    GLOW_COLOR, GLOW_FEATHER_DP * density, cornerPx);
            glow.setMode(floating ? ImeGlowStrokeDrawable.MODE_ALL
                                  : ImeGlowStrokeDrawable.MODE_TOP_ONLY);
            android.graphics.drawable.LayerDrawable layer =
                    new android.graphics.drawable.LayerDrawable(new Drawable[] { d, glow });

            v.setBackground(layer);
            v.invalidate();
            ModuleLog.d(TAG, "blur applied r=" + radius
                    + " color=0x" + Integer.toHexString(color)
                    + " corner=" + cornerDp + "dp"
                    + " float=" + floating);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "doBlur fail", t);
        }
    }

    private static int applyMaskAlpha(int color, float strength) {
        if (strength >= 1.0f) return color;
        if (strength < 0f) strength = 0f;
        int a = (color >>> 24) & 0xFF;
        int na = Math.round(a * strength);
        return (na << 24) | (color & 0x00FFFFFF);
    }

    private static boolean isDark(View v) {
        try {
            android.content.Context ctx = v.getContext();
            if (ctx == null) return false;
            int mode = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;

            if (mode == sLastUiMode) return sLastDark;
            boolean dark = (mode == android.content.res.Configuration.UI_MODE_NIGHT_YES);
            sLastUiMode = mode;
            sLastDark = dark;
            return dark;
        } catch (Throwable t) {
            return false;
        }
    }
}
