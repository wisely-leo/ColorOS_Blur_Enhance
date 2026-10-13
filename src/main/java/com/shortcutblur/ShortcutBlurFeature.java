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
    private static final long BLUR_DURATION = 330L;
    /** 图标模糊目标半径：用户可在设置页自定义（FeatureFlags 热更），默认 64。 */
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
    /**
     * 「fade-out 期间又被重新点亮」标记。
     * 场景：打开一级快捷菜单时图标模糊已 fade-in；接着用户点二级（如"添加快捷方式"），
     * 一级弹窗走 close 动画 → 我们 fade-out；紧接着二级弹窗 open → 我们 fade-in 重新点亮。
     * 此时 fade-out 的延迟 remove 回调必须放弃移除，否则会把刚点亮的模糊层清掉（"秒清"）。
     */
    private static volatile boolean sIconBlurRelight = false;

    // 发热优化：OnPreDraw 监听器去重。
    // 关闭动画入口 hook 在同一次关闭过程中可能被调用多次（动画重启 / 多调用点 /
    // 一级→二级切换）。原实现每次都新建并注册一个 OnPreDrawListener 与 3 个
    // postDelayed，叠加后等于「每帧 ×N 次」回调。这里记录当前在跑的那一个。
    private static volatile android.view.ViewTreeObserver.OnPreDrawListener sRepressListener = null;
    private static volatile View sRepressHost = null;
    private static volatile long sRepressFallbackAt = 0L;
    private static volatile boolean sFadingOut = false;
    private static volatile int sSwallowCount = 0;

    // ---- ③ 图标模糊：实例级缓存（弱引用，避免钉住 View 子树）----
    private static final Map<View, Boolean> armed = new WeakHashMap<>();
    private static final Map<View, Integer> flagsCache = new WeakHashMap<>();
    /**
     * layer(或其动画宿主 View) → 正在跑的模糊动画。
     * 以 View 本身为 key（而非 identityHashCode 字符串），避免每次动画创建临时 String，
     * 也杜绝 hashCode 碰撞导致"误删他人动画"。用 WeakHashMap 保证 key(View) 可被回收，
     * 不会因动画条目残留而钉住 View 子树造成泄漏（配套访问均在同一锁内做复合操作）。
     */
    private static final Map<View, ValueAnimator> iconAnims =
            new java.util.WeakHashMap<>();

    // ---- Oplus 反射缓存（③ 的 applyIconBlurRadius 使用）----
    private static volatile boolean sOplusEffectResolved = false;
    private static volatile Class<?> sOplusEffectCls = null;
    private static volatile Method sSetBgRenderEffect = null;
    private static volatile Method sSetRenderEffectViewMethod = null;

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
                                ModuleLog.dv("FINISH", "flags=" + flags);
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
                            // 关闭动画：close 期间持续把面板背景压回半透明，
                            // 抵消 assignMarginsAndBackgrounds→newDrawable 刷白（C 类闪白）。
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
                                        // 发热优化：监听器去重（同一 host 已有在跑的 → 不再重复注册）。
                                        if (sRepressListener == null || sRepressHost != fhost) {
                                            // 关键：不用 postDelayed（会晚一帧 → 漏一帧实心）。
                                            // 改用 OnPreDrawListener：每次绘制前先把面板背景压回半透明，
                                            // 这样"白底"在真正上屏前就被改掉，杜绝"一帧实心"。
                                            try {
                                                final android.view.ViewTreeObserver vto = fhost.getViewTreeObserver();
                                                final android.view.ViewTreeObserver.OnPreDrawListener l =
                                                        new android.view.ViewTreeObserver.OnPreDrawListener() {
                                                    private int frames = 0;
                                                    @Override public boolean onPreDraw() {
                                                        try {
                                                            repressPanelAlpha(fhost);
                                                        } catch (Throwable ignored) {}
                                                        if (++frames >= 24) {   // 约 0.4s 后自动摘除（覆盖 330ms 关闭动画）
                                                            try {
                                                                android.view.ViewTreeObserver v = fhost.getViewTreeObserver();
                                                                if (v.isAlive()) v.removeOnPreDrawListener(this);
                                                            } catch (Throwable ignored) {}
                                                            if (sRepressListener == this) {
                                                                sRepressListener = null;
                                                                sRepressHost = null;
                                                            }
                                                        }
                                                        return true;
                                                    }
                                                };
                                                vto.addOnPreDrawListener(l);
                                                sRepressListener = l;
                                                sRepressHost = fhost;
                                            } catch (Throwable t) {
                                                ModuleLog.e("CARDBLUR", "add OnPreDraw failed", t);
                                            }
                                        }
                                        // 兜底：再压几次，防止 OnPreDraw 未触发的边界情况。
                                        // 发热优化：同一 host 300ms 内不重复排队（避免多次 close 叠加）。
                                        // （发热优化④：去掉 0L —— 与 OnPreDraw 首帧重复；仅保留错开的 3 次）
                                        final long repressNow = android.os.SystemClock.uptimeMillis();
                                        if (repressNow - sRepressFallbackAt > 300L) {
                                            sRepressFallbackAt = repressNow;
                                            for (final long d : new long[]{32L, 96L, 160L}) {
                                                fhost.postDelayed(new Runnable() {
                                                    @Override public void run() {
                                                        try {
                                                            repressPanelAlpha(fhost);
                                                        } catch (Throwable ignored) {}
                                                    }
                                                }, d);
                                            }
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
                                    ModuleLog.dv("ANIM", methodName + " flags=" + flags);
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
                                            // 标记「已重新点亮」：若此前一级弹窗刚触发过 fade-out，
                                            // 其延迟 remove 回调会因此放弃移除（保留模糊层）。
                                            sIconBlurRelight = true;
                                            Object iconAnim = createIconBlurAnimator(lt, 0.0f, blurRadius(), BLUR_DURATION);
                                            if (iconAnim instanceof ValueAnimator) {
                                                ((ValueAnimator) iconAnim).start();
                                                ModuleLog.dv("ICONANIM", "open anim -> started icon blur fade-in (sync)");
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
                                        ModuleLog.dv("PAUSE", "swallow pauseWindowBlur during shortcut (#" + sSwallowCount + ")");
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
    // ⑤ 快捷菜单卡片本体模糊（Oplus 背景渲染效果）
    // ------------------------------------------------------------------------
    // 目标：ArrowPopup.assignMarginsAndBackgrounds(ViewGroup, int) 会给"弹窗内容
    // 容器"设置纯色 GradientDrawable 背景（卡片本体）。我们在它执行之后：
    //   1) 把该容器背景整体调半透明（保留圆角 / Ripple / 形状）；
    //   2) 用 Oplus 私有 API setBackgroundRenderEffect 叠加背景模糊。
    // 隶属「Shortcut 实时模糊」总开关（FeatureFlags.SHORTCUT_BLUR）。
    // 只挂一次（WeakHashMap 去重由 applyCardBlurRec 的深度短路保证），
    // 只针对"直接挂背景的容器"（通常是弹窗内容根）。
    // ========================================================================

    /** 弹窗卡片本体模糊半径（FeatureFlags 热更），默认 64。 */
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

    /** 给快捷菜单卡片挂 Oplus 背景模糊：递归找"带背景的可见子视图"（真正的卡片面板），
     *  把其背景调半透明并叠加 Oplus 背景渲染效果（setBackgroundRenderEffect，模块原有 API）。
     *  返回实际挂上的视图数（0 = 没找到可挂目标）。 */
    /** 把面板（≥200×200 且带背景）的背景颜色 alpha 强制压成 CARD_PANEL_ALPHA。
     *  与 applyCardBlurRec 的 setAlpha 不同：这里直接 setColor 改"颜色本身"，
     *  可抵消 ArrowPopup 用 newDrawable() 从 constant state 复制出的不透明实例。
     *  性能：优先复用上次命中的面板 View（弱引用缓存，且与 root 绑定），避免每帧遍历整个子树。
     *  发热优化⑤：入口先做"alpha 已达标"短路，避免每帧白跑一遍 forceAlphaOnBg。 */
    private static volatile java.lang.ref.WeakReference<View> sPanelCache = null;
    private static volatile java.lang.ref.WeakReference<View> sPanelCacheRoot = null;
    private static void repressPanelAlpha(View v) {
        try {
            if (v == null) return;
            // 快速路径：同一 root 下上次命中的面板仍在树上且尺寸达标 → 直接压，不遍历。
            java.lang.ref.WeakReference<View> rootRef = sPanelCacheRoot;
            java.lang.ref.WeakReference<View> ref = sPanelCache;
            if (rootRef != null && rootRef.get() == v && ref != null) {
                View cached = ref.get();
                if (cached != null && cached.getParent() != null
                        && cached.getWidth() >= 200 && cached.getHeight() >= 200) {
                    if (isPanelAlphaOk(cached)) return;   // ← 已达标：整次调用直接结束
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
            // 已是目标 alpha。仍需处理"颜色本身不透明"的情况 → 检查 GradientDrawable 颜色。
            if (bg.getAlpha() == (FeatureFlags.CARD_PANEL_ALPHA & 0xFF)) {
                if (bg instanceof android.graphics.drawable.GradientDrawable) {
                    android.content.res.ColorStateList csl =
                            ((android.graphics.drawable.GradientDrawable) bg).getColor();
                    if (csl != null) {
                        int cur = csl.getDefaultColor();
                        if ((cur >>> 24) == (FeatureFlags.CARD_PANEL_ALPHA & 0xFF)) return;
                        int out = want | (cur & 0x00FFFFFF);
                        ((android.graphics.drawable.GradientDrawable) bg).setColor(out);
                        ModuleLog.dv("CARDBLUR", "  repress gdColor 0x" + Integer.toHexString(cur)
                                + " -> 0x" + Integer.toHexString(out));
                    }
                }
                return;
            }
            // alpha 字段不是目标值：直接 setAlpha（对普通 Drawable 有效）。
            bg.mutate().setAlpha(FeatureFlags.CARD_PANEL_ALPHA);
            ModuleLog.dv("CARDBLUR", "  repress alpha -> 0x"
                    + Integer.toHexString(FeatureFlags.CARD_PANEL_ALPHA)
                    + " cls=" + c.getClass().getSimpleName());
        } catch (Throwable ignored) {}
    }

    /** 发热优化⑤ 配套：判定面板背景 alpha 是否已达标（含 GradientDrawable 颜色 alpha）。
     *  纯读操作，不 mutate、不触发 invalidate，可安全用于每帧短路判断。 */
    private static boolean isPanelAlphaOk(View c) {
        try {
            android.graphics.drawable.Drawable bg = c.getBackground();
            if (bg == null) return false;
            int target = FeatureFlags.CARD_PANEL_ALPHA & 0xFF;
            if (bg instanceof android.graphics.drawable.GradientDrawable) {
                android.content.res.ColorStateList csl =
                        ((android.graphics.drawable.GradientDrawable) bg).getColor();
                if (csl != null) return (csl.getDefaultColor() >>> 24) == target;
            }
            return bg.getAlpha() == target;
        } catch (Throwable ignored) {
            return false;
        }
    }
    private static int applyCardBlur(ViewGroup card) {
        if (card == null) return 0;
        try {
            if (!FeatureFlags.SHORTCUT_BLUR) return 0;
            float r = cardBlurRadius();
            if (r <= 0.01f) return 0;
            int count = applyCardBlurRec(card, r, 0);
            ModuleLog.dv("CARDBLUR", "apply card blur r=" + r
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
            // "卡面板"判定：带背景 + 足够大（排除 84x84 之类图标项）。
            // 一级菜单面板实测 ≈812x923，其子图标约 84x84，故 200px 阈值可准确区分。
            boolean bigEnough = v.getWidth() >= 200 && v.getHeight() >= 200;
            if (hasBg && bigEnough) {
                // 命中"卡面板"：背景调半透明 + 叠加 Oplus 背景模糊（原方案）。
                // 发热优化：先做「已达标即跳过」判定，避免每次布局都 mutate + setBackground
                // （后者会触发 invalidate/requestLayout，可能再次进入本 hook）。
                if (!isPanelAlphaOk(v)) {
                    try {
                        android.graphics.drawable.Drawable mut = bg.mutate();
                        mut.setAlpha(FeatureFlags.CARD_PANEL_ALPHA);
                        v.setBackground(mut);
                    } catch (Throwable t) {
                        ModuleLog.dv("CARDBLUR", "  alpha failed: " + t);
                    }
                }
                // 发热优化：RenderEffect 只在首次（或半径变化）挂一次，避免重复建立渲染路径。
                int radiusKey = Float.floatToIntBits(r);
                Integer prev;
                synchronized (sCardBlurDone) { prev = sCardBlurDone.get(v); }
                if (prev == null || prev.intValue() != radiusKey) {
                    applyIconBlurRadius(v, r, true);
                    synchronized (sCardBlurDone) { sCardBlurDone.put(v, radiusKey); }
                }
                ModuleLog.dv("CARDBLUR", "  card panel depth=" + depth
                        + " cls=" + v.getClass().getSimpleName()
                        + " " + v.getWidth() + "x" + v.getHeight()
                        + " alpha=0x" + Integer.toHexString(FeatureFlags.CARD_PANEL_ALPHA)
                        + " bg=" + bg.getClass().getSimpleName());
                n++;
                // 命中面板后不再下探其子项（图标），避免误伤。
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
            ModuleLog.dv("CARDBLUR", "  rec err: " + t);
        }
        return n;
    }

    /** Hook showForIcon：快捷菜单弹出入口。基类 PopupContainerWithArrow.showForIcon(BubbleTextView)
     *  与 OEM 子类 OplusPopupContainerWithArrow.showForIcon(View) 都要装（实际走 OEM 版）。 */
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
                                ModuleLog.dv("CARDBLUR", mid + " ret cls=" + card.getClass().getName()
                                        + " " + card.getWidth() + "x" + card.getHeight()
                                        + " children=" + card.getChildCount()
                                        + " bg=" + (card.getBackground() == null ? "null"
                                                   : card.getBackground().getClass().getName()));
                                final ViewGroup fc = card;
                                fc.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            ModuleLog.dv("CARDBLUR", "post layout cls=" + fc.getClass().getName()
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

    // ========================================================================
    // ⑥ 二级（"更多功能"）菜单模糊：强制开启 COUI 官方背景模糊
    // ------------------------------------------------------------------------
    // 二级菜单由 com.coui.appcompat.poplist.COUIPopupListWindow 承载，其主子两个
    // RoundFrameLayout（mMainMenuWrapper / mSubMenuWrapper）各自持有一个
    // COUIBackgroundBlurBuilder；是否模糊由 builder.d 决定，写入点是
    // COUIPopupListWindow.setUseBackgroundBlur(boolean[, AnimLevel])。
    // 我们 hook 该方法，强制首个 boolean 参数为 true，使主子两级菜单都模糊。
    // 隶属「Shortcut 实时模糊」总开关（FeatureFlags.SHORTCUT_BLUR）。
    // ========================================================================
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
                                ModuleLog.dv("CARDBLUR", "setUseBackgroundBlur(" + orig + ", argc=" + argc
                                        + ") this=" + who);
                                if (FeatureFlags.SHORTCUT_BLUR && !orig) {
                                    ModuleLog.dv("CARDBLUR", "force coui blur ON (was " + orig + ", argc=" + argc + ")");
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

    // ========================================================================
    // ⑥ 二级（"更多功能"）菜单模糊：走一级同款 Oplus 私有模糊 API
    // ------------------------------------------------------------------------
    // 二级菜单窗口 com.android.launcher3.popup.MoreFunctionsPopupListWindow 继承
    // COUIPopupListWindow，其 mMainMenuWrapper / mSubMenuWrapper 均为
    // com.coui.appcompat.poplist.RoundFrameLayout（即一/二级菜单卡片面板）。
    // 系统默认 setUseBackgroundBlur(false)，故二级无模糊。
    // 本 hook 在窗口构造后，对两个 wrapper 复用一级同款 Oplus 私有模糊
    // （applyIconBlurRadius(v, r, useOplus=true)），实现一/二级视觉统一。
    // 隶属「Shortcut 实时模糊」总开关（FeatureFlags.SHORTCUT_BLUR）。
    // ========================================================================
    // ========================================================================
    // ⑧ 一级快捷菜单"背景发白/退出恢复实心"根治（资源色 alpha）
    // ------------------------------------------------------------------------
    // 【历史说明】曾用两条"源头降透"思路，实测均未命中（hook 装上但零调用），
    // 已移除，避免无效 hook 增加开销与干扰：
    //   · ColorUtils.removeOpacity  —— 实测零调用（isLightWeightOS/isAnimationLevel 分支未命中）
    //   · ArrowPopup.lambda$assignMarginsAndBackgrounds$0 / getExtractedColor
    //                              —— 实测零调用（疑被 R8 内联/重命名）
    //   · Context/Resources.getColor（按资源 ID 0x7f06077f 过滤）—— 高频出口，已移除
    // 现役有效修复见下方 ⑫（hookArrowRoundedBg，命中 mRoundedTop/Bottom）与
    //       ⑬（hookPopupColorPrimaryAttr，命中 popupColorPrimary 属性色）。
    // ========================================================================
    // ========================================================================
    // ⑫ 终极修复：ArrowPopup.mRoundedTop/mRoundedBottom 背景"降透"
    // ------------------------------------------------------------------------
    // 调用栈实证（探针）：
    //   GD.setColor(0xffffffff) ← ArrowPopup.<init>:36 / :39
    // 源码实证（ArrowPopup.smali）：
    //   .line 35-36  mRoundedTop  = new GradientDrawable(); setColor(0xffffffff)
    //   .line 38-39  mRoundedBottom = new GradientDrawable(); setColor(0xffffffff)
    //   .line 3205+  assignMarginsAndBackgrounds 里：
    //        child.setBackground(mRoundedTop.getConstantState().newDrawable())
    //     ★ newDrawable() 复制的是"原始 constant state"，会丢弃我们后面设的 alpha，
    //       于是面板每次 assign 都被刷回"纯白不透明" → 打开/关闭闪白。
    // 修法：构造完成后，反射取 mRoundedTop/mRoundedBottom，把颜色改成
    //   (CARD_PANEL_ALPHA<<24)|(原RGB)，即"纯白但半透明"。此后 newDrawable()
    //   复制出来的新实例天然带该 alpha → 任何时机都不会再变实心。
    // 影响面：仅 OplusPopupContainerWithArrow / 其他 ArrowPopup 子类（都是快捷菜单）。
    // ========================================================================
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
        // 兜底：即使构造 hook 没装上，也直接 hook GD.setColor，
        // 仅当"调用者就是 ArrowPopup 构造函数"时改值（用栈判定，命中率低但安全）。
        return n;
    }
    /** 反射读 ArrowPopup 的 mRoundedTop/Bottom 字段，把颜色 alpha 换成 CARD_PANEL_ALPHA。 */
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
            ModuleLog.dv("CARDBLUR", "rounded " + field + " -> 0x" + Integer.toHexString(out));
        } catch (Throwable t) {
            ModuleLog.e("CARDBLUR", "recolorRounded(" + field + ") failed", t);
        }
    }
    // ========================================================================
    // ⑬ 从"主题属性 popupColorPrimary"源头降透（快捷菜单背景色的最上游）
    // ------------------------------------------------------------------------
    // 源码实证（ArrowPopup.smali 构造）：
    //   sget v6, R$attr;->popupColorPrimary   // = 0x7f04085a
    //   getAttrColor(ctx, v6)                 // ← 面板背景色（mBackgroundColor）
    //   mBackgroundColor = v6; mArrowColor = v6
    //   mRoundedTop.setColor(v6); mRoundedBottom.setColor(v6)
    // 用户指出 popupColorPrimary 指向的正是 res 资源 launcher_deep_shortcut_bg_color。
    // 本 hook 在"属性色解析"出口把 alpha 换成 CARD_PANEL_ALPHA —— 比"构造后
    // recolor"更早、更彻底：mBackgroundColor 与 mRoundedTop/Bottom 同时被治。
    // 仅当 attr == popupColorPrimary 才改，其它属性色零影响。
    // ========================================================================
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
                        // 发热优化⑫：Themes.getAttrColor 是 Launcher3 的全局工具方法（高频）。
                        // 先做 attr 判定再取值，绝大多数调用（attr != ATTR_POPUP_COLOR_PRIMARY）
                        // 走最短路：一次 getArg + 一次 int 比较，不做装箱、不碰 result。
                        Object attr = chain.getArg(1);
                        boolean isTarget = (attr instanceof Integer)
                                && ((Integer) attr).intValue() == ATTR_POPUP_COLOR_PRIMARY;
                        if (!isTarget) return chain.proceed();
                        Object result = chain.proceed();
                        try {
                            if (result instanceof Integer) {
                                int c = (Integer) result;
                                int out = ((FeatureFlags.CARD_PANEL_ALPHA & 0xFF) << 24) | (c & 0x00FFFFFF);
                                ModuleLog.dv("CARDBLUR", "popupColorPrimary in=0x" + Integer.toHexString(c)
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
    // ========================================================================
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
                            // ⚠️ MoreFunctionsPopupListWindow 继承 PopupWindow，不是 View！
                            // wrapper（mMainMenuWrapper/mSubMenuWrapper）才是 View。
                            // 用 getContentView()（PopupWindow API）拿根 View，post 后再挂模糊。
                            ModuleLog.dv("CARDBLUR", "mfCtor ret self="
                                    + (self == null ? "null" : self.getClass().getName()));
                            if (self != null) {
                                View anchor = resolvePopupRoot(self);
                                if (anchor != null) {
                                    // 弹出动画期间就要有模糊 → 不能等 120ms。
                                    // 发热优化：3 次调度（0/32/80ms）收敛为 2 次（0/80ms）。
                                    // 原第 2 次（32ms）几乎总是重复设置同一组参数——多一次
                                    // setBackground + invalidate 触发重绘；80ms 那次已足以覆盖
                                    // 「wrapper 尚未 inflate 完」的时序。
                                    final long[] delays = {0L, 80L};
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

    // 从 PopupWindow 子类（MoreFunctionsPopupListWindow）拿一个可用的 View 作为 post 载体。
    // 优先 getContentView()；拿不到则尝试从 wrapper 字段取。
    private static View resolvePopupRoot(Object self) {
        // 发热优化：改用 Reflect.method（带缓存 + 负缓存），避免每次弹窗重新 getMethod。
        try {
            Method gcv = Reflect.method(self.getClass(), "getContentView", 0);
            if (gcv == null) {
                try { gcv = self.getClass().getMethod("getContentView"); } catch (Throwable ignore) {}
            }
            if (gcv != null) {
                Object c = gcv.invoke(self);
                if (c instanceof View) return (View) c;
            }
        } catch (Throwable ignored) {}
        // ② 反射取 mMainMenuWrapper / mSubMenuWrapper
        for (String fn : new String[]{"mMainMenuWrapper", "mSubMenuWrapper"}) {
            try {
                Field f = Reflect.field(self.getClass(), fn);
                if (f == null) continue;
                Object v = f.get(self);
                if (v instanceof View) return (View) v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // 对 MoreFunctionsPopupListWindow 的 mMainMenuWrapper / mSubMenuWrapper
    // （均为 RoundFrameLayout）挂 Oplus 私有背景模糊（com.oplus.view.ViewRootManager）。
    // 一级卡片走 setBackgroundRenderEffect（原方案），二级走 ViewRootManager（本方案），
    // 两者底层机制不同：前者模糊视图自身渲染层（与图标模糊同层、可叠加），
    // 后者由 SurfaceFlinger 合成真背景模糊。二级沿用一级的半透明背景观感（alpha 0xCC）。
    private static void applySecondaryBlur(Object window) {
        if (!FeatureFlags.SHORTCUT_BLUR) return;
        // 二级可用独立半径（CARD_SECONDARY_BLUR_RADIUS）；<=0 回退一级半径。
        float radius = FeatureFlags.CARD_SECONDARY_BLUR_RADIUS;
        if (radius <= 0.01f) radius = cardBlurRadius();
        if (radius > 200f) radius = 200f;
        int hit = 0;
        for (String fn : new String[]{"mMainMenuWrapper", "mSubMenuWrapper"}) {
            try {
                // 发热优化：改用 Reflect.field（统一字段缓存 + 负缓存），
                // 原实现每次弹窗都要沿继承链 getDeclaredField + setAccessible。
                Field f = Reflect.field(window.getClass(), fn);
                if (f == null) continue;
                Object v = f.get(window);
                if (!(v instanceof View)) continue;
                View vv = (View) v;
                ModuleLog.dv("CARDBLUR", "secondary " + fn + " cls=" + vv.getClass().getName()
                        + " " + vv.getWidth() + "x" + vv.getHeight()
                        + " bg=" + (vv.getBackground() == null ? "null" : vv.getBackground().getClass().getName()));
                // 走 Oplus 私有模糊（ViewRootManager）。
                OplusCardBlur.apply(vv, radius);
                hit++;
            } catch (Throwable t) {
                ModuleLog.e("CARDBLUR", "secondary " + fn + " err", t);
            }
        }
        ModuleLog.dv("CARDBLUR", "apply secondary oplus blur r=" + radius + " hit=" + hit);
    }

    // ========================================================================
    // ③ 图标模糊：layer 生命周期 / 状态 / 装配
    // ========================================================================
    private static View ensureIconBlurLayer(View pbv) {
        try {
            if (pbv == null) return null;
            Object parent = pbv.getParent();
            if (!(parent instanceof ViewGroup)) {
                ModuleLog.dv("ICONBLUR", "pbv parent not ViewGroup: " + (parent == null ? "null" : parent.getClass().getName()));
                return null;
            }
            ViewGroup vg = (ViewGroup) parent;
            View existing = sIconBlurLayer;
            if (existing != null && existing.getParent() == vg) {
                ModuleLog.dv("ICONBLUR", "icon blur layer reused (already attached)");
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
            ModuleLog.dv("ICONBLUR", "icon blur layer added to " + vg.getClass().getSimpleName()
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
            ModuleLog.dv("ICONANIM", "already fading out, skip duplicate fade-out");
            return;
        }
        sFadingOut = true;
        sPopupSeq++;
        sIconAnimStartedSeq = -1L;
        // 新一轮 fade-out：清「重新点亮」标记。若本次 fade-out 尚未完成时
        // 又发生了一次 open（会把它置 true），则延迟回调放弃移除。
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
            // 发热优化：补上与 createIconBlurAnimator 一致的 0.5f 节流。
            // 原实现每帧无条件 applyIconBlurRadius → 每帧 new RenderEffect + 反射 invoke
            // （330ms ≈ 20 帧），是关闭弹窗时最集中的一次功耗尖峰；同一功能的 fade-in
            // 路径本来就有该节流，这里属实现不对称。
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                private float last = Float.NaN;
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    try {
                        float r = (Float) a.getAnimatedValue();
                        if (!Float.isNaN(last) && Math.abs(r - last) < 0.5f) return;
                        last = r;
                        applyIconBlurRadius(layer, r, true);
                    } catch (Throwable ignore) {}
                }
            });
            synchronized (iconAnims) {
                iconAnims.put(layer, va);
            }
            va.start();
            ModuleLog.dv("ICONANIM", "fade-out " + blurRadius() + "->0.0 dur=" + BLUR_DURATION);
            layer.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        removeAnimation(layer);
                        if (sIconBlurLayer != layer) {
                            ModuleLog.dv("ICONANIM", "fade-out done, layer already replaced, skip remove");
                            sFadingOut = false;
                            return;
                        }
                        // 关键：fade-out 期间又发生了一次 open（二级弹窗等），
                        // 说明这不是"真正关闭"，而是"切换到下一个弹窗"。
                        // 此时保留模糊层（它已被新 open 重新 fade-in），不要移除。
                        if (sIconBlurRelight) {
                            ModuleLog.dv("ICONANIM", "fade-out done but relit (next popup opened), skip remove");
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
            ModuleLog.dv("ICONBLUR", "icon blur layer removed (+anim state cleared)");
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
                    ModuleLog.dv("PAUSE", "window CLOSE (delayed reset, exit anim done)");
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
            // 发热优化⑥：本方法是「每次弹窗」的热路径，4 条 d() 日志改为 dv()（仅在 VERBOSE 档输出）
            ModuleLog.dv("LIVE", "armBlurForView id=" + mid);
            if (sFadingOut) {
                sFadingOut = false;
                ModuleLog.dv("ICONANIM", "enter during fade-out, aborted fade-out state");
            }
            sShortcutBlurActive = true;
            ModuleLog.dv("PAUSE", "window OPEN (armBlurForView id=" + mid + ")");
            final int flags = resolveBlurFlags(view);
            ModuleLog.dv("LIVE", "flags=" + flags + " (static=" + ((flags & F_STATIC) != 0)
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
                    ModuleLog.dv("ICONANIM", "arm icon (defer anim to open, seq=" + seq + ")");
                } else {
                    ModuleLog.dv("ICONANIM", "same popup round, skip re-arm (seq=" + seq + ")");
                }
            }
            if ((flags & F_WALL) == 0) return;
            fv.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Object launcher = getLauncherQuietly(fv);
                        if (launcher == null) {
                            ModuleLog.dv("DEPTH", "launcher null, skip retry");
                            return;
                        }
                        Object dc = Reflect.call(launcher, "getDepthController");
                        if (dc == null) {
                            ModuleLog.dv("DEPTH", "depthController null");
                            return;
                        }
                        Object g = Reflect.call(dc, "getCurrentBlur");
                        float v = (g instanceof Float) ? (Float) g : -1f;
                        ModuleLog.dv("DEPTH", "currentBlur=" + v);
                        if (v <= 0.05f) {
                            boolean ok = setDepthBlur(fv, 1.0f);
                            ModuleLog.dv("DEPTH", "fallback setBlur=1 ok=" + ok);
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

    // 发热优化：RenderEffect 复用。
    // createBlurEffect 会创建 native 层对象；同一半径会被反复使用（每次布局同一个 r、
    // 卡片一级/二级共用同一配置值、动画相邻帧落在同一取值）。按「完全相同半径」缓存，
    // 容量小、零视觉风险（不做量化，避免模糊半径跳变）。
    private static final Map<Integer, RenderEffect> sEffectCache = new java.util.HashMap<>();

    private static RenderEffect effectFor(float radius) {
        int key = Float.floatToIntBits(radius);
        synchronized (sEffectCache) {
            RenderEffect hit = sEffectCache.get(key);
            if (hit != null) return hit;
        }
        RenderEffect e = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR);
        synchronized (sEffectCache) {
            if (sEffectCache.size() >= 32) sEffectCache.clear();
            sEffectCache.put(key, e);
        }
        return e;
    }

    // 发热优化：卡片模糊「已应用」标记（View → 上次应用时的半径位模式）。
    // 原实现每次 applyCardBlurRec 命中面板都会 mutate 背景 + setBackground + 重挂
    // RenderEffect；而本方法被挂在布局阶段方法（assignMarginsAndBackgrounds）上，
    // 弹窗期间可能被多次触发，形成「改背景 → 重布局 → 再改背景」的重复开销。
    private static final Map<View, Integer> sCardBlurDone =
            new java.util.WeakHashMap<>();

    private static void applyIconBlurRadius(View view, float radius, boolean useOplus) {
        if (view == null) return;
        try {
            RenderEffect effect = effectFor(radius);
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
            ModuleLog.dv("CLEAR", "staticLayers wall=" + wall + " drag=" + drag);
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
            ModuleLog.dv("ICONANIM", "create icon animator " + from + "->" + to + " dur=" + duration);
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
            ModuleLog.dv("DEPTH", "BLUR field not static nor instance-accessible: " + t);
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
