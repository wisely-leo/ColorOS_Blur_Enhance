package com.shortcutblur;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.RemoteViews;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import static com.shortcutblur.BlurLib.*;
import static com.shortcutblur.Symbols.*;
public class BlurEnhanceModule extends XposedModule {
    // ========================================================================
    // 职责地图（本类 5 大块，按出现顺序）：
    //   ① 入口 & 调度 .............. onModuleLoaded / onPackageReady / hookEx
    //   ② 包名路由 & 安装 .......... installHooks / 屏幕·配置广播接收器
    //   ③ 图标模糊动画 ............. ensureIconBlurLayer / armBlurForView / fadeOut…
    //   ④ 后处理（PostEffect）..... installPostEffectHooks / hookBySignature / depth
    //   ⑤ 组件合并（Merge）........ RemoteViews.apply / AppWidgetHostView / TextView
    // 注：本类为 Xposed 模块入口，单文件承载多职责是 LSPosed 常见形态；
    //     字段与 static 状态按“上述分块”集中声明，改动时请就近查找。
    // ========================================================================

    // ---- ① 入口 & 调度 ----
    private final RecentsBlur.HookApi recentsApi = new RecentsBlur.HookApi() {
        @Override public void hook(String id, Executable target, XposedInterface.Hooker hooker) {
            hookEx(id, target, hooker);
        }
    };
    private void hookEx(String id, Executable target, XposedInterface.Hooker hooker) {
        hook(target).setId(id).setExceptionMode(XposedInterface.ExceptionMode.DEFAULT).intercept(hooker);
    }
    public void hookPublic(String id, Executable target, XposedInterface.Hooker hooker) {
        hookEx(id, target, hooker);
    }
    /** ShortcutBlur 功能（③ 块）的宿主契约实现：提供类加载器与 hookEx。 */
    private final ShortcutBlurFeature.Host shortcutBlurHost = new ShortcutBlurFeature.Host() {
        @Override public ClassLoader classLoader() { return cl; }
        @Override public void hookEx(String id, Executable target, XposedInterface.Hooker hooker) {
            BlurEnhanceModule.this.hookEx(id, target, hooker);
        }
    };

    // ---- ② 常量：包名 ----
    private static final String PKG_POSTEFFECT = "com.oplus.blur";
    private static final String PKG_QUICKSEARCH = "com.heytap.quicksearchbox";
    private static final String PKG_CLOCK = "com.coloros.alarmclock";
    private static final String PKG_YUYAN = "com.yuyan.pinyin.offline.release";

    // ---- ② 常量：动画 / 阈值 ----
    private static final float BLUR_RADIUS = 64.0f;
    private static final long BLUR_DURATION = 330L;
    private static final long DEPTH_FALLBACK_DELAY = 500L;
    private static final long SWALLOW_RESET_DELAY = 450L;

    // ---- ③ 常量：模糊标志位（resolveBlurFlags 产出）----
    private static final int F_STATIC = 1 << 0;
    private static final int F_ICON = 1 << 1;
    private static final int F_WALL = 1 << 2;
    private static final int F_ICON_ANIM = 1 << 3;

    // ---- ② 常量：广播 / 版本 ----
    private static final String ACTION_SETCONF = "com.wiselyleo.blurenhance.SETCONF";
    private static final String BUILD_TAG = "v46.2";

    // ---- ② 安装状态 ----
    private volatile ClassLoader cl;
    private volatile boolean installed = false;
    private volatile boolean postEffectInstalled = false;
    private static volatile boolean sScreenReceiverInstalled = false;
    private static volatile boolean sConfReceiverInstalled = false;

    // ---- ③ 图标模糊动画已迁出至 ShortcutBlurFeature（原 ShortcutBlur 本体）----

    // ---- ④ 后处理：反射缓存与去重 ----
    private final Set<Method> peHooked = new HashSet<>();
    /** SCALE 热路径去重：同 tag+old 只记一次，避免每帧刷屏。 */
    private final Set<String> peScaleLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean sOplusEffectResolved = false;
    private static volatile Class<?> sOplusEffectCls = null;
    private static volatile Method sSetBgRenderEffect = null;
    private static volatile Method sSetRenderEffectViewMethod = null;
    private static volatile boolean sOplusApiDumped = false;
    private final Set<String> dumpedCls = new HashSet<>();

    // ---- ⑤ 组件合并：时钟文本去重 ----
    private final java.util.WeakHashMap<android.view.View, String> sLastClockText = new java.util.WeakHashMap<android.view.View, String>();

