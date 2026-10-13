package com.shortcutblur;

import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedInterface;
import static com.shortcutblur.BlurLib.*;
import static com.shortcutblur.Symbols.*;

/**
 * ShortcutBlur 功能实体（原 ShortcutBlur 模块本体）。
 *
 * 来源：本类由 BlurEnhanceModule 的“③ 图标模糊动画”块整体迁出，
 * 对应旧项目 Color-os-shortcut-enhance（原 ShortcutBlur）的核心实现，
 * 即包名 com.shortcutblur 的历史本义。迁出后 BlurEnhanceModule 仅保留入口与装配。
 *
 * 依赖边界：本类只依赖 ModuleLog / Reflect / BlurLib / ViewUtils / Symbols，
 * 以及宿主提供的 Host（classLoader + hookEx），不反向依赖 BlurEnhanceModule。
 */
public final class ShortcutBlurFeature {
    private ShortcutBlurFeature() {}

    /** 宿主契约：由 BlurEnhanceModule 实现，等价于其 cl 字段与 hookEx()。 */
    public interface Host {
        ClassLoader classLoader();
        void hookEx(String id, Executable target, XposedInterface.Hooker hooker);
    }

    private static volatile Host host;

    // ---- ③ 常量：动画 / 阈值 ----
    private static final float BLUR_RADIUS = 64.0f;
    private static final long BLUR_DURATION = 330L;
    private static final long DEPTH_FALLBACK_DELAY = 500L;
    private static final long SWALLOW_RESET_DELAY = 450L;

    // ---- ③ 常量：模糊标志位（resolveBlurFlags 产出）----
    private static final int F_STATIC = 1 << 0;
    private static final int F_ICON = 1 << 1;
    private static final int F_WALL = 1 << 2;
    private static final int F_ICON_ANIM = 1 << 3;

    // ---- ③ 图标模糊生命周期状态 ----
    static volatile boolean sShortcutBlurActive = false;
    private static volatile View sIconBlurLayer = null;
    private static volatile long sPopupSeq = 0L;
    private static volatile long sIconAnimStartedSeq = -1L;
    private static volatile boolean sFadingOut = false;
    private static volatile int sSwallowCount = 0;

    // ---- ③ 图标模糊：实例级缓存（弱引用，避免钉住 View 子树）----
    private static final Map<View, Boolean> armed = new WeakHashMap<>();
    private static final Map<View, Integer> flagsCache = new WeakHashMap<>();
    private static final Map<String, ValueAnimator> iconAnims = new ConcurrentHashMap<>();

    // ---- Oplus 反射缓存（③ 的 applyIconBlurRadius 使用）----
    private static volatile boolean sOplusEffectResolved = false;
    private static volatile Class<?> sOplusEffectCls = null;
    private static volatile Method sSetBgRenderEffect = null;
    private static volatile Method sSetRenderEffectViewMethod = null;
    private static volatile boolean sOplusApiDumped = false;
    private static final Set<String> dumpedCls = new HashSet<>();

    /** 安装 ③ 的全部 hook。返回安装数量。 */
    public static int install(ClassLoader loader, Host h) {
        host = h;
        int n = 0;
        n += hookViewReturningMethod(loader, CLS_POPUP_BLUR_VIEW, M_GET_POP_BLUR_VIEW, "pbv");
        n += hookPopupFinish(loader, CLS_POPUP_BLUR_VIEW);
        n += hookViewReturningMethod(loader, CLS_POPUP_BLUR_VIEW + "$Companion", M_GET_POP_BLUR_VIEW, "pbv_companion");
        for (String cn : new String[]{CLS_OPLUS_POPUP, CLS_ARROW_POPUP, CLS_POPUP_BLUR_VIEW}) {
            n += hookPopupOpenCloseAnimation(loader, cn, "onCreateOpenAnimation", true);
            n += hookPopupOpenCloseAnimation(loader, cn, "onCreateCloseAnimation", false);
        }
        n += installSwallowPauseHook(loader);
        return n;
    }

