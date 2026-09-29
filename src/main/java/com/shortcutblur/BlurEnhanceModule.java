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
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.RemoteViews;

import java.lang.reflect.Constructor;
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

public class BlurEnhanceModule extends XposedModule {

    private static final String CLS_POPUP_BLUR_VIEW = "com.android.launcher3.popup.PopupBlurView";
    private static final String CLS_OPLUS_POPUP = "com.android.launcher3.popup.OplusPopupContainerWithArrow";
    private static final String CLS_ARROW_POPUP = "com.android.launcher3.popup.ArrowPopup";
    private static final String CLS_LAUNCHER = "com.android.launcher.Launcher";

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

    private static final String PKG_CLOCK = "com.coloros.alarmclock";
    private static final String CLS_EA = "e.a";
    /** launcher 进程内的 posteffect 管理器：吞掉 shortcut 期间的 pauseWindowBlur，保住活模糊不被冻结。 */
    private static final String CLS_BLUR_MGR = "com.oplus.posteffect.manager.BlurDrawableManager";
    private static final float SAMPLE_SCALE = 0.5f;

    private static final float BLUR_RADIUS = 64.0f;
    private static final long BLUR_DURATION = 330L;

    private static final int F_STATIC = 1 << 0;
    private static final int F_ICON = 1 << 1;
    private static final int F_WALL = 1 << 2;
    private static final int F_ICON_ANIM = 1 << 3;

    private static final long ICON_BLUR_DELAY = 32L;
    private static final long DEPTH_FALLBACK_DELAY = 500L;

    private volatile ClassLoader cl;
    private static volatile boolean sScreenReceiverInstalled = false;

    private volatile boolean installed = false;
    private final java.util.WeakHashMap<android.view.View, String> sLastClockText = new java.util.WeakHashMap<android.view.View, String>();

    private volatile boolean postEffectInstalled = false;
    private final Set<Method> peHooked = new HashSet<>();

    private final Map<View, Boolean> armed = new WeakHashMap<>();
    private final Map<String, ValueAnimator> iconAnims = new ConcurrentHashMap<>();
    private final Set<String> dumpedCls = new HashSet<>();
    private final Map<View, Integer> flagsCache = new WeakHashMap<>();

