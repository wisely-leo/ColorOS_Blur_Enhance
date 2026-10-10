package com.shortcutblur;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.Window;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;
public final class QuickSearchBlur {
    private static final String TAG = "QSBLUR";
    private static final Drawable TRANSPARENT = new ColorDrawable(0x00000000);
    private static final int ENTER_SOURCE_ASSISTANT_SCREEN = 2;
    private static final int ENTER_SOURCE_ASSISTANT_INFO = 4;
    private static final int ENTER_SOURCE_UNKNOWN = -1;
    private static final String CLS_RUN_TIME_CONFIG =
            "com.heytap.quicksearchbox.core.constant.RunTimeConfig";
    private static final String FLD_QS_ENTER_SOURCE_FLAG = "QS_ENTER_SOURCE_FLAG";
    private static volatile boolean sInstalled = false;
    private static volatile ClassLoader sLoader = null;
    private QuickSearchBlur() {}
    public static void install(BlurEnhanceModule module, ClassLoader loader) {
        if (loader != null) sLoader = loader;
        if (sInstalled) return;
        synchronized (QuickSearchBlur.class) {
            if (sInstalled) return;
            try {
                installViewHook(module, loader);
                installWindowHook(module, loader);
                sInstalled = true;
                ModuleLog.d(TAG, "install done classLoader=" + (loader != null));
            } catch (Throwable t) {
                ModuleLog.e(TAG, "install failed", t);
            }
        }
    }
    private static volatile boolean sViewHookInstalled = false;
    private static void installViewHook(BlurEnhanceModule module, ClassLoader loader) throws Throwable {
        if (sViewHookInstalled) return;
        Method m = View.class.getDeclaredMethod("setBackground", Drawable.class);
        m.setAccessible(true);
        module.hookPublic("qs.view.setBg", (Executable) m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof View && isDecorView((View) self) && shouldTransparent()) {
                        Object[] args = chain.getArgs().toArray();
                        if (args.length >= 1) {
                            ModuleLog.d(TAG, "hit decorView.setBackground -> transparent");
                            args[0] = TRANSPARENT;
                            return chain.proceed(args);
                        }
                    }
                } catch (Throwable ignore) {}
                return chain.proceed();
            }
        });
        sViewHookInstalled = true;
        ModuleLog.d(TAG, "[hook] View.setBackground");
    }
    private static volatile boolean sWindowHookInstalled = false;
    private static void installWindowHook(BlurEnhanceModule module, ClassLoader loader) throws Throwable {
        if (sWindowHookInstalled) return;
        Method m = Window.class.getDeclaredMethod("setBackgroundDrawable", Drawable.class);
        m.setAccessible(true);
        module.hookPublic("qs.window.setBg", (Executable) m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    if (shouldTransparent()) {
                        Object[] args = chain.getArgs().toArray();
                        if (args.length >= 1) {
                            ModuleLog.d(TAG, "hit window.setBackgroundDrawable -> transparent");
                            args[0] = TRANSPARENT;
                            return chain.proceed(args);
                        }
                    }
                } catch (Throwable ignore) {}
                return chain.proceed();
            }
        });
        sWindowHookInstalled = true;
        ModuleLog.d(TAG, "[hook] Window.setBackgroundDrawable");
    }
    private static boolean shouldTransparent() {
        try {
            ClassLoader cl = sLoader;
            if (cl == null) cl = QuickSearchBlur.class.getClassLoader();
            Class<?> rc = Class.forName(CLS_RUN_TIME_CONFIG, false, cl);
            Field f = rc.getField(FLD_QS_ENTER_SOURCE_FLAG);
            int flag = f.getInt(null);
            boolean ok = flag != ENTER_SOURCE_ASSISTANT_SCREEN
                    && flag != ENTER_SOURCE_ASSISTANT_INFO
                    && flag != ENTER_SOURCE_UNKNOWN;
            ModuleLog.d(TAG, "enterSourceFlag=" + flag + " transparent=" + ok
                    + " loader=" + (cl == null ? "null"
                    : Integer.toHexString(System.identityHashCode(cl))));
            return ok;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "read source flag failed (loader="
                    + (sLoader == null ? "null" : Integer.toHexString(System.identityHashCode(sLoader)))
                    + ")", t);
            return false;
        }
    }
    private static boolean isDecorView(View v) {
        try {
            String cn = v.getClass().getName();
            if (cn.endsWith("DecorView") || cn.contains("DecorView")) return true;
            return v.getParent() == null && v.getWindowToken() != null;
        } catch (Throwable ignore) {}
        return false;
    }
}
