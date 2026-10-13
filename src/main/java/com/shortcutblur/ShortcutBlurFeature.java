package com.shortcutblur;

import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.view.animation.DecelerateInterpolator;
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
import static com.shortcutblur.BlurLib.*;
import static com.shortcutblur.Symbols.*;

public final class ShortcutBlurFeature {
    private ShortcutBlurFeature() {}

    public interface Host {
        ClassLoader classLoader();
        void hookEx(String id, Executable target, XposedInterface.Hooker hooker);
    }

    private static volatile Host host;

    private static final long BLUR_DURATION = 330L;

    private static float blurRadius() {
        try {
            float r = FeatureFlags.SHORTCUT_BLUR_RADIUS;
            return (r < 0f) ? 0f : (r > 200f ? 200f : r);
        } catch (Throwable t) {
            return 64.0f;
        }
    }
    private static final long DEPTH_FALLBACK_DELAY = 500L;
    private static final long SWALLOW_RESET_DELAY = 450L;

    private static final int F_STATIC = 1 << 0;
    private static final int F_ICON = 1 << 1;
    private static final int F_WALL = 1 << 2;
    private static final int F_ICON_ANIM = 1 << 3;

    static volatile boolean sShortcutBlurActive = false;
    private static volatile View sIconBlurLayer = null;
    private static volatile long sPopupSeq = 0L;
    private static volatile long sIconAnimStartedSeq = -1L;

    private static volatile boolean sIconBlurRelight = false;
    private static volatile boolean sFadingOut = false;
    private static volatile int sSwallowCount = 0;

    private static final Map<View, Boolean> armed = new WeakHashMap<>();
    private static final Map<View, Integer> flagsCache = new WeakHashMap<>();

    private static final Map<View, ValueAnimator> iconAnims =
            new java.util.WeakHashMap<>();