    private volatile RenderEffect blurEffect;

    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    /** shortcut 弹窗打开窗口期：期间吞掉 pauseWindowBlur，保持 posteffect 模糊服务持续采样（=保住动态）。 */
    static volatile boolean sShortcutBlurActive = false;
    /** 【图标模糊新挂点】独立空 View（照 Stack.mBlurEffectView 做法）：在图标之上、菜单之下，避免挂容器吃掉图标。 */
    private static volatile View sIconBlurLayer = null;
    /** 每次 shortcut 弹窗递增的序列号（用于让 F_ICON 动画只在本轮首次 arm 启动，避免被同一轮的第二次 arm 取消）。 */
    private static volatile long sPopupSeq = 0L;
    private static volatile long sIconAnimStartedSeq = -1L;
    /** 当前是否正在做退出淡出（幂等保护：同一次退出的双路径只应触发一次淡出）。 */
    private static volatile boolean sFadingOut = false;
    /** 退出复位延迟（> 退场动画 330ms），避免退场瞬间静态先恢复再被切走。 */
    private static final long SWALLOW_RESET_DELAY = 450L;
    /** shortcut 期间被吞掉的 pause 次数（诊断用）。 */
    private static volatile int sSwallowCount = 0;
    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            if (param == null) return;
            String pkg = param.getPackageName();
            ModuleLog.i("onPackageReady pkg=" + pkg);
            installScreenReceiverViaApp(param);
            if (PKG_POSTEFFECT.equals(pkg)) {
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

            if (PKG_CLOCK.equals(pkg)) {
                ClockTextAlphaHook.install(this, param.getClassLoader());
            }

            ClassLoader anyLoader = param.getClassLoader();
            if (anyLoader != null) {
                hookRemoteViewsApply(anyLoader);
                hookAppWidgetHostView(anyLoader);
            }

            if (!isTargetLauncher(pkg)) return;
            ClassLoader loader = param.getClassLoader();
            if (loader == null) return;
            this.cl = loader;

            if (installed) {
                ModuleLog.d("READY", "already installed, skip");
                return;
            }

            hookTextViewSetText(loader);
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
                        GlyphBlurRenderer.setScreenOn(on);
                    }
                }, f);
                sScreenReceiverInstalled = true;
                ModuleLog.d("READY", "screen receiver installed ctx=" + ctx.getClass().getName());
            } catch (Throwable t) {
                ModuleLog.d("SCREEN", "registerReceiver err: " + t);
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
            Class<?> cls = loadClass(CLS_POPUP_BLUR_VIEW, loader);
            if (cls != null) {
                r.total += hookViewReturningMethod(cls, M_GET_POP_BLUR_VIEW, "pbv");
                r.total += hookPopupFinish(cls);
            }
            Class<?> comp = loadClass(CLS_POPUP_BLUR_VIEW + "$Companion", loader);
            if (comp != null) {
                r.total += hookViewReturningMethod(comp, M_GET_POP_BLUR_VIEW, "pbv_companion");
            }

            for (String cn : new String[]{CLS_OPLUS_POPUP, CLS_ARROW_POPUP, CLS_POPUP_BLUR_VIEW}) {
                Class<?> ac = loadClass(cn, loader);
                if (ac == null) continue;
                r.critical += hookPopupOpenCloseAnimation(ac, "onCreateOpenAnimation", true, hooked);
                r.critical += hookPopupOpenCloseAnimation(ac, "onCreateCloseAnimation", false, hooked);
            }
            r.total += r.critical;
            r.total += installSwallowPauseHook(loader);
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
                setAccessibleQuietly(m);
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
                                        // 【渐降】不再调 clearIconBlurByFlags（它会把模糊瞬间清成 0，导致 fade-out 变成 0->0）。
                                        // 转交由 fadeOutAndRemoveIconBlur 做 64->0 淡出，动画结束 removeIconBlurLayer 内部再 clearIconBlur 兜底。
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
                    setAccessibleQuietly(m);
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
                                            Object pbv = getFieldQuietlyAny(self, "mPopBlurView");
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
                                            // 【渐进时机】此刻（onCreateOpenAnimation 执行时）系统菜单展开动画刚开始，
                                            // 直接 start 图标模糊 0->64，与之同帧同步；不再 postDelayed（会晚 100ms+），
                                            // 也不 playIntoAnimatorSet（系统的 set 需自行 start，加入后并不能立即生效）。
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
                                                // 【渐降】同上：不做瞬清，交给 fadeOutAndRemoveIconBlur 的 64->0 淡出。
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

    /**
     * 【吞暂停】hook launcher 进程内 {@code BlurDrawableManager} 的 pauseWindowBlur / resumeWindowBlur：
     * shortcut 弹窗窗口期（sShortcutBlurActive=true）只吞 pauseWindowBlur、永远放行 resumeWindowBlur，
     * 避免 PopupBlurView.createBlurAnim 触发的全局暂停把 posteffect 模糊服务冻结（=保持动态）。
     */
    /**
     * 【退出延迟复位】退场动画（~330ms alpha）刚触发时不能立即复位 sShortcutBlurActive，
     * 否则退场期间 pauseWindowBlur 放行 -> 静态先恢复 -> 退出闪现。延迟 SWALLOW_RESET_DELAY(450ms) 再复位。
     */
    /**
     * 【图标模糊新挂点】在 pbv 的父容器（含图标的 BaseDragLayer）里，pbv 之下插入一个全屏空 View，
     * 把模糊挂到这个空 View 上（setBackgroundRenderEffect 糊的是其背后内容 = 含图标），
     * 避免直接挂 anchor 容器导致图标被吃掉 / 扁平化。返回该空 View（失败返回 null）。
     */
    private static View ensureIconBlurLayer(View pbv) {
        try {
            if (pbv == null) return null;
            Object parent = pbv.getParent();
            if (!(parent instanceof ViewGroup)) {
                ModuleLog.d("ICONBLUR", "pbv parent not ViewGroup: " + (parent == null ? "null" : parent.getClass().getName()));
                return null;
            }
            ViewGroup vg = (ViewGroup) parent;
            // 【复用】已存在且仍挂在同一 parent 上的挂点直接复用，避免重复创建造成泄漏。
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

    /**
     * 【退出渐降】退出时先 64->0 淡出，动画结束再移除挂点并递增序列号。
     * 不走 animateIconBlur 的 skip dup，避免被上一轮残留状态误跳过。
     * 同时：退出即递增 sPopupSeq（显式标记“新一轮”），不依赖带延迟的 sShortcutBlurActive。
     */
    private void fadeOutAndRemoveIconBlur() {
        final View layer = sIconBlurLayer;
        // 【幂等】退出的双路径（finish + onCreateCloseAnimation）会各调一次，
        // 若已在淡出中则直接忽略，否则会出现“两条 346ms 回调”互相取消 + 第一条先删挂点导致图标闪烁。
        if (sFadingOut) {
            ModuleLog.d("ICONANIM", "already fading out, skip duplicate fade-out");
            return;
        }
        sFadingOut = true;
        // 先递增序列号：无论下面是否有挂点，退出都意味着“下一轮是新轮”。
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
                        // 【护栏】若这期间新一轮已复用/替换了挂点，则本次淡出回调不能误删新轮的挂点。
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

    /** 移除图标模糊空 View，并清理该挂点的动画去重状态（否则下次弹窗会被 animateIconBlur 的 skip dup 误跳过）。 */
    private void removeIconBlurLayer() {
        try {
            View layer = sIconBlurLayer;
            if (layer == null) return;
            final String key = System.identityHashCode(layer) + "";
            synchronized (iconAnims) {
                ValueAnimator old = iconAnims.remove(key);
                if (old != null) { try { old.cancel(); } catch (Throwable ignore) {} }
            }
            // 【消闪】移除前先把半径硬归零，确保挂点不带残留模糊地离开层级，避免删除瞬间跳变闪烁。
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
            Class<?> cls = loadClass(CLS_BLUR_MGR, loader);
            if (cls == null) {
                ModuleLog.d("PAUSE", "[miss] " + CLS_BLUR_MGR);
                return 0;
            }
            for (String name : new String[]{"pauseWindowBlur", "resumeWindowBlur"}) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    setAccessibleQuietly(m);
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
                                        return null; // 吞掉暂停，不放行
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
                setAccessibleQuietly(m);
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
        synchronized (flagsCache) {
            Integer c = flagsCache.get(view);
            if (c != null) return c;
        }
        int flags = isInsideOpenFolder(view)
                ? (F_STATIC | F_ICON | F_ICON_ANIM)
                : (F_STATIC | F_ICON | F_WALL);
        synchronized (flagsCache) {
            flagsCache.put(view, flags);
        }
        return flags;
    }

    private void clearBlurFlagsCache(View view) {
        if (view == null) return;
        synchronized (flagsCache) {
            flagsCache.remove(view);
        }
    }

    private void armBlurForView(View view, String mid) {
        if (view == null) return;
        try {
            ModuleLog.d("LIVE", "armBlurForView id=" + mid);
            // 【打断退出淡出】若上一轮的退出淡出还没跑完就再次进入，先取消淡出状态。
            // （已递增的 sPopupSeq 保留，正好让本轮成为“新一轮”，动画能正常启动。）
            if (sFadingOut) {
                sFadingOut = false;
                ModuleLog.d("ICONANIM", "enter during fade-out, aborted fade-out state");
            }
            // 【吞暂停】置位窗口开：早于 PopupBlurView.createBlurAnim 内的 pauseWindowBlur 调用，才能吞掉它。
            sShortcutBlurActive = true;
            // 【序列号】“新一轮”的判定完全交给退出端（fadeOutAndRemoveIconBlur 负责 sPopupSeq++ 且 sIconAnimStartedSeq=-1）。
            // 这里不再依赖带延迟的 wasActive，避免连续快速进出时误判成同一轮 -> 跳过动画。
            ModuleLog.d("PAUSE", "window OPEN (armBlurForView id=" + mid + ")");
            final int flags = resolveBlurFlags(view);
            ModuleLog.d("LIVE", "flags=" + flags + " (static=" + ((flags & F_STATIC) != 0)
                    + " icon=" + ((flags & F_ICON) != 0) + " wall=" + ((flags & F_WALL) != 0) + ")");

            setIconBlurArmed(view, true);

            if ((flags & F_STATIC) != 0) clearStaticLayers(view);

            final View fv = view;

            // 【图标模糊新挂点】在 pbv 之下插入全屏空 View，把模糊挂到它上，
            // 避免挂 anchor 容器导致图标被吃掉。挂点失败则回退到 fv（保持旧行为）。
            // 【关键】armed 标志必须打到「实际挂模糊的 target」上，否则 isIconBlurArmed(target) 为 false -> skipped: disarmed。
            View iconTarget = fv;
            if ((flags & (F_ICON | F_ICON_ANIM)) != 0) {
                View layer = ensureIconBlurLayer(fv);
                if (layer != null) {
                    iconTarget = layer;
                    setIconBlurArmed(fv, false);          // 原 anchor 不再需要标志
                    setIconBlurArmed(iconTarget, true);   // 标志改打到实际挂点
                }
            }
            final View itv = iconTarget;
            if ((flags & (F_ICON | F_ICON_ANIM)) != 0) {
                // 【渐进】文件夹内(F_ICON_ANIM)与桌面(F_ICON)统一：都不在 armBlurForView 启动动画。
                // 这里只把起点压到 0（清晰）并置 armed；真正动画改由 onCreateOpenAnimation 在展开开始时启动。
                // 【幂等】同一轮弹窗内 arm 会被调两次（companion + 本体），只对首次 arm 做起点压 0 + 记录轮次。
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
                        Object dc = invokeNoArgQuietly(launcher, "getDepthController");
                        if (dc == null) {
                            ModuleLog.d("DEPTH", "depthController null");
                            return;
                        }
                        Object g = invokeNoArgQuietly(dc, "getCurrentBlur");
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
        String key = cls.getName();
        synchronized (dumpedCls) {
            if (!dumpedCls.add(key)) return;
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
                Class<?> cls = loadClass(CLS_OPLUS_EFFECT, currentClassLoader());
                if (cls != null) {
                    dumpOplusApiOnce(cls);
                    Method m = findMethodCached(cls, "setBackgroundRenderEffect", RenderEffect.class, View.class);
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
                ModuleLog.d("ICONBLUR", "fallback setRenderEffect err=" + err);
            }
        } catch (Throwable t) {
            ModuleLog.e("ICONBLUR", "applyIconBlurRadius failed", t);
        }
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
    private RenderEffect getBlurEffect() {
        RenderEffect e = blurEffect;
        if (e == null) {
            e = RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.MIRROR);
            blurEffect = e;
        }
        return e;
    }

    /**
     * @param animate true = 走 0→BLUR_RADIUS 渐进动画（有模糊半径过渡）；false = 一次性设置（旧行为，会“清晰→糊”硬切）。
     */
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

    private static void setAccessibleQuietly(Executable e) {
        try {
            e.setAccessible(true);
        } catch (Throwable ignore) {
        }
    }

    private void clearIconBlur(View view) {

        setIconBlurArmed(view, false);
        try {
            Class<?> cls = loadClass(CLS_OPLUS_EFFECT, currentClassLoader());
            if (cls != null) {
                Method m = findMethodCached(cls, "setBackgroundRenderEffect", RenderEffect.class, View.class);
                if (m != null) {
                    m.invoke(null, null, view);
                    ModuleLog.d("CLEAR", "icon blur cleared via oplus");
                    return;
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("CLEAR", "oplus clear failed", t);
        }
        try {
            Method m = findMethodCached(View.class, "setRenderEffect", RenderEffect.class);
            if (m != null) {
                m.invoke(view, (Object) null);
                ModuleLog.d("CLEAR", "icon blur cleared via View");
            }
        } catch (Throwable t) {
            ModuleLog.d("CLEAR", "icon blur clear failed: " + t);
        }
    }

    private void clearStaticLayers(View view) {
        try {
            Class<?> drawableCls = loadClass(CLS_DRAWABLE, currentClassLoader());
            boolean wall = false;
            boolean drag = false;

            if (drawableCls != null) {
                Method mw = findMethodCached(view.getClass(), "setWallpaperDrawable", drawableCls);
                if (mw != null) {
                    try { mw.invoke(view, (Object) null); wall = true; } catch (Throwable ignore) {}
                }
                Method md = findMethodCached(view.getClass(), "setDragLayerDrawable", drawableCls);
                if (md != null) {
                    try { md.invoke(view, (Object) null); drag = true; } catch (Throwable ignore) {}
                }
            }

            Field f = findField(view.getClass(), "mIsBlurUnavailable");
            if (f != null) {
                try { f.setBoolean(view, true); } catch (Throwable ignore) {}
            }
            invokeNoArgQuietly(view, "invalidate");
            ModuleLog.d("CLEAR", "staticLayers wall=" + wall + " drag=" + drag);
        } catch (Throwable t) {
            ModuleLog.e("CLEAR", "clearStaticLayers failed", t);
        }
    }

    private String trySetViewRenderEffect(View view, RenderEffect effect) {
        try {
            Method m = findMethodCached(View.class, "setRenderEffect", RenderEffect.class);
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
            Object dc = invokeNoArgQuietly(launcher, "getDepthController");
            if (dc == null) return null;
            Object prop = getStaticFloatProperty(dc, "BLUR");
            if (prop == null) return null;

            float cur = from;
            try {
                Object g = invokeNoArgQuietly(dc, "getCurrentBlur");
                if (g instanceof Float) {
                    float v = (Float) g;
                    if (v >= 0.0f) cur = v;
                }
            } catch (Throwable ignore) {}

            Class<?> oaCls = loadClass(CLS_OBJECT_ANIMATOR, currentClassLoader());
            Class<?> propCls = loadClass(CLS_PROPERTY, currentClassLoader());
            if (oaCls == null || propCls == null) return null;

            Method ofFloat = findMethodCached(oaCls, "ofFloat", Object.class, propCls, float[].class);
            if (ofFloat == null) return null;
            Object anim = ofFloat.invoke(null, dc, prop, new float[]{cur, to});
            if (anim == null) return null;

            Class<?> animCls = loadClass(CLS_ANIMATOR, currentClassLoader());
            if (animCls == null) return null;

            Method setDur = findMethodCached(animCls, "setDuration", long.class);
            if (setDur != null) setDur.invoke(anim, duration);

            Class<?> ipCls = loadClass(CLS_TIME_INTERPOLATOR, currentClassLoader());
            Class<?> decCls = loadClass(CLS_DECELERATE, currentClassLoader());
            if (ipCls != null && decCls != null) {
                Object decObj = newInstanceCached(decCls);
                Method setI = findMethodCached(animCls, "setInterpolator", ipCls);
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
                Class<?> animCls = loadClass(CLS_ANIMATOR, currentClassLoader());
                if (animCls != null) {
                    Method start = findMethodCached(animCls, "start");
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

    /**
     * 【图标模糊渐进】造一个控制挂点模糊半径 from->to 的 ValueAnimator。
     * 与系统菜单展开 AnimatorSet 一起 play，时间轴完全对齐（不再自己 postDelayed 启动）。
     */
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
            Class<?> setCls = loadClass(CLS_ANIMATOR_SET, currentClassLoader());
            Class<?> animCls = loadClass(CLS_ANIMATOR, currentClassLoader());
            if (setCls == null || animCls == null) return;
            Method play = findMethodCached(setCls, "play", animCls);
            if (play != null) play.invoke(set, anim);
        } catch (Throwable t) {
        }
    }

    private boolean setDepthBlur(View view, float value) {
        try {
            Object launcher = getLauncherQuietly(view);
            if (launcher == null) return false;
            Object dc = invokeNoArgQuietly(launcher, "getDepthController");
            if (dc == null) return false;
            Method m = findMethodCached(dc.getClass(), "setBlurWithoutAnim", float.class);
            if (m == null) return false;
            m.invoke(dc, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Class<?> loadClass(String name, ClassLoader loader) {
        if (loader == null) return null;
        String key = name + "@" + System.identityHashCode(loader);
        Class<?> cached = CLASS_CACHE.get(key);
        if (cached != null) return cached;
        try {
            Class<?> c = Class.forName(name, false, loader);
            CLASS_CACHE.put(key, c);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethodCached(Class<?> cls, String name, Class<?>... paramTypes) {
        return Reflect.method(cls, name, paramTypes);
    }

    private static Field findField(Class<?> cls, String name) {
        return Reflect.field(cls, name);
    }

    private static Object newInstanceCached(Class<?> cls) {
        return Reflect.newInstance(cls);
    }

    private Object getStaticFloatProperty(Object dc, String name) {
        Field f = findField(dc.getClass(), name);
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

    private Object getLauncherQuietly(View view) {
        try {
            Context ctx = view.getContext();
            if (ctx == null) return null;
            Class<?> lc = loadClass(CLS_LAUNCHER, currentClassLoader());
            if (lc == null) return null;

            Method g = findMethodCached(lc, "getLauncher", Context.class);
            if (g != null) {
                try {
                    Object r = g.invoke(null, ctx);
                    if (r != null) return r;
                } catch (Throwable ignore) {}
            }
            Method g2 = findMethodCached(lc, "getLauncherOrNull", Context.class);
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

    private Object invokeNoArgQuietly(Object target, String name) {
        return Reflect.call(target, name);
    }

    private Object getFieldQuietlyAny(Object obj, String name) {
        return Reflect.readField(obj, name);
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
                            if (old != SAMPLE_SCALE) {
                                args[pos] = SAMPLE_SCALE;
                                ModuleLog.d("SCALE", tag + " " + old + " -> " + SAMPLE_SCALE);
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
                ModuleLog.d("FOLDER", "no dragLayer found, chain=" + dumpViewChainNames(view));
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
            ModuleLog.d("FOLDER", "no workspace child, dragLayer children=" + dumpChildViewNames(dragLayer));
            return false;
        } catch (Throwable t) {
            ModuleLog.e("FOLDER", "isInsideOpenFolder failed", t);
            return false;
        }
    }

    private String dumpViewChainNames(View view) {
        StringBuilder sb = new StringBuilder();
        try {
            ViewParent p = view.getParent();
            int g = 0;
            while (p != null && g < 20) {
                sb.append(p.getClass().getSimpleName()).append(" > ");
                p = (p instanceof View) ? ((View) p).getParent() : null;
                g++;
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private String dumpChildViewNames(ViewGroup vg) {
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i < vg.getChildCount(); i++) {
                sb.append(vg.getChildAt(i).getClass().getSimpleName()).append(" ");
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private ClassLoader currentClassLoader() {
        return cl;
    }

    private void hookRemoteViewsApply(ClassLoader cl) {
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

    private void hookAppWidgetHostView(ClassLoader cl) {
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
    private void hookTextViewSetText(ClassLoader cl) {
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
