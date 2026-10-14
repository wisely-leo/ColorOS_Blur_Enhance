package com.shortcutblur;
import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import java.lang.reflect.Method;
import static com.shortcutblur.Symbols.*;
public final class BlurLib {
    private BlurLib() {}
    public static volatile ClassLoader LOADER = null;
    public static ClassLoader classLoader() { return LOADER; }
    public static void setBlurRadius(View v, float radius) {
        if (v == null) return;
        try {
            RenderEffect fx = (radius > 0.5f)
                    ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
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
            // 首选：精确匹配 LauncherState.OVERVIEW 静态单例。
            Object overview = readStaticState("OVERVIEW");
            if (overview != null) return state == overview;
        } catch (Throwable ignore) {}
        // 兜底：按类名判断。
        // 注意：不能再用 overviewUi 布尔字段——Background 态的 overviewUi 也为 true，
        //      会把「应用退到桌面」误判成「进入最近任务」。
        try {
            String cn = state.getClass().getSimpleName();
            return cn.contains("Overview") && !cn.contains("Background");
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
            java.lang.reflect.Field f = Reflect.field(ls, name);
            return f == null ? null : f.get(null);
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
    // 发热优化：结果缓存。
    // currentAppContext() 被 GlassColorHook.isDarkMode / GlyphBlurRenderer.isUiDarkMode 等
    // 高频路径当作「取 Context 工具」反复调用（滚动、内容变化、重建链路）。
    // 原实现每次都 Class.forName + 反射 invoke；Application 实例与进程同生命周期，
    // 因此缓存 30s 即可（极端情况如 Application 被替换也能在 30s 内自愈）。
    private static volatile android.content.Context sAppCtx = null;
    private static volatile long sAppCtxAt = 0L;
    private static final long APP_CTX_TTL_MS = 30_000L;

    public static android.content.Context currentAppContext() {
        android.content.Context cached = sAppCtx;
        if (cached != null
                && (android.os.SystemClock.uptimeMillis() - sAppCtxAt) < APP_CTX_TTL_MS) {
            return cached;
        }
        try {
            Class<?> at = Class.forName(CLS_ACTIVITY_THREAD);
            Object app = Reflect.callStatic(at, M_CURRENT_APPLICATION);
            if (app instanceof android.content.Context) {
                sAppCtx = (android.content.Context) app;
                sAppCtxAt = android.os.SystemClock.uptimeMillis();
                return (android.content.Context) app;
            }
        } catch (Throwable ignore) {}
        return cached;   // 失败时回退上次可用值（可能为 null）
    }
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