    private static void hookTarget(Executable target, String id, XposedInterface.Hooker hooker) {
        host.hookEx(id, target, hooker);
    }

    // ========================================================================
    // ③ 图标模糊动画（快捷方式弹窗 lifecycle）
    // ========================================================================
    private static int hookPopupFinish(ClassLoader loader, String clsName) {
        int n = 0;
        try {
            Class<?> cls = Reflect.loadClass(clsName, loader);
            if (cls == null) return 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("finish")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2) continue;
                if (!"android.view.ViewGroup".equals(pt[0].getName())) continue;
                if (pt[1] != boolean.class) continue;
                Reflect.setAccessible(m);
                hookTarget((Executable) m, "iconblur.finish", new XposedInterface.Hooker() {
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

    private static int hookPopupOpenCloseAnimation(ClassLoader loader, String clsName,
                                                   final String methodName, final boolean opening) {
        int n = 0;
        try {
            Class<?> cls = Reflect.loadClass(clsName, loader);
            if (cls == null) return 0;
            final Set<Method> hooked = new HashSet<>();
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
                    hookTarget((Executable) m, "iconblur.opa." + methodName, new XposedInterface.Hooker() {
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

    private static int installSwallowPauseHook(ClassLoader loader) {
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
                    hookTarget((Executable) m, "swallow.pause." + name + "." + m.getParameterTypes().length,
                            new XposedInterface.Hooker() {
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

    private static int hookViewReturningMethod(ClassLoader loader, String clsName, String methodName, String id) {
        int n = 0;
        try {
            Class<?> cls = Reflect.loadClass(clsName, loader);
            if (cls == null) return 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                Reflect.setAccessible(m);
                final String mid = id + "#" + m.getParameterTypes().length;
                hookTarget((Executable) m, "hook." + mid, new XposedInterface.Hooker() {
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

    // ========================================================================
    // ③ 图标模糊：layer 生命周期 / 状态 / 装配
    // ========================================================================
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

    private static void fadeOutAndRemoveIconBlur() {
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

    private static void removeIconBlurLayer() {
        try {
            View layer = sIconBlurLayer;
            if (layer == null) return;
            final String key = System.identityHashCode(layer) + "";
            synchronized (iconAnims) {
                ValueAnimator old = iconAnims.remove(key);
                if (old != null) { try { old.cancel(); } catch (Throwable ignore) {} }
            }
            try { applyIconBlurRadius(layer, 0.0f, false); } catch (Throwable ignore) { ModuleLog.swallow("module.iconApply", ignore); }
            sIconAnimStartedSeq = -1L;
            ViewGroup vg = (ViewGroup) layer.getParent();
            if (vg != null) vg.removeView(layer);
            sIconBlurLayer = null;
            ModuleLog.d("ICONBLUR", "icon blur layer removed (+anim state cleared)");
        } catch (Throwable t) {
            sIconBlurLayer = null;
        }
    }

    private static void scheduleSwallowReset(final View anchor) {
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

    private static int resolveBlurFlags(View view) {
        if (view == null) return 0;
        Integer c = flagsCache.get(view);
        if (c != null) return c;
        int flags = isInsideOpenFolder(view)
                ? (F_STATIC | F_ICON | F_ICON_ANIM)
                : (F_STATIC | F_ICON | F_WALL);
        flagsCache.put(view, flags);
        return flags;
    }

    private static void clearBlurFlagsCache(View view) {
        if (view == null) return;
        flagsCache.remove(view);
    }

    private static void armBlurForView(View view, String mid) {
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
                    try { applyIconBlurRadius(itv, 0.01f, false); } catch (Throwable ignore) { ModuleLog.swallow("module.iconApply", ignore); }
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

    private static void applyIconBlurRadius(View view, float radius) {
        applyIconBlurRadius(view, radius, true);
    }

    private static void applyIconBlurRadius(View view, float radius, boolean useOplus) {
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

    private static Class<?> resolveOplusEffectCls() {
        if (sOplusEffectResolved) return sOplusEffectCls;
        synchronized (ShortcutBlurFeature.class) {
            if (sOplusEffectResolved) return sOplusEffectCls;
            try {
                Class<?> cls = Reflect.loadClass(CLS_OPLUS_EFFECT, host.classLoader());
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

    private static void dumpOplusApiOnce(Class<?> cls) {
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

    private static void setIconBlurArmed(View view, boolean value) {
        if (view == null) return;
        synchronized (armed) {
            if (value) {
                armed.put(view, Boolean.TRUE);
            } else {
                armed.remove(view);
            }
        }
    }

    private static boolean isIconBlurArmed(View view) {
        if (view == null) return false;
        synchronized (armed) {
            return armed.containsKey(view);
        }
    }

    private static boolean isCurrentAnimation(String key, ValueAnimator va) {
        synchronized (iconAnims) {
            return iconAnims.get(key) == va;
        }
    }

    private static void removeAnimation(String key) {
        synchronized (iconAnims) {
            iconAnims.remove(key);
        }
    }

    private static void clearStaticLayers(View view) {
        try {
            Class<?> drawableCls = Reflect.loadClass(CLS_DRAWABLE, host.classLoader());
            boolean wall = false;
            boolean drag = false;
            if (drawableCls != null) {
                Method mw = Reflect.method(view.getClass(), "setWallpaperDrawable", drawableCls);
                if (mw != null) {
                    try { mw.invoke(view, (Object) null); wall = true; } catch (Throwable ignore) { ModuleLog.swallow("module.reflectInvoke", ignore); }
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

    private static String trySetViewRenderEffect(View view, RenderEffect effect) {
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

    private static Object createDepthBlurAnimation(View view, float from, float to, long duration) {
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
            Class<?> oaCls = Reflect.loadClass(CLS_OBJECT_ANIMATOR, host.classLoader());
            Class<?> propCls = Reflect.loadClass(CLS_PROPERTY, host.classLoader());
            if (oaCls == null || propCls == null) return null;
            Method ofFloat = Reflect.method(oaCls, "ofFloat", Object.class, propCls, float[].class);
            if (ofFloat == null) return null;
            Object anim = ofFloat.invoke(null, dc, prop, new float[]{cur, to});
            if (anim == null) return null;
            Class<?> animCls = Reflect.loadClass(CLS_ANIMATOR, host.classLoader());
            if (animCls == null) return null;
            Method setDur = Reflect.method(animCls, "setDuration", long.class);
            if (setDur != null) setDur.invoke(anim, duration);
            Class<?> ipCls = Reflect.loadClass(CLS_TIME_INTERPOLATOR, host.classLoader());
            Class<?> decCls = Reflect.loadClass(CLS_DECELERATE, host.classLoader());
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

    private static boolean animateDepthBlur(View view, float from, float to, long duration) {
        try {
            Object anim = createDepthBlurAnimation(view, from, to, duration);
            if (anim != null) {
                Class<?> animCls = Reflect.loadClass(CLS_ANIMATOR, host.classLoader());
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

    private static Object createIconBlurAnimator(final View view, final float from, final float to, long duration) {
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
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { removeAnimation(key); }
                @Override public void onAnimationCancel(android.animation.Animator a) { removeAnimation(key); }
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

    private static void playIntoAnimatorSet(Object set, Object anim) {
        try {
            Class<?> setCls = Reflect.loadClass(CLS_ANIMATOR_SET, host.classLoader());
            Class<?> animCls = Reflect.loadClass(CLS_ANIMATOR, host.classLoader());
            if (setCls == null || animCls == null) return;
            Method play = Reflect.method(setCls, "play", animCls);
            if (play != null) play.invoke(set, anim);
        } catch (Throwable t) {
        }
    }

    private static boolean setDepthBlur(View view, float value) {
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

    private static Object getStaticFloatProperty(Object dc, String name) {
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

    private static boolean isInsideOpenFolder(View view) {
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
}
