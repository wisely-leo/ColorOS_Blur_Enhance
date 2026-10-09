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

    public static final String PKG_YUYAN = "com.yuyan.pinyin.offline.release";

    private static final String CLS_IMV_BASE = "fx";

    private static volatile java.lang.ref.WeakReference<View> sLastView =
            new java.lang.ref.WeakReference<>(null);

    private static final String CLS_VRM = "com.oplus.view.ViewRootManager";

    private static volatile boolean sReflReady = false;
    private static Constructor<?> sCtorViewRootManager;
    private static Method sGetBlurDrawable;
    private static Method sSetBlurRadius;
    private static Method sSetColor;
    private static Method sSetCornerRadius;

    private static volatile int sLastUiMode = -1;
    private static volatile boolean sLastDark = false;

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

    public static void install(final BlurEnhanceModule mod,
                               XposedModuleInterface.PackageReadyParam param) {
        final ClassLoader cl = param.getClassLoader();
        if (cl == null) return;

        installYuyan(mod, cl);
    }

    private static void installYuyan(final BlurEnhanceModule mod, final ClassLoader cl) {
        try {
            Class<?> base = Class.forName(CLS_IMV_BASE, false, cl);
            if (base == null || !View.class.isAssignableFrom(base)) {
                ModuleLog.d(TAG, "base class not a View: " + CLS_IMV_BASE);
                return;
            }

            Executable target = null;
            for (Method m : base.getDeclaredMethods()) {
                if ("onAttachedToWindow".equals(m.getName()) && m.getParameterCount() == 0) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                ModuleLog.d(TAG, "onAttachedToWindow not found in " + CLS_IMV_BASE);
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
            ModuleLog.d(TAG, "hooked " + CLS_IMV_BASE + ".onAttachedToWindow");

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
                ModuleLog.d(TAG, "hooked " + CLS_IMV_BASE + ".onVisibilityChanged");
            } else {
                ModuleLog.d(TAG, "onVisibilityChanged not found, skip");
            }

            try {
                Class<?> xu = Class.forName("xu", false, cl);
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
                                            ModuleLog.d(TAG, "xu.u() -> re-applyBlur after theme bg");
                                            applyBlur((View) self);
                                        }
                                    } catch (Throwable t) {
                                        ModuleLog.e(TAG, "theme bg applyBlur fail", t);
                                    }
                                    return r;
                                }
                            });
                    ModuleLog.d(TAG, "hooked xu.u (theme bg)");
                } else {
                    ModuleLog.d(TAG, "xu.u not found, skip");
                }
            } catch (Throwable t) {
                ModuleLog.d(TAG, "hook xu.u failed: " + t);
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
        View v = sLastView == null ? null : sLastView.get();
        if (v == null) return;
        try {
            View target = tryGetSkbRoot(v);
            if (target == null) target = v;
            target.setBackground(null);
            target.invalidate();
            ModuleLog.d(TAG, "clearBlur: removed blur background");
        } catch (Throwable t) {
            ModuleLog.e(TAG, "clearBlur fail", t);
        }
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
            Object mgr = sCtorViewRootManager.newInstance(v);
            Drawable d = (Drawable) sGetBlurDrawable.invoke(mgr);
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
            if (cornerDp > 0f && sSetCornerRadius != null) {
                float px = cornerDp * v.getResources().getDisplayMetrics().density;
                try {
                    sSetCornerRadius.invoke(mgr, px);
                } catch (Throwable ignored) {}
            }

            v.setBackground(d);
            v.invalidate();
            ModuleLog.d(TAG, "blur applied r=" + radius
                    + " color=0x" + Integer.toHexString(color)
                    + " corner=" + cornerDp + "dp");
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