    // ========================================================================
    // ① 入口 & 调度
    // ========================================================================
    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
    }
    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            if (param == null) return;
            String pkg = param.getPackageName();
            FeatureFlags.load();
            ModuleLog.i("onPackageReady pkg=" + pkg);
            installScreenReceiverViaApp(param);
            if (PKG_POSTEFFECT.equals(pkg)) {
                if (!FeatureFlags.POSTEFFECT) {
                    ModuleLog.d("READY", "posteffect disabled by flag, skip");
                    return;
                }
                ClassLoader peLoader = param.getClassLoader();
                if (peLoader == null) return;
                if (postEffectInstalled) {
                    ModuleLog.d("READY", "posteffect already installed, skip");
                    return;
                }
                if (installPostEffectHooks(peLoader)) {
                    postEffectInstalled = true;
                }
                return;
            }
            if (PKG_QUICKSEARCH.equals(pkg)) {
                if (FeatureFlags.QUICKSEARCH_BLUR) {
                    QuickSearchBlur.install(this, param.getClassLoader());
                } else {
                    ModuleLog.d("READY", "quicksearch blur disabled by flag, skip");
                }
                return;
            }
            if (PKG_CLOCK.equals(pkg)) {
                if (FeatureFlags.WIDGET_BLUR) {
                    ClockTextAlphaHook.install(this, param.getClassLoader());
                } else {
                    ModuleLog.d("READY", "clock alpha disabled by flag, skip");
                }
            }

            if (PKG_YUYAN.equals(pkg)) {
                if (FeatureFlags.IME_BLUR) {
                    ImeBlurHook.install(this, param);
                } else {
                    ModuleLog.d("READY", "ime blur disabled by flag, skip");
                }
                return;
            }
            if (GalleryLightHook.PKG_GALLERY.equals(pkg)) {
                if (FeatureFlags.GALLERY_LIGHT) {
                    GalleryLightHook.install(this, param.getClassLoader(), null);
                } else {
                    ModuleLog.d("READY", "gallery light disabled by flag, skip");
                }
                return;
            }
            ClassLoader anyLoader = param.getClassLoader();
            if (anyLoader != null) {
                if (FeatureFlags.WIDGET_BLUR) {
                    hookRemoteViewsApply(anyLoader);
                    hookAppWidgetHostView(anyLoader);
                } else {
                    ModuleLog.d("READY", "widget blur disabled by flag, skip");
                }
            }
            if (!isTargetLauncher(pkg)) return;
            ClassLoader loader = param.getClassLoader();
            if (loader == null) return;
            this.cl = loader;
            BlurLib.LOADER = loader;
            if (installed) {
                ModuleLog.d("READY", "already installed, skip");
                return;
            }
            if (FeatureFlags.SHORTCUT_BLUR) {
                hookTextViewSetText(loader);
            }
            HookInstallResult r = installHooks(loader);
            if (r.critical > 0) {
                installed = true;
                ModuleLog.d("READY", "installed critical=" + r.critical + " total=" + r.total);
            } else {
                ModuleLog.d("READY", "no critical hook, will retry (total=" + r.total + ")");
            }
        } catch (Throwable t) {
            ModuleLog.e("READY", "onPackageReady failed", t);
        }
    }
    // ========================================================================
    // ② 包名路由 & 安装
    // ========================================================================
    private void installScreenReceiverViaApp(XposedModuleInterface.PackageReadyParam param) {
        if (sScreenReceiverInstalled) return;
        synchronized (BlurEnhanceModule.class) {
            if (sScreenReceiverInstalled) return;
            try {
                ClassLoader cl = param.getClassLoader();
                if (cl == null) { ModuleLog.d("SCREEN", "loader null"); return; }
                ModuleLog.d("SCREEN", "try hook Application.attach via loader=" + cl.getClass().getName());
                Class<?> appCls = Class.forName("android.app.Application", false, cl);
                for (final Method m : appCls.getDeclaredMethods()) {
                    if (!m.getName().equals("attach")) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length != 1 || !ps[0].getName().equals("android.content.Context")) continue;
                    m.setAccessible(true);
                    this.hook(m).intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            if (self instanceof Context) {
                                if (!sScreenReceiverInstalled) {
                                    registerScreenReceiver((Context) self);
                                    registerConfReceiver((Context) self);
                                }
                            }
                        } catch (Throwable t) {
                            ModuleLog.d("SCREEN", "attach hook err: " + t);
                        }
                        return r;
                    });
                    ModuleLog.d("SCREEN", "hooked Application.attach (waiting)");
                    return;
                }
                ModuleLog.d("SCREEN", "Application.attach not found");
            } catch (Throwable t) {
                ModuleLog.d("SCREEN", "hook attach err: " + t);
            }
        }
    }
    private void registerScreenReceiver(Context ctx) {
        synchronized (BlurEnhanceModule.class) {
            if (sScreenReceiverInstalled) return;
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_SCREEN_ON);
                f.addAction(Intent.ACTION_SCREEN_OFF);
                f.addAction(Intent.ACTION_USER_PRESENT);
                ctx.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        String a = i == null ? "?" : i.getAction();
                        boolean on = !Intent.ACTION_SCREEN_OFF.equals(a);
                        ModuleLog.d("SCREEN", "action=" + a + " screenOn=" + on);
                        if (FeatureFlags.WIDGET_BLUR) {
                            GlyphBlurRenderer.setScreenOn(on);
                        }
                    }
                }, f);
                sScreenReceiverInstalled = true;
                ModuleLog.d("READY", "screen receiver installed ctx=" + ctx.getClass().getName());
            } catch (Throwable t) {
                ModuleLog.d("SCREEN", "registerReceiver err: " + t);
            }
        }
    }
    private void registerConfReceiver(Context ctx) {
        synchronized (BlurEnhanceModule.class) {
            if (sConfReceiverInstalled) return;
            try {
                IntentFilter f = new IntentFilter(ACTION_SETCONF);
                BroadcastReceiver recv = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        if (i == null) return;
                        try {
                            RecentsBlur.applyConf(i);
                            boolean clockChanged = FeatureFlags.applyFromIntent(i);
                            if (clockChanged) {
                                try { GlyphBlurRenderer.notifyContentMaybeChangedAll(); } catch (Throwable ignored) { ModuleLog.swallow("module.notify", ignored); }
                            }
                            String mk = i.getStringExtra("mark");
                            if (mk != null) ModuleLog.d("MARK", "==== " + mk + " ====");
                            String vb = i.getStringExtra("verbose");
                            if (vb != null) ModuleLog.VERBOSE = !vb.trim().equalsIgnoreCase("off");
                        } catch (Throwable t) {
                            ModuleLog.e("CONF", "live set failed", t);
                        }
                    }
                };
                try {
                    ctx.registerReceiver(recv, f);
                } catch (Throwable t1) {
                    ctx.registerReceiver(recv, f, android.content.Context.RECEIVER_EXPORTED);
                }
                sConfReceiverInstalled = true;
                ModuleLog.d("CONF", "conf receiver installed action=" + ACTION_SETCONF);
                            ModuleLog.d("VER", "build=" + BUILD_TAG + RecentsBlur.describe());
            } catch (Throwable t) {
                ModuleLog.e("CONF", "register conf receiver failed", t);
            }
        }
    }
    private static boolean isTargetLauncher(String p) {
        return "com.android.launcher".equals(p)
                || "com.oplus.launcher".equals(p)
                || "com.coloros.launcher".equals(p);
    }
    private static final class HookInstallResult {
        int critical;
        int total;
    }
    private HookInstallResult installHooks(ClassLoader loader) {
        HookInstallResult r = new HookInstallResult();
        Set<Method> hooked = new HashSet<>();
        try {

            GlassColorHook.install(this, loader);
            // 桌面应用名阴影：与下面的模糊链路无关，独立安装。
            // 注意：这里不受 Flag 限制 —— 开关在运行期判定，这样 GUI 里刚打开的开关
            // 也能在桌面下一次重绑应用名时立刻生效，而不必等模块重新加载才挂得上 hook。
            r.total += LauncherLabelShadowHook.install(this, loader);
            if (!FeatureFlags.LAUNCHER_LABEL_SHADOW) {
                ModuleLog.d("INSTALL", "launcher label shadow currently OFF (hook installed, idle)");
            }
            if (FeatureFlags.SHORTCUT_BLUR) {
                r.total += ShortcutBlurFeature.install(loader, shortcutBlurHost);
            } else {
                ModuleLog.d("INSTALL", "shortcut blur disabled by flag, skip");
            }
            if (FeatureFlags.RECENTS_BLUR) {
                r.total += RecentsBlur.installProbes(loader, recentsApi);
                r.critical += RecentsBlur.installStateHooks(loader, recentsApi);
            } else {
                ModuleLog.d("INSTALL", "recents blur disabled by flag, skip");
            }
            ModuleLog.d("INSTALL", "critical=" + r.critical + " total=" + r.total);
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "installHooks failed", t);
        }
        return r;
    }
    // ④ 后处理（PostEffect）hook 安装与采样率修正
    // ========================================================================
    private boolean installPostEffectHooks(ClassLoader loader) {
        Class<?> cls;
        try {
            cls = Class.forName(CLS_EA, false, loader);
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "class not found: " + CLS_EA, t);
            return false;
        }
        ModuleLog.i("INSTALL class loaded: " + cls.getName());
        int critical = 0;
        critical += hookBySignature(cls, "c",
                new String[]{ CLS_SURFACE_CONTROL, "java.lang.Float", "java.lang.Integer", "java.lang.Long" },
                "c");
        critical += hookBySignature(cls, "e",
                new String[]{ CLS_SURFACE_CONTROL, "java.lang.Float", "java.lang.Integer", "java.lang.Long" },
                "e");
        critical += hookBySignature(cls, "d",
                new String[]{ CLS_SURFACE_CONTROL, "float", "int", "long" },
                "d");
        critical += hookBySignature(cls, "f",
                new String[]{ "float", "int", "long" },
                "f");
        ModuleLog.i("READY posteffect installed critical=" + critical);
        return critical > 0;
    }
    private int hookBySignature(Class<?> cls, String methodName, String[] wantParams, String tag) {
        Method target = null;
        StringBuilder all = new StringBuilder();
        for (Method m : cls.getDeclaredMethods()) {
            all.append(m.getName()).append("(");
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) all.append(",");
                all.append(ps[i].getName());
            }
            all.append(") ");
            if (!m.getName().equals(methodName)) continue;
            if (ps.length != wantParams.length) continue;
            boolean ok = true;
            for (int i = 0; i < ps.length; i++) {
                if (!ps[i].getName().equals(wantParams[i])) { ok = false; break; }
            }
            if (ok) { target = m; break; }
        }
        if (target == null) {
            ModuleLog.e("INSTALL", "method not found: " + methodName + " | declared: " + all, null);
            return 0;
        }
        final Method m = target;
        final Class<?>[] paramTypes = m.getParameterTypes();
        try {
            m.setAccessible(true);
            if (!peHooked.add(m)) {
                ModuleLog.d("INSTALL", "skip dup hook " + cls.getName() + "." + methodName);
                return 0;
            }
            this.hook(m)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        int pos = findFloatParamIndex(paramTypes);
                        if (pos >= 0 && pos < args.length && args[pos] instanceof Number) {
                            float old = ((Number) args[pos]).floatValue();
                            float peTarget = FeatureFlags.SAMPLE_SCALE;
                            if (old != peTarget) {
                                args[pos] = peTarget;
                                // 热路径（每帧调用）：同 tag 的同一 old 值只记一次，避免刷屏。
                                if (peScaleLogged.add(tag + ":" + old)) {
                                    ModuleLog.d("SCALE", tag + " " + old + " -> " + peTarget);
                                }
                            } else if (ModuleLog.VERBOSE) {
                                // keep：默认不打印（仅 VERBOSE 时记录）
                                ModuleLog.dv("SCALE", tag + " keep " + old);
                            }
                        } else if (ModuleLog.VERBOSE) {
                            ModuleLog.dv("SCALE", tag + " no float arg (pos=" + pos + ")");
                        }
                        return chain.proceed(args);
                    });
            ModuleLog.i("INSTALL hooked " + cls.getName() + "." + methodName + " (" + tag + ")");
            return 1;
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hook failed: " + cls.getName() + "." + methodName, t);
            return 0;
        }
    }
    private int findFloatParamIndex(Class<?>[] types) {
        int idx = -1;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == float.class || t == Float.class) {
                idx = i;
            }
        }
        return idx;
    }
    private volatile boolean sRemoteViewsHooked = false;
    // ========================================================================
    // ⑤ 组件合并（Merge）：RemoteViews.apply / AppWidgetHostView / TextView.setText
    // ========================================================================
    private void hookRemoteViewsApply(ClassLoader cl) {
        if (sRemoteViewsHooked) return;   // 幂等：同进程多次 onPackageReady 不重复 hook
        // ★ 注意：不要在此处提前置位。必须全部成功后再置 true，
        //   否则首次 hook 抛异常会把标志污染成"已装"，导致后续 onPackageReady 永远不再重试。
        boolean ok = true;
        try {
            Method m = RemoteViews.class.getDeclaredMethod("apply", android.content.Context.class, ViewGroup.class);
            hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(new RemoteViewsApplyHook(cl));
            ModuleLog.i("[merge] hooked RemoteViews.apply");
        } catch (Throwable t) {
            ok = false;
            ModuleLog.e("MERGE", "RemoteViews.apply hook fail", t);
        }
        try {
            Method m2 = RemoteViews.class.getDeclaredMethod("reapply", android.content.Context.class, android.view.View.class);
            hook(m2).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try {
                        ModuleLog.d("MERGE", "[reapply] called");
                        GlyphBlurRenderer.notifyContentMaybeChangedAll();
                    } catch (Throwable ignored) {}
                    return r;
                }
            });
            ModuleLog.i("[merge] hooked RemoteViews.reapply");
        } catch (Throwable t) {
            ok = false;
            ModuleLog.e("MERGE", "RemoteViews.reapply hook fail", t);
        }
        if (ok) sRemoteViewsHooked = true;
    }
    private volatile boolean sAhvHooked = false;
    private void hookAppWidgetHostView(ClassLoader cl) {
        if (sAhvHooked) return;   // 幂等：同进程多次 onPackageReady 不重复 hook
        boolean ok = false;
        try {
            Class<?> ahv = Class.forName("android.appwidget.AppWidgetHostView", false, cl);
            for (Method m : ahv.getDeclaredMethods()) {
                if (m.getName().equals("updateAppWidget")) {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(new AppWidgetHostViewHook(cl));
                    ok = true;
                    ModuleLog.i("[merge] hooked AppWidgetHostView.updateAppWidget");
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("MERGE", "AppWidgetHostView hook fail", t);
        }
        if (ok) sAhvHooked = true;
    }
    private static boolean isClockTextId(int id) {
        for (int tid : ClockIds.TEXT_IDS) {
            if (tid == id) return true;
        }
        return false;
    }
    private void hookAllSetText(Class<?> tvClass) {
        for (Method mm : tvClass.getDeclaredMethods()) {
            if (!mm.getName().equals("setText")) continue;
            try {
                hook(mm).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object self = chain.getThisObject();
                        Object r = chain.proceed();
                        try {
                            if (self instanceof android.widget.TextView) {
                                android.widget.TextView t = (android.widget.TextView) self;
                                int id = t.getId();
                                if (isClockTextId(id)) {
                                    CharSequence cs = t.getText();
                                    String now = cs == null ? "" : cs.toString();
                                    String key = Integer.toHexString(id) + ":" + now;
                                    String old = sLastClockText.get(t);
                                    if (!key.equals(old)) {
                                        sLastClockText.put(t, key);
                                        GlyphBlurRenderer.notifyContentMaybeChangedAll();
                                        ModuleLog.d("EVT", "clock setText id=0x" + Integer.toHexString(id) + " v=" + now);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                        return r;
                    }
                });
            } catch (Throwable ignored) {}
        }
        ModuleLog.i("[merge] hooked TextView.setText event-driven");
    }
    private volatile boolean sTextHooked = false;
    private void hookTextViewSetText(ClassLoader cl) {
        if (sTextHooked) return;   // 幂等：避免上游 critical==0 重试时重复 hook setText
        sTextHooked = true;
        try {
            Class<?> tv = Class.forName("android.widget.TextView", false, cl);
            hookAllSetText(tv);
        } catch (Throwable t) {
            ModuleLog.e("MERGE", "TextView.setText hook fail", t);
        }
    }

    private static final class RemoteViewsApplyHook implements XposedInterface.Hooker {
        private final ClassLoader cl;
        RemoteViewsApplyHook(ClassLoader c) { this.cl = c; }
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                java.util.List<Object> args = chain.getArgs();
                Object vg = args.size() > 1 ? args.get(1) : null;
                if (vg instanceof ViewGroup) {
                    WidgetBlurAttacher.resetGiveUp((View) vg);
                    WidgetBlurAttacher.attach("[apply]", (View) vg, cl);
                }
                try { GlyphBlurRenderer.notifyContentMaybeChangedAll(); } catch (Throwable ignored) { ModuleLog.swallow("module.notify", ignored); }
            } catch (Throwable t) {
                ModuleLog.e("MERGE", "apply post fail", t);
            }
            return result;
        }
    }
    private static final class AppWidgetHostViewHook implements XposedInterface.Hooker {
        private final ClassLoader cl;
        AppWidgetHostViewHook(ClassLoader c) { this.cl = c; }
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object self = chain.getThisObject();
            Object result = chain.proceed();
            try {
                if (self instanceof View) {
                    View v = (View) self;
                    if (v instanceof ViewGroup) {
                        WidgetBlurAttacher.resetGiveUp(v);
                        WidgetBlurAttacher.attach("[ahv]", v, cl);
                    }
                    GlyphBlurRenderer.onWidgetUpdated(v);
                }
            } catch (Throwable t) {
                ModuleLog.e("MERGE", "ahv post fail", t);
            }
            return result;
        }
    }
}
