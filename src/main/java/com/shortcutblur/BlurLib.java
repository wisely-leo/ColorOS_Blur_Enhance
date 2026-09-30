package com.shortcutblur;

import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

/**
 * 通用库：常量 + 反射/View/状态/渲染效果 工具函数。
 * 被 BlurEnhanceModule 与 RecentsBlur 共用（static import）。
 */
public final class BlurLib {
    private BlurLib() {}

    /** 当前 launcher 的 ClassLoader（由模块在 onPackageReady 时注入）。 */
    public static volatile ClassLoader LOADER = null;
    public static ClassLoader classLoader() { return LOADER; }

    public static final String CLS_LAUNCHER = "com.android.launcher.Launcher";


    public static final String CLS_WORKSPACE_SCRIM = "com.android.launcher3.views.WorkSpaceScrimView";


    public static final String CLS_OPLUS_DRAGLAYER = "com.android.launcher3.OplusDragLayer";

    // === 状态机信号（替换 scale 穿越判断） ===

    public static final String CLS_LAUNCHER_RECENTS_VIEW = "com.android.quickstep.views.LauncherRecentsView";

    public static final String CLS_LAUNCHER_STATE = "com.android.launcher3.LauncherState";

    public static final String M_ON_STATE_TRANSITION_START = "onStateTransitionStart";

    public static final String M_ON_STATE_TRANSITION_COMPLETE = "onStateTransitionComplete";

    public static final String M_ON_STATE_TRANSITION_CANCEL = "onStateTransitionCancel";

    // 状态驱动：是否已经因为"进入 OVERVIEW"而武装模糊（防止重复触发）

    /** 给 View 施加（或清除）高斯模糊。纯函数式，不含模块状态。 */
    public static void setBlurRadius(View v, float radius) {
        if (v == null) return;
        try {
            RenderEffect fx = (radius > 0.5f)
                    ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR)
                    : null;
            v.setRenderEffect(fx);
        } catch (Throwable ignore) {}
    }

    public static android.view.Window resolveWindow(View v) {
        try {
            android.content.Context c = v.getContext();
            int guard = 0;
            while (c != null && guard++ < 10) {
                if (c instanceof android.app.Activity) return ((android.app.Activity) c).getWindow();
                if (c instanceof android.content.ContextWrapper) c = ((android.content.ContextWrapper) c).getBaseContext();
                else break;
            }
        } catch (Throwable ignore) {}
        return null;
    }


    public static boolean isOverviewState(Object state) {
        if (state == null) return false;
        try {
            Object overview = readStaticState("OVERVIEW");
            if (overview != null && state == overview) return true;
            // 兜底：读 overviewUi 字段
            Object ui = Reflect.readField(state, "overviewUi");
            if (ui instanceof Boolean) return (Boolean) ui;
        } catch (Throwable ignore) {}
        return false;
    }


    public static boolean isNormalState(Object state) {
        if (state == null) return false;
        try {
            Object normal = readStaticState("NORMAL");
            return normal != null && state == normal;
        } catch (Throwable ignore) {}
        return false;
    }


    public static Object readStaticState(String name) {
        try {
            Class<?> ls = Reflect.loadClass(CLS_LAUNCHER_STATE, classLoader());
            if (ls == null) return null;
            java.lang.reflect.Field f = ls.getField(name);
            return f.get(null);
        } catch (Throwable ignore) {}
        return null;
    }


    public static String stateName(Object state) {
        return state == null ? "" : state.toString();
    }


    public static String anchorName(View v) {
        return v == null ? ""
                : (v.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(v)));
    }

    // 每 2 秒最多读一次外部配置（改文件即可调参，无需重新编译）

    public static android.content.Context currentAppContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof android.content.Context) return (android.content.Context) app;
        } catch (Throwable ignore) {}
        return null;
    }

    // 入场后探测 RenderEffect 是否还在（判断是否被系统/launcher 清掉）

    public static Object getLauncherQuietly(View view) {
        try {
            Context ctx = view.getContext();
            if (ctx == null) return null;
            Class<?> lc = Reflect.loadClass(CLS_LAUNCHER, classLoader());
            if (lc == null) return null;

            Method g = Reflect.method(lc, "getLauncher", Context.class);
            if (g != null) {
                try {
                    Object r = g.invoke(null, ctx);
                    if (r != null) return r;
                } catch (Throwable ignore) {}
            }
            Method g2 = Reflect.method(lc, "getLauncherOrNull", Context.class);
            if (g2 != null) {
                try {
                    Object r2 = g2.invoke(null, ctx);
                    if (r2 != null) return r2;
                } catch (Throwable ignore) {}
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

}