    private static volatile boolean sOplusEffectResolved = false;
    private static volatile Class<?> sOplusEffectCls = null;
    private static volatile Method sSetBgRenderEffect = null;
    private static volatile Method sSetRenderEffectViewMethod = null;

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
        n += hookAssignMargins(loader);
        n += hookShowForIcon(loader);
        n += hookMoreFunctionsCtor(loader);
        n += hookArrowRoundedBg(loader);
        n += hookPopupColorPrimaryAttr(loader);
        return n;
    }

    private static void hookTarget(Executable target, String id, XposedInterface.Hooker hooker) {
        host.hookEx(id, target, hooker);
    }

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
                    if (!CLS_ANIMATOR_SET.equals(pt[0].getName())) continue;
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

                            if (!opening) {
                                try {
                                    View host = null;
                                    if (self instanceof View) host = (View) self;
                                    if (host == null && self != null) {
                                        Object pbv = Reflect.readField(self, "mPopBlurView");
                                        if (pbv instanceof View) host = (View) pbv;
                                    }
                                    if (host != null) {
                                        final View fhost = host;

                                        try {
                                            final android.view.ViewTreeObserver vto = fhost.getViewTreeObserver();
                                            final android.view.ViewTreeObserver.OnPreDrawListener l =
                                                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                                                private int frames = 0;
                                                @Override public boolean onPreDraw() {
                                                    try {
                                                        repressPanelAlpha(fhost);
                                                    } catch (Throwable ignored) {}
                                                    if (++frames >= 40) {
                                                        try {
                                                            android.view.ViewTreeObserver v = fhost.getViewTreeObserver();
                                                            if (v.isAlive()) v.removeOnPreDrawListener(this);
                                                        } catch (Throwable ignored) {}
                                                    }
                                                    return true;
                                                }
                                            };
                                            vto.addOnPreDrawListener(l);
                                        } catch (Throwable t) {
                                            ModuleLog.e("CARDBLUR", "add OnPreDraw failed", t);
                                        }

                                        for (final long d : new long[]{0L, 16L, 48L, 96L, 160L}) {
                                            fhost.postDelayed(new Runnable() {
                                                @Override public void run() {
                                                    try {
                                                        repressPanelAlpha(fhost);
                                                    } catch (Throwable ignored) {}
                                                }
                                            }, d);
                                        }
                                    }
                                } catch (Throwable t) {
                                    ModuleLog.e("CARDBLUR", "close repress failed", t);
                                }
                            }
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

                                            sIconBlurRelight = true;
                                            Object iconAnim = createIconBlurAnimator(lt, 0.0f, blurRadius(), BLUR_DURATION);
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

    private static final Map<View, Boolean> cardBlurApplied = new WeakHashMap<>();

    private static float cardBlurRadius() {
        try {
            float r = FeatureFlags.SHORTCUT_BLUR_RADIUS;
            return (r < 0f) ? 0f : (r > 200f ? 200f : r);
        } catch (Throwable t) {
            return 64.0f;
        }
    }

    private static int hookAssignMargins(ClassLoader loader) {
        int n = 0;
        try {
            Class<?> cls = Reflect.loadClass(CLS_ARROW_POPUP, loader);
            if (cls == null) {
                ModuleLog.d("CARDBLUR", "ArrowPopup not found");
                return 0;
            }
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(M_ASSIGN_MARGINS)) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2) continue;
                if (!"android.view.ViewGroup".equals(pt[0].getName())) continue;
                if (pt[1] != int.class) continue;
                Reflect.setAccessible(m);
                hookTarget((Executable) m, "cardblur.assignMargins", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof ViewGroup) {
                                applyCardBlur((ViewGroup) arg0);
                            }
                        } catch (Throwable t) {
                            ModuleLog.e("CARDBLUR", "hook body failed", t);
                        }
                        return result;
                    }
                });
                n++;
                ModuleLog.d("CARDBLUR", "hook assignMarginsAndBackgrounds installed");
            }
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookAssignMargins failed", t);
        }
        return n;
    }

    private static volatile java.lang.ref.WeakReference<View> sPanelCache = null;
    private static volatile java.lang.ref.WeakReference<View> sPanelCacheRoot = null;
    private static void repressPanelAlpha(View v) {
        try {
            if (v == null) return;

            java.lang.ref.WeakReference<View> rootRef = sPanelCacheRoot;
            java.lang.ref.WeakReference<View> ref = sPanelCache;
            if (rootRef != null && rootRef.get() == v && ref != null) {
                View cached = ref.get();
                if (cached != null && cached.getParent() != null
                        && cached.getWidth() >= 200 && cached.getHeight() >= 200) {
                    forceAlphaOnBg(cached);
                    return;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c.getWidth() >= 200 && c.getHeight() >= 200) {
                        forceAlphaOnBg(c);
                        if (c.getBackground() != null) {
                            sPanelCache = new java.lang.ref.WeakReference<>(c);
                            sPanelCacheRoot = new java.lang.ref.WeakReference<>(v);
                        }
                    }
                }
            } else {
                forceAlphaOnBg(v);
                if (v.getBackground() != null) {
                    sPanelCache = new java.lang.ref.WeakReference<>(v);
                    sPanelCacheRoot = new java.lang.ref.WeakReference<>(v);
                }
            }
        } catch (Throwable ignored) {}
    }
    private static void forceAlphaOnBg(View c) {
        try {
            android.graphics.drawable.Drawable bg = c.getBackground();
            if (bg == null) return;
            int want = (FeatureFlags.CARD_PANEL_ALPHA & 0xFF) << 24;
            if (bg.getAlpha() == (FeatureFlags.CARD_PANEL_ALPHA & 0xFF)) {

                if (bg instanceof android.graphics.drawable.GradientDrawable) {
                    android.content.res.ColorStateList csl =
                            ((android.graphics.drawable.GradientDrawable) bg).getColor();
                    if (csl != null) {
                        int cur = csl.getDefaultColor();
                        if ((cur >>> 24) == (FeatureFlags.CARD_PANEL_ALPHA & 0xFF)) return;
                        int out = want | (cur & 0x00FFFFFF);
                        ((android.graphics.drawable.GradientDrawable) bg).setColor(out);
                        ModuleLog.d("CARDBLUR", "  repress gdColor 0x" + Integer.toHexString(cur)
                                + " -> 0x" + Integer.toHexString(out));
                    }
                }
                return;
            }

            bg.mutate().setAlpha(FeatureFlags.CARD_PANEL_ALPHA);
            ModuleLog.d("CARDBLUR", "  repress alpha -> 0x"
                    + Integer.toHexString(FeatureFlags.CARD_PANEL_ALPHA)
                    + " cls=" + c.getClass().getSimpleName());
        } catch (Throwable ignored) {}
    }
    private static int applyCardBlur(ViewGroup card) {
        if (card == null) return 0;
        try {
            if (!FeatureFlags.SHORTCUT_BLUR) return 0;
            float r = cardBlurRadius();
            if (r <= 0.01f) return 0;
            int count = applyCardBlurRec(card, r, 0);
            ModuleLog.d("CARDBLUR", "apply card blur r=" + r
                    + " root=" + card.getClass().getSimpleName()
                    + " " + card.getWidth() + "x" + card.getHeight()
                    + " attached=" + count);
            return count;
        } catch (Throwable t) {
            ModuleLog.e("CARDBLUR", "applyCardBlur failed", t);
            return 0;
        }
    }

    private static int applyCardBlurRec(View v, float r, int depth) {
        if (v == null || depth > 4) return 0;
        int n = 0;
        try {
            android.graphics.drawable.Drawable bg = v.getBackground();
            boolean hasBg = bg != null;

            boolean bigEnough = v.getWidth() >= 200 && v.getHeight() >= 200;
            if (hasBg && bigEnough) {

                try {
                    android.graphics.drawable.Drawable mut = bg.mutate();
                    mut.setAlpha(FeatureFlags.CARD_PANEL_ALPHA);
                    v.setBackground(mut);
                } catch (Throwable t) {
                    ModuleLog.d("CARDBLUR", "  alpha failed: " + t);
                }
                ModuleLog.d("CARDBLUR", "  card panel depth=" + depth
                        + " cls=" + v.getClass().getSimpleName()
                        + " " + v.getWidth() + "x" + v.getHeight()
                        + " alpha=0x" + Integer.toHexString(FeatureFlags.CARD_PANEL_ALPHA)
                        + " bg=" + bg.getClass().getSimpleName());
                applyIconBlurRadius(v, r, true);
                n++;

                return n;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int c = g.getChildCount();
                for (int i = 0; i < c; i++) {
                    n += applyCardBlurRec(g.getChildAt(i), r, depth + 1);
                }
            }
        } catch (Throwable t) {
            ModuleLog.d("CARDBLUR", "  rec err: " + t);
        }
        return n;
    }

    private static int hookShowForIcon(ClassLoader loader) {
        int n = 0;
        for (String cn : new String[]{CLS_OPLUS_POPUP, CLS_POPUP_CONTAINER}) {
            n += hookShowForIconOn(loader, cn);
        }
        return n;
    }

    private static int hookShowForIconOn(ClassLoader loader, String clsName) {
        int n = 0;
        try {
            Class<?> cls = Reflect.loadClass(clsName, loader);
            if (cls == null) {
                ModuleLog.d("CARDBLUR", "class not found: " + clsName);
                return 0;
            }
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("showForIcon")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 1) continue;
                Reflect.setAccessible(m);
                final String mid = clsName.substring(clsName.lastIndexOf('.') + 1)
                        + "#showForIcon(" + pt[0].getSimpleName() + ")";
                hookTarget((Executable) m, "cardblur.showForIcon", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try {
                            if (result instanceof ViewGroup) {
                                ViewGroup card = (ViewGroup) result;
                                ModuleLog.d("CARDBLUR", mid + " ret cls=" + card.getClass().getName()
                                        + " " + card.getWidth() + "x" + card.getHeight()
                                        + " children=" + card.getChildCount()
                                        + " bg=" + (card.getBackground() == null ? "null"
                                                   : card.getBackground().getClass().getName()));
                                final ViewGroup fc = card;
                                fc.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            ModuleLog.d("CARDBLUR", "post layout cls=" + fc.getClass().getName()
                                                    + " " + fc.getWidth() + "x" + fc.getHeight()
                                                    + " children=" + fc.getChildCount());
                                            applyCardBlur(fc);
                                        } catch (Throwable t) {
                                            ModuleLog.e("CARDBLUR", "post apply failed", t);
                                        }
                                    }
                                }, 80L);
                            }
                        } catch (Throwable t) {
                            ModuleLog.e("CARDBLUR", "showForIcon hook body failed", t);
                        }
                        return result;
                    }
                });
                n++;
                ModuleLog.d("CARDBLUR", "hook " + mid + " installed");
            }
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookShowForIconOn(" + clsName + ") failed", t);
        }
        return n;
    }

    private static int hookCouiUseBlur(ClassLoader loader) {
        int n = 0;
        try {
            Class<?> cls = Reflect.loadClass(CLS_COUI_POPUP_LIST_WINDOW, loader);
            if (cls == null) {
                ModuleLog.d("CARDBLUR", "COUIPopupListWindow not found");
                return 0;
            }
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(M_SET_USE_BG_BLUR)) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 1 || pt[0] != boolean.class) continue;
                Reflect.setAccessible(m);
                final int argc = pt.length;
                hookTarget((Executable) m, "cardblur.couiUseBlur", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
try {
                                boolean orig = (Boolean) chain.getArg(0);
                                Object self = chain.getThisObject();
                                String who = (self == null ? "null" : self.getClass().getSimpleName());
                                ModuleLog.d("CARDBLUR", "setUseBackgroundBlur(" + orig + ", argc=" + argc
                                        + ") this=" + who);
                                if (FeatureFlags.SHORTCUT_BLUR && !orig) {
                                    ModuleLog.d("CARDBLUR", "force coui blur ON (was " + orig + ", argc=" + argc + ")");
                                    Object[] args = new Object[argc];
                                    args[0] = Boolean.TRUE;
                                    for (int i = 1; i < argc; i++) args[i] = chain.getArg(i);
                                    return chain.proceed(args);
                                }
                            } catch (Throwable t) {
                            ModuleLog.e("CARDBLUR", "couiUseBlur body failed", t);
                        }
                        return chain.proceed();
                    }
                });
                n++;
                ModuleLog.d("CARDBLUR", "hook COUIPopupListWindow#" + M_SET_USE_BG_BLUR + "(" + argc + ") installed");
            }
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookCouiUseBlur failed", t);
        }
        return n;
    }

    private static int hookArrowRoundedBg(ClassLoader loader) {
        int n = 0;
        try {
            Class<?> cls = Reflect.loadClass(CLS_ARROW_POPUP, loader);
            if (cls == null) {
                ModuleLog.d("CARDBLUR", "ArrowPopup not found (rounded)");
                return 0;
            }
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                Reflect.setAccessible(c);
                hookTarget((Executable) c, "cardblur.arrowRoundedCtor", new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object r = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            recolorRounded(self, "mRoundedTop");
                            recolorRounded(self, "mRoundedBottom");
                        } catch (Throwable t) {
                            ModuleLog.e("CARDBLUR", "arrowRounded recolor failed", t);
                        }
                        return r;
                    }
                });
                n++;
            }
            ModuleLog.d("CARDBLUR", "hook ArrowPopup rounded-bg installed n=" + n);
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookArrowRoundedBg failed", t);
        }

        return n;
    }

    private static void recolorRounded(Object popup, String field) {
        try {
            if (popup == null) return;
            Object gd = Reflect.readField(popup, field);
            if (gd == null) return;
            Method setColor = gd.getClass().getMethod("setColor", int.class);
            Method getColor = gd.getClass().getMethod("getColor");
            Object csl = getColor.invoke(gd);
            int base = 0xFFFFFF;
            if (csl instanceof android.content.res.ColorStateList) {
                base = ((android.content.res.ColorStateList) csl).getDefaultColor() & 0x00FFFFFF;
            }
            int out = ((FeatureFlags.CARD_PANEL_ALPHA & 0xFF) << 24) | base;
            setColor.invoke(gd, out);
            ModuleLog.d("CARDBLUR", "rounded " + field + " -> 0x" + Integer.toHexString(out));
        } catch (Throwable t) {
            ModuleLog.e("CARDBLUR", "recolorRounded(" + field + ") failed", t);
        }
    }

    private static final int ATTR_POPUP_COLOR_PRIMARY = 0x7f04085a;
    private static int hookPopupColorPrimaryAttr(ClassLoader loader) {
        try {
            Class<?> themes = Reflect.loadClass("com.android.launcher3.util.Themes", loader);
            if (themes == null) {
                ModuleLog.d("CARDBLUR", "Themes not found");
                return 0;
            }
            int n = 0;
            for (Method m : themes.getDeclaredMethods()) {
                if (!"getAttrColor".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2 || pt[0] != android.content.Context.class || pt[1] != int.class) continue;
                if (m.getReturnType() != int.class) continue;
                Reflect.setAccessible(m);
                hookTarget((Executable) m, "cardblur.getAttrColor", new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object attr = chain.getArg(1);
                        Object result = chain.proceed();
                        try {
                            if (attr instanceof Integer && ((Integer) attr) == ATTR_POPUP_COLOR_PRIMARY
                                    && result instanceof Integer) {
                                int c = (Integer) result;
                                int out = ((FeatureFlags.CARD_PANEL_ALPHA & 0xFF) << 24) | (c & 0x00FFFFFF);
                                ModuleLog.d("CARDBLUR", "popupColorPrimary in=0x" + Integer.toHexString(c)
                                        + " out=0x" + Integer.toHexString(out));
                                return out;
                            }
                        } catch (Throwable t) {
                            ModuleLog.e("CARDBLUR", "getAttrColor body failed", t);
                        }
                        return result;
                    }
                });
                n++;
            }
            ModuleLog.d("CARDBLUR", "hook popupColorPrimary attr installed n=" + n);
            return n;
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookPopupColorPrimaryAttr failed", t);
            return 0;
        }
    }

    private static int hookMoreFunctionsCtor(ClassLoader loader) {
        int n = 0;
        try {
            Class<?> cls = Reflect.loadClass(CLS_MORE_FUNCTIONS_WINDOW, loader);
            if (cls == null) {
                ModuleLog.d("CARDBLUR", "MoreFunctionsPopupListWindow not found");
                return 0;
            }
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length != 2) continue;
                Reflect.setAccessible(c);
                hookTarget((Executable) c, "cardblur.mfCtor", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object r = chain.proceed();
                        try {
                            final Object self = chain.getThisObject();

                            ModuleLog.d("CARDBLUR", "mfCtor ret self="
                                    + (self == null ? "null" : self.getClass().getName()));
                            if (self != null) {
                                View anchor = resolvePopupRoot(self);
                                if (anchor != null) {

                                    final long[] delays = {0L, 32L, 80L};
                                    for (final long d : delays) {
                                        anchor.postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    applySecondaryBlur(self);
                                                } catch (Throwable t) {
                                                    ModuleLog.e("CARDBLUR", "secondary blur failed", t);
                                                }
                                            }
                                        }, d);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            ModuleLog.e("CARDBLUR", "mfCtor body failed", t);
                        }
                        return r;
                    }
                });
                n++;
                ModuleLog.d("CARDBLUR", "hook MoreFunctionsPopupListWindow(2) installed");
            }
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookMoreFunctionsCtor failed", t);
        }
        return n;
    }

    private static View resolvePopupRoot(Object self) {

        try {
            Method gcv = self.getClass().getMethod("getContentView");
            Object c = gcv.invoke(self);
            if (c instanceof View) return (View) c;
        } catch (Throwable ignored) {}

        for (String fn : new String[]{"mMainMenuWrapper", "mSubMenuWrapper"}) {
            try {
                Field f = null;
                Class<?> c = self.getClass();
                while (c != null && f == null) {
                    try { f = c.getDeclaredField(fn); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
                }
                if (f == null) continue;
                f.setAccessible(true);
                Object v = f.get(self);
                if (v instanceof View) return (View) v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void applySecondaryBlur(Object window) {
        if (!FeatureFlags.SHORTCUT_BLUR) return;

        float radius = FeatureFlags.CARD_SECONDARY_BLUR_RADIUS;
        if (radius <= 0.01f) radius = cardBlurRadius();
        if (radius > 200f) radius = 200f;
        int hit = 0;
        for (String fn : new String[]{"mMainMenuWrapper", "mSubMenuWrapper"}) {
            try {
                Field f = null;
                Class<?> c = window.getClass();
                while (c != null && f == null) {
                    try { f = c.getDeclaredField(fn); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
                }
                if (f == null) continue;
                f.setAccessible(true);
                Object v = f.get(window);
                if (!(v instanceof View)) continue;
                View vv = (View) v;
                ModuleLog.d("CARDBLUR", "secondary " + fn + " cls=" + vv.getClass().getName()
                        + " " + vv.getWidth() + "x" + vv.getHeight()
                        + " bg=" + (vv.getBackground() == null ? "null" : vv.getBackground().getClass().getName()));

                OplusCardBlur.apply(vv, radius);
                hit++;
            } catch (Throwable t) {
                ModuleLog.e("CARDBLUR", "secondary " + fn + " err", t);
            }
        }
        ModuleLog.d("CARDBLUR", "apply secondary oplus blur r=" + radius + " hit=" + hit);
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

    private static void fadeOutAndRemoveIconBlur() {
        final View layer = sIconBlurLayer;
        if (sFadingOut) {
            ModuleLog.d("ICONANIM", "already fading out, skip duplicate fade-out");
            return;
        }
        sFadingOut = true;
        sPopupSeq++;
        sIconAnimStartedSeq = -1L;

        sIconBlurRelight = false;
        if (layer == null) { sFadingOut = false; return; }
        try {
            synchronized (iconAnims) {
                ValueAnimator old = iconAnims.remove(layer);
                if (old != null) { try { old.cancel(); } catch (Throwable ignore) {} }
            }
            final ValueAnimator va = ValueAnimator.ofFloat(blurRadius(), 0.0f);
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
                iconAnims.put(layer, va);
            }
            va.start();
            ModuleLog.d("ICONANIM", "fade-out " + blurRadius() + "->0.0 dur=" + BLUR_DURATION);
            layer.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        removeAnimation(layer);
                        if (sIconBlurLayer != layer) {
                            ModuleLog.d("ICONANIM", "fade-out done, layer already replaced, skip remove");
                            sFadingOut = false;
                            return;
                        }

                        if (sIconBlurRelight) {
                            ModuleLog.d("ICONANIM", "fade-out done but relit (next popup opened), skip remove");
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
            synchronized (iconAnims) {
                ValueAnimator old = iconAnims.remove(layer);
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

    private static void clearBlurFlagsCache(View view) {
        if (view == null) return;
        synchronized (flagsCache) {
            flagsCache.remove(view);
        }
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

    private static boolean isCurrentAnimation(View key, ValueAnimator va) {
        synchronized (iconAnims) {
            return iconAnims.get(key) == va;
        }
    }

    private static void removeAnimation(View key) {
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
            synchronized (iconAnims) {
                ValueAnimator old = iconAnims.remove(view);
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
                        if (!isCurrentAnimation(view, va)) return;
                        float r = (Float) a.getAnimatedValue();
                        if (!Float.isNaN(last) && Math.abs(r - last) < 0.5f) return;
                        last = r;
                        if (!isIconBlurArmed(view)) return;
                        applyIconBlurRadius(view, r, true);
                    } catch (Throwable ignore) {}
                }
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { removeAnimation(view); }
                @Override public void onAnimationCancel(android.animation.Animator a) { removeAnimation(view); }
            });
            synchronized (iconAnims) {
                iconAnims.put(view, va);
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
