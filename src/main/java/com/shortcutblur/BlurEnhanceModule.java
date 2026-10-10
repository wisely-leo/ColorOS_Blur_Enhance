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
public class BlurEnhanceModule extends XposedModule {
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
    private static final String CLS_POPUP_BLUR_VIEW = "com.android.launcher3.popup.PopupBlurView";
    private static final String CLS_OPLUS_POPUP = "com.android.launcher3.popup.OplusPopupContainerWithArrow";
    private static final String CLS_ARROW_POPUP = "com.android.launcher3.popup.ArrowPopup";
    private static final String CLS_OPLUS_EFFECT = "com.oplus.view.OplusViewBackgroundRenderEffect";
    private static final String CLS_DRAWABLE = "android.graphics.drawable.Drawable";
    private static final String CLS_OBJECT_ANIMATOR = "android.animation.ObjectAnimator";
    private static final String CLS_PROPERTY = "android.util.Property";
    private static final String CLS_ANIMATOR_SET = "android.animation.AnimatorSet";
    private static final String CLS_ANIMATOR = "android.animation.Animator";
    private static final String CLS_TIME_INTERPOLATOR = "android.animation.TimeInterpolator";
    private static final String CLS_DECELERATE = "android.view.animation.DecelerateInterpolator";
    private static final String M_GET_POP_BLUR_VIEW = "getPopBlurView";
    private static final String PKG_POSTEFFECT = "com.oplus.blur";
    private static final String PKG_QUICKSEARCH = "com.heytap.quicksearchbox";
    private static final String PKG_CLOCK = "com.coloros.alarmclock";
    private static final String PKG_YUYAN = "com.yuyan.pinyin.offline.release";
    private static final String CLS_EA = "e.a";
    private static final String CLS_BLUR_MGR = "com.oplus.posteffect.manager.BlurDrawableManager";
    private static final float BLUR_RADIUS = 64.0f;
    private static final long BLUR_DURATION = 330L;
    private static final int F_STATIC = 1 << 0;
    private static final int F_ICON = 1 << 1;
    private static final int F_WALL = 1 << 2;
    private static final int F_ICON_ANIM = 1 << 3;
    private static final long DEPTH_FALLBACK_DELAY = 500L;
    private volatile ClassLoader cl;
    private static volatile boolean sScreenReceiverInstalled = false;
    private static volatile boolean sConfReceiverInstalled = false;
    private static final String ACTION_SETCONF = "com.wiselyleo.blurenhance.SETCONF";
    private static final String BUILD_TAG = "v42.5";
    private volatile boolean installed = false;
    private final java.util.WeakHashMap<android.view.View, String> sLastClockText = new java.util.WeakHashMap<android.view.View, String>();
    private volatile boolean postEffectInstalled = false;
    private final Set<Method> peHooked = new HashSet<>();
    private final Map<View, Boolean> armed = new WeakHashMap<>();
    private final Map<String, ValueAnimator> iconAnims = new ConcurrentHashMap<>();
    private final Set<String> dumpedCls = new HashSet<>();
    private final Map<View, Integer> flagsCache = new WeakHashMap<>();
    static volatile boolean sShortcutBlurActive = false;
    private static volatile View sIconBlurLayer = null;
    private static volatile long sPopupSeq = 0L;
    private static volatile long sIconAnimStartedSeq = -1L;
    private static volatile boolean sOplusEffectResolved = false;
    private static volatile Class<?> sOplusEffectCls = null;
    private static volatile Method sSetBgRenderEffect = null;
    private static volatile Method sSetRenderEffectViewMethod = null;
    private static volatile boolean sOplusApiDumped = false;
    private static volatile boolean sFadingOut = false;
    private static final long SWALLOW_RESET_DELAY = 450L;
    private static volatile int sSwallowCount = 0;
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
                                try { GlyphBlurRenderer.notifyContentMaybeChangedAll(); } catch (Throwable ignored) {}
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
            if (FeatureFlags.SHORTCUT_BLUR) {
                Class<?> cls = Reflect.loadClass(CLS_POPUP_BLUR_VIEW, loader);
                if (cls != null) {
                    r.total += hookViewReturningMethod(cls, M_GET_POP_BLUR_VIEW, "pbv");
                    r.total += hookPopupFinish(cls);
                }
                Class<?> comp = Reflect.loadClass(CLS_POPUP_BLUR_VIEW + "$Companion", loader);
                if (comp != null) {
                    r.total += hookViewReturningMethod(comp, M_GET_POP_BLUR_VIEW, "pbv_companion");
                }
                for (String cn : new String[]{CLS_OPLUS_POPUP, CLS_ARROW_POPUP, CLS_POPUP_BLUR_VIEW}) {
                    Class<?> ac = Reflect.loadClass(cn, loader);
                    if (ac == null) continue;
                    r.critical += hookPopupOpenCloseAnimation(ac, "onCreateOpenAnimation", true, hooked);
                    r.critical += hookPopupOpenCloseAnimation(ac, "onCreateCloseAnimation", false, hooked);
                }
                r.total += r.critical;
                r.total += installSwallowPauseHook(loader);
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
    private int hookPopupFinish(Class<?> cls) {
        int n = 0;
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("finish")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2) continue;
                if (!"android.view.ViewGroup".equals(pt[0].getName())) continue;
                if (pt[1] != boolean.class) continue;
                Reflect.setAccessible(m);
                hook((Executable) m)
                        .setId("iconblur.finish")
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object self = chain.getThisObject();
                                try {
                                    if (self instanceof View) {
                                        View v = (View) self;
                                        int flags = resolveBlurFlags(v);
                                        ModuleLog.d("FINISH", "flags=" + flags);
                                        clearBlurFlagsCache(v);
                                        if ((flags & F_WALL) != 0) {
                                            animateDepthBlur(v, 1.0f, 0.0f, BLUR_DURATION);
                                        }
                                        fadeOutAndRemoveIconBlur();
                                        scheduleSwallowReset(v);
                                    }
                                } catch (Throwable t) {
                                    ModuleLog.e("FINISH", "hook body failed", t);
                                }
                                return chain.proceed();
                            }
                        });
                n++;
            }
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookPopupFinish failed", t);
        }
        return n;
    }
    private int hookPopupOpenCloseAnimation(Class<?> cls, final String methodName, final boolean opening,
                                  Set<Method> hooked) {
        int n = 0;
        try {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(methodName)) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length != 1) continue;
                    if (!"android.animation.AnimatorSet".equals(pt[0].getName())) continue;
                    if (!hooked.add(m)) {
                        ModuleLog.d("INSTALL", "skip dup hook " + c.getName() + "." + methodName);
                        continue;
                    }
                    Reflect.setAccessible(m);
                    hook((Executable) m)
                            .setId("iconblur.opa." + methodName)
                            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object self = chain.getThisObject();
                                    Object set = chain.getArg(0);
                                    Object result = chain.proceed();
                                    try {
                                        View anchor = null;
                                        if (self != null) {
                                            Object pbv = Reflect.readField(self, "mPopBlurView");
                                            if (pbv instanceof View) anchor = (View) pbv;
                                            if (anchor == null && self instanceof View) anchor = (View) self;
                                        }
                                        if (anchor != null && set != null) {
                                            int flags = resolveBlurFlags(anchor);
                                            ModuleLog.d("ANIM", methodName + " flags=" + flags);
                                            if ((flags & F_WALL) != 0) {
                                                float from = opening ? 0.0f : 1.0f;
                                                float to = opening ? 1.0f : 0.0f;
                                                Object anim = createDepthBlurAnimation(anchor, from, to, BLUR_DURATION);
                                                if (anim != null) {
                                                    playIntoAnimatorSet(set, anim);
                                                }
                                            }
                                            if (opening && (flags & (F_ICON | F_ICON_ANIM)) != 0) {
                                                final View lt = sIconBlurLayer;
                                                if (lt != null) {
                                                    Object iconAnim = createIconBlurAnimator(lt, 0.0f, BLUR_RADIUS, BLUR_DURATION);
                                                    if (iconAnim instanceof ValueAnimator) {
                                                        ((ValueAnimator) iconAnim).start();
                                                        ModuleLog.d("ICONANIM", "open anim -> started icon blur fade-in (sync)");
                                                    }
                                                }
                                            }
                                            if (!opening) {
                                                clearBlurFlagsCache(anchor);
                                                fadeOutAndRemoveIconBlur();
                                                scheduleSwallowReset(anchor);
                                            }
                                        }
                                    } catch (Throwable t) {
                                        ModuleLog.e("ANIM", "hook body failed (" + methodName + ")", t);
                                    }
                                    return result;
                                }
                            });
                    n++;
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookPopupOpenCloseAnimation(" + methodName + ") failed", t);
        }
        return n;
    }
    private static View ensureIconBlurLayer(View pbv) {
        try {
            if (pbv == null) return null;
            Object parent = pbv.getParent();
            if (!(parent instanceof ViewGroup)) {
                ModuleLog.d("ICONBLUR", "pbv parent not ViewGroup: " + (parent == null ? "null" : parent.getClass().getName()));
                return null;
            }
            ViewGroup vg = (ViewGroup) parent;
            View existing = sIconBlurLayer;
            if (existing != null && existing.getParent() == vg) {
                ModuleLog.d("ICONBLUR", "icon blur layer reused (already attached)");
                return existing;
            }
            View layer = new View(pbv.getContext());
            layer.setClickable(false);
            layer.setFocusable(false);
            layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            int pvIndex = vg.indexOfChild(pbv);
            vg.addView(layer, pvIndex >= 0 ? pvIndex : vg.getChildCount(), lp);
            sIconBlurLayer = layer;
            ModuleLog.d("ICONBLUR", "icon blur layer added to " + vg.getClass().getSimpleName()
                    + " idx=" + (pvIndex >= 0 ? pvIndex : vg.getChildCount()) + " children=" + vg.getChildCount());
            return layer;
        } catch (Throwable t) {
            ModuleLog.e("ICONBLUR", "ensureIconBlurLayer failed", t);
            return null;
        }
    }
    private void fadeOutAndRemoveIconBlur() {
        final View layer = sIconBlurLayer;
        if (sFadingOut) {
            ModuleLog.d("ICONANIM", "already fading out, skip duplicate fade-out");
            return;
        }
        sFadingOut = true;
        sPopupSeq++;
        sIconAnimStartedSeq = -1L;
        if (layer == null) { sFadingOut = false; return; }
        try {
            final String key = System.identityHashCode(layer) + "";
            synchronized (iconAnims) {
                ValueAnimator old = iconAnims.remove(key);
                if (old != null) { try { old.cancel(); } catch (Throwable ignore) {} }
            }
            final ValueAnimator va = ValueAnimator.ofFloat(BLUR_RADIUS, 0.0f);
            va.setDuration(BLUR_DURATION);
            va.setInterpolator(new DecelerateInterpolator());
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    try {
                        float r = (Float) a.getAnimatedValue();
                        applyIconBlurRadius(layer, r, true);
                    } catch (Throwable ignore) {}
                }
            });
            synchronized (iconAnims) {
                iconAnims.put(key, va);
            }
            va.start();
            ModuleLog.d("ICONANIM", "fade-out 64.0->0.0 dur=" + BLUR_DURATION);
            layer.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        removeAnimation(key);
                        if (sIconBlurLayer != layer) {
                            ModuleLog.d("ICONANIM", "fade-out done, layer already replaced, skip remove");
                            sFadingOut = false;
                            return;
                        }
                        removeIconBlurLayer();
                        sFadingOut = false;
                    } catch (Throwable ignore) {}
                }
            }, BLUR_DURATION + 16L);
        } catch (Throwable t) {
            ModuleLog.e("ICONANIM", "fadeOutAndRemoveIconBlur failed", t);
            removeIconBlurLayer();
            sFadingOut = false;
        }
    }
    private void removeIconBlurLayer() {
        try {
            View layer = sIconBlurLayer;
            if (layer == null) return;
            final String key = System.identityHashCode(layer) + "";
            synchronized (iconAnims) {
                ValueAnimator old = iconAnims.remove(key);
                if (old != null) { try { old.cancel(); } catch (Throwable ignore) {} }
            }
            try { applyIconBlurRadius(layer, 0.0f, false); } catch (Throwable ignore) {}
            sIconAnimStartedSeq = -1L;
            ViewGroup vg = (ViewGroup) layer.getParent();
            if (vg != null) vg.removeView(layer);
            sIconBlurLayer = null;
            ModuleLog.d("ICONBLUR", "icon blur layer removed (+anim state cleared)");
        } catch (Throwable t) {
            sIconBlurLayer = null;
        }
    }
    private void scheduleSwallowReset(final View anchor) {
        try {
            View post = anchor;
            if (post == null) return;
            post.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sShortcutBlurActive = false;
                    ModuleLog.d("PAUSE", "window CLOSE (delayed reset, exit anim done)");
                }
            }, SWALLOW_RESET_DELAY);
        } catch (Throwable t) {
            sShortcutBlurActive = false;
            ModuleLog.e("PAUSE", "scheduleSwallowReset failed", t);
        }
    }
    private int installSwallowPauseHook(ClassLoader loader) {
        int n = 0;
        try {
            Class<?> cls = Reflect.loadClass(CLS_BLUR_MGR, loader);
            if (cls == null) {
                ModuleLog.d("PAUSE", "[miss] " + CLS_BLUR_MGR);
                return 0;
            }
            for (String name : new String[]{"pauseWindowBlur", "resumeWindowBlur"}) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    Reflect.setAccessible(m);
                    final boolean isPause = "pauseWindowBlur".equals(name);
                    hook((Executable) m)
                            .setId("swallow.pause." + name + "." + m.getParameterTypes().length)
                            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    if (isPause && sShortcutBlurActive) {
                                        sSwallowCount++;
                                        ModuleLog.d("PAUSE", "swallow pauseWindowBlur during shortcut (#" + sSwallowCount + ")");
                                        return null;
                                    }
                                    return chain.proceed();
                                }
                            });
                    n++;
                    ModuleLog.d("PAUSE", "[hook] " + name + "/" + m.getParameterTypes().length);
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("PAUSE", "installSwallowPauseHook failed", t);
        }
        return n;
    }
    private int hookViewReturningMethod(Class<?> cls, String methodName, String id) {
        int n = 0;
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                Reflect.setAccessible(m);
                final String mid = id + "#" + m.getParameterTypes().length;
                hook((Executable) m)
                        .setId("hook." + mid)
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object result = chain.proceed();
                                try {
                                    if (result instanceof View) {
                                        armBlurForView((View) result, mid);
                                    }
                                } catch (Throwable t) {
                                    ModuleLog.e("LIVE", "hook body failed", t);
                                }
                                return result;
                            }
                        });
                n++;
            }
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookViewReturningMethod(" + id + ") failed", t);
        }
        return n;
    }
    private int resolveBlurFlags(View view) {
        if (view == null) return 0;
        Integer c = flagsCache.get(view);
        if (c != null) return c;
        int flags = isInsideOpenFolder(view)
                ? (F_STATIC | F_ICON | F_ICON_ANIM)
                : (F_STATIC | F_ICON | F_WALL);
        flagsCache.put(view, flags);
        return flags;
    }
    private void clearBlurFlagsCache(View view) {
        if (view == null) return;
        flagsCache.remove(view);
    }
    private void armBlurForView(View view, String mid) {
        if (view == null) return;
        try {
            ModuleLog.d("LIVE", "armBlurForView id=" + mid);
            if (sFadingOut) {
                sFadingOut = false;
                ModuleLog.d("ICONANIM", "enter during fade-out, aborted fade-out state");
            }
            sShortcutBlurActive = true;
            ModuleLog.d("PAUSE", "window OPEN (armBlurForView id=" + mid + ")");
            final int flags = resolveBlurFlags(view);
            ModuleLog.d("LIVE", "flags=" + flags + " (static=" + ((flags & F_STATIC) != 0)
                    + " icon=" + ((flags & F_ICON) != 0) + " wall=" + ((flags & F_WALL) != 0) + ")");
            setIconBlurArmed(view, true);
            if ((flags & F_STATIC) != 0) clearStaticLayers(view);
            final View fv = view;
            View iconTarget = fv;
            if ((flags & (F_ICON | F_ICON_ANIM)) != 0) {
                View layer = ensureIconBlurLayer(fv);
                if (layer != null) {
                    iconTarget = layer;
                    setIconBlurArmed(fv, false);
                    setIconBlurArmed(iconTarget, true);
                }
            }
            final View itv = iconTarget;
            if ((flags & (F_ICON | F_ICON_ANIM)) != 0) {
                final long seq = sPopupSeq;
                if (sIconAnimStartedSeq != seq) {
                    sIconAnimStartedSeq = seq;
                    try { applyIconBlurRadius(itv, 0.01f, false); } catch (Throwable ignore) {}
                    ModuleLog.d("ICONANIM", "arm icon (defer anim to open, seq=" + seq + ")");
                } else {
                    ModuleLog.d("ICONANIM", "same popup round, skip re-arm (seq=" + seq + ")");
                }
            }
            if ((flags & F_WALL) == 0) return;
            fv.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Object launcher = getLauncherQuietly(fv);
                        if (launcher == null) {
                            ModuleLog.d("DEPTH", "launcher null, skip retry");
                            return;
                        }
                        Object dc = Reflect.call(launcher, "getDepthController");
                        if (dc == null) {
                            ModuleLog.d("DEPTH", "depthController null");
                            return;
                        }
                        Object g = Reflect.call(dc, "getCurrentBlur");
                        float v = (g instanceof Float) ? (Float) g : -1f;
                        ModuleLog.d("DEPTH", "currentBlur=" + v);
                        if (v <= 0.05f) {
                            boolean ok = setDepthBlur(fv, 1.0f);
                            ModuleLog.d("DEPTH", "fallback setBlur=1 ok=" + ok);
                        }
                    } catch (Throwable t) {
                        ModuleLog.e("DEPTH", "retry failed", t);
                    }
                }
            }, DEPTH_FALLBACK_DELAY);
        } catch (Throwable t) {
            ModuleLog.e("LIVE", "armBlurForView failed", t);
        }
    }
    private void applyIconBlurRadius(View view, float radius) {
        applyIconBlurRadius(view, radius, true);
    }
    private void dumpOplusApiOnce(Class<?> cls) {
        if (sOplusApiDumped) return;
        String key = cls.getName();
        synchronized (dumpedCls) {
            if (!dumpedCls.add(key)) { sOplusApiDumped = true; return; }
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (Method m : cls.getDeclaredMethods()) {
                sb.append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(")->").append(m.getReturnType().getSimpleName()).append(" | ");
            }
            ModuleLog.d("OPLUSDUMP", sb.toString());
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? f.get(null) : null;
                    ModuleLog.d("OPLUSDUMP", "FIELD " + f.getName() + " type=" + f.getType().getSimpleName() + " val=" + val);
                } catch (Throwable ig) {}
            }
            for (Class<?> c = cls.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
                StringBuilder s2 = new StringBuilder("SUPER " + c.getName() + ": ");
                for (Method m : c.getDeclaredMethods()) {
                    s2.append(m.getName()).append('(');
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) s2.append(',');
                        s2.append(ps[i].getSimpleName());
                    }
                    s2.append(") | ");
                }
                ModuleLog.d("OPLUSDUMP", s2.toString());
            }
        } catch (Throwable t) {
            ModuleLog.e("OPLUSDUMP", "dump failed", t);
        }
        sOplusApiDumped = true;
    }
    private void applyIconBlurRadius(View view, float radius, boolean useOplus) {
        if (view == null) return;
        try {
            RenderEffect effect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR);
            if (!useOplus) {
                trySetViewRenderEffect(view, effect);
                return;
            }
            boolean oplusOk = false;
            try {
                Class<?> cls = resolveOplusEffectCls();
                if (cls != null) {
                    dumpOplusApiOnce(cls);
                    Method m = sSetBgRenderEffect;
                    if (m != null) {
                        m.invoke(null, effect, view);
                        oplusOk = true;
                    }
                }
            } catch (Throwable t) {
                ModuleLog.d("ICONBLUR", "oplus path failed: " + t);
            }
            if (!oplusOk) {
                String err = trySetViewRenderEffect(view, effect);
                if (ModuleLog.enabled()) ModuleLog.d("ICONBLUR", "fallback setRenderEffect err=" + err);
            }
        } catch (Throwable t) {
            ModuleLog.e("ICONBLUR", "applyIconBlurRadius failed", t);
        }
    }
    private Class<?> resolveOplusEffectCls() {
        if (sOplusEffectResolved) return sOplusEffectCls;
        synchronized (BlurEnhanceModule.class) {
            if (sOplusEffectResolved) return sOplusEffectCls;
            try {
                Class<?> cls = Reflect.loadClass(CLS_OPLUS_EFFECT, currentClassLoader());
                if (cls != null) {
                    sSetBgRenderEffect = Reflect.method(cls, "setBackgroundRenderEffect", RenderEffect.class, View.class);
                }
                sOplusEffectCls = cls;
            } finally {
                sOplusEffectResolved = true;
            }
        }
        return sOplusEffectCls;
    }
    private void setIconBlurArmed(View view, boolean value) {
        if (view == null) return;
        synchronized (armed) {
            if (value) {
                armed.put(view, Boolean.TRUE);
            } else {
                armed.remove(view);
            }
        }
    }
    private boolean isIconBlurArmed(View view) {
        if (view == null) return false;
        synchronized (armed) {
            return armed.containsKey(view);
        }
    }
    private boolean isCurrentAnimation(String key, ValueAnimator va) {
        synchronized (iconAnims) {
            return iconAnims.get(key) == va;
        }
    }
    private void removeAnimation(String key) {
        synchronized (iconAnims) {
            iconAnims.remove(key);
        }
    }
    private void clearStaticLayers(View view) {
        try {
            Class<?> drawableCls = Reflect.loadClass(CLS_DRAWABLE, currentClassLoader());
            boolean wall = false;
            boolean drag = false;
            if (drawableCls != null) {
                Method mw = Reflect.method(view.getClass(), "setWallpaperDrawable", drawableCls);
                if (mw != null) {
                    try { mw.invoke(view, (Object) null); wall = true; } catch (Throwable ignore) {}
                }
                Method md = Reflect.method(view.getClass(), "setDragLayerDrawable", drawableCls);
                if (md != null) {
                    try { md.invoke(view, (Object) null); drag = true; } catch (Throwable ignore) {}
                }
            }
            Field f = Reflect.field(view.getClass(), "mIsBlurUnavailable");
            if (f != null) {
                try { f.setBoolean(view, true); } catch (Throwable ignore) {}
            }
            Reflect.call(view, "invalidate");
            ModuleLog.d("CLEAR", "staticLayers wall=" + wall + " drag=" + drag);
        } catch (Throwable t) {
            ModuleLog.e("CLEAR", "clearStaticLayers failed", t);
        }
    }
    private String trySetViewRenderEffect(View view, RenderEffect effect) {
        try {
            Method m = sSetRenderEffectViewMethod;
            if (m == null) {
                m = Reflect.method(View.class, "setRenderEffect", RenderEffect.class);
                sSetRenderEffectViewMethod = m;
            }
            if (m == null) return "setRenderEffect not found";
            m.invoke(view, effect);
            return null;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }
    private Object createDepthBlurAnimation(View view, float from, float to, long duration) {
        try {
            Object launcher = getLauncherQuietly(view);
            if (launcher == null) return null;
            Object dc = Reflect.call(launcher, "getDepthController");
            if (dc == null) return null;
            Object prop = getStaticFloatProperty(dc, "BLUR");
            if (prop == null) return null;
            float cur = from;
            try {
                Object g = Reflect.call(dc, "getCurrentBlur");
                if (g instanceof Float) {
                    float v = (Float) g;
                    if (v >= 0.0f) cur = v;
                }
            } catch (Throwable ignore) {}
            Class<?> oaCls = Reflect.loadClass(CLS_OBJECT_ANIMATOR, currentClassLoader());
            Class<?> propCls = Reflect.loadClass(CLS_PROPERTY, currentClassLoader());
            if (oaCls == null || propCls == null) return null;
            Method ofFloat = Reflect.method(oaCls, "ofFloat", Object.class, propCls, float[].class);
            if (ofFloat == null) return null;
            Object anim = ofFloat.invoke(null, dc, prop, new float[]{cur, to});
            if (anim == null) return null;
            Class<?> animCls = Reflect.loadClass(CLS_ANIMATOR, currentClassLoader());
            if (animCls == null) return null;
            Method setDur = Reflect.method(animCls, "setDuration", long.class);
            if (setDur != null) setDur.invoke(anim, duration);
            Class<?> ipCls = Reflect.loadClass(CLS_TIME_INTERPOLATOR, currentClassLoader());
            Class<?> decCls = Reflect.loadClass(CLS_DECELERATE, currentClassLoader());
            if (ipCls != null && decCls != null) {
                Object decObj = Reflect.newInstance(decCls);
                Method setI = Reflect.method(animCls, "setInterpolator", ipCls);
                if (setI != null && decObj != null) setI.invoke(anim, decObj);
            }
            return anim;
        } catch (Throwable t) {
            ModuleLog.e("ANIM", "createDepthBlurAnimation failed", t);
            return null;
        }
    }
    private boolean animateDepthBlur(View view, float from, float to, long duration) {
        try {
            Object anim = createDepthBlurAnimation(view, from, to, duration);
            if (anim != null) {
                Class<?> animCls = Reflect.loadClass(CLS_ANIMATOR, currentClassLoader());
                if (animCls != null) {
                    Method start = Reflect.method(animCls, "start");
                    if (start != null) {
                        start.invoke(anim);
                        return true;
                    }
                }
            }
            return setDepthBlur(view, to);
        } catch (Throwable t) {
            return false;
        }
    }
    private Object createIconBlurAnimator(final View view, final float from, final float to, long duration) {
        if (view == null) return null;
        try {
            final String key = System.identityHashCode(view) + "";
            synchronized (iconAnims) {
                ValueAnimator old = iconAnims.remove(key);
                if (old != null) { try { old.cancel(); } catch (Throwable ignore) {} }
            }
            final ValueAnimator va = ValueAnimator.ofFloat(from, to);
            va.setDuration(duration);
            va.setInterpolator(new DecelerateInterpolator());
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                private float last = Float.NaN;
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    try {
                        if (!isCurrentAnimation(key, va)) return;
                        float r = (Float) a.getAnimatedValue();
                        if (!Float.isNaN(last) && Math.abs(r - last) < 0.5f) return;
                        last = r;
                        if (!isIconBlurArmed(view)) return;
                        applyIconBlurRadius(view, r, true);
                    } catch (Throwable ignore) {}
                }
            });
            synchronized (iconAnims) {
                iconAnims.put(key, va);
            }
            ModuleLog.d("ICONANIM", "create icon animator " + from + "->" + to + " dur=" + duration);
            return va;
        } catch (Throwable t) {
            ModuleLog.e("ICONANIM", "createIconBlurAnimator failed", t);
            return null;
        }
    }
    private void playIntoAnimatorSet(Object set, Object anim) {
        try {
            Class<?> setCls = Reflect.loadClass(CLS_ANIMATOR_SET, currentClassLoader());
            Class<?> animCls = Reflect.loadClass(CLS_ANIMATOR, currentClassLoader());
            if (setCls == null || animCls == null) return;
            Method play = Reflect.method(setCls, "play", animCls);
            if (play != null) play.invoke(set, anim);
        } catch (Throwable t) {
        }
    }
    private boolean setDepthBlur(View view, float value) {
        try {
            Object launcher = getLauncherQuietly(view);
            if (launcher == null) return false;
            Object dc = Reflect.call(launcher, "getDepthController");
            if (dc == null) return false;
            Method m = Reflect.method(dc.getClass(), "setBlurWithoutAnim", float.class);
            if (m == null) return false;
            m.invoke(dc, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    private Object getStaticFloatProperty(Object dc, String name) {
        Field f = Reflect.field(dc.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(null);
        } catch (Throwable ignore) {
        }
        try {
            return f.get(dc);
        } catch (Throwable t) {
            ModuleLog.d("DEPTH", "BLUR field not static nor instance-accessible: " + t);
            return null;
        }
    }
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
                new String[]{ "android.view.SurfaceControl", "java.lang.Float", "java.lang.Integer", "java.lang.Long" },
                "c");
        critical += hookBySignature(cls, "e",
                new String[]{ "android.view.SurfaceControl", "java.lang.Float", "java.lang.Integer", "java.lang.Long" },
                "e");
        critical += hookBySignature(cls, "d",
                new String[]{ "android.view.SurfaceControl", "float", "int", "long" },
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
                                ModuleLog.d("SCALE", tag + " " + old + " -> " + peTarget);
                            } else {
                                ModuleLog.d("SCALE", tag + " keep " + old);
                            }
                        } else {
                            ModuleLog.d("SCALE", tag + " no float arg (pos=" + pos + ")");
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
    private boolean isInsideOpenFolder(View view) {
        try {
            ViewGroup dragLayer = ViewUtils.ancestorGroupOfType(view, "DragLayer");
            if (dragLayer == null) {
                ModuleLog.d("FOLDER", "no dragLayer found, chain=" + ViewUtils.dumpViewChainNames(view));
                return false;
            }
            for (int i = 0; i < dragLayer.getChildCount(); i++) {
                View c = dragLayer.getChildAt(i);
                String cn = c.getClass().getName();
                if (cn.contains("Workspace")) {
                    float a = c.getAlpha();
                    int vis = c.getVisibility();
                    boolean inFolder = (a < 0.9f) || (vis != View.VISIBLE);
                    ModuleLog.d("FOLDER", "workspace vis=" + vis + " alpha=" + a + " -> inFolder=" + inFolder);
                    return inFolder;
                }
            }
            ModuleLog.d("FOLDER", "no workspace child, dragLayer children=" + ViewUtils.dumpChildViewNames(dragLayer));
            return false;
        } catch (Throwable t) {
            ModuleLog.e("FOLDER", "isInsideOpenFolder failed", t);
            return false;
        }
    }
    private ClassLoader currentClassLoader() {
        return cl;
    }
    private volatile boolean sRemoteViewsHooked = false;
    private void hookRemoteViewsApply(ClassLoader cl) {
        if (sRemoteViewsHooked) return;   // 幂等：同进程多次 onPackageReady 不重复 hook
        sRemoteViewsHooked = true;
        try {
            Method m = RemoteViews.class.getDeclaredMethod("apply", android.content.Context.class, ViewGroup.class);
            hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(new RemoteViewsApplyHook(cl));
            ModuleLog.i("[merge] hooked RemoteViews.apply");
        } catch (Throwable t) {
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
            ModuleLog.e("MERGE", "RemoteViews.reapply hook fail", t);
        }
    }
    private volatile boolean sAhvHooked = false;
    private void hookAppWidgetHostView(ClassLoader cl) {
        if (sAhvHooked) return;   // 幂等：同进程多次 onPackageReady 不重复 hook
        sAhvHooked = true;
        try {
            Class<?> ahv = Class.forName("android.appwidget.AppWidgetHostView", false, cl);
            for (Method m : ahv.getDeclaredMethods()) {
                if (m.getName().equals("updateAppWidget")) {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(new AppWidgetHostViewHook(cl));
                    ModuleLog.i("[merge] hooked AppWidgetHostView.updateAppWidget");
                }
            }
} catch (Throwable t) {
            ModuleLog.e("MERGE", "AppWidgetHostView hook fail", t);
        }
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
                try { GlyphBlurRenderer.notifyContentMaybeChangedAll(); } catch (Throwable ignored) {}
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
