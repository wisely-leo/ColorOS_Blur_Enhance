package com.shortcutblur;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class GlassColorHook {
    private static volatile boolean sInstalled = false;
    private static final String CLS_CONTINUOUS = "com.oplus.posteffect.drawable.ContinuousBlurDrawable";
    private static final String CLS_BLUR_PARAM = "com.oplus.posteffect.BlurParam";

    private GlassColorHook() {}

    public static void install(XposedModule mod, ClassLoader cl) {
        if (sInstalled) return;
        synchronized (GlassColorHook.class) {
            if (sInstalled) return;
        }
        try {
            Class<?> cls = Class.forName(CLS_CONTINUOUS, false, cl);
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("setBlurParamsInternal")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 3) continue;
                if (!CLS_BLUR_PARAM.equals(pt[0].getName())) continue;
                if (pt[2] != boolean.class) continue;
                Reflect.setAccessible(m);
                mod.hook((Executable) m)
                        .setId("glass.color")
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                try {
                                    Object self = chain.getThisObject();
                                    if (GlyphBlurRenderer.isClockBlurDrawable(self)) {
                                        Object param = chain.getArg(0);
                                        applyGlassColorToParam(param);
                                    }
                                } catch (Throwable t) {
                                    ModuleLog.e("GLASS", "hook body fail", t);
                                }
                                return chain.proceed();
                            }
                        });
                sInstalled = true;
                ModuleLog.d("GLASS", "hooked setBlurParamsInternal/" + pt.length);
                break;
            }
            if (!sInstalled) ModuleLog.e("GLASS", "setBlurParamsInternal not found", null);
        } catch (Throwable t) {
            ModuleLog.e("GLASS", "install fail", t);
        }
    }

    private static boolean isDarkMode(Object param) {
        try {
            Class<?> appCls = Class.forName("android.app.ActivityThread", false, null);
            Object app = appCls.getMethod("currentApplication").invoke(null);
            if (app instanceof android.app.Application) {
                android.content.res.Resources res = ((android.app.Application) app).getResources();
                if (res != null) {
                    android.content.res.Configuration cfg = res.getConfiguration();
                    return (cfg.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static void applyGlassColorToParam(Object param) {
        if (param == null) return;
        try {
            if (!FeatureFlags.CLOCK_GLASS) return;
            Class<?> paramCls = param.getClass();
            boolean dark = isDarkMode(param);
            int blendMode = dark ? 4 : 3;
            int blendA = FeatureFlags.CLOCK_GLASS_BLEND;
            int blendB = FeatureFlags.CLOCK_GLASS_MIX;
            if (blendA == 0 && blendB == 0) return;
            Method setMP = Reflect.method(paramCls, "setMaterialParams", 3);
            if (setMP != null) { setMP.setAccessible(true); setMP.invoke(param, blendMode, blendA, blendB); }
        } catch (Throwable t) {
            ModuleLog.e("GLASS", "applyColorToParam fail", t);
        }
    }
}
