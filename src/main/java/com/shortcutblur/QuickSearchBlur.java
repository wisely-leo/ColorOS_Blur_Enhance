package com.shortcutblur;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.Window;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 【下拉搜索实时模糊】全局搜索（com.heytap.quicksearchbox）背景透明化。
 *
 * <p>原理：把搜索页顶层背景置为透明，使下层"桌面实时模糊画面"透出。
 * 判定策略：**排除负一屏系**（QS_ENTER_SOURCE_FLAG 为 2/4/-1 时保持默认模糊），
 * 其余来源（桌面下拉 0、点搜索框 1/7、抽屉 5 等"下层是桌面"的场景）一律透明。
 *
 * <p>隔离原则：
 * <ul>
 *   <li>仅当 FeatureFlags.QUICKSEARCH_BLUR=true 时装配；否则一行不跑。</li>
 *   <li>仅在本模块作用域内、仅对搜索页进程生效（作用域已限定包名）。</li>
 *   <li>所有 hook 回调全 try-catch，任何异常都放行原逻辑，绝不影响宿主。</li>
 *   <li>不改动任何既有功能代码路径。</li>
 * </ul>
 */
public final class QuickSearchBlur {

    private static final String TAG = "QSBLUR";

    /** 透明 drawable（复用，避免频繁分配）。 */
    private static final Drawable TRANSPARENT = new ColorDrawable(0x00000000);

    /**
     * 进入来源 flag 枚举（com.heytap.quicksearchbox.core.constant.EnterSource）：
     * 0=LAUNCHER 1=WIDGET 2=ASSISTANT_SCREEN 3=KEYBOARD 4=ASSISTANT_INFO
     * 5=DRAWER 6=PUSH 7=DOCK 9=OPLUS_DOCK -1=UNKNOWN
     *
     * <p>判定策略：**排除负一屏系**（2/4/-1），其余一律透明。
     * 这样桌面下拉(0)、点搜索框(1/7)、抽屉(5)等"下层是桌面"的场景都透明，
     * 只有负一屏(assistantscreen)保持搜索默认模糊。
     */
    private static final int ENTER_SOURCE_ASSISTANT_SCREEN = 2;
    private static final int ENTER_SOURCE_ASSISTANT_INFO = 4;
    private static final int ENTER_SOURCE_UNKNOWN = -1;

    private static final String CLS_RUN_TIME_CONFIG =
            "com.heytap.quicksearchbox.core.constant.RunTimeConfig";
    private static final String FLD_QS_ENTER_SOURCE_FLAG = "QS_ENTER_SOURCE_FLAG";

    /** 防止重复安装。 */
    private static volatile boolean sInstalled = false;

    /** 搜索页进程的 ClassLoader（用于反射读 RunTimeConfig）。 */
    private static volatile ClassLoader sLoader = null;

    private QuickSearchBlur() {}

    /** 供 BlurEnhanceModule 调用的统一装配入口。 */
    public static void install(BlurEnhanceModule module, ClassLoader loader) {
        // ★ 每次都更新 loader —— 修复"sLoader 只在首次设置、之后 stale 导致反射读 flag 失败"的问题。
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

    // ---------- Hook 1: View.setBackground(Drawable) ----------
    private static void installViewHook(BlurEnhanceModule module, ClassLoader loader) throws Throwable {
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
        ModuleLog.d(TAG, "[hook] View.setBackground");
    }

    // ---------- Hook 2: Window.setBackgroundDrawable(Drawable) ----------
    private static void installWindowHook(BlurEnhanceModule module, ClassLoader loader) throws Throwable {
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
        ModuleLog.d(TAG, "[hook] Window.setBackgroundDrawable");
    }

    // ---------- 读进入来源 ----------
    /**
     * true = 下层是桌面（可透明化）；false = 负一屏系（保持搜索默认模糊）。
     *
     * <p>策略：排除负一屏系（2=ASSISTANT_SCREEN / 4=ASSISTANT_INFO / -1=UNKNOWN）。
     * 读失败保守返回 false（不改背景，保持默认模糊，绝不误伤）。
     */
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
            // 详细日志：便于定位"点搜索框不透明"时实际 flag 值 / loader 情况。
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

    // ---------- 判定：是否顶层 DecorView ----------
    /**
     * 判断是否为需要透明化的顶层背景 View。
     *
     * <p>放宽策略：只要类名是 DecorView 家族即认可（不强制 parent==null）——
     * 因为不同进入入口（下拉 / 点搜索框）可能在 decorView 尚未 attach、
     * 或已 attach 的时序下设置背景，强制 parent==null 会漏判。
     * 兜底：无 parent 且已 attach（有 windowToken）的 View 也认。
     */
    private static boolean isDecorView(View v) {
        try {
            String cn = v.getClass().getName();
            if (cn.endsWith("DecorView") || cn.contains("DecorView")) return true;
            return v.getParent() == null && v.getWindowToken() != null;
        } catch (Throwable ignore) {}
        return false;
    }
}