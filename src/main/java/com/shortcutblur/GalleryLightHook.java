package com.shortcutblur;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

public final class GalleryLightHook {
    public static final String PKG_GALLERY = "com.coloros.gallery3d";
    private static final String TAG = "GALLERY";
    private static final String TARGET_CLASS = "com.oplus.aiunit.vision.rda";
    private static final Set<String> FORCE_FALSE = new HashSet<>();
    private static final Set<String> FORCE_TRUE = new HashSet<>();
    static {
        FORCE_FALSE.add("is_product_light");
        FORCE_FALSE.add("is_product_light_low");
        FORCE_FALSE.add("is_realme_force_product_light_low");
        FORCE_FALSE.add("is_force_dark_theme");
        FORCE_TRUE.add("feature_is_support_photo_page_light_theme");
        FORCE_TRUE.add("is_product_light_high");
    }
    private static volatile boolean installed = false;
    private GalleryLightHook() {}

    public static void install(BlurEnhanceModule mod, ClassLoader loader, String apkPath) {
        if (installed) return;
        synchronized (GalleryLightHook.class) {
            if (installed) return;
            try {
                int n = hookTarget(mod, loader);
                installed = true;
                ModuleLog.i("[GALLERY] install done, hooked=" + n + " apk=" + apkPath);
            } catch (Throwable t) {
                ModuleLog.e(TAG, "install failed", t);
            }
        }
    }

    private static int hookTarget(BlurEnhanceModule mod, ClassLoader loader) {
        int n = 0;
        Set<Method> seen = new HashSet<>();
        try {
            Class<?> c = Class.forName(TARGET_CLASS, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!isBooleanConfigMethod(m)) continue;
                if (!seen.add(m)) continue;
                try {
                    mod.hookPublic("gallery:" + TARGET_CLASS + "." + m.getName(),
                            (Executable) m,
                            chain -> {
                                Object r = chain.proceed();
                                try {
                                    Object a0 = chain.getArg(0);
                                    if (a0 != null) {
                                        String key = String.valueOf(a0);
                                        Boolean forced = decide(key);
                                        if (forced != null) {
                                            ModuleLog.i("[GALLERY] override " + key + " -> " + forced);
                                            return forced;
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                return r;
                            });
                    n++;
                    ModuleLog.i("[GALLERY] hooked " + TARGET_CLASS + "." + m.getName());
                } catch (Throwable t) {
                    ModuleLog.d(TAG, "hook " + m.getName() + " failed: " + t);
                }
            }
        } catch (Throwable t) {
            ModuleLog.d(TAG, "load " + TARGET_CLASS + " failed: " + t);
        }
        return n;
    }

    private static Boolean decide(String key) {
        for (String k : FORCE_FALSE) {
            if (key.equals(k) || key.contains(k)) return Boolean.FALSE;
        }
        for (String k : FORCE_TRUE) {
            if (key.equals(k) || key.contains(k)) return Boolean.TRUE;
        }
        return null;
    }

    private static boolean isBooleanConfigMethod(Method m) {
        if (Modifier.isAbstract(m.getModifiers())) return false;
        Class<?>[] ps = m.getParameterTypes();
        if (ps.length != 2) return false;
        Class<?> ret = m.getReturnType();
        if (ret != Boolean.class && ret != Boolean.TYPE) return false;
        Class<?> p0 = ps[0];
        Class<?> p1 = ps[1];
        boolean firstOk = p0 == String.class || p0 == Object.class || p0.isPrimitive();
        boolean secondOk = p1 == Boolean.class || p1 == Boolean.TYPE;
        return firstOk && secondOk;
    }
}