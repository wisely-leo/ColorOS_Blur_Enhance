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

    private static final int GLOW_COLOR = 0x66FFFFFF;
    private static final float GLOW_FEATHER_DP = 8f;

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

    private static volatile android.content.Context sImeContext = null;

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

    private static synchronized void ensureFloatProbe() {
        if (sFloatProbeReady) return;
        ClassLoader cl = sHostCl;
        if (cl == null) return;
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
            sHostCl = cl;
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

            installKeyColorOverride(mod, cl);

            installConfigChangeWatch(mod, cl);

        } catch (Throwable t) {
            ModuleLog.e(TAG, "install failed", t);
        }
    }

    private static void installConfigChangeWatch(final BlurEnhanceModule mod, final ClassLoader cl) {
        try {
            Class<?> ims;
            try {
                ims = Class.forName("android.inputmethodservice.InputMethodService", false, cl);
            } catch (Throwable t) {
                ims = null;
            }
            if (ims == null) {
                ModuleLog.d(TAG, "InputMethodService not found, skip config watch");
                return;
            }
            Method onCfg = null;
            for (Method m : ims.getDeclaredMethods()) {
                if ("onConfigurationChanged".equals(m.getName()) && m.getParameterCount() == 1) {
                    onCfg = m;
                    break;
                }
            }
            if (onCfg == null) {
                ModuleLog.d(TAG, "onConfigurationChanged not found, skip config watch");
                return;
            }
            mod.hook(onCfg)
                    .setId("ime.configchange")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {

                                sLastUiMode = -1;
                                reapply();
                                View v = sLastView == null ? null : sLastView.get();
                                if (v != null) {
                                    final View rv = v;
                                    rv.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            rv.invalidate();
                                        }
                                    }, 120);
                                }
                            } catch (Throwable t) {
                                ModuleLog.d(TAG, "config change reapply fail: " + t);
                            }
                            return r;
                        }
                    });
            ModuleLog.d(TAG, "config change watch installed (InputMethodService.onConfigurationChanged)");
        } catch (Throwable t) {
            ModuleLog.d(TAG, "install config change watch failed: " + t);
        }
    }

    private static final int KEY_OVERLAY_COLOR_LIGHT = 0xCCffffff;
    private static final int KEY_OVERLAY_COLOR_DARK = 0xCC1C1C1E;

    private static final String[] KEY_OVERLAY_METHODS = { "e", "d", "k" };

    private static int keyOverlayColor() {
        return isDarkCached() ? KEY_OVERLAY_COLOR_DARK : KEY_OVERLAY_COLOR_LIGHT;
    }

    private static boolean isDarkCached() {
        try {
            android.content.Context ctx = sImeContext;
            if (ctx != null) {
                int mode = ctx.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                if (mode == sLastUiMode) return sLastDark;
                boolean dark = (mode == android.content.res.Configuration.UI_MODE_NIGHT_YES);
                sLastUiMode = mode;
                sLastDark = dark;
                return dark;
            }
        } catch (Throwable ignored) {}
        return sLastDark;
    }

    private static void installKeyColorOverride(final BlurEnhanceModule mod, final ClassLoader cl) {
        final String[] impls = {
                "com.yuyan.imemodule.data.theme.Theme$Builtin",
                "com.yuyan.imemodule.data.theme.Theme$Custom"
        };
        int hooked = 0;
        for (String cls : impls) {
            Class<?> c = null;
            try {
                c = Class.forName(cls, false, cl);
            } catch (Throwable t) {
                continue;
            }
            for (final String mname : KEY_OVERLAY_METHODS) {
                try {
                    Method m = c.getDeclaredMethod(mname);
                    if (m.getReturnType() != int.class) continue;
                    mod.hook(m)
                            .setId("ime.keycolor." + mname)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    chain.proceed();
                                    return keyOverlayColor();
                                }
                            });
                    hooked++;
                } catch (Throwable ignored) {}
            }
        }
        ModuleLog.d(TAG, "key color override installed, hooked=" + hooked
                + " light=0x" + Integer.toHexString(KEY_OVERLAY_COLOR_LIGHT)
                + " dark=0x" + Integer.toHexString(KEY_OVERLAY_COLOR_DARK));
    }

    private static void applyBlur(final View v) {
        if (v == null) return;

        sLastView = new java.lang.ref.WeakReference<>(v);

        try {
            android.content.Context ctx = v.getContext();
            if (ctx != null) sImeContext = ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx;
        } catch (Throwable ignored) {}

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

        for (View v : sBlurOf.keySet()) {
            try {
                Drawable d = sBlurOf.get(v);
                if (d != null) d.setVisible(false, false);
            } catch (Throwable ignored) {}
        }

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

        if (retry > 0) {
            try { if (!v.isAttachedToWindow()) return; } catch (Throwable ignore) {}
        }
        if (!ensureReflection()) {
            ModuleLog.d(TAG, "doBlur: reflection not ready, abort");
            return;
        }
        try {

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

            float cornerDp = FeatureFlags.IME_BLUR_CORNER_DP;
            float cornerPx = 0f;
            boolean floating = isFloatKeyboard(v);
            if (cornerDp > 0f && sSetCornerRadius != null) {
                cornerPx = floating
                        ? cornerDp * v.getResources().getDisplayMetrics().density
                        : 0f;

                if (cornerPx > 0f) {
                    int w = v.getWidth();
                    int h = v.getHeight();
                    if (w > 0 && h > 0) {
                        float maxSafe = Math.min(w, h) / 2f - 2f;
                        if (maxSafe < 0f) maxSafe = 0f;
                        if (cornerPx > maxSafe) {
                            ModuleLog.d(TAG, "corner clamped " + cornerPx + " -> " + maxSafe
                                    + " (view " + w + "x" + h + ")");
                            cornerPx = maxSafe;
                        }
                    } else {
                        ModuleLog.d(TAG, "corner fallback 0 (view not measured)");
                        cornerPx = 0f;
                    }
                }
                try {
                    sSetCornerRadius.invoke(mgr, cornerPx);
                } catch (Throwable ignored) {}
            }

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
